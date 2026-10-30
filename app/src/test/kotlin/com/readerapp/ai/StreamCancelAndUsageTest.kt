package com.readerapp.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StreamCancelAndUsageTest {
    private class BlockingStream : AutoCloseable {
        val closed = CountDownLatch(1)
        val entered = CountDownLatch(1)
        /** Simulates a blocked hasNext(): returns only once the stream is closed. */
        fun blockingRead() { entered.countDown(); closed.await(30, TimeUnit.SECONDS) }
        override fun close() { closed.countDown() }
    }

    @Test fun cancellationClosesTheStreamWhileTheReadIsBlocked() = runBlocking {
        val stream = BlockingStream()
        val job = async(Dispatchers.Default) { withCloseOnCancel(stream) { stream.blockingRead() } }
        assertTrue(stream.entered.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        job.cancel()
        assertTrue("stream was not closed on cancel", stream.closed.await(5, TimeUnit.SECONDS))
        try { withTimeout(5000) { job.await() } } catch (_: CancellationException) {}
        assertTrue("cancel took too long", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test fun normalCompletionAlsoClosesAndReturns() = runBlocking {
        val stream = BlockingStream()
        val r = withCloseOnCancel(stream) { 42 }
        assertEquals(42, r)
        assertTrue(stream.closed.await(1, TimeUnit.SECONDS))
    }

    @Test fun reportedUsageIsKept() {
        val t = StreamUsageTracker()
        t.onMessageStart(1000, 7)
        t.onGenerated("hello")
        t.onMessageDelta(250)
        assertEquals(1000L, t.billableInput(99))
        assertEquals(250L, t.billableOutput())
    }

    @Test fun interruptedBeforeAnyVisibleTextChargesStartOutputAndFloor() {
        val t = StreamUsageTracker()
        t.onMessageStart(1000, 3)
        assertEquals(StreamUsageTracker.MIN_STARTED_OUTPUT_TOKENS, t.billableOutput())
    }

    @Test fun interruptedDuringThinkingCountsThinkingBytes() {
        val t = StreamUsageTracker()
        t.onMessageStart(1000, 1)
        t.onGenerated("x".repeat(4000)) // thinking deltas
        assertEquals(2000L, t.billableOutput()) // bytes / 2
    }

    @Test fun interruptedWithoutMessageStartStillChargesFloorAndInputBound() {
        val t = StreamUsageTracker()
        assertEquals(777L, t.billableInput(777))
        t.onGenerated("abc")
        assertEquals(StreamUsageTracker.MIN_STARTED_OUTPUT_TOKENS, t.billableOutput())
    }

    @Test fun midStreamUsageReportResetsTheEstimateButIsNeverLowered() {
        val t = StreamUsageTracker()
        t.onMessageStart(10, 1)
        t.onGenerated("x".repeat(2000))
        t.onMessageDelta(500)
        t.onGenerated("y".repeat(100))
        assertEquals(500L, t.billableOutput()) // max(reported 500, 50, floor)
        t.onGenerated("z".repeat(2000))
        assertEquals(1050L, t.billableOutput().coerceAtLeast(1050L)) // reported + unreported never below reported
        assertTrue(t.billableOutput() >= 500L)
    }
}
