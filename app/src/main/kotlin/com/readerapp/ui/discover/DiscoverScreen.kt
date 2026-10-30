package com.readerapp.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.readerapp.search.Work

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    onOpenWork: (String) -> Unit,
    onOpenNovel: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.mode == DiscoverMode.DESCRIBE, { viewModel.setMode(DiscoverMode.DESCRIBE) }, { Text("Describe it") })
                    FilterChip(s.mode == DiscoverMode.TITLE, { viewModel.setMode(DiscoverMode.TITLE) }, { Text("Title search") })
                }
            }
            item {
                if (s.mode == DiscoverMode.DESCRIBE) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = s.description,
                            onValueChange = viewModel::setDescription,
                            label = { Text("Describe the novel you want") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = viewModel::search, enabled = !s.loading && s.description.isNotBlank()) { Text("Search") }
                    }
                } else {
                    OutlinedTextField(
                        value = s.titleQuery,
                        onValueChange = viewModel::setTitleQuery,
                        label = { Text("Title or alias") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.searchTitle() }),
                        trailingIcon = { TextButton(onClick = viewModel::searchTitle, enabled = !s.loading && s.titleQuery.isNotBlank()) { Text("Go") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (s.loading) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        if (s.mode == DiscoverMode.DESCRIBE) "Writing imagined scenes, searching, ranking... (can take up to 40 s)" else "Searching...",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            s.error?.let { err ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onOpenSettings) { Text("Open settings") }
                    }
                }
            }
            s.query?.let { q ->
                item { SearchedFor(q, enabled = !s.loading, onEdit = viewModel::editQuery, onSearchAgain = viewModel::searchAgain) }
            }
            if (s.notes.isNotEmpty()) item {
                Text(s.notes.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            }
            val results = s.results
            if (results != null && !s.loading) {
                if (results.isEmpty()) item { Text("No matches.") }
                items(results, key = { it.workId }) { work ->
                    ResultCard(work, inLibraryId = s.inLibrary[work.workId], onClick = { onOpenWork(work.workId) }, onOpenNovel = onOpenNovel)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchedFor(q: EditableQuery, enabled: Boolean, onEdit: (EditableQuery.() -> EditableQuery) -> Unit, onSearchAgain: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("What I searched for", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (open) "Hide" else "Edit", style = MaterialTheme.typography.labelLarge)
            }
            if (open) {
                Text("Imagined scenes", style = MaterialTheme.typography.labelLarge)
                q.scenes.forEachIndexed { i, scene ->
                    Row(verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            value = scene,
                            onValueChange = { v -> onEdit { copy(scenes = scenes.toMutableList().also { it[i] = v }) } },
                            minLines = 3,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onEdit { copy(scenes = scenes.filterIndexed { j, _ -> j != i }) } }) { Text("Remove") }
                    }
                }
                TextButton(onClick = { onEdit { copy(scenes = scenes + "") } }) { Text("Add scene") }
                OutlinedTextField(q.synopsis, { v -> onEdit { copy(synopsis = v) } }, label = { Text("Imagined synopsis") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(q.tags, { v -> onEdit { copy(tags = v) } }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
                Text("Filters", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(q.language, { v -> onEdit { copy(language = v) } }, label = { Text("Language") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(q.status, { v -> onEdit { copy(status = v) } }, label = { Text("Status") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(q.minChapters, { v -> onEdit { copy(minChapters = v.filter(Char::isDigit)) } }, label = { Text("Min chapters") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(q.maxChapters, { v -> onEdit { copy(maxChapters = v.filter(Char::isDigit)) } }, label = { Text("Max chapters") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(q.includeTags, { v -> onEdit { copy(includeTags = v) } }, label = { Text("Must have tags") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(q.excludeTags, { v -> onEdit { copy(excludeTags = v) } }, label = { Text("Must not have tags") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onSearchAgain, enabled = enabled) { Text("Search again") }
            } else {
                Text(q.synopsis, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(work: Work, inLibraryId: Long?, onClick: () -> Unit, onOpenNovel: (Long) -> Unit) {
    var aliasesOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(work.coverUrl, work.title, Modifier.width(80.dp).height(120.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(work.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (inLibraryId != null) {
                    AssistChip(onClick = { onOpenNovel(inLibraryId) }, label = { Text("In library") })
                }
                if (work.aliases.isNotEmpty()) {
                    Text(
                        if (aliasesOpen) "Also: " + work.aliases.joinToString(" / ") else "${work.aliases.size} alias(es)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { aliasesOpen = !aliasesOpen },
                    )
                }
                if (work.ratings.isNotEmpty()) {
                    Text(
                        work.ratings.joinToString("  ") { "${it.source} ${"%.1f".format(it.normalized)}/10" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (work.reason.isNotBlank()) Text(work.reason, style = MaterialTheme.typography.bodyMedium)
                if (work.matchedScenes > 0) {
                    Text("Matched ${work.matchedScenes} imagined scene(s)", style = MaterialTheme.typography.labelSmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    work.tags.take(8).forEach { SuggestionChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
                }
            }
        }
    }
}

@Composable
internal fun Cover(url: String?, title: String, modifier: Modifier) {
    Box(modifier.clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(title.take(2).uppercase(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
