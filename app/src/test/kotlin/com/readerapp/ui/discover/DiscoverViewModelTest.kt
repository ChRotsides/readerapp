package com.readerapp.ui.discover

import androidx.lifecycle.SavedStateHandle
import com.readerapp.data.db.Novel
import com.readerapp.search.ConnectionResult
import com.readerapp.search.CrawlJob
import com.readerapp.search.CrawlRequest
import com.readerapp.search.LibraryUploadRequest
import com.readerapp.search.LibraryUploadResult
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.NovelPageImporter
import com.readerapp.search.NovelPageStore
import com.readerapp.search.SearchException
import com.readerapp.search.SearchFilters
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.SourceUrl
import com.readerapp.search.Work
import com.readerapp.scrape.toc.TocResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class DiscoverViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val work = Work(workId = "w1", title = "Book", urls = listOf(SourceUrl("rr", "https://royalroad.com/fiction/1")), reason = "fits")
    private val response = SearchResponse(
        results = listOf(work), scenes = listOf("s1", "s2"), synopsis = "syn", tags = listOf("a", "b"),
        filters = SearchFilters(language = "ko", minChapters = 100, excludeTags = listOf("harem")), reranked = true,
    )

    private val noPrefetch = com.readerapp.offline.SearchResultPrefetch { }

    private class FakeApi : DiscoveryApi {
        val requests = mutableListOf<SearchRequest>()
        var gate: CompletableDeferred<Unit>? = null
        var next: () -> SearchResponse = { error("unset") }
        var titleResults: List<Work> = emptyList()
        var workResult: () -> Work = { throw SearchException.NotFound() }
        override suspend fun search(request: SearchRequest): SearchResponse { requests += request; gate?.await(); return next() }
        override suspend fun work(workId: String) = workResult()
        override suspend fun searchTitle(query: String, limit: Int) = titleResults
        override suspend fun testConnection() = ConnectionResult.NotConfigured
        override suspend fun startCrawl(request: CrawlRequest): CrawlJob = throw UnsupportedOperationException()
        override suspend fun crawlJobs(): List<CrawlJob> = emptyList()
        override suspend fun pauseCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun resumeCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun cancelCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun uploadLibraryChapters(request: LibraryUploadRequest) = LibraryUploadResult()
    }

    @Test fun loadingThenResultsWithEditableQueryAndLibraryBadge() = runTest {
        val api = FakeApi().apply { next = { response }; gate = CompletableDeferred() }
        val lib = Novel(id = 9, title = "Book", sourceUrl = "https://royalroad.com/c/1", domain = "royalroad.com")
        val vm = DiscoverViewModel(api, { flowOf(listOf(lib)) }, noPrefetch)
        vm.setDescription(" dark mystery ")
        vm.search()
        assertTrue(vm.state.value.loading)
        api.gate!!.complete(Unit)
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf(work), s.results)
        assertEquals(mapOf("w1" to 9L), s.inLibrary)
        assertEquals("ko", s.query!!.language); assertEquals("100", s.query!!.minChapters); assertEquals("a, b", s.query!!.tags)
        assertEquals(SearchRequest("dark mystery"), api.requests.single())
    }

    @Test fun errorsSurfaceAndClear() = runTest {
        val api = FakeApi().apply { next = { throw SearchException.BudgetReached(null) } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch)
        vm.setDescription("x"); vm.search()
        assertTrue(vm.state.value.error!!.contains("budget"))
        assertFalse(vm.state.value.loading)
        api.next = { response }
        vm.search()
        assertNull(vm.state.value.error)
        assertNotNull(vm.state.value.results)
    }

    @Test fun editAndSearchAgainSendsEditedPartsWithOriginalDescription() = runTest {
        val api = FakeApi().apply { next = { response } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch)
        vm.setDescription("original"); vm.search()
        vm.setDescription("changed box text after search")
        vm.editQuery { copy(scenes = listOf("edited scene", "  ", "s2"), synopsis = "new syn", tags = "x, y ,", language = "", excludeTags = "gore, harem") }
        vm.searchAgain()
        val req = api.requests.last()
        assertEquals("original", req.description)
        assertEquals(listOf("edited scene", "s2"), req.scenes)
        assertEquals("new syn", req.synopsis)
        assertEquals(listOf("x", "y"), req.tags)
        assertEquals(SearchFilters(language = null, status = null, minChapters = 100, maxChapters = null, excludeTags = listOf("gore", "harem")), req.filters)
        assertEquals(2, api.requests.size)
    }

    @Test fun titleSearchShowsResultsWithoutEditableQuery() = runTest {
        val api = FakeApi().apply { titleResults = listOf(work) }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch)
        vm.setMode(DiscoverMode.TITLE); vm.setTitleQuery("boo"); vm.searchTitle()
        assertEquals(listOf(work), vm.state.value.results)
        assertNull(vm.state.value.query)
    }

    @Test fun libraryChangesUpdateBadgesLive() = runTest {
        val api = FakeApi().apply { next = { response } }
        val lib = MutableStateFlow(emptyList<Novel>())
        val vm = DiscoverViewModel(api, { lib }, noPrefetch)
        vm.setDescription("x"); vm.search()
        assertTrue(vm.state.value.inLibrary.isEmpty())
        lib.value = listOf(Novel(id = 4, title = "Book", sourceUrl = "https://royalroad.com/fiction/1", domain = "royalroad.com"))
        assertEquals(mapOf("w1" to 4L), vm.state.value.inLibrary)
    }

    @Test fun workViewModelLoadsAndRoutesAddToLibrary() = runTest {
        val api = FakeApi().apply { workResult = { work } }
        val created = mutableListOf<Novel>()
        val store = object : NovelPageStore {
            override suspend fun findExisting(domain: String, url: String, title: String): Novel? = null
            override suspend fun create(novel: Novel): Long { created += novel; return 11 }
        }
        val importer = NovelPageImporter(store, { TocResult.NoAdapter })
        val vm = WorkViewModel(SavedStateHandle(mapOf("workId" to "w1")), api, { flowOf(emptyList()) }, importer, {})
        assertEquals("Book", vm.state.value.work?.title)
        vm.addToLibrary("https://royalroad.com/fiction/1")
        val ev = vm.state.value.event as WorkEvent.OpenBrowser
        assertEquals("https://royalroad.com/fiction/1", ev.url)
        assertEquals("Book", created.single().title)
        vm.eventHandled()
        assertNull(vm.state.value.event)
    }

    @Test fun workViewModelErrorState() = runTest {
        val vm = WorkViewModel(SavedStateHandle(mapOf("workId" to "zz")), FakeApi(), { flowOf(emptyList()) },
            NovelPageImporter(object : NovelPageStore {
                override suspend fun findExisting(domain: String, url: String, title: String): Novel? = null
                override suspend fun create(novel: Novel) = 1L
            }, { TocResult.NoAdapter }), {})
        assertTrue(vm.state.value.error!!.contains("404"))
    }
}
