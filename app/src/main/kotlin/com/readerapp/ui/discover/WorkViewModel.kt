package com.readerapp.ui.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.LibraryMatch
import com.readerapp.search.LibrarySource
import com.readerapp.search.NovelPageImport
import com.readerapp.search.NovelPageImporter
import com.readerapp.search.NovelPageRequest
import com.readerapp.search.SearchException
import com.readerapp.search.Work
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot outcome of "Add to library" for the screen to act on. */
sealed interface WorkEvent {
    data class OpenNovel(val novelId: Long) : WorkEvent
    data class OpenBrowser(val url: String, val message: String) : WorkEvent
    data class Message(val text: String) : WorkEvent
}

data class WorkUiState(
    val loading: Boolean = true,
    val work: Work? = null,
    val error: String? = null,
    val libraryNovelId: Long? = null,
    /** URL currently being added, if any. */
    val importingUrl: String? = null,
    val event: WorkEvent? = null,
)

@HiltViewModel
class WorkViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: DiscoveryApi,
    private val library: LibrarySource,
    private val importer: NovelPageImporter,
) : ViewModel() {
    private val workId: String = savedStateHandle.get<String>("workId").orEmpty()
    private val _state = MutableStateFlow(WorkUiState())
    val state: StateFlow<WorkUiState> = _state.asStateFlow()
    private var novels: List<com.readerapp.data.db.Novel> = emptyList()

    init {
        load()
        viewModelScope.launch {
            library.novels().collect { lib ->
                novels = lib
                _state.update { s -> s.copy(libraryNovelId = s.work?.let { LibraryMatch.find(it, lib)?.id }) }
            }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val work = api.work(workId)
                _state.update { it.copy(loading = false, work = work, libraryNovelId = LibraryMatch.find(work, novels)?.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun addToLibrary(url: String) {
        val work = _state.value.work ?: return
        if (_state.value.importingUrl != null) return
        _state.update { it.copy(importingUrl = url) }
        viewModelScope.launch {
            val event = try {
                when (val r = importer.import(
                    NovelPageRequest(
                        url = url, title = work.title, author = work.authors.firstOrNull(), coverUrl = work.coverUrl,
                        synopsis = work.synopsis, language = work.language,
                    ),
                )) {
                    is NovelPageImport.AlreadyInLibrary -> WorkEvent.OpenNovel(r.novelId)
                    is NovelPageImport.Imported -> WorkEvent.OpenNovel(r.novelId)
                    is NovelPageImport.NeedsChapter -> WorkEvent.OpenBrowser(
                        r.browserUrl,
                        "${r.reason} Open chapter 1, then choose Add to library in the browser.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WorkEvent.Message(e.message ?: "Could not add the novel")
            }
            _state.update { it.copy(importingUrl = null, event = event) }
        }
    }

    fun eventHandled() = _state.update { it.copy(event = null) }
}
