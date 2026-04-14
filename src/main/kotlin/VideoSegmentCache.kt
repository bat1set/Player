package main

import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_q2d
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.math.max

data class Segment(
    val startSeconds: Double,
    val packetTimestamp: Long
)

data class SegmentIndex(
    val key: SegmentCacheKey,
    val duration: Double,
    val segments: List<Segment>
) {
    fun findSegmentStart(timeSeconds: Double): Double {
        if (segments.isEmpty()) return 0.0
        var result = segments.first().startSeconds
        for (segment in segments) {
            if (segment.startSeconds <= timeSeconds) {
                result = segment.startSeconds
            } else {
                break
            }
        }
        return result.coerceAtLeast(0.0)
    }

    fun nextSegmentStart(timeSeconds: Double): Double? {
        return segments.firstOrNull { it.startSeconds > timeSeconds }?.startSeconds
    }
}

data class SegmentCacheKey(
    val absolutePath: String,
    val size: Long,
    val modifiedMillis: Long,
    val contentHash: String
) {
    val directoryName: String
        get() = contentHash.take(16)

    fun toProperties(): Properties = Properties().apply {
        setProperty("absolutePath", absolutePath)
        setProperty("size", size.toString())
        setProperty("modifiedMillis", modifiedMillis.toString())
        setProperty("contentHash", contentHash)
    }

    companion object {
        fun from(file: Path): SegmentCacheKey {
            val absolute = file.absolute().normalize()
            val size = Files.size(absolute)
            val modified = Files.getLastModifiedTime(absolute).toMillis()
            return SegmentCacheKey(
                absolutePath = absolute.toString(),
                size = size,
                modifiedMillis = modified,
                contentHash = shortContentHash(absolute, size, modified)
            )
        }

        fun fromProperties(properties: Properties): SegmentCacheKey {
            return SegmentCacheKey(
                absolutePath = properties.getProperty("absolutePath"),
                size = properties.getProperty("size").toLong(),
                modifiedMillis = properties.getProperty("modifiedMillis").toLong(),
                contentHash = properties.getProperty("contentHash")
            )
        }

        private fun shortContentHash(file: Path, size: Long, modifiedMillis: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(file.toString().toByteArray(Charsets.UTF_8))
            digest.update(size.toString().toByteArray(Charsets.UTF_8))
            digest.update(modifiedMillis.toString().toByteArray(Charsets.UTF_8))
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = 1024 * 1024
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class VideoSegmentCache(
    private val cacheDir: Path,
    private val maxSizeBytes: Long
) {
    private val ramIndex = ConcurrentHashMap<String, SegmentIndex>()
    private val prefetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "segment-cache-prefetch").apply { isDaemon = true }
    }

    fun loadOrBuild(filePath: String): SegmentIndex {
        val key = SegmentCacheKey.from(Path.of(filePath))
        ramIndex[key.contentHash]?.let { return it }

        Files.createDirectories(cacheDir)
        Log.info("Cache directory: ${cacheDir.toAbsolutePath()}")
        val entryDir = cacheDir.resolve(key.directoryName)
        val manifest = entryDir.resolve("manifest.properties")
        val segments = entryDir.resolve("segments.txt")

        val cached = readCachedIndex(key, manifest, segments)
        if (cached != null) {
            Log.info("Segment cache hit: ${entryDir.toAbsolutePath()}")
            ramIndex[key.contentHash] = cached
            return cached
        }

        Log.info("Segment cache miss; scanning keyframes")
        val built = buildIndex(key)
        Files.createDirectories(entryDir)
        key.toProperties().apply {
            setProperty("duration", built.duration.toString())
        }.store(manifest.outputStream(), "Player segment cache manifest")

        segments.outputStream().bufferedWriter().use { writer ->
            for (segment in built.segments) {
                writer.append(segment.startSeconds.toString())
                    .append(',')
                    .append(segment.packetTimestamp.toString())
                    .appendLine()
            }
        }
        prune()
        Log.info("Segment cache written: ${entryDir.toAbsolutePath()}")
        ramIndex[key.contentHash] = built
        return built
    }

    fun prefetchAround(index: SegmentIndex, timeSeconds: Double) {
        prefetchExecutor.execute {
            val start = index.findSegmentStart(timeSeconds)
            val next = index.nextSegmentStart(start)
            ramIndex[index.key.contentHash] = index
            if (next != null) {
                ramIndex["${index.key.contentHash}:next"] = index
            }
        }
    }

    fun close() {
        prefetchExecutor.shutdownNow()
    }

    private fun readCachedIndex(
        expectedKey: SegmentCacheKey,
        manifest: Path,
        segmentsFile: Path
    ): SegmentIndex? {
        if (!manifest.exists() || !segmentsFile.exists()) return null

        val properties = Properties()
        manifest.inputStream().use { properties.load(it) }
        val key = SegmentCacheKey.fromProperties(properties)
        if (key != expectedKey) return null

        val duration = properties.getProperty("duration")?.toDoubleOrNull() ?: 0.0
        val segments = Files.newBufferedReader(segmentsFile).use { reader ->
            readSegments(reader)
        }
        return SegmentIndex(key, duration, segments)
    }

    private fun readSegments(reader: BufferedReader): List<Segment> {
        return reader.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(',', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val start = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val packetTimestamp = parts[1].toLongOrNull() ?: return@mapNotNull null
                Segment(start, packetTimestamp)
            }
            .sortedBy { it.startSeconds }
            .toList()
    }

    private fun buildIndex(key: SegmentCacheKey): SegmentIndex {
        val fmtCtx = FFmpegVideoDecoder.openFormatContext(key.absolutePath)
        val pkt = av_packet_alloc() ?: throw RuntimeException("Failed to allocate segment scan packet")
        try {
            val info = FFmpegVideoDecoder.readVideoInfo(key.absolutePath)
            val stream = fmtCtx.streams(info.videoStreamIndex)
            val timeBaseSeconds = av_q2d(stream.time_base())
            val streamStartSeconds = FFmpegVideoDecoder.streamStartTimestamp(stream.start_time()) * timeBaseSeconds
            val segments = mutableListOf<Segment>()

            while (av_read_frame(fmtCtx, pkt) >= 0) {
                try {
                    if (pkt.stream_index() == info.videoStreamIndex &&
                        FFmpegVideoDecoder.isPacketKeyFrame(pkt.flags())
                    ) {
                        val pts = when {
                            pkt.pts() != AV_NOPTS_VALUE -> pkt.pts()
                            pkt.dts() != AV_NOPTS_VALUE -> pkt.dts()
                            else -> 0L
                        }
                        val seconds = max(0.0, pts * timeBaseSeconds - streamStartSeconds)
                        if (segments.isEmpty() || seconds > segments.last().startSeconds + 0.001) {
                            segments += Segment(seconds, pts)
                        }
                    }
                } finally {
                    av_packet_unref(pkt)
                }
            }

            if (segments.isEmpty() || segments.first().startSeconds > 0.0) {
                segments.add(0, Segment(0.0, 0L))
            }

            return SegmentIndex(key, info.duration, segments.sortedBy { it.startSeconds })
        } finally {
            av_packet_free(pkt)
            avformat_close_input(fmtCtx)
        }
    }

    private fun prune() {
        if (!cacheDir.exists() || maxSizeBytes <= 0) return
        val entries = Files.list(cacheDir).use { stream ->
            stream.filter { it.isDirectory() }
                .map { path ->
                    val size = directorySize(path)
                    val modified = Files.getLastModifiedTime(path).toMillis()
                    CacheEntry(path, size, modified)
                }
                .toList()
                .sortedByDescending { it.modifiedMillis }
        }

        var total = entries.sumOf { it.sizeBytes }
        for (entry in entries.asReversed()) {
            if (total <= maxSizeBytes) break
            deleteDirectory(entry.path)
            total -= entry.sizeBytes
        }
    }

    private fun directorySize(path: Path): Long {
        return Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .mapToLong { Files.size(it) }
                .sum()
        }
    }

    private fun deleteDirectory(path: Path) {
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private data class CacheEntry(
        val path: Path,
        val sizeBytes: Long,
        val modifiedMillis: Long
    )
}
