package com.readerapp.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.data.db.Chapter
import com.readerapp.data.settings.ReaderPrefs
import com.readerapp.ui.settings.ReaderPrefsControls
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showPrefs by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }

    val current: Chapter? = when (val s = state) {
        is ReaderUiState.Content -> s.chapter
        is ReaderUiState.Error -> s.chapter
        ReaderUiState.Loading -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.title?.ifBlank { null } ?: "Reader", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    current?.let { c ->
                        IconButton(onClick = { onOpenInBrowser(c.url) }) { Icon(Icons.Filled.Public, "Open in browser") }
                    }
                    IconButton(onClick = { showPrefs = true }) { Icon(Icons.Filled.TextFormat, "Text settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                ReaderUiState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Loading chapter...", Modifier.padding(top = 16.dp))
                }
                is ReaderUiState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (s.locked) "This chapter is locked" else "Couldn't load this chapter",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    if (s.locked) {
                        Text(
                            "Log in or unlock it in the in-app browser, then retry.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::retry) { Text("Retry") }
                        s.chapter?.let { c -> OutlinedButton(onClick = { onOpenInBrowser(c.url) }) { Text("Open in browser") } }
                    }
                }
                is ReaderUiState.Content -> ChapterContent(
                    content = s,
                    prefs = prefs,
                    onPrevious = viewModel::goPrevious,
                    onNext = viewModel::goNext,
                    onScroll = { index, offset -> viewModel.saveScroll(s.chapter, index, offset) },
                    onFinished = { viewModel.markRead(s.chapter.id) },
                )
            }
        }
    }

    if (showPrefs) {
        ModalBottomSheet(onDismissRequest = { showPrefs = false }) {
            ReaderPrefsControls(
                prefs = prefs,
                onFontSize = { viewModel.setFontSize(it) },
                onLineSpacing = { viewModel.setLineSpacing(it) },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 24.dp),
            )
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ChapterContent(
    content: ReaderUiState.Content,
    prefs: ReaderPrefs,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onScroll: (index: Int, offset: Int) -> Unit,
    onFinished: () -> Unit,
) {
    // A fresh list state per chapter restores that chapter's saved position.
    androidx.compose.runtime.key(content.chapter.id) {
        val listState = rememberLazyListState(content.chapter.scrollIndex, content.chapter.scrollOffset)
        val latestOnScroll by rememberUpdatedState(onScroll)
        val latestOnFinished by rememberUpdatedState(onFinished)
        val footerIndex = content.paragraphs.size + 1

        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .debounce(700)
                .distinctUntilChanged()
                .collect { (i, o) -> latestOnScroll(i, o) }
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index >= footerIndex - 1 } }
                .distinctUntilChanged()
                .collect { atEnd -> if (atEnd) latestOnFinished() }
        }
        DisposableEffect(listState) {
            onDispose { latestOnScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
        }

        val fontSize = prefs.fontSizeSp.sp
        val lineHeight = (prefs.fontSizeSp * prefs.lineSpacing).sp
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            ) {
                item(key = "title") {
                    Text(
                        content.chapter.title.ifBlank { "Untitled chapter" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                }
                itemsIndexed(content.paragraphs, key = { i, _ -> "p$i" }) { _, paragraph ->
                    Text(
                        paragraph,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        modifier = Modifier.padding(bottom = (prefs.fontSizeSp * 0.6f).dp),
                    )
                }
                item(key = "nav") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = onPrevious, enabled = content.hasPrev, modifier = Modifier.weight(1f)) {
                            Text("Previous")
                        }
                        Button(onClick = onNext, modifier = Modifier.weight(1f)) {
                            Text(if (content.hasNext) "Next chapter" else "Check for next")
                        }
                    }
                }
            }
        }
    }
}
