package com.readerapp.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioShaperTest {
    private val rate = 24_000
    private fun n(ms: Int) = rate * ms / 1000

    /** [leadMs] of silence, [soundMs] of a loud sine, [tailMs] of silence. */
    private fun clip(leadMs: Int, soundMs: Int, tailMs: Int): FloatArray {
        val out = FloatArray(n(leadMs) + n(soundMs) + n(tailMs))
        for (i in 0 until n(soundMs)) out[n(leadMs) + i] = (0.5 * sin(2 * PI * 220 * i / rate)).toFloat()
        return out
    }

    @Test
    fun trimsToMarginsAndAppendsPause() {
        val shaped = AudioShaper.shape(clip(200, 500, 300), rate, pauseMs = 220)
        // 10 ms lead margin + 500 ms sound + 40 ms tail margin (window-aligned here) + 220 ms pause.
        assertEquals(n(10 + 500 + 40 + 220), shaped.size)
        assertTrue(shaped.takeLast(n(220)).all { it == 0f })
    }

    @Test
    fun fadesTheEdges() {
        val shaped = AudioShaper.shape(FloatArray(n(100)) { 0.5f }, rate, pauseMs = 0)
        assertEquals(0f, shaped.first(), 0f)
        assertEquals(0f, shaped.last(), 0f)
        assertEquals(0.5f, shaped[shaped.size / 2], 1e-6f)
        // Fade-in reaches full level after 3 ms, fade-out starts 15 ms before the end.
        assertEquals(0.5f, shaped[n(3)], 1e-6f)
        assertTrue(shaped[shaped.size - n(15) / 2] < 0.5f)
    }

    @Test
    fun quietAudioIsReturnedUnchanged() {
        val quiet = FloatArray(n(300)) { 0.001f } // about -60 dBFS
        assertSame(quiet, AudioShaper.shape(quiet, rate, 300))
        val empty = FloatArray(0)
        assertSame(empty, AudioShaper.shape(empty, rate, 300))
    }

    @Test
    fun keepsShortLeadAndTailWhenThereIsLessThanTheMargin() {
        val input = clip(0, 100, 0)
        val shaped = AudioShaper.shape(input, rate, 0)
        assertEquals(input.size, shaped.size)
    }

    @Test
    fun soundBelowThresholdAtTheEdgesIsCut() {
        val input = clip(100, 200, 100)
        for (i in 0 until n(100)) input[i] = 0.002f // -54 dBFS hiss
        val shaped = AudioShaper.shape(input, rate, 0)
        assertEquals(n(10 + 200 + 40), shaped.size)
    }

    @Test
    fun pauseOnlyAppendsZeros() {
        val shaped = AudioShaper.shape(clip(0, 100, 0), rate, 90)
        assertArrayEquals(FloatArray(n(90)), shaped.copyOfRange(shaped.size - n(90), shaped.size), 0f)
    }

    @Test
    fun pauseSelection() {
        assertEquals(90, AudioShaper.pauseMs("and then he went on,", endsParagraph = false, speed = 1f))
        assertEquals(90, AudioShaper.pauseMs("and then he went on,", endsParagraph = true, speed = 1f))
        assertEquals(220, AudioShaper.pauseMs("He left.", endsParagraph = false, speed = 1f))
        assertEquals(220, AudioShaper.pauseMs("Why?", endsParagraph = false, speed = 1f))
        assertEquals(420, AudioShaper.pauseMs("Run!", endsParagraph = true, speed = 1f))
        assertEquals(110, AudioShaper.pauseMs("He left.", endsParagraph = false, speed = 2f))
        assertEquals(147, AudioShaper.pauseMs("He left.", endsParagraph = false, speed = 1.5f))
    }
}
