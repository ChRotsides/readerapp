package com.readerapp.ui.glossary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.readerapp.data.db.GlossaryEntry
import com.readerapp.data.db.Novel
import com.readerapp.data.repo.GlossaryRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.ui.nav.GlossaryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GlossaryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val glossary: GlossaryRepository,
    novels: NovelRepository,
) : ViewModel() {
    private val novelId = savedStateHandle.toRoute<GlossaryRoute>().novelId

    val novel: StateFlow<Novel?> = novels.observe(novelId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val entries: StateFlow<List<GlossaryEntry>> = glossary.observe(novelId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Add (id == 0) or edit; both mark the entry as user-edited so automatic merging leaves it alone. */
    fun save(id: Long, source: String, target: String, note: String) {
        if (source.isBlank() || target.isBlank()) return
        viewModelScope.launch {
            glossary.saveByUser(GlossaryEntry(id = id, novelId = novelId, source = source, target = target, note = note.trim().ifEmpty { null }))
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { glossary.delete(id) }
    }
}
