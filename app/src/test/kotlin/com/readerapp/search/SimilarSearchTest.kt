package com.readerapp.search

import com.readerapp.data.db.Chapter
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.db.ChapterText
import com.readerapp.data.db.Novel
import com.readerapp.data.db.TextVariant
import com.readerapp.data.db.UploadedChapter
import com.readerapp.data.settings.SearchTuning
import com.readerapp.sync.UploadItem
import com.readerapp.sync.UploadStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SimilarSearchTest {
    @Test fun seedChaptersAreTheOpeningOnesPlusThePreviousAndCurrentChapter() {
        val all = (0..100).toList()
        assertEquals(listOf(0, 1, 2, 49, 50), SimilarSeedChapters.indexes(all, 50))
        assertEquals(listOf(0, 1, 2, 3), SimilarSeedChapters.indexes(all, 3))         // previous 2 is already an opening one
        assertEquals(listOf(0, 1, 2), SimilarSeedChapters.indexes(all, 1))            // current inside the opening: deduped
        assertEquals(listOf(0, 1, 2), SimilarSeedChapters.indexes(all, null))
    }

    @Test fun seedChaptersAreClampedToWhatExists() {
        assertEquals(listOf(0, 1), SimilarSeedChapters.indexes(listOf(0, 1), 1))
        assertEquals(listOf(0), SimilarSeedChapters.indexes(listOf(0), 0))
        assertEquals(listOf(0, 1, 2, 5, 10), SimilarSeedChapters.indexes(listOf(0, 1, 2, 5, 10), 10))   // previous = the one before it that exists
        assertEquals(listOf(0, 1, 2), SimilarSeedChapters.indexes(listOf(0, 1, 2), 99))                 // current not among the chapters
        assertTrue(SimilarSeedChapters.indexes(emptyList(), 4).isEmpty())
    }

    @Test fun openingChaptersAreTheLowestNonNegativeIndexesNotLiteralZeroOneTwo() {
        // Imported mid-story: no chapter 0; negative indexes (chapters found before the first) are never "opening".
        assertEquals(listOf(40, 41, 42, 59, 60), SimilarSeedChapters.indexes((40..60).toList(), 60))
        assertEquals(listOf(5, 6, 7), SimilarSeedChapters.indexes(listOf(-2, -1, 5, 6, 7, 8, 9), null))
        assertEquals(listOf(-2, -1, 5, 6, 7), SimilarSeedChapters.indexes(listOf(-2, -1, 5, 6, 7, 8), -1))
    }

    @Test fun requestSerializesTheContractFieldsAndOmitsNulls() {
        val json = ServerApi.json
        val lib = json.encodeToString(SimilarRequest.serializer(), SimilarRequest(libraryUrl = "https://x/n", chapters = listOf(0, 1, 49, 50), sceneCount = 3, rerankPool = 20, rerankProvider = "jev"))
        assertEquals(
            """{"library_url":"https://x/n","chapters":[0,1,49,50],"hide_library":true,"scene_count":3,"rerank_pool":20,"rerank_provider":"jev","limit":20}""", lib,
        )
        val work = json.encodeToString(SimilarRequest.serializer(), SimilarRequest(workId = "w1", hideLibrary = false, filters = SearchFilters(language = "ko")))
        assertTrue(work, work.startsWith("""{"work_id":"w1","hide_library":false,"filters":{"language":"ko""""))
        assertFalse(work, work.contains("library_url") || work.contains("\"chapters\""))
    }

    @Test fun responseParsesSeedTitleAndDefaultsItToNull() {
        val json = ServerApi.json
        assertEquals("Seed", json.decodeFromString(SearchResponse.serializer(), """{"results":[],"seed_title":"Seed"}""").seedTitle)
        assertNull(json.decodeFromString(SearchResponse.serializer(), """{"results":[]}""").seedTitle)
    }

    @Test fun apiPostsToSimilarWithBearerAndMaps409ToHttpError() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            val api = ServerApi({ ServerConfig(server.url("/").toString().trimEnd('/'), "tok") })
            server.enqueue(MockResponse().setBody("""{"results":[],"seed_title":"Seed"}"""))
            assertEquals("Seed", api.similar(SimilarRequest(workId = "w1")).seedTitle)
            val req = server.takeRequest()
            assertEquals("POST", req.method); assertEquals("/similar", req.path); assertEquals("Bearer tok", req.getHeader("Authorization"))
            assertTrue(req.body.readUtf8().contains("\"work_id\":\"w1\""))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"seed has no embedded chapters"}"""))
            try { api.similar(SimilarRequest(libraryUrl = "u")); fail() } catch (e: SearchException.Http) {
                assertEquals(409, e.code); assertTrue(e.message!!.contains("no embedded chapters"))
            }
        } finally { server.shutdown() }
    }

    // --- SimilarFinder ---

    private val novel = Novel(id = 1, title = "My Novel", sourceUrl = "https://x.example/c/1", domain = "x.example", novelUrl = "https://x.example/n/1", lastReadChapterId = 51)

    /** Chapters with ids 1..N and indexes 0..N-1 (so id = index + 1). */
    private fun chapters(n: Int) = (0 until n).map { Chapter(id = it + 1L, novelId = 1, index = it, title = "T$it", url = "https://x.example/c/$it", status = ChapterStatus.NOT_FETCHED) }

    private class FakeSource(val novel: Novel?, val chapters: List<Chapter>, val failing: Set<Long> = emptySet()) : SimilarSource {
        val downloaded = mutableListOf<Long>()
        override suspend fun novel(novelId: Long) = novel
        override suspend fun chapters(novelId: Long) = chapters
        override suspend fun ensureDownloaded(chapter: Chapter): Boolean {
            if (chapter.id in failing) return false
            downloaded += chapter.id
            return true
        }
    }

    private class FakeStore(val novel: Novel, val chapters: List<Chapter>) : UploadStore {
        val recorded = HashMap<Long, UploadedChapter>()
        override suspend fun pendingChapterIds(): List<Long> = emptyList()
        override suspend fun item(chapterId: Long): UploadItem? = chapters.firstOrNull { it.id == chapterId }?.let {
            UploadItem(it.id, novel, it.index, it.title, it.url, listOf(ChapterText(it.id, TextVariant.ORIGINAL, listOf("text ${it.index}"))), recorded[it.id]?.hash)
        }
        override suspend fun record(rows: List<UploadedChapter>) { rows.forEach { recorded[it.chapterId] = it } }
    }

    private class FakeApi : DiscoveryApi by NoApi {
        val uploads = mutableListOf<LibraryUploadRequest>()
        val similarRequests = mutableListOf<SimilarRequest>()
        /** Answers /similar; receives how many uploads happened so far. */
        var onSimilar: (SimilarRequest, Int) -> SearchResponse = { _, _ -> SearchResponse() }
        var refuseUploads = false
        override suspend fun uploadLibraryChapters(request: LibraryUploadRequest): LibraryUploadResult {
            if (refuseUploads) throw SearchException.Http(422, "bad")
            uploads += request; return LibraryUploadResult(embedded = request.chapters.size) }
        override suspend fun similar(request: SimilarRequest): SearchResponse { similarRequests += request; return onSimilar(request, uploads.size) }
    }

    private object NoApi : DiscoveryApi {
        override suspend fun search(request: SearchRequest): SearchResponse = throw UnsupportedOperationException()
        override suspend fun similar(request: SimilarRequest): SearchResponse = throw UnsupportedOperationException()
        override suspend fun work(workId: String): Work = throw UnsupportedOperationException()
        override suspend fun searchTitle(query: String, limit: Int): List<Work> = throw UnsupportedOperationException()
        override suspend fun testConnection(): ConnectionResult = throw UnsupportedOperationException()
        override suspend fun startCrawl(request: CrawlRequest): CrawlJob = throw UnsupportedOperationException()
        override suspend fun crawlJobs(): List<CrawlJob> = throw UnsupportedOperationException()
        override suspend fun pauseCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun resumeCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun cancelCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun uploadLibraryChapters(request: LibraryUploadRequest): LibraryUploadResult = throw UnsupportedOperationException()
        override suspend fun putFavorite(favorite: FavoriteDto): FavoriteDto = throw UnsupportedOperationException()
        override suspend fun favorites(since: String?): FavoritesResponse = throw UnsupportedOperationException()
        override suspend fun searches(since: String?, limit: Int): SearchListResponse = throw UnsupportedOperationException()
        override suspend fun searchEntry(id: String): SearchEntryDto = throw UnsupportedOperationException()
        override suspend fun deleteSearch(id: String) = throw UnsupportedOperationException()
    }

    private var uploadsOn = true
    private val configured = ServerConfigProvider { ServerConfig("http://s", "t") }
    private val tuning = object : SearchTuning {
        override suspend fun apply(request: SearchRequest) = request
        override suspend fun applySimilar(request: SimilarRequest) = request.copy(sceneCount = 3, rerankPool = 20, rerankProvider = "jev")
    }

    private fun finder(api: FakeApi, source: FakeSource, n: Int = 100, config: ServerConfigProvider = configured): Pair<SimilarFinder, FakeStore> {
        val store = FakeStore(novel, chapters(n))
        return SimilarFinder(source, store, api, config, tuning) { uploadsOn } to store
    }

    @Test fun libraryNovelDownloadsUploadsThenSearchesWithTheSameUrlAndIndexes() = runTest {
        val api = FakeApi().apply { onSimilar = { _, _ -> SearchResponse(seedTitle = "Server Title") } }
        val source = FakeSource(novel, chapters(100))
        val (finder, store) = finder(api, source)
        val stages = mutableListOf<SimilarStage>()
        val result = finder.find(SimilarSeed.LibraryNovel(1), stages::add)
        // lastReadChapterId 51 = index 50: opening 0,1,2 + previous 49 + current 50
        assertEquals(listOf(1L, 2L, 3L, 50L, 51L), source.downloaded)
        assertEquals(listOf(SimilarStage.PREPARING, SimilarStage.UPLOADING, SimilarStage.SEARCHING), stages)
        assertEquals(listOf(0, 1, 2, 49, 50), api.uploads.single().chapters.map { it.index })
        assertEquals("https://x.example/n/1", api.uploads.single().novel.url)
        val req = api.similarRequests.single()
        assertEquals("https://x.example/n/1", req.libraryUrl); assertNull(req.workId)
        assertEquals(listOf(0, 1, 2, 49, 50), req.chapters)
        assertEquals(3, req.sceneCount); assertEquals("jev", req.rerankProvider)   // tuning applied
        assertEquals("Server Title", result.seedTitle)
        assertEquals(setOf(1L, 2L, 3L, 50L, 51L), store.recorded.keys)             // recorded like a normal upload
    }

    @Test fun explicitCurrentChapterOverridesTheSavedPositionAndSeedChaptersAreAlwaysSentAgain() = runTest {
        val api = FakeApi()
        val (finder, _) = finder(api, FakeSource(novel, chapters(100)))
        val r = finder.find(SimilarSeed.LibraryNovel(1, currentChapterId = 11)) { }
        assertEquals(listOf(0, 1, 2, 9, 10), api.similarRequests.single().chapters)
        assertEquals("My Novel", r.seedTitle)                                       // falls back to the library title
        finder.find(SimilarSeed.LibraryNovel(1, currentChapterId = 11)) { }
        assertEquals("seed chapters are always sent (the novel url may have changed since)", 2, api.uploads.size)
        assertEquals(2, api.similarRequests.size)
    }

    @Test fun a409UploadsAgainOnceAndRetries() = runTest {
        val api = FakeApi().apply {
            onSimilar = { _, uploads -> if (uploads < 2) throw SearchException.Http(409, "seed has no embedded chapters") else SearchResponse(seedTitle = "ok") }
        }
        val (finder, _) = finder(api, FakeSource(novel, chapters(100)))
        val stages = mutableListOf<SimilarStage>()
        val result = finder.find(SimilarSeed.LibraryNovel(1), stages::add)
        assertEquals("ok", result.seedTitle)
        assertEquals(2, api.uploads.size)                                           // forced resend although recorded
        assertEquals(2, api.similarRequests.size)
        assertEquals(listOf(SimilarStage.PREPARING, SimilarStage.UPLOADING, SimilarStage.SEARCHING, SimilarStage.UPLOADING, SimilarStage.SEARCHING), stages)
    }

    @Test fun aSecondConflictOrAnotherErrorIsNotRetriedFurther() = runTest {
        val always409 = FakeApi().apply { onSimilar = { _, _ -> throw SearchException.Http(409, "seed has no embedded chapters") } }
        try { finder(always409, FakeSource(novel, chapters(100))).first.find(SimilarSeed.LibraryNovel(1)) { }; fail() } catch (e: SearchException.SeedNotOnServer) { }
        assertEquals(2, always409.similarRequests.size)
        val budget = FakeApi().apply { onSimilar = { _, _ -> throw SearchException.BudgetReached(null) } }
        try { finder(budget, FakeSource(novel, chapters(100))).first.find(SimilarSeed.LibraryNovel(1)) { }; fail() } catch (e: SearchException.BudgetReached) { }
        assertEquals(1, budget.similarRequests.size)
        assertEquals(1, budget.uploads.size)
    }

    @Test fun chaptersThatFailToDownloadAreSkippedAndNoneAtAllIsAnError() = runTest {
        val api = FakeApi()
        val (finder, _) = finder(api, FakeSource(novel, chapters(100), failing = setOf(2L, 51L)))
        finder.find(SimilarSeed.LibraryNovel(1)) { }
        assertEquals(listOf(0, 2, 49), api.similarRequests.single().chapters)
        val none = FakeApi()
        try { finder(none, FakeSource(novel, chapters(100), failing = (1L..100L).toSet())).first.find(SimilarSeed.LibraryNovel(1)) { }; fail() } catch (e: SearchException.NoSeedChapters) { }
        assertTrue(none.uploads.isEmpty() && none.similarRequests.isEmpty())
    }

    @Test fun corpusWorkSearchesDirectlyWithoutUploading() = runTest {
        val api = FakeApi().apply { onSimilar = { _, _ -> SearchResponse(seedTitle = "Corpus Book") } }
        val source = FakeSource(null, emptyList())
        val (finder, _) = finder(api, source)
        val stages = mutableListOf<SimilarStage>()
        val r = finder.find(SimilarSeed.CorpusWork("w7"), stages::add)
        assertEquals("Corpus Book", r.seedTitle)
        assertEquals(listOf(SimilarStage.SEARCHING), stages)
        val req = api.similarRequests.single()
        assertEquals("w7", req.workId); assertNull(req.libraryUrl); assertNull(req.chapters); assertEquals(20, req.rerankPool)
        assertTrue(api.uploads.isEmpty() && source.downloaded.isEmpty())
    }

    @Test fun notConfiguredFailsBeforeAnythingIsDownloaded() = runTest {
        val source = FakeSource(novel, chapters(10))
        try { finder(FakeApi(), source, config = { null }).first.find(SimilarSeed.LibraryNovel(1)) { }; fail() } catch (e: SearchException.NotConfigured) { }
        assertTrue(source.downloaded.isEmpty())
    }

    @Test fun a404ForALibrarySeedIsRetriedOnceAfterAForcedUploadThenReportedAsNotOnServer() = runTest {
        val flaky = FakeApi().apply { onSimilar = { _, uploads -> if (uploads < 2) throw SearchException.NotFound() else SearchResponse(seedTitle = "ok") } }
        assertEquals("ok", finder(flaky, FakeSource(novel, chapters(100))).first.find(SimilarSeed.LibraryNovel(1)) { }.seedTitle)
        assertEquals(2, flaky.uploads.size)
        val gone = FakeApi().apply { onSimilar = { _, _ -> throw SearchException.NotFound() } }
        try { finder(gone, FakeSource(novel, chapters(100))).first.find(SimilarSeed.LibraryNovel(1)) { }; fail() } catch (e: SearchException.SeedNotOnServer) { }
        assertEquals(2, gone.similarRequests.size)
    }

    @Test fun rateLimitAndRefusedSeedChaptersGetSpecificMessages() = runTest {
        val limited = FakeApi().apply { onSimilar = { _, _ -> throw SearchException.Http(429, "slow down") } }
        try { finder(limited, FakeSource(novel, chapters(100))).first.find(SimilarSeed.LibraryNovel(1)) { }; fail() } catch (e: SearchException.RateLimited) {
            assertTrue(e.message!!.contains("rate limit"))
        }
        assertEquals(1, limited.similarRequests.size)
        val refused = FakeApi().apply { refuseUploads = true }
        try { finder(refused, FakeSource(novel, chapters(100))).first.find(SimilarSeed.LibraryNovel(1)) { }; fail() } catch (e: SearchException.SeedRejected) { }
        assertTrue(refused.similarRequests.isEmpty())
    }

    @Test fun uploadingWithTheOptionOffIsAnnouncedAsSuch() = runTest {
        uploadsOn = false
        val stages = mutableListOf<SimilarStage>()
        finder(FakeApi(), FakeSource(novel, chapters(100))).first.find(SimilarSeed.LibraryNovel(1), stages::add)
        assertEquals(listOf(SimilarStage.PREPARING, SimilarStage.UPLOADING_UNASKED, SimilarStage.SEARCHING), stages)
    }

    @Test fun replayRunsASavedSimilarRequestAsIsWithoutUploading() = runTest {
        val api = FakeApi().apply { onSimilar = { _, _ -> SearchResponse(seedTitle = "Seed") } }
        val saved = SimilarRequest(libraryUrl = "https://x.example/n/1", chapters = listOf(0, 1), limit = 7)
        val r = finder(api, FakeSource(novel, chapters(10))).first.find(SimilarSeed.Replay(saved)) { }
        assertEquals(saved, api.similarRequests.single()); assertEquals(saved, r.request)
        assertTrue(api.uploads.isEmpty())
    }
}
