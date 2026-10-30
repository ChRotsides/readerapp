package com.readerapp.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** JSON shapes of the discovery server (server/readerserver/search/schemas.py). */

@Serializable
data class SearchFilters(
    val language: String? = null,
    val status: String? = null,
    @SerialName("min_chapters") val minChapters: Int? = null,
    @SerialName("max_chapters") val maxChapters: Int? = null,
    @SerialName("include_tags") val includeTags: List<String> = emptyList(),
    @SerialName("exclude_tags") val excludeTags: List<String> = emptyList(),
)

@Serializable
data class SearchRequest(
    val description: String,
    val filters: SearchFilters? = null,
    /** When [scenes] and [synopsis] are supplied the server skips Claude's HyDE step and uses them as-is. */
    val scenes: List<String>? = null,
    val synopsis: String? = null,
    val tags: List<String>? = null,
    val limit: Int = 20,
)

@Serializable
data class Rating(
    val source: String,
    val rating: Double,
    val scale: Double,
    val votes: Int? = null,
    /** 0-10. */
    val normalized: Double = 0.0,
)

@Serializable
data class SourceUrl(val source: String, val url: String)

/**
 * A canonical work. The same shape serves `/works` (WorkOut) and `/search` results (SearchResult adds the last three
 * fields, which default for plain works).
 */
@Serializable
data class Work(
    @SerialName("work_id") val workId: String,
    val title: String,
    @SerialName("native_title") val nativeTitle: String? = null,
    val aliases: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    val synopsis: String? = null,
    val tags: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val status: String? = null,
    @SerialName("chapter_count") val chapterCount: Int? = null,
    val language: String? = null,
    @SerialName("start_year") val startYear: Int? = null,
    val ratings: List<Rating> = emptyList(),
    val urls: List<SourceUrl> = emptyList(),
    val related: List<JsonObject> = emptyList(),
    val score: Double = 0.0,
    val reason: String = "",
    @SerialName("matched_scenes") val matchedScenes: Int = 0,
)

@Serializable
data class SearchResponse(
    val results: List<Work> = emptyList(),
    val scenes: List<String> = emptyList(),
    val synopsis: String = "",
    val tags: List<String> = emptyList(),
    val filters: SearchFilters = SearchFilters(),
    val reranked: Boolean = false,
    val notes: List<String> = emptyList(),
)

/** Typed failures of the discovery server client. [message] is user-presentable. */
sealed class SearchException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : SearchException("Set the server URL and API token in Settings first.")
    class Unauthorized : SearchException("The server rejected the API token (401). Check it in Settings.")
    class BudgetReached(detail: String?) :
        SearchException("The server's monthly AI budget is used up (402).${detail?.let { " $it" }.orEmpty()}")
    /** 503: API_TOKEN unset on the server, or the Anthropic / Voyage key is missing. */
    class ServerUnavailable(val detail: String?) :
        SearchException("The server is not fully configured (503): ${detail ?: "keys missing"}.")
    class NotFound : SearchException("Not found on the server (404).")
    class Network(cause: Throwable?) : SearchException("Cannot reach the server. Is Tailscale on?", cause)
    class Timeout(cause: Throwable?) : SearchException("The server took too long to answer. Try again.", cause)
    class Http(val code: Int, detail: String?) : SearchException("Server error $code${detail?.let { ": $it" }.orEmpty()}")
    class BadResponse(cause: Throwable?) : SearchException("The server sent a response this app could not read.", cause)
}

/** Outcome of Settings > Test connection. */
sealed interface ConnectionResult {
    data class Ok(val works: Int?, val summary: String) : ConnectionResult
    data object NotConfigured : ConnectionResult
    data object Unreachable : ConnectionResult
    data object BadToken : ConnectionResult
    data class ServerKeysMissing(val detail: String?) : ConnectionResult
    data class Other(val message: String) : ConnectionResult
}

fun ConnectionResult.describe(): String = when (this) {
    is ConnectionResult.Ok -> summary
    ConnectionResult.NotConfigured -> "Enter the server URL and API token first."
    ConnectionResult.Unreachable -> "Cannot reach the server. Is Tailscale on?"
    ConnectionResult.BadToken -> "The server is reachable but rejected the API token (401)."
    is ConnectionResult.ServerKeysMissing ->
        "The server is reachable, but its keys are missing (503)${detail?.let { ": $it" }.orEmpty()}."
    is ConnectionResult.Other -> message
}
