package com.readerapp.scrape

enum class FetchMode { HTTP, WEBVIEW }

/** Raw page as obtained by one of the fetchers. */
data class FetchedPage(val url: String, val html: String, val fetchMode: FetchMode)

data class ChapterRef(val title: String?, val url: String)

data class ExtractedChapter(
    val title: String?,
    val paragraphs: List<String>,
    val nextUrl: String?,
    val prevUrl: String?,
    /** Link to the novel's main page (breadcrumb / "table of contents" link), when the extractor found one. */
    val novelUrl: String? = null,
)

data class ExtractedNovelInfo(
    val title: String?,
    val author: String?,
    val coverUrl: String?,
    val synopsis: String?,
    /** Table-of-contents entries when the page/adapter can provide them; empty for the generic extractor. */
    val chapterRefs: List<ChapterRef> = emptyList(),
)

/**
 * An adapter version produced nothing usable on a page that was not a login/bot/locked page. Accounting is left to
 * whoever owns the whole fetch strategy (see `ChapterJudge`), because a later attempt (e.g. WebView after an HTTP
 * JS shell) may still succeed with the same adapter.
 */
data class AdapterFault(val domain: String, val version: Int)

sealed interface ExtractionResult {
    /** [novelInfo] is whatever the extractor could learn about the novel from the same page. */
    data class Success(
        val chapter: ExtractedChapter,
        val novelInfo: ExtractedNovelInfo? = null,
        /** Name of the extractor that produced this result (generic, adapter domain, ...). */
        val extractor: String = "unknown",
        /** An adapter that failed on this page before this (generic) result was produced. */
        val adapterFault: AdapterFault? = null,
    ) : ExtractionResult

    data class Failure(val reason: String, val adapterFault: AdapterFault? = null) : ExtractionResult
}

/**
 * Extension point for Milestone 2: site adapters implement this and are placed ahead of
 * [GenericExtractor] in an [ExtractorChain] (see `di/AppModule.kt`).
 */
interface ChapterExtractor {
    suspend fun extract(page: FetchedPage): ExtractionResult
}

/** Tries each extractor in order and returns the first success (or the last failure). */
class ExtractorChain(private val extractors: List<ChapterExtractor>) : ChapterExtractor {
    override suspend fun extract(page: FetchedPage): ExtractionResult {
        var last: ExtractionResult = ExtractionResult.Failure("No extractors configured")
        var fault: AdapterFault? = null
        for (extractor in extractors) {
            val result = runCatching { extractor.extract(page) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else ExtractionResult.Failure(it.message ?: it::class.java.simpleName) }
            if (result is ExtractionResult.Success) return if (fault != null && result.adapterFault == null) result.copy(adapterFault = fault) else result
            fault = fault ?: (result as ExtractionResult.Failure).adapterFault
            last = result
        }
        val l = last
        return if (l is ExtractionResult.Failure && fault != null && l.adapterFault == null) l.copy(adapterFault = fault) else l
    }
}
