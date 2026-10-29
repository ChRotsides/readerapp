package com.readerapp.scrape

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

data class TrimmedHtml(
    val html: String,
    /** True when the page was cut down to fit [HtmlTrimmer.MAX_CHARS]; the output is then NOT the complete page. */
    val reduced: Boolean,
    val originalLength: Int,
)

/**
 * Produces the compact HTML that is sent to models and stored in fixtures: no scripts/styles/svg/etc., no
 * comments, only id/class/href/rel attributes, collapsed whitespace. Oversized pages are reduced around their
 * main text region and say so explicitly (comment marker at the top + [TrimmedHtml.reduced]).
 */
object HtmlTrimmer {
    const val MAX_CHARS = 120_000

    private const val REMOVE = "script, style, svg, noscript, iframe, link, meta, template, object, embed, canvas"
    private val KEEP_ATTRS = setOf("id", "class", "href", "rel")

    fun trim(html: String, baseUrl: String = "", maxChars: Int = MAX_CHARS): TrimmedHtml {
        val doc = Jsoup.parse(html, baseUrl)
        doc.outputSettings().prettyPrint(false)
        clean(doc)
        val full = serialize(doc)
        if (full.length <= maxChars) return TrimmedHtml(full, reduced = false, originalLength = full.length)
        return TrimmedHtml(reduce(doc, full.length, maxChars), reduced = true, originalLength = full.length)
    }

    private fun serialize(doc: Document): String = doc.html().replace(Regex(">\\s+<"), "> <").trim()

    private fun clean(doc: Document) {
        doc.select(REMOVE).remove()
        val comments = ArrayList<Node>()
        val texts = ArrayList<TextNode>()
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is Comment -> comments += node
                        is TextNode -> texts += node
                        is Element -> node.attributes().asList().map { it.key }
                            .filter { it !in KEEP_ATTRS }
                            .forEach { node.removeAttr(it) }
                    }
                }

                override fun tail(node: Node, depth: Int) = Unit
            },
            doc,
        )
        comments.forEach { it.remove() }
        val inPre = { t: TextNode -> t.parent()?.let { p -> (p as? Element)?.closest("pre") != null } == true }
        texts.forEach { t ->
            if (!inPre(t)) {
                val collapsed = t.wholeText.replace(Regex("\\s+"), " ")
                if (collapsed.isBlank()) t.text(" ") else t.text(collapsed)
            }
        }
    }

    /**
     * Keeps `<head>` (title only after cleaning), the ancestor chain down to the main text container, short
     * summaries (with links) of everything off that chain, and as many leading/trailing children of the
     * container as fit.
     */
    private fun reduce(doc: Document, originalLength: Int, maxChars: Int): String {
        val body = doc.body()
        val container = mainTextContainer(body)
        val chain = generateSequence(container) { it.parent() }.takeWhile { it !== body.parent() }.toSet()

        // Summarise everything hanging off the chain (but not the container itself).
        for (el in chain.toList()) {
            if (el === container) continue
            for (sibling in el.children().toList()) {
                if (sibling in chain) continue
                summarise(sibling)
            }
        }

        val kids = container.children().toList()
        if (kids.isEmpty()) {
            val room = (maxChars - serialize(doc).length + container.text().length - 400).coerceAtLeast(2_000)
            container.text(container.text().take(room))
            return finish(serialize(doc), originalLength)
        }
        val sizes = kids.map { it.outerHtml().length }
        val fixed = serialize(doc).length - sizes.sum()
        var budget = (maxChars - fixed - 400).coerceAtLeast(2_000)
        var keep = chooseKept(sizes, budget)
        while (keep.size < kids.size && fixed + keep.sumOf { sizes[it] } > maxChars - 400 && budget > 500) {
            budget = (budget * 0.8).toInt()
            keep = chooseKept(sizes, budget)
        }
        var omitted = 0
        kids.forEachIndexed { i, k ->
            if (i in keep) {
                if (omitted > 0) k.before(Comment(" $omitted elements omitted "))
                omitted = 0
            } else {
                k.remove(); omitted++
            }
        }
        if (omitted > 0) container.appendChild(Comment(" $omitted elements omitted "))
        return finish(serialize(doc).take(maxChars), originalLength)
    }

    /** Leading three quarters of [budget] from the start of the container, the rest from its end. */
    private fun chooseKept(sizes: List<Int>, budget: Int): Set<Int> {
        val keep = HashSet<Int>()
        var used = 0
        for (i in sizes.indices) {
            if (used + sizes[i] > budget * 3 / 4) break
            used += sizes[i]; keep += i
        }
        for (i in sizes.indices.reversed()) {
            if (i in keep) continue
            if (used + sizes[i] > budget) break
            used += sizes[i]; keep += i
        }
        return keep
    }

    private fun finish(html: String, originalLength: Int): String =
        "<!-- TRIMMED: page reduced from $originalLength to ${html.length} chars around its main text region; " +
            "it is NOT the complete page. Elements off the main path are summarised. -->\n$html"

    /** Descends from [body] while one child holds more than half of the text of its parent. */
    private fun mainTextContainer(body: Element): Element {
        var current = body
        while (true) {
            val total = current.text().length
            val next = current.children().maxByOrNull { it.text().length } ?: return current
            if (total == 0 || next.text().length * 2 <= total) return current
            current = next
        }
    }

    private fun summarise(el: Element) {
        val links = el.select("a[href]").take(40).map { a ->
            Element("a").attr("href", a.attr("href")).text(a.text().take(40))
        }
        val text = el.ownText().take(80)
        el.empty()
        if (text.isNotBlank()) el.appendText(text)
        links.forEach { el.appendChild(it) }
    }
}
