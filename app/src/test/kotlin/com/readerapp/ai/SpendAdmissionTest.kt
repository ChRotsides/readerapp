package com.readerapp.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SpendAdmissionTest {
    private class Budget(var spent: Long, val cap: Long) : BudgetSource, SpendGuard {
        override suspend fun spentMicros() = spent
        override suspend fun capMicros() = cap
        override suspend fun capReached() = spent >= cap
    }

    private val request = LlmRequest(ClaudeModels.HAIKU, "s", "u", LlmEffort.LOW, maxTokens = 1_000, purpose = "t")
    private val estimate = ModelPricing.estimateMaxMicros(ClaudeModels.HAIKU, 2, 1_000)

    @Test
    fun concurrentCallsCannotAllSlipUnderTheCap() = runTest {
        // Room for exactly two worst-case calls.
        val budget = Budget(spent = 0, cap = estimate * 2 + estimate / 2)
        val running = AtomicInteger()
        var maxRunning = 0
        val gate = CompletableDeferred<Unit>()
        val slow = object : LlmClient {
            override suspend fun complete(request: LlmRequest): LlmResponse {
                maxRunning = maxOf(maxRunning, running.incrementAndGet())
                gate.await()
                running.decrementAndGet()
                return LlmResponse("ok", 1, 1)
            }
        }
        val guarded = GuardedLlmClient(slow, budget)
        val results = (1..5).map { async { runCatching { guarded.complete(request) } } }
        delay(10)
        gate.complete(Unit)
        val outcomes = results.awaitAll()
        assertEquals(2, outcomes.count { it.isSuccess })
        assertEquals(3, outcomes.count { it.exceptionOrNull() is LlmException.SpendCapReached })
        assertEquals(2, maxRunning)
    }

    @Test
    fun reservationIsReleasedAfterCompletionAndAfterFailure() = runTest {
        val budget = Budget(spent = 0, cap = estimate + 1)
        var fail = true
        val llm = LlmClient {
            if (fail) { fail = false; throw LlmException.Retryable("x") }
            LlmResponse("ok", 1, 1)
        }
        val guarded = GuardedLlmClient(llm, budget)
        try { guarded.complete(request); throw AssertionError("should fail") } catch (_: LlmException.Retryable) {}
        assertEquals("ok", guarded.complete(request).text)   // would be refused if the first reservation leaked
    }

    @Test
    fun alreadySpentBudgetCountsAgainstAdmission() = runTest {
        val budget = Budget(spent = 1_000_000, cap = 1_000_000 + estimate - 1)
        val guarded = GuardedLlmClient(LlmClient { LlmResponse("ok", 1, 1) }, budget)
        try { guarded.complete(request); throw AssertionError("should be refused") } catch (_: LlmException.SpendCapReached) {}
    }

    @Test
    fun usageIsRecordedEvenWhenTheCallerIsCancelledDuringTheBlockingCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recorded = AtomicInteger()
        val job = launch {
            blockingThenRecord(
                call = { started.countDown(); release.await(); "result" },
                record = { recorded.incrementAndGet() },
            )
        }
        withContext(kotlinx.coroutines.Dispatchers.IO) { started.await() }
        job.cancel()            // caller cancelled while the (uninterruptible) call is in flight
        release.countDown()     // call finishes
        job.cancelAndJoin()
        assertEquals(1, recorded.get())
        assertTrue(job.isCancelled)
    }
}
