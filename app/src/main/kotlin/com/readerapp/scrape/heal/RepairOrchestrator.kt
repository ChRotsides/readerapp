package com.readerapp.scrape.heal

import com.readerapp.ai.ClaudeModels
import com.readerapp.ai.LlmEffort
import com.readerapp.ai.LlmException
import com.readerapp.ai.SpendGuard
import com.readerapp.data.db.AdapterFixture
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.DomainState
import com.readerapp.data.db.SiteAdapter
import com.readerapp.data.repo.AdapterStore
import com.readerapp.scrape.ExtractedChapter
import com.readerapp.scrape.ExtractionResult
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.HtmlTrimmer
import com.readerapp.scrape.adapter.AdapterRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where repair gets pages from. Fetches must respect the per-domain rate limiter. */
interface PageSource {
    suspend fun fetch(url: String): FetchedPage?
    /** Other already-fetched chapter URLs of [domain], newest first. */
    suspend fun sampleUrls(domain: String, excludeUrl: String, limit: Int): List<String>
    suspend fun latestChapterUrl(domain: String): String?
}

interface AdapterNotifier {
    fun fixed(domain: String, version: Int, restored: Boolean)
    fun failed(domain: String, reason: String)
    fun spendCapReached(domain: String)
    fun generated(domain: String, version: Int)
}

sealed interface RepairOutcome {
    /** A new version was written and validated. */
    data class Fixed(val adapter: SiteAdapter) : RepairOutcome
    /** An older version validated and was reactivated. */
    data class Reactivated(val adapter: SiteAdapter) : RepairOutcome
    data object NotNeeded : RepairOutcome
    data object AlreadyRunning : RepairOutcome
    data object NoApiKey : RepairOutcome
    data object SpendCapReached : RepairOutcome
    /** The page itself is a login/bot/locked/error page: nothing was changed. */
    data class PageProblem(val reason: String) : RepairOutcome
    /** Transient (rate limit, network): the caller may retry later. */
    data class Transient(val reason: String) : RepairOutcome
    data class GaveUp(val reason: String) : RepairOutcome
}

data class RepairConfig(
    val haikuAttempts: Int = 3,
    val sonnetAttempts: Int = 3,
    val samplePages: Int = 2,
)

