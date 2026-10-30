package com.readerapp.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val SUGGESTIONS = listOf("Who is this?", "What does this mean?", "Remind me what happened before")

/** Bottom sheet for highlight Q&A: quoted selection, suggestions, streaming answer, follow-ups in the same thread. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QaSheet(state: QaUiState, onAsk: (String) -> Unit, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    var question by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(state.messages.lastOrNull()?.text?.length, state.messages.size) { scroll.animateScrollTo(scroll.maxValue) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "“${state.selected}”",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.messages.isEmpty()) {
                    Text("Ask about this passage. Answers use only what you have read so far.", style = MaterialTheme.typography.bodySmall)
                }
                state.messages.forEach { m ->
                    if (m.fromUser) {
                        Text(m.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    } else if (m.text.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                            Text("Reading back through the story...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.error?.let { err ->
                    Text(err.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (err.openSettings) TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                }
            }
            if (state.messages.isEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { s -> AssistChip(onClick = { onAsk(s) }, label = { Text(s) }, enabled = !state.busy) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.messages.isEmpty()) "Ask a question" else "Ask a follow-up") },
                    maxLines = 3,
                )
                IconButton(
                    enabled = question.isNotBlank() && !state.busy,
                    onClick = { onAsk(question); question = "" },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}
