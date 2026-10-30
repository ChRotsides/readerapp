package com.readerapp.ui.reader

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/** The few clipboard operations selection capture needs (an interface so it can be faked in JVM tests). */
internal interface ClipboardPort {
    fun snapshot(): Any?
    fun readText(): String?
    fun setSentinel(token: String)
    fun restore(snapshot: Any?)
}

/**
 * Reads the selection through the selection manager's copy callback WITHOUT ever trusting old clipboard contents: a
 * unique sentinel is put on the clipboard first, so the capture only counts once the clipboard holds something else,
 * i.e. the copy really happened. A copy that fails or is too slow yields null (the caller shows nothing; the
 * previous clipboard text is never returned). The user's clipboard is restored in `finally` (also on cancellation),
 * unless it was changed by someone else in the meantime.
 */
internal suspend fun captureSelection(
    port: ClipboardPort,
    onCopy: () -> Unit,
    pollMs: Long = 40,
    maxPolls: Int = 25,
    token: String = "readerapp-" + UUID.randomUUID(),
): String? {
    val previous = port.snapshot()
    var captured: String? = null
    try {
        port.setSentinel(token)
        if (port.readText() != token) return null // cannot write the clipboard: nothing can be confirmed
        onCopy()
        var polls = 0
        while (captured == null && polls++ < maxPolls) {
            delay(pollMs)
            val now = port.readText()
            if (!now.isNullOrBlank() && now != token) captured = now
        }
    } finally {
        withContext(NonCancellable) {
            val now = port.readText()
            if (now == token || (captured != null && now == captured)) port.restore(previous)
        }
    }
    return captured?.takeIf { it.isNotBlank() }
}
