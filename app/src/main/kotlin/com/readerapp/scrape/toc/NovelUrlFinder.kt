package com.readerapp.scrape.toc

import com.readerapp.scrape.DomainRateLimiter
import org.jsoup.Jsoup
import java.net.URI

/** Finds the novel's main page (table of contents / info page) from one of its chapter pages. */
object NovelUrlFinder {
    private val NAV_TEXT = Regex(
        "(?i)^\\s*(table of contents|contents|chapter list|chapters|all chapters|novel info|novel page|index|" +
            "back to (the )?(novel|book|series|story|list)|目录|目錄|返回目录|章节目录|章節目錄|返回)\\s*$",
    )
    /** A path segment that is clearly a chapter ("chapter-12", "ch_3", "ep5"). */
    private val CHAPTER_KEYWORD = Regex("(?i)^(chapter|chap|ch|episode|ep|c)[-_]?\\d+.*|.*chapter.*")
    /** Segments that may be a chapter when they are the last one of a chapter URL: keywords or a bare number ("456.html"). */
    private val CHAPTER_SEGMENT = Regex("(?i)^\\d+(\\.html?)?$|${CHAPTER_KEYWORD.pattern}")

    fun find(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val pageKey = UrlKey.of(pageUrl)
        val domain = DomainRateLimiter.domainOf(pageUrl)

        fun accept(url: String): String? {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            if (DomainRateLimiter.domainOf(url) != domain) return null
            if (UrlKey.of(url) == pageKey) return null
            val path = runCatching { URI(url).path }.getOrNull().orEmpty().trim('/')
            if (path.isEmpty()) return null
            if (CHAPTER_KEYWORD.matches(path.substringAfterLast('/'))) return null
            return url.substringBefore('#')
        }

        val breadcrumbs = doc.select(
            "[itemtype*=BreadcrumbList] a[href], .breadcrumb a[href], .breadcrumbs a[href], " +
                "nav[aria-label~=(?i)breadcrumb] a[href], ol.breadcrumb a[href], ul.breadcrumb a[href], [class*=breadcrumb] a[href]",
        ).mapNotNull { accept(it.absUrl("href")) }
        breadcrumbs.lastOrNull()?.let { return it }

        doc.select("a[rel~=(up|index|contents)][href], link[rel=up][href]").firstNotNullOfOrNull { accept(it.absUrl("href")) }
            ?.let { return it }

        val anchors = doc.select("a[href]")
        anchors.firstOrNull { NAV_TEXT.matches(it.text()) && accept(it.absUrl("href")) != null }
            ?.let { return accept(it.absUrl("href")) }

        // Parent path of a chapter-looking URL, preferably one that the page itself links to.
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || !CHAPTER_SEGMENT.matches(segments.last())) return null
        val parent = "${uri.scheme}://${uri.host}/" + segments.dropLast(1).joinToString("/")
        val linked = anchors.firstOrNull { UrlKey.of(it.absUrl("href")) == UrlKey.of(parent) }
        return (linked?.absUrl("href") ?: parent).let { accept(it) }
    }
}
