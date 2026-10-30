package com.readerapp.ui.reader

import com.readerapp.ai.ModelPricing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.ZoneOffset

class ClipboardCaptureTest {
    private class FakePort(var text: String?, val onCopyWrites: String?) : ClipboardPort {
        fun copy() { if (onCopyWrites != null) text = onCopyWrites }
        override fun snapshot(): Any? = text
        override fun readText(): String? = text
        override fun setSentinel(token: String) { text = token }
        override fun restore(snapshot: Any?) { text = snapshot as String? }
    }

    @Test
    fun successfulCopyReturnsSelectionAndRestoresClipboard() = runTest {
        val port = FakePort("my password", "selected words")
        assertEquals("selected words", captureSelection(port, { port.copy() }))
        assertEquals("my password", port.text)
    }

    @Test
    fun failedCopyNeverReturnsPreviousClipboardText() = runTest {
        val port = FakePort("my password", onCopyWrites = null)
        assertNull(captureSelection(port, { port.copy() }))
        assertEquals("my password", port.text)
    }

    @Test
    fun clipboardChangedByAnotherAppIsNotOverwrittenOnRestore() = runTest {
        val port = FakePort("old", onCopyWrites = null)
        assertNull(captureSelection(port, { port.text = null }, maxPolls = 2)) // clipboard cleared by someone else
        assertNull(port.text) // neither our sentinel nor our capture: not restored over it
    }

    @Test
    fun resumeWorkNameIsPerTargetMonth() {
        val jan = java.time.LocalDate.of(2026, 1, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val feb = java.time.LocalDate.of(2026, 2, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("spend-resume-2026-02", ModelPricing.resumeWorkName(jan, ZoneOffset.UTC))
        assertNotEquals(ModelPricing.resumeWorkName(jan, ZoneOffset.UTC), ModelPricing.resumeWorkName(feb, ZoneOffset.UTC))
    }
}
