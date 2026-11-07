package com.readerapp.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The discovery server, behind an interface so the screens can be tested with a fake. */
interface DiscoveryApi {
    suspend fun search(request: SearchRequest): SearchResponse
    /** Novels similar to a library novel or a corpus work; 409 ([SearchException.Http]) when the seed's chapters are not on the server yet. */
    suspend fun similar(request: SimilarRequest): SearchResponse
    suspend fun work(workId: String): Work
    suspend fun searchTitle(query: String, limit: Int = 20): List<Work>
    suspend fun testConnection(): ConnectionResult

    suspend fun startCrawl(request: CrawlRequest): CrawlJob
    /** Newest first. */
    suspend fun crawlJobs(): List<CrawlJob>
    suspend fun pauseCrawl(id: Long): CrawlJob
    suspend fun resumeCrawl(id: Long): CrawlJob
    suspend fun cancelCrawl(id: Long): CrawlJob

    /** Sends chapters the user downloaded to the server's search index (at most [LibraryUploadRequest.MAX_CHAPTERS]). */
    suspend fun uploadLibraryChapters(request: LibraryUploadRequest): LibraryUploadResult

    /** Upserts one favorite (last write wins on `changed_at`); returns the state the server now holds, which may be newer than [favorite]. */
    suspend fun putFavorite(favorite: FavoriteDto): FavoriteDto
    /** Favorites changed after [since] (the previous `server_time`, tombstones included); only live ones when null. */
    suspend fun favorites(since: String?): FavoritesResponse

    /** Saved searches, newest first, without results. With [since] (the previous `server_time`) tombstones are included. */
    suspend fun searches(since: String?, limit: Int = 200): SearchListResponse
    /** One saved search with its results; [SearchException.NotFound] when it is gone. */
    suspend fun searchEntry(id: String): SearchEntryDto
    /** Deletes a saved search on the server (already gone counts as done). */
    suspend fun deleteSearch(id: String)
}

data class ServerConfig(val baseUrl: String, val token: String)

fun interface ServerConfigProvider {
    suspend fun config(): ServerConfig?
}

