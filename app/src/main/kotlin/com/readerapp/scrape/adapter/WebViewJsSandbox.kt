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
 *  - HTML is loaded with loadDataWithBaseURL (base URL only for relative-link resolution);
 *  - `blockNetworkLoads`, and `shouldInterceptRequest` answers every request with an empty response;
 *  - no `addJavascriptInterface`, file and content access off, DOM storage off, no window opening;
 *  - `document.cookie` is shadowed before the adapter runs; the shared CookieManager is not touched;
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
        webView.loadDataWithBaseURL(pageUrl, html, "text/html", "utf-8", null)
        loaded.await()

        val result = CompletableDeferred<String>()
        webView.evaluateJavascript(wrap(code)) { value -> result.complete(value ?: "null") }
        // evaluateJavascript yields a JSON-encoded value; our wrapper returns a string, so it is a JSON string literal.
        val encoded = result.await()
        val decoded = (runCatching { Json.parseToJsonElement(encoded) }.getOrNull() as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?: throw IllegalStateException("JS adapter returned no value")
        if (decoded.startsWith(ERROR_PREFIX)) throw IllegalStateException(decoded.removePrefix(ERROR_PREFIX))
        return decoded
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        private const val ERROR_PREFIX = "__ADAPTER_ERROR__:"

        /** Adapter code must define `extract(document)`. Cookie access is neutralised first. */
        internal fun wrap(code: String): String = """
            (function() {
              try {
                try { Object.defineProperty(document, 'cookie', { get: function() { return ''; }, set: function() {} }); } catch (e) {}
                var __extract = (function() { $code
                  ; return (typeof extract === 'function') ? extract : null; })();
                if (!__extract) { return '$ERROR_PREFIX' + 'adapter does not define extract(document)'; }
                var __r = __extract(document);
                return JSON.stringify(__r === undefined ? null : __r);
              } catch (e) {
                return '$ERROR_PREFIX' + String(e);
              }
            })()
        """.trimIndent()
    }
}
