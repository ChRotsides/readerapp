package com.readerapp.scrape.adapter

import com.readerapp.data.repo.AdapterStore
import com.readerapp.scrape.ChapterExtractor
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.scrape.ExtractionResult
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.heal.AdapterHealth
import com.readerapp.scrape.heal.RuleChecks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses the ACTIVE adapter of the page's domain. Domains without an active adapter yield a Failure so the
 * chain moves on to the generic extractor. When the adapter itself cannot produce anything and the page is
 * not a login/bot/locked page, that counts as an adapter failure (two in a row = BROKEN, repair is scheduled).
 * Whether extracted text is actually a good chapter is judged afterwards by the break-detection pipeline.
 */
@Singleton
class AdapterExtractor @Inject constructor(
    private val adapters: AdapterStore,
    private val runtime: AdapterRuntime,
    private val health: AdapterHealth,
) : ChapterExtractor {

    override suspend fun extract(page: FetchedPage): ExtractionResult {
        val domain = DomainRateLimiter.domainOf(page.url)
        val adapter = adapters.activeFor(domain) ?: return ExtractionResult.Failure("No adapter for $domain")
        val result = runtime.execute(adapter, page)
        if (result is ExtractionResult.Failure && RuleChecks.pageProblem(page.html) == null) {
            health.onAdapterFailure(adapter, page)
        }
        return result
    }
}
