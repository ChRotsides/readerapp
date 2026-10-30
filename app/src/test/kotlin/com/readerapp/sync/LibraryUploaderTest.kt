package com.readerapp.sync

import com.readerapp.data.db.ChapterText
import com.readerapp.data.db.Novel
import com.readerapp.data.db.TextVariant
import com.readerapp.data.db.UploadedChapter
import com.readerapp.search.CrawlJob
import com.readerapp.search.CrawlRequest
import com.readerapp.search.ConnectionResult
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.LibraryUploadRequest
import com.readerapp.search.LibraryUploadResult
import com.readerapp.search.SearchException
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.Work
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryUploaderTest {
    private class FakeApi : DiscoveryApi {
        val uploads = mutableListOf<LibraryUploadRequest>()
        var failOnCall: Int? = null
        /** Chapter indexes the server refuses with 422 (a batch containing one is refused as a whole). */
        var rejectIndexes = emptySet<Int>()
        override suspend fun uploadLibraryChapters(request: LibraryUploadRequest): LibraryUploadResult {
            if (failOnCall == uploads.size + 1) throw SearchException.Network(null)
            if (request.chapters.any { it.index in rejectIndexes }) throw SearchException.Http(422, "bad chapter")
            uploads += request
            return LibraryUploadResult(embedded = request.chapters.size)
        }
        override suspend fun search(request: SearchRequest): SearchResponse = throw UnsupportedOperationException()
        override suspend fun work(workId: String): Work = throw UnsupportedOperationException()
        override suspend fun searchTitle(query: String, limit: Int): List<Work> = throw UnsupportedOperationException()
        override suspend fun testConnection(): ConnectionResult = throw UnsupportedOperationException()
        override suspend fun startCrawl(request: CrawlRequest): CrawlJob = throw UnsupportedOperationException()
        override suspend fun crawlJobs(): List<CrawlJob> = throw UnsupportedOperationException()
        override suspend fun pauseCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun resumeCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
        override suspend fun cancelCrawl(id: Long): CrawlJob = throw UnsupportedOperationException()
    }

    /** In-memory store: "pending" mirrors the DAO (no record yet, or a text newer than the record). */
    private class FakeStore(val items: MutableList<UploadItem>) : UploadStore {
        val recorded = HashMap<Long, UploadedChapter>()
        override suspend fun pendingChapterIds(): List<Long> = items.filter { i ->
            val r = recorded[i.chapterId]
            r == null || r.uploadedAt < (i.texts.maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE) ||
                r.chapterIndex != i.index || r.title != i.title || r.url != i.url
        }.map { it.chapterId }
        override suspend fun item(chapterId: Long): UploadItem? =
            items.firstOrNull { it.chapterId == chapterId }?.let { it.copy(knownHash = recorded[chapterId]?.hash) }
        override suspend fun record(rows: List<UploadedChapter>) { rows.forEach { recorded[it.chapterId] = it } }
    }

    private fun novel(id: Long) = Novel(id = id, title = "N$id", author = "A", synopsis = "S", sourceUrl = "https://x.example/c/$id", domain = "x.example", novelUrl = "https://x.example/n/$id")

    private fun text(variant: TextVariant, at: Long, vararg p: String) = ChapterText(0, variant, p.toList(), createdAt = at)

    private fun item(id: Long, novelId: Long = 1, index: Int = id.toInt(), texts: List<ChapterText> = listOf(text(TextVariant.ORIGINAL, 100, "p1", "p2"))) =
        UploadItem(id, novel(novelId), index, "T$id", "https://x.example/c/$id", texts, null)

    @Test fun variantChoiceIsTranslatedThenFixedThenOriginal() {
        val o = text(TextVariant.ORIGINAL, 1, "orig"); val f = text(TextVariant.FIXED, 2, "fixed"); val t = text(TextVariant.TRANSLATED, 3, "trans")
        assertEquals(listOf("trans"), UploadText.choose(listOf(o, f, t)))
        assertEquals(listOf("fixed"), UploadText.choose(listOf(o, f)))
        assertEquals(listOf("orig"), UploadText.choose(listOf(o)))
        assertNull(UploadText.choose(emptyList()))
        assertNull(UploadText.choose(listOf(text(TextVariant.ORIGINAL, 1, " ", ""))))
    }

    @Test fun paragraphsAreJoinedWithBlankLines() = runTest {
        val api = FakeApi()
        LibraryUploader(FakeStore(mutableListOf(item(1))), api).run()
        val c = api.uploads.single().chapters.single()
        assertEquals("p1\n\np2", c.text)
        assertEquals("T1", c.title); assertEquals("https://x.example/c/1", c.url)
        val n = api.uploads.single().novel
        assertEquals("https://x.example/n/1", n.url); assertEquals("x.example", n.domain); assertEquals("A", n.author)
    }

    @Test fun batchesOfAtMostFiftyPerNovel() = runTest {
        val api = FakeApi()
        val items = (1L..120L).map { item(it, novelId = 1) } + (121L..130L).map { item(it, novelId = 2) }
        val summary = LibraryUploader(FakeStore(items.toMutableList()), api).run()
        assertEquals(listOf(50, 50, 20, 10), api.uploads.map { it.chapters.size })
        assertEquals(listOf("N1", "N1", "N1", "N2"), api.uploads.map { it.novel.title })
        assertEquals(130, summary.sent)
        assertTrue(api.uploads.all { it.chapters.size <= LibraryUploadRequest.MAX_CHAPTERS })
    }

    @Test fun unchangedChaptersAreNotResentButChangedOnesAre() = runTest {
        val api = FakeApi()
        val store = FakeStore(mutableListOf(item(1), item(2)))
        var now = 500L
        val up = LibraryUploader(store, api, clock = { now })
        up.run()
        assertEquals(2, api.uploads.single().chapters.size)

        api.uploads.clear()
        up.run()
        assertTrue("nothing pending, nothing sent", api.uploads.isEmpty())

        // A translation lands for chapter 2 (new text, newer than the upload): only that one is resent.
        store.items[1] = item(2, texts = listOf(text(TextVariant.ORIGINAL, 100, "p1", "p2"), text(TextVariant.TRANSLATED, 600, "english")))
        now = 700
        up.run()
        assertEquals(listOf(2), api.uploads.single().chapters.map { it.index })
        assertEquals("english", api.uploads.single().chapters.single().text)
    }

    @Test fun rewrittenWithSameContentMovesCheckpointWithoutUpload() = runTest {
        val api = FakeApi()
        val store = FakeStore(mutableListOf(item(1)))
        LibraryUploader(store, api, clock = { 500 }).run()
        api.uploads.clear()
        // Same text written again later (e.g. a refetch): flagged as pending by the DB hint, hash says unchanged.
        store.items[0] = item(1, texts = listOf(text(TextVariant.ORIGINAL, 900, "p1", "p2")))
        val s = LibraryUploader(store, api, clock = { 1000 }).run()
        assertTrue(api.uploads.isEmpty())
        assertEquals(1, s.unchanged)
        assertEquals(1000L, store.recorded[1]!!.uploadedAt)
    }

    @Test fun hashCoversIndexTitleUrlAndText() {
        val base = UploadText.hash(1, "t", "u", "x")
        assertEquals(base, UploadText.hash(1, "t", "u", "x"))
        assertNotEquals(base, UploadText.hash(2, "t", "u", "x"))
        assertNotEquals(base, UploadText.hash(1, "t2", "u", "x"))
        assertNotEquals(base, UploadText.hash(1, "t", "u2", "x"))
        assertNotEquals(base, UploadText.hash(1, "t", "u", "y"))
        assertEquals(64, base.length)
    }

    @Test fun failedBatchKeepsEarlierBatchesRecordedAndResumes() = runTest {
        val api = FakeApi().apply { failOnCall = 2 }
        val store = FakeStore((1L..60L).map { item(it) }.toMutableList())
        try { LibraryUploader(store, api).run(); fail() } catch (e: SearchException) { assertTrue(e is SearchException.Network) }
        assertEquals(50, store.recorded.size)
        api.failOnCall = null
        LibraryUploader(store, api).run()
        assertEquals(listOf(50, 10), api.uploads.map { it.chapters.size })
        assertEquals(60, store.recorded.size)
    }

    @Test fun chaptersWithoutTextAreSkippedAndDoNotLoop() = runTest {
        val api = FakeApi()
        val store = FakeStore(mutableListOf(item(1, texts = emptyList()), item(2)))
        LibraryUploader(store, api).run()
        assertEquals(listOf(2), api.uploads.single().chapters.map { it.index })
    }

    @Test fun reindexedOrRetitledChapterIsResentEvenWithSameText() = runTest {
        val api = FakeApi()
        val store = FakeStore(mutableListOf(item(1), item(2)))
        val up = LibraryUploader(store, api, clock = { 500 })
        up.run(); api.uploads.clear()
        store.items[0] = item(1, index = 7) // TOC re-index: same text, new position
        up.run()
        assertEquals(listOf(7), api.uploads.single().chapters.map { it.index })
    }

    @Test fun rejectedBatchIsSplitRejectedChapterIsSkippedAndRunContinues() = runTest {
        val api = FakeApi().apply { rejectIndexes = setOf(2) }
        val store = FakeStore(mutableListOf(item(1), item(2), item(3), item(4, novelId = 2)))
        val s = LibraryUploader(store, api, clock = { 500 }).run()
        assertEquals(1, s.rejected)
        assertEquals(setOf(1, 3, 4), api.uploads.flatMap { it.chapters.map { c -> c.index } }.toSet())
        assertTrue(store.recorded[2]!!.hash.startsWith(UploadedChapter.REJECTED_PREFIX))
        api.uploads.clear()
        LibraryUploader(store, api, clock = { 600 }).run()
        assertTrue(api.uploads.isEmpty())
        store.items[1] = item(2, texts = listOf(text(TextVariant.ORIGINAL, 700, "different")))
        api.rejectIndexes = emptySet()
        LibraryUploader(store, api, clock = { 800 }).run()
        assertEquals(listOf(2), api.uploads.single().chapters.map { it.index })
    }

    @Test fun nonRejectionHttpErrorsStillPropagate() = runTest {
        val api = object : DiscoveryApi by FakeApi() {
            override suspend fun uploadLibraryChapters(request: LibraryUploadRequest): LibraryUploadResult = throw SearchException.Http(429, null)
        }
        try { LibraryUploader(FakeStore(mutableListOf(item(1))), api).run(); fail() } catch (e: SearchException.Http) { assertEquals(429, e.code) }
    }
}
