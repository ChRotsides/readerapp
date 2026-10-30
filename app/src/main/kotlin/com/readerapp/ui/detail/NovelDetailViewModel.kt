package com.readerapp.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.readerapp.data.db.Chapter
import com.readerapp.data.db.Novel
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.offline.DownloadProgress
import com.readerapp.offline.DownloadScheduler
import com.readerapp.offline.DownloadWorker
import com.readerapp.offline.OfflineDownloads
import com.readerapp.scrape.ChapterFetcher
import com.readerapp.scrape.RecheckOutcome
import com.readerapp.ui.nav.NovelRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import com.readerapp.ai.process.ProcessScheduler
import com.readerapp.data.db.AutoProcessMode
import com.readerapp.data.settings.AiSettings
import com.readerapp.scrape.toc.TocImporter
import com.readerapp.scrape.toc.TocResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NovelDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val novels: NovelRepository,
    private val chapterRepo: ChapterRepository,
    private val toc: TocImporter,
    private val offline: OfflineDownloads,
    private val downloads: DownloadScheduler,
    private val aiSettings: AiSettings,
    private val processScheduler: ProcessScheduler,
    private val fetcher: ChapterFetcher,
) : ViewModel() {
    private val novelId = savedStateHandle.toRoute<NovelRoute>().novelId

    val novel: StateFlow<Novel?> = novels.observe(novelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<Chapter>> = chapterRepo.observeChapters(novelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Default target language from settings (shown as the hint for the per-novel override). */
    val defaultTargetLanguage: StateFlow<String> = aiSettings.targetLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettings.DEFAULT_TARGET_LANGUAGE)

    private val _rechecking = MutableStateFlow<Set<Long>>(emptySet())

    /** Flagged chapters being refetched and rechecked right now. */
    val rechecking: StateFlow<Set<Long>> = _rechecking.asStateFlow()

    /** "Refetch & recheck" for a chapter flagged as possibly incomplete; the flag clears only on a confirmed-good copy. */
    fun recheck(chapterId: Long) {
        if (chapterId in _rechecking.value) return
        _rechecking.value = _rechecking.value + chapterId
        viewModelScope.launch {
            try {
                _message.value = when (val r = fetcher.refetchAndRecheck(chapterId)) {
                    RecheckOutcome.Cleared -> "Checked again: the chapter looks complete."
                    RecheckOutcome.StillUncertain -> "Still not sure this chapter is complete."
                    is RecheckOutcome.NeedsBrowser -> r.reason
                    is RecheckOutcome.Failed -> "Could not re-check: ${r.reason}"
                }
            } finally {
                _rechecking.value = _rechecking.value - chapterId
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    /** Re-reads the novel's table of contents with the domain's adapter (follows pagination). */
    fun refreshChapterList() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                offline.joined(novelId)
                val result = toc.import(novelId)
                offline.afterToc(novelId, result)
                _message.value = when (val r = result) {
                    is TocResult.Imported -> "Chapter list updated: ${r.total} chapters (${r.added} new)."
                    TocResult.NoAdapter -> "No site adapter for this domain yet. It is created in the background after the first import."
                    TocResult.NoTocSpec -> "This site's adapter has no table-of-contents selectors."
                    TocResult.NoNovelPage -> "The novel's main page is unknown. Re-add it from its page in the browser."
                    is TocResult.Incomplete -> "The chapter list could not be read completely (${r.reason}); nothing was changed. Try again later."
                    is TocResult.FetchFailed -> "Could not read the chapter list: ${r.reason}"
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun saveAiSettings(mode: AutoProcessMode, targetLanguage: String, sonnet: Boolean, sourceLanguage: String) {
        viewModelScope.launch {
            novels.setAiSettings(novelId, mode, targetLanguage, sonnet, sourceLanguage)
            offline.joined(novelId)
            // Start the queue right away from the reading position.
            if (mode != AutoProcessMode.OFF) {
                val n = novels.get(novelId)
                val anchor = n?.lastReadChapterId ?: chapters.value.firstOrNull()?.id
                if (anchor != null) processScheduler.schedule(novelId, anchor)
            }
        }
    }

    /** The chapter "continue reading" should open: last read, else first unread, else first. */
    fun continueTarget(onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            val lastRead = novels.get(novelId)?.lastReadChapterId
            val list = chapters.value
            onResult(lastRead ?: list.firstOrNull { !it.read }?.id ?: list.firstOrNull()?.id)
        }
    }

    /** Progress of this novel's background download; null when none is queued or running. */
    val download: StateFlow<DownloadProgress?> = downloads.observe(novelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Queues the download of the next [count] chapters after the reading position ([DownloadWorker.ALL] = all remaining). */
    fun downloadForOffline(count: Int) {
        viewModelScope.launch {
            // Using a hidden "from search" novel makes it a library novel, so the 14-day cleanup never takes it.
            offline.joined(novelId)
            downloads.enqueue(novelId, count)
        }
    }

    /** "Add to library" for a hidden novel opened from search results. */
    fun addToLibrary() {
        viewModelScope.launch { offline.joined(novelId) }
    }

    fun cancelDownload() = downloads.cancel(novelId)
}
