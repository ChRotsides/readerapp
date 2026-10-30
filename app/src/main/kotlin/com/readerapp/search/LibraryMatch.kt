package com.readerapp.search

import com.readerapp.data.db.Novel
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.scrape.toc.UrlKey
import java.text.Normalizer

object LibraryMatch {
    private val VERSION_TAG = Regex("\\((?:wn|ln|web novel|light novel)\\)", RegexOption.IGNORE_CASE)

    /** NFKC, lowercase, "(WN)/(LN)" dropped, punctuation removed, whitespace collapsed. */
    fun normalizeTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase()
            .replace(VERSION_TAG, " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /**
     * The library novel [work] corresponds to, or null. Matches when a source URL equals the novel's stored page URL,
     * or when the novel's domain is one of the work's source domains and its normalized title equals the work's
     * title, native title or an alias.
     */
    fun find(work: Work, library: List<Novel>): Novel? {
        if (library.isEmpty()) return null
        val urlKeys = work.urls.map { UrlKey.of(it.url) }.toHashSet()
        val domains = work.urls.map { DomainRateLimiter.domainOf(it.url) }.toHashSet()
        val titles = (listOf(work.title, work.nativeTitle) + work.aliases).filterNotNull()
            .map(::normalizeTitle).filter { it.isNotEmpty() }.toHashSet()
        return library.firstOrNull { n ->
            listOfNotNull(n.sourceUrl, n.novelUrl).any { UrlKey.of(it) in urlKeys } ||
                (n.domain in domains && normalizeTitle(n.title) in titles)
        }
    }

    fun matches(works: List<Work>, library: List<Novel>): Map<String, Long> =
        works.mapNotNull { w -> find(w, library)?.let { w.workId to it.id } }.toMap()
}
