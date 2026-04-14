package main

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import java.nio.file.Files

class PlayerCoreTest {
    @Test
    fun segmentLookupUsesPreviousKeyframe() {
        val key = SegmentCacheKey("video.mp4", 1, 2, "abcdef")
        val index = SegmentIndex(
            key = key,
            duration = 30.0,
            segments = listOf(
                Segment(0.0, 0),
                Segment(4.0, 40),
                Segment(10.0, 100),
                Segment(20.0, 200)
            )
        )

        assertEquals(0.0, index.findSegmentStart(0.1))
        assertEquals(4.0, index.findSegmentStart(9.9))
        assertEquals(10.0, index.findSegmentStart(10.0))
        assertEquals(20.0, index.findSegmentStart(29.0))
    }

    @Test
    fun cacheKeyChangesWhenFileChanges() {
        val dir = Files.createTempDirectory("player-cache-key-test")
        val file = dir.resolve("sample.bin")
        Files.write(file, byteArrayOf(1, 2, 3, 4))
        val first = SegmentCacheKey.from(file)

        Thread.sleep(10)
        Files.write(file, byteArrayOf(1, 2, 3, 4, 5))
        val second = SegmentCacheKey.from(file)

        assertNotEquals(first, second)
        assertNotEquals(first.contentHash, second.contentHash)
    }

    @Test
    fun secondsToStreamTimestampUsesStreamTimeBase() {
        assertEquals(25L, FFmpegVideoDecoder.secondsToStreamTimestamp(1.0, 1.0 / 25.0))
        assertEquals(90_000L, FFmpegVideoDecoder.secondsToStreamTimestamp(1.0, 1.0 / 90_000.0))
        assertEquals(99_000L, FFmpegVideoDecoder.secondsToStreamTimestamp(1.0, 1.0 / 90_000.0, 9_000L))
    }

    @Test
    fun cliRequiresVideoPath() {
        assertNull(parsePlayerOptions(emptyArray()))
        assertNull(parsePlayerOptions(arrayOf("--cache-dir")))
    }
}
