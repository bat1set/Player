package main

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_network_init
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGB24
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_get_pix_fmt_name
import org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays
import org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.PointerPointer
import kotlin.concurrent.thread

interface DecoderListener {
    fun onFrameDecoded(frame: VideoFrame)
    fun onDecodingFinished()
    fun onDecodingError(error: Exception)
}

class FFmpegVideoDecoder(
    private val filePath: String,
    private val startTime: Double = 0.0,
    private val listener: DecoderListener? = null,
    private val bufferPool: ByteBufferPool = ByteBufferPool(),
    private val nativeYuv: Boolean = false,
    private val debugVideo: Boolean = false
) {
    @Volatile private var shouldStop = false
    @Volatile private var isDecoding = false
    private var frameCallback: ((VideoFrame) -> Unit)? = null
    private var decoderThread: Thread? = null
    private var firstFrameDebugLogged = false

    private val info = readVideoInfo(filePath)

    fun getVideoDuration(): Double = info.duration
    fun getVideoSize(): Pair<Int, Int> = info.width to info.height
    fun setFrameCallback(callback: (VideoFrame) -> Unit) {
        frameCallback = callback
    }

    fun startAsync() {
        if (isDecoding) return
        shouldStop = false
        isDecoding = true
        decoderThread = thread(name = "ffmpeg-video-decoder") {
            try {
                decodeLoop()
                listener?.onDecodingFinished()
            } catch (e: Exception) {
                listener?.onDecodingError(e)
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
        val fmtCtx = openFormatContext(filePath)
        var codecCtx: AVCodecContext? = null
        val pkt = av_packet_alloc() ?: throw RuntimeException("Failed to allocate packet")
        val decoded = av_frame_alloc() ?: throw RuntimeException("Failed to allocate frame")
        val rgb = av_frame_alloc() ?: throw RuntimeException("Failed to allocate rgb frame")
        var rgbBuffer: BytePointer? = null
        var swsCtx: org.bytedeco.ffmpeg.swscale.SwsContext? = null

        try {
            codecCtx = createCodecContext(fmtCtx, info.videoStreamIndex, open = true)
            val stream = fmtCtx.streams(info.videoStreamIndex)
            val timeBase = stream.time_base()
            val timeBaseSeconds = av_q2d(timeBase)
            val streamStartTimestamp = streamStartTimestamp(stream.start_time())
            val streamStartSeconds = streamStartTimestamp * timeBaseSeconds

            if (startTime > 0.0) {
                val seekTimestamp = secondsToStreamTimestamp(startTime, timeBaseSeconds, streamStartTimestamp)
                if (av_seek_frame(fmtCtx, info.videoStreamIndex, seekTimestamp, AVSEEK_FLAG_BACKWARD) >= 0) {
                    avcodec_flush_buffers(codecCtx)
                }
            }

            Log.info(
                "Video decoder pixel format: ${pixelFormatName(codecCtx.pix_fmt())}; " +
                    "nativeYuv=$nativeYuv; output=${if (nativeYuv && isNativePixelFormat(codecCtx.pix_fmt())) "YUV planes" else "RGB24"}"
            )

            if (!nativeYuv || !isNativePixelFormat(codecCtx.pix_fmt())) {
                swsCtx = sws_getContext(
                    info.width, info.height, codecCtx.pix_fmt(),
                    info.width, info.height, AV_PIX_FMT_RGB24,
                    SWS_BILINEAR, null, null, null as DoublePointer?
                ) ?: throw RuntimeException("Failed to create SwsContext")

                val rgbBufferSize = av_image_get_buffer_size(AV_PIX_FMT_RGB24, info.width, info.height, 1)
                rgbBuffer = BytePointer(av_malloc(rgbBufferSize.toLong())).capacity(rgbBufferSize.toLong())
                av_image_fill_arrays(
                    rgb.data(), rgb.linesize(), rgbBuffer,
                    AV_PIX_FMT_RGB24, info.width, info.height, 1
                )
            }

            while (!shouldStop && av_read_frame(fmtCtx, pkt) >= 0) {
                try {
                    if (pkt.stream_index() == info.videoStreamIndex && avcodec_send_packet(codecCtx, pkt) == 0) {
                        receiveFrames(codecCtx, decoded, rgb, swsCtx, rgbBuffer, timeBaseSeconds, streamStartSeconds)
                    }
                } finally {
                    av_packet_unref(pkt)
                }
            }

            avcodec_send_packet(codecCtx, null)
            receiveFrames(codecCtx, decoded, rgb, swsCtx, rgbBuffer, timeBaseSeconds, streamStartSeconds)
        } finally {
            swsCtx?.let { sws_freeContext(it) }
            rgbBuffer?.let { av_free(it) }
            av_frame_free(decoded)
            av_frame_free(rgb)
            av_packet_free(pkt)
            codecCtx?.let { avcodec_free_context(it) }
            avformat_close_input(fmtCtx)
        }
    }

    private fun receiveFrames(
        codecCtx: AVCodecContext,
        decoded: org.bytedeco.ffmpeg.avutil.AVFrame,
        rgb: org.bytedeco.ffmpeg.avutil.AVFrame,
        swsCtx: org.bytedeco.ffmpeg.swscale.SwsContext?,
        rgbBuffer: BytePointer?,
        timeBaseSeconds: Double,
        streamStartSeconds: Double
    ) {
        while (!shouldStop && avcodec_receive_frame(codecCtx, decoded) == 0) {
            val timestamp = frameTimestampSeconds(decoded, timeBaseSeconds, streamStartSeconds)
            if (timestamp + 0.001 < startTime) {
                continue
            }

            val frame = when (codecCtx.pix_fmt()) {
                AV_PIX_FMT_YUV420P -> if (nativeYuv) {
                    copyYuv420pFrame(decoded, timestamp)
                } else {
                    copyRgbFallbackFrame(decoded, rgb, swsCtx, rgbBuffer, timestamp)
                }
                AV_PIX_FMT_NV12 -> if (nativeYuv) {
                    copyNv12Frame(decoded, timestamp)
                } else {
                    copyRgbFallbackFrame(decoded, rgb, swsCtx, rgbBuffer, timestamp)
                }
                else -> copyRgbFallbackFrame(decoded, rgb, swsCtx, rgbBuffer, timestamp)
            }
            logFirstFrameDebug(frame, codecCtx.pix_fmt(), decoded)
            emitFrame(frame)
        }
    }

    private fun copyYuv420pFrame(
        frame: org.bytedeco.ffmpeg.avutil.AVFrame,
        timestamp: Double
    ): VideoFrame {
        val chromaWidth = (info.width + 1) / 2
        val chromaHeight = (info.height + 1) / 2
        val y = bufferPool.acquire(info.width * info.height)
        val u = bufferPool.acquire(chromaWidth * chromaHeight)
        val v = bufferPool.acquire(chromaWidth * chromaHeight)

        copyPlane(frame.data(0), frame.linesize(0), info.width, info.height, y)
        copyPlane(frame.data(1), frame.linesize(1), chromaWidth, chromaHeight, u)
        copyPlane(frame.data(2), frame.linesize(2), chromaWidth, chromaHeight, v)

        return Yuv420pVideoFrame(y, u, v, info.width, info.height, timestamp, bufferPool::release)
    }

    private fun copyNv12Frame(
        frame: org.bytedeco.ffmpeg.avutil.AVFrame,
        timestamp: Double
    ): VideoFrame {
        val chromaHeight = (info.height + 1) / 2
        val y = bufferPool.acquire(info.width * info.height)
        val uv = bufferPool.acquire(info.width * chromaHeight)

        copyPlane(frame.data(0), frame.linesize(0), info.width, info.height, y)
        copyPlane(frame.data(1), frame.linesize(1), info.width, chromaHeight, uv)

        return Nv12VideoFrame(y, uv, info.width, info.height, timestamp, bufferPool::release)
    }

    private fun copyRgbFallbackFrame(
        decoded: org.bytedeco.ffmpeg.avutil.AVFrame,
        rgb: org.bytedeco.ffmpeg.avutil.AVFrame,
        swsCtx: org.bytedeco.ffmpeg.swscale.SwsContext?,
        rgbBuffer: BytePointer?,
        timestamp: Double
    ): VideoFrame {
        val scaler = swsCtx ?: throw RuntimeException("Missing SwsContext for RGB fallback")
        val source = rgbBuffer ?: throw RuntimeException("Missing RGB fallback buffer")
        sws_scale(
            scaler, decoded.data(), decoded.linesize(),
            0, info.height, rgb.data(), rgb.linesize()
        )

        val size = info.width * info.height * 3
        val pooled = bufferPool.acquire(size)
        copyPointer(source, size, pooled)
        return RgbVideoFrame(pooled, info.width, info.height, timestamp, bufferPool::release)
    }

    private fun emitFrame(frame: VideoFrame) {
        try {
            val callback = frameCallback
            if (callback != null) {
                callback(frame)
            } else {
                listener?.onFrameDecoded(frame)
            }
        } catch (e: Exception) {
            frame.close()
            throw e
        }
    }

    private fun logFirstFrameDebug(
        frame: VideoFrame,
        pixelFormat: Int,
        decoded: org.bytedeco.ffmpeg.avutil.AVFrame
    ) {
        if (!debugVideo || firstFrameDebugLogged) return
        firstFrameDebugLogged = true
        Log.info(
            "First decoded video frame: type=${frame::class.simpleName}, " +
                "format=${pixelFormatName(pixelFormat)}, timestamp=%.3fs, size=%dx%d, linesize=[%d,%d,%d]".format(
                    frame.timestamp,
                    frame.width,
                    frame.height,
                    decoded.linesize(0),
                    decoded.linesize(1),
                    decoded.linesize(2)
                )
        )
        when (frame) {
            is RgbVideoFrame -> Log.info("RGB sample: ${bufferStats(frame.rgb)}")
            is Yuv420pVideoFrame -> {
                Log.info("Y plane sample: ${bufferStats(frame.y)}")
                Log.info("U plane sample: ${bufferStats(frame.u)}")
                Log.info("V plane sample: ${bufferStats(frame.v)}")
            }
            is Nv12VideoFrame -> {
                Log.info("Y plane sample: ${bufferStats(frame.y)}")
                Log.info("UV plane sample: ${bufferStats(frame.uv)}")
            }
        }
    }

    private fun bufferStats(buffer: java.nio.ByteBuffer, sampleSize: Int = 4096): String {
        val duplicate = buffer.asReadOnlyBuffer()
        duplicate.position(0)
        val count = minOf(sampleSize, duplicate.remaining())
        if (count <= 0) return "empty"
        var min = 255
        var max = 0
        var sum = 0L
        repeat(count) {
            val value = duplicate.get().toInt() and 0xff
            if (value < min) min = value
            if (value > max) max = value
            sum += value
        }
        val avg = sum.toDouble() / count
        return "sample=$count min=$min max=$max avg=%.2f".format(avg)
    }

    private fun copyPlane(
        src: BytePointer,
        lineSize: Int,
        width: Int,
        height: Int,
        target: java.nio.ByteBuffer
    ) {
        target.clear()
        repeat(height) { row ->
            val offset = row * lineSize
            val rowPtr = BytePointer(src).position(offset.toLong()).capacity((offset + width).toLong())
            val rowBuffer = rowPtr.asByteBuffer()
            rowBuffer.limit(width)
            target.put(rowBuffer)
        }
        target.flip()
    }

    private fun copyPointer(src: BytePointer, size: Int, target: java.nio.ByteBuffer) {
        target.clear()
        val source = BytePointer(src).position(0).capacity(size.toLong()).asByteBuffer()
        source.limit(size)
        target.put(source)
        target.flip()
    }

    companion object {
        fun getVideoDuration(path: String): Double = readVideoInfo(path).duration

        fun getVideoInfo(path: String): Triple<Int, Int, Double> {
            val info = readVideoInfo(path)
            return Triple(info.width, info.height, info.duration)
        }

        fun readVideoInfo(path: String): VideoInfo {
            val fmtCtx = openFormatContext(path)
            var codecCtx: AVCodecContext? = null
            try {
                val videoStreamIndex = findStream(fmtCtx, AVMEDIA_TYPE_VIDEO)
                val audioStreamIndex = findStream(fmtCtx, AVMEDIA_TYPE_AUDIO, required = false)
                codecCtx = createCodecContext(fmtCtx, videoStreamIndex, open = false)
                val duration = if (fmtCtx.duration() != AV_NOPTS_VALUE) {
                    fmtCtx.duration() / 1_000_000.0
                } else {
                    0.0
                }

                return VideoInfo(
                    width = codecCtx.width(),
                    height = codecCtx.height(),
                    duration = duration,
                    videoStreamIndex = videoStreamIndex,
                    audioStreamIndex = audioStreamIndex
                )
            } finally {
                codecCtx?.let { avcodec_free_context(it) }
                avformat_close_input(fmtCtx)
            }
        }

        fun openFormatContext(path: String): AVFormatContext {
            avformat_network_init()
            val ctx = avformat_alloc_context()
                ?: throw RuntimeException("Failed to allocate format context")
            if (avformat_open_input(ctx, path, null, null) != 0) {
                throw RuntimeException("Could not open file: $path")
            }
            if (avformat_find_stream_info(ctx, null as PointerPointer<BytePointer>?) < 0) {
                avformat_close_input(ctx)
                throw RuntimeException("Could not find stream info")
            }
            return ctx
        }

        fun findStream(ctx: AVFormatContext, mediaType: Int, required: Boolean = true): Int {
            for (i in 0 until ctx.nb_streams()) {
                if (ctx.streams(i).codecpar().codec_type() == mediaType) {
                    return i
                }
            }
            if (required) {
                throw RuntimeException("No stream found for media type $mediaType")
            }
            return -1
        }

        fun createCodecContext(
            ctx: AVFormatContext,
            streamIndex: Int,
            open: Boolean
        ): AVCodecContext {
            val params = ctx.streams(streamIndex).codecpar()
            val codec = avcodec_find_decoder(params.codec_id())
                ?: throw RuntimeException("Codec not found")
            val codecCtx = avcodec_alloc_context3(codec)
                ?: throw RuntimeException("Failed to allocate codec context")
            if (avcodec_parameters_to_context(codecCtx, params) < 0) {
                avcodec_free_context(codecCtx)
                throw RuntimeException("Failed to copy codec parameters")
            }
            if (open && avcodec_open2(codecCtx, codec, null as PointerPointer<BytePointer>?) < 0) {
                avcodec_free_context(codecCtx)
                throw RuntimeException("Failed to open codec")
            }
            return codecCtx
        }

        fun secondsToStreamTimestamp(seconds: Double, timeBaseSeconds: Double): Long {
            if (timeBaseSeconds <= 0.0) return 0L
            return (seconds / timeBaseSeconds).toLong()
        }

        fun secondsToStreamTimestamp(seconds: Double, timeBaseSeconds: Double, streamStartTimestamp: Long): Long {
            if (timeBaseSeconds <= 0.0) return streamStartTimestamp
            return streamStartTimestamp + (seconds / timeBaseSeconds).toLong()
        }

        fun frameTimestampSeconds(
            frame: org.bytedeco.ffmpeg.avutil.AVFrame,
            timeBaseSeconds: Double,
            streamStartSeconds: Double = 0.0
        ): Double {
            val pts = when {
                frame.best_effort_timestamp() != AV_NOPTS_VALUE -> frame.best_effort_timestamp()
                frame.pts() != AV_NOPTS_VALUE -> frame.pts()
                else -> 0L
            }
            return (pts * timeBaseSeconds - streamStartSeconds).coerceAtLeast(0.0)
        }

        fun streamStartTimestamp(startTime: Long): Long {
            return if (startTime == AV_NOPTS_VALUE) 0L else startTime
        }

        fun isNativePixelFormat(pixelFormat: Int): Boolean {
            return pixelFormat == AV_PIX_FMT_YUV420P || pixelFormat == AV_PIX_FMT_NV12
        }

        fun isPacketKeyFrame(flags: Int): Boolean {
            return flags and AV_PKT_FLAG_KEY != 0
        }

        fun pixelFormatName(pixelFormat: Int): String {
            return av_get_pix_fmt_name(pixelFormat)?.string ?: "unknown($pixelFormat)"
        }
    }
}

typealias H264Decoder = FFmpegVideoDecoder
