package com.readerapp.scrape.heal

import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.DomainState
import com.readerapp.data.db.SiteAdapter
import com.readerapp.data.repo.AdapterStore
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.HtmlTrimmer
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules background adapter work. Implemented with WorkManager (unique work per domain). */
interface RepairScheduler {
    fun scheduleRepair(domain: String)
    fun scheduleGeneration(domain: String, pageUrl: String, novelUrl: String?)
}

/** Success/failure bookkeeping for adapter versions and page-problem state. */
@Singleton
class AdapterHealth @Inject constructor(
    private val store: AdapterStore,
    private val scheduler: RepairScheduler,
) {
    suspend fun onSuccess(adapter: SiteAdapter) {
        store.recordSuccess(adapter)
        val state = store.domainState(adapter.domain)
        if (state != null && (state.browserActionNeeded != null || state.failingHtml != null)) {
            store.saveDomainState(state.copy(browserActionNeeded = null, failingHtml = null, failingUrl = null))
        }
    }

    /**
     * Counts an adapter failure. [BREAK_AFTER] consecutive failures flip the version to BROKEN, keep the failing
     * page for repair and schedule the repair job.
     */
    suspend fun onAdapterFailure(adapter: SiteAdapter, page: FetchedPage) {
        store.recordFailure(adapter)
        val current = store.get(adapter.domain, adapter.version) ?: return
        if (current.status != AdapterStatus.ACTIVE) return
        if (shouldBreak(current.consecutiveFailures)) {
            store.setStatus(current.domain, current.version, AdapterStatus.BROKEN)
            val state = store.domainState(current.domain) ?: DomainState(current.domain)
            store.saveDomainState(
                state.copy(
                    failingUrl = page.url,
                    failingHtml = HtmlTrimmer.trim(page.html, page.url).html,
                    lastMessage = "v${current.version} broke on ${page.url}",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            scheduler.scheduleRepair(current.domain)
        }
    }

    /** Login wall / bot check: remembered so the UI can ask the user to act; adapters are never touched. */
    suspend fun onBrowserActionNeeded(url: String, message: String) {
        val domain = DomainRateLimiter.domainOf(url)
        val state = store.domainState(domain) ?: DomainState(domain)
        store.saveDomainState(state.copy(browserActionNeeded = message, updatedAt = System.currentTimeMillis()))
    }

    companion object {
        const val BREAK_AFTER = 2
        fun shouldBreak(consecutiveFailures: Int) = consecutiveFailures >= BREAK_AFTER
    }
}
