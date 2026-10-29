package com.readerapp.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
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

    companion object {
        const val DEFAULT_CAP_USD = 5.0
        private val CAP = doublePreferencesKey("ai_monthly_cap_usd")
    }
}
