package com.readerapp.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.data.db.Novel
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.LibraryMatch
import com.readerapp.search.LibrarySource
import com.readerapp.search.SearchException
import com.readerapp.search.SearchFilters
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.Work
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
    val notes: List<String> = emptyList(),
    val error: String? = null,
    val library: List<Novel> = emptyList(),
    /** workId -> library novel id. */
    val inLibrary: Map<String, Long> = emptyMap(),
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val api: DiscoveryApi,
    librarySource: LibrarySource,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            librarySource.novels().collect { lib ->
                _state.update { s -> s.copy(library = lib, inLibrary = LibraryMatch.matches(s.results.orEmpty(), lib)) }
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
        val description = _state.value.description.trim()
        if (description.isEmpty()) return
        run { api.search(SearchRequest(description = description)) }.also { _state.update { it.copy(searchedDescription = description) } }
    }

    /** Re-sends the (possibly edited) scenes, synopsis, tags and filters with the original description. */
    fun searchAgain() {
        val s = _state.value
        val q = s.query ?: return
        val description = s.searchedDescription ?: s.description.trim()
        if (description.isEmpty()) return
        val scenes = q.scenes.map { it.trim() }.filter { it.isNotEmpty() }
        run {
            api.search(
                SearchRequest(
                    description = description,
                    filters = q.filters(),
                    scenes = scenes,
                    synopsis = q.synopsis.trim(),
                    tags = EditableQuery.splitList(q.tags),
                ),
            )
        }
    }

    fun searchTitle() {
        val q = _state.value.titleQuery.trim()
        if (q.isEmpty()) return
        launchLoading {
            val works = api.searchTitle(q)
            _state.update { s ->
                s.copy(loading = false, results = works, query = null, searchedDescription = null, reranked = false, notes = emptyList(),
                    inLibrary = LibraryMatch.matches(works, s.library))
            }
        }
    }

    private fun run(call: suspend () -> SearchResponse) = launchLoading {
        val r = call()
        _state.update { s ->
            s.copy(loading = false, results = r.results, query = EditableQuery.from(r), reranked = r.reranked, notes = r.notes,
                inLibrary = LibraryMatch.matches(r.results, s.library))
        }
    }

    private fun launchLoading(block: suspend () -> Unit) {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Search failed") }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
