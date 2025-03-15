package main

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteBuffer
import kotlin.concurrent.thread

data class Frame(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val timestamp: Double
)

interface DecoderListener {
    fun onFrameDecoded(frame: Frame)
    fun onDecodingFinished()
    fun onDecodingError(error: Exception)
}

class H264Decoder(
    private val filePath: String,
    private val startTime: Double = 0.0,
    private val listener: DecoderListener? = null
) {
    @Volatile
    private var shouldStop: Boolean = false
    private var frameCallback: ((Frame) -> Unit)? = null
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var totalDuration: Double = 0.0
    private var videoStreamIndex: Int = -1
    private var decoderThread: Thread? = null
    private var isDecoding: Boolean = false

    fun getVideoDuration(): Double {
        if (totalDuration <= 0.0) {
            totalDuration = fetchVideoDuration()
        }
        return totalDuration
    }

    fun getVideoSize(): Pair<Int, Int> {
        return Pair(frameWidth, frameHeight)
    }

    fun setFrameCallback(callback: (Frame) -> Unit) {
        this.frameCallback = callback
    }

    fun stop() {
        shouldStop = true
    }

    fun startAsync() {
        if (isDecoding) return
        isDecoding = true
        shouldStop = false
        decoderThread = thread(start = true) {
            try {
                decode()
                listener?.onDecodingFinished()
            } catch (e: Exception) {
                listener?.onDecodingError(e)
            } finally {
                isDecoding = false
            }
        }
    }

    fun join() {
        decoderThread?.join()
    }

    fun decode() {
        avformat_network_init()
        var formatContext: AVFormatContext? = null
        var codecContext: AVCodecContext? = null
        var packet: AVPacket? = null
        var frame: AVFrame? = null
        var rgbFrame: AVFrame? = null
        var swsContext: SwsContext? = null
        var buffer: BytePointer? = null

        try {
            formatContext = avformat_alloc_context()
                ?: throw RuntimeException("Failed to allocate format context")

            if (avformat_open_input(formatContext, filePath, null, null) != 0) {
                throw RuntimeException("Could not open file: $filePath")
            }
            if (avformat_find_stream_info(formatContext, null as PointerPointer<BytePointer>?) < 0) {
                throw RuntimeException("Could not find stream info")
            }

            totalDuration = if (formatContext.duration() != AV_NOPTS_VALUE)
                formatContext.duration() / 1000000.0
            else 0.0

            videoStreamIndex = -1
            for (i in 0 until formatContext.nb_streams()) {
                if (formatContext.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    videoStreamIndex = i
                    break
                }
            }
            if (videoStreamIndex == -1) {
                throw RuntimeException("No video stream found")
            }

            val codecParams = formatContext.streams(videoStreamIndex).codecpar()
            val codec = avcodec_find_decoder(codecParams.codec_id())
                ?: throw RuntimeException("Codec not found")
            codecContext = avcodec_alloc_context3(codec)
                ?: throw RuntimeException("Failed to allocate codec context")

            if (avcodec_parameters_to_context(codecContext, codecParams) < 0) {
                throw RuntimeException("Failed to copy codec parameters")
            }
            if (avcodec_open2(codecContext, codec, null as PointerPointer<BytePointer>?) < 0) {
                throw RuntimeException("Failed to open codec")
            }

            frameWidth = codecContext.width()
            frameHeight = codecContext.height()

            if (startTime > 0.0) {
                val seekTimestamp = (startTime * AV_TIME_BASE).toLong()
                if (av_seek_frame(formatContext, videoStreamIndex, seekTimestamp, AVSEEK_FLAG_BACKWARD) < 0) {
                    println("Warning: seek failed")
                } else {
                    avcodec_flush_buffers(codecContext)
                }
            }

            packet = av_packet_alloc() ?: throw RuntimeException("Failed to allocate packet")
            frame = av_frame_alloc() ?: throw RuntimeException("Failed to allocate frame")
            rgbFrame = av_frame_alloc() ?: throw RuntimeException("Failed to allocate rgbFrame")

            swsContext = sws_getContext(
                frameWidth, frameHeight, codecContext.pix_fmt(),
                frameWidth, frameHeight, AV_PIX_FMT_RGB24,
                SWS_BILINEAR, null, null, null as DoublePointer?
            ) ?: throw RuntimeException("Failed to create swsContext")

            val bufferSize = av_image_get_buffer_size(AV_PIX_FMT_RGB24, frameWidth, frameHeight, 1)
            buffer = BytePointer(av_malloc(bufferSize.toLong()))
            buffer.capacity(bufferSize.toLong())
            av_image_fill_arrays(
                rgbFrame.data(), rgbFrame.linesize(), buffer,
                AV_PIX_FMT_RGB24, frameWidth, frameHeight, 1
            )

            val timeBase = formatContext.streams(videoStreamIndex).time_base()

            while (!shouldStop && av_read_frame(formatContext, packet) >= 0) {
                if (packet.stream_index() == videoStreamIndex) {
                    if (avcodec_send_packet(codecContext, packet) == 0) {
                        while (!shouldStop && avcodec_receive_frame(codecContext, frame) == 0) {
                            sws_scale(
                                swsContext,
                                frame.data(), frame.linesize(),
                                0, frameHeight,
                                rgbFrame.data(), rgbFrame.linesize()
                            )
                            val pts = if (frame.pts() != AV_NOPTS_VALUE) frame.pts() else 0L
                            val timestamp = pts * av_q2d(timeBase)
                            val dataPointer: BytePointer = rgbFrame.data(0)
                            dataPointer.capacity(bufferSize.toLong())
                            val byteBuffer: ByteBuffer = dataPointer.asByteBuffer()

                            val frameCopy = ByteBuffer.allocateDirect(byteBuffer.remaining())
                            frameCopy.put(byteBuffer)
                            frameCopy.flip()

                            val decodedFrame = Frame(frameCopy, frameWidth, frameHeight, timestamp)

                            frameCallback?.invoke(decodedFrame)
                            listener?.onFrameDecoded(decodedFrame)
                        }
                    }
                }
                av_packet_unref(packet)
            }
        } finally {
            if (frame != null) av_frame_free(frame)
            if (rgbFrame != null) av_frame_free(rgbFrame)
            if (packet != null) av_packet_free(packet)
            if (codecContext != null) avcodec_free_context(codecContext)
            if (formatContext != null) avformat_close_input(formatContext)
            if (swsContext != null) sws_freeContext(swsContext)
        }
    }

    private fun fetchVideoDuration(): Double {
        avformat_network_init()
        val formatContext = avformat_alloc_context()
            ?: throw RuntimeException("Failed to allocate format context")

        if (avformat_open_input(formatContext, filePath, null, null) != 0) {
            throw RuntimeException("Could not open file: $filePath")
        }
        if (avformat_find_stream_info(formatContext, null as PointerPointer<*>?) < 0) {
            throw RuntimeException("Could not find stream info")
        }
        val duration = if (formatContext.duration() != AV_NOPTS_VALUE)
            formatContext.duration() / 1000000.0
        else 0.0
        avformat_close_input(formatContext)
        return duration
    }

    companion object {
        fun getVideoDuration(filePath: String): Double {
            avformat_network_init()
            val formatContext = avformat_alloc_context()
                ?: throw RuntimeException("Failed to allocate format context")

            if (avformat_open_input(formatContext, filePath, null, null) != 0) {
                throw RuntimeException("Could not open file: $filePath")
            }
            if (avformat_find_stream_info(formatContext, null as PointerPointer<*>?) < 0) {
                throw RuntimeException("Could not find stream info")
            }
            val duration = if (formatContext.duration() != AV_NOPTS_VALUE)
                formatContext.duration() / 1000000.0
            else 0.0
            avformat_close_input(formatContext)
            return duration
        }
    }
}