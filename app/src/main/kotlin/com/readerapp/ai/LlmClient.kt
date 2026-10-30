package com.readerapp.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object ClaudeModels {
    const val SONNET = "claude-sonnet-5-5"
    const val HAIKU = "claude-haiku-5-5"
}

enum class LlmEffort { LOW, MEDIUM, HIGH }

/**
 * One single-turn request. [jsonSchema] (a JSON Schema object as text) switches on structured outputs:
 * the reply text is then guaranteed to be JSON matching it. [purpose] is only used for usage records.
 */
data class LlmRequest(
    val model: String,
    val system: String,
    val user: String,
    val effort: LlmEffort,
    val jsonSchema: String? = null,
    val maxTokens: Long = 8_000,
    val purpose: String,
    /** Put a `cache_control` breakpoint on the system block (use for large, stable system prompts). */
    val cacheSystem: Boolean = false,
    /**
     * Stable text placed first in the first user message and marked with a `cache_control` breakpoint, so follow-up
     * turns (same [system], same [context], same earlier [history]) hit the prompt cache.
     */
    val context: String? = null,
    /** Earlier (user text, assistant text) turns of a conversation, oldest first; [user] is the newest user text. */
    val history: List<Pair<String, String>> = emptyList(),
    /** Ask the API to re-run the request on the default fallback model if the model refuses (Sonnet requests). */
    val serverFallback: Boolean = model == ClaudeModels.SONNET,
)

data class LlmResponse(val text: String, val inputTokens: Long, val outputTokens: Long)

/** Typed failures of an LLM call. Retryable ones are transient (rate limit, 5xx, network). */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : LlmException("No Anthropic API key is set. Add one in Settings.")
    class Refused(detail: String) : LlmException("The model refused the request: $detail")
    class Truncated : LlmException("The model ran out of output tokens before finishing.")
    class Retryable(message: String, cause: Throwable? = null) : LlmException(message, cause)
    class Rejected(message: String, cause: Throwable? = null) : LlmException(message, cause)
    class SpendCapReached : LlmException("The monthly AI spend cap has been reached.")
}

fun interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse

    /**
     * Streams the reply text as it is generated. The default implementation is not incremental (one chunk);
     * [ClaudeClient] overrides it with real streaming. Failures are thrown from the flow as [LlmException].
     */
    fun stream(request: LlmRequest): Flow<String> = flow { emit(complete(request).text) }
}
