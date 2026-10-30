package com.readerapp.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.readerapp.ai.process.ChapterProcessor
import com.readerapp.ai.process.ChapterTextResolver
import com.readerapp.ai.process.LanguageDetector
import com.readerapp.ai.process.ProcessOutcome
import com.readerapp.ai.process.ProcessScheduler
import com.readerapp.data.db.AutoProcessMode
import com.readerapp.data.db.Chapter
import com.readerapp.data.db.TextVariant
import com.readerapp.data.settings.AiSettings
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Error(val chapter: Chapter?, val message: String, val locked: Boolean) : ReaderUiState

    /** [paragraphs] are the paragraphs of [variant], the variant the reader is showing (see [ReaderViewModel.displayedParagraphs]). */
    data class Content(
        val chapter: Chapter,
        val paragraphs: List<String>,
        val hasPrev: Boolean,
        val hasNext: Boolean,
        val variant: TextVariant = TextVariant.ORIGINAL,
        /** Variants that exist for this chapter. */
        val available: Set<TextVariant> = setOf(TextVariant.ORIGINAL),
        /** Translation makes sense for this chapter (its text is not already in the target language). */
        val canTranslate: Boolean = false,
        val autoProcess: AutoProcessMode = AutoProcessMode.OFF,
    ) : ReaderUiState
}

