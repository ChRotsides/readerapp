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
     * Stores the extracted ORIGINAL text for [chapter], marks it FETCHED and registers the neighbouring
     * chapters (as NOT_FETCHED placeholders) discovered from the page's next/prev links.
     */
    suspend fun storeFetched(
        chapter: Chapter,
        title: String?,
        paragraphs: List<String>,
        nextUrl: String?,
        prevUrl: String?,
    ) = db.withTransaction {
        val now = System.currentTimeMillis()
        texts.upsert(ChapterText(chapter.id, TextVariant.ORIGINAL, paragraphs, model = null, createdAt = now))
        chapters.update(
            chapter.copy(
                title = title?.takeIf { it.isNotBlank() } ?: chapter.title,
                status = ChapterStatus.FETCHED,
                fetchedAt = now,
            ),
        )
        // Only extend the chain at its ends; a chapter in the middle already has neighbours.
        if (nextUrl != null && nextUrl != chapter.url && chapters.nextAfter(chapter.novelId, chapter.index, chapter.id) == null) {
            insertIfAbsent(chapter.novelId, nextUrl, chapter.index + 1)
        }
        if (prevUrl != null && prevUrl != chapter.url && chapters.previousBefore(chapter.novelId, chapter.index, chapter.id) == null) {
            insertIfAbsent(chapter.novelId, prevUrl, chapter.index - 1)
        }
    }
}
