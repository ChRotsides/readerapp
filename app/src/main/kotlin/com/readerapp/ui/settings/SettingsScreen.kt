package com.readerapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readerapp.data.settings.LibraryOptions
import com.readerapp.data.settings.ReaderPrefs
import com.readerapp.data.settings.SecretKeyName
import com.readerapp.data.settings.ThemeMode
import com.readerapp.data.settings.maskSecret

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenAdapters: () -> Unit, onOpenCrawl: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val secrets by viewModel.secrets.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val cap by viewModel.monthlyCapUsd.collectAsStateWithLifecycle()
    val spent by viewModel.monthToDateUsd.collectAsStateWithLifecycle()
    val targetLanguage by viewModel.targetLanguage.collectAsStateWithLifecycle()
    val processAhead by viewModel.processAhead.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("API keys", style = MaterialTheme.typography.titleMedium)
            Text(
                "Stored encrypted with a key held in the Android Keystore. Used by AI features in later versions.",
                style = MaterialTheme.typography.bodySmall,
            )
            SecretField("Anthropic API key", secrets.anthropicApiKey, masked = true,
                onSave = { viewModel.saveSecret(SecretKeyName.ANTHROPIC_API_KEY, it) },
                onClear = { viewModel.clearSecret(SecretKeyName.ANTHROPIC_API_KEY) })
            SecretField("Jev API key", secrets.jevApiKey, masked = true,
                onSave = { viewModel.saveSecret(SecretKeyName.JEV_API_KEY, it) },
                onClear = { viewModel.clearSecret(SecretKeyName.JEV_API_KEY) })
            SecretField("Jev base URL", secrets.jevBaseUrl, masked = false,
                onSave = { viewModel.saveSecret(SecretKeyName.JEV_BASE_URL, it) },
                onClear = { viewModel.clearSecret(SecretKeyName.JEV_BASE_URL) })
            val settingsError by viewModel.error.collectAsStateWithLifecycle()
            settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            Text("Discovery server", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your own server, reached over Tailscale (https://<pc>.<tailnet>.ts.net or http://100.x.y.z:8000). " +
                    "Used by Discover. The token is stored encrypted.",
                style = MaterialTheme.typography.bodySmall,
            )
            SecretField("Server URL", secrets.serverUrl, masked = false,
                onSave = { viewModel.saveSecret(SecretKeyName.SERVER_URL, it) },
                onClear = { viewModel.clearSecret(SecretKeyName.SERVER_URL) })
            secrets.serverUrl?.let { Text("Current: $it", style = MaterialTheme.typography.bodySmall) }
            SecretField("Server API token", secrets.serverToken, masked = true,
                onSave = { viewModel.saveSecret(SecretKeyName.SERVER_TOKEN, it) },
                onClear = { viewModel.clearSecret(SecretKeyName.SERVER_TOKEN) })
            val connection by viewModel.connection.collectAsStateWithLifecycle()
            OutlinedButton(onClick = viewModel::testConnection, enabled = secrets.serverUrl != null && secrets.serverToken != null) {
                Text("Test connection")
            }
            connection?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton(onClick = onOpenCrawl) { Text("Crawl a link") }
            SwitchRow(
                "Add chapters I read to search", library.uploadChapters, viewModel::setUploadChapters,
                "Sends the English text of chapters downloaded on this phone to your server's search index.",
            )
            Text("Scenes Claude writes per search: ${library.searchScenes}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = library.searchScenes.toFloat(),
                onValueChange = { viewModel.setSearchScenes(it.roundToInt()) },
                valueRange = LibraryOptions.SCENES_RANGE.first.toFloat()..LibraryOptions.SCENES_RANGE.last.toFloat(),
                steps = LibraryOptions.SCENES_RANGE.last - LibraryOptions.SCENES_RANGE.first - 1,
            )
            Text("Results reranked: top ${library.rerankPool}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = library.rerankPool.toFloat(),
                onValueChange = { viewModel.setRerankPool((it / LibraryOptions.RERANK_STEP).roundToInt() * LibraryOptions.RERANK_STEP) },
                valueRange = LibraryOptions.RERANK_RANGE.first.toFloat()..LibraryOptions.RERANK_RANGE.last.toFloat(),
                steps = (LibraryOptions.RERANK_RANGE.last - LibraryOptions.RERANK_RANGE.first) / LibraryOptions.RERANK_STEP - 1,
            )
            Text("Reranker", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(library.reranker == "jev", { viewModel.setReranker("jev") }, { Text("Jev") })
                FilterChip(library.reranker == "claude", { viewModel.setReranker("claude") }, { Text("Claude") })
            }

            HorizontalDivider()
            Text("Offline reading", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                "Download whole novel when added to library", library.downloadWholeOnAdd, viewModel::setDownloadWholeOnAdd,
                "Adding a novel downloads all its chapters, and new chapters found later. Off: chapters download as you read.",
            )
            Text("Save opening chapters for the top ${library.searchPrefetchResults} search results", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = library.searchPrefetchResults.toFloat(),
                onValueChange = { viewModel.setSearchPrefetchResults(it.roundToInt()) },
                valueRange = LibraryOptions.RESULTS_RANGE.first.toFloat()..LibraryOptions.RESULTS_RANGE.last.toFloat(),
                steps = LibraryOptions.RESULTS_RANGE.last - 1,
            )
            Text("Chapters saved per result: ${library.searchPrefetchChapters}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = library.searchPrefetchChapters.toFloat(),
                onValueChange = { viewModel.setSearchPrefetchChapters(it.roundToInt()) },
                valueRange = LibraryOptions.CHAPTERS_RANGE.first.toFloat()..LibraryOptions.CHAPTERS_RANGE.last.toFloat(),
                steps = LibraryOptions.CHAPTERS_RANGE.last - 1,
            )
            Text(
                "Saved results stay hidden until you add or read one, and are deleted after 14 days if you never do.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("AI spending", style = MaterialTheme.typography.titleMedium)
            Text(
                "Spent this month: ${"$%.4f".format(spent)} of ${"$%.2f".format(cap)}. " +
                    "Adapter generation, repair, chapter processing, summaries and Ask AI stop when the cap is reached.",
                style = MaterialTheme.typography.bodyMedium,
            )
            CapField(cap, onSave = viewModel::setMonthlyCap)
            OutlinedButton(onClick = onOpenAdapters) { Text("Site adapters") }
            TargetLanguageField(targetLanguage, onSave = viewModel::setTargetLanguage)
            Text("Process ahead: $processAhead chapters (auto fix / translate queue)", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = processAhead.toFloat(),
                onValueChange = { viewModel.setProcessAhead(it.roundToInt()) },
                valueRange = 0f..20f,
                steps = 19,
            )

            HorizontalDivider()
            com.readerapp.tts.ui.TtsSettingsSection()
            Text("Reader", style = MaterialTheme.typography.titleMedium)
            ReaderPrefsControls(prefs, onFontSize = { viewModel.setFontSize(it) }, onLineSpacing = { viewModel.setLineSpacing(it) })
            Text("Prefetch ahead: ${prefs.prefetchCount} chapters", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = prefs.prefetchCount.toFloat(),
                onValueChange = { viewModel.setPrefetchCount(it.roundToInt()) },
                valueRange = ReaderPrefs.PREFETCH_RANGE.first.toFloat()..ReaderPrefs.PREFETCH_RANGE.last.toFloat(),
                steps = ReaderPrefs.PREFETCH_RANGE.last - 1,
            )
            Text("Theme", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = checked, onCheckedChange = onChange)
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

/** Shows a masked value when set; otherwise an input. Values are never displayed in full. */
@Composable
private fun SecretField(
    label: String,
    stored: String?,
    masked: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(label) },
            placeholder = { Text(stored?.let(::maskSecret) ?: "Not set") },
            singleLine = true,
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (masked) KeyboardType.Password else KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = input.isNotBlank(), onClick = { onSave(input); input = "" }) { Text("Save") }
            TextButton(enabled = stored != null, onClick = { onClear(); input = "" }) { Text("Clear") }
        }
    }
}

@Composable
private fun CapField(capUsd: Double, onSave: (Double) -> Unit) {
    var input by remember { mutableStateOf("") }
    val parsed = input.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Monthly cap (USD)") },
            placeholder = { Text("%.2f".format(capUsd)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(enabled = parsed != null, onClick = { parsed?.let(onSave); input = "" }) { Text("Save") }
    }
}

@Composable
private fun TargetLanguageField(current: String, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Default translation language") },
            placeholder = { Text(current) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(enabled = input.isNotBlank(), onClick = { onSave(input); input = "" }) { Text("Save") }
    }
}
