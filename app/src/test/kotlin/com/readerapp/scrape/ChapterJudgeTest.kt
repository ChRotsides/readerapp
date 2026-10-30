package com.readerapp.scrape

import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.settings.normalizeBaseUrl
import com.readerapp.scrape.adapter.AdapterTag
import com.readerapp.scrape.heal.AdapterHealth
import com.readerapp.scrape.heal.ChapterAssessor
import com.readerapp.scrape.heal.FakeStore
import com.readerapp.scrape.heal.RepairScheduler
import com.readerapp.scrape.heal.Verdict
import com.readerapp.scrape.heal.VerdictResult
import com.readerapp.scrape.heal.VerifierUnavailableException
import com.readerapp.scrape.heal.adapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterJudgeTest {
    private val domain = "site.example"
    private val url = "https://site.example/ch-1"
    private val paragraphs = List(5) { "Sentence number $it of a perfectly ordinary chapter body, long enough to be real text." }
    private val chapter = ExtractedChapter("Chapter 1", paragraphs, null, null)
    private val tag = AdapterTag.of(domain, 1)

    private class Sched : RepairScheduler {
        val repairs = mutableListOf<String>()
        override fun scheduleRepair(domain: String) { repairs += domain }
        override fun scheduleGeneration(domain: String, pageUrl: String, novelUrl: String?) = Unit
    }

    private fun judge(store: FakeStore, sched: Sched, verifierDown: Boolean): ChapterJudge {
        val assessor = ChapterAssessor {
            if (verifierDown) throw VerifierUnavailableException("down") else VerdictResult(Verdict.VALID_CHAPTER, 0.99, "fake")
        }
        return ChapterJudge(assessor, store, AdapterHealth(store, sched), GenericExtractor())
    }

    private fun page(mode: FetchMode) = FetchedPage(url, "<html><body><p>x</p></body></html>", mode)

    /**
     * The reported scenario: verifier unavailable; the HTTP attempt gets a JS shell the adapter cannot read, the WebView
     * attempt is read fine by the same adapter. Repeating this for several chapters must not break the adapter.
     */
    @Test fun httpShellFailureFollowedByWebViewSuccessNeverBreaksTheAdapter() = runTest {
        val store = FakeStore(listOf(adapter(1, AdapterStatus.ACTIVE)))
        val sched = Sched()
        val judge = judge(store, sched, verifierDown = true)
        repeat(4) {
            val shell = judge.judgeFailure(page(FetchMode.HTTP), ExtractionResult.Failure("no match", AdapterFault(domain, 1)))
            val rendered = judge.judge(page(FetchMode.WEBVIEW), ExtractionResult.Success(chapter, extractor = tag), previous = null)
            assertTrue(rendered.accepted != null)
            assertFalse(rendered.verified)
            judge.account(rendered, listOf(shell, rendered))
        }
        assertEquals(AdapterStatus.ACTIVE, store.get(domain, 1)!!.status)
        assertEquals(0, store.get(domain, 1)!!.consecutiveFailures)
        assertTrue(sched.repairs.isEmpty())
    }

    @Test fun failuresCountOncePerFetchAndBreakAfterTwoFetches() = runTest {
        val store = FakeStore(listOf(adapter(1, AdapterStatus.ACTIVE)))
        val sched = Sched()
        val judge = judge(store, sched, verifierDown = false)
        fun failedFetch() = listOf(
            judge.judgeFailure(page(FetchMode.HTTP), ExtractionResult.Failure("a", AdapterFault(domain, 1))),
            judge.judgeFailure(page(FetchMode.WEBVIEW), ExtractionResult.Failure("b", AdapterFault(domain, 1))),
        )
        judge.account(null, failedFetch())
        assertEquals(1, store.get(domain, 1)!!.consecutiveFailures)   // two attempts, one failure
        assertEquals(AdapterStatus.ACTIVE, store.get(domain, 1)!!.status)
        judge.account(null, failedFetch())
        assertEquals(AdapterStatus.BROKEN, store.get(domain, 1)!!.status)
        assertEquals(listOf(domain), sched.repairs)
    }

    @Test fun pageProblemsNeverCountAgainstTheAdapter() = runTest {
        val store = FakeStore(listOf(adapter(1, AdapterStatus.ACTIVE)))
        val judge = judge(store, Sched(), verifierDown = false)
        val fault = judge.judgeFailure(page(FetchMode.HTTP), ExtractionResult.Failure("a", AdapterFault(domain, 1)))
        val login = com.readerapp.scrape.Judgement(reason = "Login required", needsBrowser = "log in")
        judge.account(null, listOf(fault, login))
        assertEquals(0, store.get(domain, 1)!!.consecutiveFailures)
    }

    @Test fun verifiedAcceptedResultResetsFailuresAndCountsSuccess() = runTest {
        val store = FakeStore(listOf(adapter(1, AdapterStatus.ACTIVE)))
        store.recordFailure(store.get(domain, 1)!!)
        val judge = judge(store, Sched(), verifierDown = false)
        val verified = judge.judge(page(FetchMode.HTTP), ExtractionResult.Success(chapter, extractor = tag), null)
        assertTrue(verified.verified && verified.accepted != null)
        judge.account(verified, listOf(verified))
        assertEquals(0, store.get(domain, 1)!!.consecutiveFailures)
        assertEquals(1, store.get(domain, 1)!!.successCount)
    }

    @Test fun jevEndpointNormalisation() {
        assertEquals("https://api.example.com", normalizeBaseUrl("api.example.com"))
        assertEquals("http://localhost:8080/x", normalizeBaseUrl(" http://localhost:8080/x "))
        assertNull(normalizeBaseUrl("ftp://x.example"))
        assertNull(normalizeBaseUrl("https://"))
        assertNull(normalizeBaseUrl("not a url at all"))
        assertNull(normalizeBaseUrl(""))
    }
}
