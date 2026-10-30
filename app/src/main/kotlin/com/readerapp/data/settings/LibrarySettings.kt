package com.readerapp.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class LibraryOptions(
    /** Upload the chapters the user downloaded to the discovery server's search index. */
    val uploadChapters: Boolean = true,
    /** Adding a novel to the library downloads all of its chapters (and the ones a TOC refresh finds later). */
    val downloadWholeOnAdd: Boolean = false,
    /** Top N search results whose opening chapters are saved in the background; 0 = off. */
    val searchPrefetchResults: Int = 5,
    /** Opening chapters saved per such result. */
    val searchPrefetchChapters: Int = 10,
) {
    companion object {
        val RESULTS_RANGE = 0..10
        val CHAPTERS_RANGE = 0..30
    }
}

/** Offline-reading and search-index options (non-secret). */
@Singleton
class LibrarySettings @Inject constructor(private val store: DataStore<Preferences>) {
    val options: Flow<LibraryOptions> = store.data.map { p ->
        val d = LibraryOptions()
        LibraryOptions(
            uploadChapters = p[UPLOAD] ?: d.uploadChapters,
            downloadWholeOnAdd = p[DOWNLOAD_WHOLE] ?: d.downloadWholeOnAdd,
            searchPrefetchResults = p[PREFETCH_RESULTS] ?: d.searchPrefetchResults,
            searchPrefetchChapters = p[PREFETCH_CHAPTERS] ?: d.searchPrefetchChapters,
        )
    }

    suspend fun current(): LibraryOptions = options.first()

    suspend fun setUploadChapters(on: Boolean) = store.edit { it[UPLOAD] = on }

    suspend fun setDownloadWholeOnAdd(on: Boolean) = store.edit { it[DOWNLOAD_WHOLE] = on }

    suspend fun setSearchPrefetchResults(n: Int) = store.edit {
        it[PREFETCH_RESULTS] = n.coerceIn(LibraryOptions.RESULTS_RANGE.first, LibraryOptions.RESULTS_RANGE.last)
    }

    suspend fun setSearchPrefetchChapters(n: Int) = store.edit {
        it[PREFETCH_CHAPTERS] = n.coerceIn(LibraryOptions.CHAPTERS_RANGE.first, LibraryOptions.CHAPTERS_RANGE.last)
    }

    private companion object {
        val UPLOAD = booleanPreferencesKey("library_upload_chapters")
        val DOWNLOAD_WHOLE = booleanPreferencesKey("library_download_whole_on_add")
        val PREFETCH_RESULTS = intPreferencesKey("search_prefetch_results")
        val PREFETCH_CHAPTERS = intPreferencesKey("search_prefetch_chapters")
    }
}
