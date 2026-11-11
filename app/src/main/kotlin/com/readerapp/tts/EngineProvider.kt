package com.readerapp.tts

import com.readerapp.tts.model.ModelManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Creates the engine lazily and recreates it when the requested model or the configured thread count changes. */
@Singleton
class EngineProvider @Inject constructor(
    private val models: ModelManager,
    private val settings: TtsSettings,
) {
    private val mutex = Mutex()
    private var engine: SherpaEngine? = null
    private var engineModel: String? = null
    private var threads = 0

    suspend fun get(modelId: String): TtsEngine = mutex.withLock {
        val model = TtsModels.byId(modelId)
        val wanted = settings.current().numThreads
        val existing = engine
        if (existing != null && threads == wanted && engineModel == model.id) return existing
        existing?.release()
        engine = null
        val installer = models.installer(model.id)
        check(installer.isInstalled()) { "The ${model.name} speech model isn't installed" }
        SherpaEngine(model, installer.modelDir, wanted).also { engine = it; engineModel = model.id; threads = wanted }
    }

    /** Frees the native model (hundreds of MB of RAM) when nothing is being read. */
    suspend fun release() = mutex.withLock {
        engine?.release()
        engine = null
        engineModel = null
    }
}
