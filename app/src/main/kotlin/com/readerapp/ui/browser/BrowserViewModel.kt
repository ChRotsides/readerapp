package com.readerapp.ui.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.readerapp.scrape.FetchMode
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.ImportDraft
import com.readerapp.scrape.ImportResult
import com.readerapp.scrape.NovelImporter
import com.readerapp.ui.nav.BrowserRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowserUiState(
    val working: Boolean = false,
    val draft: ImportDraft? = null,
    val message: String? = null,
    val imported: Pair<Long, Long>? = null,
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importer: NovelImporter,
) : ViewModel() {
    val startUrl: String = savedStateHandle.toRoute<BrowserRoute>().url ?: DEFAULT_URL

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    /** Called with the DOM currently displayed in the in-app browser. */
    fun prepareImport(url: String, html: String) {
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            importer.prepare(FetchedPage(url, html, FetchMode.WEBVIEW))
                .onSuccess { draft -> _state.update { it.copy(working = false, draft = draft) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            working = false,
                            message = "Couldn't find chapter text on this page: ${e.message}. Open a chapter page and try again.",
                        )
                    }
                }
        }
    }

    fun confirmImport(title: String) {
        val draft = _state.value.draft ?: return
        _state.update { it.copy(draft = null, working = true) }
        viewModelScope.launch {
            when (val result = importer.import(draft, title)) {
                is ImportResult.Imported ->
                    _state.update { it.copy(working = false, imported = result.novelId to result.chapterId) }
                is ImportResult.Failed -> _state.update { it.copy(working = false, message = result.reason) }
            }
        }
    }

    fun dismissDraft() = _state.update { it.copy(draft = null) }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun importHandled() = _state.update { it.copy(imported = null) }

    fun fail(message: String) = _state.update { it.copy(working = false, message = message) }

    private companion object {
        const val DEFAULT_URL = "https://duckduckgo.com/"
    }
}
