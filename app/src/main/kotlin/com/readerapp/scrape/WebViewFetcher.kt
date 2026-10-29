package com.readerapp.scrape

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Loads a URL in an offscreen WebView (runs site JS, shares cookies/logins with the in-app browser)
 * and returns the rendered DOM. Everything WebView-related happens on the main thread.
 */
@Singleton
class WebViewFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rateLimiter: SharedRateLimiter,
) {
    suspend fun fetch(
        url: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        settleMs: Long = DEFAULT_SETTLE_MS,
    ): FetchedPage {
        rateLimiter.limiter.acquire(url)
        return withContext(Dispatchers.Main.immediate) {
            try {
                withTimeout(timeoutMs) { load(url, settleMs) }
            } catch (e: TimeoutCancellationException) {
                throw FetchException("WebView timed out after ${timeoutMs}ms for $url", cause = e)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String, settleMs: Long): FetchedPage = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val webView = WebView(context)
        var finished = false
        fun finish(block: CancellableContinuation<FetchedPage>.() -> Unit) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            cont.block()
            webView.stopLoading()
            webView.destroy()
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = UserAgents.DESKTOP
            loadsImagesAutomatically = false
            blockNetworkImage = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // Redirect chains restart the settle timer.
                handler.removeCallbacksAndMessages(null)
            }

            override fun onPageFinished(view: WebView, finishedUrl: String?) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    if (finished) return@postDelayed
                    view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
                        val html = decodeJsString(raw)
                        val finalUrl = view.url ?: url
                        finish {
                            if (html.isNullOrEmpty()) {
                                resumeWithException(FetchException("WebView returned empty DOM for $url"))
                            } else {
                                resume(FetchedPage(finalUrl, html, FetchMode.WEBVIEW))
                            }
                        }
                    }
                }, settleMs)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    finish { resumeWithException(FetchException("WebView error ${error.errorCode}: ${error.description}")) }
                }
            }
        }
        cont.invokeOnCancellation {
            handler.post {
                if (!finished) {
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }
        webView.loadUrl(url)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 40_000L
        const val DEFAULT_SETTLE_MS = 1_500L

        /** `evaluateJavascript` returns a JSON-encoded value; "null" for no result. */
        fun decodeJsString(raw: String?): String? {
            if (raw == null) return null
            val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
            return (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        }
    }
}
