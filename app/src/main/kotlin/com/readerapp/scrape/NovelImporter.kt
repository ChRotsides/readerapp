package com.readerapp.scrape

import com.readerapp.data.db.Novel
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import javax.inject.Inject
import javax.inject.Singleton

/** A page the user wants to import plus what extraction found on it (shown for confirmation). */
data class ImportDraft(
    val page: FetchedPage,
    val extraction: ExtractionResult.Success,
    val suggestedTitle: String,
)

sealed interface ImportResult {
    data class Imported(val novelId: Long, val chapterId: Long) : ImportResult
    data class Failed(val reason: String) : ImportResult
}

/** Implements the browser's "Add to library": chapter page -> novel + stored chapter (+ next/prev placeholders). */
@Singleton
class NovelImporter @Inject constructor(
    private val extractor: ChapterExtractor,
    private val novels: NovelRepository,
    private val chapters: ChapterRepository,
) {
    suspend fun prepare(page: FetchedPage): Result<ImportDraft> =
        when (val result = extractor.extract(page)) {
            is ExtractionResult.Failure -> Result.failure(IllegalStateException(result.reason))
            is ExtractionResult.Success -> {
                val domain = DomainRateLimiter.domainOf(page.url)
                val title = result.novelInfo?.title?.takeIf { it.isNotBlank() }
                    ?: result.chapter.title?.takeIf { it.isNotBlank() }
                    ?: domain
                Result.success(ImportDraft(page, result, title))
            }
        }

    suspend fun import(draft: ImportDraft, novelTitle: String): ImportResult {
        val page = draft.page
        val chapterData = draft.extraction.chapter
        val title = novelTitle.trim().ifEmpty { draft.suggestedTitle }

        chapters.findAnyByUrl(page.url)?.let { existing ->
            if (existing.status != com.readerapp.data.db.ChapterStatus.FETCHED) {
                chapters.storeFetched(existing, chapterData.title, chapterData.paragraphs, chapterData.nextUrl, chapterData.prevUrl)
            }
            return ImportResult.Imported(existing.novelId, existing.id)
        }

        val domain = DomainRateLimiter.domainOf(page.url)
        val info = draft.extraction.novelInfo
        val existingNovel = novels.findByDomainAndTitle(domain, title)
        val novelId = existingNovel?.id ?: novels.create(
            Novel(
                title = title,
                author = info?.author,
                coverUrl = info?.coverUrl,
                synopsis = info?.synopsis,
                sourceUrl = page.url,
                domain = domain,
            ),
        )

        val index = if (existingNovel == null) 0 else indexInExisting(novelId, chapterData)
        val chapter = chapters.insertIfAbsent(novelId, page.url, index, chapterData.title.orEmpty())
        chapters.storeFetched(chapter, chapterData.title, chapterData.paragraphs, chapterData.nextUrl, chapterData.prevUrl)
        return ImportResult.Imported(novelId, chapter.id)
    }

    private suspend fun indexInExisting(novelId: Long, data: ExtractedChapter): Int {
        data.prevUrl?.let { url -> chapters.findByUrl(novelId, url)?.let { return it.index + 1 } }
        data.nextUrl?.let { url -> chapters.findByUrl(novelId, url)?.let { return it.index - 1 } }
        val last = chapters.last(novelId)
        return (last?.index ?: -1) + 1
    }
}
