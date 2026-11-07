package com.readerapp.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** JSON shapes of `/searches` (server/readerserver/api/searches.py). Times are ISO-8601 instants. */

/** A history entry without its results (list rows, and tombstones when pulled with `since`). */
@Serializable
data class SearchItemDto(
    val id: String,
    val description: String = "",
    @SerialName("result_count") val resultCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class SearchListResponse(
    val searches: List<SearchItemDto> = emptyList(),
    /** Pass back as `since` on the next pull. */
    @SerialName("server_time") val serverTime: String,
)

/** `GET /searches/{id}`: the whole entry. [scenes] has the shape of [SearchBundle]; [results] are the saved ranked works. */
@Serializable
data class SearchEntryDto(
    val id: String,
    val description: String = "",
    @SerialName("result_count") val resultCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean = false,
    val request: JsonObject = JsonObject(emptyMap()),
    val scenes: JsonObject = JsonObject(emptyMap()),
    val results: List<Work> = emptyList(),
)

/** What the server generated and used for one search (the `scenes` column of a history entry). */
@Serializable
data class SearchBundle(
    val scenes: List<String> = emptyList(),
    val synopsis: String = "",
    val tags: List<String> = emptyList(),
    val filters: SearchFilters = SearchFilters(),
    val reranked: Boolean = false,
    val notes: List<String> = emptyList(),
)
