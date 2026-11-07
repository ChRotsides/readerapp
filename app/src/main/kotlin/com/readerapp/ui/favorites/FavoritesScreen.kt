package com.readerapp.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.data.db.Favorite
import com.readerapp.data.db.Novel
import com.readerapp.scrape.toc.UrlKey
import com.readerapp.ui.discover.Cover

/** Every starred novel, including ones that are not in the library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOpenNovel: (Long) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favorites") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No favorites yet.\nTap the star on a novel to add it.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(favorites, key = { it.key }) { f ->
                    val local = library.firstOrNull { f.matchesNovel(it) }
                    FavoriteCard(
                        f, inLibrary = local != null, onRemove = { viewModel.remove(f) },
                        onClick = {
                            when {
                                local != null -> onOpenNovel(local.id)
                                f.workId != null -> onOpenWork(f.workId)
                                f.novelUrl != null -> onOpenInBrowser(f.novelUrl)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun Favorite.matchesNovel(n: Novel): Boolean {
    val mine = novelUrl?.let(UrlKey::of) ?: return false
    return listOfNotNull(n.sourceUrl, n.novelUrl).any { UrlKey.of(it) == mine }
}

@Composable
private fun FavoriteCard(f: Favorite, inLibrary: Boolean, onRemove: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(f.coverUrl, f.title, Modifier.width(60.dp).height(90.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(f.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                f.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                f.domain?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                if (inLibrary) AssistChip(onClick = onClick, label = { Text("In library") })
            }
            FavoriteStar(isFavorite = true, onToggle = onRemove)
        }
    }
}
