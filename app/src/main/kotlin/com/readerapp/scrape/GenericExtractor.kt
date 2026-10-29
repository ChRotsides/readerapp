package com.readerapp.scrape

import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Site-agnostic extraction: Readability4J for the chapter body, heuristics for the title,
 * next/prev links and novel metadata. Always the last fallback of the extractor chain.
 */
@Singleton
class GenericExtractor @Inject constructor() : ChapterExtractor {
    private val cleaner = TextCleaner()

    override suspend fun extract(page: FetchedPage): ExtractionResult = withContext(Dispatchers.Default) {
        extractBlocking(page)
    }

    internal fun extractBlocking(page: FetchedPage): ExtractionResult {
        val doc = Jsoup.parse(page.html, page.url)
        val links = ChapterLinkDetector.detect(doc, page.url)

        val articleHtml = readabilityHtml(page) ?: fallbackContentHtml(doc)
            ?: return ExtractionResult.Failure("No readable content found")
        val title = chapterTitle(doc, page)
        var paragraphs = cleaner.toParagraphs(articleHtml)
        if (paragraphs.firstOrNull() == title) paragraphs = paragraphs.drop(1)
        if (paragraphs.isEmpty()) return ExtractionResult.Failure("Extracted content had no paragraphs")

        return ExtractionResult.Success(
            chapter = ExtractedChapter(title, paragraphs, links.next, links.prev),
            novelInfo = novelInfo(doc, title, page.url),
            extractor = NAME,
        )
    }

    private fun readabilityHtml(page: FetchedPage): String? = runCatching {
        Readability4J(page.url, page.html).parse().content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** When Readability gives up: the container holding the most direct paragraphs. */
    private fun fallbackContentHtml(doc: Document): String? =
        doc.select("article, main, section, div")
            .maxByOrNull { el -> el.children().count { it.tagName() == "p" } }
            ?.takeIf { el -> el.children().count { it.tagName() == "p" } >= 3 }
            ?.html()

    private fun chapterTitle(doc: Document, page: FetchedPage): String? {
        val headings = doc.select("h1, h2, h3").map { it.text().trim() }.filter { it.isNotEmpty() && it.length <= 150 }
        headings.firstOrNull { CHAPTER_TITLE.containsMatchIn(it) }?.let { return it }
        headings.firstOrNull()?.let { return it }
        return runCatching { Readability4J(page.url, page.html).parse().title }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }
    }

    internal fun novelInfo(doc: Document, chapterTitle: String?, pageUrl: String): ExtractedNovelInfo {
        fun meta(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
            doc.select("meta[property=$n], meta[name=$n]").firstOrNull()?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        }
        val explicitTitle = meta("og:novel:book_name", "og:book:title", "book:title", "og:novel:title")
        val cover = doc.select("meta[property=og:image]").firstOrNull()?.absUrl("content")?.takeIf { it.isNotBlank() }
        return ExtractedNovelInfo(
            title = explicitTitle ?: guessNovelTitle(doc.title(), chapterTitle, meta("og:site_name")),
            author = meta("og:novel:author", "og:book:author", "book:author", "author", "article:author"),
            coverUrl = cover,
            synopsis = meta("og:description", "description"),
        )
    }

    companion object {
        const val NAME = "generic"

        private val CHAPTER_TITLE = Regex(
            "(?i)(\\b(chapter|chap\\.?|ch\\.?|episode|ep\\.?)\\s*\\d)|第\\s*[0-9一二三四五六七八九十百千零]+\\s*[章话話回节節]|\\d+\\s*화",
        )
        private val TITLE_SEPARATORS = Regex("\\s+[-|–—_·»:]\\s+|\\s*[|_]\\s*")

        /** Picks the `<title>` segment that is neither the chapter title nor the site name nor "chapter N". */
        fun guessNovelTitle(pageTitle: String, chapterTitle: String?, siteName: String?): String? {
            val segments = pageTitle.split(TITLE_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
            return segments.firstOrNull { seg ->
                !seg.equals(chapterTitle, ignoreCase = true) &&
                    !seg.equals(siteName, ignoreCase = true) &&
                    !CHAPTER_TITLE.containsMatchIn(seg) &&
                    !(chapterTitle != null && chapterTitle.contains(seg, ignoreCase = true))
            }
        }
    }
}
