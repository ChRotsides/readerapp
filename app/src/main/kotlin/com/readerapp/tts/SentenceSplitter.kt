package com.readerapp.tts

/** One speakable piece of a paragraph: [start, end) in the paragraph as displayed, and the cleaned text to synthesize. */
data class SentenceSpan(val start: Int, val end: Int, val text: String)

/**
 * Splits displayed paragraphs into sentence-sized synthesis units. The result is deterministic, so the reader can
 * recompute it to find the character range of the sentence being spoken (index = sentence number in the paragraph).
 */
object SentenceSplitter {
    /** Raw pieces longer than this are split at commas / semicolons to keep synthesis latency low. */
    const val MAX_CHARS = 240
    private const val MIN_PIECE = 60

    private const val TERMINATORS = ".!?…。！？‼⁇⁈⁉"
    private const val CLOSERS = "\"'”’»)]}」』）】〉》"
    private const val CJK_TERMINATORS = "。！？‼⁇⁈⁉"
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc", "gen", "col", "capt", "lt", "sgt",
        "cmdr", "rev", "hon", "no", "fig", "vol", "inc", "ltd", "co", "e.g", "i.e", "approx", "dept", "est",
    )

    fun split(paragraph: String): List<SentenceSpan> {
        val out = ArrayList<SentenceSpan>()
        for ((s, e) in boundaries(paragraph)) {
            for ((ps, pe) in splitLong(paragraph, s, e)) {
                val text = clean(paragraph.substring(ps, pe))
                if (isSpeakable(text)) out += SentenceSpan(ps, pe, text)
            }
        }
        return out
    }

    private fun boundaries(t: String): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var start = 0
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\n') {
                addTrimmed(t, start, i, result)
                start = i + 1
                i++
                continue
            }
            if (c in TERMINATORS) {
                var j = i
                while (j + 1 < t.length && t[j + 1] in TERMINATORS) j++
                var k = j
                while (k + 1 < t.length && t[k + 1] in CLOSERS) k++
                val end = k + 1
                if (isBoundary(t, i, j, end)) {
                    addTrimmed(t, start, end, result)
                    start = end
                }
                i = end
                continue
            }
            i++
        }
        addTrimmed(t, start, t.length, result)
        return result
    }

    private fun isBoundary(t: String, termStart: Int, termEnd: Int, end: Int): Boolean {
        if (end >= t.length) return true
        val cjk = t[termEnd] in CJK_TERMINATORS
        if (!cjk && !t[end].isWhitespace()) return false // "3.14", "U.S.A", 'said."x'
        var n = end
        while (n < t.length && t[n].isWhitespace()) n++
        if (n >= t.length) return true
        if (t.substring(end, n).contains('\n')) return true
        if (!cjk && t[n].isLowerCase()) return false // '"Hello?" she said', 'Well... maybe'
        if (t[termStart] == '.' && termEnd == termStart && end == termEnd + 1) {
            val word = wordBefore(t, termStart)
            if (word.lowercase() in ABBREVIATIONS) return false
            if (word.length == 1 && word[0].isUpperCase()) return false // initials: "J. R. R. Tolkien"
        }
        return true
    }

    private fun wordBefore(t: String, idx: Int): String {
        var s = idx
        while (s > 0 && (t[s - 1].isLetter() || t[s - 1] == '.')) s--
        return t.substring(s, idx)
    }

    private fun addTrimmed(t: String, from: Int, to: Int, out: MutableList<Pair<Int, Int>>) {
        var s = from
        var e = to
        while (s < e && t[s].isWhitespace()) s++
        while (e > s && t[e - 1].isWhitespace()) e--
        if (e > s) out += s to e
    }

    private fun splitLong(t: String, start: Int, end: Int): List<Pair<Int, Int>> {
        if (end - start <= MAX_CHARS) return listOf(start to end)
        val pieces = ArrayList<Pair<Int, Int>>()
        var s = start
        while (end - s > MAX_CHARS) {
            val limit = s + MAX_CHARS
            var cut = -1
            // Prefer the last comma-like break inside the window (but not too early), then any space.
            for (i in limit - 1 downTo s + MIN_PIECE) {
                if (t[i] in ",;:，；：、—") { cut = i + 1; break }
            }
            if (cut < 0) for (i in limit - 1 downTo s + MIN_PIECE) {
                if (t[i].isWhitespace()) { cut = i + 1; break }
            }
            if (cut < 0) cut = limit
            addTrimmed(t, s, cut, pieces)
            s = cut
        }
        addTrimmed(t, s, end, pieces)
        return pieces
    }

    private val URL = Regex("""https?://\S+|www\.\S+""")
    private val TAG = Regex("""<[^>\n]{1,80}>""")
    private val JUNK_CHARS = Regex("""[\u0000-\u0008\u000B-\u001F\u007F​-‏ -‮⁠﻿*_~^#|\\`]""")
    private val SPACES = Regex("""\s+""")
    private val DASHES = Regex("""[-–—]{2,}|\s[-–—]\s|—""")

    /** Text as sent to the synthesizer: junk stripped, CJK punctuation mapped to ASCII, whitespace collapsed. */
    fun clean(raw: String): String {
        var t = URL.replace(raw, " ")
        t = TAG.replace(t, " ")
        t = JUNK_CHARS.replace(t, " ")
        val mapped = StringBuilder(t.length)
        for (c in t) {
            mapped.append(
                when (c) {
                    '，', '、' -> ", "
                    '。' -> ". "
                    '！' -> "! "
                    '？' -> "? "
                    '：' -> ": "
                    '；' -> "; "
                    '（', '【', '〈', '《' -> " ("
                    '）', '】', '〉', '》' -> ") "
                    '「', '」', '『', '』', '“', '”', '„', '«', '»' -> "\""
                    '‘', '’' -> "'"
                    '…' -> "..."
                    ' ' -> " "
                    else -> c
                },
            )
        }
        t = DASHES.replace(mapped.toString(), ", ")
        t = t.replace("[", " ").replace("]", " ")
        return SPACES.replace(t, " ").trim()
    }

    private fun isSpeakable(text: String): Boolean = text.any { it.isLetterOrDigit() }
}
