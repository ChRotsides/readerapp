package com.readerapp.favorites

import com.readerapp.data.db.Favorite
import com.readerapp.search.ConnectionResult
import com.readerapp.search.CrawlJob
import com.readerapp.search.CrawlRequest
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.FavoriteDto
import com.readerapp.search.FavoritesResponse
import com.readerapp.search.LibraryUploadRequest
import com.readerapp.search.LibraryUploadResult
import com.readerapp.search.SearchException
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.SimilarRequest
import com.readerapp.search.Work
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class FavoriteSyncTest {
    private class MemStore : FavoriteStore {
        val rows = MutableStateFlow<Map<String, Favorite>>(emptyMap())
        var since: String? = null
        private val lock = Mutex()
        override fun observeLive(): Flow<List<Favorite>> = rows.map { m -> m.values.filter { it.favorite }.sortedByDescending { it.changedAt } }
        override suspend fun all() = rows.value.values.toList()
        override suspend fun get(key: String) = rows.value[key]
        override suspend fun pending() = rows.value.values.filter { it.pendingSync }.sortedBy { it.changedAt }
        override suspend fun put(favorite: Favorite) { rows.value = rows.value + (favorite.key to favorite) }
        override suspend fun lastSync() = since
        override suspend fun setLastSync(serverTime: String) { since = serverTime }
        override suspend fun <T> atomically(block: suspend () -> T): T = lock.withLock { block() }
    }

    /** A server with last-write-wins on changed_at, like server/readerserver/api/favorites.py. */
    private class FakeServer : DiscoveryApi {
        val rows = linkedMapOf<String, FavoriteDto>()
        val puts = mutableListOf<FavoriteDto>()
        val pulls = mutableListOf<String?>()
        var putFailure: ((FavoriteDto) -> SearchException?)? = null
        var pullFailure: SearchException? = null
        var time = "2026-10-31T12:00:00Z"
        override suspend fun putFavorite(favorite: FavoriteDto): FavoriteDto {
            putFailure?.invoke(favorite)?.let { throw it }
            puts += favorite
            val old = rows[favorite.key]
            if (old == null || Instant.parse(favorite.changedAt) >= Instant.parse(old.changedAt)) rows[favorite.key] = favorite.copy(updatedAt = time)
            return rows.getValue(favorite.key)
        }
        override suspend fun favorites(since: String?): FavoritesResponse {
            pulls += since
            pullFailure?.let { throw it }
            val list = if (since == null) rows.values.filter { it.favorite } else rows.values.toList()
            return FavoritesResponse(list, time)
        }
        override suspend fun searches(since: String?, limit: Int): com.readerapp.search.SearchListResponse = throw UnsupportedOperationException()
        override suspend fun searchEntry(id: String): com.readerapp.search.SearchEntryDto = throw UnsupportedOperationException()
        override suspend fun deleteSearch(id: String) = throw UnsupportedOperationException()
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
    }

    private val store = MemStore()
    private val server = FakeServer()
    private val sync = FavoriteSync(store, server)
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun local(key: String, at: String, on: Boolean = true, pending: Boolean = true, title: String = "T") =
        Favorite(key = key, title = title, favorite = on, changedAt = ms(at), pendingSync = pending)

    private fun remote(key: String, at: String, on: Boolean = true, title: String = "R", workId: String? = null) =
        FavoriteDto(key = key, workId = workId, title = title, favorite = on, changedAt = at)

    @Test fun pendingChangesArePushedThenCleared() = runTest {
        store.put(local("url:https://a.example/1", "2026-10-31T10:00:00.123Z"))
        store.put(local("url:https://a.example/2", "2026-10-31T10:00:05Z", on = false))
        store.put(local("url:https://a.example/3", "2026-10-31T09:00:00Z", pending = false))
        val o = sync.run()
        assertEquals(2, o.pushed); assertFalse(o.moreWork)
        assertEquals(listOf("url:https://a.example/1", "url:https://a.example/2"), server.puts.map { it.key })
        assertEquals("2026-10-31T10:00:00.123Z", server.puts[0].changedAt) // ms precision survives the wire
        assertFalse(server.puts[1].favorite)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun pullStoresNewRowsAndRemembersServerTime() = runTest {
        server.rows["work:w1"] = remote("work:w1", "2026-10-31T08:00:00Z", workId = "w1")
        val o = sync.run()
        assertEquals(1, o.pulled)
        val row = store.get("work:w1")!!
        assertEquals("w1", row.workId); assertTrue(row.favorite); assertFalse(row.pendingSync)
        assertEquals("2026-10-31T12:00:00Z", store.since)
        sync.run()
        assertEquals(listOf(null, "2026-10-31T12:00:00Z"), server.pulls) // the next pull is incremental
    }

    @Test fun pulledTombstoneUnstarsALocalRowButNeverCreatesOne() = runTest {
        store.put(local("k1", "2026-10-31T08:00:00Z", pending = false))
        server.rows["k1"] = remote("k1", "2026-10-31T09:00:00Z", on = false)
        server.rows["k2"] = remote("k2", "2026-10-31T09:00:00Z", on = false)
        store.since = "2026-10-31T00:00:00Z"
        sync.run()
        assertFalse(store.get("k1")!!.favorite)
        assertNull(store.get("k2"))
    }

    @Test fun aNewerPendingLocalChangeBeatsTheServerAndIsPushed() = runTest {
        store.put(local("k", "2026-10-31T11:00:00Z", on = false))
        server.rows["k"] = remote("k", "2026-10-31T10:00:00Z", on = true)
        sync.run()
        assertFalse(server.rows.getValue("k").favorite)
        assertFalse(store.get("k")!!.favorite); assertFalse(store.get("k")!!.pendingSync)
    }

    @Test fun anOlderLocalChangeLosesToTheServersAndFieldsComeFromTheServer() = runTest {
        store.put(local("k", "2026-10-31T09:00:00Z", on = true, title = "Old"))
        server.rows["k"] = remote("k", "2026-10-31T10:00:00Z", on = false, title = "Server")
        sync.run()
        val row = store.get("k")!!
        assertFalse(row.favorite); assertEquals("Server", row.title); assertFalse(row.pendingSync)
        assertEquals(ms("2026-10-31T10:00:00Z"), row.changedAt)
    }

    @Test fun anOlderServerRowNeverOverridesANewerSyncedLocalRow() = runTest {
        store.put(local("k", "2026-10-31T11:00:00Z", pending = false, title = "Local"))
        server.rows["k"] = remote("k", "2026-10-31T10:00:00Z", title = "Server")
        store.since = "2026-10-31T00:00:00Z"
        sync.run()
        assertEquals("Local", store.get("k")!!.title)
    }

    @Test fun anOfflineToggleStaysPendingUntilTheServerIsReachable() = runTest {
        store.put(local("k", "2026-10-31T10:00:00Z"))
        server.putFailure = { SearchException.Network(null) }
        try { sync.run(); fail("expected the network failure") } catch (e: SearchException.Network) { /* the worker retries */ }
        assertTrue(store.get("k")!!.pendingSync); assertNull(store.since)
        server.putFailure = null
        sync.run()
        assertFalse(store.get("k")!!.pendingSync); assertNotNull(store.since)
    }

    @Test fun transientServerErrorsPropagateButOtherClientErrorsAreSkipped() = runTest {
        store.put(local("a", "2026-10-31T10:00:00Z"))
        store.put(local("b", "2026-10-31T10:00:01Z"))
        server.putFailure = { if (it.key == "a") SearchException.Http(422, "bad") else null }
        val o = sync.run()
        assertEquals(1, o.pushed); assertFalse(o.moreWork) // "a" stays pending but is not retried in a loop
        assertTrue(store.get("a")!!.pendingSync); assertFalse(store.get("b")!!.pendingSync)

        for (e in listOf(SearchException.Http(429, null), SearchException.Http(500, null), SearchException.ServerUnavailable(null), SearchException.Timeout(null))) {
            server.putFailure = { e }
            try { sync.run(); fail("expected $e") } catch (x: SearchException) { assertEquals(e, x) }
        }
    }

    @Test fun aRefusedPullEndsQuietlyAndTokenProblemsPropagate() = runTest {
        server.pullFailure = SearchException.Http(400, "bad since")
        assertEquals(0, sync.run().pulled)
        assertNull(store.since)
        server.pullFailure = SearchException.Unauthorized()
        try { sync.run(); fail("expected Unauthorized") } catch (e: SearchException.Unauthorized) { /* worker succeeds quietly */ }
    }

    @Test fun retryClassification() {
        assertTrue(FavoriteSync.isPermanent(SearchException.Http(404, null)))
        assertTrue(FavoriteSync.isPermanent(SearchException.NotFound()))
        assertTrue(FavoriteSync.isPermanent(SearchException.BadResponse(null)))
        assertFalse(FavoriteSync.isPermanent(SearchException.Http(429, null)))
        assertFalse(FavoriteSync.isPermanent(SearchException.Http(502, null)))
        assertFalse(FavoriteSync.isPermanent(SearchException.Network(null)))
    }

    @Test fun twoDevicesConverge() = runTest {
        val phoneB = MemStore()
        val syncB = FavoriteSync(phoneB, server)
        val repoA = FavoriteRepository(store).apply { clock = { ms("2026-10-31T10:00:00Z") } }
        val repoB = FavoriteRepository(phoneB).apply { clock = { ms("2026-10-31T10:30:00Z") } }
        val ref = FavoriteRef("work:w1", "w1", null, "Book", null, null, null, emptySet())
        repoA.toggle(ref); sync.run(); syncB.run()
        assertTrue(phoneB.get("work:w1")!!.favorite)
        repoB.toggle(ref); syncB.run(); sync.run()   // B un-stars later: A learns about the tombstone
        assertFalse(store.get("work:w1")!!.favorite)
    }

    @Test fun toggleMarksPendingKeepsTimeMonotonicAndUnstarsEveryMatch() = runTest {
        val repo = FavoriteRepository(store).apply { clock = { 1_000 } }
        val ref = FavoriteRef("url:https://a.example/n", null, "https://a.example/n", "Book", "Au", null, "a.example", setOf("a.example/n"))
        assertTrue(repo.toggle(ref))
        store.get(ref.key)!!.let { assertTrue(it.favorite && it.pendingSync); assertEquals(1_000, it.changedAt); assertEquals("Au", it.author) }
        assertFalse(repo.toggle(ref)) // same millisecond: still strictly later
        store.get(ref.key)!!.let { assertFalse(it.favorite); assertTrue(it.pendingSync); assertEquals(1_001, it.changedAt) }
        assertTrue(repo.toggle(ref))
        assertEquals(1_002, store.get(ref.key)!!.changedAt)
        // another row for the same book (work key with that page) is un-starred together
        store.put(Favorite(key = "work:w", workId = "w", novelUrl = "https://www.a.example/n/", title = "Book", changedAt = 5))
        assertFalse(repo.toggle(ref))
        assertTrue(store.all().none { it.favorite })
    }
}
