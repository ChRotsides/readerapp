package com.readerapp.tts

import com.readerapp.tts.model.ModelSpec

enum class EngineKind { KOKORO, SUPERTONIC }

/** One selectable voice: [id] is the sherpa-onnx speaker id, [label] what the pickers show. */
data class VoiceOption(val id: Int, val label: String)

/**
 * A downloadable speech model. [id] is what settings persist; [tag] is the short marker that goes into audio cache
 * file names so clips from different models never collide. [dirName] (inside [spec]) must stay stable: it is how an
 * already-installed copy is recognised.
 */
data class TtsModel(
    val id: String,
    val tag: String,
    val name: String,
    val description: String,
    val engine: EngineKind,
    val spec: ModelSpec,
    val voices: List<VoiceOption>,
    val defaultVoice: Int,
    /** Kokoro only: lexicon files in the model directory (joined with commas for the engine), if any. */
    val lexiconFiles: List<String> = emptyList(),
    /** Kokoro only: language hint for the lexicon ("en-us"), if any. */
    val lang: String? = null,
) {
    /** [voice] if this model has it, otherwise the model's default. */
    fun validVoice(voice: Int): Int = if (voices.any { it.id == voice }) voice else defaultVoice

    fun voiceLabel(voice: Int): String = voices.firstOrNull { it.id == voice }?.label ?: "?"
}

/** The registry of supported models (the first entry is the default, and the one earlier app versions shipped). */
object TtsModels {
    private const val RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val MARGIN = 150_000_000L

    private val KOKORO_V019 = TtsModel(
        id = "kokoro-en-v0_19",
        tag = "k19",
        name = "Kokoro v0.19",
        description = "Natural voice. Needs the app on screen to keep up on this phone.",
        engine = EngineKind.KOKORO,
        spec = ModelSpec(
            url = RELEASE + "kokoro-en-v0_19.tar.bz2",
            archiveBytes = 319_625_534L,
            sha256 = "912804855a04745fa77a30be545b3f9a5d15c4d66db00b88cbcd4921df605ac7",
            dirName = "kokoro-en-v0_19",
            requiredFiles = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data"),
            extractedBytesEstimate = 345_000_000L,
            freeSpaceMarginBytes = MARGIN,
        ),
        // Speaker names in sherpa-onnx's id order for this model (the library only exposes the count).
        voices = listOf(
            "Default (US female)", "Bella (US female)", "Nicole (US female)", "Sarah (US female)", "Sky (US female)",
            "Adam (US male)", "Michael (US male)", "Emma (UK female)", "Isabella (UK female)",
            "George (UK male)", "Lewis (UK male)",
        ).mapIndexed { i, l -> VoiceOption(i, l) },
        defaultVoice = 1,
    )

    /** English voices of Kokoro v1.0 in speaker-id order; the rest of the 53 are other languages and are not offered. */
    private val KOKORO_V10_VOICE_CODES = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river",
        "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
        "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable",
        "bm_george", "bm_lewis",
    )

    private fun kokoroV10Label(code: String): String {
        val (prefix, name) = code.split('_')
        val kind = when (prefix) {
            "af" -> "US female"
            "am" -> "US male"
            "bf" -> "UK female"
            else -> "UK male"
        }
        return "${name.replaceFirstChar { it.uppercase() }} ($kind)"
    }

    private val KOKORO_V10 = TtsModel(
        id = "kokoro-multi-lang-v1_0",
        tag = "k10",
        name = "Kokoro v1.0",
        description = "Newer Kokoro with more voices. Same speed as v0.19.",
        engine = EngineKind.KOKORO,
        spec = ModelSpec(
            url = RELEASE + "kokoro-multi-lang-v1_0.tar.bz2",
            archiveBytes = 349_886_476L,
            sha256 = "60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4",
            dirName = "kokoro-multi-lang-v1_0",
            requiredFiles = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data", "lexicon-us-en.txt", "lexicon-zh.txt"),
            extractedBytesEstimate = 401_300_000L,
            freeSpaceMarginBytes = MARGIN,
        ),
        voices = KOKORO_V10_VOICE_CODES.mapIndexed { i, c -> VoiceOption(i, kokoroV10Label(c)) },
        defaultVoice = 3, // Heart
        lexiconFiles = listOf("lexicon-us-en.txt", "lexicon-zh.txt"),
        lang = "en-us",
    )

    private val SUPERTONIC = TtsModel(
        id = "supertonic-int8",
        tag = "st",
        name = "Supertonic",
        description = "Much faster. Keeps up with the screen off.",
        engine = EngineKind.SUPERTONIC,
        spec = ModelSpec(
            url = RELEASE + "sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2",
            archiveBytes = 84_692_981L,
            sha256 = "8c74359f63edd5045d47747f65331f0f6dbcbc91d7e898dd756d631295fe3259",
            dirName = "sherpa-onnx-supertonic-tts-int8-2026-03-06",
            requiredFiles = listOf(
                "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
                "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
            ),
            extractedBytesEstimate = 96_500_000L,
            freeSpaceMarginBytes = MARGIN,
        ),
        voices = (0..4).map { VoiceOption(it, "Female ${it + 1}") } + (5..9).map { VoiceOption(it, "Male ${it - 4}") },
        defaultVoice = 0,
    )

    val ALL: List<TtsModel> = listOf(KOKORO_V019, KOKORO_V10, SUPERTONIC)
    val DEFAULT: TtsModel = KOKORO_V019
    val DEFAULT_ID: String = DEFAULT.id

    /** The model with [id], or the default for an unknown / missing id. */
    fun byId(id: String?): TtsModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
