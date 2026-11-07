package com.readerapp.search.history

import com.readerapp.data.db.SearchHistoryEntry
import com.readerapp.search.SearchEntryDto
import com.readerapp.search.SearchException
import com.readerapp.search.SearchFilters
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.SimilarRequest
import com.readerapp.search.Work
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SearchHistoryRepositoryTest {
    private val store = MemSearchHistoryStore()
    private val server = SearchHistorySyncTest.FakeServer()
    private var syncRequests = 0
    private val repo = SearchHistoryRepository(store, server) { syncRequests++ }.apply { clock = { 5_000 } }

    private val work = Work(workId = "w1", title = "Book", authors = listOf("A"), coverUrl = "https://x/c.jpg", synopsis = "S", tags = listOf("t"),
        status = "ongoing", chapterCount = 7, score = 0.8, reason = "fits", matchedScenes = 2)
    private val response = SearchResponse(
        results = listOf(work), scenes = listOf("s1", "s2"), synopsis = "syn", tags = listOf("a"),
        filters = SearchFilters(language = "ko"), reranked = true, notes = listOf("n"), historyId = "h1",
    )

    @Test fun recordStoresEverythingNeededToRenderOffline() = runTest {
        repo.record(SearchRequest("a dragon"), response)
        val item = repo.recent.first().single()
        assertEquals(listOf("h1", "a dragon", 5_000L, 1, true), listOf(item.id, item.description, item.createdAt, item.resultCount, item.hasResults))
        val saved = repo.open("h1")!!
        assertEquals(response, saved.response)             // results with score/reason/matched scenes, scenes, synopsis, tags, filters
        assertEquals(SearchRequest("a dragon"), saved.request)
        assertEquals("a dragon", saved.description)
    }

    @Test fun recordWithoutAServerIdStillSavesLocally() = runTest {
        repo.record(SearchRequest("x"), response.copy(historyId = null))
        val id = repo.recent.first().single().id
        assertTrue(id.isNotBlank() && id != "null")
        assertNotNull(repo.open(id))
    }

    @Test fun recentIsNewestFirstAndDeleteAndClearHideAtOnceAndAskForSync() = runTest {
        var t = 1L
        repo.clock = { t++ }
        listOf("h1", "h2", "h3").forEach { repo.record(SearchRequest("q$it"), response.copy(historyId = it)) }
        assertEquals(listOf("h3", "h2", "h1"), repo.recent.first().map { it.id })
        repo.delete("h2")
        assertEquals(listOf("h3", "h1"), repo.recent.first().map { it.id })
        assertNull(repo.open("h2")); assertEquals(1, syncRequests)
        assertTrue(store.get("h2")!!.deleted) // a tombstone until the server confirms
        repo.clear()
        assertTrue(repo.recent.first().isEmpty()); assertEquals(2, syncRequests)
        assertEquals(setOf("h1", "h2", "h3"), store.pendingDeletes().toSet())
    }

    @Test fun openDownloadsResultsForAListRowAndFailsOfflineWithoutLosingTheRow() = runTest {
        store.put(SearchHistoryEntry("r", "remote one", 1_000, 1))
        server.entryFailure = { SearchException.Network(null) }
        try { repo.open("r"); fail("expected the network failure") } catch (e: SearchException.Network) { /* shown as an error */ }
        assertNotNull(store.get("r"))
        server.entryFailure = null
        server.add("r", description = "remote one", results = listOf(work))
        val saved = repo.open("r")!!
        assertEquals(listOf(work), saved.response.results); assertEquals("remote one", saved.description)
        assertEquals(1_000, saved.createdAt)
        assertEquals(listOf("syn"), listOf(saved.response.synopsis)); assertTrue(saved.response.reranked)
        assertEquals(2, server.entryCalls.size)
        repo.open("r"); assertEquals(2, server.entryCalls.size) // the second open is local
    }

    @Test fun aServerSideSimilarEntryWithASimilarShapedRequestOpensAndIsMarkedSimilar() = runTest {
        store.put(SearchHistoryEntry("r", "Similar to Seed", 1_000, 1))
        server.live["r"] = SearchEntryDto(
            "r", "Similar to Seed", 1, "2026-10-31T10:00:00Z",
            request = Json.parseToJsonElement("""{"library_url":"https://x/n/1","chapters":[0,1,2],"hide_library":true,"limit":20,"extra":1}""").jsonObject,
            scenes = Json.parseToJsonElement("""{"scenes":[],"synopsis":"","tags":[],"filters":{},"reranked":false,"notes":[]}""").jsonObject,
            results = listOf(work),
        )
        val saved = repo.open("r")!!
        assertEquals(listOf(work), saved.response.results)
        assertEquals("Similar to Seed", saved.request.description)
        assertEquals("https://x/n/1", saved.similar!!.libraryUrl); assertEquals(listOf(0, 1, 2), saved.similar!!.chapters)
    }

    @Test fun recordSimilarKeepsTheSimilarRequestAndPlainSearchesAreNotSimilar() = runTest {
        repo.recordSimilar(SimilarRequest(workId = "w1"), "Similar to Seed", response)
        assertEquals("w1", repo.open("h1")!!.similar!!.workId)
        repo.record(SearchRequest("Similar to a dragon"), response.copy(historyId = "h2"))
        assertNull(repo.open("h2")!!.similar)
    }
}
