package com.readerapp.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class MenuRequest(val rect: Rect, val onCopy: (() -> Unit)?, val onSelectAll: (() -> Unit)?)

/**
 * A [TextToolbar] that shows its own small menu (Copy / Select all / Ask AI) next to the selection. The platform
 * toolbar has no hook for custom items and does not hand out the selected text, so this one gets the text by
 * invoking the selection's copy callback (see [copySelectionText]).
 */
private class AskAiTextToolbar(private val request: MutableState<MenuRequest?>) : TextToolbar {
    override val status: TextToolbarStatus
        get() = if (request.value != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        request.value = MenuRequest(rect, onCopyRequested, onSelectAllRequested)
    }

    override fun hide() {
        request.value = null
    }
}

/**
 * Selected text via the copy callback: remember what is on the clipboard, trigger the copy, read the selection from
 * the clipboard and put the user's previous clipboard contents back.
 */
internal suspend fun copySelectionText(context: Context, onCopy: () -> Unit): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val previous: ClipData? = runCatching { clipboard.primaryClip }.getOrNull()
    val previousText = runCatching { previous?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
    onCopy()
    var text: String? = null
    // The copy is dispatched asynchronously inside the selection manager: wait until the clipboard changes.
    // If it never changes, the selection equals what was already on the clipboard, which is fine.
    var attempts = 0
    while (attempts++ < 10) {
        delay(40)
        text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        if (!text.isNullOrBlank() && text != previousText) break
    }
    runCatching {
        if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
    }
    return text?.takeIf { it.isNotBlank() }
}

/** Installs [AskAiTextToolbar] for [content] (a selection container) and calls [onAsk] with the highlighted text. */
@Composable
fun ProvideAskAiToolbar(onAsk: (String) -> Unit, content: @Composable () -> Unit) {
    val request = remember { mutableStateOf<MenuRequest?>(null) }
    val toolbar = remember { AskAiTextToolbar(request) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalTextToolbar provides toolbar) { content() }

    request.value?.let { req ->
        Popup(
            popupPositionProvider = remember(req.rect) { AboveRectPositionProvider(req.rect) },
            properties = PopupProperties(focusable = false),
        ) {
            Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 4.dp)) {
                    req.onCopy?.let { copy -> TextButton(onClick = { copy(); toolbar.hide() }) { Text("Copy") } }
                    req.onSelectAll?.let { all -> TextButton(onClick = { all() }) { Text("Select all") } }
                    req.onCopy?.let { copy ->
                        TextButton(onClick = {
                            scope.launch {
                                val text = copySelectionText(context, copy)
                                toolbar.hide()
                                if (text != null) onAsk(text)
                            }
                        }) { Text("Ask AI") }
                    }
                }
            }
        }
    }
}

/** Places the popup centred above the selection, or below it when there is no room above. */
private class AboveRectPositionProvider(private val rect: Rect) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val gap = 12
        val x = (rect.center.x.toInt() - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = rect.top.toInt() - popupContentSize.height - gap
        val y = if (above >= 0) above else rect.bottom.toInt() + gap + 48
        return IntOffset(x, y.coerceAtLeast(0))
    }
}
