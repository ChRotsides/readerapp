package com.readerapp.favorites

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.readerapp.data.db.Favorite
import com.readerapp.data.db.FavoriteDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Local favorites plus the sync cursor, behind an interface so the toggle and sync logic can be tested without Room. */
interface FavoriteStore {
    /** Live (starred) favorites, newest change first. */
    fun observeLive(): Flow<List<Favorite>>
    suspend fun all(): List<Favorite>
    suspend fun get(key: String): Favorite?
    suspend fun pending(): List<Favorite>
    suspend fun put(favorite: Favorite)

    /** The server's `server_time` from the last successful pull; null = never pulled. */
    suspend fun lastSync(): String?
    suspend fun setLastSync(serverTime: String)

    /** Serializes read-modify-write steps (a toggle against a sync merge). */
    suspend fun <T> atomically(block: suspend () -> T): T
}

@Singleton
class RoomFavoriteStore @Inject constructor(
    private val dao: FavoriteDao,
    private val prefs: DataStore<Preferences>,
) : FavoriteStore {
    private val lock = Mutex()

    override fun observeLive(): Flow<List<Favorite>> = dao.observeLive()
    override suspend fun all(): List<Favorite> = dao.all()
    override suspend fun get(key: String): Favorite? = dao.get(key)
    override suspend fun pending(): List<Favorite> = dao.pending()
    override suspend fun put(favorite: Favorite) = dao.upsert(favorite)

    override suspend fun lastSync(): String? = prefs.data.map { it[SINCE] }.first()
    override suspend fun setLastSync(serverTime: String) { prefs.edit { it[SINCE] = serverTime } }

    override suspend fun <T> atomically(block: suspend () -> T): T = lock.withLock { block() }

    private companion object {
        val SINCE = stringPreferencesKey("favorites_since")
    }
}
