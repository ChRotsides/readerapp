package com.readerapp.sync

import com.readerapp.ai.process.VariantChoice
import com.readerapp.data.db.ChapterText
import com.readerapp.data.db.Novel
import com.readerapp.data.db.UploadedChapter
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.LibraryChapterPayload
import com.readerapp.search.LibraryNovelPayload
import com.readerapp.search.LibraryUploadRequest
import com.readerapp.search.SearchException
import java.security.MessageDigest

/** A downloaded chapter of a library novel, with everything needed to decide whether it must be (re)sent. */
data class UploadItem(
    val chapterId: Long,
    val novel: Novel,
    val index: Int,
    val title: String,
    val url: String,
    val texts: List<ChapterText>,
    /** Hash recorded by the last successful upload, if any. */
    val knownHash: String?,
)

/** Persistence for the uploader (an interface so it can be faked in JVM tests). */
interface UploadStore {
    /** Chapters worth checking, grouped by novel and in reading order; a hint, not a verdict (see [LibraryUploader]). */
    suspend fun pendingChapterIds(): List<Long>
    suspend fun item(chapterId: Long): UploadItem?
    suspend fun record(rows: List<UploadedChapter>)
}

object UploadText {
    /** The English text the reader shows: TRANSLATED if present, else FIXED, else ORIGINAL; null when there is none. */
    fun choose(texts: List<ChapterText>): List<String>? =
        VariantChoice.best(texts)?.paragraphs?.takeIf { p -> p.any { it.isNotBlank() } }

    fun body(paragraphs: List<String>): String = paragraphs.joinToString("\n\n")

