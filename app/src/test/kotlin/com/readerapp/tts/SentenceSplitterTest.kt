package com.readerapp.tts

import com.readerapp.data.db.TextVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {
    private fun texts(p: String) = SentenceSplitter.split(p).map { it.text }

    @Test
    fun splitsPlainSentences() {
        assertEquals(
            listOf("He ran home.", "She stayed behind!", "Why?"),
            texts("He ran home. She stayed behind! Why?"),
        )
    }

    @Test
    fun abbreviationsAndInitialsDoNotSplit() {
        assertEquals(listOf("Mr. Smith met Dr. Jones at 5 p.m. today."), texts("Mr. Smith met Dr. Jones at 5 p.m. today."))
        assertEquals(listOf("J. R. R. Tolkien wrote it.", "Next."), texts("J. R. R. Tolkien wrote it. Next."))
    }

    @Test
    fun decimalsDoNotSplit() {
        assertEquals(listOf("Pi is 3.14 roughly."), texts("Pi is 3.14 roughly."))
    }

    @Test
    fun quotesStayWithTheirSentence() {
        assertEquals(
            listOf("\"Where are you going?\" she asked.", "\"Out.\"", "He left."),
            texts("\"Where are you going?\" she asked. \"Out.\" He left."),
        )
        // A closing quote followed by a capital letter ends the sentence inside the quote.
        assertEquals(listOf("\"Stop!\"", "Nobody listened."), texts("\"Stop!\" Nobody listened."))
    }

    @Test
    fun ellipsisContinuesBeforeLowercaseAndSplitsBeforeCapital() {
        assertEquals(listOf("Well... maybe not."), texts("Well... maybe not."))
        assertEquals(listOf("He waited...", "Nothing came."), texts("He waited... Nothing came."))
        assertEquals(listOf("He waited...", "Nothing came."), texts("He waited… Nothing came."))
    }

    @Test
    fun cjkPunctuationSplitsWithoutSpacesAndIsNormalised() {
        assertEquals(listOf("He said: hello.", "Go now!"), texts("He said：hello。Go now！"))
        assertEquals(listOf("\"Come here\", she said."), texts("「Come here」，she said。"))
    }

    @Test
    fun junkIsStrippedAndEmptyItemsSkipped() {
        assertEquals(listOf("Bold text and a link here."), texts("**Bold** text and a <b>link</b> https://x.com/a?b=1 here."))
        assertEquals(emptyList<String>(), texts("* * *"))
        assertEquals(emptyList<String>(), texts("   ​  "))
        assertEquals(listOf("Hello", "World"), texts("Hello\nWorld"))
    }

    @Test
    fun longSentencesSplitAtCommas() {
        val clause = "the quick brown fox jumped over the lazy dog"
        val long = List(8) { clause }.joinToString(", ") + "."
        val parts = SentenceSplitter.split(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.end - it.start <= SentenceSplitter.MAX_CHARS })
        assertTrue(parts.dropLast(1).all { long[it.end - 1] == ',' })
        // Pieces are contiguous (ignoring whitespace) and cover the paragraph.
        assertEquals(long.replace(" ", ""), parts.joinToString("") { long.substring(it.start, it.end) }.replace(" ", ""))
    }

    @Test
    fun longTextWithoutCommasSplitsAtSpaces() {
        val long = List(80) { "word" }.joinToString(" ")
        val parts = SentenceSplitter.split(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.end - it.start <= SentenceSplitter.MAX_CHARS })
    }

    @Test
    fun spansPointIntoTheOriginalParagraph() {
        val p = "  First one.   Second \"one\" here?  "
        val spans = SentenceSplitter.split(p)
        assertEquals("First one.", p.substring(spans[0].start, spans[0].end))
        assertEquals("Second \"one\" here?", p.substring(spans[1].start, spans[1].end))
    }

    @Test
    fun englishCheck() {
        assertTrue(ChapterSpeechSource.isEnglish(null, TextVariant.TRANSLATED, listOf("The hero walked into the dark forest alone.")))
        assertFalse(ChapterSpeechSource.isEnglish(null, TextVariant.ORIGINAL, listOf("他走进了黑暗的森林，没有人知道他去了哪里，也没有人知道他为什么要离开那座古老的城市。")))
        assertFalse(ChapterSpeechSource.isEnglish("Chinese", TextVariant.ORIGINAL, listOf("The hero walked into the dark forest alone.")))
        assertTrue(ChapterSpeechSource.isEnglish("en", TextVariant.ORIGINAL, listOf("The hero walked into the dark forest alone.")))
    }
}
