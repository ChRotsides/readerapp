package com.readerapp.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readerapp.data.db.Favorite
import com.readerapp.data.db.Novel
import com.readerapp.favorites.FavoriteRef
import com.readerapp.favorites.FavoriteRepository
import com.readerapp.search.LibrarySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Starred novels for every screen that shows or toggles a star, and the Favorites list. */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repo: FavoriteRepository,
    library: LibrarySource,
) : ViewModel() {
    val favorites: StateFlow<List<Favorite>> = repo.live.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The library, so a favorite that is also in it opens the local novel (works offline). */
    val library: StateFlow<List<Novel>> = library.novels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(ref: FavoriteRef) {
        viewModelScope.launch { repo.toggle(ref) }
    }

    /** Un-stars a row of the Favorites list. */
    fun remove(favorite: Favorite) = toggle(
        FavoriteRef(
            key = favorite.key, workId = favorite.workId, novelUrl = favorite.novelUrl, title = favorite.title, author = favorite.author,
            coverUrl = favorite.coverUrl, domain = favorite.domain, urlKeys = emptySet(),
        ),
    )
}
