package com.readerapp.ai

/** One billed model attempt of a response: the model that ran it and its (cache-weighted) token counts. */
data class BilledAttempt(val model: String, val inputTokens: Long, val outputTokens: Long)

/**
 * Turns the API's per-attempt record (`usage.iterations`, untyped in the SDK, converted to plain Java maps/lists)
 * into billed attempts. With server-side fallback the top-level usage describes only the attempt that produced the
 * returned message; every attempt in `iterations` that produced billable tokens must be charged at ITS model's rate.
 */
object UsageAccounting {
    /** [iterations] is the `usage.iterations` value as plain Java structures (List of Map), or null when absent. */
    fun attempts(iterations: Any?, fallbackModel: String): List<BilledAttempt> {
        val list = iterations as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val m = entry as? Map<*, *> ?: return@mapNotNull null
            fun n(key: String): Long = (m[key] as? Number)?.toLong() ?: 0L
            val input = ClaudeClient.effectiveInputTokens(n("input_tokens"), n("cache_creation_input_tokens"), n("cache_read_input_tokens"))
            val output = n("output_tokens")
            if (input == 0L && output == 0L) null
            else BilledAttempt((m["model"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackModel, input, output)
        }
    }

    /**
     * What to record for a finished call: the iteration list when the API sent one, otherwise a single attempt on the
     * model that actually served the response ([servedModel] may differ from the requested one after a fallback).
     */
    fun toRecord(iterations: Any?, servedModel: String?, requestModel: String, input: Long, output: Long): List<BilledAttempt> {
        val fromIterations = attempts(iterations, servedModel ?: requestModel)
        if (fromIterations.isNotEmpty()) return fromIterations
        if (input == 0L && output == 0L) return emptyList()
        return listOf(BilledAttempt(servedModel?.takeIf { it.isNotBlank() } ?: requestModel, input, output))
    }
}
