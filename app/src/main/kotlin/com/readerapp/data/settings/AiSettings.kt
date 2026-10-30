package com.readerapp.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret AI settings. [monthlyCapUsd] stops adapter generation/repair once month-to-date spend reaches it. */
@Singleton
class AiSettings @Inject constructor(private val store: DataStore<Preferences>) {
    val monthlyCapUsd: Flow<Double> = store.data.map { it[CAP] ?: DEFAULT_CAP_USD }

    suspend fun currentCapUsd(): Double = monthlyCapUsd.first()

    suspend fun setMonthlyCapUsd(usd: Double) = store.edit { it[CAP] = usd.coerceIn(0.0, 1000.0) }

    /** Default translation target for novels without their own. */
    val targetLanguage: Flow<String> = store.data.map { it[TARGET]?.takeIf { v -> v.isNotBlank() } ?: DEFAULT_TARGET_LANGUAGE }

    suspend fun currentTargetLanguage(): String = targetLanguage.first()

    suspend fun setTargetLanguage(language: String) = store.edit { it[TARGET] = language.trim() }

    /** How many chapters after the one being read the background queue processes. */
    val processAhead: Flow<Int> = store.data.map { it[AHEAD] ?: DEFAULT_PROCESS_AHEAD }

    suspend fun currentProcessAhead(): Int = processAhead.first()

    suspend fun setProcessAhead(n: Int) = store.edit { it[AHEAD] = n.coerceIn(0, 20) }

    companion object {
        const val DEFAULT_CAP_USD = 5.0
        const val DEFAULT_TARGET_LANGUAGE = "English"
        const val DEFAULT_PROCESS_AHEAD = 3
        private val CAP = doublePreferencesKey("ai_monthly_cap_usd")
        private val TARGET = stringPreferencesKey("ai_target_language")
        private val AHEAD = intPreferencesKey("ai_process_ahead")
    }
}
