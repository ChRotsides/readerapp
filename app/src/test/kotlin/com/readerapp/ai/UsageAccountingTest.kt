package com.readerapp.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAccountingTest {
    private val iterations = listOf(
        mapOf("type" to "message", "model" to "claude-fable-5", "input_tokens" to 535, "output_tokens" to 0),
        mapOf("type" to "fallback_message", "model" to "claude-opus-4-8", "input_tokens" to 412, "output_tokens" to 264),
    )

    @Test
    fun everyBillableIterationIsChargedAtItsOwnModel() {
        val rec = UsageAccounting.toRecord(iterations, "claude-opus-4-8", ClaudeModels.SONNET, 412, 264)
        assertEquals(listOf("claude-fable-5", "claude-opus-4-8"), rec.map { it.model })
        assertEquals(535L, rec[0].inputTokens)
        assertEquals(264L, rec[1].outputTokens)
    }

    @Test
    fun withoutIterationsTheServingModelIsUsed() {
        val rec = UsageAccounting.toRecord(null, "claude-opus-4-8", ClaudeModels.SONNET, 10, 5)
        assertEquals(listOf(BilledAttempt("claude-opus-4-8", 10, 5)), rec)
        assertEquals(emptyList<BilledAttempt>(), UsageAccounting.toRecord(null, null, ClaudeModels.SONNET, 0, 0))
    }

    @Test
    fun cacheTokensAreWeightedInIterations() {
        val rec = UsageAccounting.attempts(
            listOf(mapOf("model" to "m", "input_tokens" to 100, "cache_creation_input_tokens" to 100, "cache_read_input_tokens" to 1000, "output_tokens" to 1)),
            "x",
        )
        assertEquals(100L + 125 + 100, rec.single().inputTokens)
    }

    @Test
    fun reservationCoversFallbackAndDenseScripts() {
        val plain = ModelPricing.estimateMaxMicros(ClaudeModels.SONNET, 10_000, 1_000)
        val withFallback = ModelPricing.estimateMaxMicros(ClaudeModels.SONNET, 10_000, 1_000, fallback = true)
        assertTrue(withFallback > plain * 3)
        // 10k CJK characters = 30k UTF-8 bytes; even 1.5 tokens per character (15k) stays under the bound.
        assertTrue(ModelPricing.inputTokensUpperBound(30_000) >= 15_000)
        assertTrue(ModelPricing.outputTokensUpperBound(4_000) >= 1_000)
        assertTrue(ModelPricing.costMicros("some-fallback-model", 1_000_000, 0) > ModelPricing.costMicros(ClaudeModels.SONNET, 1_000_000, 0))
    }
}

class SourceRevisionTest {
    @Test
    fun revisionChangesWithTextAndParagraphBoundaries() {
        val a = com.readerapp.ai.process.SourceRevision.of(listOf("ab", "c"))
        assertEquals(a, com.readerapp.ai.process.SourceRevision.of(listOf("ab", "c")))
        assertTrue(a != com.readerapp.ai.process.SourceRevision.of(listOf("a", "bc")))
        assertTrue(a != com.readerapp.ai.process.SourceRevision.of(listOf("ab", "d")))
    }
}
