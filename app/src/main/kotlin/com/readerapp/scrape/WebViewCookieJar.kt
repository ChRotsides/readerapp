package com.readerapp.scrape

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** OkHttp cookie jar backed by the WebView's [CookieManager] so logins/bot-check cookies are shared. */
class WebViewCookieJar : CookieJar {
    private val manager: CookieManager get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val target = url.toString()
        cookies.forEach { manager.setCookie(target, it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = manager.getCookie(url.toString()).orEmpty()
        if (header.isBlank()) return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
