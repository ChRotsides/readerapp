package com.readerapp.scrape.adapter

import com.readerapp.scrape.ExtractionResult
import com.readerapp.scrape.FetchMode
import com.readerapp.scrape.FetchedPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterUrlsTest {
    private val page = "https://www.site.example/book/1/ch-2"

    @Test fun relativeUrlsResolveAgainstThePage() {
        assertEquals("https://www.site.example/book/1/ch-3", AdapterUrls.sameSite("ch-3", page))
        assertEquals("https://www.site.example/x", AdapterUrls.sameSite("/x", page))
    }

    @Test fun sameRegistrableDomainIsAllowedAcrossSubdomains() {
        assertEquals("https://m.site.example/a", AdapterUrls.sameSite("https://m.site.example/a", page))
        assertEquals("https://site.example/a", AdapterUrls.sameSite("https://site.example/a", page))
    }

    @Test fun crossSiteAndNonHttpUrlsAreRejected() {
        assertNull(AdapterUrls.sameSite("https://evil.example/?c=secret", page))
        assertNull(AdapterUrls.sameSite("https://site.example.evil.com/", page))
        assertNull(AdapterUrls.sameSite("javascript:alert(1)", page))
        assertNull(AdapterUrls.sameSite("ftp://site.example/a", page))
        assertNull(AdapterUrls.sameSite("data:text/html,x", page))
        assertNull(AdapterUrls.sameSite("https://adapter-sandbox.invalid/x", page))
        assertNull(AdapterUrls.sameSite("https://user:pw@site.example/a", page))
        assertNull(AdapterUrls.sameSite("", page))
        assertNull(AdapterUrls.sameSite(null, page))
        assertNull(AdapterUrls.sameSite("http://[bad", page))
    }

    @Test fun privateSuffixTenantsAreDifferentSites() {
        assertEquals("novel.blogspot.com", AdapterUrls.registrableDomain("www.novel.blogspot.com"))
        assertEquals("me.github.io", AdapterUrls.registrableDomain("me.github.io"))
        assertNull(AdapterUrls.sameSite("https://attacker.blogspot.com/x?d=1", "https://novel.blogspot.com/c/1"))
        assertNull(AdapterUrls.sameSite("https://evil.github.io/", "https://me.github.io/c/1"))
        assertEquals("https://me.github.io/c/2", AdapterUrls.sameSite("/c/2", "https://me.github.io/c/1"))
        assertEquals("https://m.novel.blogspot.com/c/2", AdapterUrls.sameSite("https://m.novel.blogspot.com/c/2", "https://novel.blogspot.com/c/1"))
        assertEquals("localhost", AdapterUrls.registrableDomain("localhost"))
    }

    @Test fun registrableDomainHandlesCoUkStyleSuffixes() {
        assertEquals("example.co.uk", AdapterUrls.registrableDomain("a.b.example.co.uk"))
        assertEquals("example.com", AdapterUrls.registrableDomain("a.example.com"))
        assertNull(AdapterUrls.sameSite("https://other.co.uk/", "https://example.co.uk/"))
    }
}

class InertHtmlTest {
    private val html = """
        <html><head><base href="https://evil.example/"><meta http-equiv="refresh" content="0;url=https://evil.example">
        <script>document.cookie</script><link rel="stylesheet" href="/a.css"></head>
        <body onload="steal()"><a id="n" href="/next" onclick="x()">n</a><a id="j" href="JaVaScRiPt:alert(1)">j</a>
        <iframe src="https://evil.example"></iframe><img src="/i.png" onerror="x()"><p>Real text</p></body></html>
    """.trimIndent()

    @Test fun removesEverythingExecutableAndAbsolutisesUrls() {
        val out = InertHtml.neutralize(html, "https://site.example/book/ch-2")
        val lower = out.lowercase()
        assertFalse("<script" in lower)
        assertFalse("<base" in lower)
        assertFalse("<iframe" in lower)
        assertFalse("http-equiv" in lower)
        assertFalse("onclick" in lower || "onload" in lower || "onerror" in lower)
        assertFalse("javascript:" in lower)
        assertFalse("<link" in lower)
        assertTrue("Real text" in out)
    }

