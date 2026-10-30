package com.readerapp.scrape.heal

import com.readerapp.ai.ClaudeModels
import com.readerapp.ai.LlmClient
import com.readerapp.ai.LlmException
import com.readerapp.ai.LlmRequest
import com.readerapp.ai.LlmResponse
import com.readerapp.ai.SpendGuard
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.DomainState
import com.readerapp.data.db.SiteAdapter
import com.readerapp.scrape.ExtractedChapter
import com.readerapp.scrape.ExtractionResult
import com.readerapp.scrape.FetchMode
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.adapter.AdapterRuntime
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairOrchestratorTest {
    private val domain = "site.example"

    /** Adapter code containing "good" extracts a fine chapter, anything else fails. Records the order of attempts. */
    private class FakeRuntime : AdapterRuntime {
        val executed = mutableListOf<String>()
        override suspend fun execute(adapter: SiteAdapter, page: FetchedPage): ExtractionResult {
            executed += "v${adapter.version}"
            if (!adapter.code.contains("good")) return ExtractionResult.Failure("selector matched nothing")
            return ExtractionResult.Success(
                ExtractedChapter("Title", List(5) { "p".repeat(90) + it }, null, null), extractor = "adapter:${adapter.domain}:v${adapter.version}",
            )
        }
    }

    private class FakeLlm(private val replies: List<String>, private val delayMs: Long = 0, val onCall: () -> Unit = {}) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            onCall()
            if (delayMs > 0) delay(delayMs)
            val text = replies.getOrNull(requests.size - 1) ?: replies.last()
            return LlmResponse(text, 100, 100)
        }
    }

    private fun reply(code: String) =
        """{"kind":"js","spec_json":"","js_code":"function extract(d){ /*$code*/ }","notes":"n"}"""

    private class FakePages : PageSource {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String): FetchedPage { fetched += url; return FetchedPage(url, "<html><body>$url</body></html>", FetchMode.HTTP) }
        override suspend fun sampleUrls(domain: String, excludeUrl: String, limit: Int) =
            listOf("https://site.example/s1", "https://site.example/s2", "https://site.example/s3").take(limit)
        override suspend fun latestChapterUrl(domain: String) = "https://site.example/latest"
    }

    private class FakeNotifier : AdapterNotifier {
        val events = mutableListOf<String>()
        override fun fixed(domain: String, version: Int, restored: Boolean) { events += "fixed:v$version:$restored" }
        override fun failed(domain: String, reason: String) { events += "failed" }
        override fun spendCapReached(domain: String) { events += "cap" }
        override fun generated(domain: String, version: Int) { events += "generated:v$version" }
    }

    private class Setup(
        val store: FakeStore,
        llm: LlmClient,
        verifier: ExtractionVerifier = ExtractionVerifier { VerdictResult(Verdict.VALID_CHAPTER, 0.99, "fake") },
        spend: SpendGuard = SpendGuard { false },
    ) {
        val runtime = FakeRuntime()
        val notifier = FakeNotifier()
        val pages = FakePages()
        val orchestrator = RepairOrchestrator(store, runtime, ChapterAssessor(verifier), AdapterWriter(llm), pages, spend, notifier)
    }

    private fun brokenStore(vararg adapters: SiteAdapter) = FakeStore(adapters.toList()).also {
        it.states[domain] = DomainState(domain, failingUrl = "https://site.example/failing", failingHtml = "<html><body>fail</body></html>")
    }

    @Test
    fun olderVersionsAreTriedNewestFirstAndFirstPassingIsReactivated() = runTest {
        val store = brokenStore(
            adapter(1, AdapterStatus.RETIRED, "good"),
            adapter(2, AdapterStatus.RETIRED, "bad"),
            adapter(3, AdapterStatus.BROKEN, "bad"),
        )
        val llm = FakeLlm(listOf(reply("good")))
        val s = Setup(store, llm)
        val outcome = s.orchestrator.repair(domain)

        assertTrue(outcome is RepairOutcome.Reactivated)
        assertEquals(listOf("v2", "v1"), s.runtime.executed)       // v3 (the broken one) is not retried; newest older first
        assertTrue(llm.requests.isEmpty())
        assertEquals(AdapterStatus.ACTIVE, store.get(domain, 1)!!.status)
        assertEquals(AdapterStatus.BROKEN, store.get(domain, 3)!!.status)
        assertEquals(listOf("fixed:v1:true"), s.notifier.events)
    }

    @Test
    fun haikuThreeAttemptsThenSonnetEscalationAndNewVersionIsAppended() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.RETIRED, "bad"), adapter(2, AdapterStatus.BROKEN, "bad"))
        val llm = FakeLlm(listOf(reply("bad"), reply("bad"), reply("bad"), reply("good")))
        val s = Setup(store, llm)
        val outcome = s.orchestrator.repair(domain)

        assertTrue(outcome is RepairOutcome.Fixed)
        assertEquals(
            listOf(ClaudeModels.HAIKU, ClaudeModels.HAIKU, ClaudeModels.HAIKU, ClaudeModels.SONNET),
            llm.requests.map { it.model },
        )
        val v3 = store.get(domain, 3)!!
        assertEquals(AdapterStatus.ACTIVE, v3.status)
        assertEquals(ClaudeModels.SONNET, v3.writtenBy)
        assertEquals(AdapterStatus.BROKEN, store.get(domain, 2)!!.status)      // old versions kept, never overwritten
        assertTrue(store.get(domain, 1) != null && store.get(domain, 2)!!.code.contains("bad"))
        assertEquals(1, store.fixtureList.size)
        assertEquals(v3.fixtureId, store.fixtureList[0].id)
        assertNull(store.states[domain]!!.failingHtml)
        assertEquals("fixed:v3:false", s.notifier.events.single())
    }

    @Test
    fun errorOfEachAttemptIsFedBackToTheNext() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        val llm = FakeLlm(listOf(reply("bad"), reply("good")))
        Setup(store, llm).orchestrator.repair(domain)
        assertFalse(llm.requests[0].user.contains("Previous attempts"))
        assertTrue(llm.requests[1].user.contains("selector matched nothing"))
        assertTrue(llm.requests[0].user.contains("<broken_adapter"))
    }

    @Test
    fun attemptLimitsThenGenericFallback() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        val llm = FakeLlm(listOf(reply("bad")))
        val s = Setup(store, llm)
        val outcome = s.orchestrator.repair(domain)
        assertTrue(outcome is RepairOutcome.GaveUp)
        assertEquals(6, llm.requests.size)
        assertEquals(3, llm.requests.count { it.model == ClaudeModels.HAIKU })
        assertEquals(3, llm.requests.count { it.model == ClaudeModels.SONNET })
        assertTrue(store.states[domain]!!.genericFallback)
        assertNull(store.activeFor(domain))
        assertEquals(listOf("failed"), s.notifier.events)
    }

    @Test
    fun unparseableRepliesCountAsAttemptsAndAreFedBack() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        val llm = FakeLlm(listOf("not json", """{"kind":"json","spec_json":"{}","js_code":"","notes":""}""", reply("good")))
        val outcome = Setup(store, llm).orchestrator.repair(domain)
        assertTrue(outcome is RepairOutcome.Fixed)
        assertEquals(3, llm.requests.size)
        assertTrue(llm.requests[2].user.contains("unusable"))
    }

    @Test
    fun spendCapStopsBeforeFurtherCalls() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        var calls = 0
        val llm = FakeLlm(listOf(reply("bad")), onCall = { calls++ })
        val s = Setup(store, llm, spend = SpendGuard { calls >= 1 })
        val outcome = s.orchestrator.repair(domain)
        assertEquals(RepairOutcome.SpendCapReached, outcome)
        assertEquals(1, llm.requests.size)
        assertEquals(listOf("cap"), s.notifier.events)

        val s2 = Setup(brokenStore(adapter(1, AdapterStatus.BROKEN, "bad")), FakeLlm(listOf(reply("good"))), spend = SpendGuard { true })
        assertEquals(RepairOutcome.SpendCapReached, s2.orchestrator.repair(domain))
    }

    @Test
    fun admissionRefusalInsideTheCallDefersInsteadOfGivingUp() = runTest {
        val llm = object : LlmClient {
            override suspend fun complete(request: LlmRequest): LlmResponse = throw com.readerapp.ai.LlmException.SpendCapReached()
        }
        val s = Setup(brokenStore(adapter(1, AdapterStatus.BROKEN, "bad")), llm)
        assertEquals(RepairOutcome.SpendCapReached, s.orchestrator.repair(domain))
        assertEquals(listOf("cap"), s.notifier.events)
    }

    @Test
    fun secondConcurrentJobForSameDomainIsRejected() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        val s = Setup(store, FakeLlm(listOf(reply("good")), delayMs = 1_000))
        val first = async { s.orchestrator.repair(domain) }
        val second = async { s.orchestrator.repair(domain) }
        assertEquals(RepairOutcome.AlreadyRunning, second.await())
        assertTrue(first.await() is RepairOutcome.Fixed)
        assertEquals(2, store.versions(domain).size)   // not written twice
    }

    @Test
    fun differentDomainsRunIndependently() = runTest {
        val store = FakeStore(listOf(adapter(1, AdapterStatus.BROKEN, "bad", "a.example"), adapter(1, AdapterStatus.BROKEN, "bad", "b.example")))
        store.states["a.example"] = DomainState("a.example", failingUrl = "u", failingHtml = "<html><body>x</body></html>")
        store.states["b.example"] = DomainState("b.example", failingUrl = "u", failingHtml = "<html><body>x</body></html>")
        val s = Setup(store, FakeLlm(listOf(reply("good")), delayMs = 500))
        val a = async { s.orchestrator.repair("a.example") }
        val b = async { s.orchestrator.repair("b.example") }
        assertTrue(a.await() is RepairOutcome.Fixed)
        assertTrue(b.await() is RepairOutcome.Fixed)
    }

    @Test
    fun pageProblemsNeverTouchTheAdapterOrCallModels() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.RETIRED, "good"), adapter(2, AdapterStatus.BROKEN, "bad"))
        val llm = FakeLlm(listOf(reply("good")))
        val s = Setup(store, llm, verifier = { VerdictResult(Verdict.LOGIN_WALL, 0.97, "fake") })
        val outcome = s.orchestrator.repair(domain)
        assertTrue(outcome is RepairOutcome.PageProblem)
        assertTrue(llm.requests.isEmpty())
        assertEquals(AdapterStatus.BROKEN, store.get(domain, 2)!!.status)
        assertEquals(AdapterStatus.RETIRED, store.get(domain, 1)!!.status)
        assertTrue(store.states[domain]!!.browserActionNeeded!!.contains("log in"))
        assertFalse(store.states[domain]!!.genericFallback)
    }

    @Test
    fun candidateMustPassOnOtherStoredChaptersToo() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        // Passes on the failing page but fails on samples: the verifier says junk for sample pages only.
        val verifier = ExtractionVerifier { input ->
            if (input.url.endsWith("/s1") || input.url.endsWith("/s2")) VerdictResult(Verdict.NAVIGATION_OR_JUNK, 0.95, "fake") else VerdictResult(Verdict.VALID_CHAPTER, 0.99, "fake")
        }
        val llm = FakeLlm(listOf(reply("good")))
        val s = Setup(store, llm, verifier = verifier)
        val outcome = s.orchestrator.repair(domain)
        assertTrue(outcome is RepairOutcome.GaveUp)
        assertEquals(6, llm.requests.size)
        assertTrue(llm.requests[1].user.contains("another chapter"))
        assertTrue("https://site.example/s1" in s.pages.fetched)
        assertTrue("https://site.example/s3" !in s.pages.fetched)
    }

    @Test
    fun verifierOutageDefersWithoutBurningAttemptsAndCandidateIsRecheckedFirst() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        var down = true
        val llm = FakeLlm(listOf(reply("good")))
        val s = Setup(
            store, llm,
            verifier = { if (down) throw VerifierUnavailableException("down") else VerdictResult(Verdict.VALID_CHAPTER, 0.99, "fake") },
        )
        val first = s.orchestrator.repair(domain)
        assertTrue(first is RepairOutcome.VerifierUnavailable)
        assertEquals(1, llm.requests.size)                       // one candidate bought, not six
        assertEquals(AdapterStatus.BROKEN, store.get(domain, 1)!!.status)
        assertNull(store.get(domain, 2)?.takeIf { it.status == AdapterStatus.ACTIVE }) // untrusted candidate never activated
        assertFalse(s.notifier.events.contains("failed"))        // not given up on
        assertNull(store.states[domain]!!.let { if (it.genericFallback) it else null })

        down = false
        val second = s.orchestrator.repair(domain)
        assertTrue(second is RepairOutcome.Fixed)
        assertEquals(1, llm.requests.size)                       // the retained candidate was verified, nothing re-bought
        assertEquals(AdapterStatus.ACTIVE, store.get(domain, 2)!!.status)
    }

    @Test
    fun spendCapDuringVerificationIsReportedAsSpendCap() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        val s = Setup(store, FakeLlm(listOf(reply("good"))), verifier = { throw VerifierUnavailableException("cap", spendCap = true) })
        assertEquals(RepairOutcome.SpendCapReached, s.orchestrator.repair(domain))
    }

    @Test
    fun repairRefetchesFailingUrlInsteadOfUsingTheLossySnapshot() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.BROKEN, "bad"))
        val s = Setup(store, FakeLlm(listOf(reply("good"))))
        s.orchestrator.repair(domain)
        assertEquals("https://site.example/failing", s.pages.fetched.first())
    }

    @Test
    fun staleRepairDoesNotActivateWhenUserActivatedAnotherVersionMeanwhile() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.RETIRED, "bad"), adapter(2, AdapterStatus.BROKEN, "bad"))
        val llm = FakeLlm(listOf(reply("good")), onCall = { kotlinx.coroutines.runBlocking { store.activateExclusive(domain, 1) } })
        val s = Setup(store, llm)
        val outcome = s.orchestrator.repair(domain)
        assertEquals(RepairOutcome.Stale, outcome)
        assertEquals(listOf(1), store.adapters.values.filter { it.status == AdapterStatus.ACTIVE }.map { it.version })
        assertEquals(AdapterStatus.RETIRED, store.get(domain, 3)!!.status)   // kept as history, not active
    }

    @Test
    fun exactlyOneActiveVersionAfterRepair() = runTest {
        val store = brokenStore(adapter(1, AdapterStatus.RETIRED, "bad"), adapter(2, AdapterStatus.BROKEN, "bad"))
        Setup(store, FakeLlm(listOf(reply("good")))).orchestrator.repair(domain)
        assertEquals(1, store.adapters.values.count { it.status == AdapterStatus.ACTIVE })
    }

    @Test
    fun generationWritesV1WithSonnetAndFixture() = runTest {
        val store = FakeStore()
        val llm = FakeLlm(listOf(reply("bad"), reply("good")))
        val s = Setup(store, llm)
        val outcome = s.orchestrator.generate(domain, "https://site.example/ch-1")
        assertTrue(outcome is RepairOutcome.Fixed)
        assertTrue(llm.requests.all { it.model == ClaudeModels.SONNET })
        val v1 = store.activeFor(domain)!!
        assertEquals(1, v1.version)
        assertEquals(1, store.fixtureList.size)
        assertEquals("https://site.example/ch-1", store.fixtureList[0].url)
        assertEquals(listOf("generated:v1"), s.notifier.events)
        // domains that already have an adapter are left alone
        assertEquals(RepairOutcome.NotNeeded, s.orchestrator.generate(domain, "https://site.example/ch-2"))
    }

    @Test
    fun healthyDomainIsNotRepairedUnlessForced() = runTest {
        val store = FakeStore(listOf(adapter(1, AdapterStatus.ACTIVE, "good")))
        val llm = FakeLlm(listOf(reply("good")))
        val s = Setup(store, llm)
        assertEquals(RepairOutcome.NotNeeded, s.orchestrator.repair(domain))
        val forced = s.orchestrator.repair(domain, force = true)
        assertTrue(forced is RepairOutcome.Fixed)
        assertEquals(2, (forced as RepairOutcome.Fixed).adapter.version)
        assertEquals(AdapterStatus.BROKEN, store.get(domain, 1)!!.status)
    }

    @Test
    fun missingApiKeyAndTransientErrorsAreReported() = runTest {
        val noKey = object : LlmClient { override suspend fun complete(request: LlmRequest): LlmResponse = throw LlmException.NotConfigured() }
        assertEquals(RepairOutcome.NoApiKey, Setup(brokenStore(adapter(1, AdapterStatus.BROKEN, "bad")), noKey).orchestrator.repair(domain))
        val flaky = object : LlmClient { override suspend fun complete(request: LlmRequest): LlmResponse = throw LlmException.Retryable("429") }
        val out = Setup(brokenStore(adapter(1, AdapterStatus.BROKEN, "bad")), flaky).orchestrator.repair(domain)
        assertTrue(out is RepairOutcome.Transient)
    }
}
