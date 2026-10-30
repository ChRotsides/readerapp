package com.readerapp.scrape

import com.readerapp.data.db.Chapter
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.scrape.heal.AdapterHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FetchOutcome {
    data class Success(val chapter: Chapter) : FetchOutcome
    /** The site requires login/payment for this chapter. It is marked LOCKED and never bypassed. */
    data class Locked(val reason: String) : FetchOutcome
    data class Failed(val reason: String) : FetchOutcome
    /** Login wall or bot check: the user must open the page in the in-app browser (log in / pass the check). */
    data class NeedsBrowser(val reason: String) : FetchOutcome
}

/** Result of "refetch & recheck" on a chapter that was stored flagged as possibly incomplete. */
sealed interface RecheckOutcome {
    /** Fetched again and confirmed good: the text was replaced and the flag cleared. */
    data object Cleared : RecheckOutcome
    /** Fetched again but the verifier is still unsure: nothing changed, the chapter stays flagged. */
    data object StillUncertain : RecheckOutcome
    data class Failed(val reason: String) : RecheckOutcome
    data class NeedsBrowser(val reason: String) : RecheckOutcome
}

/**
 * Fetches and stores a single chapter: HTTP first, WebView fallback if the HTTP result fails basic sanity.
 * Safe to call concurrently (reader + prefetch); each chapter is fetched at most once at a time.
 */
