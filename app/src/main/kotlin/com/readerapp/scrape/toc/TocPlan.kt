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

/** What applying a table of contents to a novel's existing chapters changes. */
data class TocPlan(val updates: List<TocUpdate>, val inserts: List<TocInsert>) {
    companion object {
        /** Index = position in [refs] (reading order). Existing chapters keep their id, text, status and read state. */
        fun compute(existing: List<Chapter>, refs: List<ChapterRef>): TocPlan {
            val byKey = existing.associateBy { UrlKey.of(it.url) }
            val seen = HashSet<String>()
            val updates = ArrayList<TocUpdate>()
            val inserts = ArrayList<TocInsert>()
            refs.forEachIndexed { position, ref ->
                val key = UrlKey.of(ref.url)
                if (!seen.add(key)) return@forEachIndexed
                val title = ref.title.orEmpty()
                val match = byKey[key]
                if (match == null) {
                    inserts += TocInsert(ref.url, position, title)
                } else {
                    val newTitle = match.title.ifBlank { title }
                    if (match.index != position || newTitle != match.title) updates += TocUpdate(match.id, position, newTitle)
                }
            }
            return TocPlan(updates, inserts)
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