    @Test fun relativeUrlsAreResolvedInKotlinAgainstThePageUrl() {
        val out = InertHtml.neutralize("""<a href="../next">n</a><img src="//cdn.site.example/i.png">""", "https://site.example/book/ch-2")
        assertTrue("href=\"https://site.example/next\"" in out)
        assertTrue("src=\"https://cdn.site.example/i.png\"" in out)
    }
}

class JsAdapterOutputTest {
    private val sandbox = object : JsSandbox {
        override suspend fun run(code: String, html: String, pageUrl: String) = error("unused")
    }
    private val runner = JsAdapterRunner(sandbox)
    private val page = "https://site.example/book/ch-2"

    @Test fun relativeLinksResolveAndCrossSiteLinksAreDropped() {
        val raw = """{"title":"T","paragraphs":["real text"],"nextUrl":"ch-3","prevUrl":"https://evil.example/?x=1"}"""
        val ok = runner.validate(raw, "t", page) as ExtractionResult.Success
        assertEquals("https://site.example/book/ch-3", ok.chapter.nextUrl)
        assertNull(ok.chapter.prevUrl)
    }

    @Test fun oversizedOutputIsRejectedBeforeParsing() {
        val raw = """{"paragraphs":["${"a".repeat(JsAdapterRunner.MAX_RESULT_CHARS)}"]}"""
        val r = runner.validate(raw, "t", page)
        assertTrue(r is ExtractionResult.Failure && r.reason.contains("too large"))
    }

    @Test fun wrapperCapsResultSizeInsidethePage() {
        val js = WebViewJsSandbox.wrap("function extract(d){return {};}")
        assertTrue(js.contains("__s.length > ${WebViewJsSandbox.MAX_RESULT_CHARS}"))
    }

    @Test fun runnerParsesOffTheCallerThread() = runBlocking {
        val s = object : JsSandbox {
            override suspend fun run(code: String, html: String, pageUrl: String) =
                """{"title":"T","paragraphs":["real text"],"nextUrl":null}"""
        }
        val r = JsAdapterRunner(s).run("x", FetchedPage(page, "<html/>", FetchMode.HTTP), "t")
        assertTrue(r is ExtractionResult.Success)
    }
}

class JsonAdapterSafetyTest {
    private fun spec(content: String = "#c", next: String? = null, dropLines: List<String> = emptyList()) =
        JsonAdapterSpec(ChapterSpec(content = content, next = next), cleanup = CleanupSpec(dropLines))

    @Test fun regexSelectorsAreRejected() {
        for (sel in listOf("p:matches(a+)", "p:matchesOwn(x)", "div:matchesWholeText(.*)", "a[href~=^/x]", "p:MATCHES(x)")) {
            assertFalse("accepted $sel", spec(content = sel).validate().isEmpty())
        }
        assertTrue(spec(content = "div.content > p:nth-child(2)").validate().isEmpty())
    }

    @Test fun catastrophicPatternRunsInLinearTime() {
        val text = "a".repeat(50_000) + "b"
        val start = System.currentTimeMillis()
        val out = JsonAdapterRunner.dropLines(listOf(text), listOf("(a+)+$"))
        assertTrue(System.currentTimeMillis() - start < 2_000)
        assertEquals(listOf(text), out)
    }

    @Test fun re2OnlySyntaxIsEnforcedAtValidation() {
        assertFalse(spec(dropLines = listOf("(?=x)y")).validate().isEmpty())     // lookahead
        assertFalse(spec(dropLines = listOf("(a)\\1")).validate().isEmpty())     // backreference
        assertTrue(spec(dropLines = listOf("^Translated by .*")).validate().isEmpty())
    }

    @Test fun crossSiteChapterAndTocLinksAreDropped() {
        val html = """<div id="c"><p>${"text ".repeat(60)}</p></div><a class="n" href="https://evil.example/x">n</a>
            <ul class="toc"><li><a href="/c/1">1</a></li><li><a href="https://evil.example/2">2</a></li></ul>"""
        val page = FetchedPage("https://site.example/book/ch-2", html, FetchMode.HTTP)
        val r = JsonAdapterRunner.run(spec(next = "a.n"), page, "t") as ExtractionResult.Success
        assertNull(r.chapter.nextUrl)
        val (_, toc) = JsonAdapterRunner.runNovelPage(
            JsonAdapterSpec(ChapterSpec(content = "body"), toc = TocSpec(items = ".toc a")), page,
        )
        assertEquals(listOf("https://site.example/c/1"), toc.refs.map { it.url })
    }
}
