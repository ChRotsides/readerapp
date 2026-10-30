package com.readerapp.tts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class TtsPrefs(
    val voice: Int = TtsConfig.DEFAULT_VOICE,
    val speed: Float = TtsConfig.DEFAULT_SPEED,
    val numThreads: Int = TtsConfig.DEFAULT_THREADS,
    val cacheLimitMb: Int = TtsConfig.DEFAULT_CACHE_MB,
)

@Singleton
class TtsSettings @Inject constructor(private val store: DataStore<Preferences>) {
    val prefs: Flow<TtsPrefs> = store.data.map { p ->
        val d = TtsPrefs()
        TtsPrefs(
            voice = (p[VOICE] ?: d.voice).coerceIn(0, TtsConfig.SPEAKERS.lastIndex),
            speed = (p[SPEED] ?: d.speed).coerceIn(TtsConfig.SPEED_RANGE),
            numThreads = (p[THREADS] ?: d.numThreads).coerceIn(TtsConfig.THREADS_RANGE),
            cacheLimitMb = (p[CACHE_MB] ?: d.cacheLimitMb).coerceIn(TtsConfig.CACHE_MB_RANGE),
        )
    }

    suspend fun current(): TtsPrefs = prefs.first()

    suspend fun setVoice(v: Int) = store.edit { it[VOICE] = v.coerceIn(0, TtsConfig.SPEAKERS.lastIndex) }

    suspend fun setSpeed(v: Float) = store.edit { it[SPEED] = v.coerceIn(TtsConfig.SPEED_RANGE) }

    suspend fun setNumThreads(n: Int) = store.edit { it[THREADS] = n.coerceIn(TtsConfig.THREADS_RANGE) }

    suspend fun setCacheLimitMb(mb: Int) = store.edit { it[CACHE_MB] = mb.coerceIn(TtsConfig.CACHE_MB_RANGE) }

    private companion object {
        val VOICE = intPreferencesKey("tts_voice")
        val SPEED = floatPreferencesKey("tts_speed")
        val THREADS = intPreferencesKey("tts_threads")
        val CACHE_MB = intPreferencesKey("tts_cache_mb")
    }
}
