package com.readerapp.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class PcmAudio(val samples: FloatArray, val sampleRate: Int)

/** Text to audio. Implementations are called from one coroutine at a time but must tolerate being called from any thread. */
interface TtsEngine {
    suspend fun synthesize(text: String, speaker: Int, speed: Float): PcmAudio

    fun release()
}

/**
 * A [TtsModel] through sherpa-onnx (Kokoro or Supertonic). The native engine is not thread-safe, so it is created and
 * used only on one dedicated thread. Loading the model takes a couple of seconds, so it happens lazily on that
 * thread on first use.
 */
class SherpaEngine(private val model: TtsModel, private val modelDir: File, private val numThreads: Int) : TtsEngine {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tts-engine").apply { priority = Thread.NORM_PRIORITY } }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private var tts: OfflineTts? = null

    private fun path(name: String) = File(modelDir, name).absolutePath

    private fun modelConfig(): OfflineTtsModelConfig = when (model.engine) {
        EngineKind.KOKORO -> OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = path("model.onnx"),
                voices = path("voices.bin"),
                tokens = path("tokens.txt"),
                dataDir = path("espeak-ng-data"),
                lexicon = model.lexiconFiles.joinToString(",") { path(it) },
                lang = model.lang.orEmpty(),
            ),
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
        )
        EngineKind.SUPERTONIC -> OfflineTtsModelConfig(
            supertonic = OfflineTtsSupertonicModelConfig(
                durationPredictor = path("duration_predictor.int8.onnx"),
                textEncoder = path("text_encoder.int8.onnx"),
                vectorEstimator = path("vector_estimator.int8.onnx"),
                vocoder = path("vocoder.int8.onnx"),
                ttsJson = path("tts.json"),
                unicodeIndexer = path("unicode_indexer.bin"),
                voiceStyle = path("voice.bin"),
            ),
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
        )
    }

    private fun engine(): OfflineTts = tts ?: OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelConfig())).also { tts = it }

    override suspend fun synthesize(text: String, speaker: Int, speed: Float): PcmAudio = withContext(dispatcher) {
        val e = engine()
        val sid = speaker.coerceIn(0, (e.numSpeakers() - 1).coerceAtLeast(0))
        val audio = when (model.engine) {
            EngineKind.KOKORO -> e.generate(text, sid, speed)
            EngineKind.SUPERTONIC -> e.generateWithConfig(
                text,
                GenerationConfig(sid = sid, speed = speed, numSteps = SUPERTONIC_STEPS, extra = mapOf("lang" to "en")),
            )
        }
        PcmAudio(audio.samples, audio.sampleRate)
    }

    override fun release() {
        executor.execute {
            tts?.release()
            tts = null
        }
        executor.shutdown()
    }

    private companion object {
        /** Denoising steps: fewer is faster, and 5 is what keeps up on this phone with the screen off. */
        const val SUPERTONIC_STEPS = 5
    }
}
