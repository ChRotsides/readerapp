package com.readerapp.scrape

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Converts chapter HTML into trimmed paragraphs and removes watermark/junk lines. */
class TextCleaner(private val junkPatterns: List<Regex> = DEFAULT_JUNK_PATTERNS) {

    /** Splits on block elements and `<br>`, collapses whitespace, drops empties and junk. */
    fun toParagraphs(html: String): List<String> {
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script, style, noscript, template, iframe, svg, form, button").remove()

        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            val text = normalize(current.toString())
            current.setLength(0)
            if (text.isNotEmpty()) out += text
        }
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when {
                        node is TextNode -> current.append(node.wholeText)
                        node is Element && (node.tagName() == "br" || node.tagName() in BLOCK_TAGS) -> flush()
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.tagName() in BLOCK_TAGS) flush()
                }
            },
            body,
        )
        flush()
        return removeJunk(out)
    }

    fun removeJunk(paragraphs: List<String>): List<String> =
        paragraphs.map { normalize(it) }.filter { p -> p.isNotEmpty() && junkPatterns.none { it.containsMatchIn(p) } }

    private fun normalize(text: String): String =
        text.replace(WHITESPACE, " ").trim()

    companion object {
        private val BLOCK_TAGS = setOf(
            "p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section",
            "article", "header", "footer", "tr", "table", "pre", "hr", "dd", "dt", "figure", "figcaption",
        )

        // Any run of whitespace incl. NBSP, ideographic space and zero-width characters.
        private val WHITESPACE = Regex("[\\s\\u00A0\\u3000\\u200B\\u200C\\u200D\\uFEFF]+")

        /** A paragraph that is only a counter, e.g. Webnovel's per-paragraph comment counts ("13", "1.2k", "99+"). */
        val COUNTER_PARAGRAPH = Regex("^\\d{1,5}(\\.\\d)?[kK]?\\+?$")

        /** Conservative defaults; each pattern is matched against a whole normalized paragraph. */
        val DEFAULT_JUNK_PATTERNS: List<Regex> = listOf(
            COUNTER_PARAGRAPH,
            Regex("^(please\\s+)?(read|find)\\s+(the\\s+)?(latest|newest|more)\\s+chapters?\\s+(at|on|from)\\b.{0,80}$", RegexOption.IGNORE_CASE),
            Regex("^(visit|find us at|read at|source:?|translated (by|at|from)|support (us|the author) (on|at))\\s*:?\\s*(https?://|www\\.)\\S+.{0,40}$", RegexOption.IGNORE_CASE),
            Regex("^\\W*(https?://|www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(com|net|org|io|co|me|cc|xyz)\\W*$", RegexOption.IGNORE_CASE),
            Regex("^(if you (find|see|notice) any (errors|mistakes|broken links?)|report (a )?(broken|error)).{0,120}$", RegexOption.IGNORE_CASE),
            Regex("^(tap|click) (the )?(screen|here).{0,60}$", RegexOption.IGNORE_CASE),
            Regex("^.{0,40}(请记住本书首发域名|手机用户请浏览|最新章节请访问|天才一秒记住).{0,80}$"),
            Regex("^.{0,30}(最新网址|无弹窗|笔趣阁).{0,60}$"),
        )
    }
}
