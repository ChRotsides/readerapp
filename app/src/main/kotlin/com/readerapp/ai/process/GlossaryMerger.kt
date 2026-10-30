package com.readerapp.ai.process

import com.readerapp.data.db.GlossaryEntry

/** A name/term the model reported while translating. */
data class FoundTerm(val source: String, val target: String, val note: String?)

object GlossaryMerger {
    /**
     * Returns the entries to write: new sources are added; an existing entry is never replaced (user-edited entries
     * for sure, but also earlier automatic ones, so the same name stays translated the same way). The only change
     * to an existing automatic entry is filling in a missing note.
     */
    fun merge(novelId: Long, existing: List<GlossaryEntry>, found: List<FoundTerm>): List<GlossaryEntry> {
        val bySource = existing.associateBy { it.source.trim().lowercase() }.toMutableMap()
        val out = LinkedHashMap<String, GlossaryEntry>()
        for (term in found) {
            val source = term.source.trim()
            val target = term.target.trim()
            if (source.isEmpty() || target.isEmpty()) continue
            val key = source.lowercase()
            val current = bySource[key]
            if (current == null) {
                val entry = GlossaryEntry(novelId = novelId, source = source, target = target, note = term.note?.trim()?.takeIf { it.isNotEmpty() })
                bySource[key] = entry
                out[key] = entry
            } else if (!current.userEdited && current.note.isNullOrBlank() && !term.note.isNullOrBlank()) {
                val updated = current.copy(note = term.note.trim())
                bySource[key] = updated
                out[key] = updated
            }
        }
        return out.values.toList()
    }
}
