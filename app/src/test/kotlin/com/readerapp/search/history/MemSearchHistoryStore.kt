package com.readerapp.search.history

import com.readerapp.data.db.SearchHistoryEntry
import com.readerapp.data.db.SearchHistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [SearchHistoryStore] with the same query semantics as the Room DAO. */
class MemSearchHistoryStore : SearchHistoryStore {
    val rows = MutableStateFlow<Map<String, SearchHistoryEntry>>(emptyMap())
    var since: String? = null
    private val lock = Mutex()

    override fun observeLive(): Flow<List<SearchHistoryItem>> = rows.map { m ->
        m.values.filter { !it.deleted }.sortedByDescending { it.createdAt }
            .map { SearchHistoryItem(it.id, it.description, it.createdAt, it.resultCount, it.resultsJson != null) }
    }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun put(entry: SearchHistoryEntry) { rows.value = rows.value + (entry.id to entry) }
    override suspend fun markDeleted(id: String) { rows.value[id]?.let { put(it.copy(deleted = true)) } }
    override suspend fun markAllDeleted() { rows.value = rows.value.mapValues { it.value.copy(deleted = true) } }
    override suspend fun pendingDeletes() = rows.value.values.filter { it.deleted }.map { it.id }
    override suspend fun remove(id: String) { rows.value = rows.value - id }
    override suspend fun idsMissingResults(limit: Int) =
        rows.value.values.filter { !it.deleted && it.resultsJson == null }.sortedByDescending { it.createdAt }.take(limit).map { it.id }
    override suspend fun lastSync() = since
    override suspend fun setLastSync(serverTime: String) { since = serverTime }
    override suspend fun <T> atomically(block: suspend () -> T): T = lock.withLock { block() }
}
