package com.readerapp.data.repo

import com.readerapp.data.db.ArcSummary
import com.readerapp.data.db.ArcSummaryDao
import com.readerapp.data.db.ChapterSummary
import com.readerapp.data.db.ChapterSummaryDao
import com.readerapp.data.db.IndexedSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SummaryRepository @Inject constructor(
    private val chapterSummaries: ChapterSummaryDao,
    private val arcs: ArcSummaryDao,
) {
    suspend fun get(chapterId: Long): ChapterSummary? = chapterSummaries.get(chapterId)

    suspend fun save(summary: ChapterSummary) = chapterSummaries.upsert(summary)

    /** Chapter summaries strictly before [index] (never the current or later chapters). */
    suspend fun before(novelId: Long, index: Int): List<IndexedSummary> = chapterSummaries.indexedBefore(novelId, index)

    suspend fun inRange(novelId: Long, from: Int, to: Int): List<IndexedSummary> =
        chapterSummaries.indexedInRange(novelId, from, to)

    suspend fun arcs(novelId: Long): List<ArcSummary> = arcs.forNovel(novelId)

    suspend fun arc(novelId: Long, arcIndex: Int): ArcSummary? = arcs.get(novelId, arcIndex)

    suspend fun saveArc(arc: ArcSummary) = arcs.upsert(arc)
}
