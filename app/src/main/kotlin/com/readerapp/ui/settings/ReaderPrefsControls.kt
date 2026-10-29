package com.readerapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.readerapp.data.settings.ReaderPrefs
import java.util.Locale

/** Font size + line spacing sliders, shared by the reader's quick sheet and the settings screen. */
@Composable
fun ReaderPrefsControls(
    prefs: ReaderPrefs,
    onFontSize: (Float) -> Unit,
    onLineSpacing: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("Font size: ${prefs.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = prefs.fontSizeSp,
            onValueChange = onFontSize,
            valueRange = ReaderPrefs.FONT_SIZE_RANGE,
            steps = (ReaderPrefs.FONT_SIZE_RANGE.endInclusive - ReaderPrefs.FONT_SIZE_RANGE.start).toInt() - 1,
        )
        Text(
            "Line spacing: ${String.format(Locale.US, "%.1f", prefs.lineSpacing)}x",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(value = prefs.lineSpacing, onValueChange = onLineSpacing, valueRange = ReaderPrefs.LINE_SPACING_RANGE)
    }
}
