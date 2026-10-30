package com.readerapp.scrape

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
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
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            try {
                withTimeout(timeoutMs) { load(webView, handler, url, settleMs) }
            } catch (e: TimeoutCancellationException) {
                throw FetchException("WebView timed out after ${timeoutMs}ms for $url", cause = e)
            } finally {
                // Always runs, also on cancellation, and on the main thread where WebView must be destroyed.
                withContext(NonCancellable) {
                    handler.removeCallbacksAndMessages(null)
                    runCatching { webView.stopLoading(); webView.destroy() }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(webView: WebView, handler: Handler, url: String, settleMs: Long): FetchedPage =
        suspendCancellableCoroutine { cont ->
            var settle: Runnable? = null
            // Status of the main-frame response of the navigation currently in flight (reset on every navigation).
            var mainFrameStatus: Int? = null

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UserAgents.DESKTOP
                loadsImagesAutomatically = false
                blockNetworkImage = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            fun cancelSettle() {
                settle?.let { handler.removeCallbacks(it) }
                settle = null
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    // Redirect chains (and JS challenges that reload) restart the settle timer and the status.
                    cancelSettle()
                    mainFrameStatus = null
                }

                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    cancelSettle()
                    val task = Runnable {
                        if (!cont.isActive) return@Runnable
                        val status = mainFrameStatus
                        if (status != null) {
                            // An HTTP error page is not a chapter: never hand its DOM to extraction.
                            cont.resumeWithException(FetchException("HTTP $status for $url", status))
                            return@Runnable
                        }
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
                            if (!cont.isActive) return@evaluateJavascript
                            val html = decodeJsString(raw)
                            val finalUrl = view.url ?: url
                            if (html.isNullOrEmpty()) {
                                cont.resumeWithException(FetchException("WebView returned empty DOM for $url"))
                            } else {
                                cont.resume(FetchedPage(finalUrl, html, FetchMode.WEBVIEW))
                            }
                        }
                    }
                    settle = task
                    handler.postDelayed(task, settleMs)
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) mainFrameStatus = errorResponse.statusCode
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && cont.isActive) {
                        cont.resumeWithException(FetchException("WebView error ${error.errorCode}: ${error.description}"))
                    }
                }
            }
            cont.invokeOnCancellation { handler.post { cancelSettle() } }
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