    /** [novelUrl] is part of the hash: chapters sent under an older novel url must be sent again when it changes. */
    fun hash(index: Int, title: String, url: String, text: String, novelUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(index.toString(), title, url, text, novelUrl).forEach { digest.update(it.toByteArray()); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Sends downloaded chapters to the server's search index, in batches of at most [LibraryUploadRequest.MAX_CHAPTERS]
 * per novel. What was sent is remembered per chapter with a content hash: unchanged chapters are never resent, changed
 * ones (new translation, refetch, re-index) are. Progress is recorded after every batch, so an interrupted run resumes.
 * A batch the server rejects with 422 is split into single chapters; a chapter that is still refused is recorded with
 * [UploadedChapter.REJECTED_PREFIX] so it is skipped until its content changes, and the run continues. Other
 * network/server errors propagate to the caller (the worker decides whether to retry).
 */
class LibraryUploader(
    private val store: UploadStore,
    private val api: DiscoveryApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = LibraryUploadRequest.MAX_CHAPTERS,
) {
    data class Summary(
        val sent: Int = 0, val unchanged: Int = 0, val embedded: Int = 0, val skippedByServer: Int = 0, val rejected: Int = 0,
    )

    init {
        require(batchSize in 1..LibraryUploadRequest.MAX_CHAPTERS)
    }

    suspend fun run(): Summary {
        var summary = Summary()
        // Chapters without text stay "pending" in the store's query; look at them once per run only. Everything else
        // is recorded when handled, so it only reappears when it changed meanwhile (and is then picked up again).
        val noText = HashSet<Long>()
        while (true) {
            val ids = store.pendingChapterIds().filter { it !in noText }
            if (ids.isEmpty()) return summary
            var batch = ArrayList<Pending>()
            var batchNovel: Long? = null
            suspend fun flush() {
                if (batch.isEmpty()) return
                summary = summary.plus(send(batch))
                batch = ArrayList()
            }
            for (id in ids) {
                val readAt = clock()
                val item = store.item(id) ?: continue
                val paragraphs = UploadText.choose(item.texts)
                if (paragraphs == null) { noText += id; continue }
                val text = UploadText.body(paragraphs)
                val hash = UploadText.hash(item.index, item.title, item.url, text, LibraryUploader.libraryUrl(item.novel))
                if (hash == item.knownHash || item.knownHash == UploadedChapter.REJECTED_PREFIX + hash) {
                    // Content unchanged (e.g. only re-written with the same text): just move the checkpoint.
                    store.record(listOf(UploadedChapter(id, item.knownHash ?: hash, readAt, item.index, item.title, item.url, libraryUrl(item.novel))))
                    summary = summary.copy(unchanged = summary.unchanged + 1)
                    continue
                }
                if (batchNovel != null && batchNovel != item.novel.id) flush()
                batchNovel = item.novel.id
                batch += Pending(item, text, hash, readAt)
                if (batch.size >= batchSize) flush()
            }
            flush()
        }
    }

    /** Outcome of [uploadChapters]: [indexes] are the chapters the server now holds in their current form. */
    data class SeedUpload(val summary: Summary, val indexes: List<Int>)

    /**
     * Sends exactly these chapters (of one novel) now, for a feature that needs them on the server immediately, and records
     * them like [run] does so they are not sent again. Chapters without text and ones the server refuses are left out of
     * the result; unchanged ones are not resent unless [force] (the server may have lost them). Errors propagate.
     */
    suspend fun uploadChapters(chapterIds: List<Long>, force: Boolean = false): SeedUpload {
        var summary = Summary()
        val usable = ArrayList<Int>()
        val pending = ArrayList<Pending>()
        for (id in chapterIds) {
            val readAt = clock()
            val item = store.item(id) ?: continue
            val paragraphs = UploadText.choose(item.texts) ?: continue
            val text = UploadText.body(paragraphs)
            val hash = UploadText.hash(item.index, item.title, item.url, text, LibraryUploader.libraryUrl(item.novel))
            when {
                item.knownHash == UploadedChapter.REJECTED_PREFIX + hash -> summary = summary.copy(rejected = summary.rejected + 1)
                hash == item.knownHash && !force -> { usable += item.index; summary = summary.copy(unchanged = summary.unchanged + 1) }
                else -> pending += Pending(item, text, hash, readAt)
            }
        }
        val rejected = ArrayList<Pending>()
        for (group in pending.groupBy { it.item.novel.id }.values) {
            for (batch in group.chunked(batchSize)) summary = summary.plus(send(batch, rejected))
        }
        usable += pending.filter { it !in rejected }.map { it.item.index }
        return SeedUpload(summary, usable.distinct().sorted())
    }

    private class Pending(val item: UploadItem, val text: String, val hash: String, val readAt: Long)

    private suspend fun send(batch: List<Pending>, rejected: MutableList<Pending>? = null): Summary {
        try {
            return sendBatch(batch)
        } catch (e: SearchException.Http) {
            if (e.code != 422) throw e
            if (batch.size > 1) return batch.fold(Summary()) { acc, p -> acc.plus(send(listOf(p), rejected)) }
            val p = batch.single()
            store.record(listOf(row(p, UploadedChapter.REJECTED_PREFIX + p.hash)))
            rejected?.add(p)
            return Summary(rejected = 1)
        }
    }

    private fun row(p: Pending, hash: String) =
        UploadedChapter(p.item.chapterId, hash, p.readAt, p.item.index, p.item.title, p.item.url, libraryUrl(p.item.novel))

    private suspend fun sendBatch(batch: List<Pending>): Summary {
        val novel = batch.first().item.novel
        val request = LibraryUploadRequest(
            novel = LibraryNovelPayload(
                title = novel.title,
                author = novel.author,
                synopsis = novel.synopsis,
                url = libraryUrl(novel),
                domain = novel.domain.ifBlank { DomainRateLimiter.domainOf(novel.sourceUrl) },
            ),
            chapters = batch.map {
                LibraryChapterPayload(
                    index = it.item.index,
                    title = it.item.title.takeIf { t -> t.isNotBlank() },
                    url = it.item.url,
                    text = it.text,
                )
            },
        )
        val result = api.uploadLibraryChapters(request)
        store.record(batch.map { row(it, it.hash) })
        return Summary(sent = batch.size, embedded = result.embedded, skippedByServer = result.skipped)
    }

    private fun Summary.plus(o: Summary) = Summary(
        sent + o.sent, unchanged + o.unchanged, embedded + o.embedded, skippedByServer + o.skippedByServer, rejected + o.rejected,
    )

    companion object {
        /** The url the server knows a library novel by (`novel.url` of the upload, `library_url` of `/similar`). */
        fun libraryUrl(novel: Novel): String = novel.novelUrl ?: novel.sourceUrl
    }
}
