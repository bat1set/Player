package main

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class VideoTextureDecoder(
    private val filePath: String,
    private val startTime: Double = 0.0,
    private val maxBufferedFrames: Int = 10,
    private val textureFiltering: Int = GL_LINEAR
) {
    private var textureIds = IntArray(2)
    private val currentTextureIndex = AtomicInteger(0)
    private var hasNewFrame = AtomicBoolean(false)

    private var decoder: H264Decoder? = null
    private var isInitialized = AtomicBoolean(false)
    private var isPlaying = AtomicBoolean(false)
    private var isLoaded = AtomicBoolean(false)

    private var videoWidth = 0
    private var videoHeight = 0

    private val frameQueue: BlockingQueue<Frame> = LinkedBlockingQueue(maxBufferedFrames)
    private val textureLock = ReentrantLock()

    private var duration: Double = 0.0
    private var currentTime: Double = 0.0
    private var lastFrameTime: Double = -1.0

    fun initialize() {
        if (isInitialized.getAndSet(true)) return

        try {
            val (width, height, videoDuration) = H264Decoder.getVideoInfo(filePath)
            videoWidth = width
            videoHeight = height
            duration = videoDuration

            if (videoWidth <= 0 || videoHeight <= 0) {
                throw RuntimeException("Invalid video dimensions: ${videoWidth}x${videoHeight}")
            }

            println("Video dimensions: ${videoWidth}x${videoHeight}, duration: $duration")

            initializeTextures()

            decoder = H264Decoder(filePath, startTime, object : DecoderListener {
                override fun onFrameDecoded(frame: Frame) {
                    while (!frameQueue.offer(frame)) {
                        frameQueue.poll()
                    }
                    hasNewFrame.set(true)
                }

                override fun onDecodingFinished() {
                    println("Декодирование завершено")
                    isPlaying.set(false)
                }

                override fun onDecodingError(error: Exception) {
                    println("Ошибка декодирования: ${error.message}")
                    error.printStackTrace()
                    isPlaying.set(false)
                }
            })

            isLoaded.set(true)
            println("VideoTextureDecoder инициализирован успешно")

        } catch (e: Exception) {
            println("Ошибка инициализации VideoTextureDecoder: ${e.message}")
            e.printStackTrace()
            isInitialized.set(false)
            throw e
        }
    }

    private fun initializeTextures() {
        textureIds = IntArray(2)
        for (i in textureIds.indices) {
            textureIds[i] = glGenTextures()
        }

        for (textureId in textureIds) {
            glBindTexture(GL_TEXTURE_2D, textureId)

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, textureFiltering)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, textureFiltering)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGB, videoWidth, videoHeight,
                0, GL_RGB, GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?
            )
        }

        glBindTexture(GL_TEXTURE_2D, 0)
        println("Текстуры инициализированы: ${textureIds.contentToString()}")
    }

    fun play() {
        if (!isInitialized.get()) {
            initialize()
        }

        if (!isPlaying.getAndSet(true)) {
            println("Запуск декодера...")
            decoder?.startAsync()
        }
    }

    fun stop() {
        if (isPlaying.getAndSet(false)) {
            println("Остановка декодера...")
            decoder?.stop()
        }
    }

    fun dispose() {
        stop()

        decoder?.join()

        textureLock.withLock {
            for (textureId in textureIds) {
                if (textureId != 0) {
                    glDeleteTextures(textureId)
                }
            }
        }

        frameQueue.clear()
        isInitialized.set(false)
        isLoaded.set(false)
        println("VideoTextureDecoder освобожден")
    }

    fun update(desiredTime: Double): Boolean {
        if (!isPlaying.get()) return false

        var frameUpdated = false

        while (true) {
            val frame = frameQueue.peek() ?: break

            if (frame.timestamp > desiredTime + 0.033) {
                break
            }

            frameQueue.poll()

            updateTexture(frame)
            currentTime = frame.timestamp
            lastFrameTime = frame.timestamp
            frameUpdated = true

            if (kotlin.math.abs(frame.timestamp - desiredTime) < 0.1) {
                break
            }
        }

        return frameUpdated
    }

    private fun updateTexture(frame: Frame) {
        try {
            val nextTextureIndex = (currentTextureIndex.get() + 1) % 2
            val textureId = textureIds[nextTextureIndex]

            textureLock.withLock {
                glBindTexture(GL_TEXTURE_2D, textureId)

                frame.buffer.position(0)

                glTexSubImage2D(
                    GL_TEXTURE_2D, 0, 0, 0,
                    frame.width, frame.height,
                    GL_RGB, GL_UNSIGNED_BYTE, frame.buffer
                )

                glBindTexture(GL_TEXTURE_2D, 0)

                currentTextureIndex.set(nextTextureIndex)
                hasNewFrame.set(false)
            }
        } catch (e: Exception) {
            println("Ошибка обновления текстуры: ${e.message}")
            e.printStackTrace()
        }
    }

    fun bindTexture(textureUnit: Int = GL_TEXTURE0): Int {
        glActiveTexture(textureUnit)
        val textureId = textureIds[currentTextureIndex.get()]
        glBindTexture(GL_TEXTURE_2D, textureId)
        return textureId
    }

    fun isFrameAvailable(): Boolean {
        return !frameQueue.isEmpty() || hasNewFrame.get()
    }

    fun unbindTexture() {
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    fun getDuration(): Double = duration
    fun getCurrentTime(): Double = currentTime
    fun getVideoSize(): Pair<Int, Int> = Pair(videoWidth, videoHeight)
    fun isLoaded(): Boolean = isLoaded.get()
    fun isPlaying(): Boolean = isPlaying.get()
    fun getQueueSize(): Int = frameQueue.size
    fun getLastFrameTime(): Double = lastFrameTime
}