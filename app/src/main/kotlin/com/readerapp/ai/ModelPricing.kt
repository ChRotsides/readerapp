package com.readerapp.ai

import java.time.Instant
import java.time.ZoneId

/** Per-1M-token USD prices. Since 1 USD per 1M tokens equals 1 micro-USD per token, cost math is direct. */
object ModelPricing {
    private data class Price(val inPerM: Double, val outPerM: Double)

    private val HAIKU = Price(0.10, 0.50)
    private val SONNET = Price(2.0, 10.0)

    /**
     * Any other model (notably the server-side fallback model, whose identity and price are not under our control)
     * is priced above Sonnet so the cap errs on the safe side.
     */
    private val UNKNOWN = Price(5.0, 25.0)

    /** Server-side fallback may hop more than once; reservations assume this many extra billable attempts. */
    const val FALLBACK_ATTEMPTS = 2

    fun costMicros(model: String, inputTokens: Long, outputTokens: Long): Long {
        val p = when (model) {
            ClaudeModels.HAIKU -> HAIKU
            ClaudeModels.SONNET -> SONNET
            else -> UNKNOWN
        }
        return Math.round(inputTokens * p.inPerM + outputTokens * p.outPerM)
    }

    /** Fixed per-request overhead (message framing, tool/format scaffolding) added to every input bound. */
    private const val INPUT_OVERHEAD_TOKENS = 500L

    /**
     * Upper bound on input tokens from the request's UTF-8 size: a token never covers fewer than ~2 bytes in
     * practice (CJK characters are 3 bytes and 1-2 tokens), so bytes / 2 holds for any script, plus fixed overhead.
     * [inputBytes] must include system, context, history, user text and the JSON schema.
     */
    fun inputTokensUpperBound(inputBytes: Long): Long = (inputBytes + 1) / 2 + INPUT_OVERHEAD_TOKENS

    /** Conservative output-token estimate for text streamed without a final usage report (bytes / 2, never below 1). */
    fun outputTokensUpperBound(streamedBytes: Long): Long = ((streamedBytes + 1) / 2).coerceAtLeast(if (streamedBytes > 0) 1 else 0)

    /**
     * Worst-case cost of one request, used to reserve budget before the call: input bounded from the UTF-8 size (as if
     * every input token were a 1.25x cache write) plus all of [maxTokens] as output. With [fallback], up to
     * [FALLBACK_ATTEMPTS] further complete attempts on an unknown (pricier) model are reserved as well.
     */
    fun estimateMaxMicros(model: String, inputBytes: Long, maxTokens: Long, fallback: Boolean = false): Long {
        val input = Math.round(inputTokensUpperBound(inputBytes) * 1.25)
        var total = costMicros(model, input, maxTokens)
        if (fallback) total += FALLBACK_ATTEMPTS * costMicros("fallback-model", input, maxTokens)
        return total
    }

    /** UTF-8 size of everything a request sends. */
    fun inputBytes(r: LlmRequest): Long {
        fun b(t: String?) = t?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        return b(r.system) + b(r.user) + b(r.context) + b(r.jsonSchema) + r.history.sumOf { b(it.first) + b(it.second) }
    }

    fun microsToUsd(micros: Long): Double = micros / 1_000_000.0

    fun nextMonthStartMillis(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** Unique WorkManager name of the wake-up that fires at the start of the month after [nowMs]'s month. */
    fun resumeWorkName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "spend-resume-" + java.time.YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone)).plusMonths(1)

    fun monthStartMillis(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
}
