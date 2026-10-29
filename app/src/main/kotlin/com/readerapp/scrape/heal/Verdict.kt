package com.readerapp.scrape.heal

enum class Verdict {
    VALID_CHAPTER, TRUNCATED, NAVIGATION_OR_JUNK, WRONG_CHAPTER,
    LOGIN_WALL, LOCKED_CHAPTER, BOT_CHECK, ERROR_PAGE;

    /** The adapter extracted the wrong thing from an otherwise fine page. */
    val isAdapterProblem: Boolean get() = this == TRUNCATED || this == NAVIGATION_OR_JUNK || this == WRONG_CHAPTER

    /** The page itself is the problem (login/paywall/bot check/error); adapters are never blamed for these. */
    val isPageProblem: Boolean get() = this == LOGIN_WALL || this == LOCKED_CHAPTER || this == BOT_CHECK || this == ERROR_PAGE
}

/** [source] names who decided ("rules", "jev", "haiku"); [reason] is a short explanation for logs and repair prompts. */
data class VerdictResult(
    val verdict: Verdict,
    val probability: Double,
    val source: String,
    val reason: String? = null,
) {
    fun describe(): String = "${verdict.name} (p=${"%.2f".format(probability)}, by $source)" + (reason?.let { ": $it" } ?: "")
}

/** What the pipeline does about a verdict. */
enum class Decision {
    /** Good chapter: store it. */
    ACCEPT,
    /** The adapter is at fault: count a failure. */
    ADAPTER_FAILURE,
    /** Mark the chapter LOCKED. Never bypassed, adapter untouched. */
    LOCKED,
    /** Login wall: ask the user to open the page in the browser and log in. Adapter untouched. */
    NEEDS_LOGIN,
    /** Bot check: ask the user to open the page in the browser and pass the check. Adapter untouched. */
    NEEDS_BOT_CHECK,
    /** Error page: try again later. Adapter untouched. */
    RETRY_LATER,
    /** In between thresholds: retry once, then store but flag. */
    AMBIGUOUS,
}

/** Thresholds from DESIGN.md. */
object VerdictPolicy {
    const val VALID_THRESHOLD = 0.90
    const val PROBLEM_THRESHOLD = 0.80

    fun decide(r: VerdictResult): Decision = when {
        r.verdict == Verdict.VALID_CHAPTER ->
            if (r.probability >= VALID_THRESHOLD) Decision.ACCEPT else Decision.AMBIGUOUS
        r.probability < PROBLEM_THRESHOLD -> Decision.AMBIGUOUS
        r.verdict.isAdapterProblem -> Decision.ADAPTER_FAILURE
        r.verdict == Verdict.LOCKED_CHAPTER -> Decision.LOCKED
        r.verdict == Verdict.LOGIN_WALL -> Decision.NEEDS_LOGIN
        r.verdict == Verdict.BOT_CHECK -> Decision.NEEDS_BOT_CHECK
        else -> Decision.RETRY_LATER
    }
}
