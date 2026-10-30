package com.readerapp.data.repo

import com.readerapp.data.db.ChapterText
import com.readerapp.data.db.TextVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredTextCleanupTest {
    private fun row(variant: TextVariant, vararg p: String) = ChapterText(1, variant, p.toList())

    @Test
    fun removesOriginalCounterIndicesFromAlignedVariants() {
        val out = StoredTextCleanup.cleanChapter(
            listOf(
                row(TextVariant.ORIGINAL, "A villain!", "13", "He bullied the weak.", "20"),
                // The fixer kept the counters, possibly reworded nothing; alignment is by index.
                row(TextVariant.FIXED, "A villain!", "13", "He bullied the weak.", "20"),
                row(TextVariant.TRANSLATED, "¡Un villano!", "13", "Intimidaba a los débiles.", "20"),
            ),
        )!!
        out.forEach { assertEquals(2, it.paragraphs.size) }
        assertEquals(listOf("¡Un villano!", "Intimidaba a los débiles."), out.single { it.variant == TextVariant.TRANSLATED }.paragraphs)
    }

    @Test
    fun misalignedVariantFallsBackToContentFilter() {
        val out = StoredTextCleanup.cleanChapter(
            listOf(row(TextVariant.ORIGINAL, "A", "13", "B"), row(TextVariant.FIXED, "A.", "13")),
        )!!
        assertEquals(listOf("A."), out.single { it.variant == TextVariant.FIXED }.paragraphs)
    }

    @Test
    fun cleanChapterIsNullWhenNothingToRemove() {
        assertNull(StoredTextCleanup.cleanChapter(listOf(row(TextVariant.ORIGINAL, "He was 13 years old."))))
    }
}
