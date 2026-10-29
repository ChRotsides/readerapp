package com.readerapp.scrape.heal

import com.readerapp.scrape.ExtractedChapter
import com.readerapp.scrape.ExtractionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictPolicyTest {
    private fun d(v: Verdict, p: Double) = VerdictPolicy.decide(VerdictResult(v, p, "t"))

    @Test fun validThreshold() {
        assertEquals(Decision.ACCEPT, d(Verdict.VALID_CHAPTER, 0.90))
        assertEquals(Decision.ACCEPT, d(Verdict.VALID_CHAPTER, 0.99))
        assertEquals(Decision.AMBIGUOUS, d(Verdict.VALID_CHAPTER, 0.89))
    }

    @Test fun adapterProblems() {
        for (v in listOf(Verdict.TRUNCATED, Verdict.NAVIGATION_OR_JUNK, Verdict.WRONG_CHAPTER)) {
            assertEquals(Decision.ADAPTER_FAILURE, d(v, 0.80))
            assertEquals(Decision.AMBIGUOUS, d(v, 0.79))
        }
    }

    @Test fun pageProblemsNeverBecomeAdapterFailures() {
        assertEquals(Decision.LOCKED, d(Verdict.LOCKED_CHAPTER, 0.95))
        assertEquals(Decision.NEEDS_LOGIN, d(Verdict.LOGIN_WALL, 0.8))
        assertEquals(Decision.NEEDS_BOT_CHECK, d(Verdict.BOT_CHECK, 0.85))
        assertEquals(Decision.RETRY_LATER, d(Verdict.ERROR_PAGE, 0.9))
        for (v in Verdict.entries.filter { it.isPageProblem }) assertEquals(Decision.AMBIGUOUS, d(v, 0.5))
        assertTrue(Verdict.entries.none { it.isPageProblem && it.isAdapterProblem })
    }

    // ---- rule checks

    private fun chapter(paragraphs: List<String>, title: String? = "T") = ExtractedChapter(title, paragraphs, null, null)
    private val good = List(5) { "x".repeat(80) + " $it" }

    @Test fun rules() {
        assertNull(RuleChecks.check("<p>ok</p>", chapter(good), null))
        assertEquals(Verdict.TRUNCATED, RuleChecks.check("", chapter(emptyList()), null)!!.verdict)
        assertEquals(Verdict.TRUNCATED, RuleChecks.check("", chapter(listOf("short", "short", "short")), null)!!.verdict)
        assertEquals(Verdict.NAVIGATION_OR_JUNK, RuleChecks.check("", chapter(listOf("y".repeat(300))), null)!!.verdict)
        assertEquals(Verdict.WRONG_CHAPTER, RuleChecks.check("", chapter(good), good)!!.verdict)
        assertEquals(Verdict.NAVIGATION_OR_JUNK, RuleChecks.check("", chapter(good, title = " "), null)!!.verdict)
    }

    @Test fun rulesDetectPageProblems() {
        val cf = "<html><head><title>Just a moment...</title></head><body>cf-chl-opt</body></html>"
        assertEquals(Verdict.BOT_CHECK, RuleChecks.check(cf, chapter(emptyList()), null)!!.verdict)
        assertEquals(Verdict.LOGIN_WALL, RuleChecks.check("Please log in to read this", chapter(listOf("teaser")), null)!!.verdict)
        assertEquals(Verdict.LOCKED_CHAPTER, RuleChecks.check("Unlock this chapter with coins", chapter(listOf("teaser")), null)!!.verdict)
        // a real long chapter that merely mentions the phrase is not a wall
        val long = List(30) { "z".repeat(100) }
        assertNull(RuleChecks.check("Unlock this chapter with coins", chapter(long), null))
        assertNotNull(RuleChecks.pageProblem("Please sign in to continue... please sign in to read"))
    }

    // ---- pipeline

    private class ScriptedVerifier(private val answers: MutableList<Any>) : ExtractionVerifier {
        var calls = 0
        override suspend fun verify(input: VerificationInput): VerdictResult {
            calls++
            val a = answers.removeAt(0)
            if (a is Exception) throw a
            return a as VerdictResult
        }
    }
    private val page = com.readerapp.scrape.FetchedPage("https://site.example/c1", "<html><body>text</body></html>", com.readerapp.scrape.FetchMode.HTTP)

    @Test fun rulesShortCircuitTheVerifier() = runTest {
        val v = ScriptedVerifier(mutableListOf())
        val a = ChapterAssessor(v).assess(page, chapter(emptyList()))
        assertEquals(Decision.ADAPTER_FAILURE, a.decision)
        assertEquals(0, v.calls)
    }

    @Test fun ambiguousIsRetriedOnceThenFlagged() = runTest {
        val amb = VerdictResult(Verdict.VALID_CHAPTER, 0.7, "t")
        val v = ScriptedVerifier(mutableListOf(amb, amb))
        val a = ChapterAssessor(v).assess(page, chapter(good))
        assertEquals(Decision.AMBIGUOUS, a.decision)
        assertEquals(2, v.calls)
        assertFalse(a.confirmedValid)
    }

    @Test fun ambiguousThenValidAccepts() = runTest {
        val v = ScriptedVerifier(mutableListOf(VerdictResult(Verdict.TRUNCATED, 0.6, "t"), VerdictResult(Verdict.VALID_CHAPTER, 0.95, "t")))
        val a = ChapterAssessor(v).assess(page, chapter(good))
        assertTrue(a.confirmedValid)
        assertEquals(2, v.calls)
    }

    @Test fun unavailableVerifierAcceptsUnverified() = runTest {
        val v = ScriptedVerifier(mutableListOf(VerifierUnavailableException("down")))
        val a = ChapterAssessor(v).assess(page, chapter(good))
        assertEquals(Decision.ACCEPT, a.decision)
        assertFalse(a.verified)
        assertFalse(a.confirmedValid)
    }

    @Test fun fallbackVerifierUsesHaikuWhenJevUnavailable() = runTest {
        val jev = ScriptedVerifier(mutableListOf(VerifierUnavailableException("x")))
        val haiku = ScriptedVerifier(mutableListOf(VerdictResult(Verdict.VALID_CHAPTER, 0.97, "haiku")))
        val r = FallbackVerifier(jev, haiku).verify(VerificationInput("u", "t", good, ""))
        assertEquals("haiku", r.source)
    }

    // ---- consecutive failures

    private class RecordingScheduler : RepairScheduler {
        val repairs = mutableListOf<String>()
        override fun scheduleRepair(domain: String) { repairs += domain }
        override fun scheduleGeneration(domain: String, pageUrl: String, novelUrl: String?) = Unit
    }

    @Test fun twoConsecutiveAdapterFailuresBreakAndScheduleRepair() = runTest {
        val store = FakeStore(listOf(adapter(1, com.readerapp.data.db.AdapterStatus.ACTIVE)))
        val sched = RecordingScheduler()
        val health = AdapterHealth(store, sched)
        val ad = store.get("site.example", 1)!!
        health.onAdapterFailure(ad, page)
        assertEquals(com.readerapp.data.db.AdapterStatus.ACTIVE, store.get("site.example", 1)!!.status)
        assertTrue(sched.repairs.isEmpty())
        health.onAdapterFailure(ad, page)
        assertEquals(com.readerapp.data.db.AdapterStatus.BROKEN, store.get("site.example", 1)!!.status)
        assertEquals(listOf("site.example"), sched.repairs)
        assertNotNull(store.states["site.example"]!!.failingHtml)
    }

    @Test fun successInBetweenResetsTheCount() = runTest {
        val store = FakeStore(listOf(adapter(1, com.readerapp.data.db.AdapterStatus.ACTIVE)))
        val sched = RecordingScheduler()
        val health = AdapterHealth(store, sched)
        val ad = store.get("site.example", 1)!!
        health.onAdapterFailure(ad, page)
        health.onSuccess(ad)
        health.onAdapterFailure(ad, page)
        assertEquals(com.readerapp.data.db.AdapterStatus.ACTIVE, store.get("site.example", 1)!!.status)
        assertTrue(sched.repairs.isEmpty())
        assertTrue(AdapterHealth.shouldBreak(2) && !AdapterHealth.shouldBreak(1))
    }
}
