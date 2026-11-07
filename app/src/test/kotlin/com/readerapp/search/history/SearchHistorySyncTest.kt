package com.readerapp.search.history

import com.readerapp.data.db.SearchHistoryEntry
import com.readerapp.search.ConnectionResult
import com.readerapp.search.CrawlJob
import com.readerapp.search.CrawlRequest
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.FavoriteDto
import com.readerapp.search.FavoritesResponse
import com.readerapp.search.LibraryUploadRequest
import com.readerapp.search.LibraryUploadResult
import com.readerapp.search.SearchBundle
import com.readerapp.search.SearchEntryDto
import com.readerapp.search.SearchException
import com.readerapp.search.SearchItemDto
import com.readerapp.search.SearchListResponse
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.SimilarRequest
import com.readerapp.search.ServerApi
import com.readerapp.search.Work
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class SearchHistorySyncTest {
    /** A server with tombstoned deletes and `since` pulls, like server/readerserver/api/searches.py. */
    class FakeServer : DiscoveryApi {
        val live = linkedMapOf<String, SearchEntryDto>()
        val tombstones = mutableListOf<String>()
        val pulls = mutableListOf<String?>()
        val deletes = mutableListOf<String>()
        val entryCalls = mutableListOf<String>()
        var deleteFailure: SearchException? = null
        var pullFailure: SearchException? = null
        var entryFailure: ((String) -> SearchException?)? = null
        var time = "2026-10-31T12:00:00Z"

        fun add(id: String, description: String = "d-$id", at: String = "2026-10-31T10:00:00Z", results: List<Work> = listOf(Work(workId = "w-$id", title = "T-$id"))) {
            live[id] = SearchEntryDto(
                id, description, results.size, at,
                request = Json.parseToJsonElement("""{"description":"$description"}""").jsonObject,
                scenes = Json.parseToJsonElement("""{"scenes":["s"],"synopsis":"syn","tags":["t"],"filters":{},"reranked":true,"notes":[]}""").jsonObject,
                results = results,
            )
        }

        override suspend fun searches(since: String?, limit: Int): SearchListResponse {
            pulls += since
            pullFailure?.let { throw it }
            val items = live.values.map { SearchItemDto(it.id, it.description, it.resultCount, it.createdAt) } +
                (if (since != null) tombstones.map { SearchItemDto(it, "", 0, "2026-10-31T09:00:00Z", deleted = true) } else emptyList())
            return SearchListResponse(items, time)
        }
        override suspend fun searchEntry(id: String): SearchEntryDto {
            entryCalls += id
            entryFailure?.invoke(id)?.let { throw it }
            return live[id] ?: throw SearchException.NotFound()
        }
        override suspend fun deleteSearch(id: String) {
            deleteFailure?.let { throw it }
            deletes += id
            if (live.remove(id) != null) tombstones += id
        }
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
    }

    private val store = MemSearchHistoryStore()
    private val server = FakeServer()
    private val sync = SearchHistorySync(store, server)

    private fun local(id: String, results: Boolean = true, deleted: Boolean = false, at: Long = 1_000) =
        SearchHistoryEntry(id, "local-$id", at, 1, resultsJson = if (results) "[]" else null, deleted = deleted)

    @Test fun firstPullAddsListRowsThenFetchesTheirResults() = runTest {
        server.add("a", at = "2026-10-31T10:00:00.5Z"); server.add("b")
        val o = sync.run()
        assertEquals(2, o.pulled); assertEquals(2, o.fetched)
        val a = store.get("a")!!
        assertEquals("d-a", a.description); assertEquals(Instant.parse("2026-10-31T10:00:00.5Z").toEpochMilli(), a.createdAt)
        assertEquals(1, a.resultCount); assertTrue(a.resultsJson!!.contains("T-a")); assertTrue(a.bundleJson.contains("syn"))
        assertEquals("2026-10-31T12:00:00Z", store.since)
        assertTrue(store.idsMissingResults(10).isEmpty())
    }

    @Test fun nextPullIsIncrementalAndTombstonesRemoveRows() = runTest {
        server.add("a"); server.add("b")
        sync.run()
        server.deleteSearch("a")           // deleted on another device
        server.time = "2026-10-31T13:00:00Z"
        val o = sync.run()
        assertEquals(listOf(null, "2026-10-31T12:00:00Z"), server.pulls)
        assertNull(store.get("a")); assertNotNull(store.get("b"))
        assertEquals(2, o.pulled) // b is returned again by the fake; known ids are left alone
    }

    @Test fun aTombstoneForAnUnknownIdAddsNothing() = runTest {
        server.tombstones += "zzz"; store.since = "2026-10-31T00:00:00Z"
        sync.run()
        assertNull(store.get("zzz"))
    }

    @Test fun localDeletesArePushedThenForgotten() = runTest {
        server.add("a"); server.add("b")
        sync.run()
        store.markDeleted("a")
        assertTrue(store.observeLiveIds().none { it == "a" })
        val o = sync.run()
        assertEquals(1, o.pushed); assertEquals(listOf("a"), server.deletes)
        assertNull(store.get("a")); assertNotNull(store.get("b"))
        sync.run() // the pulled tombstone of our own delete changes nothing
        assertEquals(listOf("a"), server.deletes); assertNull(store.get("a"))
    }

    @Test fun aPendingLocalDeleteIsNotResurrectedByAPull() = runTest {
        server.add("a")
        store.put(local("a", deleted = true))
        server.deleteFailure = SearchException.Network(null)
        try { sync.run(); fail("expected the network failure") } catch (e: SearchException.Network) { /* the worker retries */ }
        assertTrue(store.get("a")!!.deleted) // offline: stays hidden and pending
        assertNull(store.since)
        server.deleteFailure = null
        sync.run()
        assertNull(store.get("a")); assertEquals(listOf("a"), server.deletes)
    }

    @Test fun clearAllPushesEveryDelete() = runTest {
        server.add("a"); server.add("b"); server.add("c")
        sync.run()
        store.markAllDeleted()
        assertEquals(3, sync.run().pushed)
        assertTrue(store.rows.value.isEmpty()); assertTrue(server.live.isEmpty())
    }

    @Test fun serverRowsNeverOverwriteLocalOnes() = runTest {
        store.put(local("a", at = 5_000))
        server.add("a", description = "server description")
        sync.run()
        assertEquals("local-a", store.get("a")!!.description); assertEquals(5_000, store.get("a")!!.createdAt)
        assertTrue(server.entryCalls.isEmpty()) // it already has its results
    }

    @Test fun entryGoneOnTheServerIsDroppedAndOtherFailuresPropagate() = runTest {
        store.put(local("gone", results = false)); store.put(local("later", results = false, at = 500))
        server.add("later"); store.since = "2026-10-31T00:00:00Z"
        sync.run()
        assertNull(store.get("gone")); assertNotNull(store.get("later")!!.resultsJson)

        store.put(local("x", results = false)); server.add("x")
        server.entryFailure = { SearchException.Timeout(null) }
        try { sync.run(); fail("expected the timeout") } catch (e: SearchException.Timeout) { /* retried later */ }
        assertNull(store.get("x")!!.resultsJson)
        server.entryFailure = { SearchException.Http(422, "bad") }
        sync.run() // skipped, not retried in a loop
        assertNull(store.get("x")!!.resultsJson)
    }

    @Test fun aRefusedPullEndsQuietlyAndTokenProblemsPropagate() = runTest {
        server.pullFailure = SearchException.Http(400, "bad since")
        assertEquals(0, sync.run().pulled); assertNull(store.since)
        server.pullFailure = SearchException.Unauthorized()
        try { sync.run(); fail("expected Unauthorized") } catch (e: SearchException.Unauthorized) { /* worker succeeds quietly */ }
    }

    @Test fun retryClassification() {
        assertTrue(SearchHistorySync.isPermanent(SearchException.Http(404, null)))
        assertTrue(SearchHistorySync.isPermanent(SearchException.BadResponse(null)))
        assertFalse(SearchHistorySync.isPermanent(SearchException.Http(429, null)))
        assertFalse(SearchHistorySync.isPermanent(SearchException.Http(502, null)))
        assertFalse(SearchHistorySync.isPermanent(SearchException.Network(null)))
    }

    @Test fun twoDevicesConverge() = runTest {
        val phoneB = MemSearchHistoryStore()
        val syncB = SearchHistorySync(phoneB, server)
        server.add("a"); server.add("b")
        sync.run(); syncB.run()
        assertEquals(setOf("a", "b"), phoneB.rows.value.keys)
        store.markDeleted("a"); sync.run()
        server.time = "2026-10-31T14:00:00Z"
        syncB.run()
        assertEquals(setOf("b"), phoneB.rows.value.keys)
    }

    private suspend fun MemSearchHistoryStore.observeLiveIds(): List<String> = rows.value.values.filter { !it.deleted }.map { it.id }
}
