package com.readerapp.scrape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTrimmerTest {
    private val page = """
        <!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>My Title</title>
        <link rel="stylesheet" href="a.css"><style>p{color:red}</style><script>var x=1;</script></head>
        <body onload="boot()"><!-- secret comment -->
          <svg><path d="M0"/></svg><noscript>enable js</noscript><iframe src="ad.html"></iframe>
          <div id="main" class="wrap" style="color:red" data-track="abc" onclick="x()">
             <p>Hello    world
                again</p>
             <a href="/next" rel="next" target="_blank" title="t">Next</a>
          </div></body></html>
    """.trimIndent()

    @Test
    fun stripsNoiseAndKeepsStructureAttributes() {
        val r = HtmlTrimmer.trim(page, "https://x.example/")
        assertFalse(r.reduced)
        val h = r.html
        listOf("<script", "<style", "<svg", "<noscript", "<iframe", "<link", "<meta", "secret comment", "onclick", "onload",
            "style=", "data-track", "target=", "title=\"t\"").forEach { assertFalse("still contains $it", h.contains(it)) }
        assertTrue(h.contains("<title>My Title</title>"))
        assertTrue(h.contains("id=\"main\""))
        assertTrue(h.contains("class=\"wrap\""))
        assertTrue(h.contains("href=\"/next\""))
        assertTrue(h.contains("rel=\"next\""))
        assertTrue(h.contains("Hello world again"))
    }

    private fun bigPage(paragraphs: Int): String {
        val body = (1..paragraphs).joinToString("") { "<p class=\"line\">Paragraph number $it with some invented filler words to take up space.</p>" }
        return "<html><head><title>Big</title></head><body><div id=\"top\"><a class=\"nav\" href=\"/toc\">Contents</a>" +
            "<div id=\"chapter\">$body</div><a rel=\"next\" href=\"/next-chapter\">Next</a></div></body></html>"
    }

    @Test
    fun oversizedPageIsReducedAndSaysSo() {
        val r = HtmlTrimmer.trim(bigPage(5_000), "https://x.example/", maxChars = 20_000)
        assertTrue(r.reduced)
        assertTrue(r.html.length <= 20_300)
        assertTrue(r.html.startsWith("<!-- TRIMMED"))
        assertTrue(r.html.contains("NOT the complete page"))
        assertTrue(r.html.contains("elements omitted"))
        assertTrue(r.html.contains("Paragraph number 1 "))           // start of the main region kept
        assertTrue(r.html.contains("href=\"/next-chapter\""))         // links off the main path kept
        assertTrue(r.html.contains("href=\"/toc\""))
        assertTrue(r.originalLength > 20_000)
    }

    @Test
    fun smallPageUnchangedBySizeLimit() {
        val r = HtmlTrimmer.trim(bigPage(3), "https://x.example/")
        assertFalse(r.reduced)
        assertEquals(r.originalLength, r.html.length)
    }
}
