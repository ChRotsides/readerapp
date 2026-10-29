package com.readerapp.scrape.heal

import com.readerapp.scrape.ExtractedChapter

/** What a verifier looks at: the extraction result plus a little context about the page. */
data class VerificationInput(
    val url: String,
    val title: String?,
    val paragraphs: List<String>,
    /** Visible text near the top of the page (not the extraction), to spot walls and error pages. */
    val pageTextSnippet: String,
)

/** Cheap, deterministic checks that run before any model is consulted. */
object RuleChecks {
    const val MIN_CHARS = 200
    const val MIN_PARAGRAPHS = 3
    /** Lock/login phrases only matter when the extracted text is short; real chapters can mention them. */
    const val WALL_MAX_CHARS = 1_000

    private val BOT_MARKERS = Regex(
        "(?i)(cf[-_]chl[-_]opt|cf-browser-verification|<title>\\s*just a moment|checking your browser before accessing|" +
            "verify you are (a )?human|attention required! \\| cloudflare|enable javascript and cookies to continue|" +
            "please complete the security check|px-captcha|ddos protection by)",
    )
    private val LOGIN_PHRASES = Regex(
        "(?i)(please (log ?in|sign ?in) to (read|continue|view)|log ?in to (read|continue)|需要登录|请登录后阅读)",
    )
    private val LOCKED_PHRASES = Regex(
        "(?i)(locked chapter|unlock (this )?chapter|unlock with|subscribe to (read|unlock|continue)|" +
            "become a (vip|member) to|VIP章节|付费章节|订阅后阅读)",
    )

    /** Page-level problem detectable from raw HTML alone (bot check, login wall, lock), else null. */
    fun pageProblem(html: String): VerdictResult? = when {
        BOT_MARKERS.containsMatchIn(html) -> VerdictResult(Verdict.BOT_CHECK, 0.95, "rules", "bot-check markers in page")
        LOGIN_PHRASES.containsMatchIn(html) -> VerdictResult(Verdict.LOGIN_WALL, 0.90, "rules", "login prompt in page")
        LOCKED_PHRASES.containsMatchIn(html) -> VerdictResult(Verdict.LOCKED_CHAPTER, 0.90, "rules", "lock/paywall phrase in page")
        else -> null
    }

    /** Null means all rule checks passed and a verifier should look at the result. */
    fun check(html: String, chapter: ExtractedChapter, previousParagraphs: List<String>?): VerdictResult? {
        val length = chapter.paragraphs.sumOf { it.length }
        if (length < WALL_MAX_CHARS) pageProblem(html)?.let { return it }
        if (chapter.paragraphs.isEmpty()) return rule(Verdict.TRUNCATED, 0.95, "no paragraphs extracted")
        if (length < MIN_CHARS) return rule(Verdict.TRUNCATED, 0.95, "only $length characters extracted (minimum $MIN_CHARS)")
        if (chapter.paragraphs.size < MIN_PARAGRAPHS) {
            return rule(Verdict.NAVIGATION_OR_JUNK, 0.85, "only ${chapter.paragraphs.size} paragraphs extracted")
        }
        if (previousParagraphs != null && previousParagraphs.isNotEmpty() && previousParagraphs == chapter.paragraphs) {
            return rule(Verdict.WRONG_CHAPTER, 0.95, "text is identical to the previous chapter")
        }
        if (chapter.title.isNullOrBlank()) return rule(Verdict.NAVIGATION_OR_JUNK, 0.80, "no chapter title extracted")
        return null
    }

    private fun rule(v: Verdict, p: Double, reason: String) = VerdictResult(v, p, "rules", reason)
}
