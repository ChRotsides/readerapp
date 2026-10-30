package com.readerapp.scrape.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs adapter JS in a throw-away offscreen WebView that is never attached to a window:
 *  - the HTML is made inert first ([InertHtml]: no scripts, handlers, javascript: URLs, <base>, meta refresh; URLs
 *    absolutised in Kotlin) and loaded under the opaque base URL [BASE_URL], never the site's origin, so the site's
 *    cookies and storage are not in scope at all; when the WebView supports multi-profile it also gets a profile of
 *    its own, which has its own (empty) cookie jar;
 *  - `blockNetworkLoads`, and `shouldInterceptRequest` answers every request with an empty response;
 *  - no `addJavascriptInterface`, file and content access off, DOM storage off, no window opening;
 *  - `document.cookie` is additionally shadowed (defence in depth only); the shared CookieManager is not touched;
 *  - the adapter result is size-capped inside the page before it is transferred, and parsed off the main thread;
 *  - hard timeout, after which the WebView is destroyed.
 * The page HTML given to it is the trimmed HTML or a fetched page; either way adapters are treated as untrusted code.
 */
@Singleton
class WebViewJsSandbox @Inject constructor(@ApplicationContext private val context: Context) : JsSandbox {
    private val gate = Semaphore(1)

    override suspend fun run(code: String, html: String, pageUrl: String): String = gate.withPermit {
        withContext(Dispatchers.Main) {
            val webView = WebView(context)
            try {
                configure(webView)
                isolateProfile(webView)
                try {
                    withTimeout(TIMEOUT_MS) { execute(webView, code, html, pageUrl) }
                } catch (e: TimeoutCancellationException) {
                    throw IllegalStateException("JS adapter timed out after ${TIMEOUT_MS / 1000}s")
                }
            } finally {
                runCatching { webView.stopLoading(); webView.destroy() }
            }
        }
    }

    /** Own browsing profile (own cookie jar/storage) when the installed WebView supports it. Must precede any load. */
    private fun isolateProfile(webView: WebView) {
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                WebViewCompat.setProfile(webView, PROFILE_NAME)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
    }

    private suspend fun execute(webView: WebView, code: String, html: String, pageUrl: String): String {
        val loaded = CompletableDeferred<Unit>()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                loaded.complete(Unit)
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                loaded.completeExceptionally(IllegalStateException("WebView render process gone"))
                return true
            }
        }
        val inert = withContext(Dispatchers.Default) { InertHtml.neutralize(html, pageUrl) }
        webView.loadDataWithBaseURL(BASE_URL, inert, "text/html", "utf-8", null)
        loaded.await()

        val result = CompletableDeferred<String>()
        webView.evaluateJavascript(wrap(code)) { value -> result.complete(value ?: "null") }
        // evaluateJavascript yields a JSON-encoded value; our wrapper returns a string, so it is a JSON string literal.
        val encoded = result.await()
        if (encoded.length > MAX_TRANSFER_CHARS) throw IllegalStateException("JS adapter result too large")
        val decoded = withContext(Dispatchers.Default) {
            (runCatching { Json.parseToJsonElement(encoded) }.getOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
        } ?: throw IllegalStateException("JS adapter returned no value")
        if (decoded.startsWith(ERROR_PREFIX)) throw IllegalStateException(decoded.removePrefix(ERROR_PREFIX))
        return decoded
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        private const val ERROR_PREFIX = "__ADAPTER_ERROR__:"
        const val PROFILE_NAME = "adapter-sandbox"

        /** Opaque, non-routable origin: no site cookies, storage or credentials can belong to it. */
        const val BASE_URL = "https://${AdapterUrls.SANDBOX_HOST}/"

        /** Longest result (JSON text) the page may return; checked in the page before anything is transferred. */
        const val MAX_RESULT_CHARS = 2_000_000

        /** JSON string-literal escaping can at most 6x the payload; anything beyond is refused unparsed. */
        private const val MAX_TRANSFER_CHARS = MAX_RESULT_CHARS * 6 + 16

        /** Adapter code must define `extract(document)`. Cookie access is neutralised first. */
        internal fun wrap(code: String): String = """
            (function() {
              try {
                try { Object.defineProperty(document, 'cookie', { get: function() { return ''; }, set: function() {} }); } catch (e) {}
                var __extract = (function() { $code
                  ; return (typeof extract === 'function') ? extract : null; })();
                if (!__extract) { return '$ERROR_PREFIX' + 'adapter does not define extract(document)'; }
                var __r = __extract(document);
                var __s = JSON.stringify(__r === undefined ? null : __r);
                if (typeof __s !== 'string') { return '$ERROR_PREFIX' + 'adapter returned no value'; }
                if (__s.length > $MAX_RESULT_CHARS) { return '$ERROR_PREFIX' + 'adapter result too large'; }
                return __s;
              } catch (e) {
                return '$ERROR_PREFIX' + String(e);
              }
            })()
        """.trimIndent()
    }
}
