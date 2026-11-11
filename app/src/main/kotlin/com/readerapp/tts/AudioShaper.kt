package com.readerapp.tts

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Trims the near-silence the models put around every sentence, fades the edges so the cut is inaudible, and appends
 * a controlled pause. Without this each clip carries ~60 ms of leading and ~120 ms of trailing silence and the gap
 * between sentences cannot be tuned.
 */
object AudioShaper {
    /** A 10 ms window counts as sound above -42 dBFS. */
    private val THRESHOLD = 10.0.pow(-42.0 / 20.0).toFloat()
    private const val WINDOW_MS = 10
    private const val LEAD_MARGIN_MS = 10
    private const val TAIL_MARGIN_MS = 40
    private const val FADE_IN_MS = 3
    private const val FADE_OUT_MS = 15

    /** Returns the trimmed, faded clip followed by [pauseMs] of silence; [samples] itself if it holds no sound. */
    fun shape(samples: FloatArray, sampleRate: Int, pauseMs: Int): FloatArray {
        val win = max(1, sampleRate * WINDOW_MS / 1000)
        var first = -1
        var last = -1
        var w = 0
        var i = 0
        while (i < samples.size) {
            val end = min(i + win, samples.size)
            var sum = 0.0
            for (k in i until end) sum += samples[k].toDouble() * samples[k]
            if (sqrt(sum / (end - i)) > THRESHOLD) {
                if (first < 0) first = w
                last = w
            }
            i = end
            w++
        }
        if (first < 0) return samples

        val start = max(0, first * win - ms(sampleRate, LEAD_MARGIN_MS))
        val end = min(samples.size, (last + 1) * win + ms(sampleRate, TAIL_MARGIN_MS))
        val pause = ms(sampleRate, pauseMs.coerceAtLeast(0))
        val len = end - start
        val out = FloatArray(len + pause)
        System.arraycopy(samples, start, out, 0, len)
        val fadeIn = min(ms(sampleRate, FADE_IN_MS), len)
        for (k in 0 until fadeIn) out[k] *= k.toFloat() / fadeIn
        val fadeOut = min(ms(sampleRate, FADE_OUT_MS), len)
        for (k in 0 until fadeOut) out[len - 1 - k] *= k.toFloat() / fadeOut
        return out
    }

    /**
     * Silence to put after a sentence: short when the sentence was cut mid-way (it does not end in `.`, `!` or `?`),
     * longest at the end of a paragraph. Scaled by 1/[speed] (faster reading, shorter pauses).
     */
    fun pauseMs(text: String, endsParagraph: Boolean, speed: Float): Int {
        val base = when {
            text.lastOrNull() !in SENTENCE_END -> TtsConfig.PAUSE_PARTIAL_MS
            endsParagraph -> TtsConfig.PAUSE_PARAGRAPH_MS
            else -> TtsConfig.PAUSE_SENTENCE_MS
        }
        return (base / speed).roundToInt()
    }

    private val SENTENCE_END = setOf('.', '!', '?')

    private fun ms(sampleRate: Int, ms: Int): Int = (sampleRate.toLong() * ms / 1000).toInt()
}
