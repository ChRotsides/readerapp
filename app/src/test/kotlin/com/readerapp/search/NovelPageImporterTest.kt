package com.readerapp.search

import com.readerapp.data.db.Novel
import com.readerapp.scrape.toc.TocResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPageImporterTest {
    private class FakeStore(var existing: Novel? = null) : NovelPageStore {
        val created = mutableListOf<Novel>()
        override suspend fun findExisting(domain: String, url: String, title: String) = existing
        override suspend fun create(novel: Novel): Long { created += novel; return 7 }
    }

    private val req = NovelPageRequest("https://www.royalroad.com/fiction/1/x", "X", author = "A", coverUrl = "c", synopsis = "s", language = "en")

    @Test fun activeAdapterWithTocImportsChapterList() = runTest {
        val store = FakeStore()
        val ran = mutableListOf<Long>()
        val r = NovelPageImporter(store, { id -> ran += id; TocResult.Imported(120, 120, 2) }).import(req)
        assertEquals(NovelPageImport.Imported(7, 120), r)
        assertEquals(listOf(7L), ran)
        val n = store.created.single()
        assertEquals("royalroad.com", n.domain)
        assertEquals(req.url, n.novelUrl)
        assertEquals("X", n.title); assertEquals("A", n.author); assertEquals("s", n.synopsis)
    }

    @Test fun noAdapterCreatesMetadataNovelAndAsksForBrowser() = runTest {
        val store = FakeStore()
        val r = NovelPageImporter(store, { TocResult.NoAdapter }).import(req)
        r as NovelPageImport.NeedsChapter
        assertEquals(7L, r.novelId); assertEquals(req.url, r.browserUrl)
        assertEquals(1, store.created.size)
    }

    @Test fun otherTocFailuresAlsoFallBackToBrowser() = runTest {
        for (res in listOf(TocResult.NoTocSpec, TocResult.NoNovelPage, TocResult.FetchFailed("x"), TocResult.Incomplete("y"))) {
            assertTrue(NovelPageImporter(FakeStore(), { res }).import(req) is NovelPageImport.NeedsChapter)
        }
        assertTrue(NovelPageImporter(FakeStore(), { error("boom") }).import(req) is NovelPageImport.NeedsChapter)
    }

    @Test fun existingNovelWithTocIsNotDuplicated() = runTest {
        val existing = Novel(id = 3, title = "X", sourceUrl = req.url, domain = "royalroad.com", tocFetchedAt = 5)
        val store = FakeStore(existing)
        assertEquals(NovelPageImport.AlreadyInLibrary(3), NovelPageImporter(store, { error("no") }).import(req))
        assertTrue(store.created.isEmpty())
    }

    @Test fun existingNovelWithoutTocReusesRowAndRetriesToc() = runTest {
        val store = FakeStore(Novel(id = 3, title = "X", sourceUrl = req.url, domain = "royalroad.com"))
        val r = NovelPageImporter(store, { TocResult.Imported(5, 5, 1) }).import(req)
        assertEquals(NovelPageImport.Imported(3, 5), r)
        assertTrue(store.created.isEmpty())
    }
}
