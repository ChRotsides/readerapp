package com.readerapp.scrape

import com.readerapp.data.db.Chapter
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.repo.ChapterRepository
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
        if (httpAttempt.accepted != null) return store(chapter, httpAttempt.accepted)
        failure = httpAttempt.reason ?: failure
        lockedHint = httpAttempt.lockedHint

        // 2. WebView fallback (JS-rendered / logged-in / bot-check cookies)
        val webAttempt = attempt(chapter) { webView.fetch(chapter.url) }
        if (webAttempt.accepted != null) return store(chapter, webAttempt.accepted)
        // A short but non-locked WebView result is the best available; accept it.
        if (webAttempt.shortSuccess != null && !webAttempt.lockedHint) return store(chapter, webAttempt.shortSuccess)
        failure = webAttempt.reason ?: failure
        lockedHint = lockedHint || webAttempt.lockedHint

        return if (lockedHint) {
            chapters.setStatus(chapterId, ChapterStatus.LOCKED)
            FetchOutcome.Locked("This chapter appears to require login or payment")
        } else {
            chapters.setStatus(chapterId, ChapterStatus.FAILED)
            FetchOutcome.Failed(failure)
        }
    }

    private suspend fun store(chapter: Chapter, success: ExtractionResult.Success): FetchOutcome {
        val c = success.chapter
        chapters.storeFetched(chapter, c.title, c.paragraphs, c.nextUrl, c.prevUrl)
        return FetchOutcome.Success(checkNotNull(chapters.get(chapter.id)))
    }

    private class Attempt(
        val accepted: ExtractionResult.Success? = null,
        val shortSuccess: ExtractionResult.Success? = null,
        val reason: String? = null,
        val lockedHint: Boolean = false,
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
            is ExtractionResult.Success -> {
                if (isSane(result.chapter) && !(locked && textLength(result.chapter) < LOCKED_MAX_CHARS)) {
                    Attempt(accepted = result)
                } else {
                    Attempt(
                        shortSuccess = result,
                        reason = "Extracted text too short (${textLength(result.chapter)} chars)",
                        lockedHint = locked,
                    )
                }
            }
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
