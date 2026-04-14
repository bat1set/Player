package main

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.AL_BUFFERS_PROCESSED
import org.lwjgl.openal.AL10.AL_FORMAT_MONO16
import org.lwjgl.openal.AL10.AL_FORMAT_STEREO16
import org.lwjgl.openal.AL10.AL_GAIN
import org.lwjgl.openal.AL10.AL_PAUSED
import org.lwjgl.openal.AL10.AL_PITCH
import org.lwjgl.openal.AL10.AL_PLAYING
import org.lwjgl.openal.AL10.AL_SOURCE_STATE
import org.lwjgl.openal.AL10.AL_STOPPED
import org.lwjgl.openal.AL10.alBufferData
import org.lwjgl.openal.AL10.alDeleteBuffers
import org.lwjgl.openal.AL10.alDeleteSources
import org.lwjgl.openal.AL10.alGenBuffers
import org.lwjgl.openal.AL10.alGenSources
import org.lwjgl.openal.AL10.alGetSourcef
import org.lwjgl.openal.AL10.alGetSourcei
import org.lwjgl.openal.AL10.alSourcePause
import org.lwjgl.openal.AL10.alSourcePlay
import org.lwjgl.openal.AL10.alSourceQueueBuffers
import org.lwjgl.openal.AL10.alSourceStop
import org.lwjgl.openal.AL10.alSourceUnqueueBuffers
import org.lwjgl.openal.AL10.alSourcef
import org.lwjgl.openal.AL11.AL_SEC_OFFSET
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.alcCloseDevice
import org.lwjgl.openal.ALC10.alcCreateContext
import org.lwjgl.openal.ALC10.alcDestroyContext
import org.lwjgl.openal.ALC10.alcMakeContextCurrent
import org.lwjgl.openal.ALC10.alcOpenDevice
import java.nio.ByteBuffer
import java.util.ArrayDeque

class OpenALAudioDevice(
    private val bufferCount: Int = 8
) : AutoCloseable {
    private var device: Long = 0
    private var context: Long = 0
    private var source: Int = 0
    private lateinit var buffers: IntArray
    private val freeBuffers = ArrayDeque<Int>()
    private val queued = ArrayDeque<AudioBufferMeta>()
    private var paused = false
    private var lastClockSeconds = 0.0

    fun initialize(deviceName: String = "default") {
        if (device != 0L) return
        if (deviceName != "default") {
            println("Only the default OpenAL device is supported in this build; using default instead of $deviceName")
        }

        device = alcOpenDevice(null as ByteBuffer?)
        if (device == 0L) {
            throw RuntimeException("Failed to open OpenAL device")
        }

        val deviceCapabilities = ALC.createCapabilities(device)
        context = alcCreateContext(device, null as java.nio.IntBuffer?)
        if (context == 0L) {
            alcCloseDevice(device)
            device = 0
            throw RuntimeException("Failed to create OpenAL context")
        }
        alcMakeContextCurrent(context)
        AL.createCapabilities(deviceCapabilities)

        source = alGenSources()
        alSourcef(source, AL_GAIN, 1.0f)
        buffers = IntArray(bufferCount) { alGenBuffers() }
        buffers.forEach { freeBuffers.addLast(it) }
    }

    fun queue(chunk: AudioChunk): Boolean {
        pump()
        if (freeBuffers.isEmpty()) {
            return false
        }
        val buffer = freeBuffers.removeFirst()

        val format = when (chunk.channels) {
            1 -> AL_FORMAT_MONO16
            2 -> AL_FORMAT_STEREO16
            else -> {
                freeBuffers.addLast(buffer)
                chunk.close()
                throw IllegalArgumentException("Unsupported OpenAL channel count: ${chunk.channels}")
            }
        }

        chunk.pcm.position(0)
        alBufferData(buffer, format, chunk.pcm, chunk.sampleRate)
        alSourceQueueBuffers(source, buffer)
        queued.addLast(AudioBufferMeta(buffer, chunk.timestamp, chunk.duration))
        chunk.close()

        if (!paused && alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
            alSourcePlay(source)
        }
        return true
    }

    fun pump() {
        if (source == 0) return
        val processed = alGetSourcei(source, AL_BUFFERS_PROCESSED)
        repeat(processed) {
            val buffer = alSourceUnqueueBuffers(source)
            val meta = if (queued.isEmpty()) null else queued.removeFirst()
            if (meta != null) {
                lastClockSeconds = meta.startSeconds + meta.durationSeconds
            }
            freeBuffers.addLast(buffer)
        }

        val state = alGetSourcei(source, AL_SOURCE_STATE)
        if (!paused && queued.isNotEmpty() && state == AL_STOPPED) {
            alSourcePlay(source)
        }
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (source == 0) return
        if (value) {
            if (alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING) {
                alSourcePause(source)
            }
        } else if (queued.isNotEmpty()) {
            val state = alGetSourcei(source, AL_SOURCE_STATE)
            if (state != AL_PLAYING) {
                alSourcePlay(source)
            }
        }
    }

    fun setPitch(speed: Double) {
        if (source != 0) {
            alSourcef(source, AL_PITCH, speed.toFloat().coerceAtLeast(0.25f))
        }
    }

    fun clockSeconds(fallback: Double): Double {
        pump()
        val first = queued.firstOrNull() ?: return lastClockSeconds.takeIf { it > 0.0 } ?: fallback
        val state = alGetSourcei(source, AL_SOURCE_STATE)
        val offset = if (state == AL_PLAYING || state == AL_PAUSED) {
            alGetSourcef(source, AL_SEC_OFFSET).toDouble()
        } else {
            0.0
        }
        lastClockSeconds = first.startSeconds + offset
        return lastClockSeconds
    }

    fun clear(positionSeconds: Double) {
        if (source == 0) return
        alSourceStop(source)

        repeat(queued.size) {
            freeBuffers.addLast(alSourceUnqueueBuffers(source))
        }
        queued.clear()

        freeBuffers.clear()
        buffers.forEach { freeBuffers.addLast(it) }
        lastClockSeconds = positionSeconds
    }

    override fun close() {
        if (source != 0) {
            clear(lastClockSeconds)
            alDeleteSources(source)
            source = 0
        }
        if (::buffers.isInitialized) {
            alDeleteBuffers(buffers)
        }
        if (context != 0L) {
            alcMakeContextCurrent(0)
            alcDestroyContext(context)
            context = 0
        }
        if (device != 0L) {
            alcCloseDevice(device)
            device = 0
        }
    }

    private data class AudioBufferMeta(
        val buffer: Int,
        val startSeconds: Double,
        val durationSeconds: Double
    )
}
