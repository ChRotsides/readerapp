package com.readerapp.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
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
 * Kokoro through sherpa-onnx. The native engine is not thread-safe, so it is created and used only on one dedicated
 * thread. Loading the ~330 MB model takes a couple of seconds, so it happens lazily on that thread on first use.
 */
class KokoroEngine(private val modelDir: File, private val numThreads: Int) : TtsEngine {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "kokoro-tts").apply { priority = Thread.NORM_PRIORITY } }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private var tts: OfflineTts? = null

    private fun engine(): OfflineTts = tts ?: OfflineTts(
        assetManager = null,
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(modelDir, "model.onnx").absolutePath,
                    voices = File(modelDir, "voices.bin").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
        ),
    ).also { tts = it }

    override suspend fun synthesize(text: String, speaker: Int, speed: Float): PcmAudio = withContext(dispatcher) {
        val e = engine()
        val sid = speaker.coerceIn(0, (e.numSpeakers() - 1).coerceAtLeast(0))
        val audio = e.generate(text, sid, speed)
        PcmAudio(audio.samples, audio.sampleRate)
    }

    override fun release() {
        executor.execute {
            tts?.release()
            tts = null
        }
        executor.shutdown()
    }
}
