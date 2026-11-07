package com.readerapp.ui.discover

import androidx.lifecycle.SavedStateHandle
import com.readerapp.data.db.Novel
import com.readerapp.search.ConnectionResult
import com.readerapp.search.CrawlJob
import com.readerapp.search.CrawlRequest
import com.readerapp.search.LibraryUploadRequest
import com.readerapp.search.LibraryUploadResult
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.FavoriteDto
import com.readerapp.search.FavoritesResponse
import com.readerapp.search.NovelPageImporter
import com.readerapp.search.NovelPageStore
import com.readerapp.search.SearchException
import com.readerapp.search.SearchFilters
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.SimilarRequest
import com.readerapp.search.SimilarResult
import com.readerapp.search.SimilarSearch
import com.readerapp.search.SimilarSeed
import com.readerapp.search.SimilarStage
import com.readerapp.search.SourceUrl
import com.readerapp.search.Work
import com.readerapp.search.history.MemSearchHistoryStore
import com.readerapp.search.history.SearchHistoryRepository
import com.readerapp.search.history.SearchHistorySyncTest
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
    private val noSimilar = SimilarSearch { _, _ -> error("unset") }

    private val historyStore = MemSearchHistoryStore()
    private var syncRequests = 0
    private val historyServer = SearchHistorySyncTest.FakeServer()
    private fun history() = SearchHistoryRepository(historyStore, historyServer) { syncRequests++ }

    private class FakeApi : DiscoveryApi {
        val requests = mutableListOf<SearchRequest>()
        var gate: CompletableDeferred<Unit>? = null
        var next: () -> SearchResponse = { error("unset") }
        var titleResults: List<Work> = emptyList()
        var workResult: () -> Work = { throw SearchException.NotFound() }
        override suspend fun search(request: SearchRequest): SearchResponse { requests += request; gate?.await(); return next() }
        override suspend fun similar(request: SimilarRequest): SearchResponse = throw UnsupportedOperationException()
        override suspend fun work(workId: String) = workResult()
        override suspend fun searchTitle(query: String, limit: Int) = titleResults
        override suspend fun testConnection() = ConnectionResult.NotConfigured
        override suspend fun startCrawl(request: CrawlRequest): CrawlJob = throw UnsupportedOperationException()
        override suspend fun crawlJobs(): List<CrawlJob> = emptyList()
        override suspend fun pauseCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun resumeCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun cancelCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun uploadLibraryChapters(request: LibraryUploadRequest) = LibraryUploadResult()
        override suspend fun putFavorite(favorite: FavoriteDto): FavoriteDto = throw UnsupportedOperationException()
        override suspend fun favorites(since: String?): FavoritesResponse = throw UnsupportedOperationException()
        override suspend fun searches(since: String?, limit: Int): com.readerapp.search.SearchListResponse = throw UnsupportedOperationException()
        override suspend fun searchEntry(id: String): com.readerapp.search.SearchEntryDto = throw UnsupportedOperationException()
        override suspend fun deleteSearch(id: String) = throw UnsupportedOperationException()
    }

    @Test fun loadingThenResultsWithEditableQueryAndLibraryBadge() = runTest {
        val api = FakeApi().apply { next = { response }; gate = CompletableDeferred() }
        val lib = Novel(id = 9, title = "Book", sourceUrl = "https://royalroad.com/c/1", domain = "royalroad.com")
        val vm = DiscoverViewModel(api, { flowOf(listOf(lib)) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
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
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
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
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
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
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.setMode(DiscoverMode.TITLE); vm.setTitleQuery("boo"); vm.searchTitle()
        assertEquals(listOf(work), vm.state.value.results)
        assertNull(vm.state.value.query)
    }

    @Test fun libraryChangesUpdateBadgesLive() = runTest {
        val api = FakeApi().apply { next = { response } }
        val lib = MutableStateFlow(emptyList<Novel>())
        val vm = DiscoverViewModel(api, { lib }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.setDescription("x"); vm.search()
        assertTrue(vm.state.value.inLibrary.isEmpty())
        lib.value = listOf(Novel(id = 4, title = "Book", sourceUrl = "https://royalroad.com/fiction/1", domain = "royalroad.com"))
        assertEquals(mapOf("w1" to 4L), vm.state.value.inLibrary)
    }

    @Test fun everySearchIsSavedUnderTheServersHistoryIdAndListedNewestFirst() = runTest {
        var n = 0
        val api = FakeApi().apply { next = { response.copy(historyId = "h${++n}") } }
        val repo = history().apply { clock = { 1_000L * (n + 1) } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, repo, { it }, noSimilar, SavedStateHandle())
        vm.setDescription("first"); vm.search()
        vm.setDescription("second"); vm.search()
        assertEquals(listOf("h2", "h1"), vm.state.value.recent.map { it.id })
        assertEquals(listOf("second", "first"), vm.state.value.recent.map { it.description })
        assertEquals(1, vm.state.value.recent[0].resultCount)
        assertNull(vm.state.value.viewingSaved)
    }

    @Test fun aFailedSearchIsNotSaved() = runTest {
        val api = FakeApi().apply { next = { throw SearchException.Network(null) } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.setDescription("x"); vm.search()
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test fun openingASavedSearchShowsItsResultsWithoutTheServer() = runTest {
        val api = FakeApi().apply { next = { response.copy(historyId = "h1") } }
        val lib = Novel(id = 9, title = "Book", sourceUrl = "https://royalroad.com/c/1", domain = "royalroad.com")
        val vm = DiscoverViewModel(api, { flowOf(listOf(lib)) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.setDescription("dark mystery"); vm.search()
        vm.setDescription(""); vm.searchTitle()           // something else is on screen now
        api.next = { error("offline") }
        api.requests.clear()
        vm.openSaved("h1")
        val s = vm.state.value
        assertEquals(listOf(work), s.results); assertEquals("dark mystery", s.description)
        assertEquals(mapOf("w1" to 9L), s.inLibrary)     // library badges still apply to saved results
        assertEquals("ko", s.query!!.language); assertTrue(s.reranked)
        assertEquals("h1", s.viewingSaved!!.id); assertNull(s.error); assertFalse(s.loading)
        assertTrue(api.requests.isEmpty())
    }

    @Test fun searchAgainRerunsTheSavedRequestAndSavesANewEntry() = runTest {
        var n = 0
        val api = FakeApi().apply { next = { response.copy(historyId = "h${++n}") } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.setDescription("dark mystery"); vm.search()
        vm.openSaved("h1")
        vm.searchSavedAgain()
        assertEquals(listOf(SearchRequest("dark mystery"), SearchRequest("dark mystery")), api.requests)
        assertEquals(listOf("h2", "h1"), vm.state.value.recent.map { it.id }.sortedDescending())
        assertEquals(2, vm.state.value.recent.size)
        assertNull(vm.state.value.viewingSaved)           // now showing the fresh answer
    }

    @Test fun editedSearchesAreReplayedAsTheyWereSent() = runTest {
        var n = 0
        val api = FakeApi().apply { next = { response.copy(historyId = "h${++n}") } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.setDescription("orig"); vm.search()
        vm.editQuery { copy(synopsis = "edited syn") }
        vm.searchAgain()                                   // saved as h2 with the edited parts
        vm.openSaved("h2"); vm.searchSavedAgain()
        assertEquals("edited syn", api.requests.last().synopsis)
        assertEquals("orig", api.requests.last().description)
    }

    @Test fun aSavedSearchThatIsOnlyAListRowNeedsTheServerAndReportsWhenOffline() = runTest {
        historyStore.put(com.readerapp.data.db.SearchHistoryEntry("r", "remote", 1_000, 1))
        historyServer.entryFailure = { SearchException.Network(null) }
        val vm = DiscoverViewModel(FakeApi(), { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.openSaved("r")
        assertTrue(vm.state.value.error!!.contains("Cannot reach"))
        assertNull(vm.state.value.results)
        historyServer.entryFailure = null
        historyServer.add("r", description = "remote", results = listOf(work))
        vm.openSaved("r")
        assertEquals(listOf(work), vm.state.value.results); assertNull(vm.state.value.error)
    }

    @Test fun openingAMissingEntryReportsIt() = runTest {
        val vm = DiscoverViewModel(FakeApi(), { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.openSaved("nope")
        assertTrue(vm.state.value.error!!.contains("no longer"))
    }

    @Test fun deleteAndClearRemoveEntriesAndAskForASync() = runTest {
        var n = 0
        val api = FakeApi().apply { next = { response.copy(historyId = "h${++n}") } }
        val repo = history().apply { var t = 0L; clock = { ++t } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, repo, { it }, noSimilar, SavedStateHandle())
        listOf("a", "b", "c").forEach { vm.setDescription(it); vm.search() }
        vm.openSaved("h2")
        vm.deleteSaved("h2")
        assertEquals(listOf("h3", "h1"), vm.state.value.recent.map { it.id })
        assertNull(vm.state.value.viewingSaved); assertEquals(1, syncRequests)
        vm.clearHistory()
        assertTrue(vm.state.value.recent.isEmpty()); assertEquals(2, syncRequests)
    }

    @Test fun suggestionsMatchWhatIsTypedAgainstPreviousSearches() = runTest {
        var n = 0
        val api = FakeApi().apply { next = { response.copy(historyId = "h${++n}") } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history().apply { var t = 0L; clock = { ++t } }, { it }, noSimilar, SavedStateHandle())
        listOf("Dragon cook restaurant", "dark mystery", "a DRAGON prince").forEach { vm.setDescription(it); vm.search() }
        vm.setDescription("dragon")
        assertEquals(listOf("a DRAGON prince", "Dragon cook restaurant"), vm.state.value.suggestions.map { it.description })
        vm.setDescription("  ")
        assertTrue(vm.state.value.suggestions.isEmpty())
        vm.setDescription("dark mystery")                 // exactly what was already searched is not suggested again
        assertTrue(vm.state.value.suggestions.isEmpty())
        vm.setDescription("zzz")
        assertTrue(vm.state.value.suggestions.isEmpty())
    }

    @Test fun similarSearchShowsStagesThenResultsHeaderAndSavesHistory() = runTest {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<SimilarStage?>()
        val seeds = mutableListOf<SimilarSeed>()
        val lib = Novel(id = 9, title = "Book", sourceUrl = "https://royalroad.com/c/1", domain = "royalroad.com")
        lateinit var vm: DiscoverViewModel
        val similar = SimilarSearch { seed, onStage ->
            seeds += seed
            onStage(SimilarStage.PREPARING); seen += vm.state.value.stage
            onStage(SimilarStage.UPLOADING); seen += vm.state.value.stage
            onStage(SimilarStage.SEARCHING); seen += vm.state.value.stage
            gate.await()
            SimilarResult(response.copy(historyId = "s1", notes = listOf("note")), "Lord of Mysteries")
        }
        vm = DiscoverViewModel(FakeApi(), { flowOf(listOf(lib)) }, noPrefetch, history(), { it }, similar, SavedStateHandle())
        vm.findSimilar(SimilarSeed.LibraryNovel(5))
        assertTrue(vm.state.value.loading)
        assertEquals(listOf<SimilarStage?>(SimilarStage.PREPARING, SimilarStage.UPLOADING, SimilarStage.SEARCHING), seen)
        gate.complete(Unit)
        val s = vm.state.value
        assertFalse(s.loading); assertNull(s.stage)
        assertEquals("Lord of Mysteries", s.similarTitle)
        assertEquals("Similar to Lord of Mysteries", s.description)   // the query box shows the label
        assertEquals(listOf(work), s.results); assertEquals(listOf("note"), s.notes)
        assertNull(s.query)                                             // nothing to edit as a description search
        assertEquals(mapOf("w1" to 9L), s.inLibrary)
        assertEquals("Similar to Lord of Mysteries", s.recent.single().description)
        vm.findSimilar(SimilarSeed.LibraryNovel(5))                    // same seed again (e.g. rotation): ignored
        assertEquals(1, seeds.size)
    }

    @Test fun editingTheSimilarQueryBoxRunsANormalSearchAndUneditedSearchRepeatsTheSimilarOne() = runTest {
        val api = FakeApi().apply { next = { response } }
        var runs = 0
        val similar = SimilarSearch { _, _ -> runs++; SimilarResult(response, "Seed") }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, similar, SavedStateHandle())
        vm.findSimilar(SimilarSeed.CorpusWork("w9"))
        vm.search()                                                     // box untouched: same similar search again
        assertEquals(2, runs); assertTrue(api.requests.isEmpty())
        vm.setDescription("Similar to Seed but darker")
        vm.search()
        assertEquals(listOf(SearchRequest("Similar to Seed but darker")), api.requests)
        assertNull(vm.state.value.similarTitle); assertNotNull(vm.state.value.query)
    }

    @Test fun similarErrorsSurfaceAndClearTheStage() = runTest {
        var fail = true
        val similar = SimilarSearch { _, onStage ->
            onStage(SimilarStage.UPLOADING)
            if (fail) throw SearchException.NotConfigured()
            SimilarResult(response, "Seed")
        }
        val vm = DiscoverViewModel(FakeApi(), { flowOf(emptyList()) }, noPrefetch, history(), { it }, similar, SavedStateHandle())
        vm.findSimilar(SimilarSeed.LibraryNovel(1))
        val s = vm.state.value
        assertTrue(s.error!!.contains("Settings")); assertFalse(s.loading); assertNull(s.stage); assertNull(s.results)
        assertTrue(s.retrySimilar)
        fail = false
        vm.retrySimilar()                                               // the screen's Retry action
        assertNull(vm.state.value.error); assertFalse(vm.state.value.retrySimilar); assertEquals("Seed", vm.state.value.similarTitle)
    }

    @Test fun theHandledSeedSurvivesRecreationSoTheSearchIsNotRunAgain() = runTest {
        var runs = 0
        val similar = SimilarSearch { _, _ -> runs++; SimilarResult(response, "Seed") }
        val handle = SavedStateHandle()
        val seed = SimilarSeed.LibraryNovel(3, 7)
        DiscoverViewModel(FakeApi(), { flowOf(emptyList()) }, noPrefetch, history(), { it }, similar, handle).findSimilar(seed)
        // Process death: a new view model gets the saved handle, the screen asks again with the same route arguments.
        val restored = DiscoverViewModel(FakeApi(), { flowOf(emptyList()) }, noPrefetch, history(), { it }, similar, handle)
        restored.findSimilar(seed)
        assertEquals(1, runs); assertNull(restored.state.value.results)
        restored.findSimilar(SimilarSeed.LibraryNovel(4))                // a different seed does run
        assertEquals(2, runs)
    }

    @Test fun aNormalSearchStartingWithSimilarToIsNotSimilarMode() = runTest {
        val api = FakeApi().apply { next = { response.copy(historyId = "n1") } }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, noSimilar, SavedStateHandle())
        vm.setDescription("Similar to Harry Potter but with dragons"); vm.search()
        vm.openSaved("n1")
        assertNull(vm.state.value.similarTitle); assertNotNull(vm.state.value.query)
        vm.searchSavedAgain()
        assertEquals("Similar to Harry Potter but with dragons", api.requests.last().description)
    }

    @Test fun openingARealSimilarEntryThenSearchingRepeatsTheSimilarSearchNotADescriptionSearch() = runTest {
        val sent = mutableListOf<SimilarSeed>()
        val api = FakeApi()
        val similar = SimilarSearch { seed, _ ->
            sent += seed
            SimilarResult(response.copy(historyId = "s${sent.size}"), "Seed", (seed as? SimilarSeed.CorpusWork)?.let { SimilarRequest(workId = it.workId) } ?: (seed as SimilarSeed.Replay).request)
        }
        val vm = DiscoverViewModel(api, { flowOf(emptyList()) }, noPrefetch, history(), { it }, similar, SavedStateHandle())
        vm.findSimilar(SimilarSeed.CorpusWork("w9"))
        vm.openSaved("s1")
        assertEquals("Seed", vm.state.value.similarTitle); assertNull(vm.state.value.query)
        vm.search()                                                      // untouched box
        assertEquals(SimilarSeed.Replay(SimilarRequest(workId = "w9")), sent.last())
        vm.openSaved("s2"); vm.searchSavedAgain()
        assertEquals(3, sent.size)
        assertTrue(api.requests.isEmpty())
    }

    @Test fun aSavedSimilarSearchOpensFromHistoryWithItsHeaderAndNoEditableQuery() = runTest {
        val similar = SimilarSearch { _, _ -> SimilarResult(response.copy(historyId = "s1"), "Seed", SimilarRequest(workId = "w9")) }
        val vm = DiscoverViewModel(FakeApi(), { flowOf(emptyList()) }, noPrefetch, history(), { it }, similar, SavedStateHandle())
        vm.findSimilar(SimilarSeed.CorpusWork("w9"))
        vm.setDescription(""); vm.searchTitle()
        vm.openSaved("s1")
        val s = vm.state.value
        assertEquals("Seed", s.similarTitle); assertEquals(listOf(work), s.results); assertNull(s.query); assertEquals("s1", s.viewingSaved!!.id)
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
