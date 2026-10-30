package com.readerapp.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FetchException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : IOException(message, cause)

@Singleton
class HttpFetcher @Inject constructor(private val rateLimiter: SharedRateLimiter) {
    /** Shared client: cookies via WebView's CookieManager, consistent desktop UA. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", UserAgents.DESKTOP)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build(),
            )
        }
        .build()

    suspend fun fetch(url: String): FetchedPage {
        rateLimiter.limiter.acquire(url)
        val request = Request.Builder().url(url).build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw FetchException("HTTP ${response.code} for $url", response.code)
            val bytes = withContext(Dispatchers.IO) { response.body.bytes() }
            val finalUrl = response.request.url.toString()
            FetchedPage(finalUrl, decode(bytes, response.body.contentType()), FetchMode.HTTP)
        }
    }

    private fun decode(bytes: ByteArray, type: MediaType?): String {
        val charset = type?.charset()
            ?: sniffMetaCharset(bytes)
            ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    private fun sniffMetaCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val name = META_CHARSET.find(head)?.groupValues?.get(1) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private companion object {
        val META_CHARSET = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(FetchException(e.message ?: "Network error", cause = e))
        }

        override fun onResponse(call: Call, response: Response) {
            // If the caller was cancelled between resume and delivery, the response must still be closed.
            cont.resume(response) { response.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
