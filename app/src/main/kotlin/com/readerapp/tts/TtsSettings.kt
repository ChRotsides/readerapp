package com.readerapp.tts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** [voice] is the voice stored for the selected [modelId], already validated against that model's voice list. */
data class TtsPrefs(
    val modelId: String = TtsModels.DEFAULT_ID,
    val voice: Int = TtsModels.DEFAULT.defaultVoice,
    val speed: Float = TtsConfig.DEFAULT_SPEED,
    val numThreads: Int = TtsConfig.DEFAULT_THREADS,
    val cacheLimitMb: Int = TtsConfig.DEFAULT_CACHE_MB,
)

@Singleton
class TtsSettings @Inject constructor(private val store: DataStore<Preferences>) {
    val prefs: Flow<TtsPrefs> = store.data.map { p ->
        val d = TtsPrefs()
        val model = TtsModels.byId(p[MODEL])
        TtsPrefs(
            modelId = model.id,
            voice = model.validVoice(p[voiceKey(model)] ?: model.defaultVoice),
            speed = (p[SPEED] ?: d.speed).coerceIn(TtsConfig.SPEED_RANGE),
            numThreads = (p[THREADS] ?: d.numThreads).coerceIn(TtsConfig.THREADS_RANGE),
            cacheLimitMb = (p[CACHE_MB] ?: d.cacheLimitMb).coerceIn(TtsConfig.CACHE_MB_RANGE),
        )
    }

    suspend fun current(): TtsPrefs = prefs.first()

    suspend fun setModel(id: String) = store.edit { it[MODEL] = TtsModels.byId(id).id }

    /** Saves [v] as the voice of the currently selected model (each model keeps its own). */
    suspend fun setVoice(v: Int) = store.edit {
        val model = TtsModels.byId(it[MODEL])
        it[voiceKey(model)] = model.validVoice(v)
    }

    suspend fun setSpeed(v: Float) = store.edit { it[SPEED] = v.coerceIn(TtsConfig.SPEED_RANGE) }

    suspend fun setNumThreads(n: Int) = store.edit { it[THREADS] = n.coerceIn(TtsConfig.THREADS_RANGE) }

    suspend fun setCacheLimitMb(mb: Int) = store.edit { it[CACHE_MB] = mb.coerceIn(TtsConfig.CACHE_MB_RANGE) }

    private companion object {
        val MODEL = stringPreferencesKey("tts_model")

        /** The default model keeps the original `tts_voice` key so the voice chosen before models existed still applies. */
        fun voiceKey(model: TtsModel) =
            intPreferencesKey(if (model.id == TtsModels.DEFAULT_ID) "tts_voice" else "tts_voice_${model.id}")

        val SPEED = floatPreferencesKey("tts_speed")
        val THREADS = intPreferencesKey("tts_threads")
        val CACHE_MB = intPreferencesKey("tts_cache_mb")
    }
}
