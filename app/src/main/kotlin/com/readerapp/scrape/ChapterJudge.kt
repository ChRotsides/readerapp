package com.readerapp.scrape

import com.readerapp.data.repo.AdapterStore
import com.readerapp.scrape.adapter.AdapterTag
import com.readerapp.scrape.heal.AdapterHealth
import com.readerapp.scrape.heal.ChapterAssessor
import com.readerapp.scrape.heal.Decision
import com.readerapp.scrape.heal.RuleChecks
import com.readerapp.scrape.heal.VerdictPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** An adapter version failed on [page]; counted against it by [ChapterJudge.account]. */
data class PageFault(val domain: String, val version: Int, val page: FetchedPage)

/** Verdict on one fetched + extracted page. Exactly the fields the fetch strategy needs to decide what to do next. */
data class Judgement(
    val accepted: ExtractionResult.Success? = null,
    /** Extracted but too short to trust; the best available result when a fetch strategy has nothing better. */
    val shortSuccess: ExtractionResult.Success? = null,
    val reason: String? = null,
    val lockedHint: Boolean = false,
    /** Accepted although the verifier was unsure; the chapter is flagged as possibly incomplete. */
    val flagged: Boolean = false,
    val needsBrowser: String? = null,
    val retryLater: Boolean = false,
    /** A verifier confirmed [accepted]. False when only rules ran or no verifier was reachable. */
    val verified: Boolean = false,
    val faults: List<PageFault> = emptyList(),
) {
    val isPageProblem: Boolean get() = lockedHint || needsBrowser != null || retryLater
}

/**
 * Break detection for an extraction plus the adapter-health bookkeeping around it, shared by every path that stores
 * a chapter (reader fetch, prefetch, browser import, refetch & recheck).
 *
 * Adapter output goes through rules + typed verifier; page problems (locked / login / bot check / error page) never
 * touch the adapter. Generic output only gets the cheap rule checks plus the original sanity logic (no model call per
 * chapter without an adapter).
 */
