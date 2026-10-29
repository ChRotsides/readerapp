package com.readerapp.scrape

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Heuristic detection of next/previous chapter links on a chapter page. */
object ChapterLinkDetector {

    data class Links(val next: String?, val prev: String?)

    fun detect(doc: Document, pageUrl: String): Links = Links(
        next = find(doc, pageUrl, Direction.NEXT),
        prev = find(doc, pageUrl, Direction.PREV),
    )

    private enum class Direction(
        val relValues: Set<String>,
        val englishPrefixes: List<String>,
        val shortTokens: List<String>,
        val attrRegex: Regex,
    ) {
        NEXT(
            relValues = setOf("next"),
            englishPrefixes = listOf("next"),
            shortTokens = listOf("下一章", "下一节", "下一话", "下一回", "下一篇", "下章", "다음", "次の章", "次へ", "次話", "次章"),
            attrRegex = Regex("(^|[^a-z])next([^a-z]|$)|nextchap|next_chap"),
        ),
        PREV(
            relValues = setOf("prev", "previous"),
            englishPrefixes = listOf("prev", "previous"),
            shortTokens = listOf("上一章", "上一节", "上一话", "上一回", "上一篇", "上章", "이전", "前の章", "前へ", "前話", "前章"),
            attrRegex = Regex("(^|[^a-z])(prev|previous)([^a-z]|$)|prevchap|prev_chap"),
        ),
    }

    private val ARROWS = mapOf(
        Direction.NEXT to setOf("→", "›", "»", ">", ">>", "▶", "➡"),
        Direction.PREV to setOf("←", "‹", "«", "<", "<<", "◀", "⬅"),
    )

    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s]")
    private val SPACES = Regex("\\s+")
    private const val MAX_TEXT_LEN = 25

    private fun find(doc: Document, pageUrl: String, dir: Direction): String? {
        val pageKey = pageUrl.substringBefore('#')
        fun Element.target(): String? {
            val raw = attr("href").trim()
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true)) {
                return null
            }
            val abs = absUrl("href").ifBlank { return null }
            if (!abs.startsWith("http", ignoreCase = true)) return null
            return abs.substringBefore('#').takeIf { it != pageKey }
        }

        // 1. rel=next / rel=prev (on <link> and <a>)
        doc.select("link[rel][href], a[rel][href]")
            .filter { el -> el.attr("rel").lowercase().split(' ').any { it in dir.relValues } }
            .firstNotNullOfOrNull { it.target() }
            ?.let { return it }

        val anchors = doc.select("a[href]")

        // 2. link text
        anchors.firstNotNullOfOrNull { a -> if (textMatches(a.text(), dir)) a.target() else null }?.let { return it }

        // 3. id / class / title / aria-label hints
        anchors.firstNotNullOfOrNull { a ->
            val hints = listOf(a.id(), a.className(), a.attr("title"), a.attr("aria-label"), a.attr("data-action"))
                .joinToString(" ").lowercase()
            if (hints.isNotBlank() && dir.attrRegex.containsMatchIn(hints) && !isPagination(a)) a.target() else null
        }?.let { return it }

        // 4. bare arrow glyphs
        return anchors.firstNotNullOfOrNull { a ->
            if (a.text().trim() in ARROWS.getValue(dir)) a.target() else null
        }
    }

    private fun isPagination(a: Element): Boolean =
        a.parents().any { p -> p.className().lowercase().let { "pagination" in it || "pager" in it } }

    private fun textMatches(raw: String, direction: Direction): Boolean {
        val text = SPACES.replace(NON_WORD.replace(raw.lowercase(), " "), " ").trim()
        if (text.isEmpty() || text.length > MAX_TEXT_LEN) return false
        if (direction.englishPrefixes.any { text == it || text.startsWith("$it ") }) return true
        return direction.shortTokens.any { text.contains(it) }
    }
}
