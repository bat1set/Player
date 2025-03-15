package main

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.stb.STBEasyFont
import org.lwjgl.system.MemoryUtil.NULL
import java.nio.ByteBuffer
import java.nio.DoubleBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class VideoPlayer(private val filePath: String) {
    private var window: Long = NULL
    private var textureID = 0
    // Очередь для кадров из декодера
    private val frameQueue = ConcurrentLinkedQueue<Frame>()

    // Управление воспроизведением
    private var paused = false
    private var playbackSpeed = 1.0
    private var currentTime = 0.0
    private var totalDuration = H264Decoder.getVideoDuration(filePath)
    private var showFps = false

    // Переменные для работы с декодером и seek
    private var decoder: H264Decoder? = null
    private var decoderStartTime = 0.0  // время, с которого запущен текущий декодер
    private val isSeekInProgress = AtomicBoolean(false)
    private var lastFrameTimestamp = -1.0

    // FPS
    private var fps = 0.0
    private var frameCount = 0
    private var fpsTimer = 0.0

    // Тайминг рендеринга
    private var lastTime = System.nanoTime() / 1_000_000_000.0

    // Параметры таймлайна
    private var timelineVisible = true
    private var timelineLastInteraction = 0.0
    private val timelineTimeout = 3.0  // секунд
    private var timelineMouseWasPressed = false

    // Размеры видео
    private var videoWidth = 0
    private var videoHeight = 0

    // Защита от частых перемоток
    private var lastSeekTime = 0.0
    private val seekCooldown = 0.5  // минимальное время между перемотками (секунды)

    // Флаг для проверки состояния декодера
    private var decoderRunning = false

    fun run() {
        initGLFW()
        initOpenGL()
        decoderStartTime = 0.0
        startDecoder(decoderStartTime)
        loop()
        cleanup()
    }

    private fun initGLFW() {
        if (!glfwInit()) throw RuntimeException("Failed to initialize GLFW")
        window = glfwCreateWindow(1280, 720, "H.264 Video Player", NULL, NULL)
        if (window == NULL) throw RuntimeException("Failed to create window")
        glfwMakeContextCurrent(window)
        glfwSwapInterval(1)
        GL.createCapabilities()
    }

    private fun initOpenGL() {
        textureID = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureID)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glBindTexture(GL_TEXTURE_2D, 0)
    }

    // Запускаем декодер с указанным стартовым временем (в секундах)
    private fun startDecoder(seekTime: Double) {
        try {
            decoder = H264Decoder(filePath, seekTime).also { decoder ->
                decoder.setFrameCallback { frame ->
                    frameQueue.add(frame)
                    // Сохраняем размеры видео из первого кадра, если они ещё не установлены
                    if (videoWidth == 0 || videoHeight == 0) {
                        videoWidth = frame.width
                        videoHeight = frame.height
                    }
                    // Обновляем последний временной штамп кадра
                    lastFrameTimestamp = frame.timestamp
                }
                decoder.startAsync()
                decoderRunning = true
            }
        } catch (e: Exception) {
            println("Ошибка запуска декодера: ${e.message}")
            decoderRunning = false
        }
    }

    // Перезапуск декодера для seek с обработкой ошибок
    private fun restartDecoder(seekTime: Double) {
        if (isSeekInProgress.getAndSet(true)) {
            return // Пропускаем, если перемотка уже в процессе
        }

        // Обновляем текущее время сразу для более отзывчивого UI
        currentTime = seekTime

        Thread {
            try {
                println("Перезапуск декодера с позиции: $seekTime сек")

                // Полная остановка текущего декодера
                val currentDecoder = decoder
                if (currentDecoder != null && decoderRunning) {
                    try {
                        currentDecoder.stop()
                        // Ожидаем завершения с таймаутом
                        val joinSuccess = currentDecoder.join() // Ждем завершения не более 1 секунды
                    } catch (e: Exception) {
                        println("Ошибка при остановке декодера: ${e.message}")
                    }
                }

                // Очистка всех ресурсов
                frameQueue.clear()
                decoderStartTime = seekTime
                decoderRunning = false

                // Небольшая пауза перед созданием нового декодера для стабильности
                safeSleep(50)

                // Запуск нового декодера с указанной позиции
                startDecoder(seekTime)

            } catch (e: Exception) {
                println("Ошибка при перезапуске декодера: ${e.message}")
            } finally {
                isSeekInProgress.set(false)
            }
        }.start()
    }

    // Обновление текстуры – вызывается в главном OpenGL потоке
    private fun updateTexture(frame: Frame) {
        try {
            glBindTexture(GL_TEXTURE_2D, textureID)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, frame.width, frame.height, 0, GL_RGB, GL_UNSIGNED_BYTE, frame.buffer)
            glBindTexture(GL_TEXTURE_2D, 0)
        } catch (e: Exception) {
            println("Ошибка обновления текстуры: ${e.message}")
        }
    }

    // Обработка клавиатурного и мышиного ввода с защитой от частых нажатий
    private fun processInput(deltaTime: Double, currentLoopTime: Double) {
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) {
            paused = !paused
            safeSleep(150)
        }
        if (glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS && !isSeekInProgress.get()) {
            val newTime = currentTime + 10.0
            if (newTime < totalDuration) {
                restartDecoder(newTime)
            }
            safeSleep(150)
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS && !isSeekInProgress.get()) {
            val newTime = currentTime - 10.0
            val targetTime = if (newTime < 0) 0.0 else newTime
            restartDecoder(targetTime)
            safeSleep(150)
        }
        if (glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS) {
            playbackSpeed += 0.25
            safeSleep(150)
        }
        if (glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS) {
            playbackSpeed -= 0.25
            if (playbackSpeed < 0.25) playbackSpeed = 0.25
            safeSleep(150)
        }
        if (glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS) {
            showFps = !showFps
            safeSleep(150)
        }
        if (glfwGetKey(window, GLFW_KEY_R) == GLFW_PRESS && !isSeekInProgress.get()) {
            // Перезапуск видео с начала
            restartDecoder(0.0)
            safeSleep(150)
        }
        processMouseInput(currentLoopTime)
    }

    // Безопасный сон потока
    private fun safeSleep(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (e: InterruptedException) {
            // Игнорируем прерывание
        }
    }

    // Обработка мышиного ввода для таймлайна с защитой от частых изменений
    // Обработка мышиного ввода для таймлайна - полная перегенерация при клике
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

            // Вызываем переход только при переходе из "не нажато" в "нажато" и если нет перемотки в процессе
            if (mousePressed && !timelineMouseWasPressed && !isSeekInProgress.get()) {
                val newSeekTime = ((mouseX - timelineX) / timelineWidth) * totalDuration

                if (newSeekTime >= 0 && newSeekTime <= totalDuration) {
                    currentTime = newSeekTime  // Устанавливаем новое время сразу
                    restartDecoder(newSeekTime)
                }
            }
            timelineMouseWasPressed = mousePressed
        } else {
            // Если курсор вне области, сбрасываем флаг
            timelineMouseWasPressed = false
        }
    }

    private fun loop() {
        var lastCheckTime = System.nanoTime() / 1_000_000_000.0
        var noFramesCounter = 0.0

        while (!glfwWindowShouldClose(window)) {
            val currentLoopTime = System.nanoTime() / 1_000_000_000.0
            val deltaTime = currentLoopTime - lastTime
            lastTime = currentLoopTime

            processInput(deltaTime, currentLoopTime)

            // Проверка на "зависание" декодера
            if (!isSeekInProgress.get() && !paused && frameQueue.isEmpty()) {
                noFramesCounter += deltaTime
                if (noFramesCounter > 2.0) { // Если нет кадров более 2 секунд
                    println("Обнаружено зависание декодера, перезапуск...")
                    restartDecoder(currentTime)
                    noFramesCounter = 0.0
                }
            } else {
                noFramesCounter = 0.0
            }

            // Проверка состояния декодера каждые 5 секунд
            if (currentLoopTime - lastCheckTime > 5.0) {
                checkDecoderState()
                lastCheckTime = currentLoopTime
            }

            if (!paused && !isSeekInProgress.get()) {
                currentTime += deltaTime * playbackSpeed
            }

            // Обновление FPS
            frameCount++
            fpsTimer += deltaTime
            if (fpsTimer >= 1.0) {
                fps = frameCount / fpsTimer
                frameCount = 0
                fpsTimer = 0.0
            }

            // Скрываем таймлайн, если нет активности более timelineTimeout секунд
            if (currentLoopTime - timelineLastInteraction > timelineTimeout) {
                timelineVisible = false
            }

            glClear(GL_COLOR_BUFFER_BIT)

            // Обновляем текстуру, если найдены кадры с timestamp <= currentTime
            var frameUpdated = false
            try {
                while (frameQueue.isNotEmpty() && frameQueue.peek().timestamp <= currentTime) {
                    val frame = frameQueue.poll()
                    updateTexture(frame)
                    frameUpdated = true
                }
            } catch (e: Exception) {
                println("Ошибка при обработке кадров: ${e.message}")
            }

            renderFrame()
            renderOverlay()

            glfwSwapBuffers(window)
            glfwPollEvents()

            if (currentTime >= totalDuration) {
                paused = true
            }

            // Небольшая задержка для снижения нагрузки на CPU
            if (!frameUpdated) {
                safeSleep(5)
            }
        }
    }

    // Проверка состояния декодера и его перезапуск при необходимости
    private fun checkDecoderState() {
        if (!isSeekInProgress.get() && (decoder == null || !decoderRunning)) {
            println("Декодер не работает, перезапуск...")
            restartDecoder(currentTime)
        } else if (!isSeekInProgress.get() && !paused && lastFrameTimestamp >= 0 &&
            currentTime - lastFrameTimestamp > 5.0 && currentTime < totalDuration - 1.0) {
            // Если текущее время значительно опережает последний декодированный кадр
            println("Декодер отстает, перезапуск...")
            restartDecoder(currentTime)
        }
    }

    private fun renderFrame() {
        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, textureID)
        glBegin(GL_QUADS)
        glTexCoord2f(0f, 1f); glVertex2f(-1f, -1f)
        glTexCoord2f(1f, 1f); glVertex2f(1f, -1f)
        glTexCoord2f(1f, 0f); glVertex2f(1f, 1f)
        glTexCoord2f(0f, 0f); glVertex2f(-1f, 1f)
        glEnd()
        glBindTexture(GL_TEXTURE_2D, 0)
        glDisable(GL_TEXTURE_2D)
    }

    // Отрисовка оверлея: текст и таймлайн (если видим)
    private fun renderOverlay() {
        glMatrixMode(GL_PROJECTION)
        glPushMatrix()
        glLoadIdentity()
        glOrtho(0.0, 1280.0, 720.0, 0.0, -1.0, 1.0)
        glMatrixMode(GL_MODELVIEW)
        glPushMatrix()
        glLoadIdentity()

        val timeText = String.format("time: %.2f / %.2f сек", currentTime, totalDuration)
        val speedText = String.format("speed: %.2fx", playbackSpeed)
        val statusText = if (isSeekInProgress.get()) "rewinding..." else if (paused) "pause" else "reproduction"
        val fpsText = if (showFps) String.format("FPS: %.2f", fps) else ""
        val overlayText = "$timeText   $speedText   $statusText   $fpsText"
        drawText(overlayText, 10f, 30f)

        if (timelineVisible) {
            renderTimeline()
        }

        glPopMatrix()
        glMatrixMode(GL_PROJECTION)
        glPopMatrix()
        glMatrixMode(GL_MODELVIEW)
    }

    // Отрисовка таймлайна (фон и прогресс)
    private fun renderTimeline() {
        val timelineX = 50.0
        val timelineY = 650.0
        val timelineWidth = 1180.0
        val timelineHeight = 20.0

        // Фон (серый прямоугольник)
        glColor3f(0.3f, 0.3f, 0.3f)
        glBegin(GL_QUADS)
        glVertex2d(timelineX, timelineY)
        glVertex2d(timelineX + timelineWidth, timelineY)
        glVertex2d(timelineX + timelineWidth, timelineY + timelineHeight)
        glVertex2d(timelineX, timelineY + timelineHeight)
        glEnd()

        // Прогресс (зелёный прямоугольник)
        val progressRatio = currentTime / totalDuration
        val progressWidth = timelineWidth * if (progressRatio <= 1.0) progressRatio else 1.0
        glColor3f(0.1f, 0.8f, 0.1f)
        glBegin(GL_QUADS)
        glVertex2d(timelineX, timelineY)
        glVertex2d(timelineX + progressWidth, timelineY)
        glVertex2d(timelineX + progressWidth, timelineY + timelineHeight)
        glVertex2d(timelineX, timelineY + timelineHeight)
        glEnd()

        // Индикатор перемотки (если активен)
        if (isSeekInProgress.get()) {
            glColor3f(1.0f, 0.5f, 0.0f) // Оранжевый цвет для индикатора перемотки
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

    // Отрисовка текста с помощью STB Easy Font с защитой от ошибок
    private fun drawText(text: String, x: Float, y: Float) {
        val charBuffer = BufferUtils.createByteBuffer(99999)
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

    private fun cleanup() {
        try {
            isSeekInProgress.set(true) // Предотвращаем новые перемотки
            decoder?.stop()
            decoder?.join() // Ждем завершения не более 1 секунды

            if (textureID != 0) {
                glDeleteTextures(textureID)
            }

            if (window != NULL) {
                glfwDestroyWindow(window)
            }
            glfwTerminate()
        } catch (e: Exception) {
            println("Ошибка при завершении приложения: ${e.message}")
        }
    }
}