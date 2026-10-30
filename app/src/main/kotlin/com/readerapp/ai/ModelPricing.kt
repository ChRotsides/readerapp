package com.readerapp.ai

import java.time.Instant
import java.time.ZoneId

/** Per-1M-token USD prices. Since 1 USD per 1M tokens equals 1 micro-USD per token, cost math is direct. */
object ModelPricing {
    private data class Price(val inPerM: Double, val outPerM: Double)

    private val HAIKU = Price(0.10, 0.50)
    private val SONNET = Price(2.0, 10.0)

    /** Unknown models are priced like Sonnet so the cap errs on the safe side. */
    fun costMicros(model: String, inputTokens: Long, outputTokens: Long): Long {
        val p = if (model == ClaudeModels.HAIKU) HAIKU else SONNET
        return Math.round(inputTokens * p.inPerM + outputTokens * p.outPerM)
    }

    /**
     * Worst-case cost of one request, used to reserve budget before the call: input estimated from the text size
     * (deliberately pessimistic, and as if every input token were a 1.25x cache write) plus all of [maxTokens] as output.
     */
    fun estimateMaxMicros(model: String, inputChars: Int, maxTokens: Long): Long {
        val inputTokens = ((inputChars + 1) / 2 + 200L)
        return costMicros(model, Math.round(inputTokens * 1.25), maxTokens)
    }

    fun microsToUsd(micros: Long): Double = micros / 1_000_000.0

    fun nextMonthStartMillis(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun monthStartMillis(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
}
