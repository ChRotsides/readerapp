package com.readerapp.search.history

import com.readerapp.data.db.SearchHistoryEntry
import com.readerapp.data.db.SearchHistoryItem
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.SearchBundle
import com.readerapp.search.SearchEntryDto
import com.readerapp.search.SearchRequest
import com.readerapp.search.SearchResponse
import com.readerapp.search.SimilarRequest
import com.readerapp.search.ServerApi
import com.readerapp.search.Work
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Asks for a background sync of the history (a unique, network-constrained worker). */
fun interface SearchHistorySyncRequester {
    fun request()
}

/** A history entry opened for display. */
data class SavedSearch(
    val id: String, val description: String, val createdAt: Long, val request: SearchRequest, val response: SearchResponse,
    /** Set when the entry is a similar-novels search (its stored request has the `/similar` shape); [request] is then only a placeholder. */
    val similar: SimilarRequest? = null,
)

/**
 * Saved discovery searches. Everything is read from and written to the local table first so history and saved results work
 * offline; [SearchHistorySync] mirrors it to the server. Deletes hide the row at once and reach the server later.
 */
@Singleton
class SearchHistoryRepository @Inject constructor(
    private val store: SearchHistoryStore,
    private val api: DiscoveryApi,
    private val syncRequester: SearchHistorySyncRequester,
) {
    internal var clock: () -> Long = System::currentTimeMillis

    /** Newest first. */
    val recent: Flow<List<SearchHistoryItem>> = store.observeLive()

    /** Stores a search that just succeeded, under the id the server saved it with (a local-only id when the server did not). */
    suspend fun record(request: SearchRequest, response: SearchResponse) {
        val id = response.historyId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        store.atomically {
            store.put(
                SearchHistoryEntry(
                    id = id, description = request.description, createdAt = clock(), resultCount = response.results.size,
                    requestJson = ServerApi.json.encodeToString(SearchRequest.serializer(), request),
                    bundleJson = ServerApi.json.encodeToString(SearchBundle.serializer(), response.toBundle()),
                    resultsJson = ServerApi.json.encodeToString(ListSerializer(Work.serializer()), response.results),
                ),
            )
        }
    }

    /** Stores a finished similar-novels search under [description]; the request is kept in the `/similar` shape, as the server does. */
    suspend fun recordSimilar(request: SimilarRequest, description: String, response: SearchResponse) {
        val id = response.historyId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        store.atomically {
            store.put(
                SearchHistoryEntry(
                    id = id, description = description, createdAt = clock(), resultCount = response.results.size,
                    requestJson = ServerApi.json.encodeToString(SimilarRequest.serializer(), request),
                    bundleJson = ServerApi.json.encodeToString(SearchBundle.serializer(), response.toBundle()),
                    resultsJson = ServerApi.json.encodeToString(ListSerializer(Work.serializer()), response.results),
                ),
            )
        }
    }

    /**
     * The saved search with its results. Downloads the results from the server first when this device only has the list
     * row (throws [com.readerapp.search.SearchException] when that is not possible). Null when the entry does not exist.
     */
    suspend fun open(id: String): SavedSearch? {
        var entry = store.get(id)?.takeIf { !it.deleted } ?: return null
        if (entry.resultsJson == null) {
            val full = api.searchEntry(id) // NotFound: deleted elsewhere
            store.atomically { store.get(id)?.takeIf { !it.deleted }?.let { store.put(it.withResults(full)) } }
            entry = store.get(id)?.takeIf { !it.deleted } ?: return null
        }
        return runCatching { entry.toSaved() }.getOrNull()
    }

    suspend fun delete(id: String) {
        store.atomically { store.markDeleted(id) }
        syncRequester.request()
    }

    suspend fun clear() {
        store.atomically { store.markAllDeleted() }
        syncRequester.request()
    }
}

internal fun SearchResponse.toBundle() = SearchBundle(scenes, synopsis, tags, filters, reranked, notes)

/** The entry with the results (and generated parts) of the server's full copy; the local request and creation time stay. */
internal fun SearchHistoryEntry.withResults(full: SearchEntryDto) = copy(
    resultCount = full.results.size,
    requestJson = if (requestJson == "{}") full.request.toString() else requestJson,
    bundleJson = full.scenes.toString(),
    resultsJson = ServerApi.json.encodeToString(ListSerializer(Work.serializer()), full.results),
)

internal fun SearchHistoryEntry.toSaved(): SavedSearch {
    val json = ServerApi.json
    // A similar search's request (library_url / work_id, no description) is told apart by its shape.
    val raw = json.parseToJsonElement(requestJson) as? JsonObject ?: JsonObject(emptyMap())
    val similar = if ("library_url" in raw || "work_id" in raw) json.decodeFromJsonElement(SimilarRequest.serializer(), raw) else null
    val request = if (similar != null) SearchRequest(description = description, limit = similar.limit)
    else json.decodeFromJsonElement(SearchRequest.serializer(), if ("description" in raw) raw else JsonObject(raw + ("description" to JsonPrimitive(description))))
    val bundle = json.decodeFromString(SearchBundle.serializer(), bundleJson)
    val results = json.decodeFromString(ListSerializer(Work.serializer()), resultsJson ?: "[]")
    return SavedSearch(
        id, description, createdAt, request.copy(description = description),
        SearchResponse(results, bundle.scenes, bundle.synopsis, bundle.tags, bundle.filters, bundle.reranked, bundle.notes, historyId = id),
        similar,
    )
}
