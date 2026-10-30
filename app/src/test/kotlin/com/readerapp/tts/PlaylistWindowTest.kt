package com.readerapp.tts

import com.readerapp.data.db.TextVariant
import com.readerapp.tts.ui.isSamePlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaylistWindowTest {
    private fun item(i: Int) = ReadyItem(SpeechRef(1, i / 3, i % 3), File("f$i.wav"), 10, "s$i", "c", "n", "ORIGINAL", false)

    @Test
    fun windowStaysBoundedAndTracksAbsolutePlayhead() {
        val w = PlaylistWindow(keepBehind = 5)
        (0 until 30).forEach { w.append(item(it)) }
        assertEquals(0, w.excess(4))
        assertFalse(w.hasTrimmedHead)
        val n = w.excess(12)
        assertEquals(7, n)
        val dropped = w.drop(n)
        assertEquals("f0.wav", dropped.first().file.name)
        assertEquals(23, w.items.size)
        assertEquals("f7.wav", w.items.first().file.name)
        assertEquals(12, w.absolute(5)) // player index 5 is absolute item 12
        assertTrue(w.hasTrimmedHead)
        w.clear()
        assertEquals(0, w.absolute(0))
        assertFalse(w.hasTrimmedHead)
    }

    @Test
    fun playButtonRestartsWhenTheDisplayedVariantDiffers() {
        val playing = TtsState(active = true, ref = SpeechRef(7, 0, 0), variant = "ORIGINAL")
        assertTrue(isSamePlayback(playing, 7, TextVariant.ORIGINAL))
        assertFalse(isSamePlayback(playing, 7, TextVariant.FIXED))
        assertFalse(isSamePlayback(playing, 8, TextVariant.ORIGINAL))
        assertFalse(isSamePlayback(playing.copy(active = false), 7, TextVariant.ORIGINAL))
    }
}
