package com.readerapp.tts

/** Everything that identifies the downloadable Kokoro model, in one place. */
object TtsConfig {
    /** Full-precision English Kokoro v0.19 from sherpa-onnx's `tts-models` release (11 speakers). */
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
    const val MODEL_ARCHIVE_BYTES = 319_625_534L
    const val MODEL_SHA256 = "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7"

    /** Top-level directory inside the archive (and the name it is installed under). */
    const val MODEL_DIR_NAME = "kokoro-en-v0_19"

    /** Files that must exist (relative to the model directory) for the install to count as complete. */
    val REQUIRED_FILES = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data")

    /** Approximate size of the extracted model, used for the free-space check. */
    const val EXTRACTED_BYTES_ESTIMATE = 345_000_000L
    const val FREE_SPACE_MARGIN_BYTES = 150_000_000L

    /** Speaker names in sherpa-onnx's id order for this model (the library only exposes the count). */
    val SPEAKERS = listOf(
        "Default (US female)", "Bella (US female)", "Nicole (US female)", "Sarah (US female)", "Sky (US female)",
        "Adam (US male)", "Michael (US male)", "Emma (UK female)", "Isabella (UK female)",
        "George (UK male)", "Lewis (UK male)",
    )

    const val DEFAULT_VOICE = 1
    const val DEFAULT_SPEED = 1.0f
    val SPEED_RANGE = 0.6f..1.8f
    const val DEFAULT_THREADS = 4
    val THREADS_RANGE = 1..8
    const val DEFAULT_CACHE_MB = 1024
    val CACHE_MB_RANGE = 128..8192

    /** Keep roughly this many sentences generated ahead of the playhead. */
    const val LOOKAHEAD_SENTENCES = 20

    const val NOT_ENGLISH_MESSAGE =
        "Kokoro reads English only. This text isn't English - switch to the Translated variant (or translate it) and try again."
}
