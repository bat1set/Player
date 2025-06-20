package main

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
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
    @Volatile private var shouldStop = false
    private var frameCallback: ((Frame) -> Unit)? = null

    private var frameWidth = 0
    private var frameHeight = 0
    private var totalDuration = 0.0
    private var videoStreamIndex = -1

    private var decoderThread: Thread? = null
    private var isDecoding = false

    init {
        val info = readVideoInfo(filePath)
        frameWidth = info.first
        frameHeight = info.second
        totalDuration = info.third
        videoStreamIndex = info.fourth
    }

    private fun openFormatContext(): AVFormatContext {
        avformat_network_init()
        val ctx = avformat_alloc_context()
            ?: throw RuntimeException("Failed to allocate format context")
        if (avformat_open_input(ctx, filePath, null, null) != 0)
            throw RuntimeException("Could not open file: $filePath")
        if (avformat_find_stream_info(ctx, null as PointerPointer<BytePointer>?) < 0)
            throw RuntimeException("Could not find stream info")
        return ctx
    }

    private fun findVideoStream(ctx: AVFormatContext): Int {
        for (i in 0 until ctx.nb_streams()) {
            if (ctx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                return i
            }
        }
        throw RuntimeException("No video stream found")
    }

    private fun createCodecContext(
        ctx: AVFormatContext, streamIndex: Int
    ): AVCodecContext {
        val params = ctx.streams(streamIndex).codecpar()
        val codec = avcodec_find_decoder(params.codec_id())
            ?: throw RuntimeException("Codec not found")
        val cctx = avcodec_alloc_context3(codec)
            ?: throw RuntimeException("Failed to allocate codec context")
        if (avcodec_parameters_to_context(cctx, params) < 0)
            throw RuntimeException("Failed to copy codec parameters")
        if (avcodec_open2(cctx, codec, null as PointerPointer<BytePointer>?) < 0)
            throw RuntimeException("Failed to open codec")
        return cctx
    }

    private fun readVideoInfo(path: String): Quadruple<Int, Int, Double, Int> {
        val fmtCtx = openFormatContext()
        try {
            val duration = if (fmtCtx.duration() != AV_NOPTS_VALUE)
                fmtCtx.duration() / 1_000_000.0 else 0.0
            val streamIndex = findVideoStream(fmtCtx)
            val codecCtx = createCodecContext(fmtCtx, streamIndex)
            val w = codecCtx.width()
            val h = codecCtx.height()
            avcodec_free_context(codecCtx)
            return Quadruple(w, h, duration, streamIndex)
        } finally {
            avformat_close_input(fmtCtx)
        }
    }

    fun getVideoDuration(): Double = totalDuration
    fun getVideoSize(): Pair<Int, Int> = frameWidth to frameHeight
    fun setFrameCallback(callback: (Frame) -> Unit) { frameCallback = callback }
    fun stop() { shouldStop = true }

    fun startAsync() {
        if (isDecoding) return
        isDecoding = true
        shouldStop = false
        decoderThread = thread {
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

    fun join() { decoderThread?.join() }

    private fun decodeLoop() {
        val fmtCtx = openFormatContext()
        val cctx = createCodecContext(fmtCtx, videoStreamIndex)
        if (startTime > 0.0) {
            val ts = (startTime * AV_TIME_BASE).toLong()
            if (av_seek_frame(fmtCtx, videoStreamIndex, ts, AVSEEK_FLAG_BACKWARD) >= 0) {
                avcodec_flush_buffers(cctx)
            }
        }

        val pkt = av_packet_alloc() ?: throw RuntimeException("Failed to allocate packet")
        val frame = av_frame_alloc() ?: throw RuntimeException("Failed to allocate frame")
        val rgb = av_frame_alloc() ?: throw RuntimeException("Failed to allocate rgb frame")

        val swsCtx = sws_getContext(
            frameWidth, frameHeight, cctx.pix_fmt(),
            frameWidth, frameHeight, AV_PIX_FMT_RGB24,
            SWS_BILINEAR, null, null, null as DoublePointer?
        ) ?: throw RuntimeException("Failed to create SwsContext")

        val bufSize = av_image_get_buffer_size(AV_PIX_FMT_RGB24, frameWidth, frameHeight, 1)
        val buf = BytePointer(av_malloc(bufSize.toLong())).capacity(bufSize.toLong())
        av_image_fill_arrays(rgb.data(), rgb.linesize(), buf,
            AV_PIX_FMT_RGB24, frameWidth, frameHeight, 1)

        val timeBase = fmtCtx.streams(videoStreamIndex).time_base()

        while (!shouldStop && av_read_frame(fmtCtx, pkt) >= 0) {
            if (pkt.stream_index() == videoStreamIndex) {
                if (avcodec_send_packet(cctx, pkt) == 0) {
                    while (!shouldStop && avcodec_receive_frame(cctx, frame) == 0) {
                        sws_scale(
                            swsCtx, frame.data(), frame.linesize(),
                            0, frameHeight, rgb.data(), rgb.linesize()
                        )
                        val pts = frame.pts().takeIf { it != AV_NOPTS_VALUE } ?: 0L
                        val timestamp = pts * av_q2d(timeBase)
                        val src = rgb.data(0).capacity(bufSize.toLong())
                        val bb = src.asByteBuffer()
                        val copy = ByteBuffer.allocateDirect(bb.remaining()).also {
                            it.put(bb).flip()
                        }
                        val f = Frame(copy, frameWidth, frameHeight, timestamp)
                        frameCallback?.invoke(f)
                        listener?.onFrameDecoded(f)
                    }
                }
            }
            av_packet_unref(pkt)
        }

        av_frame_free(frame)
        av_frame_free(rgb)
        av_packet_free(pkt)
        sws_freeContext(swsCtx)
        avcodec_free_context(cctx)
        avformat_close_input(fmtCtx)
    }

    companion object {
        fun getVideoDuration(path: String): Double {
            return readStaticInfo(path).third
        }

        fun getVideoInfo(path: String): Triple<Int, Int, Double> {
            val (w, h, d, _) = readStaticInfo(path)
            return Triple(w, h, d)
        }

        private fun readStaticInfo(path: String): Quadruple<Int, Int, Double, Int> {
            val fmtCtx = run {
                avformat_network_init()
                val c = avformat_alloc_context()
                    ?: throw RuntimeException("Failed to alloc format context")
                if (avformat_open_input(c, path, null, null) != 0)
                    throw RuntimeException("Could not open file: $path")
                if (avformat_find_stream_info(c, null as PointerPointer<BytePointer>?) < 0)
                    throw RuntimeException("Could not find stream info")
                c
            }
            try {
                val duration = if (fmtCtx.duration() != AV_NOPTS_VALUE)
                    fmtCtx.duration() / 1_000_000.0 else 0.0
                val streamIndex = findVideoStreamStatic(fmtCtx)
                val codecCtx = createCodecContextStatic(fmtCtx, streamIndex)
                val w = codecCtx.width()
                val h = codecCtx.height()
                avcodec_free_context(codecCtx)
                return Quadruple(w, h, duration, streamIndex)
            } finally {
                avformat_close_input(fmtCtx)
            }
        }

        private fun findVideoStreamStatic(ctx: AVFormatContext): Int {
            for (i in 0 until ctx.nb_streams()) {
                if (ctx.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    return i
                }
            }
            throw RuntimeException("No video stream found")
        }

        private fun createCodecContextStatic(
            ctx: AVFormatContext, streamIndex: Int
        ): AVCodecContext {
            val params = ctx.streams(streamIndex).codecpar()
            val codec = avcodec_find_decoder(params.codec_id())
                ?: throw RuntimeException("Codec not found")
            val cctx = avcodec_alloc_context3(codec)
                ?: throw RuntimeException("Failed to allocate codec context")
            if (avcodec_parameters_to_context(cctx, params) < 0)
                throw RuntimeException("Failed to copy codec parameters")
            return cctx
        }
    }
}

data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
