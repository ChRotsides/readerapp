package com.readerapp.scrape

import com.readerapp.data.db.Novel
import com.readerapp.data.repo.AdapterStore
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.scrape.heal.RepairScheduler
import com.readerapp.scrape.toc.NovelUrlFinder
import com.readerapp.scrape.toc.TocScheduler
import javax.inject.Inject
import javax.inject.Singleton

/** A page the user wants to import plus what extraction found on it (shown for confirmation). */
data class ImportDraft(
    val page: FetchedPage,
    val extraction: ExtractionResult.Success,
    val suggestedTitle: String,
    /** The verifier was unsure about this extraction: the chapter is stored flagged as possibly incomplete. */
    val flagged: Boolean = false,
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
    private val adapters: AdapterStore,
    private val scheduler: RepairScheduler,
    private val toc: TocScheduler,
    private val judge: ChapterJudge,
) {
    /**
     * Extracts the browser's page and runs the same break detection as a normal fetch, so a login page, teaser,
     * error page or broken adapter result is rejected here instead of being stored as a FETCHED chapter.
     */
    suspend fun prepare(page: FetchedPage): Result<ImportDraft> {
        val result = extractor.extract(page)
        if (result is ExtractionResult.Failure) {
            judge.account(null, listOf(judge.judgeFailure(page, result)))
            return Result.failure(IllegalStateException(result.reason))
        }
        val success = result as ExtractionResult.Success
        val judgement = judge.judge(page, success, previous = null)
        val chosen = judgement.accepted
            ?: judgement.shortSuccess?.takeIf { !judgement.lockedHint }
        judge.account(if (chosen != null) judgement.copy(accepted = chosen) else null, listOf(judgement))
        if (chosen == null) {
            val why = when {
                judgement.lockedHint -> "This page looks locked (login or payment required)."
                judgement.needsBrowser != null -> "This page looks like a login or bot-check page, not a chapter."
                judgement.retryLater -> "This page looks like a site error page, not a chapter."
                else -> judgement.reason ?: "No chapter text could be extracted."
            }
            return Result.failure(IllegalStateException(why))
        }
        val domain = DomainRateLimiter.domainOf(page.url)
        val title = chosen.novelInfo?.title?.takeIf { it.isNotBlank() }
            ?: chosen.chapter.title?.takeIf { it.isNotBlank() }
            ?: domain
        return Result.success(ImportDraft(page, chosen, title, flagged = judgement.flagged && judgement.accepted != null))
    }

    suspend fun import(draft: ImportDraft, novelTitle: String): ImportResult {
        val page = draft.page
        val chapterData = draft.extraction.chapter
        val title = novelTitle.trim().ifEmpty { draft.suggestedTitle }
        // The novel's main page: the adapter's novelLink if it has one, else breadcrumb / "contents" link heuristics.
        val novelUrl = chapterData.novelUrl ?: runCatching { NovelUrlFinder.find(page.html, page.url) }.getOrNull()
        maybeScheduleAdapterGeneration(page.url, novelUrl)

        chapters.findAnyByUrl(page.url)?.let { existing ->
            if (existing.status != com.readerapp.data.db.ChapterStatus.FETCHED) {
                chapters.storeFetched(existing, chapterData.title, chapterData.paragraphs, chapterData.nextUrl, chapterData.prevUrl, draft.flagged)
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
                novelUrl = novelUrl,
            ),
        )
        if (existingNovel != null && existingNovel.novelUrl == null && novelUrl != null) novels.setNovelUrl(novelId, novelUrl)

        val index = if (existingNovel == null) 0 else indexInExisting(novelId, chapterData)
        val chapter = chapters.insertAt(novelId, page.url, index, chapterData.title.orEmpty())
        chapters.storeFetched(chapter, chapterData.title, chapterData.paragraphs, chapterData.nextUrl, chapterData.prevUrl, draft.flagged)
        // Runs now when the domain's active adapter has TOC selectors; otherwise again once an adapter is generated.
        if (novelUrl != null || existingNovel?.novelUrl != null) toc.schedule(novelId)
        return ImportResult.Imported(novelId, chapter.id)
    }

    /**
     * New domain (no adapter version yet, and generation has not already been given up on): a background job asks
     * Sonnet to write v1. Until it finishes the generic extractor is used.
     */
    private suspend fun maybeScheduleAdapterGeneration(pageUrl: String, novelUrl: String?) {
        val domain = DomainRateLimiter.domainOf(pageUrl)
        if (adapters.versions(domain).isNotEmpty()) return
        if (adapters.domainState(domain)?.genericFallback == true) return
        scheduler.scheduleGeneration(domain, pageUrl, novelUrl)
    }

    private suspend fun indexInExisting(novelId: Long, data: ExtractedChapter): Int {
        data.prevUrl?.let { url -> chapters.findByUrl(novelId, url)?.let { return it.index + 1 } }
        data.nextUrl?.let { url -> chapters.findByUrl(novelId, url)?.let { return it.index } }
        val last = chapters.last(novelId)
        return (last?.index ?: -1) + 1
    }
}
