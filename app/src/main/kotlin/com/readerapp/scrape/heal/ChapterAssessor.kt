package com.readerapp.scrape.heal

import com.readerapp.scrape.ExtractedChapter
import com.readerapp.scrape.FetchedPage
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of break detection for one extraction. [verified] is false when only the rule checks ran
 * (verifier unavailable), in which case [decision] ACCEPT means "nothing looked wrong", not "confirmed".
 */
data class Assessment(val decision: Decision, val verdict: VerdictResult, val verified: Boolean) {
    /** Confirmed good by a verifier (VALID >= 0.90). Required before an adapter version is trusted. */
    val confirmedValid: Boolean get() = decision == Decision.ACCEPT && verified
}

/** Break detection pipeline: cheap rule checks first, then the typed verifier; in-between verdicts are re-asked once. */
@Singleton
class ChapterAssessor @Inject constructor(private val verifier: ExtractionVerifier) {

    suspend fun assess(
        page: FetchedPage,
        chapter: ExtractedChapter,
        previousParagraphs: List<String>? = null,
    ): Assessment {
        RuleChecks.check(page.html, chapter, previousParagraphs)?.let {
            return Assessment(VerdictPolicy.decide(it), it, verified = false)
        }
        val input = VerificationInput(page.url, chapter.title, chapter.paragraphs, pageSnippet(page.html))
        var last: VerdictResult? = null
        repeat(2) {
            val verdict = try {
                verifier.verify(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VerifierUnavailableException) {
                return Assessment(
                    Decision.ACCEPT,
                    VerdictResult(Verdict.VALID_CHAPTER, 0.0, "none", "no verifier available: ${e.message}"),
                    verified = false,
                )
            }
            last = verdict
            val decision = VerdictPolicy.decide(verdict)
            if (decision != Decision.AMBIGUOUS) return Assessment(decision, verdict, verified = true)
        }
        return Assessment(Decision.AMBIGUOUS, checkNotNull(last), verified = true)
    }

    private fun pageSnippet(html: String): String =
        runCatching { Jsoup.parse(html).body().text().take(600) }.getOrDefault("")
}
