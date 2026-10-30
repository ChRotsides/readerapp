package com.readerapp.tts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.tts.SleepSpec
import com.readerapp.tts.TtsCommand
import com.readerapp.tts.TtsConfig
import com.readerapp.tts.TtsState
import com.readerapp.tts.model.ModelState
import java.util.Locale

private val SPEEDS = listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f)
private val SLEEP_MINUTES = listOf(15, 30, 60)

private fun mb(bytes: Long) = "${bytes / 1_000_000} MB"

/** "Download the speech model?" confirmation, shown when reading aloud is requested before the model exists. */
@Composable
fun TtsDialogs(viewModel: TtsViewModel = hiltViewModel()) {
    val confirm by viewModel.confirmDownload.collectAsStateWithLifecycle()
    if (!confirm) return
    AlertDialog(
        onDismissRequest = viewModel::dismissDownload,
        title = { Text("Download speech model?") },
        text = {
            Text(
                "Reading aloud runs fully on this phone using the Kokoro voice model. It is a one-time download of " +
                    "${mb(TtsConfig.MODEL_ARCHIVE_BYTES)} (about ${mb(TtsConfig.MODEL_ARCHIVE_BYTES + TtsConfig.EXTRACTED_BYTES_ESTIMATE)} " +
                    "of free storage is needed while unpacking). Wi-Fi is recommended. Progress shows in a notification and can be resumed.",
            )
        },
        confirmButton = { TextButton(onClick = viewModel::confirmDownload) { Text("Download") } },
        dismissButton = { TextButton(onClick = viewModel::dismissDownload) { Text("Cancel") } },
    )
}

/**
 * Bar shown while the speech model downloads / failed, or while something is being read aloud: title, transport
 * controls, speed, sleep timer and voice. Renders nothing when idle.
 */
@Composable
fun TtsMiniPlayer(modifier: Modifier = Modifier, viewModel: TtsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val model by viewModel.modelState.collectAsStateWithLifecycle()
    Column(modifier) {
        when (val m = model) {
            is ModelState.Downloading -> ModelProgress(
                "Downloading speech model - ${mb(m.downloaded)} of ${mb(m.total)}",
                if (m.total > 0) m.downloaded.toFloat() / m.total else 0f,
                onCancel = viewModel::cancelDownload,
            )
            ModelState.Verifying -> ModelProgress("Verifying speech model...", null, null)
            ModelState.Extracting -> ModelProgress("Unpacking speech model...", null, null)
            is ModelState.Failed -> if (state.active.not()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.reason, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = viewModel::startDownload) { Text("Retry") }
                }
            }
            else -> Unit
        }
        if (state.active) PlayerBar(state, viewModel)
    }
}

@Composable
private fun ModelProgress(text: String, progress: Float?, onCancel: (() -> Unit)?) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PlayerBar(state: TtsState, vm: TtsViewModel) {
    Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        state.chapterTitle.ifBlank { "Reading aloud" },
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (state.buffering) "Generating audio..." else state.novelTitle,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { vm.send(TtsCommand.PrevParagraph) }) { Icon(Icons.Filled.SkipPrevious, "Previous paragraph") }
                IconButton(onClick = { vm.send(TtsCommand.Toggle) }) {
                    Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.playing) "Pause" else "Play")
                }
                IconButton(onClick = { vm.send(TtsCommand.NextParagraph) }) { Icon(Icons.Filled.SkipNext, "Next paragraph") }
                IconButton(onClick = { vm.send(TtsCommand.NextChapter) }) { Icon(Icons.Filled.FastForward, "Next chapter") }
                IconButton(onClick = { vm.send(TtsCommand.Stop) }) { Icon(Icons.Filled.Close, "Stop reading") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeedButton(state.speed, vm::setSpeed)
                SleepButton(state, vm)
                VoiceButton(state.voice, vm::setVoice)
            }
        }
    }
}

@Composable
private fun SpeedButton(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "x") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SPEEDS.forEach { s ->
            DropdownMenuItem(text = { Text("${s}x") }, onClick = { open = false; onSpeed(s) })
        }
    }
}

