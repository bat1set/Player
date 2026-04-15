package main

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_D
import org.lwjgl.glfw.GLFW.GLFW_KEY_F
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_R
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetCursorPos
import org.lwjgl.glfw.GLFW.glfwGetKey
import org.lwjgl.glfw.GLFW.glfwGetMouseButton
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowShouldClose
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_FLOAT
import org.lwjgl.opengl.GL11.GL_MODELVIEW
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_PROJECTION
import org.lwjgl.opengl.GL11.GL_QUADS
import org.lwjgl.opengl.GL11.GL_RENDERER
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_VERTEX_ARRAY
import org.lwjgl.opengl.GL11.GL_VENDOR
import org.lwjgl.opengl.GL11.GL_VERSION
import org.lwjgl.opengl.GL11.glBegin
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glColor3f
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glDisableClientState
import org.lwjgl.opengl.GL11.glDrawArrays
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glEnableClientState
import org.lwjgl.opengl.GL11.glEnd
import org.lwjgl.opengl.GL11.glLoadIdentity
import org.lwjgl.opengl.GL11.glMatrixMode
import org.lwjgl.opengl.GL11.glOrtho
import org.lwjgl.opengl.GL11.glPopMatrix
import org.lwjgl.opengl.GL11.glPushMatrix
import org.lwjgl.opengl.GL11.glGetString
import org.lwjgl.opengl.GL11.glVertex2d
import org.lwjgl.opengl.GL11.glVertexPointer
import org.lwjgl.stb.STBEasyFont
import org.lwjgl.system.MemoryUtil.NULL
import java.nio.DoubleBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class VideoPlayer(private val options: PlayerOptions) {
    private var window: Long = NULL
    private val renderer = VideoFrameRenderer(options.debugVideo)
    private var audioDevice: OpenALAudioDevice? = null
    private var segmentCache: VideoSegmentCache? = null
    private var segmentIndex: SegmentIndex? = null

    private val videoQueue = ArrayBlockingQueue<VideoFrame>(90)
    private val audioQueue = ArrayBlockingQueue<AudioChunk>(96)
    private var pendingAudioChunk: AudioChunk? = null

    private val videoBufferPool = ByteBufferPool(maxPooledBuffers = 160)
    private val audioBufferPool = ByteBufferPool(maxPooledBuffers = 128)

    private var videoDecoder: FFmpegVideoDecoder? = null
    private var audioDecoder: FFmpegAudioDecoder? = null
    private val decoderGeneration = AtomicInteger(0)
    private val isSeekInProgress = AtomicBoolean(false)
    private val playback = PlaybackStateController()

    private val info = FFmpegVideoDecoder.readVideoInfo(options.filePath)
    private val totalDuration = info.duration
    private val fallbackClock = PlaybackClock()

    private var playbackSpeed = 1.0
    private var currentTime = 0.0
    private var lastFrameTimestamp = -1.0
    private var showFps = false
    private var debugOverlayVisible = false
    private var droppedLateFrames = 0
    private var lastAudioClock = 0.0
    private var lastAvDrift = 0.0
    private var lastSyncDebugLogTime = 0.0
    private var bufferingStartedNanos = System.nanoTime()

    private var fps = 0.0
    private var frameCount = 0
    private var fpsTimer = 0.0
    private var lastLoopTime = System.nanoTime() / 1_000_000_000.0
    private val keyState = HashMap<Int, Boolean>()

    private var timelineVisible = true
    private var timelineLastInteraction = 0.0
    private val timelineTimeout = 3.0
    private var timelineMouseWasPressed = false
    private val paused: Boolean get() = playback.state == PlaybackState.PAUSED

    fun run() {
        Log.info("Starting player")
        Log.info("Video: ${options.filePath}")
        Log.info(
            "Info: ${info.width}x${info.height}, duration=%.3fs, videoStream=%d, audioStream=%d".format(
                info.duration,
                info.videoStreamIndex,
                info.audioStreamIndex
            )
        )
        Log.info("Cache: enabled=${options.cacheEnabled}, dir=${options.cacheDir.toAbsolutePath()}, limit=${options.cacheSizeMb}MB")
        Log.info("Video output mode: ${if (options.nativeYuv) "native YUV shader (experimental)" else "RGB fallback"}")
        Log.info("Diagnostics: debugSync=${options.debugSync}, debugVideo=${options.debugVideo}")
        initGLFW()
        initOpenGL()
        initCache()
        initAudio()
        beginBuffering(0.0, playWhenReady = true, reason = "startup")
        startDecoders(0.0)
        preloadFirstFrame()
        lastLoopTime = System.nanoTime() / 1_000_000_000.0
        loop()
        cleanup()
    }

    private fun initGLFW() {
        if (!glfwInit()) throw RuntimeException("Failed to initialize GLFW")
        window = glfwCreateWindow(1280, 720, "Direct FFmpeg Player", NULL, NULL)
        if (window == NULL) throw RuntimeException("Failed to create window")
        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        GL.createCapabilities()
    }

    private fun initOpenGL() {
        if (options.debugVideo) {
            Log.info(
                "OpenGL: version=${glGetString(GL_VERSION)}, " +
                    "renderer=${glGetString(GL_RENDERER)}, vendor=${glGetString(GL_VENDOR)}"
            )
        }
        renderer.initialize()
    }

    private fun initCache() {
        if (!options.cacheEnabled) return
        segmentCache = VideoSegmentCache(
            cacheDir = options.cacheDir,
            maxSizeBytes = options.cacheSizeMb * 1024L * 1024L
        )
        segmentIndex = try {
            segmentCache?.loadOrBuild(options.filePath)?.also {
                Log.info("Segment cache ready: ${it.segments.size} keyframe segments")
            }
        } catch (e: Exception) {
            Log.error("Segment cache disabled for this run: ${e.message}", e)
            null
        }
    }

    private fun initAudio() {
        if (!info.hasAudio) return
        audioDevice = try {
            OpenALAudioDevice().also {
                it.initialize(options.audioDevice)
                Log.info("OpenAL audio initialized")
            }
        } catch (e: Exception) {
            Log.error("Audio disabled: ${e.message}", e)
            null
        }
    }

    private fun startDecoders(seekTime: Double) {
        val generation = decoderGeneration.incrementAndGet()
        lastFrameTimestamp = seekTime
        Log.info("Starting decoders at %.3fs (generation %d)".format(seekTime, generation))
        logSelectedSegment(seekTime)

        videoDecoder = FFmpegVideoDecoder(
            filePath = options.filePath,
            startTime = seekTime,
            listener = object : DecoderListener {
                override fun onFrameDecoded(frame: VideoFrame) {
                    if (generation != decoderGeneration.get()) {
                        frame.close()
                        return
                    }
                    offerVideoFrame(frame, generation)
                }

                override fun onDecodingFinished() = Unit

                override fun onDecodingError(error: Exception) {
                    Log.error("Video decode error: ${error.message}", error)
                }
            },
            bufferPool = videoBufferPool,
            nativeYuv = options.nativeYuv,
            debugVideo = options.debugVideo
        ).also { it.startAsync() }

        if (info.hasAudio && audioDevice != null) {
            audioDecoder = FFmpegAudioDecoder(
                filePath = options.filePath,
                startTime = seekTime,
                listener = object : AudioDecoderListener {
                    override fun onAudioDecoded(chunk: AudioChunk) {
                        if (generation != decoderGeneration.get()) {
                            chunk.close()
                            return
                        }
                        offerAudioChunk(chunk, generation)
                    }

                    override fun onAudioFinished() = Unit

                    override fun onAudioError(error: Exception) {
                        Log.error("Audio decode error: ${error.message}", error)
                    }
                },
                bufferPool = audioBufferPool
            ).also { it.startAsync() }
        }

        segmentIndex?.let { segmentCache?.prefetchAround(it, seekTime) }
    }

    private fun preloadFirstFrame(timeoutMillis: Long = 5_000) {
        val startedAt = System.nanoTime()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        Log.info("Waiting for first decoded video frame")
        while (System.nanoTime() < deadline) {
            val frame = videoQueue.poll(25, TimeUnit.MILLISECONDS)
            if (frame != null) {
                completeBufferedFrame(frame, "startup", startedAt)
                return
            }
        }
        Log.info("First video frame was not ready after ${timeoutMillis}ms; starting playback anyway")
        finishBufferingWithoutFrame("startup timeout")
    }

    private fun stopDecoders() {
        Log.info("Stopping decoders")
        videoDecoder?.stop()
        audioDecoder?.stop()
        videoDecoder?.join()
        audioDecoder?.join()
        videoDecoder = null
        audioDecoder = null
    }

    private fun offerVideoFrame(frame: VideoFrame, generation: Int) {
        var offered = false
        try {
            while (generation == decoderGeneration.get()) {
                if (videoQueue.offer(frame, 25, TimeUnit.MILLISECONDS)) {
                    offered = true
                    return
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (!offered) {
                frame.close()
            }
        }
    }

    private fun offerAudioChunk(chunk: AudioChunk, generation: Int) {
        var offered = false
        try {
            while (generation == decoderGeneration.get()) {
                if (audioQueue.offer(chunk, 25, TimeUnit.MILLISECONDS)) {
                    offered = true
                    return
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (!offered) {
                chunk.close()
            }
        }
    }

    private fun requestSeek(targetTime: Double) {
        if (isSeekInProgress.getAndSet(true)) return
        val clamped = targetTime.coerceIn(0.0, totalDuration.takeIf { it > 0.0 } ?: targetTime)
        currentTime = clamped
        val shouldResume = playback.playWhenReady && playback.state != PlaybackState.PAUSED
        beginSeek(clamped, playWhenReady = shouldResume, reason = "user/request")
        fallbackClock.reset(clamped, paused = true, playbackSpeed)
        decoderGeneration.incrementAndGet()
        Log.info(
            "Seek requested: target=%.3fs, selectedSegment=%.3fs, resume=%s".format(
                clamped,
                selectedSegmentStart(clamped),
                shouldResume
            )
        )
        audioDevice?.setPaused(true)
        audioDevice?.clear(clamped)
        closeQueuedFrames()
        closeQueuedAudio()
        pendingAudioChunk?.close()
        pendingAudioChunk = null

        Thread({
            try {
                stopDecoders()
                startDecoders(clamped)
            } catch (e: Exception) {
                Log.error("Seek failed: ${e.message}", e)
            } finally {
                isSeekInProgress.set(false)
            }
        }, "seek-restart").start()
    }

    private fun loop() {
        var noFramesCounter = 0.0

        while (!glfwWindowShouldClose(window)) {
            val loopTime = System.nanoTime() / 1_000_000_000.0
            val deltaTime = loopTime - lastLoopTime
            lastLoopTime = loopTime

            processInput(loopTime)
            handleBufferedFrameIfNeeded()
            pumpAudio()
            updateClock(loopTime)
            logSyncDiagnostics(loopTime)
            updateFps(deltaTime)

            if (loopTime - timelineLastInteraction > timelineTimeout) {
                timelineVisible = false
            }

            val frameUpdated = uploadDueFrames()
            renderer.render()
            renderOverlay()

            glfwSwapBuffers(window)
            glfwPollEvents()

            if (playback.state == PlaybackState.PLAYING && videoQueue.isEmpty() && currentTime < totalDuration - 1.0) {
                noFramesCounter += deltaTime
                if (noFramesCounter > 4.0) {
                    Log.info("Decoder stalled; restarting near %.2f seconds".format(currentTime))
                    requestSeek(currentTime)
                    noFramesCounter = 0.0
                }
            } else {
                noFramesCounter = 0.0
            }

            if (totalDuration > 0.0 && currentTime >= totalDuration) {
                markEnded("duration reached")
            }

            if (!frameUpdated) {
                sleepQuietly(2)
            }
        }
    }

    private fun pumpAudio() {
        if (!playback.canPumpAudio()) {
            audioDevice?.setPaused(true)
            return
        }
        val device = audioDevice ?: return
        device.pump()

        pendingAudioChunk?.let { pending ->
            if (device.queue(pending)) {
                pendingAudioChunk = null
            } else {
                return
            }
        }

        while (true) {
            val chunk = audioQueue.poll() ?: break
            if (!device.queue(chunk)) {
                pendingAudioChunk = chunk
                break
            }
        }
    }

    private fun updateClock(loopTime: Double) {
        if (!playback.canAdvanceClock()) {
            return
        }

        currentTime = if (audioDevice != null && info.hasAudio) {
            val audioClock = audioDevice?.clockSeconds(currentTime) ?: fallbackClock.seconds(loopTime)
            lastAudioClock = audioClock
            audioClock
        } else {
            fallbackClock.seconds(loopTime)
        }
        lastAvDrift = lastFrameTimestamp - currentTime
        segmentIndex?.let { segmentCache?.prefetchAround(it, currentTime) }
    }

    private fun uploadDueFrames(): Boolean {
        if (playback.isWaitingForFirstFrame()) {
            return false
        }

        var uploaded = false
        while (true) {
            val frame = videoQueue.peek() ?: break
            if (videoQueue.size > 1 && frame.timestamp < currentTime - 0.100) {
                videoQueue.poll()?.close()
                droppedLateFrames++
                continue
            }
            if (frame.timestamp > currentTime + 0.025) break
            val due = videoQueue.poll() ?: break
            lastFrameTimestamp = due.timestamp
            renderer.upload(due)
            uploaded = true
        }
        return uploaded
    }

    private fun updateFps(deltaTime: Double) {
        frameCount++
        fpsTimer += deltaTime
        if (fpsTimer >= 1.0) {
            fps = frameCount / fpsTimer
            frameCount = 0
            fpsTimer = 0.0
        }
    }

    private fun processInput(currentLoopTime: Double) {
        if (pressedOnce(GLFW_KEY_SPACE)) {
            setPaused(!paused)
        }
        if (pressedOnce(GLFW_KEY_RIGHT)) {
            requestSeek(currentTime + 10.0)
        }
        if (pressedOnce(GLFW_KEY_LEFT)) {
            requestSeek(max(0.0, currentTime - 10.0))
        }
        if (pressedOnce(GLFW_KEY_UP)) {
            setPlaybackSpeed(playbackSpeed + 0.25)
        }
        if (pressedOnce(GLFW_KEY_DOWN)) {
            setPlaybackSpeed(max(0.25, playbackSpeed - 0.25))
        }
        if (pressedOnce(GLFW_KEY_F)) {
            showFps = !showFps
        }
        if (pressedOnce(GLFW_KEY_D)) {
            debugOverlayVisible = !debugOverlayVisible
        }
        if (pressedOnce(GLFW_KEY_R)) {
            requestSeek(0.0)
        }
        processMouseInput(currentLoopTime)
    }

    private fun pressedOnce(key: Int): Boolean {
        val down = glfwGetKey(window, key) == GLFW_PRESS
        val wasDown = keyState[key] == true
        keyState[key] = down
        return down && !wasDown
    }

    private fun setPaused(value: Boolean) {
        val before = playback.state
        if (value) {
            playback.pause()
            audioDevice?.setPaused(true)
        } else {
            playback.play()
            fallbackClock.reset(currentTime, paused = false, playbackSpeed)
            audioDevice?.setPaused(playback.state != PlaybackState.PLAYING)
        }
        logStateChange(before, playback.state, if (value) "pause requested" else "play requested")
    }

    private fun setPlaybackSpeed(value: Double) {
        playbackSpeed = value.coerceAtLeast(0.25)
        fallbackClock.reset(currentTime, paused, playbackSpeed)
        audioDevice?.setPitch(playbackSpeed)
    }

    private fun processMouseInput(currentLoopTime: Double) {
        val timelineX = 50.0
        val timelineY = 650.0
        val timelineWidth = 1180.0
        val timelineHeight = 20.0

        val xPosBuffer: DoubleBuffer = BufferUtils.createDoubleBuffer(1)
        val yPosBuffer: DoubleBuffer = BufferUtils.createDoubleBuffer(1)
        glfwGetCursorPos(window, xPosBuffer, yPosBuffer)
        val mouseX = xPosBuffer.get(0)
        val mouseY = yPosBuffer.get(0)

        val mouseInTimeline = mouseX in timelineX..(timelineX + timelineWidth) &&
            mouseY in timelineY..(timelineY + timelineHeight)

        if (mouseInTimeline) {
            timelineVisible = true
            timelineLastInteraction = currentLoopTime

            val mousePressed = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS
            if (mousePressed && !timelineMouseWasPressed && !isSeekInProgress.get()) {
                val newSeekTime = ((mouseX - timelineX) / timelineWidth) * totalDuration
                requestSeek(newSeekTime)
            }
            timelineMouseWasPressed = mousePressed
        } else {
            timelineMouseWasPressed = false
        }
    }

    private fun beginBuffering(targetTime: Double, playWhenReady: Boolean, reason: String) {
        val before = playback.state
        bufferingStartedNanos = System.nanoTime()
        playback.startBuffering(targetTime, playWhenReady)
        currentTime = targetTime
        fallbackClock.reset(targetTime, paused = true, playbackSpeed)
        audioDevice?.setPaused(true)
        logStateChange(before, playback.state, reason)
    }

    private fun beginSeek(targetTime: Double, playWhenReady: Boolean, reason: String) {
        val before = playback.state
        bufferingStartedNanos = System.nanoTime()
        playback.startSeek(targetTime, playWhenReady)
        logStateChange(before, playback.state, reason)
    }

    private fun handleBufferedFrameIfNeeded() {
        if (!playback.isWaitingForFirstFrame()) return
        val frame = videoQueue.poll()
        if (frame == null) {
            val waitedMs = (System.nanoTime() - bufferingStartedNanos) / 1_000_000L
            if (waitedMs > 5_000) {
                Log.info("No video frame arrived while ${playback.state} after ${waitedMs}ms")
                finishBufferingWithoutFrame("${playback.state.name.lowercase()} timeout")
            }
            return
        }
        completeBufferedFrame(frame, playback.state.name.lowercase(), bufferingStartedNanos)
    }

    private fun completeBufferedFrame(frame: VideoFrame, reason: String, startedAtNanos: Long) {
        currentTime = frame.timestamp
        lastFrameTimestamp = frame.timestamp
        renderer.upload(frame)
        val waitMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0
        val before = playback.state
        val after = playback.firstFrameReady()
        fallbackClock.reset(currentTime, paused = after != PlaybackState.PLAYING, playbackSpeed)
        dropAudioBefore(currentTime - 0.020)
        audioDevice?.clear(currentTime)
        audioDevice?.setPaused(after != PlaybackState.PLAYING)
        isSeekInProgress.set(false)
        Log.info(
            "First video frame ready after %s: frame=%.3fs, target=%.3fs, wait=%.1fms".format(
                reason,
                currentTime,
                playback.targetTime,
                waitMs
            )
        )
        logStateChange(before, after, "first frame ready")
    }

    private fun finishBufferingWithoutFrame(reason: String) {
        val before = playback.state
        val after = playback.firstFrameReady()
        fallbackClock.reset(currentTime, paused = after != PlaybackState.PLAYING, playbackSpeed)
        audioDevice?.setPaused(after != PlaybackState.PLAYING)
        isSeekInProgress.set(false)
        logStateChange(before, after, reason)
    }

    private fun markEnded(reason: String) {
        if (playback.state == PlaybackState.ENDED) return
        val before = playback.state
        playback.end()
        currentTime = totalDuration
        audioDevice?.setPaused(true)
        logStateChange(before, playback.state, reason)
    }

    private fun dropAudioBefore(timeSeconds: Double) {
        var dropped = 0
        while (true) {
            val chunk = audioQueue.peek() ?: break
            if (chunk.timestamp + chunk.duration >= timeSeconds) break
            audioQueue.poll()?.close()
            dropped++
        }
        if (dropped > 0) {
            Log.info("Dropped $dropped stale audio chunks before %.3fs".format(timeSeconds))
        }
    }

    private fun selectedSegmentStart(timeSeconds: Double): Double {
        return segmentIndex?.findSegmentStart(timeSeconds) ?: 0.0
    }

    private fun logSelectedSegment(timeSeconds: Double) {
        val index = segmentIndex ?: return
        val start = index.findSegmentStart(timeSeconds)
        val next = index.nextSegmentStart(start)
        Log.info(
            "Segment selected: target=%.3fs, segmentStart=%.3fs, next=%s".format(
                timeSeconds,
                start,
                next?.let { "%.3fs".format(it) } ?: "none"
            )
        )
    }

    private fun logStateChange(before: PlaybackState, after: PlaybackState, reason: String) {
        if (before != after) {
            Log.info("Playback state: $before -> $after ($reason)")
        } else if (options.debugSync) {
            Log.info("Playback state unchanged: $after ($reason)")
        }
    }

    private fun logSyncDiagnostics(loopTime: Double) {
        if (!options.debugSync || loopTime - lastSyncDebugLogTime < 1.0) return
        lastSyncDebugLogTime = loopTime
        Log.info(
            "Sync: state=%s time=%.3fs video=%.3fs audio=%.3fs drift=%.3fs vq=%d aq=%d dropped=%d".format(
                playback.state,
                currentTime,
                lastFrameTimestamp,
                lastAudioClock,
                lastAvDrift,
                videoQueue.size,
                audioQueue.size,
                droppedLateFrames
            )
        )
    }

    private fun debugOverlayText(): String {
        return "state: %s   vq: %d   aq: %d   drift: %.3fs   video: %.3fs   audio: %.3fs   dropped: %d".format(
            playback.state,
            videoQueue.size,
            audioQueue.size,
            lastAvDrift,
            lastFrameTimestamp,
            lastAudioClock,
            droppedLateFrames
        )
    }

    private fun renderOverlay() {
        glMatrixMode(GL_PROJECTION)
        glPushMatrix()
        glLoadIdentity()
        glOrtho(0.0, 1280.0, 720.0, 0.0, -1.0, 1.0)
        glMatrixMode(GL_MODELVIEW)
        glPushMatrix()
        glLoadIdentity()

        val cacheText = if (options.cacheEnabled && segmentIndex != null) "cache:on" else "cache:off"
        val audioText = if (audioDevice != null && info.hasAudio) "audio:on" else "audio:off"
        val statusText = playback.state.name.lowercase()
        val fpsText = if (showFps) " FPS: %.2f".format(fps) else ""
        val overlayText = "time: %.2f / %.2f sec   speed: %.2fx   %s   %s   %s%s".format(
            currentTime,
            totalDuration,
            playbackSpeed,
            statusText,
            audioText,
            cacheText,
            fpsText
        )
        drawText(overlayText, 10f, 30f)
        if (debugOverlayVisible || options.debugSync) {
            drawText(debugOverlayText(), 10f, 48f)
        }

        if (timelineVisible) {
            renderTimeline()
        }

        glPopMatrix()
        glMatrixMode(GL_PROJECTION)
        glPopMatrix()
        glMatrixMode(GL_MODELVIEW)
    }

    private fun renderTimeline() {
        val timelineX = 50.0
        val timelineY = 650.0
        val timelineWidth = 1180.0
        val timelineHeight = 20.0

        glColor3f(0.3f, 0.3f, 0.3f)
        glBegin(GL_QUADS)
        glVertex2d(timelineX, timelineY)
        glVertex2d(timelineX + timelineWidth, timelineY)
        glVertex2d(timelineX + timelineWidth, timelineY + timelineHeight)
        glVertex2d(timelineX, timelineY + timelineHeight)
        glEnd()

        val progressRatio = if (totalDuration > 0.0) currentTime / totalDuration else 0.0
        val progressWidth = timelineWidth * min(1.0, max(0.0, progressRatio))
        glColor3f(0.1f, 0.8f, 0.1f)
        glBegin(GL_QUADS)
        glVertex2d(timelineX, timelineY)
        glVertex2d(timelineX + progressWidth, timelineY)
        glVertex2d(timelineX + progressWidth, timelineY + timelineHeight)
        glVertex2d(timelineX, timelineY + timelineHeight)
        glEnd()

        if (isSeekInProgress.get()) {
            glColor3f(1.0f, 0.5f, 0.0f)
            val loadingBarWidth = 5.0
            glBegin(GL_QUADS)
            glVertex2d(timelineX + progressWidth - loadingBarWidth, timelineY - 5)
            glVertex2d(timelineX + progressWidth + loadingBarWidth, timelineY - 5)
            glVertex2d(timelineX + progressWidth + loadingBarWidth, timelineY + timelineHeight + 5)
            glVertex2d(timelineX + progressWidth - loadingBarWidth, timelineY + timelineHeight + 5)
            glEnd()
        }

        glColor3f(1f, 1f, 1f)
    }

    private fun drawText(text: String, x: Float, y: Float) {
        val charBuffer = BufferUtils.createByteBuffer(99_999)
        val quads = STBEasyFont.stb_easy_font_print(x, y, text, null, charBuffer)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glColor3f(1f, 1f, 1f)
        glEnableClientState(GL_VERTEX_ARRAY)
        glVertexPointer(2, GL_FLOAT, 16, charBuffer)
        glDrawArrays(GL_QUADS, 0, quads * 4)
        glDisableClientState(GL_VERTEX_ARRAY)
        glDisable(GL_BLEND)
    }

    private fun closeQueuedFrames() {
        while (true) {
            videoQueue.poll()?.close() ?: break
        }
    }

    private fun closeQueuedAudio() {
        while (true) {
            audioQueue.poll()?.close() ?: break
        }
    }

    private fun cleanup() {
        try {
            isSeekInProgress.set(true)
            decoderGeneration.incrementAndGet()
            stopDecoders()
            closeQueuedFrames()
            closeQueuedAudio()
            pendingAudioChunk?.close()
            pendingAudioChunk = null
            segmentCache?.close()
            audioDevice?.close()
            renderer.close()

            if (window != NULL) {
                glfwDestroyWindow(window)
            }
            glfwTerminate()
        } catch (e: Exception) {
            Log.error("Cleanup error: ${e.message}", e)
        }
    }

    private fun sleepQuietly(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private class PlaybackClock {
        private var baseSeconds = 0.0
        private var baseNano = System.nanoTime()
        private var paused = false
        private var speed = 1.0

        fun reset(positionSeconds: Double, paused: Boolean, speed: Double) {
            baseSeconds = positionSeconds
            baseNano = System.nanoTime()
            this.paused = paused
            this.speed = speed
        }

        fun seconds(loopTimeSeconds: Double): Double {
            if (paused) return baseSeconds
            val elapsed = loopTimeSeconds - baseNano / 1_000_000_000.0
            return baseSeconds + elapsed * speed
        }
    }
}
