package com.readerapp.ui.crawl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.search.CrawlJob

/** Starts server-side crawls of a link and shows the server's jobs (polled every few seconds while visible). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrawlScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, viewModel: CrawlViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    // Polling only while the screen is at least started (visible); stops when it is covered or the app goes to background.
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crawl a link") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            // The Scaffold's inner padding already includes the status and navigation bar insets (edge-to-edge).
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CrawlForm(s, viewModel) }
            s.startError?.let { err ->
                item { ErrorText(err, onOpenSettings) }
            }
            item { Text("Jobs", style = MaterialTheme.typography.titleMedium) }
            s.listError?.let { err -> item { ErrorText(err, onOpenSettings) } }
            val jobs = s.jobs
            when {
                jobs == null -> if (s.listError == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                jobs.isEmpty() -> item { Text("No crawl jobs yet.", style = MaterialTheme.typography.bodyMedium) }
                else -> items(jobs, key = { it.id }) { job ->
                    JobCard(job, busy = job.id in s.busy, viewModel::pause, viewModel::resume, viewModel::cancel)
                }
            }
        }
    }
}

@Composable
private fun CrawlForm(s: CrawlUiState, vm: CrawlViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = s.url,
            onValueChange = vm::setUrl,
            label = { Text("Link (novel page, listing or site)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.scope == CrawlScope.NOVEL, { vm.setScope(CrawlScope.NOVEL) }, { Text("This novel") })
            FilterChip(s.scope == CrawlScope.SITE, { vm.setScope(CrawlScope.SITE) }, { Text("Everything this link leads to") })
        }
        Text("Chapters per novel", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            FilterChip(s.allChapters, { vm.setAllChapters(true) }, { Text("All") })
            FilterChip(!s.allChapters, { vm.setAllChapters(false) }, { Text("Number") })
            if (!s.allChapters) {
                OutlinedTextField(
                    value = s.chaptersText,
                    onValueChange = vm::setChaptersText,
                    label = { Text("Chapters") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Button(onClick = vm::start, enabled = s.canStart) { Text(if (s.starting) "Starting..." else "Start") }
    }
}

@Composable
private fun ErrorText(text: String, onOpenSettings: () -> Unit) {
    Column {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        // Not configured / rejected token / unreachable are all fixed in Settings.
        TextButton(onClick = onOpenSettings) { Text("Open settings") }
    }
}

@Composable
private fun JobCard(job: CrawlJob, busy: Boolean, onPause: (Long) -> Unit, onResume: (Long) -> Unit, onCancel: (Long) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(job.url, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(job.status, style = MaterialTheme.typography.labelLarge, color = statusColor(job.status))
            }
            Text(
                (if (job.scope == "site") "Everything linked" else "This novel") + " - " +
                    (job.chaptersPerNovel?.let { "$it chapters per novel" } ?: "all chapters"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(counters(job), style = MaterialTheme.typography.bodyMedium)
            job.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (job.canPause || job.canResume || job.canCancel) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (job.canPause) OutlinedButton(onClick = { onPause(job.id) }, enabled = !busy) { Text("Pause") }
                    if (job.canResume) OutlinedButton(onClick = { onResume(job.id) }, enabled = !busy) { Text("Resume") }
                    if (job.canCancel) TextButton(onClick = { onCancel(job.id) }, enabled = !busy) { Text("Cancel") }
                }
            }
        }
    }
}

internal fun counters(job: CrawlJob): String =
    "${job.pagesFetched} pages, ${job.novelsFound} novels, ${job.chaptersEmbedded} chapters embedded, " +
        "${job.chaptersSkipped} skipped, ${"$%.4f".format(job.spendUsd)}"

@Composable
private fun statusColor(status: String) = when (status) {
    "failed", "blocked" -> MaterialTheme.colorScheme.error
    "running" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
