package com.readerapp.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {
    private val cleaner = TextCleaner()

    @Test
    fun splitsOnParagraphTagsAndTrims() {
        val html = "<div><p>  First   paragraph. </p><p>Second paragraph.</p><p>   </p><p></p></div>"
        assertEquals(listOf("First paragraph.", "Second paragraph."), cleaner.toParagraphs(html))
    }

    @Test
    fun splitsOnBrAndKeepsLooseText() {
        val html = "Line one<br>Line two<br/><br>Line three<div>Block four</div>trailing"
        assertEquals(listOf("Line one", "Line two", "Line three", "Block four", "trailing"), cleaner.toParagraphs(html))
    }

    @Test
    fun dropsScriptsAndStyles() {
        val html = "<p>Visible</p><script>var a = 'hidden';</script><style>p{color:red}</style><p>Also visible</p>"
        assertEquals(listOf("Visible", "Also visible"), cleaner.toParagraphs(html))
    }

    @Test
    fun inlineElementsStayInOneParagraph() {
        val html = "<p>She said <em>hello</em> and <b>left</b>.</p>"
        assertEquals(listOf("She said hello and left."), cleaner.toParagraphs(html))
    }

    @Test
    fun removesDefaultWatermarkLines() {
        val html = """
            <p>The door creaked open slowly.</p>
            <p>Read the latest chapters at example-novels.com</p>
            <p>www.example-novels.com</p>
            <p>If you find any errors, please report them to us.</p>
            <p>He stepped inside.</p>
        """.trimIndent()
        assertEquals(listOf("The door creaked open slowly.", "He stepped inside."), cleaner.toParagraphs(html))
    }

    @Test
    fun doesNotDropNormalSentencesMentioningReading() {
        val text = "She wanted to read the latest chapters of the old diary before dawn."
        assertEquals(listOf(text), cleaner.removeJunk(listOf(text)))
    }

    @Test
    fun customPatternsAreApplied() {
        val custom = TextCleaner(listOf(Regex("^\\[ad\\].*")))
        assertEquals(listOf("keep"), custom.removeJunk(listOf("[ad] buy now", "keep")))
        // Defaults are replaced, not merged.
        assertTrue(custom.removeJunk(listOf("www.example.com")).isNotEmpty())
    }

    @Test
    fun handlesCjkWatermarks() {
        val result = cleaner.removeJunk(listOf("他走进了房间。", "请记住本书首发域名：example.com"))
        assertEquals(listOf("他走进了房间。"), result)
    }

    @Test
    fun dropsCounterParagraphsButKeepsSentencesWithNumbers() {
        val paragraphs = listOf("Arrogant! Cruel!", "13", "1.2k", "99+", "He was 13 years old.", "Chapter 2", "2.")
        assertEquals(listOf("Arrogant! Cruel!", "He was 13 years old.", "Chapter 2", "2."), cleaner.removeJunk(paragraphs))
    }
}
