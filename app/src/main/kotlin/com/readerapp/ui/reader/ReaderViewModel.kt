package com.readerapp.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.readerapp.data.db.Chapter
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.settings.ReaderPrefs
import com.readerapp.data.settings.ReaderPrefsRepository
import com.readerapp.scrape.ChapterFetcher
import com.readerapp.scrape.FetchOutcome
import com.readerapp.scrape.PrefetchScheduler
import com.readerapp.ui.nav.ReaderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Error(val chapter: Chapter?, val message: String, val locked: Boolean) : ReaderUiState

    /** [paragraphs] is the ORIGINAL variant for now; M3/M4 pick FIXED/TRANSLATED here. */
    data class Content(
        val chapter: Chapter,
        val paragraphs: List<String>,
        val hasPrev: Boolean,
        val hasNext: Boolean,
    ) : ReaderUiState
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val chapters: ChapterRepository,
    private val novels: NovelRepository,
    private val fetcher: ChapterFetcher,
    private val prefsRepo: ReaderPrefsRepository,
    private val prefetch: PrefetchScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val prefs: StateFlow<ReaderPrefs> = prefsRepo.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    private var loadJob: Job? = null

    init {
        val initial = savedStateHandle.get<Long>(KEY_CHAPTER_ID) ?: savedStateHandle.toRoute<ReaderRoute>().chapterId
        load(initial)
    }

    fun load(chapterId: Long) {
        loadJob?.cancel()
        savedStateHandle[KEY_CHAPTER_ID] = chapterId
        _state.value = ReaderUiState.Loading
        loadJob = viewModelScope.launch {
            val chapter = chapters.get(chapterId)
                ?: return@launch run { _state.value = ReaderUiState.Error(null, "Chapter not found", locked = false) }

            var text = chapters.getText(chapterId)
            if (text == null) {
                when (val outcome = fetcher.ensureFetched(chapterId)) {
                    is FetchOutcome.Success -> text = chapters.getText(chapterId)
                    is FetchOutcome.Locked ->
                        return@launch run { _state.value = ReaderUiState.Error(chapter, outcome.reason, locked = true) }
                    is FetchOutcome.Failed ->
                        return@launch run { _state.value = ReaderUiState.Error(chapter, outcome.reason, locked = false) }
                }
            }
            val fresh = chapters.get(chapterId) ?: chapter
            val paragraphs = text?.paragraphs.orEmpty()
            _state.value = ReaderUiState.Content(
                chapter = fresh,
                paragraphs = paragraphs,
                hasPrev = chapters.previous(fresh) != null,
                hasNext = chapters.next(fresh) != null,
            )
            novels.setLastRead(fresh.novelId, fresh.id, fresh.scrollIndex)
            prefetch.schedule(fresh.novelId, fresh.id, prefsRepo.current().prefetchCount)
        }
    }

    fun goNext() {
        val content = _state.value as? ReaderUiState.Content ?: return
        viewModelScope.launch {
            val next = chapters.next(content.chapter)
            if (next == null) {
                _message.value = "No next chapter link was found on this page."
            } else {
                chapters.setRead(content.chapter.id)
                load(next.id)
            }
        }
    }

    fun goPrevious() {
        val content = _state.value as? ReaderUiState.Content ?: return
        viewModelScope.launch {
            val prev = chapters.previous(content.chapter)
            if (prev == null) _message.value = "No previous chapter link was found." else load(prev.id)
        }
    }

    fun retry() {
        val id = savedStateHandle.get<Long>(KEY_CHAPTER_ID) ?: return
        load(id)
    }

    fun saveScroll(chapter: Chapter, index: Int, offset: Int) {
        // NonCancellable: this is also called while the screen is being disposed.
        viewModelScope.launch {
            withContext(NonCancellable) {
                chapters.updateScroll(chapter.id, index, offset)
                novels.setLastRead(chapter.novelId, chapter.id, index)
            }
        }
    }

    fun markRead(chapterId: Long) {
        viewModelScope.launch { chapters.setRead(chapterId) }
    }

    fun messageShown() {
        _message.value = null
    }

    fun setFontSize(sp: Float) = viewModelScope.launch { prefsRepo.setFontSize(sp) }

    fun setLineSpacing(v: Float) = viewModelScope.launch { prefsRepo.setLineSpacing(v) }

    private companion object {
        const val KEY_CHAPTER_ID = "chapterId"
    }
}
