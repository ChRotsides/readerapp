package com.readerapp.scrape

import com.readerapp.data.db.Chapter
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.repo.AdapterStore
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.scrape.adapter.AdapterTag
import com.readerapp.scrape.heal.AdapterHealth
import com.readerapp.scrape.heal.ChapterAssessor
import com.readerapp.scrape.heal.Decision
import com.readerapp.scrape.heal.RuleChecks
import com.readerapp.scrape.heal.VerdictPolicy
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
    private val generic: GenericExtractor,
    private val assessor: ChapterAssessor,
    private val adapters: AdapterStore,
    private val health: AdapterHealth,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun ensureFetched(chapterId: Long): FetchOutcome =
        locks.getOrPut(chapterId) { Mutex() }.withLock { fetchLocked(chapterId) }

    private suspend fun fetchLocked(chapterId: Long): FetchOutcome {
        val chapter = chapters.get(chapterId) ?: return FetchOutcome.Failed("Chapter no longer exists")
        if (chapter.status == ChapterStatus.FETCHED && chapters.getText(chapterId) != null) {
            return FetchOutcome.Success(chapter)
        }

        var failure = "Unknown error"
        var lockedHint = false

        // 1. HTTP
        val httpAttempt = attempt(chapter) { http.fetch(chapter.url) }
        if (httpAttempt.accepted != null) return store(chapter, httpAttempt.accepted, httpAttempt.flagged)
        failure = httpAttempt.reason ?: failure
        lockedHint = httpAttempt.lockedHint

        // 2. WebView fallback (JS-rendered / logged-in / bot-check cookies)
        val webAttempt = attempt(chapter) { webView.fetch(chapter.url) }
        if (webAttempt.accepted != null) return store(chapter, webAttempt.accepted, webAttempt.flagged)
        // A short but non-locked WebView result is the best available; accept it.
        if (webAttempt.shortSuccess != null && !webAttempt.lockedHint) return store(chapter, webAttempt.shortSuccess, flagged = false)
        failure = webAttempt.reason ?: failure
        lockedHint = lockedHint || webAttempt.lockedHint
        val browserReason = httpAttempt.needsBrowser ?: webAttempt.needsBrowser
        val retryLater = httpAttempt.retryLater || webAttempt.retryLater

        return if (lockedHint) {
            chapters.setStatus(chapterId, ChapterStatus.LOCKED)
            FetchOutcome.Locked("This chapter appears to require login or payment")
        } else if (browserReason != null) {
            // Page problem: the adapter is never blamed. Status stays NOT_FETCHED so it is retried after the user acts.
            health.onBrowserActionNeeded(chapter.url, browserReason)
            FetchOutcome.NeedsBrowser(browserReason)
        } else if (retryLater) {
            FetchOutcome.Failed("The site returned an error page. Try again later.")
        } else {
            chapters.setStatus(chapterId, ChapterStatus.FAILED)
            FetchOutcome.Failed(failure)
        }
    }

    private suspend fun store(chapter: Chapter, success: ExtractionResult.Success, flagged: Boolean): FetchOutcome {
        val c = success.chapter
        chapters.storeFetched(chapter, c.title, c.paragraphs, c.nextUrl, c.prevUrl)
        if (flagged) chapters.setFlagged(chapter.id, true)
        return FetchOutcome.Success(checkNotNull(chapters.get(chapter.id)))
    }

    private class Attempt(
        val accepted: ExtractionResult.Success? = null,
        val shortSuccess: ExtractionResult.Success? = null,
        val reason: String? = null,
        val lockedHint: Boolean = false,
        /** Saved although the verifier was unsure; the chapter is flagged as possibly incomplete. */
        val flagged: Boolean = false,
        val needsBrowser: String? = null,
        val retryLater: Boolean = false,
    )

    private suspend fun attempt(chapter: Chapter, fetch: suspend () -> FetchedPage): Attempt {
        val page = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Attempt(reason = e.message ?: "Fetch failed")
        }
        val locked = looksLocked(page.html)
        return when (val result = extractor.extract(page)) {
            is ExtractionResult.Failure -> Attempt(reason = result.reason, lockedHint = locked)
            is ExtractionResult.Success -> evaluate(chapter, page, result, locked)
        }
    }

    /**
     * Break detection for an extraction. Adapter output goes through rules + typed verifier and feeds adapter health;
     * page problems (locked / login / bot check / error page) never touch the adapter. Generic output only gets the
     * cheap rule checks plus the original sanity logic (no model call per chapter without an adapter).
     */
    private suspend fun evaluate(chapter: Chapter, page: FetchedPage, result: ExtractionResult.Success, locked: Boolean): Attempt {
        val adapterRef = AdapterTag.parse(result.extractor)
        if (adapterRef == null) return evaluateGeneric(chapter, page, result, locked)

        val previous = chapters.previous(chapter)?.let { chapters.getText(it.id)?.paragraphs }
        val assessment = assessor.assess(page, result.chapter, previous)
        val adapter = adapters.get(adapterRef.first, adapterRef.second)
        return when (assessment.decision) {
            Decision.ACCEPT -> {
                if (assessment.verified && adapter != null) health.onSuccess(adapter)
                Attempt(accepted = result)
            }
            Decision.AMBIGUOUS -> Attempt(accepted = result, flagged = true)
            Decision.ADAPTER_FAILURE -> {
                if (adapter != null) health.onAdapterFailure(adapter, page)
                // Still give the reader something: the generic extractor on the same page.
                when (val fallback = generic.extract(page)) {
                    is ExtractionResult.Success -> evaluateGeneric(chapter, page, fallback, locked)
                    is ExtractionResult.Failure -> Attempt(reason = assessment.verdict.describe(), lockedHint = locked)
                }
            }
            Decision.LOCKED -> Attempt(reason = "Chapter is locked", lockedHint = true)
            Decision.NEEDS_LOGIN -> Attempt(reason = "Login required", needsBrowser = "Open this page in the browser and log in")
            Decision.NEEDS_BOT_CHECK -> Attempt(reason = "Bot check", needsBrowser = "Open this page in the browser and pass the check")
            Decision.RETRY_LATER -> Attempt(reason = "Error page", retryLater = true)
        }
    }

    private fun evaluateGeneric(chapter: Chapter, page: FetchedPage, result: ExtractionResult.Success, locked: Boolean): Attempt {
        RuleChecks.check(page.html, result.chapter, null)?.let { rule ->
            if (rule.verdict.isPageProblem && rule.probability >= VerdictPolicy.PROBLEM_THRESHOLD) {
                when (VerdictPolicy.decide(rule)) {
                    Decision.LOCKED -> return Attempt(reason = "Chapter is locked", lockedHint = true)
                    Decision.NEEDS_LOGIN -> return Attempt(reason = "Login required", needsBrowser = "Open this page in the browser and log in")
                    Decision.NEEDS_BOT_CHECK -> return Attempt(reason = "Bot check", needsBrowser = "Open this page in the browser and pass the check")
                    else -> Unit
                }
            }
        }
        return if (isSane(result.chapter) && !(locked && textLength(result.chapter) < LOCKED_MAX_CHARS)) {
            Attempt(accepted = result)
        } else {
            Attempt(
                shortSuccess = result,
                reason = "Extracted text too short (${textLength(result.chapter)} chars)",
                lockedHint = locked,
            )
        }
    }

    companion object {
        const val MIN_CHARS = 200
        private const val LOCKED_MAX_CHARS = 1_000

        private val LOCK_HINTS = Regex(
            "(?i)(locked chapter|unlock (this )?chapter|unlock with|subscribe to (read|unlock|continue)|" +
                "please (log ?in|sign ?in) to (read|continue|view)|become a (vip|member) to|需要登录|请登录后阅读|VIP章节|付费章节|订阅后阅读)",
        )

        fun textLength(chapter: ExtractedChapter): Int = chapter.paragraphs.sumOf { it.length }

        fun isSane(chapter: ExtractedChapter): Boolean = textLength(chapter) >= MIN_CHARS

        fun looksLocked(html: String): Boolean = LOCK_HINTS.containsMatchIn(html)
    }
}
