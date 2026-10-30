package com.readerapp.ai

import com.readerapp.ai.qa.QaContext
import com.readerapp.ai.qa.QaPromptBuilder
import com.readerapp.ai.qa.QaTurn
import com.readerapp.ai.summary.ArcMath
import com.readerapp.ai.summary.SummarySelection
import com.readerapp.ai.summary.SummarySelector
import com.readerapp.data.db.ArcSummary
import com.readerapp.data.db.IndexedSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarySelectorTest {
    private fun chapters(range: IntRange) = range.map { IndexedSummary(it.toLong(), it, "summary of chapter $it") }
    private fun arc(i: Int) = ArcSummary(1, i, ArcMath.start(i), ArcMath.end(i), "arc $i summary")

    @Test
    fun `only chapters before the current one are ever selected`() {
        val sel = SummarySelector.select(listOf(arc(0)), chapters(0..120), currentIndex = 30)
        assertTrue(sel.chapters.all { it.chapterIndex < 30 })
        assertEquals((0..29).toList(), sel.chapters.map { it.chapterIndex })
        assertTrue("arc 0 ends at 49, after the current chapter", sel.arcs.isEmpty())
    }

    @Test
    fun `old arcs replace old chapters and the last 50 chapters stay individual`() {
        val sel = SummarySelector.select(listOf(arc(0), arc(1)), chapters(0..199), currentIndex = 160)
        // recent = chapters 110..159; arcs fully before 110: arc 0 (0-49) and arc 1 (50-99)
        assertEquals(listOf(0, 1), sel.arcs.map { it.arcIndex })
        // chapters 100..109 are not covered by an arc and not recent => included individually
        assertEquals((100..159).toList(), sel.chapters.map { it.chapterIndex })
    }

    @Test
    fun `arc overlapping the recent window is not used, its chapters are`() {
        val sel = SummarySelector.select(listOf(arc(0), arc(1)), chapters(0..139), currentIndex = 120)
        // recent = 70..119; arc 1 (50-99) overlaps it -> skipped; arc 0 used
        assertEquals(listOf(0), sel.arcs.map { it.arcIndex })
        assertEquals((50..119).toList(), sel.chapters.map { it.chapterIndex })
    }

    @Test
    fun `no summaries yields an empty selection`() {
        val sel = SummarySelector.select(emptyList(), emptyList(), 10)
        assertTrue(sel.arcs.isEmpty() && sel.chapters.isEmpty())
    }

    @Test
    fun `arc math groups indices in blocks of 50 including negatives`() {
        assertEquals(0, ArcMath.arcIndex(49))
        assertEquals(1, ArcMath.arcIndex(50))
        assertEquals(-1, ArcMath.arcIndex(-1))
        assertEquals(50, ArcMath.start(1))
        assertEquals(99, ArcMath.end(1))
    }
}

class QaPromptBuilderTest {
    private val selection = SummarySelector.select(
        listOf(ArcSummary(1, 0, 0, 49, "ARC-ZERO")),
        (0..130).map { IndexedSummary(it.toLong(), it, "SUM-$it-END") },
        currentIndex = 100,
    )

    private fun ctx(paragraphs: List<String> = listOf("First paragraph.", "Second paragraph.")) = QaContext(
        novelTitle = "My Novel", synopsis = "A hero rises.", summaries = selection,
        chapterTitle = "Chapter 100", variantLabel = "translated", paragraphs = paragraphs,
    )

    @Test
    fun `context contains earlier summaries only and never later chapters`() {
        val block = QaPromptBuilder.contextBlock(ctx())
        assertTrue(block.contains("ARC-ZERO"))
        assertTrue(block.contains("SUM-99-END"))
        assertTrue(block.contains("SUM-50-END"))
        assertFalse("current chapter's own summary must not appear", block.contains("SUM-100-END"))
        assertFalse(block.contains("SUM-101-END"))
        assertFalse(block.contains("SUM-130-END"))
        assertFalse("summaries covered by the arc are not repeated", block.contains("SUM-10-END"))
        assertTrue(block.contains("My Novel") && block.contains("A hero rises."))
        assertTrue(block.contains("[1] First paragraph.") && block.contains("[2] Second paragraph."))
    }

    @Test
    fun `context order is novel, story so far, then the current chapter`() {
        val block = QaPromptBuilder.contextBlock(ctx())
        val novel = block.indexOf("<novel ")
        val so = block.indexOf("<story_so_far>")
        val arc = block.indexOf("ARC-ZERO")
        val recent = block.indexOf("SUM-99-END")
        val cur = block.indexOf("<current_chapter")
        assertTrue(novel in 0 until so && so < arc && arc < recent && recent < cur)
    }

    @Test
    fun `request puts stable parts first with cache breakpoints and the question last`() {
        val context = QaPromptBuilder.contextBlock(ctx())
        val q1 = QaPromptBuilder.questionText("the old man", "Who is this?")
        val first = QaPromptBuilder.request(context, q1, emptyList())

        assertEquals(ClaudeModels.SONNET, first.model)
        assertTrue(first.serverFallback)
        assertTrue("system block is a cache breakpoint", first.cacheSystem)
        assertEquals(context, first.context)           // cached block holds all stable material
        assertTrue(first.system.contains("never reveal", ignoreCase = true) || first.system.contains("Never reveal"))
        assertTrue(first.system.contains("don't know"))
        assertTrue(first.user.contains("the old man") && first.user.contains("Who is this?"))
        assertFalse("question must not leak into the cached prefix", first.context!!.contains("Who is this?"))

        val answer = "He is the sect elder."
        val second = QaPromptBuilder.request(context, "<question>\nWhy?\n</question>", listOf(QaTurn(q1, answer)))
        // follow-up: byte-identical system + context + first turn => cache hit on the prefix
        assertEquals(first.system, second.system)
        assertEquals(first.context, second.context)
        assertEquals(listOf(q1 to answer), second.history)
        assertTrue(second.user.contains("Why?"))
    }

    @Test
    fun `empty summaries are stated explicitly`() {
        val block = QaPromptBuilder.contextBlock(ctx().copy(summaries = SummarySelection(emptyList(), emptyList())))
        assertTrue(block.contains("no summaries of earlier chapters"))
        assertNull(null)
    }
}
