package com.readerapp.ui.adapters

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.SiteAdapter
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptersScreen(onBack: () -> Unit, viewModel: AdaptersViewModel = hiltViewModel()) {
    val domains by viewModel.domains.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<SiteAdapter?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Site adapters") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val list = domains
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                "No adapters yet. One is written automatically after you import a chapter from a new site " +
                    "(needs an Anthropic API key).",
                Modifier.padding(padding).padding(16.dp),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.domain }) { d ->
                    DomainCard(d, onRegenerate = { viewModel.regenerate(d.domain) },
                        onActivate = { viewModel.activate(d.domain, it) }, onView = { viewing = it })
                }
            }
        }
    }

    viewing?.let { a ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
            title = { Text("${a.domain} v${a.version} (${a.kind.name.lowercase()})") },
            text = {
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(a.code, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                }
            },
        )
    }
}

@Composable
private fun DomainCard(
    d: DomainAdapters,
    onRegenerate: () -> Unit,
    onActivate: (Int) -> Unit,
    onView: (SiteAdapter) -> Unit,
) {
    val active = d.active
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.domain, style = MaterialTheme.typography.titleMedium)
            val status = when {
                active != null -> "Active: v${active.version}"
                d.state?.genericFallback == true -> "Using generic extractor (repair gave up)"
                d.versions.isEmpty() -> "No adapter yet (generic extractor)"
                else -> "No active version (generic extractor)"
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            active?.let { Text("OK ${it.successCount} / failed ${it.failCount}", style = MaterialTheme.typography.bodySmall) }
            Text("Last success: ${d.lastSuccessAt?.let(::formatTime) ?: "never"}", style = MaterialTheme.typography.bodySmall)
            d.state?.browserActionNeeded?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            d.state?.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = onRegenerate) { Text("Regenerate now") }
            if (d.versions.isNotEmpty()) HorizontalDivider()
            d.versions.forEach { v ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("v${v.version} ${v.kind.name} - ${v.status.name}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "by ${v.writtenBy ?: "hand"}, ok ${v.successCount}, failed ${v.failCount}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onView(v) }) { Text("Code") }
                    if (v.status != AdapterStatus.ACTIVE) TextButton(onClick = { onActivate(v.version) }) { Text("Activate") }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
