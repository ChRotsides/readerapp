package com.readerapp.search.history

import com.readerapp.data.db.SearchHistoryEntry
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.SearchException
import com.readerapp.search.SearchItemDto
import java.time.OffsetDateTime

/**
 * Pushes local deletes (DELETE), pulls what changed on the server since the last pull (list rows; tombstones remove rows),
 * then downloads the results of the newest rows that only have a list row. Server rows never overwrite local ones: entries
 * are immutable, so the only changes are creation and deletion. Network and server trouble is thrown as [SearchException]
 * for the worker to retry; an entry the server refuses with a 4xx other than 429 is skipped, and a refused pull ends quietly.
 */
class SearchHistorySync(private val store: SearchHistoryStore, private val api: DiscoveryApi) {
    data class Outcome(val pushed: Int, val pulled: Int, val fetched: Int, val moreWork: Boolean)

    suspend fun run(): Outcome {
        var pushed = 0
        var skipped = false
        for (id in store.pendingDeletes()) {
            try {
                api.deleteSearch(id)
            } catch (e: SearchException) {
                if (!isPermanent(e)) throw e
                skipped = true
                continue
            }
            pushed++
            store.atomically { if (store.get(id)?.deleted == true) store.remove(id) }
        }
        val since = store.lastSync()
        val response = try {
            api.searches(since, if (since == null) FIRST_PULL_LIMIT else PULL_LIMIT)
        } catch (e: SearchException) {
            if (!isPermanent(e)) throw e
            return Outcome(pushed, 0, 0, moreWork = false)
        }
        response.searches.forEach { merge(it) }
        store.setLastSync(response.serverTime)
        var fetched = 0
        for (id in store.idsMissingResults(FETCH_PER_RUN)) {
            val full = try {
                api.searchEntry(id)
            } catch (e: SearchException.NotFound) {
                store.atomically { store.remove(id) }
                continue
            } catch (e: SearchException) {
                if (!isPermanent(e)) throw e
                continue
            }
            store.atomically { store.get(id)?.takeIf { !it.deleted }?.let { store.put(it.withResults(full)) } }
            fetched++
        }
        // Deleted while this ran: one more pass.
        return Outcome(pushed, response.searches.size, fetched, moreWork = !skipped && store.pendingDeletes().isNotEmpty())
    }

    /** A tombstone removes the local row (whatever its state); a live row is added only when this device does not know the id. */
    internal suspend fun merge(remote: SearchItemDto) {
        store.atomically {
            val local = store.get(remote.id)
            when {
                remote.deleted -> if (local != null) store.remove(remote.id)
                local == null -> {
                    val created = parseInstantMillis(remote.createdAt) ?: return@atomically
                    store.put(SearchHistoryEntry(id = remote.id, description = remote.description, createdAt = created, resultCount = remote.resultCount))
                }
            }
        }
    }

    companion object {
        const val FIRST_PULL_LIMIT = 200
        const val PULL_LIMIT = 500
        const val FETCH_PER_RUN = 20

        /** Another try cannot help: not a connectivity, 429 or 5xx problem, and not a token/config problem (those end the run). */
        fun isPermanent(e: SearchException): Boolean = when (e) {
            is SearchException.NotFound, is SearchException.BadResponse -> true
            is SearchException.Http -> e.code in 400..499 && e.code != 429
            else -> false
        }

        fun parseInstantMillis(text: String): Long? = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }
}
