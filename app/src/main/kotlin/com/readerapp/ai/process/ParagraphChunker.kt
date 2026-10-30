package com.readerapp.ai.process

/** Splits a chapter into request-sized, contiguous groups of paragraphs. */
object ParagraphChunker {
    const val MAX_PARAGRAPHS = 40
    const val MAX_CHARS = 14_000

    /** Each chunk holds at most [maxParagraphs] paragraphs and (unless one paragraph alone is bigger) [maxChars] chars. */
    fun chunk(paragraphs: List<String>, maxParagraphs: Int = MAX_PARAGRAPHS, maxChars: Int = MAX_CHARS): List<List<String>> {
        val chunks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var chars = 0
        for (p in paragraphs) {
            if (current.isNotEmpty() && (current.size >= maxParagraphs || chars + p.length > maxChars)) {
                chunks += current
                current = ArrayList()
                chars = 0
            }
            current += p
            chars += p.length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }
}
