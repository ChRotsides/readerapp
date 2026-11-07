package com.readerapp.favorites

import com.readerapp.data.db.Favorite
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.FavoriteDto
import com.readerapp.search.SearchException
import java.time.Instant

/**
 * Pushes pending favorites (PUT), then pulls what changed on the server since the last pull. The later `changedAt`
 * wins, so an old change from another device never overrides a newer one, and un-starred rows travel as tombstones.
 * Network and server trouble is thrown as [SearchException] for the worker to retry; a favorite the server refuses with a
 * 4xx other than 429 is left pending but skipped, and a refused pull ends quietly.
 */
class FavoriteSync(private val store: FavoriteStore, private val api: DiscoveryApi) {
    data class Outcome(val pushed: Int, val pulled: Int, val moreWork: Boolean)

    suspend fun run(): Outcome {
        var pushed = 0
        val gaveUp = mutableSetOf<String>()
        for (p in store.pending()) {
            val stored = try {
                api.putFavorite(p.toDto())
            } catch (e: SearchException) {
                if (!isPermanent(e)) throw e
                gaveUp += p.key
                continue
            }
            pushed++
            merge(stored)
        }
        val response = try {
            api.favorites(store.lastSync())
        } catch (e: SearchException) {
            if (!isPermanent(e)) throw e
            return Outcome(pushed, 0, moreWork = false)
        }
        response.favorites.forEach { merge(it) }
        store.setLastSync(response.serverTime)
        // Toggled while this ran: one more pass.
        return Outcome(pushed, response.favorites.size, moreWork = store.pending().any { it.key !in gaveUp })
    }

    /** Merges one server row: a newer pending local change is kept; otherwise the later `changedAt` wins (ties take the server's). */
    internal suspend fun merge(remote: FavoriteDto) {
        val changedAt = parseInstantMillis(remote.changedAt) ?: return
        store.atomically {
            val local = store.get(remote.key)
            when {
                local == null -> if (remote.favorite) store.put(remote.toEntity(changedAt))
                local.pendingSync && local.changedAt > changedAt -> Unit
                changedAt >= local.changedAt -> store.put(remote.toEntity(changedAt))
            }
        }
    }

    companion object {
        /** Another try cannot help: not a connectivity, 429 or 5xx problem, and not a token/config problem (those end the run). */
        fun isPermanent(e: SearchException): Boolean = when (e) {
            is SearchException.NotFound, is SearchException.BadResponse -> true
            is SearchException.Http -> e.code in 400..499 && e.code != 429
            else -> false
        }

        fun parseInstantMillis(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }
}

internal fun Favorite.toDto() = FavoriteDto(
    key = key, workId = workId, novelUrl = novelUrl, title = title, author = author, coverUrl = coverUrl, domain = domain,
    favorite = favorite, changedAt = Instant.ofEpochMilli(changedAt).toString(),
)

private fun FavoriteDto.toEntity(changedAt: Long) = Favorite(
    key = key, workId = workId, novelUrl = novelUrl, title = title, author = author, coverUrl = coverUrl, domain = domain,
    favorite = favorite, changedAt = changedAt, pendingSync = false,
)
