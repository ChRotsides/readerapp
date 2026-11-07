package com.readerapp.ui.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.readerapp.data.settings.SearchTuning
import androidx.lifecycle.viewModelScope
import com.readerapp.data.db.Novel
import com.readerapp.data.db.SearchHistoryItem
import com.readerapp.offline.SearchResultPrefetch
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.LibraryMatch
import com.readerapp.search.LibrarySource
import com.readerapp.search.SearchException
import com.readerapp.search.SearchFilters
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.SimilarSearch
import com.readerapp.search.SimilarSeed
import com.readerapp.search.SimilarStage
import com.readerapp.search.Work
import com.readerapp.search.history.SavedSearch
import com.readerapp.search.history.SearchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DiscoverMode { DESCRIBE, TITLE }

/** The description the server saves a similar-novels search under (and the query box shows). */
const val SIMILAR_PREFIX = "Similar to "

fun similarLabel(title: String) = SIMILAR_PREFIX + title

/** The editable form of "what I searched for". Numbers and tag lists are kept as text while editing. */
data class EditableQuery(
    val scenes: List<String> = emptyList(),
    val synopsis: String = "",
    val tags: String = "",
    val language: String = "",
    val status: String = "",
    val minChapters: String = "",
    val maxChapters: String = "",
    val includeTags: String = "",
    val excludeTags: String = "",
) {
    fun filters(): SearchFilters = SearchFilters(
        language = language.trim().ifEmpty { null },
        status = status.trim().ifEmpty { null },
        minChapters = minChapters.trim().toIntOrNull(),
        maxChapters = maxChapters.trim().toIntOrNull(),
        includeTags = splitList(includeTags),
        excludeTags = splitList(excludeTags),
    )

    companion object {
        fun splitList(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        fun from(r: SearchResponse) = EditableQuery(
            scenes = r.scenes,
            synopsis = r.synopsis,
            tags = r.tags.joinToString(", "),
            language = r.filters.language.orEmpty(),
            status = r.filters.status.orEmpty(),
            minChapters = r.filters.minChapters?.toString().orEmpty(),
            maxChapters = r.filters.maxChapters?.toString().orEmpty(),
            includeTags = r.filters.includeTags.joinToString(", "),
            excludeTags = r.filters.excludeTags.joinToString(", "),
        )
    }
}

data class DiscoverUiState(
    val mode: DiscoverMode = DiscoverMode.DESCRIBE,
    val description: String = "",
    val titleQuery: String = "",
    val loading: Boolean = false,
    val results: List<Work>? = null,
    /** The description the shown [results] / [query] belong to. */
    val searchedDescription: String? = null,
    val query: EditableQuery? = null,
    val reranked: Boolean = false,
    /** "jev" / "claude" when the shown results were reranked. */
    val rerankedBy: String? = null,
    val notes: List<String> = emptyList(),
    val error: String? = null,
    /** Set while the shown [results] are the answer of "Find similar": the seed novel's title. */
    val similarTitle: String? = null,
    /** Where a running similar search is (null otherwise). */
    val stage: SimilarStage? = null,
    /** The last similar search failed and [DiscoverViewModel.retrySimilar] can run it again. */
    val retrySimilar: Boolean = false,
    val library: List<Novel> = emptyList(),
    /** workId -> library novel id. */
    val inLibrary: Map<String, Long> = emptyMap(),
    /** workId -> hidden novel whose opening chapters were saved by search prefetch (readable offline). */
    val saved: Map<String, Long> = emptyMap(),
    val savedNovels: List<Novel> = emptyList(),
    /** Saved searches, newest first. */
    val recent: List<SearchHistoryItem> = emptyList(),
    /** Set while [results] are a saved search shown from the local history (not a fresh answer). */
    val viewingSaved: SavedSearch? = null,
) {
    /** Previous searches whose description contains what is being typed (case-insensitive), best for suggestions. */
    val suggestions: List<SearchHistoryItem>
        get() {
            val typed = description.trim()
            if (typed.isEmpty()) return emptyList()
            return recent.filter { it.description.contains(typed, ignoreCase = true) && !it.description.trim().equals(typed, ignoreCase = true) }.take(MAX_SUGGESTIONS)
        }

    companion object {
        const val MAX_SUGGESTIONS = 5
    }
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val api: DiscoveryApi,
    librarySource: LibrarySource,
    private val prefetch: SearchResultPrefetch,
    private val history: SearchHistoryRepository,
    private val tuning: SearchTuning,
    private val similar: SimilarSearch,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    private var job: Job? = null
    /** The seed of the similar search on screen, so Search on its unedited query box repeats it. */
    private var similarSeed: SimilarSeed? = null

    init {
        viewModelScope.launch { history.recent.collect { recent -> _state.update { it.copy(recent = recent) } } }
        viewModelScope.launch {
            librarySource.novels().collect { lib ->
                _state.update { s -> s.copy(library = lib, inLibrary = LibraryMatch.matches(s.results.orEmpty(), lib)) }
            }
        }
        viewModelScope.launch {
            librarySource.savedFromSearch().collect { hidden ->
                _state.update { s -> s.copy(savedNovels = hidden, saved = LibraryMatch.matches(s.results.orEmpty(), hidden)) }
            }
        }
    }

    fun setMode(mode: DiscoverMode) = _state.update { it.copy(mode = mode, error = null) }
    fun setDescription(text: String) = _state.update { it.copy(description = text) }
    fun setTitleQuery(text: String) = _state.update { it.copy(titleQuery = text) }
    fun editQuery(change: EditableQuery.() -> EditableQuery) =
        _state.update { s -> s.query?.let { s.copy(query = it.change()) } ?: s }

    /** Fresh search: the server writes the scenes / synopsis / tags itself. */
    fun search() {
        val s = _state.value
        val description = s.description.trim()
        if (description.isEmpty()) return
        val seed = similarSeed
        // The box still shows the similar-search label: run that search again instead of describing "Similar to ...".
        if (seed != null && s.similarTitle != null && description == similarLabel(s.similarTitle)) return runSimilar(seed)
        run(SearchRequest(description = description)).also { _state.update { it.copy(searchedDescription = description) } }
    }

    /** Starts a similar-novels search from the screen's route; a repeated call for the same seed (rotation) does nothing. */
    fun findSimilar(seed: SimilarSeed) {
        // The handled seed survives rotation and process death (the route's arguments do too): it is run once per screen.
        val key = seed.toString()
        if (handle.get<String>(HANDLED_SEED) == key) {
            if (similarSeed == null) similarSeed = seed
            return
        }
        handle[HANDLED_SEED] = key
        runSimilar(seed)
    }

    fun retrySimilar() {
        similarSeed?.let(::runSimilar)
    }

    private fun runSimilar(seed: SimilarSeed) {
        similarSeed = seed
        launchLoading {
            val found = try {
                similar.find(seed) { stage -> _state.update { it.copy(stage = stage) } }
            } catch (e: Exception) {
                if (e !is CancellationException) _state.update { it.copy(retrySimilar = true) }
                throw e
            }
            val r = found.response
            val title = found.seedTitle ?: "this novel"
            val label = similarLabel(title)
            val request = found.request ?: (seed as? SimilarSeed.Replay)?.request
            runCatching { if (request != null) history.recordSimilar(request, label, r) else history.record(SearchRequest(description = label), r) }.onFailure { if (it is CancellationException) throw it }
            _state.update { s ->
                s.copy(loading = false, stage = null, mode = DiscoverMode.DESCRIBE, description = label, searchedDescription = label, similarTitle = title,
                    results = r.results, query = null, reranked = r.reranked, rerankedBy = r.rerankProviderUsed, notes = r.notes, viewingSaved = null,
                    inLibrary = LibraryMatch.matches(r.results, s.library), saved = LibraryMatch.matches(r.results, s.savedNovels))
            }
            prefetch.onResults(r.results)
        }
    }

    /** Re-sends the (possibly edited) scenes, synopsis, tags and filters with the original description. */
    fun searchAgain() {
        val s = _state.value
        val q = s.query ?: return
        val description = s.searchedDescription ?: s.description.trim()
        if (description.isEmpty()) return
        val scenes = q.scenes.map { it.trim() }.filter { it.isNotEmpty() }
        run(
            SearchRequest(
                description = description,
                filters = q.filters(),
                scenes = scenes,
                synopsis = q.synopsis.trim(),
                tags = EditableQuery.splitList(q.tags),
            ),
        )
    }

    /** Shows a saved search's results from the local history (downloading them first if this device only has the list row). */
    fun openSaved(id: String) {
        launchLoading {
            val saved = history.open(id)
            if (saved == null) {
                _state.update { it.copy(loading = false, error = "That search is no longer in your history.") }
                return@launchLoading
            }
            val r = saved.response
            val similarTitle = saved.similar?.let { saved.description.removePrefix(SIMILAR_PREFIX) }
            similarSeed = saved.similar?.let { SimilarSeed.Replay(it) }
            _state.update { s ->
                s.copy(loading = false, mode = DiscoverMode.DESCRIBE, description = saved.description, searchedDescription = saved.description,
                    similarTitle = similarTitle, results = r.results, query = if (similarTitle != null) null else EditableQuery.from(r), reranked = r.reranked, rerankedBy = r.rerankProviderUsed, notes = r.notes, viewingSaved = saved,
                    inLibrary = LibraryMatch.matches(r.results, s.library), saved = LibraryMatch.matches(r.results, s.savedNovels))
            }
        }
    }

    /** Re-runs the saved search exactly as it was first sent; the new answer is saved as a new history entry. */
    fun searchSavedAgain() {
        val saved = _state.value.viewingSaved ?: return
        saved.similar?.let { return runSimilar(SimilarSeed.Replay(it)) }
        run(saved.request).also { _state.update { it.copy(searchedDescription = saved.description) } }
    }

    fun deleteSaved(id: String) {
        _state.update { if (it.viewingSaved?.id == id) it.copy(viewingSaved = null) else it }
        viewModelScope.launch { history.delete(id) }
    }

    fun clearHistory() {
        _state.update { it.copy(viewingSaved = null) }
        viewModelScope.launch { history.clear() }
    }

    fun searchTitle() {
        val q = _state.value.titleQuery.trim()
        if (q.isEmpty()) return
        launchLoading {
            val works = api.searchTitle(q)
            _state.update { s ->
                s.copy(loading = false, results = works, query = null, similarTitle = null, searchedDescription = null, reranked = false, rerankedBy = null, notes = emptyList(), viewingSaved = null,
                    inLibrary = LibraryMatch.matches(works, s.library), saved = LibraryMatch.matches(works, s.savedNovels))
            }
            prefetch.onResults(works)
        }
    }

    private fun run(base: SearchRequest) = launchLoading {
        val request = tuning.apply(base)
        val r = api.search(request)
        runCatching { history.record(request, r) }.onFailure { if (it is CancellationException) throw it }
        _state.update { s ->
            s.copy(loading = false, similarTitle = null, results = r.results, query = EditableQuery.from(r), reranked = r.reranked, rerankedBy = r.rerankProviderUsed, notes = r.notes, viewingSaved = null,
                inLibrary = LibraryMatch.matches(r.results, s.library), saved = LibraryMatch.matches(r.results, s.savedNovels))
        }
        prefetch.onResults(r.results)
    }

    private fun launchLoading(block: suspend () -> Unit) {
        job?.cancel()
        _state.update { it.copy(loading = true, stage = null, retrySimilar = false, error = null) }
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchException) {
                _state.update { it.copy(loading = false, stage = null, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, stage = null, error = e.message ?: "Search failed") }
            }
        }
    }

    private companion object {
        const val HANDLED_SEED = "similarSeed"
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
