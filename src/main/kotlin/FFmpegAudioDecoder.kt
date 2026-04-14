package main

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_ROUND_UP
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_check
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import org.bytedeco.ffmpeg.global.avutil.av_rescale_rnd
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_delay
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import kotlin.concurrent.thread

interface AudioDecoderListener {
    fun onAudioDecoded(chunk: AudioChunk)
    fun onAudioFinished()
    fun onAudioError(error: Exception)
}

class FFmpegAudioDecoder(
    private val filePath: String,
    private val startTime: Double,
    private val listener: AudioDecoderListener,
    private val bufferPool: ByteBufferPool = ByteBufferPool(),
    private val outputSampleRate: Int = 48_000,
    private val outputChannels: Int = 2
) {
    @Volatile private var shouldStop = false
    @Volatile private var isDecoding = false
    private var decoderThread: Thread? = null
    private val info = FFmpegVideoDecoder.readVideoInfo(filePath)

    fun hasAudio(): Boolean = info.hasAudio

    fun startAsync() {
        if (!info.hasAudio || isDecoding) return
        shouldStop = false
        isDecoding = true
        decoderThread = thread(name = "ffmpeg-audio-decoder") {
            try {
                decodeLoop()
                listener.onAudioFinished()
            } catch (e: Exception) {
                listener.onAudioError(e)
            } finally {
                isDecoding = false
            }
        }
    }

    fun stop() {
        shouldStop = true
    }

    fun join(timeoutMillis: Long = 1_000) {
        decoderThread?.join(timeoutMillis)
    }

    private fun decodeLoop() {
        val fmtCtx = FFmpegVideoDecoder.openFormatContext(filePath)
        var codecCtx: AVCodecContext? = null
        var swrCtx: SwrContext? = null
        val pkt = av_packet_alloc() ?: throw RuntimeException("Failed to allocate audio packet")
        val frame = av_frame_alloc() ?: throw RuntimeException("Failed to allocate audio frame")

        try {
            codecCtx = FFmpegVideoDecoder.createCodecContext(fmtCtx, info.audioStreamIndex, open = true)
            swrCtx = createResampler(codecCtx)
            val stream = fmtCtx.streams(info.audioStreamIndex)
            val timeBaseSeconds = av_q2d(stream.time_base())
            val streamStartTimestamp = FFmpegVideoDecoder.streamStartTimestamp(stream.start_time())
            val streamStartSeconds = streamStartTimestamp * timeBaseSeconds

            if (startTime > 0.0) {
                val seekTimestamp = FFmpegVideoDecoder.secondsToStreamTimestamp(
                    startTime,
                    timeBaseSeconds,
                    streamStartTimestamp
                )
                if (av_seek_frame(fmtCtx, info.audioStreamIndex, seekTimestamp, AVSEEK_FLAG_BACKWARD) >= 0) {
                    avcodec_flush_buffers(codecCtx)
                }
            }

            while (!shouldStop && av_read_frame(fmtCtx, pkt) >= 0) {
                try {
                    if (pkt.stream_index() == info.audioStreamIndex && avcodec_send_packet(codecCtx, pkt) == 0) {
                        receiveAudio(codecCtx, swrCtx, frame, timeBaseSeconds, streamStartSeconds)
                    }
                } finally {
                    av_packet_unref(pkt)
                }
            }

            avcodec_send_packet(codecCtx, null)
            receiveAudio(codecCtx, swrCtx, frame, timeBaseSeconds, streamStartSeconds)
        } finally {
            swrCtx?.let { swr_free(it) }
            av_frame_free(frame)
            av_packet_free(pkt)
            codecCtx?.let { avcodec_free_context(it) }
            avformat_close_input(fmtCtx)
        }
    }

    private fun createResampler(codecCtx: AVCodecContext): SwrContext {
        val inputLayout = codecCtx.ch_layout()
        if (av_channel_layout_check(inputLayout) == 0) {
            av_channel_layout_default(inputLayout, inputLayout.nb_channels().coerceAtLeast(1))
        }
        val outputLayout = AVChannelLayout()
        av_channel_layout_default(outputLayout, outputChannels)

        val ctx = swr_alloc() ?: throw RuntimeException("Failed to allocate SwrContext")
        if (swr_alloc_set_opts2(
            ctx,
            outputLayout,
            AV_SAMPLE_FMT_S16,
            outputSampleRate,
            inputLayout,
            codecCtx.sample_fmt(),
            codecCtx.sample_rate(),
            0,
            null
        ) < 0) {
            swr_free(ctx)
            throw RuntimeException("Failed to configure SwrContext")
        }

        if (swr_init(ctx) < 0) {
            swr_free(ctx)
            throw RuntimeException("Failed to initialize SwrContext")
        }
        return ctx
    }

    private fun receiveAudio(
        codecCtx: AVCodecContext,
        swrCtx: SwrContext,
        frame: org.bytedeco.ffmpeg.avutil.AVFrame,
        timeBaseSeconds: Double,
        streamStartSeconds: Double
    ) {
        while (!shouldStop && avcodec_receive_frame(codecCtx, frame) == 0) {
            val timestamp = audioTimestampSeconds(frame, timeBaseSeconds, streamStartSeconds)
            if (timestamp + 0.001 < startTime) {
                continue
            }

            val outSamples = av_rescale_rnd(
                swr_get_delay(swrCtx, codecCtx.sample_rate().toLong()) + frame.nb_samples(),
                outputSampleRate.toLong(),
                codecCtx.sample_rate().toLong(),
                AV_ROUND_UP
            ).toInt()

            val maxOutputSize = outSamples * outputChannels * Short.SIZE_BYTES
            val buffer = bufferPool.acquire(maxOutputSize)
            val outPointer = BytePointer(buffer)
            val outPointers = PointerPointer<BytePointer>(1).put(0, outPointer)

            val convertedSamples = swr_convert(
                swrCtx,
                outPointers,
                outSamples,
                frame.extended_data(),
                frame.nb_samples()
            )

            if (convertedSamples <= 0) {
                bufferPool.release(buffer)
                continue
            }

            val bytes = convertedSamples * outputChannels * Short.SIZE_BYTES
            buffer.position(0)
            buffer.limit(bytes)
            val duration = convertedSamples.toDouble() / outputSampleRate

            listener.onAudioDecoded(
                AudioChunk(
                    pcm = buffer,
                    sampleRate = outputSampleRate,
                    channels = outputChannels,
                    timestamp = timestamp,
                    duration = duration,
                    release = bufferPool::release
                )
            )
        }
    }

    private fun audioTimestampSeconds(
        frame: org.bytedeco.ffmpeg.avutil.AVFrame,
        timeBaseSeconds: Double,
        streamStartSeconds: Double
    ): Double {
        val pts = when {
            frame.best_effort_timestamp() != AV_NOPTS_VALUE -> frame.best_effort_timestamp()
            frame.pts() != AV_NOPTS_VALUE -> frame.pts()
            else -> 0L
        }
        return (pts * timeBaseSeconds - streamStartSeconds).coerceAtLeast(0.0)
    }
}
