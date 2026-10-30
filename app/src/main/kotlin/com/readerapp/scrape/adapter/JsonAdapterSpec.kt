package com.readerapp.scrape.adapter

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.select.Selector

/**
 * Declarative site adapter: CSS selectors (Jsoup syntax) plus regex cleanup. Stored as JSON in
 * `SiteAdapter.code` when `kind == JSON`. All fields besides [chapter].content are optional.
 */
@Serializable
data class JsonAdapterSpec(
    val chapter: ChapterSpec,
    val novel: NovelSpec? = null,
    val toc: TocSpec? = null,
    val cleanup: CleanupSpec = CleanupSpec(),
) {
    /** Returns human-readable problems; empty when the spec is usable. */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        val probe = Jsoup.parse("<html><body></body></html>")
        fun selector(name: String, sel: String?) {
            if (sel.isNullOrBlank()) return
            if (sel.length > MAX_SELECTOR_LEN) errors += "$name: selector too long"
            else runCatching { probe.select(sel) }.onFailure { errors += "$name: invalid CSS selector '$sel' (${it.message})" }
        }
        if (chapter.content.isBlank()) errors += "chapter.content is required"
        selector("chapter.content", chapter.content)
        selector("chapter.title", chapter.title)
        selector("chapter.paragraphs", chapter.paragraphs)
        chapter.remove.forEachIndexed { i, s -> selector("chapter.remove[$i]", s) }
        selector("chapter.next", chapter.next)
        selector("chapter.prev", chapter.prev)
        selector("chapter.novelLink", chapter.novelLink)
        novel?.let {
            selector("novel.title", it.title); selector("novel.author", it.author)
            selector("novel.cover", it.cover); selector("novel.synopsis", it.synopsis)
        }
        toc?.let {
            if (it.items.isBlank()) errors += "toc.items is required when toc is present"
            selector("toc.items", it.items); selector("toc.nextPage", it.nextPage)
        }
        cleanup.dropLines.forEachIndexed { i, r ->
            if (r.length > MAX_REGEX_LEN) errors += "cleanup.dropLines[$i]: pattern too long"
            else runCatching { Regex(r) }.onFailure { errors += "cleanup.dropLines[$i]: invalid regex (${it.message})" }
        }
        return errors
    }

    fun toJson(): String = CODEC.encodeToString(serializer(), this)

    companion object {
        const val MAX_SELECTOR_LEN = 300
        const val MAX_REGEX_LEN = 200

        val CODEC = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; prettyPrint = true }

        fun parse(text: String): JsonAdapterSpec = CODEC.decodeFromString(serializer(), text)
    }
}

@Serializable
data class ChapterSpec(
    /** Container element holding the chapter text. Required. */
    val content: String,
    /** Title element (text is used). */
    val title: String? = null,
    /** Paragraph elements inside [content]; when null the container's block structure is split into paragraphs. */
    val paragraphs: String? = null,
    /** Elements inside [content] to delete first (ads, share bars, ...). */
    val remove: List<String> = emptyList(),
    /** Link (or element containing a link) to the next / previous chapter; its href is used. */
    val next: String? = null,
    val prev: String? = null,
    /** Link to the novel's main page (breadcrumb or "table of contents" link) on the chapter page. */
    val novelLink: String? = null,
)

@Serializable
data class NovelSpec(
    val title: String? = null,
    val author: String? = null,
    /** Cover element: an `img` (src / data-src), or any element with a `content` attribute (meta). */
    val cover: String? = null,
    val synopsis: String? = null,
)

@Serializable
data class TocSpec(
    /** Links (`a`) to chapters, in reading order. */
    val items: String,
    /** Link to the next page of the chapter list, if the list is paginated. */
    val nextPage: String? = null,
)

@Serializable
data class CleanupSpec(
    /** Regexes; any paragraph that contains a match is dropped (on top of the built-in junk patterns). */
    val dropLines: List<String> = emptyList(),
)
