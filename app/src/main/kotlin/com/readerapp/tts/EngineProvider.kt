package com.readerapp.tts

import com.readerapp.tts.model.ModelManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Creates the Kokoro engine lazily and recreates it when the configured thread count changes. */
@Singleton
class EngineProvider @Inject constructor(
    private val models: ModelManager,
    private val settings: TtsSettings,
) {
    private val mutex = Mutex()
    private var engine: KokoroEngine? = null
    private var threads = 0

    suspend fun get(): TtsEngine = mutex.withLock {
        val wanted = settings.current().numThreads
        val existing = engine
        if (existing != null && threads == wanted) return existing
        existing?.release()
        check(models.installer.isInstalled()) { "The speech model isn't installed" }
        KokoroEngine(models.installer.modelDir, wanted).also { engine = it; threads = wanted }
    }

    /** Frees the native model (hundreds of MB of RAM) when nothing is being read. */
    suspend fun release() = mutex.withLock {
        engine?.release()
        engine = null
    }
}
