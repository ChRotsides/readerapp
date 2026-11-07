package com.readerapp.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.readerapp.search.SearchRequest
import com.readerapp.search.SimilarRequest
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
    /** Hypothetical scenes Claude writes per discovery search (each is matched against chapter text). */
    val searchScenes: Int = 3,
    /** Top retrieved candidates Claude reranks per discovery search. */
    val rerankPool: Int = 20,
    /** Who reranks the retrieved candidates: "jev" or "claude". */
    val reranker: String = "jev",
) {
    companion object {
        val RESULTS_RANGE = 0..10
        val CHAPTERS_RANGE = 0..30
        val SCENES_RANGE = 1..8
        val RERANK_RANGE = 5..100
        const val RERANK_STEP = 5
        val RERANKERS = listOf("jev", "claude")
    }
}

/** Adds the user's search options (scene count, rerank pool) to a discovery search request. */
fun interface SearchTuning {
    suspend fun apply(request: SearchRequest): SearchRequest

    /** The same for a similar-novels search. */
    suspend fun applySimilar(request: SimilarRequest): SimilarRequest = request
}

class SettingsSearchTuning @Inject constructor(private val settings: LibrarySettings) : SearchTuning {
    override suspend fun apply(request: SearchRequest): SearchRequest {
        val o = settings.current()
        return request.copy(sceneCount = request.sceneCount ?: o.searchScenes, rerankPool = request.rerankPool ?: o.rerankPool,
            rerankProvider = request.rerankProvider ?: o.reranker)
    }

    override suspend fun applySimilar(request: SimilarRequest): SimilarRequest {
        val o = settings.current()
        return request.copy(sceneCount = request.sceneCount ?: o.searchScenes, rerankPool = request.rerankPool ?: o.rerankPool,
            rerankProvider = request.rerankProvider ?: o.reranker)
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
            searchScenes = p[SEARCH_SCENES] ?: d.searchScenes,
            rerankPool = p[RERANK_POOL] ?: d.rerankPool,
            reranker = p[RERANKER]?.takeIf { it in LibraryOptions.RERANKERS } ?: d.reranker,
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

    suspend fun setSearchScenes(n: Int) = store.edit {
        it[SEARCH_SCENES] = n.coerceIn(LibraryOptions.SCENES_RANGE.first, LibraryOptions.SCENES_RANGE.last)
    }

    suspend fun setRerankPool(n: Int) = store.edit {
        it[RERANK_POOL] = n.coerceIn(LibraryOptions.RERANK_RANGE.first, LibraryOptions.RERANK_RANGE.last)
    }

    suspend fun setReranker(value: String) = store.edit {
        it[RERANKER] = value.takeIf { v -> v in LibraryOptions.RERANKERS } ?: LibraryOptions().reranker
    }

    private companion object {
        val UPLOAD = booleanPreferencesKey("library_upload_chapters")
        val DOWNLOAD_WHOLE = booleanPreferencesKey("library_download_whole_on_add")
        val PREFETCH_RESULTS = intPreferencesKey("search_prefetch_results")
        val PREFETCH_CHAPTERS = intPreferencesKey("search_prefetch_chapters")
        val SEARCH_SCENES = intPreferencesKey("search_scenes")
        val RERANK_POOL = intPreferencesKey("search_rerank_pool")
        val RERANKER = stringPreferencesKey("search_reranker")
    }
}
