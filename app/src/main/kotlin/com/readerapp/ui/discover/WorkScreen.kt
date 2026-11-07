package com.readerapp.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.favorites.FavoriteRef
import com.readerapp.favorites.isFavorite
import com.readerapp.search.Work
import com.readerapp.ui.favorites.FavoriteStar
import com.readerapp.ui.favorites.FavoritesViewModel

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WorkScreen(
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onOpenNovel: (Long) -> Unit,
    onMoreLikeThis: (String) -> Unit,
    viewModel: WorkViewModel = hiltViewModel(),
    favoritesViewModel: FavoritesViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val favorites by favoritesViewModel.favorites.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The snackbar is shown from a screen-scoped coroutine (not the effect below): clearing the event changes the
    // effect's key and cancels it, so nothing suspending may run in the effect after eventHandled(). The event is
    // consumed only after the navigation callback ran.
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.event) {
        when (val e = s.event) {
            is WorkEvent.OpenNovel -> { onOpenNovel(e.novelId); viewModel.eventHandled() }
            is WorkEvent.OpenBrowser -> {
                scope.launch { snackbar.showSnackbar(e.message) }
                onOpenInBrowser(e.url)
                viewModel.eventHandled()
            }
            is WorkEvent.Message -> { scope.launch { snackbar.showSnackbar(e.text) }; viewModel.eventHandled() }
            null -> Unit
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(s.work?.title ?: "Novel", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    s.work?.let { w ->
                        val ref = FavoriteRef.forWork(w)
                        FavoriteStar(favorites.isFavorite(ref), { favoritesViewModel.toggle(ref) })
                    }
                },
            )
        },
    ) { padding ->
        val work = s.work
        when {
            work != null -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Cover(work.coverUrl, work.title, Modifier.width(110.dp).height(165.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(work.title, style = MaterialTheme.typography.titleLarge)
                        work.nativeTitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (work.authors.isNotEmpty()) Text(work.authors.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                        Text(facts(work), style = MaterialTheme.typography.bodySmall)
                        s.libraryNovelId?.let { id -> AssistChip(onClick = { onOpenNovel(id) }, label = { Text("In library") }) }
                        if (s.libraryNovelId == null) {
                            s.savedNovelId?.let { id -> AssistChip(onClick = { onOpenNovel(id) }, label = { Text("Opening chapters saved - read offline") }) }
                        }
                    }
                }
                OutlinedButton(onClick = { onMoreLikeThis(work.workId) }) { Text("More like this") }
                if (work.aliases.isNotEmpty()) Text("Also known as: " + work.aliases.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                work.synopsis?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (work.tags.isNotEmpty() || work.genres.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (work.genres + work.tags).distinct().forEach { SuggestionChip(onClick = {}, label = { Text(it) }) }
                    }
                }
                if (work.ratings.isNotEmpty()) {
                    Text("Ratings", style = MaterialTheme.typography.titleSmall)
                    work.ratings.forEach { r ->
                        Text("${r.source}: ${trim(r.rating)}/${trim(r.scale)}" + (r.votes?.let { " ($it votes)" } ?: "") +
                            "  [${"%.1f".format(r.normalized)}/10]", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (work.reason.isNotBlank()) Text(work.reason, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("Sources", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Add to library imports from the novel page. If this site has no chapter-list recipe yet, the browser opens so you can go to chapter 1 and add it there.",
                    style = MaterialTheme.typography.bodySmall,
                )
                work.urls.groupBy { DomainRateLimiter.domainOf(it.url) }.forEach { (domain, urls) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(domain, style = MaterialTheme.typography.titleSmall)
                            urls.forEach { u ->
                                Text(u.url, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onOpenInBrowser(u.url) }) { Text("Open in browser") }
                                    Button(onClick = { viewModel.addToLibrary(u.url) }, enabled = s.importingUrl == null) {
                                        Text(if (s.importingUrl == u.url) "Adding..." else "Add to library")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            s.loading -> Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.padding(32.dp))
            }
            else -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(s.error ?: "Failed to load", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text("Retry") }
            }
        }
    }
}

private fun trim(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)

private fun facts(w: Work): String = listOfNotNull(
    w.status, w.language?.uppercase(), w.chapterCount?.let { "$it ch." }, w.startYear?.toString(),
).joinToString(" · ")
