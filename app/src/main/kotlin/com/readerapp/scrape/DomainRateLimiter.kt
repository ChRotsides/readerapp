package com.readerapp.scrape

import kotlinx.coroutines.delay
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Enforces a minimum interval between requests to the same domain. Callers reserve a slot and
 * suspend until it arrives, so concurrent callers are spaced out rather than bursting.
 */
class DomainRateLimiter(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private val nextSlot = HashMap<String, Long>()

    suspend fun acquire(url: String) = acquireDomain(domainOf(url))

    suspend fun acquireDomain(domain: String) {
        val waitMs = synchronized(lock) {
            val now = nowMs()
            val slot = max(now, nextSlot[domain] ?: now)
            nextSlot[domain] = slot + minIntervalMs
            slot - now
        }
        if (waitMs > 0) sleep(waitMs)
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 2_500L

        fun domainOf(url: String): String {
            val host = runCatching { URI(url).host }.getOrNull() ?: url
            return host.lowercase().removePrefix("www.")
        }
    }
}

/** Process-wide limiter shared by every fetcher. */
@Singleton
class SharedRateLimiter @Inject constructor() {
    val limiter = DomainRateLimiter()
}
