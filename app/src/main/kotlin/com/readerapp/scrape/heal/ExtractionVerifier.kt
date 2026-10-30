package com.readerapp.scrape.heal

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** The verifier cannot give an answer right now (not configured, timeout, HTTP error, spend cap, ...). */
class VerifierUnavailableException(
    message: String,
    cause: Throwable? = null,
    /** The reason is the monthly spend cap rather than an outage. */
    val spendCap: Boolean = false,
) : Exception(message, cause)

/** Typed, probabilistic judgement of whether an extraction is a complete, correct chapter. */
fun interface ExtractionVerifier {
    /** @throws VerifierUnavailableException when no verdict can be produced. */
    suspend fun verify(input: VerificationInput): VerdictResult
}

/** Tries [primary] (Jev) and falls back to [fallback] (Haiku) when it is unavailable. */
class FallbackVerifier(
    private val primary: ExtractionVerifier,
    private val fallback: ExtractionVerifier,
) : ExtractionVerifier {
    override suspend fun verify(input: VerificationInput): VerdictResult =
        try {
            primary.verify(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VerifierUnavailableException) {
            fallback.verify(input)
        }
}

/** Shared textual rendering of a [VerificationInput] for both verifiers. */
object VerifierText {
    private const val HEAD_CHARS = 2_500
    private const val TAIL_CHARS = 800

    fun render(input: VerificationInput): String {
        val text = input.paragraphs.joinToString("\n")
        val body = if (text.length <= HEAD_CHARS + TAIL_CHARS) text
        else text.take(HEAD_CHARS) + "\n[... ${text.length - HEAD_CHARS - TAIL_CHARS} characters omitted ...]\n" + text.takeLast(TAIL_CHARS)
        return buildString {
            appendLine("URL: ${input.url}")
            appendLine("EXTRACTED TITLE: ${input.title ?: "(none)"}")
            appendLine("EXTRACTED PARAGRAPHS: ${input.paragraphs.size}, TOTAL CHARACTERS: ${text.length}")
            appendLine("VISIBLE PAGE TEXT (start of page): ${input.pageTextSnippet}")
            appendLine("EXTRACTED CHAPTER TEXT:")
            append(body)
        }
    }
}

@Singleton
class VerifierProvider @Inject constructor(
    jev: JevVerifier,
    haiku: HaikuVerifier,
) : ExtractionVerifier by FallbackVerifier(jev, haiku)
