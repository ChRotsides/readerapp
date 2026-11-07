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
    /** Scenes Claude writes (null = the server's HYDE_SCENE_COUNT). */
    @SerialName("scene_count") val sceneCount: Int? = null,
    /** Top candidates Claude reranks (null = the server's RERANK_POOL). */
    @SerialName("rerank_pool") val rerankPool: Int? = null,
    /** "jev" or "claude" (null = the server's RERANK_PROVIDER). */
    @SerialName("rerank_provider") val rerankProvider: String? = null,
)

/**
 * `POST /similar`: novels like a seed, which is either a library novel ([libraryUrl], the url the uploader sent as
 * `novel.url`, plus the [chapters] to compare by) or a corpus work ([workId]); exactly one of the two.
 */
@Serializable
data class SimilarRequest(
    @SerialName("library_url") val libraryUrl: String? = null,
    @SerialName("work_id") val workId: String? = null,
    /** Seed chapter indexes, the same `index` the uploader sends. */
    val chapters: List<Int>? = null,
    /** Leave the user's own library novels out of the results. */
    @SerialName("hide_library") val hideLibrary: Boolean = true,
    val filters: SearchFilters? = null,
    @SerialName("scene_count") val sceneCount: Int? = null,
    @SerialName("rerank_pool") val rerankPool: Int? = null,
    @SerialName("rerank_provider") val rerankProvider: String? = null,
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
    /** Starred on the server. The screens use the local favorites table (works offline); this is what the server knew. */
    val favorite: Boolean = false,
    val score: Double = 0.0,
    val reason: String = "",
    @SerialName("matched_scenes") val matchedScenes: Int = 0,
    /** Jev's fit score normalized 0-1 (null when Claude, or nothing, reranked). */
    @SerialName("rerank_score") val rerankScore: Double? = null,
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
    /** Id of the history entry the server saved for this search (null if saving failed or the server is older). */
    @SerialName("history_id") val historyId: String? = null,
    /** "jev" or "claude" when [reranked]. */
    @SerialName("rerank_provider_used") val rerankProviderUsed: String? = null,
    /** Title of the novel a `/similar` search started from (null for plain searches). */
    @SerialName("seed_title") val seedTitle: String? = null,
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
    /** None of the novel's seed chapters could be downloaded and uploaded for a similar-novels search. */
    class NoSeedChapters : SearchException("None of this novel's opening or current chapters could be downloaded, so there is nothing to compare. Open the novel and read a chapter first.")
    /** The server cannot find or use the seed's chapters even after they were uploaded again (409 / 404 twice). */
    class SeedNotOnServer : SearchException("The server could not find this novel's chapters even after they were uploaded. Try again in a minute; if it keeps failing, check the server.")
    /** Every seed chapter was refused by the server (422). */
    class SeedRejected : SearchException("The server refused all of this novel's seed chapters, so it cannot be compared.")
    /** 429 on a similar search. */
    class RateLimited : SearchException("The server's embedding rate limit was hit. Try again in a minute.")
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
