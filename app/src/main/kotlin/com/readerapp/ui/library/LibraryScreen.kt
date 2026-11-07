package com.readerapp.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.readerapp.data.db.NovelWithProgress
import com.readerapp.favorites.FavoriteRef
import com.readerapp.favorites.isFavorite
import com.readerapp.ui.favorites.FavoritesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenNovel: (Long) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiscover: () -> Unit,
    onFindSimilar: (Long) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
    favoritesViewModel: FavoritesViewModel = hiltViewModel(),
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val favorites by favoritesViewModel.favorites.collectAsStateWithLifecycle()
    var onlyFavorites by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NovelWithProgress?>(null) }

    Scaffold(
        bottomBar = { com.readerapp.tts.ui.TtsMiniPlayer() },
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = { onlyFavorites = !onlyFavorites }) {
                        Icon(
                            if (onlyFavorites) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            if (onlyFavorites) "Show all novels" else "Show only favorites",
                        )
                    }
                    IconButton(onClick = onOpenDiscover) { Icon(Icons.Filled.Search, "Discover novels") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenBrowser) { Icon(Icons.Filled.Add, "Add novel from browser") }
        },
    ) { padding ->
        val items = library?.let { all ->
            if (onlyFavorites) all.filter { favorites.isFavorite(FavoriteRef.forNovel(it.novel)) } else all
        }
        when {
            items == null -> Box(Modifier.fillMaxSize().padding(padding))
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (onlyFavorites) "None of the novels in your library is a favorite yet.\nTap the star on a novel's page, or long-press it here."
                    else "Your library is empty.\nTap + to open the browser, navigate to a chapter and choose \"Add to library\".",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(130.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.novel.id }) { item ->
                    val ref = FavoriteRef.forNovel(item.novel)
                    NovelCard(
                        item, isFavorite = favorites.isFavorite(ref), onClick = { onOpenNovel(item.novel.id) },
                        onToggleFavorite = { favoritesViewModel.toggle(ref) }, onFindSimilar = { onFindSimilar(item.novel.id) },
                        onDelete = { pendingDelete = item },
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete novel?") },
            text = { Text("\"${item.novel.title}\" and all its stored chapters will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(item.novel.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelCard(item: NovelWithProgress, isFavorite: Boolean, onClick: () -> Unit, onToggleFavorite: () -> Unit, onFindSimilar: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.medium)) {
                if (item.novel.coverUrl != null) {
                    AsyncImage(
                        model = item.novel.coverUrl,
                        contentDescription = item.novel.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            item.novel.title.take(2).uppercase(),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isFavorite) {
                    Icon(
                        Icons.Filled.Star, "Favorite", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Remove from favorites" else "Add to favorites") },
                        leadingIcon = { Icon(if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null) },
                        onClick = { menu = false; onToggleFavorite() },
                    )
                    DropdownMenuItem(
                        text = { Text("Find similar") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        onClick = { menu = false; onFindSimilar() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(item.novel.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (item.chapterCount == 0) 0f else item.readCount / item.chapterCount.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${item.readCount} read / ${item.chapterCount} known",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
