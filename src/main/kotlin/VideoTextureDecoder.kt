package main

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL13.GL_TEXTURE0
import org.lwjgl.opengl.GL13.glActiveTexture
import org.lwjgl.BufferUtils
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class VideoTextureDecoder(
    private val filePath: String,
    private val startTime: Double = 0.0,
    private val maxBufferedFrames: Int = 2,
    private val textureFiltering: Int = GL_LINEAR
){
    private var textureIds = IntArray(2)

    private val currentTextureIndex = AtomicInteger(0)

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

    fun initialize() {
        if (isInitialized.getAndSet(true)) return

        decoder = H264Decoder(filePath, startTime, object : DecoderListener {
            override fun onFrameDecoded(frame: Frame) {
                if (!frameQueue.offer(frame)) {
                    frameQueue.poll()
                    frameQueue.offer(frame)
                }
            }

            override fun onDecodingFinished() {
                isPlaying.set(false)
            }

            override fun onDecodingError(error: Exception) {
                println("Ошибка декодирования: ${error.message}")
                isPlaying.set(false)
            }
        })

        val size = decoder?.getVideoSize() ?: Pair(0, 0)
        videoWidth = size.first
        videoHeight = size.second
        duration = decoder?.getVideoDuration() ?: 0.0

        initializeTextures()

        isLoaded.set(true)
    }

    private fun initializeTextures() {
        textureIds = IntArray(2) { glGenTextures() }

        for (textureId in textureIds) {
            glBindTexture(GL_TEXTURE_2D, textureId)

            // параметры текстуры
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, textureFiltering)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, textureFiltering)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)


            val emptyBuffer = BufferUtils.createByteBuffer(videoWidth * videoHeight * 3)
            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGB, videoWidth, videoHeight,
                0, GL_RGB, GL_UNSIGNED_BYTE, emptyBuffer
            )
        }

        glBindTexture(GL_TEXTURE_2D, 0)
    }

    fun play() {
        if (!isInitialized.get()) {
            initialize()
        }

        if (!isPlaying.getAndSet(true)) {
            decoder?.startAsync()
        }
    }

    fun stop() {
        if (isPlaying.getAndSet(false)) {
            decoder?.stop()
        }
    }

    fun dispose() {
        stop()

        for (textureId in textureIds) {
            glDeleteTextures(textureId)
        }

        isInitialized.set(false)
        isLoaded.set(false)
    }

    fun update(desiredTime: Double): Boolean {
        if (!isPlaying.get() || frameQueue.isEmpty()) return false
        val frame = frameQueue.peek() ?: return false
        if (frame.timestamp <= desiredTime) {
            frameQueue.poll()
            val nextTextureIndex = (currentTextureIndex.get() + 1) % 2
            val textureId = textureIds[nextTextureIndex]
            textureLock.withLock {
                glBindTexture(GL_TEXTURE_2D, textureId)
                val buffer = frame.buffer
                buffer.position(0)
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, frame.width, frame.height, 0, GL_RGB, GL_UNSIGNED_BYTE, buffer)
                glBindTexture(GL_TEXTURE_2D, 0)
                currentTextureIndex.set(nextTextureIndex)
            }
            currentTime = frame.timestamp
            return true
        }
        return false
    }
    /**
     * Привязать текущую текстуру к указанному текстурному юниту.
     * Этот метод должен вызываться в потоке рендеринга OpenGL.
     *
     * @param textureUnit текстурный юнит (по умолчанию GL_TEXTURE0)
     * @return ID текстуры, которая была привязана
     */
    fun bindTexture(textureUnit: Int = GL_TEXTURE0): Int {
        glActiveTexture(textureUnit)
        val textureId = textureIds[currentTextureIndex.get()]
        glBindTexture(GL_TEXTURE_2D, textureId)
        return textureId
    }

    /**
     * Проверяет, доступен ли новый кадр видео.
     *
     * @return true, если новый кадр доступен, false - если нет
     */
    fun isFrameAvailable(): Boolean {
        return !frameQueue.isEmpty()
    }

    /**
     * Отвязать текстуру
     */
    fun unbindTexture() {
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    /**
     * Получить длительность видео в секундах
     */
    fun getDuration(): Double = duration

    /**
     * Получить текущее время воспроизведения в секундах
     */
    fun getCurrentTime(): Double = currentTime

    /**
     * Получить размеры видео
     */
    fun getVideoSize(): Pair<Int, Int> = Pair(videoWidth, videoHeight)

    /**
     * Проверить, загружено ли видео
     */
    fun isLoaded(): Boolean = isLoaded.get()

    /**
     * Проверить, воспроизводится ли видео
     */
    fun isPlaying(): Boolean = isPlaying.get()

}