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

sealed interface ExtractionResult {
    /** [novelInfo] is whatever the extractor could learn about the novel from the same page. */
    data class Success(
        val chapter: ExtractedChapter,
        val novelInfo: ExtractedNovelInfo? = null,
        /** Name of the extractor that produced this result (generic, adapter domain, ...). */
        val extractor: String = "unknown",
    ) : ExtractionResult

    data class Failure(val reason: String) : ExtractionResult
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
        for (extractor in extractors) {
            val result = runCatching { extractor.extract(page) }
                .getOrElse { ExtractionResult.Failure(it.message ?: it::class.java.simpleName) }
            if (result is ExtractionResult.Success) return result
            last = result
        }
        return last
    }
}