@Singleton
class ChapterFetcher @Inject constructor(
    private val http: HttpFetcher,
    private val webView: WebViewFetcher,
    private val extractor: ChapterExtractor,
    private val chapters: ChapterRepository,
    private val judge: ChapterJudge,
    private val health: AdapterHealth,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun ensureFetched(chapterId: Long): FetchOutcome =
        locks.getOrPut(chapterId) { Mutex() }.withLock { fetchLocked(chapterId) }

    /**
     * Downloads a chapter again although it is cached and flagged, runs the same break detection, and only when the
     * result is confirmed good replaces the text and clears the flag. Any other outcome leaves the stored chapter as is.
     */
    suspend fun refetchAndRecheck(chapterId: Long): RecheckOutcome =
        locks.getOrPut(chapterId) { Mutex() }.withLock {
            val chapter = chapters.get(chapterId) ?: return@withLock RecheckOutcome.Failed("Chapter no longer exists")
            val run = runStrategy(chapter)
            val accepted = run.chosen?.accepted
            when {
                accepted != null && run.chosen?.flagged == false -> {
                    judge.account(run.chosen, run.attempts)
                    store(chapter, accepted, flagged = false)
                    RecheckOutcome.Cleared
                }
                accepted != null -> RecheckOutcome.StillUncertain
                run.browserReason != null -> RecheckOutcome.NeedsBrowser(run.browserReason)
                else -> RecheckOutcome.Failed(run.failure)
            }
        }

    /**
     * "Check for next": re-reads the page of a chapter that had no successor when it was cached (the author may have
     * published since) and registers the successor link it now shows. The stored text is not touched.
     * Returns the successor, or null when the site still shows none.
     */
    suspend fun refreshNextLink(chapterId: Long): Chapter? =
        locks.getOrPut(chapterId) { Mutex() }.withLock {
            val chapter = chapters.get(chapterId) ?: return@withLock null
            val sources = listOf<suspend () -> FetchedPage>({ http.fetch(chapter.url) }, { webView.fetch(chapter.url) })
            for (source in sources) {
                val page = try {
                    source()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                val next = (extractor.extract(page) as? ExtractionResult.Success)?.chapter?.nextUrl ?: continue
                chapters.registerNext(chapter, next)
                break
            }
            chapters.next(chapter)
        }

    private suspend fun fetchLocked(chapterId: Long): FetchOutcome {
        val chapter = chapters.get(chapterId) ?: return FetchOutcome.Failed("Chapter no longer exists")
        if (chapter.status == ChapterStatus.FETCHED && chapters.getText(chapterId) != null) {
            return FetchOutcome.Success(chapter)
        }

        val run = runStrategy(chapter)
        // Health is accounted once for the whole strategy, not per attempt.
        judge.account(run.chosen, run.attempts)
        run.chosen?.accepted?.let { return store(chapter, it, run.chosen.flagged) }

        return if (run.lockedHint) {
            chapters.setStatus(chapterId, ChapterStatus.LOCKED)
            FetchOutcome.Locked("This chapter appears to require login or payment")
        } else if (run.browserReason != null) {
            // Page problem: the adapter is never blamed. Status stays NOT_FETCHED so it is retried after the user acts.
            health.onBrowserActionNeeded(chapter.url, run.browserReason)
            FetchOutcome.NeedsBrowser(run.browserReason)
        } else if (run.retryLater) {
            FetchOutcome.Failed("The site returned an error page. Try again later.")
        } else {
            chapters.setStatus(chapterId, ChapterStatus.FAILED)
            FetchOutcome.Failed(run.failure)
        }
    }

    private suspend fun store(chapter: Chapter, success: ExtractionResult.Success, flagged: Boolean): FetchOutcome {
        val c = success.chapter
        chapters.storeFetched(chapter, c.title, c.paragraphs, c.nextUrl, c.prevUrl, flagged)
        return FetchOutcome.Success(checkNotNull(chapters.get(chapter.id)))
    }

    private class Run(
        /** The judgement whose `accepted` result should be stored, if any attempt produced one. */
        val chosen: Judgement?,
        val attempts: List<Judgement>,
        val failure: String,
        val lockedHint: Boolean,
        val browserReason: String?,
        val retryLater: Boolean,
    )

    /** HTTP first, WebView fallback (JS-rendered / logged-in / bot-check cookies). Stores nothing. */
    private suspend fun runStrategy(chapter: Chapter): Run {
        val previous = chapters.previous(chapter)?.let { chapters.getText(it.id)?.paragraphs }

        val httpAttempt = attempt(previous) { http.fetch(chapter.url) }
        if (httpAttempt.accepted != null) return Run(httpAttempt, listOf(httpAttempt), "", false, null, false)

        val webAttempt = attempt(previous) { webView.fetch(chapter.url) }
        val attempts = listOf(httpAttempt, webAttempt)
        if (webAttempt.accepted != null) return Run(webAttempt, attempts, "", false, null, false)
        // A short but non-locked WebView result is the best available; accept it.
        if (webAttempt.shortSuccess != null && !webAttempt.lockedHint) {
            return Run(webAttempt.copy(accepted = webAttempt.shortSuccess, flagged = false), attempts, "", false, null, false)
        }
        return Run(
            chosen = null,
            attempts = attempts,
            failure = webAttempt.reason ?: httpAttempt.reason ?: "Unknown error",
            lockedHint = httpAttempt.lockedHint || webAttempt.lockedHint,
            browserReason = httpAttempt.needsBrowser ?: webAttempt.needsBrowser,
            retryLater = httpAttempt.retryLater || webAttempt.retryLater,
        )
    }

    private suspend fun attempt(previous: List<String>?, fetch: suspend () -> FetchedPage): Judgement {
        val page = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FetchException) {
            return fromStatus(e)
        } catch (e: Exception) {
            return Judgement(reason = e.message ?: "Fetch failed")
        }
        return when (val result = extractor.extract(page)) {
            is ExtractionResult.Failure -> judge.judgeFailure(page, result)
            is ExtractionResult.Success -> judge.judge(page, result, previous)
        }
    }

    /** HTTP status of a rejected response says what kind of problem it is; none of them is an adapter defect. */
    private fun fromStatus(e: FetchException): Judgement = when (e.statusCode) {
        401, 403 -> Judgement(reason = e.message, needsBrowser = "Open this page in the browser, log in or pass the check")
        408, 425, 429, in 500..599 -> Judgement(reason = e.message, retryLater = true)
        else -> Judgement(reason = e.message ?: "Fetch failed")
    }

    companion object {
        const val MIN_CHARS = ChapterJudge.MIN_CHARS
    }
}
