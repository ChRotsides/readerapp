package com.readerapp.tts

/** Shared TTS tunables. The downloadable models themselves are described in [TtsModels]. */
object TtsConfig {
    const val DEFAULT_SPEED = 1.0f
    val SPEED_RANGE = 0.6f..1.8f
    const val DEFAULT_THREADS = 4
    val THREADS_RANGE = 1..8
    const val DEFAULT_CACHE_MB = 1024
    val CACHE_MB_RANGE = 128..8192

    /** Keep roughly this many sentences generated ahead of the playhead. */
    const val LOOKAHEAD_SENTENCES = 20

    /**
     * Bump when [AudioShaper] or the sentence cleaning changes what a cached clip contains; it is part of the cache
     * file name, so older clips are never reused (they age out through the LRU eviction).
     */
    const val SHAPING_VERSION = 1

    /** Silence appended after a clip (before dividing by the playback speed). */
    const val PAUSE_PARTIAL_MS = 90 // sentence cut at a comma: the sentence continues
    const val PAUSE_SENTENCE_MS = 220
    const val PAUSE_PARAGRAPH_MS = 420

    const val NOT_ENGLISH_MESSAGE =
        "The speech models read English only. This text isn't English - switch to the Translated variant (or translate it) and try again."
}
