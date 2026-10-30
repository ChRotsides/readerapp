package com.readerapp.scrape.adapter

import com.readerapp.scrape.ChapterRef
import com.readerapp.scrape.ExtractedChapter
import com.readerapp.scrape.ExtractedNovelInfo
import com.readerapp.scrape.ExtractionResult
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.TextCleaner
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.google.re2j.Pattern

data class TocPage(val refs: List<ChapterRef>, val nextPageUrl: String?)

/** Executes a [JsonAdapterSpec] with Jsoup. Output paragraphs go through [TextCleaner]. */
object JsonAdapterRunner {

    fun run(spec: JsonAdapterSpec, page: FetchedPage, extractorName: String): ExtractionResult {
        val doc = Jsoup.parse(page.html, page.url)
        val container = runCatching { doc.selectFirst(spec.chapter.content) }.getOrNull()
            ?: return ExtractionResult.Failure("chapter.content selector '${spec.chapter.content}' matched nothing")
        spec.chapter.remove.forEach { sel -> runCatching { container.select(sel).remove() } }

        val cleaner = TextCleaner(TextCleaner.DEFAULT_JUNK_PATTERNS)
        val html = spec.chapter.paragraphs?.takeIf { it.isNotBlank() }
            ?.let { sel -> container.select(sel).joinToString("") { "<div>${it.html()}</div>" } }
            ?: container.html()
        var paragraphs = cleaner.toParagraphs(html)
        paragraphs = dropLines(paragraphs, spec.cleanup.dropLines)

        val title = spec.chapter.title?.let { sel -> doc.selectFirst(sel)?.text()?.trim()?.takeIf { it.isNotEmpty() } }
        if (title != null && paragraphs.firstOrNull() == title) paragraphs = paragraphs.drop(1)
        if (paragraphs.isEmpty()) return ExtractionResult.Failure("Adapter extracted no paragraphs")

        return ExtractionResult.Success(
            chapter = ExtractedChapter(
                title = title,
                paragraphs = paragraphs,
                nextUrl = spec.chapter.next?.let { linkOf(doc, it) },
                prevUrl = spec.chapter.prev?.let { linkOf(doc, it) },
                novelUrl = spec.chapter.novelLink?.let { linkOf(doc, it) },
            ),
            novelInfo = spec.novel?.let { novelInfo(doc, it) },
            extractor = extractorName,
        )
    }

    /** Novel information plus table of contents of a novel/listing page. */
    fun runNovelPage(spec: JsonAdapterSpec, page: FetchedPage): Pair<ExtractedNovelInfo, TocPage> {
        val doc = Jsoup.parse(page.html, page.url)
        val info = spec.novel?.let { novelInfo(doc, it) } ?: ExtractedNovelInfo(null, null, null, null)
        val toc = spec.toc ?: return info to TocPage(emptyList(), null)
        val refs = runCatching { doc.select(toc.items) }.getOrDefault(org.jsoup.select.Elements())
            .mapNotNull { a ->
                val href = AdapterUrls.sameSite(a.absUrl("href"), page.url) ?: return@mapNotNull null
                ChapterRef(a.text().trim().takeIf { it.isNotEmpty() }, href)
            }
            .distinctBy { it.url }
        val next = toc.nextPage?.let { linkOf(doc, it) }
        return info.copy(chapterRefs = refs) to TocPage(refs, next)
    }

    private fun novelInfo(doc: Document, n: NovelSpec): ExtractedNovelInfo = ExtractedNovelInfo(
        title = n.title?.let { text(doc, it) },
        author = n.author?.let { text(doc, it) },
        coverUrl = n.cover?.let { sel ->
            runCatching { doc.selectFirst(sel) }.getOrNull()?.let { el ->
                listOf("src", "data-src", "content").firstNotNullOfOrNull { attr ->
                    el.absUrl(attr).takeIf { it.isNotBlank() }
                }
            }
        },
        synopsis = n.synopsis?.let { text(doc, it) },
    )

    private fun text(doc: Document, selector: String): String? =
        runCatching { doc.selectFirst(selector) }.getOrNull()?.let { el ->
            (el.attr("content").takeIf { it.isNotBlank() && el.tagName() == "meta" } ?: el.text()).trim()
        }?.takeIf { it.isNotEmpty() }

    private fun linkOf(doc: Document, selector: String): String? {
        val el: Element = runCatching { doc.selectFirst(selector) }.getOrNull() ?: return null
        val a = if (el.tagName() == "a") el else el.selectFirst("a[href]") ?: el.closest("a[href]") ?: return null
        val url = a.absUrl("href")
        return AdapterUrls.sameSite(url, doc.location())
    }

    /**
     * Applies LLM-written regexes with RE2/J, whose matching time is linear in the input, so no pattern
     * (e.g. `(a+)+$`) can hang the app. Patterns RE2 cannot express (lookaround, backreferences) are ignored here;
     * [JsonAdapterSpec.validate] rejects them up front.
     */
    internal fun dropLines(paragraphs: List<String>, patterns: List<String>): List<String> {
        if (patterns.isEmpty()) return paragraphs
        val regexes = patterns.mapNotNull { runCatching { Pattern.compile(it) }.getOrNull() }
        return paragraphs.filter { p -> regexes.none { r -> r.matcher(p).find() } }
    }
}
