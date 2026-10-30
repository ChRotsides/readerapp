package com.readerapp.scrape.adapter

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Makes page HTML inert before an untrusted adapter's JS is given the DOM. The adapter only needs to *read* the
 * document, so everything that could run code or cause a request is removed, and relative URLs are resolved in
 * Kotlin against the real page URL (the WebView is loaded under an opaque base URL and cannot resolve them itself).
 */
object InertHtml {
    private val URL_ATTRS = listOf("href", "src", "action", "formaction", "poster", "data", "srcset", "xlink:href", "background", "cite", "longdesc", "ping")

    fun neutralize(html: String, pageUrl: String): String {
        val doc = Jsoup.parse(html, pageUrl)
        doc.select("script, base, iframe, frame, frameset, object, embed, applet, link[rel~=(?i)(stylesheet|preload|prefetch|import|modulepreload)], noscript").remove()
        doc.select("meta[http-equiv]").forEach { m ->
            if (m.attr("http-equiv").trim().lowercase() in setOf("refresh", "set-cookie", "content-security-policy")) m.remove()
        }
        for (el in doc.getAllElements()) scrub(el)
        return doc.outerHtml()
    }

    private fun scrub(el: Element) {
        val attrs = el.attributes().asList().map { it.key }
        for (key in attrs) {
            val lower = key.lowercase()
            if (lower.startsWith("on")) {
                el.removeAttr(key)
            } else if (lower in URL_ATTRS) {
                val value = el.attr(key)
                if (lower == "srcset") continue
                val trimmed = value.replace(Regex("[\\u0000-\\u0020]+"), "")
                if (trimmed.startsWith("javascript:", ignoreCase = true) || trimmed.startsWith("data:", ignoreCase = true) ||
                    trimmed.startsWith("vbscript:", ignoreCase = true)
                ) {
                    el.removeAttr(key)
                } else {
                    val abs = el.absUrl(key)
                    if (abs.isNotEmpty()) el.attr(key, abs)
                }
            }
        }
    }
}
