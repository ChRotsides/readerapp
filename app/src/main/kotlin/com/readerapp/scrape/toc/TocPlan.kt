package com.readerapp.scrape.toc

import com.readerapp.data.db.Chapter
import com.readerapp.scrape.ChapterRef
import java.net.URI

/** Canonical form of a chapter URL for matching (case of host, `www.`, fragment and trailing slash ignored). */
object UrlKey {
    fun of(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url.trim().trimEnd('/')
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return url.trim().trimEnd('/')
        val path = (uri.rawPath ?: "").trimEnd('/')
        return host + path + (uri.rawQuery?.let { "?$it" } ?: "")
    }
}

data class TocUpdate(val chapterId: Long, val index: Int, val title: String)

data class TocInsert(val url: String, val index: Int, val title: String)

/**
 * What applying a table of contents to a novel's existing chapters changes.
 * [deletes] are leftover placeholders (never fetched, never read) the TOC does not list; every other existing chapter
 * the TOC does not list is kept and appended after the TOC, in its old relative order (see [compute]).
 */
data class TocPlan(val updates: List<TocUpdate>, val inserts: List<TocInsert>, val deletes: List<Long> = emptyList()) {
    /** Smallest reading position (old or new) whose occupant changed; arcs ending at or after it are stale. */
    fun firstAffectedIndex(existing: List<Chapter>): Int? {
        val old = existing.associateBy { it.id }
        val moved = updates.mapNotNull { u -> old[u.chapterId]?.takeIf { it.index != u.index }?.let { minOf(it.index, u.index) } }
        return (moved + inserts.map { it.index } + deletes.mapNotNull { old[it]?.index }).minOrNull()
    }

    companion object {
        /**
         * Index = position in [refs] (reading order). Existing chapters keep their id, text, status and read state.
         * Existing chapters the TOC does not list are reconciled explicitly instead of being left at their old
         * indices (where they would interleave with the imported ones): placeholders that carry no user data are
         * dropped, the rest are appended after the TOC keeping their relative order.
         */
        fun compute(existing: List<Chapter>, refs: List<ChapterRef>): TocPlan {
            val byKey = existing.associateBy { UrlKey.of(it.url) }
            val seen = HashSet<String>()
            val updates = ArrayList<TocUpdate>()
            val inserts = ArrayList<TocInsert>()
            val matched = HashSet<Long>()
            var total = 0
            refs.forEachIndexed { position, ref ->
                val key = UrlKey.of(ref.url)
                if (!seen.add(key)) return@forEachIndexed
                total = position + 1
                val title = ref.title.orEmpty()
                val match = byKey[key]
                if (match == null) {
                    inserts += TocInsert(ref.url, position, title)
                } else {
                    matched += match.id
                    val newTitle = match.title.ifBlank { title }
                    if (match.index != position || newTitle != match.title) updates += TocUpdate(match.id, position, newTitle)
                }
            }
            val deletes = ArrayList<Long>()
            var next = total
            existing.filter { it.id !in matched }.sortedWith(compareBy({ it.index }, { it.id })).forEach { c ->
                val disposable = c.status == com.readerapp.data.db.ChapterStatus.NOT_FETCHED && !c.read &&
                    c.scrollIndex == 0 && c.scrollOffset == 0
                if (disposable) {
                    deletes += c.id
                } else {
                    if (c.index != next) updates += TocUpdate(c.id, next, c.title)
                    next++
                }
            }
            return TocPlan(updates, inserts, deletes)
        }
    }
}

object TocOrder {
    private val NUMBER = Regex("(\\d+)")

    /** Some sites list newest first: reverse when the chapter numbers in the titles clearly descend. */
    fun normalize(refs: List<ChapterRef>): List<ChapterRef> {
        val numbers = refs.mapNotNull { r -> r.title?.let { NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() } }
        if (numbers.size < 2) return refs
        val descending = numbers.zipWithNext().count { (a, b) -> a > b }
        val ascending = numbers.zipWithNext().count { (a, b) -> a < b }
        return if (descending > ascending) refs.reversed() else refs
    }
}
