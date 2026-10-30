package com.readerapp.offline

import com.readerapp.data.db.Novel
import com.readerapp.search.SourceUrl
import com.readerapp.search.Work
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPrefetchTest {
    private fun work(id: String, vararg urls: String) =
        Work(workId = id, title = "Book $id", urls = urls.map { SourceUrl("s", it) })

    private fun novel(id: Long, title: String, url: String, domain: String, inLibrary: Boolean = true, addedAt: Long = 0, lastOpenedAt: Long = addedAt) =
        Novel(id = id, title = title, sourceUrl = url, domain = domain, inLibrary = inLibrary, addedAt = addedAt, lastOpenedAt = lastOpenedAt)

    @Test fun picksTopNOnlyAndDoesNotBackfill() {
        val results = (1..8).map { work("w$it", "https://royalroad.com/fiction/$it") }
        assertEquals(listOf("w1", "w2", "w3"), SearchPrefetchPlanner.pick(results, 3, emptyList()).map { it.workId })
        val existing = listOf(novel(1, "Book w2", "https://royalroad.com/fiction/2", "royalroad.com"))
        assertEquals(listOf("w1", "w3"), SearchPrefetchPlanner.pick(results, 3, existing).map { it.workId })
        assertTrue(SearchPrefetchPlanner.pick(results, 0, emptyList()).isEmpty())
        assertTrue(SearchPrefetchPlanner.pick(results, -2, emptyList()).isEmpty())
    }

    @Test fun alreadySavedHiddenNovelsAreNotFetchedAgain() {
        val hidden = novel(5, "Book w1", "https://royalroad.com/fiction/1", "royalroad.com", inLibrary = false)
        assertTrue(SearchPrefetchPlanner.pick(listOf(work("w1", "https://royalroad.com/fiction/1")), 5, listOf(hidden)).isEmpty())
    }

    @Test fun resolvesFirstSourceWhoseDomainCanListChapters() = runTest {
        val w = work("w", "https://nope.example/a", "https://www.royalroad.com/fiction/9", "https://scribblehub.com/x")
        val checked = mutableListOf<String>()
        val source = SearchPrefetchPlanner.resolveSource(w) { d -> checked += d; d == "royalroad.com" || d == "scribblehub.com" }
        assertEquals("https://www.royalroad.com/fiction/9", source!!.url)
        assertEquals(listOf("nope.example", "royalroad.com"), checked)
        assertNull(SearchPrefetchPlanner.resolveSource(w) { false })
    }

    private val day = 24 * 60 * 60 * 1000L

    @Test fun retentionRules() {
        val now = 100 * day
        assertTrue(SearchRetention.isStale(novel(1, "a", "u", "d", inLibrary = false, addedAt = now - 15 * day), now))
        assertFalse(SearchRetention.isStale(novel(1, "a", "u", "d", inLibrary = false, addedAt = now - 13 * day), now))
        // Opened recently although added long ago.
        assertFalse(SearchRetention.isStale(novel(1, "a", "u", "d", inLibrary = false, addedAt = now - 30 * day, lastOpenedAt = now - 2 * day), now))
        // Library novels are never stale.
        assertFalse(SearchRetention.isStale(novel(1, "a", "u", "d", inLibrary = true, addedAt = now - 400 * day), now))
        assertEquals(14, SearchRetention.MAX_AGE_DAYS)
    }

    @Test fun cleanupSparesNovelAddedAfterTheListWasRead() = runTest {
        val now = 100 * day
        val n = novel(1, "raced", "u", "d", inLibrary = false, addedAt = now - 20 * day)
        val all = mutableListOf(n)
        val store = object : HiddenNovelStore {
            override suspend fun hidden(): List<Novel> {
                val snapshot = all.filter { !it.inLibrary }
                all[0] = n.copy(inLibrary = true) // the user adds it between hidden() and the delete
                return snapshot
            }
            override suspend fun deleteIfStale(id: Long, cutoff: Long) =
                all.removeAll { it.id == id && !it.inLibrary && it.addedAt < cutoff && it.lastOpenedAt < cutoff }
        }
        assertEquals(0, SearchCleanup(store).run(now))
        assertEquals(1, all.size)
    }

    @Test fun cleanupDeletesOnlyStaleHiddenNovels() = runTest {
        val now = 100 * day
        val all = mutableListOf(
            novel(1, "old hidden", "u1", "d", inLibrary = false, addedAt = now - 20 * day),
            novel(2, "new hidden", "u2", "d", inLibrary = false, addedAt = now - 3 * day),
            novel(3, "old library", "u3", "d", inLibrary = true, addedAt = now - 90 * day),
            novel(4, "old but read", "u4", "d", inLibrary = false, addedAt = now - 40 * day, lastOpenedAt = now - day),
        )
        val deleted = mutableListOf<Long>()
        val store = object : HiddenNovelStore {
            // Mirrors the DAO: only hidden novels are offered.
            override suspend fun hidden() = all.filter { !it.inLibrary }
            override suspend fun deleteIfStale(id: Long, cutoff: Long): Boolean {
                // Mirrors the DAO's WHERE: still hidden and neither added nor opened since the cutoff.
                val n = all.firstOrNull { it.id == id && !it.inLibrary && it.addedAt < cutoff && it.lastOpenedAt < cutoff } ?: return false
                deleted += id; all.remove(n); return true
            }
        }
        assertEquals(1, SearchCleanup(store).run(now))
        assertEquals(listOf(1L), deleted)
        assertEquals(listOf(2L, 3L, 4L), all.map { it.id })
    }
}
