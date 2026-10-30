package com.readerapp.ai.process

import java.security.MessageDigest

/** Revision of a chapter's source text: processing results are only committed against the revision they started from. */
object SourceRevision {
    fun of(paragraphs: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        paragraphs.forEach { p ->
            val bytes = p.toByteArray(Charsets.UTF_8)
            md.update(bytes.size.toString().toByteArray()); md.update(0); md.update(bytes) // length-prefixed: no ambiguity
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