/** A message about AI work; [openSettings] adds a shortcut to Settings (spend cap reached / no API key). */
data class AiNotice(val text: String, val openSettings: Boolean = false)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val chapters: ChapterRepository,
    private val novels: NovelRepository,
    private val fetcher: ChapterFetcher,
    private val prefsRepo: ReaderPrefsRepository,
    private val prefetch: PrefetchScheduler,
    private val processor: ChapterProcessor,
    private val resolver: ChapterTextResolver,
    private val processScheduler: ProcessScheduler,
    private val aiSettings: AiSettings,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val prefs: StateFlow<ReaderPrefs> = prefsRepo.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderPrefs())

    private var loadJob: Job? = null
    private var textsJob: Job? = null

    /** The user's explicit variant choice; null = the novel's preferred variant. Kept while moving between chapters. */
    private val selectedVariant = MutableStateFlow<TextVariant?>(null)

    private val _processing = MutableStateFlow<Set<Pair<Long, TextVariant>>>(emptySet())

    /** The (chapter, variant) pairs being produced right now. */
    val processing: StateFlow<Set<Pair<Long, TextVariant>>> = _processing.asStateFlow()

    private val _notice = MutableStateFlow<AiNotice?>(null)
    val notice: StateFlow<AiNotice?> = _notice.asStateFlow()

    /**
     * The paragraphs the reader is showing right now (Original, Fixed or Translated, whichever is selected), updated
     * live. This is the single source for text-to-speech: read these, and you read what the user sees.
     */
    val displayedParagraphs: StateFlow<List<String>> = _state
        .map { (it as? ReaderUiState.Content)?.paragraphs.orEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                    is FetchOutcome.NeedsBrowser ->
                        return@launch run { _state.value = ReaderUiState.Error(chapter, outcome.reason, locked = false) }
                    is FetchOutcome.Failed ->
                        return@launch run { _state.value = ReaderUiState.Error(chapter, outcome.reason, locked = false) }
                }
            }
            val fresh = chapters.get(chapterId) ?: chapter
            val paragraphs = text?.paragraphs.orEmpty()
            val novel = novels.get(fresh.novelId)
            val mode = novel?.autoProcess ?: AutoProcessMode.OFF
            val target = novel?.targetLanguage?.takeIf { it.isNotBlank() } ?: aiSettings.currentTargetLanguage()
            val canTranslate = paragraphs.isNotEmpty() &&
                LanguageDetector.needsTranslation(LanguageDetector.detect(novel?.language, paragraphs), target)
            _state.value = ReaderUiState.Content(
                chapter = fresh,
                paragraphs = paragraphs,
                hasPrev = chapters.previous(fresh) != null,
                hasNext = chapters.next(fresh) != null,
                canTranslate = canTranslate,
                autoProcess = mode,
            )
            // Live view of whichever variant is shown: picks up fixes/translations that finish in the background.
            textsJob?.cancel()
            textsJob = viewModelScope.launch {
                resolver.observe(fresh.id, selectedVariant).collect { d ->
                    val c = _state.value as? ReaderUiState.Content ?: return@collect
                    if (c.chapter.id == d.chapterId && d.paragraphs.isNotEmpty()) {
                        _state.value = c.copy(paragraphs = d.paragraphs, variant = d.variant, available = d.available)
                    }
                }
            }
            novels.setLastRead(fresh.novelId, fresh.id, fresh.scrollIndex)
            prefetch.schedule(fresh.novelId, fresh.id, prefsRepo.current().prefetchCount)
            if (mode != AutoProcessMode.OFF) {
                // The current chapter right here (visible "processing" state, clear cap/key messages) ...
                val preferred = if (mode == AutoProcessMode.TRANSLATE) TextVariant.TRANSLATED else TextVariant.FIXED
                if (chapters.getText(fresh.id, preferred) == null && (preferred != TextVariant.TRANSLATED || canTranslate)) {
                    processNow(preferred, force = false, announceDone = false)
                }
                // ... and the next chapters in the background queue.
                processScheduler.schedule(fresh.novelId, fresh.id)
            }
        }
    }

    /** Switch the displayed variant. A variant that does not exist yet is produced (it is shown once ready). */
    fun selectVariant(variant: TextVariant) {
        val content = _state.value as? ReaderUiState.Content ?: return
        selectedVariant.value = variant
        if (variant != TextVariant.ORIGINAL && variant !in content.available) processNow(variant)
    }

    /** "Process now" / per-chapter re-run of [variant] for the chapter being read. */
    fun processNow(
        variant: TextVariant,
        force: Boolean = false,
        announceDone: Boolean = true,
    ) {
        val content = _state.value as? ReaderUiState.Content ?: return
        if (variant == TextVariant.ORIGINAL) return
        val key = content.chapter.id to variant
        if (key in _processing.value) return
        _processing.value = _processing.value + key
        viewModelScope.launch {
            try {
                when (val outcome = processor.process(content.chapter.id, variant, force)) {
                    is ProcessOutcome.Done -> if (outcome.partial) {
                        _message.value = "Some paragraphs could not be processed and were left as the original."
                    } else if (announceDone) {
                        _message.value = if (variant == TextVariant.FIXED) "Grammar fixed." else "Translated."
                    }
                    ProcessOutcome.AlreadyDone, ProcessOutcome.NoOriginal -> Unit
                    is ProcessOutcome.NotNeeded -> {
                        if (selectedVariant.value == variant) selectedVariant.value = null
                        _message.value = outcome.reason
                    }
                    ProcessOutcome.CapReached -> {
                        resetSelectionIfMissing(content.chapter.id, variant)
                        _notice.value = AiNotice("The monthly AI spend cap has been reached, so this chapter was not processed.", openSettings = true)
                    }
                    ProcessOutcome.NoApiKey -> {
                        resetSelectionIfMissing(content.chapter.id, variant)
                        _notice.value = AiNotice("Add your Anthropic API key in Settings to process chapters.", openSettings = true)
                    }
                    is ProcessOutcome.Retry -> {
                        resetSelectionIfMissing(content.chapter.id, variant)
                        _message.value = "Temporary AI error: ${outcome.reason}. Try again in a moment."
                    }
                    is ProcessOutcome.Failed -> {
                        resetSelectionIfMissing(content.chapter.id, variant)
                        _message.value = "Processing failed: ${outcome.reason}"
                    }
                }
            } finally {
                _processing.value = _processing.value - key
            }
        }
    }

    private suspend fun resetSelectionIfMissing(chapterId: Long, variant: TextVariant) {
        if (selectedVariant.value == variant && chapters.getText(chapterId, variant) == null) selectedVariant.value = null
    }

    fun noticeShown() {
        _notice.value = null
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
