package com.readerapp.data.repo

import androidx.room.withTransaction
import com.readerapp.data.db.AppDatabase
import com.readerapp.data.db.Chapter
import com.readerapp.data.db.ChapterDao
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.db.ChapterText
import com.readerapp.data.db.ChapterTextDao
import com.readerapp.data.db.TextVariant
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChapterRepository @Inject constructor(
    private val db: AppDatabase,
    private val chapters: ChapterDao,
    private val texts: ChapterTextDao,
) {
    fun observeChapters(novelId: Long): Flow<List<Chapter>> = chapters.observeForNovel(novelId)

    suspend fun get(id: Long): Chapter? = chapters.get(id)

    suspend fun findByUrl(novelId: Long, url: String): Chapter? = chapters.findByUrl(novelId, url)

    suspend fun findAnyByUrl(url: String): Chapter? = chapters.findAnyByUrl(url)

    suspend fun first(novelId: Long): Chapter? = chapters.first(novelId)

    suspend fun last(novelId: Long): Chapter? = chapters.last(novelId)

    suspend fun next(chapter: Chapter): Chapter? = chapters.nextAfter(chapter.novelId, chapter.index, chapter.id)

    suspend fun nextUnfetched(chapter: Chapter): Chapter? =
        chapters.nextUnfetchedAfter(chapter.novelId, chapter.index, chapter.id)

    suspend fun previous(chapter: Chapter): Chapter? =
        chapters.previousBefore(chapter.novelId, chapter.index, chapter.id)

    suspend fun getText(chapterId: Long, variant: TextVariant = TextVariant.ORIGINAL): ChapterText? =
        texts.get(chapterId, variant)

    suspend fun saveText(text: ChapterText) = texts.upsert(text)

    /** Emits whenever a variant of the chapter text is added, replaced or removed. */
    fun observeTexts(chapterId: Long): Flow<List<ChapterText>> = texts.observeAll(chapterId)

    suspend fun allTexts(chapterId: Long): List<ChapterText> = texts.all(chapterId)

    suspend fun nextN(chapter: Chapter, limit: Int): List<Chapter> =
        chapters.nextN(chapter.novelId, chapter.index, chapter.id, limit)

    suspend fun inRange(novelId: Long, from: Int, to: Int): List<Chapter> = chapters.inRange(novelId, from, to)

    suspend fun fetchedBefore(novelId: Long, index: Int, limit: Int): List<Chapter> =
        chapters.fetchedBefore(novelId, index, limit)

    /**
     * Applies a table of contents: chapters whose URL is already known get the TOC position as index (and a title if
     * they have none); unknown ones are inserted. Reading state, text and status are never touched.
     */
    suspend fun applyToc(novelId: Long, refs: List<com.readerapp.scrape.ChapterRef>): com.readerapp.scrape.toc.TocPlan =
        db.withTransaction {
            val plan = com.readerapp.scrape.toc.TocPlan.compute(chapters.all(novelId), refs)
            plan.updates.forEach { chapters.setIndexAndTitle(it.chapterId, it.index, it.title) }
            plan.inserts.forEach { chapters.insert(Chapter(novelId = novelId, index = it.index, title = it.title, url = it.url)) }
            plan
        }

    suspend fun setStatus(chapterId: Long, status: ChapterStatus) = chapters.setStatus(chapterId, status)

    suspend fun updateScroll(chapterId: Long, index: Int, offset: Int) = chapters.setScroll(chapterId, index, offset)

    suspend fun setFlagged(chapterId: Long, flagged: Boolean) = chapters.setFlagged(chapterId, flagged)

    suspend fun sampleUrls(domain: String, excludeUrl: String, limit: Int): List<String> =
        chapters.sampleUrls(domain, excludeUrl, limit)

    suspend fun latestFetchedUrl(domain: String): String? = chapters.latestFetchedUrl(domain)

    suspend fun setRead(chapterId: Long, read: Boolean = true) = chapters.setRead(chapterId, read)

    /** Inserts a chapter placed at [index] unless the novel already has that URL. Returns the stored chapter. */
    suspend fun insertIfAbsent(novelId: Long, url: String, index: Int, title: String = ""): Chapter {
        chapters.findByUrl(novelId, url)?.let { return it }
        chapters.insert(Chapter(novelId = novelId, index = index, title = title, url = url))
        return checkNotNull(chapters.findByUrl(novelId, url))
    }

    /**
     * Like [insertIfAbsent] but exactly at position [index]: chapters already at or after it move one place back, so a
     * chapter learned from a next/prev link lands between its real neighbours instead of after unrelated imports.
     */
    suspend fun insertAt(novelId: Long, url: String, index: Int, title: String = ""): Chapter = db.withTransaction {
        chapters.findByUrl(novelId, url)?.let { return@withTransaction it }
        chapters.shiftFrom(novelId, index, 1)
        chapters.insert(Chapter(novelId = novelId, index = index, title = title, url = url))
        checkNotNull(chapters.findByUrl(novelId, url))
    }

    /** Registers the chapter that follows [chapter] according to a freshly read page; no-op when already known. */
    suspend fun registerNext(chapter: Chapter, nextUrl: String): Chapter? {
        val cur = chapters.get(chapter.id) ?: return null
        if (nextUrl == cur.url) return null
        return insertAt(cur.novelId, nextUrl, cur.index + 1)
    }

    /**
     * Stores the extracted ORIGINAL text for [chapter], marks it FETCHED (flagged = "possibly incomplete" when the
     * verifier was unsure) and registers the neighbouring chapters discovered from the page's next/prev links.
     * The links are the truth about adjacency: a link target not yet in the novel is inserted directly after/before
     * this chapter even when other chapters (e.g. a later, non-adjacent import) already sit further along.
     * Replacing different original text drops the Fixed/Translated variants derived from the old one.
     */
    suspend fun storeFetched(
        chapter: Chapter,
        title: String?,
        paragraphs: List<String>,
        nextUrl: String?,
        prevUrl: String?,
        flagged: Boolean = false,
    ) = db.withTransaction {
        val now = System.currentTimeMillis()
        val cur = chapters.get(chapter.id) ?: chapter
        val old = texts.get(cur.id, TextVariant.ORIGINAL)
        if (old != null && old.paragraphs != paragraphs) {
            texts.delete(cur.id, TextVariant.FIXED)
            texts.delete(cur.id, TextVariant.TRANSLATED)
        }
        texts.upsert(ChapterText(cur.id, TextVariant.ORIGINAL, paragraphs, model = null, createdAt = now))
        chapters.update(
            cur.copy(
                title = title?.takeIf { it.isNotBlank() } ?: cur.title,
                status = ChapterStatus.FETCHED,
                fetchedAt = now,
                flagged = flagged,
            ),
        )
        if (nextUrl != null && nextUrl != cur.url) insertAt(cur.novelId, nextUrl, cur.index + 1)
        if (prevUrl != null && prevUrl != cur.url) insertAt(cur.novelId, prevUrl, cur.index)
    }
}
