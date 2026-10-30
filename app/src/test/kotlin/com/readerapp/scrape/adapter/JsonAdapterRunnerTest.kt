package com.readerapp.scrape.adapter

import com.readerapp.scrape.ExtractionResult
import com.readerapp.scrape.FetchMode
import com.readerapp.scrape.FetchedPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonAdapterRunnerTest {
    private val chapterHtml = """
        <html><head><title>Test Site</title></head><body>
          <div class="nav"><a class="prev" href="/book/1/ch-1">Previous</a><a class="next" href="/book/1/ch-3">Next</a></div>
          <h1 class="ch-title">Chapter 2: Sample</h1>
          <div id="content">
            <div class="ad">Buy things now</div>
            <p>First invented sentence for the test fixture.</p>
            <p>Second invented sentence, nothing real here.</p>
            <p>Read the latest chapters at www.example-site.com</p>
            <p>WATERMARK-XYZ should be dropped by cleanup.</p>
            <p>Third invented sentence with <em>inline</em> markup.</p>
          </div>
          <div class="info"><span class="book">Fixture Book</span><span class="by">Some Author</span>
            <img class="cover" data-src="/img/c.jpg"><div class="syn">A made-up synopsis.</div></div>
        </body></html>
    """.trimIndent()

    private val spec = JsonAdapterSpec(
        chapter = ChapterSpec(
            content = "#content", title = ".ch-title", paragraphs = "p", remove = listOf(".ad"),
            next = "a.next", prev = "a.prev",
        ),
        novel = NovelSpec(title = ".book", author = ".by", cover = ".cover", synopsis = ".syn"),
        cleanup = CleanupSpec(dropLines = listOf("WATERMARK-[A-Z]+")),
    )

    private fun page(html: String, url: String = "https://site.example/book/1/ch-2") = FetchedPage(url, html, FetchMode.HTTP)

    @Test
    fun extractsTitleParagraphsLinksAndNovelInfo() {
        val result = JsonAdapterRunner.run(spec, page(chapterHtml), "adapter:site.example:v1") as ExtractionResult.Success
        val c = result.chapter
        assertEquals("Chapter 2: Sample", c.title)
        assertEquals(
            listOf(
                "First invented sentence for the test fixture.",
                "Second invented sentence, nothing real here.",
                "Third invented sentence with inline markup.",
            ),
            c.paragraphs,
        )
        assertEquals("https://site.example/book/1/ch-3", c.nextUrl)
        assertEquals("https://site.example/book/1/ch-1", c.prevUrl)
        assertEquals("adapter:site.example:v1", result.extractor)
        val info = result.novelInfo!!
        assertEquals("Fixture Book", info.title)
        assertEquals("Some Author", info.author)
        assertEquals("https://site.example/img/c.jpg", info.coverUrl)
        assertEquals("A made-up synopsis.", info.synopsis)
    }

    @Test
    fun withoutParagraphSelectorSplitsContainerByBlocks() {
        val s = spec.copy(chapter = spec.chapter.copy(paragraphs = null))
        val result = JsonAdapterRunner.run(s, page(chapterHtml), "t") as ExtractionResult.Success
        assertEquals(3, result.chapter.paragraphs.size)
    }

    @Test
    fun missingContentSelectorFails() {
        val s = spec.copy(chapter = spec.chapter.copy(content = "#nope"))
        assertTrue(JsonAdapterRunner.run(s, page(chapterHtml), "t") is ExtractionResult.Failure)
    }

    @Test
    fun absentNextLinkIsNull() {
        val s = spec.copy(chapter = spec.chapter.copy(next = ".missing"))
        val c = (JsonAdapterRunner.run(s, page(chapterHtml), "t") as ExtractionResult.Success).chapter
        assertNull(c.nextUrl)
    }

    @Test
    fun tocWithPagination() {
        val html = """<ul class="toc"><li><a href="/c/1">One</a></li><li><a href="/c/2">Two</a></li><li><a href="/c/2">Two again</a></li></ul>
            <a class="more" href="?page=2">More</a>"""
        val s = JsonAdapterSpec(
            chapter = ChapterSpec(content = "body"),
            toc = TocSpec(items = ".toc a", nextPage = "a.more"),
        )
        val (_, toc) = JsonAdapterRunner.runNovelPage(s, page(html, "https://site.example/book/1"))
        assertEquals(listOf("https://site.example/c/1", "https://site.example/c/2"), toc.refs.map { it.url })
        assertEquals("https://site.example/book/1?page=2", toc.nextPageUrl)
    }

    @Test
    fun validateFlagsBadSelectorsAndRegexes() {
        assertTrue(spec.validate().isEmpty())
        assertFalse(spec.copy(chapter = spec.chapter.copy(content = "div[")).validate().isEmpty())
        assertFalse(spec.copy(cleanup = CleanupSpec(listOf("("))).validate().isEmpty())
        assertFalse(spec.copy(chapter = spec.chapter.copy(content = "")).validate().isEmpty())
    }

    @Test
    fun specRoundTripsThroughJson() {
        assertEquals(spec, JsonAdapterSpec.parse(spec.toJson()))
    }

    @Test
    fun parseToleratesNullsAndUnknownKeys() {
        val s = JsonAdapterSpec.parse("""{"chapter":{"content":"#c","title":null,"remove":[],"extra":1},"cleanup":{"dropLines":[]},"novel":null}""")
        assertEquals("#c", s.chapter.content)
    }
}