@Singleton
class ChapterJudge @Inject constructor(
    private val assessor: ChapterAssessor,
    private val adapters: AdapterStore,
    private val health: AdapterHealth,
    private val generic: GenericExtractor,
) {
    suspend fun judge(page: FetchedPage, result: ExtractionResult.Success, previous: List<String>?): Judgement {
        val locked = looksLocked(page.html)
        val early = result.adapterFault?.let { PageFault(it.domain, it.version, page) }
        val j = if (AdapterTag.parse(result.extractor) == null) evaluateGeneric(page, result, locked) else evaluateAdapter(page, result, locked, previous)
        return if (early != null) j.copy(faults = listOf(early) + j.faults) else j
    }

    /** Judgement for a failed extraction (no usable result), carrying the adapter fault if there was one. */
    fun judgeFailure(page: FetchedPage, failure: ExtractionResult.Failure): Judgement =
        Judgement(
            reason = failure.reason,
            lockedHint = looksLocked(page.html),
            faults = listOfNotNull(failure.adapterFault?.let { PageFault(it.domain, it.version, page) }),
        )

    private suspend fun evaluateAdapter(page: FetchedPage, result: ExtractionResult.Success, locked: Boolean, previous: List<String>?): Judgement {
        val ref = checkNotNull(AdapterTag.parse(result.extractor))
        val assessment = assessor.assess(page, result.chapter, previous)
        return when (assessment.decision) {
            Decision.ACCEPT -> Judgement(accepted = result, verified = assessment.verified)
            Decision.AMBIGUOUS -> Judgement(accepted = result, flagged = true)
            Decision.ADAPTER_FAILURE -> {
                val fault = PageFault(ref.first, ref.second, page)
                // Still give the reader something: the generic extractor on the same page.
                when (val fallback = generic.extract(page)) {
                    is ExtractionResult.Success -> evaluateGeneric(page, fallback, locked).let { it.copy(faults = listOf(fault) + it.faults) }
                    is ExtractionResult.Failure -> Judgement(reason = assessment.verdict.describe(), lockedHint = locked, faults = listOf(fault))
                }
            }
            Decision.LOCKED -> Judgement(reason = "Chapter is locked", lockedHint = true)
            Decision.NEEDS_LOGIN -> Judgement(reason = "Login required", needsBrowser = LOGIN_HINT)
            Decision.NEEDS_BOT_CHECK -> Judgement(reason = "Bot check", needsBrowser = BOT_HINT)
            Decision.RETRY_LATER -> Judgement(reason = "Error page", retryLater = true)
        }
    }

    private fun evaluateGeneric(page: FetchedPage, result: ExtractionResult.Success, locked: Boolean): Judgement {
        RuleChecks.check(page.html, result.chapter, null)?.let { rule ->
            if (rule.verdict.isPageProblem && rule.probability >= VerdictPolicy.PROBLEM_THRESHOLD) {
                when (VerdictPolicy.decide(rule)) {
                    Decision.LOCKED -> return Judgement(reason = "Chapter is locked", lockedHint = true)
                    Decision.NEEDS_LOGIN -> return Judgement(reason = "Login required", needsBrowser = LOGIN_HINT)
                    Decision.NEEDS_BOT_CHECK -> return Judgement(reason = "Bot check", needsBrowser = BOT_HINT)
                    else -> Unit
                }
            }
        }
        return if (isSane(result.chapter) && !(locked && textLength(result.chapter) < LOCKED_MAX_CHARS)) {
            Judgement(accepted = result)
        } else {
            Judgement(
                shortSuccess = result,
                reason = "Extracted text too short (${textLength(result.chapter)} chars)",
                lockedHint = locked,
            )
        }
    }

    /**
     * Health bookkeeping once a whole fetch strategy is done, never per attempt: a working adapter must not be broken
     * by an HTTP JS shell that the WebView attempt then read fine.
     *  - [chosen] came from an adapter: verified -> success (also clears failures); accepted without any verifier
     *    reachable -> consecutive failures are reset (a good extraction is evidence of health); flagged -> untouched.
     *  - Faults of the other attempts count once per adapter version, unless that same version produced the accepted
     *    result, and not at all when an attempt hit a page problem (login wall, lock, error page).
     */
    suspend fun account(chosen: Judgement?, attempts: List<Judgement>) {
        val okRef = chosen?.accepted?.let { AdapterTag.parse(it.extractor) }
        if (okRef != null) {
            adapters.get(okRef.first, okRef.second)?.let { adapter ->
                if (chosen.verified) health.onSuccess(adapter) else if (!chosen.flagged) adapters.resetFailures(adapter)
            }
        }
        if (chosen == null && attempts.any { it.isPageProblem }) return
        if (chosen != null && chosen.isPageProblem) return
        for (fault in attempts.flatMap { it.faults }.distinctBy { it.domain to it.version }) {
            if (okRef != null && okRef.first == fault.domain && okRef.second == fault.version) continue
            adapters.get(fault.domain, fault.version)?.let { health.onAdapterFailure(it, fault.page) }
        }
    }

    companion object {
        const val MIN_CHARS = 200
        private const val LOCKED_MAX_CHARS = 1_000
        const val LOGIN_HINT = "Open this page in the browser and log in"
        const val BOT_HINT = "Open this page in the browser and pass the check"

        private val LOCK_HINTS = Regex(
            "(?i)(locked chapter|unlock (this )?chapter|unlock with|subscribe to (read|unlock|continue)|" +
                "please (log ?in|sign ?in) to (read|continue|view)|become a (vip|member) to|需要登录|请登录后阅读|VIP章节|付费章节|订阅后阅读)",
        )

        fun textLength(chapter: ExtractedChapter): Int = chapter.paragraphs.sumOf { it.length }

        fun isSane(chapter: ExtractedChapter): Boolean = textLength(chapter) >= MIN_CHARS

        fun looksLocked(html: String): Boolean = LOCK_HINTS.containsMatchIn(html)
    }
}
