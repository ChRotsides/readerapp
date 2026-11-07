package com.readerapp.favorites

import com.readerapp.data.db.Favorite
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Starring works offline: a toggle only writes locally and marks the row pending; [FavoriteSyncTrigger] sends it later. */
@Singleton
class FavoriteRepository @Inject constructor(private val store: FavoriteStore) {
    internal var clock: () -> Long = System::currentTimeMillis

    val live: Flow<List<Favorite>> = store.observeLive()

    /** Stars [ref] when it is not starred, otherwise un-stars every favorite that identifies it. Returns the new state. */
    suspend fun toggle(ref: FavoriteRef): Boolean = store.atomically {
        val all = store.all()
        val starred = all.filter { it.favorite && ref.matches(it) }
        if (starred.isNotEmpty()) {
            starred.forEach { store.put(it.copy(favorite = false, changedAt = next(it.changedAt), pendingSync = true)) }
            false
        } else {
            val old = all.firstOrNull { it.key == ref.key }
            store.put(
                Favorite(
                    key = ref.key, workId = ref.workId, novelUrl = ref.novelUrl, title = ref.title, author = ref.author,
                    coverUrl = ref.coverUrl, domain = ref.domain, favorite = true, changedAt = next(old?.changedAt ?: 0), pendingSync = true,
                ),
            )
            true
        }
    }

    /** Strictly after the row's previous change, even if the device clock stepped back. */
    private fun next(previous: Long): Long = maxOf(clock(), previous + 1)
}
