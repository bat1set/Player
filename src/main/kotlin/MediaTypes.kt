package main

import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer

data class PlayerOptions(
    val filePath: String,
    val cacheEnabled: Boolean = true,
    val cacheDir: Path = defaultCacheDir(),
    val cacheSizeMb: Long = 512,
    val audioDevice: String = "default",
    val nativeYuv: Boolean = false,
    val debugSync: Boolean = false,
    val debugVideo: Boolean = false
)

data class VideoInfo(
    val width: Int,
    val height: Int,
    val duration: Double,
    val videoStreamIndex: Int,
    val audioStreamIndex: Int
) {
    val hasAudio: Boolean get() = audioStreamIndex >= 0
}

sealed class VideoFrame(
    open val width: Int,
    open val height: Int,
    open val timestamp: Double
) : AutoCloseable

data class RgbVideoFrame(
    val rgb: ByteBuffer,
    override val width: Int,
    override val height: Int,
    override val timestamp: Double,
    private val release: (ByteBuffer) -> Unit
) : VideoFrame(width, height, timestamp) {
    override fun close() = release(rgb)
}

data class Yuv420pVideoFrame(
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    override val width: Int,
    override val height: Int,
    override val timestamp: Double,
    private val release: (ByteBuffer) -> Unit
) : VideoFrame(width, height, timestamp) {
    override fun close() {
        release(y)
        release(u)
        release(v)
    }
}

data class Nv12VideoFrame(
    val y: ByteBuffer,
    val uv: ByteBuffer,
    override val width: Int,
    override val height: Int,
    override val timestamp: Double,
    private val release: (ByteBuffer) -> Unit
) : VideoFrame(width, height, timestamp) {
    override fun close() {
        release(y)
        release(uv)
    }
}

data class AudioChunk(
    val pcm: ByteBuffer,
    val sampleRate: Int,
    val channels: Int,
    val timestamp: Double,
    val duration: Double,
    private val release: (ByteBuffer) -> Unit
) : AutoCloseable {
    override fun close() = release(pcm)
}

fun defaultCacheDir(): Path {
    return defaultCacheDir(Path.of(System.getProperty("user.dir")))
}

fun defaultCacheDir(workingDir: Path): Path {
    val normalized = workingDir.toAbsolutePath().normalize()
    val buildDir = normalized.parent
    val projectCandidate = buildDir?.parent
    val projectRoot = if (
        normalized.fileName?.toString() == "libs" &&
        buildDir?.fileName?.toString() == "build" &&
        projectCandidate != null &&
        (Files.exists(projectCandidate.resolve("settings.gradle.kts")) ||
            Files.exists(projectCandidate.resolve("build.gradle.kts")))
    ) {
        projectCandidate
    } else {
        normalized
    }
    return projectRoot.resolve(".player-cache")
}

fun printUsage() {
    println(
        """
        Usage: java -jar h264-decoder.jar [options] <video>

        Options:
          --no-cache              Disable disk and RAM segment cache.
          --cache-dir <path>      Override cache directory.
          --cache-size-mb <n>     Limit disk cache size in megabytes. Default: 512.
          --audio-device default  Use the default OpenAL output device.
          --native-yuv            Use experimental YUV shader upload instead of RGB fallback.
          --debug-sync            Log audio/video clock drift and queue sizes.
          --debug-video           Log decoded frame and pixel format diagnostics.
          --help                  Show this help.
        """.trimIndent()
    )
}

fun parsePlayerOptions(args: Array<String>): PlayerOptions? {
    if (args.isEmpty() || args.any { it == "--help" || it == "-h" }) {
        printUsage()
        return null
    }

    var cacheEnabled = true
    var cacheDir = defaultCacheDir()
    var cacheSizeMb = 512L
    var audioDevice = "default"
    var nativeYuv = false
    var debugSync = false
    var debugVideo = false
    var filePath: String? = null

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--no-cache" -> cacheEnabled = false
            "--native-yuv" -> nativeYuv = true
            "--debug-sync" -> debugSync = true
            "--debug-video" -> debugVideo = true
            "--cache-dir" -> {
                i++
                if (i >= args.size) {
                    println("Missing value for --cache-dir")
                    printUsage()
                    return null
                }
                cacheDir = Path.of(args[i])
            }
            "--cache-size-mb" -> {
                i++
                if (i >= args.size) {
                    println("Missing value for --cache-size-mb")
                    printUsage()
                    return null
                }
                cacheSizeMb = args[i].toLongOrNull()?.coerceAtLeast(16) ?: run {
                    println("Invalid --cache-size-mb value: ${args[i]}")
                    printUsage()
                    return null
                }
            }
            "--audio-device" -> {
                i++
                if (i >= args.size) {
                    println("Missing value for --audio-device")
                    printUsage()
                    return null
                }
                audioDevice = args[i]
            }
            else -> {
                if (arg.startsWith("--")) {
                    println("Unknown option: $arg")
                    printUsage()
                    return null
                }
                filePath = arg
            }
        }
        i++
    }

    if (filePath == null) {
        println("Missing video path")
        printUsage()
        return null
    }

    return PlayerOptions(
        filePath = filePath,
        cacheEnabled = cacheEnabled,
        cacheDir = cacheDir,
        cacheSizeMb = cacheSizeMb,
        audioDevice = audioDevice,
        nativeYuv = nativeYuv,
        debugSync = debugSync,
        debugVideo = debugVideo
    )
}
