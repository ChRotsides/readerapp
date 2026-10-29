package com.readerapp.scrape

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DomainRateLimiterTest {

    @Test
    fun firstRequestIsImmediateSecondWaitsMinInterval() = runTest {
        val limiter = DomainRateLimiter(2_500, nowMs = { currentTime }, sleep = { delay(it) })
        limiter.acquire("https://a.example.com/1")
        assertEquals(0, currentTime)
        limiter.acquire("https://a.example.com/2")
        assertEquals(2_500, currentTime)
        limiter.acquire("https://a.example.com/3")
        assertEquals(5_000, currentTime)
    }

    @Test
    fun domainsAreIndependentAndWwwIsNormalised() = runTest {
        val limiter = DomainRateLimiter(2_500, nowMs = { currentTime }, sleep = { delay(it) })
        limiter.acquire("https://a.example.com/1")
        limiter.acquire("https://b.example.com/1")
        assertEquals(0, currentTime)
        limiter.acquire("https://www.a.example.com/2")
        assertEquals(2_500, currentTime)
    }

    @Test
    fun noWaitIfEnoughTimeAlreadyPassed() = runTest {
        val limiter = DomainRateLimiter(2_500, nowMs = { currentTime }, sleep = { delay(it) })
        limiter.acquire("https://a.example.com/1")
        delay(10_000)
        val before = currentTime
        limiter.acquire("https://a.example.com/2")
        assertEquals(before, currentTime)
    }

    @Test
    fun concurrentCallersAreSpacedOut() = runTest {
        val limiter = DomainRateLimiter(2_500, nowMs = { currentTime }, sleep = { delay(it) })
        val times = (1..3).map { async { limiter.acquire("https://a.example.com/$it"); currentTime } }.awaitAll()
        assertEquals(listOf(0L, 2_500L, 5_000L), times.sorted())
    }

    @Test
    fun domainExtraction() {
        assertEquals("example.com", DomainRateLimiter.domainOf("https://www.Example.com/x?y=1"))
        assertEquals("sub.example.com", DomainRateLimiter.domainOf("http://sub.example.com:8080/"))
    }
}
