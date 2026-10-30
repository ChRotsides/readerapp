package com.readerapp.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun cache(max: Long) = AudioCache(tmp.newFolder(), max) { now++ }

    private fun key(sentence: Int, voice: Int = 1, speed: Float = 1f, text: String = "t$sentence") =
        AudioKey(7, "TRANSLATED", voice, speed, 0, sentence, AudioKey.hash(text))

    /** A fake WAV of [bytes] total bytes (header size counted). */
    private fun write(c: AudioCache, k: AudioKey, bytes: Int) = c.put(k) { it.writeBytes(ByteArray(bytes)) }

    @Test
    fun keyDependsOnEveryComponent() {
        val base = key(0)
        val names = setOf(
            base, base.copy(chapterId = 8), base.copy(variant = "FIXED"), base.copy(voice = 2), base.copy(speed = 1.25f),
            base.copy(paragraph = 1), base.copy(sentence = 1), base.copy(textHash = AudioKey.hash("other")),
        ).map { it.fileName() }
        assertEquals(8, names.toSet().size)
        assertEquals(base.fileName(), key(0).fileName())
    }

    @Test
    fun missAndPartialFilesAreNotHits() {
        val c = cache(10_000)
        assertNull(c.get(key(0)))
        write(c, key(0), 30) // header-sized junk is not a valid entry
        assertNull(c.get(key(0)))
        write(c, key(1), 500)
        assertNotNull(c.get(key(1)))
    }

    @Test
    fun evictsLeastRecentlyUsedFirst() {
        val c = cache(1_000)
        (0..3).forEach { write(c, key(it), 400) } // 1600 bytes, written oldest -> newest 0,1,2,3
        c.get(key(0)) // touch 0: now the most recent
        val removed = c.evict()
        assertEquals(2, removed) // 1600 -> 800 <= 1000
        assertNotNull(c.get(key(0)))
        assertNull(c.get(key(1)))
        assertNull(c.get(key(2)))
        assertNotNull(c.get(key(3)))
        assertTrue(c.totalBytes() <= 1_000)
    }

    @Test
    fun protectedFilesSurviveEviction() {
        val c = cache(500)
        (0..2).forEach { write(c, key(it), 400) }
        c.evict(protectedNames = setOf(key(0).fileName()))
        assertNotNull(c.get(key(0)))
        assertNull(c.get(key(1)))
        assertNull(c.get(key(2)))
    }

    @Test
    fun noEvictionUnderLimit() {
        val c = cache(10_000)
        write(c, key(0), 400)
        assertEquals(0, c.evict())
    }

    @Test
    fun putReplacesAndLeavesNoTempFile() {
        val c = cache(10_000)
        write(c, key(0), 100)
        write(c, key(0), 200)
        assertEquals(200, c.get(key(0))!!.length())
        assertFalse(File(c.file(key(0)).parentFile, c.file(key(0)).name + ".tmp").exists())
    }

    @Test
    fun wavRoundTripDuration() {
        val f = tmp.newFile("a.wav")
        WavWriter.write(f, FloatArray(24_000), 24_000)
        assertEquals(WavWriter.HEADER_BYTES + 48_000, f.length())
        assertEquals(1_000, WavWriter.durationMs(f))
        assertNotEquals(0L, f.length())
    }
}
