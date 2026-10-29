package com.readerapp.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.readerapp.data.db.Chapter
import com.readerapp.data.db.Novel
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.settings.ReaderPrefsRepository
import com.readerapp.scrape.PrefetchScheduler
import com.readerapp.ui.nav.NovelRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NovelDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val novels: NovelRepository,
    private val chapterRepo: ChapterRepository,
    private val prefs: ReaderPrefsRepository,
    private val prefetch: PrefetchScheduler,
) : ViewModel() {
    private val novelId = savedStateHandle.toRoute<NovelRoute>().novelId

    val novel: StateFlow<Novel?> = novels.observe(novelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<Chapter>> = chapterRepo.observeChapters(novelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The chapter "continue reading" should open: last read, else first unread, else first. */
    fun continueTarget(onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            val lastRead = novels.get(novelId)?.lastReadChapterId
            val list = chapters.value
            onResult(lastRead ?: list.firstOrNull { !it.read }?.id ?: list.firstOrNull()?.id)
        }
    }

    /** Queue a background download of the chapters after the last fetched one. */
    fun downloadAhead() {
        viewModelScope.launch {
            val list = chapters.value
            val anchor = list.lastOrNull { it.status == com.readerapp.data.db.ChapterStatus.FETCHED } ?: return@launch
            prefetch.schedule(novelId, anchor.id, prefs.current().prefetchCount.coerceAtLeast(5))
        }
    }
}
