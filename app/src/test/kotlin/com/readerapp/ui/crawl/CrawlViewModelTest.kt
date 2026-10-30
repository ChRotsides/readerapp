package com.readerapp.ui.crawl

import com.readerapp.search.ConnectionResult
import com.readerapp.search.CrawlJob
import com.readerapp.search.CrawlRequest
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.LibraryUploadRequest
import com.readerapp.search.LibraryUploadResult
import com.readerapp.search.SearchException
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrawlViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeApi : DiscoveryApi {
        var jobs = listOf<CrawlJob>()
        var listFailure: SearchException? = null
        var startFailure: SearchException? = null
        var listCalls = 0
        val started = mutableListOf<CrawlRequest>()
        val actions = mutableListOf<String>()
        override suspend fun crawlJobs(): List<CrawlJob> { listCalls++; listFailure?.let { throw it }; return jobs }
        override suspend fun startCrawl(request: CrawlRequest): CrawlJob {
            startFailure?.let { throw it }
            started += request
            return CrawlJob(id = 10, url = request.url, scope = request.scope, chaptersPerNovel = request.chaptersPerNovel, status = "queued")
        }
        private fun act(name: String, id: Long, status: String): CrawlJob { actions += "$name$id"; return jobs.first { it.id == id }.copy(status = status) }
        override suspend fun pauseCrawl(id: Long) = act("pause", id, "paused")
        override suspend fun resumeCrawl(id: Long) = act("resume", id, "running")
        override suspend fun cancelCrawl(id: Long) = act("cancel", id, "cancelled")
        override suspend fun search(request: SearchRequest): SearchResponse = throw UnsupportedOperationException()
        override suspend fun work(workId: String): Work = throw UnsupportedOperationException()
        override suspend fun searchTitle(query: String, limit: Int): List<Work> = throw UnsupportedOperationException()
        override suspend fun testConnection(): ConnectionResult = throw UnsupportedOperationException()
        override suspend fun uploadLibraryChapters(request: LibraryUploadRequest): LibraryUploadResult = throw UnsupportedOperationException()
    }

    private fun TestScope.vm(api: FakeApi) = CrawlViewModel(api)

    @Test fun formBuildsRequestsAndValidates() {
        val s = CrawlUiState()
        assertNull(s.request()); assertFalse(s.canStart)
        assertNull(s.copy(url = "not a url").request())
        assertEquals(CrawlRequest("https://x.example/n", "novel", null), s.copy(url = " https://x.example/n ").request())
        val site = s.copy(url = "https://x.example", scope = CrawlScope.SITE, allChapters = false, chaptersText = "25").request()
        assertEquals(CrawlRequest("https://x.example", "site", 25), site)
        assertNull(s.copy(url = "https://x.example", allChapters = false, chaptersText = "").request())
        assertNull(s.copy(url = "https://x.example", allChapters = false, chaptersText = "0").request())
        // "All" ignores whatever number was typed.
        assertNull(s.copy(url = "https://x.example", allChapters = true, chaptersText = "7").request()!!.chaptersPerNovel)
    }

    @Test fun startAddsJobToTopAndClearsUrl() = runTest(dispatcher) {
        val api = FakeApi().apply { jobs = listOf(CrawlJob(id = 1, status = "done")) }
        val vm = vm(api)
        vm.refresh()
        vm.setUrl("https://x.example/n"); vm.setAllChapters(false); vm.setChaptersText("1a2")
        assertEquals("12", vm.state.value.chaptersText)
        vm.start()
        assertTrue(vm.state.value.starting)
        runCurrent()
        assertEquals(CrawlRequest("https://x.example/n", "novel", 12), api.started.single())
        assertEquals(listOf(10L, 1L), vm.state.value.jobs!!.map { it.id })
        assertEquals("", vm.state.value.url); assertFalse(vm.state.value.starting)
    }

    @Test fun startErrorsAreShownAndInvalidFormIsRejectedLocally() = runTest(dispatcher) {
        val api = FakeApi().apply { startFailure = SearchException.Unauthorized() }
        val vm = vm(api)
        vm.start()
        assertEquals("Enter the link to crawl.", vm.state.value.startError)
        vm.setUrl("https://x.example")
        vm.start(); runCurrent()
        assertTrue(vm.state.value.startError!!.contains("401"))
        assertFalse(vm.state.value.starting)
        assertEquals("https://x.example", vm.state.value.url)
        vm.setUrl("https://x.example/2")
        assertNull(vm.state.value.startError)
    }

    @Test fun pollsEveryFiveSecondsOnlyWhileStarted() = runTest(dispatcher) {
        val api = FakeApi().apply { jobs = listOf(CrawlJob(id = 1, status = "running", pagesFetched = 1)) }
        val vm = vm(api)
        vm.startPolling(); runCurrent()
        assertEquals(1, api.listCalls)
        assertEquals(1, vm.state.value.jobs!!.single().pagesFetched)
        api.jobs = listOf(CrawlJob(id = 1, status = "running", pagesFetched = 9))
        advanceTimeBy(CrawlViewModel.POLL_MS - 100); runCurrent()
        assertEquals(1, api.listCalls)
        advanceTimeBy(200); runCurrent()
        assertEquals(2, api.listCalls)
        assertEquals(9, vm.state.value.jobs!!.single().pagesFetched)
        vm.startPolling() // second start while polling does not double the loop
        advanceTimeBy(CrawlViewModel.POLL_MS); runCurrent()
        assertEquals(3, api.listCalls)
        vm.stopPolling()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, api.listCalls)
    }

    @Test fun pollFailureKeepsLastJobsAndRecovers() = runTest(dispatcher) {
        val api = FakeApi().apply { jobs = listOf(CrawlJob(id = 1)) }
        val vm = vm(api)
        vm.refresh()
        api.listFailure = SearchException.Network(null)
        vm.refresh()
        assertTrue(vm.state.value.listError!!.contains("Cannot reach"))
        assertEquals(1, vm.state.value.jobs!!.size)
        api.listFailure = null
        vm.refresh()
        assertNull(vm.state.value.listError)
    }

    @Test fun notConfiguredShowsServerMessageWithoutJobs() = runTest(dispatcher) {
        val api = FakeApi().apply { listFailure = SearchException.NotConfigured() }
        val vm = vm(api)
        vm.refresh()
        assertNull(vm.state.value.jobs)
        assertTrue(vm.state.value.listError!!.contains("Settings"))
    }

    @Test fun pauseResumeCancelReplaceTheJobAndTrackBusy() = runTest(dispatcher) {
        val api = FakeApi().apply { jobs = listOf(CrawlJob(id = 1, status = "running"), CrawlJob(id = 2, status = "queued")) }
        val vm = vm(api)
        vm.refresh()
        vm.pause(1)
        assertTrue(1L in vm.state.value.busy)
        runCurrent()
        assertTrue(vm.state.value.busy.isEmpty())
        assertEquals("paused", vm.state.value.jobs!!.first { it.id == 1L }.status)
        assertEquals("queued", vm.state.value.jobs!!.first { it.id == 2L }.status)
        api.jobs = vm.state.value.jobs!!
        vm.resume(1); runCurrent()
        assertEquals("running", vm.state.value.jobs!!.first { it.id == 1L }.status)
        vm.cancel(2); runCurrent()
        assertEquals("cancelled", vm.state.value.jobs!!.first { it.id == 2L }.status)
        assertEquals(listOf("pause1", "resume1", "cancel2"), api.actions)
        assertNotNull(vm.state.value.jobs)
    }
}