@Composable
private fun SleepButton(state: TtsState, vm: TtsViewModel) {
    var open by remember { mutableStateOf(false) }
    val armed = state.sleepEndsAtMs != null || state.sleepEndOfChapter
    TextButton(onClick = { open = true }) {
        val label = when {
            state.sleepEndOfChapter -> "Sleep: end of chapter"
            state.sleepEndsAtMs != null -> "Sleep: ${((state.sleepEndsAtMs - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)} min"
            else -> "Sleep"
        }
        Text(label)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        SLEEP_MINUTES.forEach { m ->
            DropdownMenuItem(text = { Text("$m minutes") }, onClick = { open = false; vm.setSleep(SleepSpec.Minutes(m)) })
        }
        DropdownMenuItem(text = { Text("End of chapter") }, onClick = { open = false; vm.setSleep(SleepSpec.EndOfChapter) })
        if (armed) DropdownMenuItem(text = { Text("Cancel timer") }, onClick = { open = false; vm.setSleep(null) })
    }
}

@Composable
private fun VoiceButton(voice: Int, onVoice: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Text("Voice: " + TtsConfig.SPEAKERS.getOrElse(voice) { "?" }.substringBefore(" ("), maxLines = 1)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        TtsConfig.SPEAKERS.forEachIndexed { i, name ->
            DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onVoice(i) })
        }
    }
}

/** Settings screen section: model status, voice, speed, CPU threads, cache size. */
@Composable
fun TtsSettingsSection(modifier: Modifier = Modifier, viewModel: TtsViewModel = hiltViewModel()) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val model by viewModel.modelState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Text to speech", style = MaterialTheme.typography.titleMedium)
        Text(
            "Kokoro runs on this device (English only). Reads the variant shown in the reader.",
            style = MaterialTheme.typography.bodySmall,
        )
        when (val m = model) {
            is ModelState.Installed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Speech model installed", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete model") }
            }
            is ModelState.Downloading -> Text("Downloading: ${mb(m.downloaded)} of ${mb(m.total)}", style = MaterialTheme.typography.bodyMedium)
            ModelState.Verifying -> Text("Verifying...", style = MaterialTheme.typography.bodyMedium)
            ModelState.Extracting -> Text("Unpacking...", style = MaterialTheme.typography.bodyMedium)
            is ModelState.Failed -> {
                Text(m.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = viewModel::startDownload) { Text("Retry download") }
            }
            ModelState.NotInstalled -> OutlinedButton(onClick = viewModel::startDownload) {
                Text("Download speech model (${mb(TtsConfig.MODEL_ARCHIVE_BYTES)})")
            }
        }
        Text("Voice", style = MaterialTheme.typography.labelLarge)
        VoicePicker(prefs.voice, viewModel::setVoice)
        Text("Speed: ${String.format(Locale.US, "%.2f", prefs.speed)}x", style = MaterialTheme.typography.labelLarge)
        Slider(value = prefs.speed, onValueChange = viewModel::setSpeed, valueRange = TtsConfig.SPEED_RANGE)
        Text("Synthesis threads: ${prefs.numThreads}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = prefs.numThreads.toFloat(),
            onValueChange = { viewModel.setNumThreads(it.toInt()) },
            valueRange = TtsConfig.THREADS_RANGE.first.toFloat()..TtsConfig.THREADS_RANGE.last.toFloat(),
            steps = TtsConfig.THREADS_RANGE.last - TtsConfig.THREADS_RANGE.first - 1,
        )
        Text("Audio cache limit: ${prefs.cacheLimitMb} MB", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = prefs.cacheLimitMb.toFloat(),
            onValueChange = { viewModel.setCacheLimitMb((it / 128).toInt() * 128) },
            valueRange = TtsConfig.CACHE_MB_RANGE.first.toFloat()..TtsConfig.CACHE_MB_RANGE.last.toFloat(),
        )
        HorizontalDivider()
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete speech model?") },
            text = { Text("Frees about ${mb(TtsConfig.EXTRACTED_BYTES_ESTIMATE)}. You can download it again later.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteModel() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VoicePicker(voice: Int, onVoice: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text(TtsConfig.SPEAKERS.getOrElse(voice) { "?" }) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        TtsConfig.SPEAKERS.forEachIndexed { i, name -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onVoice(i) }) }
    }
}
