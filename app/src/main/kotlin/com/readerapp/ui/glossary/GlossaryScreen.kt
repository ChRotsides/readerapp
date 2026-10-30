package com.readerapp.ui.glossary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.data.db.GlossaryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(onBack: () -> Unit, viewModel: GlossaryViewModel = hiltViewModel()) {
    val novel by viewModel.novel.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    // null = closed; an entry with id 0 = "add new"
    var editing by remember { mutableStateOf<GlossaryEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Glossary" + (novel?.title?.let { " - $it" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = GlossaryEntry(novelId = novel?.id ?: 0, source = "", target = "") }) {
                Icon(Icons.Filled.Add, "Add term")
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("No terms yet.")
                Text(
                    "Names and terms found while translating are added here automatically. Terms you add or edit are never overwritten.",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(entries, key = { it.id }) { e ->
                    ListItem(
                        modifier = Modifier.clickable { editing = e },
                        headlineContent = { Text("${e.source}  →  ${e.target}") },
                        supportingContent = {
                            val parts = listOfNotNull(e.note?.takeIf { it.isNotBlank() }, if (e.userEdited) "edited by you" else null)
                            if (parts.isNotEmpty()) Text(parts.joinToString(" - "))
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.delete(e.id) }) { Icon(Icons.Filled.Delete, "Delete ${e.source}") }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { entry ->
        EntryDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { s, t, n -> viewModel.save(entry.id, s, t, n); editing = null },
        )
    }
}

@Composable
private fun EntryDialog(entry: GlossaryEntry, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var source by remember(entry.id) { mutableStateOf(entry.source) }
    var target by remember(entry.id) { mutableStateOf(entry.target) }
    var note by remember(entry.id) { mutableStateOf(entry.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.id == 0L) "Add term" else "Edit term") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(source, { source = it }, label = { Text("Source (as in the original text)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(target, { target = it }, label = { Text("Translation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = source.isNotBlank() && target.isNotBlank(), onClick = { onSave(source, target, note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