/** Serialized form of an extraction, stored in `AdapterFixture.expectedOutput`. */
@Serializable
data class FixtureOutput(
    val title: String?,
    val paragraphs: List<String>,
    val nextUrl: String? = null,
    val prevUrl: String? = null,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    fun summary(): String = buildString {
        appendLine("title: $title")
        appendLine("paragraph count: ${paragraphs.size}")
        appendLine("nextUrl: $nextUrl")
        appendLine("prevUrl: $prevUrl")
        paragraphs.take(3).forEachIndexed { i, p -> appendLine("paragraph ${i + 1}: ${p.take(300)}") }
    }.trimEnd()

    companion object {
        fun of(c: ExtractedChapter) = FixtureOutput(c.title, c.paragraphs, c.nextUrl, c.prevUrl)
        fun parse(text: String): FixtureOutput? =
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * Adapter generation and self-healing repair. One job at a time per domain (non-blocking mutex keyed by domain).
 * Never repairs page problems (locked / login / bot check / error pages); versions are append-only.
 */
@Singleton
class RepairOrchestrator @Inject constructor(
    private val store: AdapterStore,
    private val runtime: AdapterRuntime,
    private val assessor: ChapterAssessor,
    private val writer: AdapterWriter,
    private val pages: PageSource,
    private val spend: SpendGuard,
    private val notifier: AdapterNotifier,
) {
    var config = RepairConfig()

    private val locks = ConcurrentHashMap<String, Mutex>()

    private sealed interface Validation {
        data object Pass : Validation
        data class Fail(val reason: String) : Validation
        data class PageProblem(val verdict: VerdictResult) : Validation
    }

    private data class Tier(val model: String, val effort: LlmEffort, val attempts: Int)

    /** Writes v1 for a domain without any adapter, from [pageUrl]. */
    suspend fun generate(domain: String, pageUrl: String, novelUrl: String? = null): RepairOutcome =
        exclusive(domain) {
            if (store.activeFor(domain) != null || store.versions(domain).isNotEmpty()) return@exclusive RepairOutcome.NotNeeded
            val page = pages.fetch(pageUrl) ?: return@exclusive RepairOutcome.Transient("Could not fetch $pageUrl")
            val novelPage = novelUrl?.takeIf { it != pageUrl }?.let { pages.fetch(it) }
            val tiers = listOf(Tier(ClaudeModels.SONNET, LlmEffort.HIGH, config.sonnetAttempts))
            val outcome = writeLoop(domain, page, tiers, previous = null, novelPage = novelPage, samples = false, nextVersion = 1)
            if (outcome is RepairOutcome.Fixed) notifier.generated(domain, outcome.adapter.version)
            else if (outcome is RepairOutcome.GaveUp) giveUp(domain, outcome.reason, notify = true)
            outcome
        }

    /**
     * Repairs a BROKEN domain: older versions newest-first, then Haiku x3, then Sonnet x3, then generic fallback.
     * [force] also treats a currently ACTIVE (working) adapter as the one to replace ("regenerate now").
     */
    suspend fun repair(domain: String, force: Boolean = false): RepairOutcome = exclusive(domain) {
        val versions = store.versions(domain).sortedByDescending { it.version }
        if (versions.isEmpty()) return@exclusive RepairOutcome.NotNeeded
        val active = versions.firstOrNull { it.status == AdapterStatus.ACTIVE }
        if (active != null && !force) return@exclusive RepairOutcome.NotNeeded
        val broken = active ?: versions.firstOrNull { it.status == AdapterStatus.BROKEN } ?: versions.first()

        val state = store.domainState(domain)
        val page = failingPage(domain, state)
            ?: return@exclusive RepairOutcome.Transient("No page available to repair $domain against")

        // (a) older versions, newest first
        for (old in versions.filter { it.version < broken.version }) {
            when (val v = validate(old, page)) {
                Validation.Pass -> {
                    store.setStatus(domain, broken.version, AdapterStatus.BROKEN)
                    store.setStatus(domain, old.version, AdapterStatus.ACTIVE)
                    store.resetFailures(old)
                    clearState(domain, "Restored v${old.version}")
                    notifier.fixed(domain, old.version, restored = true)
                    return@exclusive RepairOutcome.Reactivated(old.copy(status = AdapterStatus.ACTIVE))
                }
                is Validation.PageProblem -> return@exclusive pageProblem(domain, v.verdict)
                is Validation.Fail -> Unit
            }
        }

        // (b) Haiku, (c) Sonnet
        val tiers = listOf(
            Tier(ClaudeModels.HAIKU, LlmEffort.MEDIUM, config.haikuAttempts),
            Tier(ClaudeModels.SONNET, LlmEffort.HIGH, config.sonnetAttempts),
        )
        val next = (versions.maxOf { it.version }) + 1
        val outcome = writeLoop(domain, page, tiers, previous = broken, novelPage = null, samples = true, nextVersion = next)
        when (outcome) {
            is RepairOutcome.Fixed -> notifier.fixed(domain, outcome.adapter.version, restored = false)
            is RepairOutcome.GaveUp -> giveUp(domain, outcome.reason, notify = true) // (d)
            else -> Unit
        }
        outcome
    }

    /** "Regenerate now": generation when there is nothing yet, else a forced repair. */
    suspend fun regenerate(domain: String): RepairOutcome {
        if (store.versions(domain).isEmpty()) {
            val url = pages.latestChapterUrl(domain) ?: return RepairOutcome.GaveUp("No stored chapter from $domain to learn from")
            return generate(domain, url)
        }
        return repair(domain, force = true)
    }

    // ---- internals

    private suspend fun <T : RepairOutcome> exclusive(domain: String, block: suspend () -> T): RepairOutcome {
        val lock = locks.getOrPut(domain) { Mutex() }
        if (!lock.tryLock()) return RepairOutcome.AlreadyRunning
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private suspend fun failingPage(domain: String, state: DomainState?): FetchedPage? {
        val url = state?.failingUrl
        val html = state?.failingHtml
        if (url != null && html != null) return FetchedPage(url, html, com.readerapp.scrape.FetchMode.HTTP)
        val latest = pages.latestChapterUrl(domain) ?: return null
        return pages.fetch(latest)
    }

    private suspend fun writeLoop(
        domain: String,
        page: FetchedPage,
        tiers: List<Tier>,
        previous: SiteAdapter?,
        novelPage: FetchedPage?,
        samples: Boolean,
        nextVersion: Int,
    ): RepairOutcome {
        val trimmed = HtmlTrimmer.trim(page.html, page.url)
        val novelTrimmed = novelPage?.let { HtmlTrimmer.trim(it.html, it.url).html }
        val example = previous?.let { store.fixtures(domain).firstOrNull() }
            ?.let { FixtureOutput.parse(it.expectedOutput)?.summary() }
        val feedback = ArrayList<String>()

        for (tier in tiers) {
            repeat(tier.attempts) {
                if (spend.capReached()) {
                    notifier.spendCapReached(domain)
                    return RepairOutcome.SpendCapReached
                }
                val candidate = try {
                    writer.write(
                        tier.model,
                        tier.effort,
                        WriteContext(domain, page.url, trimmed.html, trimmed.reduced, novelTrimmed, previous, example, feedback.toList()),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AdapterParseException) {
                    feedback += "Your last reply was unusable: ${e.message}"
                    return@repeat
                } catch (e: LlmException.NotConfigured) {
                    return RepairOutcome.NoApiKey
                } catch (e: LlmException.Retryable) {
                    return RepairOutcome.Transient(e.message ?: "temporary API error")
                } catch (e: LlmException.Refused) {
                    feedback += "The model refused an earlier attempt."
                    return@repeat
                } catch (e: LlmException.Truncated) {
                    feedback += "Your last reply was cut off for length; keep the adapter small."
                    return@repeat
                } catch (e: LlmException) {
                    return RepairOutcome.GaveUp(e.message ?: "API error")
                }

                val adapter = SiteAdapter(
                    domain = domain,
                    version = nextVersion,
                    kind = candidate.kind,
                    code = candidate.code,
                    writtenBy = tier.model,
                    status = AdapterStatus.ACTIVE,
                )
                when (val v = validate(adapter, page)) {
                    is Validation.PageProblem -> return pageProblem(domain, v.verdict)
                    is Validation.Fail -> {
                        feedback += "Attempt with ${tier.model} failed on the target page: ${v.reason}"
                        return@repeat
                    }
                    Validation.Pass -> Unit
                }
                if (samples) {
                    val failure = validateSamples(adapter, domain, page.url)
                    if (failure != null) {
                        feedback += failure
                        return@repeat
                    }
                }
                return commit(adapter, previous, page, trimmed.html)
            }
        }
        return RepairOutcome.GaveUp("No valid adapter after ${tiers.sumOf { it.attempts }} attempts. " + feedback.lastOrNull().orEmpty())
    }

    /** Re-fetches up to [RepairConfig.samplePages] other stored chapters of the domain; returns a feedback line on failure. */
    private suspend fun validateSamples(adapter: SiteAdapter, domain: String, excludeUrl: String): String? {
        for (url in pages.sampleUrls(domain, excludeUrl, config.samplePages)) {
            val sample = pages.fetch(url) ?: continue
            when (val v = validate(adapter, sample)) {
                Validation.Pass -> Unit
                is Validation.PageProblem -> Unit // that sample is locked/blocked: says nothing about the adapter
                is Validation.Fail -> return "The adapter worked on the target page but failed on another chapter ($url): ${v.reason}"
            }
        }
        return null
    }

    private suspend fun validate(adapter: SiteAdapter, page: FetchedPage): Validation {
        when (val result = runtime.execute(adapter, page)) {
            is ExtractionResult.Failure -> {
                RuleChecks.pageProblem(page.html)?.let { return Validation.PageProblem(it) }
                return Validation.Fail(result.reason)
            }
            is ExtractionResult.Success -> {
                val a = assessor.assess(page, result.chapter)
                return when {
                    a.confirmedValid -> Validation.Pass
                    a.decision == Decision.ADAPTER_FAILURE || a.decision == Decision.AMBIGUOUS ->
                        Validation.Fail("extraction judged ${a.verdict.describe()}")
                    a.decision == Decision.ACCEPT -> Validation.Fail("extraction could not be verified: ${a.verdict.reason}")
                    else -> Validation.PageProblem(a.verdict)
                }
            }
        }
    }

    private suspend fun commit(adapter: SiteAdapter, previous: SiteAdapter?, page: FetchedPage, trimmedHtml: String): RepairOutcome {
        val extracted = (runtime.execute(adapter, page) as? ExtractionResult.Success)?.chapter
        val fixtureId = store.saveFixture(
            AdapterFixture(
                domain = adapter.domain,
                url = page.url,
                trimmedHtml = trimmedHtml,
                expectedOutput = extracted?.let { FixtureOutput.of(it).toJson() } ?: "{}",
            ),
        )
        val saved = adapter.copy(fixtureId = fixtureId, status = AdapterStatus.ACTIVE, lastSuccessAt = System.currentTimeMillis())
        store.save(saved)
        previous?.let { store.setStatus(it.domain, it.version, AdapterStatus.BROKEN) }
        clearState(adapter.domain, "Now v${saved.version} (${saved.writtenBy})")
        return RepairOutcome.Fixed(saved)
    }

    private suspend fun clearState(domain: String, message: String) {
        val s = store.domainState(domain) ?: DomainState(domain)
        store.saveDomainState(
            s.copy(
                genericFallback = false, browserActionNeeded = null, failingUrl = null, failingHtml = null,
                lastMessage = message, updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun giveUp(domain: String, reason: String, notify: Boolean) {
        val s = store.domainState(domain) ?: DomainState(domain)
        store.saveDomainState(s.copy(genericFallback = true, lastMessage = reason, updatedAt = System.currentTimeMillis()))
        if (notify) notifier.failed(domain, reason)
    }

    private suspend fun pageProblem(domain: String, verdict: VerdictResult): RepairOutcome {
        val message = when (verdict.verdict) {
            Verdict.LOGIN_WALL -> "Open $domain in the browser and log in"
            Verdict.BOT_CHECK -> "Open $domain in the browser and pass the check"
            Verdict.LOCKED_CHAPTER -> "Chapter is locked"
            else -> "Site returned an error page; try again later"
        }
        if (verdict.verdict == Verdict.LOGIN_WALL || verdict.verdict == Verdict.BOT_CHECK) {
            val s = store.domainState(domain) ?: DomainState(domain)
            store.saveDomainState(s.copy(browserActionNeeded = message, updatedAt = System.currentTimeMillis()))
        }
        return RepairOutcome.PageProblem(message)
    }
}
