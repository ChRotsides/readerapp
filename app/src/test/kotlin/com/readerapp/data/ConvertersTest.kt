package com.readerapp.data

import com.readerapp.data.db.AdapterKind
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.db.Converters
import com.readerapp.data.db.TextVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertersTest {
    private val c = Converters()

    @Test
    fun paragraphsRoundTrip() {
        val paragraphs = listOf("Plain.", "With \"quotes\" and \\ backslash", "Line\nbreak", "日本語 / 한국어 / emoji 😀", "")
        assertEquals(paragraphs, c.jsonToParagraphs(c.paragraphsToJson(paragraphs)))
    }

    @Test
    fun emptyListAndBlankJson() {
        assertEquals("[]", c.paragraphsToJson(emptyList()))
        assertTrue(c.jsonToParagraphs("[]").isEmpty())
        assertTrue(c.jsonToParagraphs("").isEmpty())
    }

    @Test
    fun paragraphsAreStoredAsJsonArray() {
        assertEquals("[\"a\",\"b\"]", c.paragraphsToJson(listOf("a", "b")))
    }

    @Test
    fun enumsRoundTrip() {
        ChapterStatus.entries.forEach { assertEquals(it, c.stringToChapterStatus(c.chapterStatusToString(it))) }
        TextVariant.entries.forEach { assertEquals(it, c.stringToVariant(c.variantToString(it))) }
        AdapterKind.entries.forEach { assertEquals(it, c.stringToAdapterKind(c.adapterKindToString(it))) }
        AdapterStatus.entries.forEach { assertEquals(it, c.stringToAdapterStatus(c.adapterStatusToString(it))) }
    }

    @Test
    fun enumStorageNamesMatchDesign() {
        assertEquals("NOT_FETCHED", c.chapterStatusToString(ChapterStatus.NOT_FETCHED))
        assertEquals("TRANSLATED", c.variantToString(TextVariant.TRANSLATED))
    }
}
