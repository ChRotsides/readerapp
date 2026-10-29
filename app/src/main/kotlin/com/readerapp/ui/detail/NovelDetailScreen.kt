package com.readerapp.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.readerapp.data.db.Chapter
import com.readerapp.data.db.ChapterStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    viewModel: NovelDetailViewModel = hiltViewModel(),
) {
    val novel by viewModel.novel.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(novel?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    novel?.let { n ->
                        IconButton(onClick = { onOpenInBrowser(n.sourceUrl) }) { Icon(Icons.Filled.Public, "Open source in browser") }
                    }
                },
            )
        },
    ) { padding ->
        val n = novel ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.width(100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
                        if (n.coverUrl != null) {
                            AsyncImage(n.coverUrl, n.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(n.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        n.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text(n.domain, style = MaterialTheme.typography.labelMedium)
                        Text("${chapters.count { it.status == ChapterStatus.FETCHED }} of ${chapters.size} known chapters downloaded",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                n.synopsis?.takeIf { it.isNotBlank() }?.let {
                    Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = chapters.isNotEmpty(),
                        onClick = { viewModel.continueTarget { id -> if (id != null) onOpenChapter(id) } },
                    ) { Text(if (n.lastReadChapterId != null) "Continue reading" else "Start reading") }
                    OutlinedButton(onClick = viewModel::downloadAhead, enabled = chapters.isNotEmpty()) {
                        Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download ahead")
                    }
                }
                HorizontalDivider()
            }
            itemsIndexed(chapters, key = { _, c -> c.id }) { position, chapter ->
                ChapterRow(position + 1, chapter, isCurrent = chapter.id == n.lastReadChapterId, onClick = { onOpenChapter(chapter.id) })
            }
        }
    }
}

@Composable
private fun ChapterRow(position: Int, chapter: Chapter, isCurrent: Boolean, onClick: () -> Unit) {
    val (icon, label) = statusVisual(chapter.status)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                chapter.title.ifBlank { "Chapter $position" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
        },
        supportingContent = { Text(label + if (chapter.read) " - read" else "") },
        leadingContent = { Text("$position", style = MaterialTheme.typography.labelLarge) },
        trailingContent = { Icon(icon, label) },
    )
}

private fun statusVisual(status: ChapterStatus): Pair<ImageVector, String> = when (status) {
    ChapterStatus.FETCHED -> Icons.Filled.CheckCircle to "Downloaded"
    ChapterStatus.NOT_FETCHED -> Icons.Outlined.CloudQueue to "Not downloaded"
    ChapterStatus.LOCKED -> Icons.Filled.Lock to "Locked (login/payment required)"
    ChapterStatus.FAILED -> Icons.Filled.ErrorOutline to "Failed - tap to retry"
}
