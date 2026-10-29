package com.readerapp.scrape

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericExtractorTest {
    private val base = "https://novels.example.org/book/1/chapter-5"

    private fun links(html: String) = ChapterLinkDetector.detect(Jsoup.parse(html, base), base)

    @Test
    fun detectsRelNextAndPrevOnLinkTags() {
        val l = links(
            """<html><head><link rel="prev" href="/book/1/chapter-4"><link rel="next" href="/book/1/chapter-6"></head><body></body></html>""",
        )
        assertEquals("https://novels.example.org/book/1/chapter-6", l.next)
        assertEquals("https://novels.example.org/book/1/chapter-4", l.prev)
    }

    @Test
    fun detectsRelOnAnchors() {
        val l = links("""<a rel="prev" href="chapter-4">x</a><a rel="next nofollow" href="chapter-6">y</a>""")
        assertEquals("https://novels.example.org/book/1/chapter-6", l.next)
        assertEquals("https://novels.example.org/book/1/chapter-4", l.prev)
    }

    @Test
    fun detectsEnglishLinkText() {
        val l = links("""<a href="/c/4">&larr; Previous Chapter</a> <a href="/toc">Index</a> <a href="/c/6">Next Chapter &rarr;</a>""")
        assertEquals("https://novels.example.org/c/6", l.next)
        assertEquals("https://novels.example.org/c/4", l.prev)
    }

    @Test
    fun detectsBareNextAndPrev() {
        val l = links("""<a href="/c/4">Prev</a><a href="/c/6">Next</a>""")
        assertEquals("https://novels.example.org/c/6", l.next)
        assertEquals("https://novels.example.org/c/4", l.prev)
    }

    @Test
    fun detectsChineseKoreanAndJapanese() {
        val zh = links("""<a href="/4">上一章</a><a href="/6">下一章</a>""")
        assertEquals("https://novels.example.org/6", zh.next)
        assertEquals("https://novels.example.org/4", zh.prev)
        val ko = links("""<a href="/4">이전</a><a href="/6">다음 화</a>""")
        assertEquals("https://novels.example.org/6", ko.next)
        assertEquals("https://novels.example.org/4", ko.prev)
        val ja = links("""<a href="/4">前の章</a><a href="/6">次の章</a>""")
        assertEquals("https://novels.example.org/6", ja.next)
        assertEquals("https://novels.example.org/4", ja.prev)
    }

    @Test
    fun fallsBackToIdAndClassHints() {
        val l = links("""<a id="prev_chap" href="/c/4">&lt;</a><a class="btn btn-next" href="/c/6"><i></i></a>""")
        assertEquals("https://novels.example.org/c/6", l.next)
        assertEquals("https://novels.example.org/c/4", l.prev)
    }

    @Test
    fun ignoresPlaceholderAndSelfLinks() {
        val l = links("""<a href="#">Next</a><a href="javascript:void(0)">Previous</a><a href="$base#comments">Next</a>""")
        assertNull(l.next)
        assertNull(l.prev)
    }

    @Test
    fun ignoresLongSentencesContainingNext() {
        val l = links("""<a href="/promo">Next time we will talk about something else entirely</a>""")
        assertNull(l.next)
    }

    @Test
    fun relBeatsLinkText() {
        val l = links("""<link rel="next" href="/real-next"><a href="/decoy">Next</a>""")
        assertEquals("https://novels.example.org/real-next", l.next)
    }

    @Test
    fun extractsChapterEndToEnd() {
        val body = (1..12).joinToString("\n") {
            "<p>This is invented sentence number $it for the fixture; it only exists to give the extractor enough text to treat the block as the article body.</p>"
        }
        val html = """
            <html><head><title>Chapter 5 - The Fixture Saga - Example Novels</title>
            <meta property="og:image" content="/covers/fixture.jpg">
            <meta name="author" content="Fixture Author"></head>
            <body>
              <nav><a href="/">Home</a><a href="/library">Library</a></nav>
              <div class="reader">
                <h1>Chapter 5: Test Heading</h1>
                $body
                <div class="nav"><a href="/book/1/chapter-4">Previous</a> <a href="/book/1/chapter-6">Next</a></div>
              </div>
              <footer>Copyright nobody</footer>
            </body></html>
        """.trimIndent()

        val result = GenericExtractor().extractBlocking(FetchedPage(base, html, FetchMode.HTTP))
        assertTrue("expected success but was $result", result is ExtractionResult.Success)
        result as ExtractionResult.Success
        assertEquals("Chapter 5: Test Heading", result.chapter.title)
        assertTrue(result.chapter.paragraphs.size >= 12)
        assertTrue(result.chapter.paragraphs.any { it.contains("invented sentence number 7") })
        assertTrue(result.chapter.paragraphs.none { it.contains("Copyright nobody") })
        assertEquals("https://novels.example.org/book/1/chapter-6", result.chapter.nextUrl)
        assertEquals("https://novels.example.org/book/1/chapter-4", result.chapter.prevUrl)
        assertEquals("The Fixture Saga", result.novelInfo?.title)
        assertEquals("Fixture Author", result.novelInfo?.author)
        assertEquals("https://novels.example.org/covers/fixture.jpg", result.novelInfo?.coverUrl)
    }

    @Test
    fun failsOnEmptyPage() {
        val result = GenericExtractor().extractBlocking(FetchedPage(base, "<html><body></body></html>", FetchMode.HTTP))
        assertTrue(result is ExtractionResult.Failure)
    }

    @Test
    fun novelTitleGuessSkipsChapterAndSite() {
        assertEquals(
            "My Novel",
            GenericExtractor.guessNovelTitle("Chapter 12 - My Novel | SiteName", "Chapter 12", "SiteName"),
        )
    }
}
