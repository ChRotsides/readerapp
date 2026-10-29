package com.readerapp.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.data.db.NovelWithProgress
import com.readerapp.data.repo.NovelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(private val novels: NovelRepository) : ViewModel() {
    /** null while loading. */
    val library: StateFlow<List<NovelWithProgress>?> = novels.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(novelId: Long) {
        viewModelScope.launch { novels.delete(novelId) }
    }
}
