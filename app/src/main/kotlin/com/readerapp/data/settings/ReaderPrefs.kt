package com.readerapp.data.settings

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

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class ReaderPrefs(
    val fontSizeSp: Float = 18f,
    val lineSpacing: Float = 1.6f,
    val prefetchCount: Int = 5,
    val themeMode: ThemeMode = ThemeMode.DARK,
) {
    companion object {
        val FONT_SIZE_RANGE = 12f..32f
        val LINE_SPACING_RANGE = 1.1f..2.4f
        val PREFETCH_RANGE = 0..20
    }
}

@Singleton
class ReaderPrefsRepository @Inject constructor(private val store: DataStore<Preferences>) {
    val prefs: Flow<ReaderPrefs> = store.data.map { p ->
        val d = ReaderPrefs()
        ReaderPrefs(
            fontSizeSp = p[FONT_SIZE] ?: d.fontSizeSp,
            lineSpacing = p[LINE_SPACING] ?: d.lineSpacing,
            prefetchCount = p[PREFETCH] ?: d.prefetchCount,
            themeMode = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
        )
    }

    suspend fun current(): ReaderPrefs = prefs.first()

    suspend fun setFontSize(sp: Float) = store.edit { it[FONT_SIZE] = sp.coerceIn(ReaderPrefs.FONT_SIZE_RANGE) }

    suspend fun setLineSpacing(v: Float) = store.edit { it[LINE_SPACING] = v.coerceIn(ReaderPrefs.LINE_SPACING_RANGE) }

    suspend fun setPrefetchCount(n: Int) = store.edit {
        it[PREFETCH] = n.coerceIn(ReaderPrefs.PREFETCH_RANGE.first, ReaderPrefs.PREFETCH_RANGE.last)
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }

    private companion object {
        val FONT_SIZE = floatPreferencesKey("reader_font_size")
        val LINE_SPACING = floatPreferencesKey("reader_line_spacing")
        val PREFETCH = intPreferencesKey("reader_prefetch_count")
        val THEME = stringPreferencesKey("theme_mode")
    }
}
