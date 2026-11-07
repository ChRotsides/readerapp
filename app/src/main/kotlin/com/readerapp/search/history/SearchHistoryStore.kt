package com.readerapp.search.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.readerapp.data.db.SearchHistoryDao
import com.readerapp.data.db.SearchHistoryEntry
import com.readerapp.data.db.SearchHistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Local search history plus the sync cursor, behind an interface so the repository and sync logic can be tested without Room. */
interface SearchHistoryStore {
    /** Entries that are not deleted, newest first, without their results. */
    fun observeLive(): Flow<List<SearchHistoryItem>>
    /** Any entry by id, tombstones included. */
    suspend fun get(id: String): SearchHistoryEntry?
    suspend fun put(entry: SearchHistoryEntry)
    suspend fun markDeleted(id: String)
    suspend fun markAllDeleted()
    /** Ids deleted locally whose delete the server has not confirmed. */
    suspend fun pendingDeletes(): List<String>
    suspend fun remove(id: String)
    /** Newest live entries whose results have not been downloaded yet. */
    suspend fun idsMissingResults(limit: Int): List<String>

    /** The server's `server_time` from the last successful pull; null = never pulled. */
    suspend fun lastSync(): String?
    suspend fun setLastSync(serverTime: String)

    /** Serializes read-modify-write steps (a sync merge against a delete). */
    suspend fun <T> atomically(block: suspend () -> T): T
}

@Singleton
class RoomSearchHistoryStore @Inject constructor(
    private val dao: SearchHistoryDao,
    private val prefs: DataStore<Preferences>,
) : SearchHistoryStore {
    private val lock = Mutex()

    override fun observeLive(): Flow<List<SearchHistoryItem>> = dao.observeLive()
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun put(entry: SearchHistoryEntry) = dao.upsert(entry)
    override suspend fun markDeleted(id: String) = dao.markDeleted(id)
    override suspend fun markAllDeleted() = dao.markAllDeleted()
    override suspend fun pendingDeletes() = dao.pendingDeletes()
    override suspend fun remove(id: String) = dao.remove(id)
    override suspend fun idsMissingResults(limit: Int) = dao.idsMissingResults(limit)

    override suspend fun lastSync(): String? = prefs.data.map { it[SINCE] }.first()
    override suspend fun setLastSync(serverTime: String) { prefs.edit { it[SINCE] = serverTime } }

    override suspend fun <T> atomically(block: suspend () -> T): T = lock.withLock { block() }

    private companion object {
        val SINCE = stringPreferencesKey("search_history_since")
    }
}
