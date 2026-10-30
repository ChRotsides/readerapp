package com.readerapp.scrape.adapter

import com.google.common.net.InternetDomainName
import java.net.URI

/**
 * Gatekeeper for every URL an adapter (JS or JSON) hands back. Such URLs are later requested by the app, so a hostile
 * adapter could otherwise smuggle data out through them. Only http(s) URLs on the page's own registrable domain pass.
 */
object AdapterUrls {
    /** Host the JS sandbox pretends to be on; never a legitimate target. */
    const val SANDBOX_HOST = "adapter-sandbox.invalid"

    /**
     * Resolves [candidate] (absolute or relative) against [pageUrl] and returns it when it is an http(s) URL on the
     * same registrable domain as [pageUrl]; null otherwise (including malformed input).
     */
    fun sameSite(candidate: String?, pageUrl: String): String? {
        val raw = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(raw) }.getOrNull() ?: return null
        val scheme = resolved.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (base.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = resolved.host?.lowercase() ?: return null
        val baseHost = base.host?.lowercase() ?: return null
        if (host == SANDBOX_HOST || host.endsWith(".invalid")) return null
        if (registrableDomain(host) != registrableDomain(baseHost)) return null
        if (!resolved.userInfo.isNullOrEmpty()) return null
        return resolved.toString()
    }

    /**
     * eTLD+1 from the Public Suffix List (Guava's copy, which includes the PRIVATE section: `blogspot.com`,
     * `github.io`, ... are public suffixes, so two tenants are never "the same site"). Hosts that are themselves a
     * public suffix are returned whole.
     */
    fun registrableDomain(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (h.matches(Regex("[0-9.]+")) || h.contains(':')) return h // IP literal
        val name = runCatching { InternetDomainName.from(h) }.getOrNull() ?: return h
        // PSL default rule "*": an unlisted TLD (`.example`, `.local`, `localhost`) counts as a one-label suffix.
        if (!name.hasPublicSuffix()) return h.split('.').takeLast(2).joinToString(".")
        return if (name.isPublicSuffix) h else name.topPrivateDomain().toString()
    }
}
