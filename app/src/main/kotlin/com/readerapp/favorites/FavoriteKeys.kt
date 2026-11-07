package com.readerapp.favorites

import com.readerapp.data.db.Favorite
import com.readerapp.data.db.Novel
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.scrape.toc.UrlKey
import com.readerapp.search.Work

/** Favorite identities. The server mirrors [normalizeUrl] in `normalize_novel_url` (api/favorites.py); both are tested against the same cases. */
object FavoriteKeys {
    private val URL = Regex("^(https?)://([^/?#]+)([^?#]*)(\\?[^#]*)?(#.*)?$", RegexOption.IGNORE_CASE)

    /**
     * The one place novel URLs are normalized for favorite keys (`url:<this>`): scheme -> https, host lowercased without
     * `www.`, fragment dropped, trailing slashes of the path dropped, query kept. Anything that is not http(s) is returned trimmed.
     */
    fun normalizeUrl(url: String): String {
        val u = url.trim()
        val m = URL.matchEntire(u) ?: return u
        val host = m.groupValues[2].lowercase().removePrefix("www.")
        val query = m.groupValues[4].takeIf { it.isNotEmpty() && it != "?" }.orEmpty()
        return "https://$host${m.groupValues[3].trimEnd('/')}$query"
    }

    /** Catalog work: stable across sites, so the same novel starred from two sources is one favorite. */
    fun forWork(workId: String): String = "work:${workId.trim().lowercase()}"

    /** Anything else, identified by its novel page. */
    fun forUrl(url: String): String = "url:${normalizeUrl(url)}"
}

/**
 * What a screen knows about a novel it can star: the key a new favorite would get, and every other way an existing
 * favorite may identify the same book (a work id, or the novel's page URLs), so the star shows however it was set.
 */
data class FavoriteRef(
    val key: String,
    val workId: String?,
    val novelUrl: String?,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val domain: String?,
    val urlKeys: Set<String>,
) {
    fun matches(f: Favorite): Boolean =
        f.key == key || (workId != null && f.workId == workId) || (f.novelUrl != null && UrlKey.of(f.novelUrl) in urlKeys)

    companion object {
        /** A library (or saved) novel: keyed by its main page, falling back to the page it was added from. */
        fun forNovel(n: Novel): FavoriteRef {
            val url = n.novelUrl ?: n.sourceUrl
            return FavoriteRef(
                key = FavoriteKeys.forUrl(url), workId = null, novelUrl = url, title = n.title, author = n.author,
                coverUrl = n.coverUrl, domain = n.domain, urlKeys = listOfNotNull(n.sourceUrl, n.novelUrl).map(UrlKey::of).toSet(),
            )
        }

        fun forWork(w: Work): FavoriteRef {
            val first = w.urls.firstOrNull()?.url
            return FavoriteRef(
                key = FavoriteKeys.forWork(w.workId), workId = w.workId.trim().lowercase(), novelUrl = first, title = w.title,
                author = w.authors.firstOrNull(), coverUrl = w.coverUrl, domain = first?.let(DomainRateLimiter::domainOf),
                urlKeys = w.urls.map { UrlKey.of(it.url) }.toSet(),
            )
        }
    }
}

fun List<Favorite>.isFavorite(ref: FavoriteRef): Boolean = any { it.favorite && ref.matches(it) }