class ServerApi(
    private val configProvider: ServerConfigProvider,
    private val client: OkHttpClient = defaultClient(),
) : DiscoveryApi {

    override suspend fun search(request: SearchRequest): SearchResponse {
        val body = json.encodeToString(SearchRequest.serializer(), request).toRequestBody(JSON_MEDIA)
        return call("search", { post(body) }, "search") { json.decodeFromString(SearchResponse.serializer(), it) }
    }

    override suspend fun similar(request: SimilarRequest): SearchResponse {
        val body = json.encodeToString(SimilarRequest.serializer(), request).toRequestBody(JSON_MEDIA)
        return call("similar", { post(body) }, "similar") { json.decodeFromString(SearchResponse.serializer(), it) }
    }

    override suspend fun work(workId: String): Work =
        call("works/${workId.trim()}", { get() }, null) { json.decodeFromString(Work.serializer(), it) }

    override suspend fun searchTitle(query: String, limit: Int): List<Work> =
        call("works", { get() }, null, { addQueryParameter("query", query.trim()); addQueryParameter("limit", limit.toString()) }) {
            json.decodeFromString(ListSerializer(Work.serializer()), it)
        }

    override suspend fun startCrawl(request: CrawlRequest): CrawlJob {
        val body = nullsJson.encodeToString(CrawlRequest.serializer(), request).toRequestBody(JSON_MEDIA)
        return call("crawl-jobs", { post(body) }, null) { json.decodeFromString(CrawlJob.serializer(), it) }
    }

    override suspend fun crawlJobs(): List<CrawlJob> =
        call("crawl-jobs", { get() }, null) { json.decodeFromString(ListSerializer(CrawlJob.serializer()), it) }

    override suspend fun pauseCrawl(id: Long): CrawlJob = crawlAction(id, "pause")
    override suspend fun resumeCrawl(id: Long): CrawlJob = crawlAction(id, "resume")
    override suspend fun cancelCrawl(id: Long): CrawlJob = crawlAction(id, "cancel")

    private suspend fun crawlAction(id: Long, action: String): CrawlJob =
        call("crawl-jobs/$id/$action", { post(ByteArray(0).toRequestBody(null)) }, null) { json.decodeFromString(CrawlJob.serializer(), it) }

    override suspend fun uploadLibraryChapters(request: LibraryUploadRequest): LibraryUploadResult {
        val body = nullsJson.encodeToString(LibraryUploadRequest.serializer(), request).toRequestBody(JSON_MEDIA)
        return call("library/chapters", { post(body) }, null) { json.decodeFromString(LibraryUploadResult.serializer(), it) }
    }

    override suspend fun putFavorite(favorite: FavoriteDto): FavoriteDto {
        val body = json.encodeToString(FavoriteDto.serializer(), favorite).toRequestBody(JSON_MEDIA)
        return call("favorites", { put(body) }, null) { json.decodeFromString(FavoriteDto.serializer(), it) }
    }

    override suspend fun favorites(since: String?): FavoritesResponse =
        call("favorites", { get() }, null, { since?.let { addQueryParameter("since", it) } }) {
            json.decodeFromString(FavoritesResponse.serializer(), it)
        }

    override suspend fun searches(since: String?, limit: Int): SearchListResponse =
        call("searches", { get() }, null, { since?.let { addQueryParameter("since", it) }; addQueryParameter("limit", limit.toString()) }) {
            json.decodeFromString(SearchListResponse.serializer(), it)
        }

    override suspend fun searchEntry(id: String): SearchEntryDto =
        call("searches/${id.trim()}", { get() }, null) { json.decodeFromString(SearchEntryDto.serializer(), it) }

    override suspend fun deleteSearch(id: String) {
        try {
            call("searches/${id.trim()}", { delete() }, null) { }
        } catch (e: SearchException.NotFound) {
            // already gone
        }
    }

    override suspend fun testConnection(): ConnectionResult = withContext(Dispatchers.IO) {
        try {
            runConnectionTest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult.Other("Connection test failed: ${e.message ?: e::class.java.simpleName}")
        }
    }

    private suspend fun runConnectionTest(): ConnectionResult {
        val cfg = configProvider.config() ?: return ConnectionResult.NotConfigured
        val base = cfg.baseUrl.toHttpUrlOrNull() ?: return ConnectionResult.Other("The server URL is not valid.")
        val quick = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
        try {
            val health = quick.newCall(Request.Builder().url(base.newBuilder().addPathSegment("health").build()).get().build()).await()
            health.use { if (!it.isSuccessful) return ConnectionResult.Other("Server answered /health with ${it.code}.") }
        } catch (e: IOException) {
            return ConnectionResult.Unreachable
        }
        return try {
            val resp = quick.newCall(authed(base, "admin/crawl", cfg.token) { get() }).await()
            resp.use {
                val text = it.body.string()
                when {
                    it.code == 401 -> ConnectionResult.BadToken
                    it.code == 503 -> ConnectionResult.ServerKeysMissing(detailOf(text))
                    it.isSuccessful -> {
                        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                        val works = (obj?.get("works") as? JsonPrimitive)?.intOrNull
                        ConnectionResult.Ok(works, "Connected. Token accepted${works?.let { w -> "; $w works in the catalog" }.orEmpty()}.")
                    }
                    else -> ConnectionResult.Other("Server answered ${it.code}${detailOf(text)?.let { d -> ": $d" }.orEmpty()}.")
                }
            }
        } catch (e: IOException) {
            ConnectionResult.Unreachable
        }
    }

    private suspend fun <T> call(
        path: String,
        method: Request.Builder.() -> Request.Builder,
        @Suppress("UNUSED_PARAMETER") tag: String?,
        query: HttpUrl.Builder.() -> Unit = {},
        parse: (String) -> T,
    ): T {
        val cfg = configProvider.config() ?: throw SearchException.NotConfigured()
        val base = cfg.baseUrl.toHttpUrlOrNull() ?: throw SearchException.NotConfigured()
        val request = authed(base, path, cfg.token, query, method)
        // The callback only guarantees headers; reading and parsing the body must not happen on the caller's (main) thread.
        return withContext(Dispatchers.IO) {
            try {
                execute(request, parse)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchException) {
                throw e
            } catch (e: Exception) {
                throw SearchException.Network(e)
            }
        }
    }

    private suspend fun <T> execute(request: Request, parse: (String) -> T): T {
        val response = try {
            client.newCall(request).await()
        } catch (e: InterruptedIOException) {
            throw SearchException.Timeout(e)
        } catch (e: IOException) {
            throw SearchException.Network(e)
        }
        response.use {
            val text = try {
                it.body.string()
            } catch (e: InterruptedIOException) {
                throw SearchException.Timeout(e)
            } catch (e: IOException) {
                throw SearchException.Network(e)
            }
            if (!it.isSuccessful) throw errorFor(it.code, detailOf(text))
            return try {
                parse(text)
            } catch (e: Exception) {
                throw SearchException.BadResponse(e)
            }
        }
    }

    private fun authed(
        base: HttpUrl,
        path: String,
        token: String,
        query: HttpUrl.Builder.() -> Unit = {},
        method: Request.Builder.() -> Request.Builder,
    ): Request {
        val url = base.newBuilder().apply { path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
            .apply(query).build()
        return Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
            .method().build()
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

        /** For request bodies whose nulls are meaningful (`chapters_per_novel: null` = all chapters). */
        private val nullsJson = Json(json) { explicitNulls = true }

        /** Search runs Claude (HyDE + rerank) on the server and can take 20-40 s. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        fun errorFor(code: Int, detail: String?): SearchException = when (code) {
            401 -> SearchException.Unauthorized()
            402 -> SearchException.BudgetReached(detail)
            404 -> SearchException.NotFound()
            503 -> SearchException.ServerUnavailable(detail)
            else -> SearchException.Http(code, detail)
        }

        /** FastAPI's `{"detail": "..."}` (or a validation-error list) as text. */
        fun detailOf(body: String): String? {
            val el = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            return when (val d = el["detail"]) {
                is JsonPrimitive -> d.contentOrNull
                is JsonArray -> d.joinToString("; ") { (it as? JsonObject)?.get("msg")?.let { m -> (m as? JsonPrimitive)?.contentOrNull }.orEmpty() }
                    .ifBlank { null }
                else -> null
            }
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}
