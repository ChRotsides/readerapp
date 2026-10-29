package com.readerapp.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class NovelWithProgress(
    @Embedded val novel: Novel,
    val chapterCount: Int,
    val readCount: Int,
    val fetchedCount: Int,
)

@Dao
interface NovelDao {
    @Query(
        """
        SELECT n.*,
          (SELECT COUNT(*) FROM chapters c WHERE c.novelId = n.id) AS chapterCount,
          (SELECT COUNT(*) FROM chapters c WHERE c.novelId = n.id AND c.`read` = 1) AS readCount,
          (SELECT COUNT(*) FROM chapters c WHERE c.novelId = n.id AND c.status = 'FETCHED') AS fetchedCount
        FROM novels n ORDER BY n.lastOpenedAt DESC
        """,
    )
    fun observeLibrary(): Flow<List<NovelWithProgress>>

    @Query("SELECT * FROM novels WHERE id = :id")
    fun observe(id: Long): Flow<Novel?>

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun get(id: Long): Novel?

    @Query("SELECT * FROM novels WHERE domain = :domain AND title = :title LIMIT 1")
    suspend fun findByDomainAndTitle(domain: String, title: String): Novel?

    @Insert
    suspend fun insert(novel: Novel): Long

    @Update
    suspend fun update(novel: Novel)

    @Query("UPDATE novels SET lastReadChapterId = :chapterId, scrollPos = :scrollPos, lastOpenedAt = :now WHERE id = :novelId")
    suspend fun setLastRead(novelId: Long, chapterId: Long, scrollPos: Int, now: Long)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY chapterIndex, id")
    fun observeForNovel(novelId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: Long): Chapter?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND url = :url")
    suspend fun findByUrl(novelId: Long, url: String): Chapter?

    @Query("SELECT * FROM chapters WHERE url = :url LIMIT 1")
    suspend fun findAnyByUrl(url: String): Chapter?

    @Query("SELECT MAX(chapterIndex) FROM chapters WHERE novelId = :novelId")
    suspend fun maxIndex(novelId: Long): Int?

    @Query("SELECT MIN(chapterIndex) FROM chapters WHERE novelId = :novelId")
    suspend fun minIndex(novelId: Long): Int?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND (chapterIndex > :index OR (chapterIndex = :index AND id > :id)) ORDER BY chapterIndex, id LIMIT 1")
    suspend fun nextAfter(novelId: Long, index: Int, id: Long): Chapter?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND (chapterIndex < :index OR (chapterIndex = :index AND id < :id)) ORDER BY chapterIndex DESC, id DESC LIMIT 1")
    suspend fun previousBefore(novelId: Long, index: Int, id: Long): Chapter?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY chapterIndex, id LIMIT 1")
    suspend fun first(novelId: Long): Chapter?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY chapterIndex DESC, id DESC LIMIT 1")
    suspend fun last(novelId: Long): Chapter?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND status = 'NOT_FETCHED' AND (chapterIndex > :index OR (chapterIndex = :index AND id > :id)) ORDER BY chapterIndex, id LIMIT 1")
    suspend fun nextUnfetchedAfter(novelId: Long, index: Int, id: Long): Chapter?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(chapter: Chapter): Long

    @Update
    suspend fun update(chapter: Chapter)

    @Query("UPDATE chapters SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: ChapterStatus)

    @Query("UPDATE chapters SET scrollIndex = :index, scrollOffset = :offset WHERE id = :id")
    suspend fun setScroll(id: Long, index: Int, offset: Int)

    @Query("UPDATE chapters SET `read` = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)
}

@Dao
interface ChapterTextDao {
    @Query("SELECT * FROM chapter_texts WHERE chapterId = :chapterId AND variant = :variant")
    suspend fun get(chapterId: Long, variant: TextVariant): ChapterText?

    @Query("SELECT * FROM chapter_texts WHERE chapterId = :chapterId")
    fun observeAll(chapterId: Long): Flow<List<ChapterText>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(text: ChapterText)

    @Query("DELETE FROM chapter_texts WHERE chapterId = :chapterId AND variant = :variant")
    suspend fun delete(chapterId: Long, variant: TextVariant)
}

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary_entries WHERE novelId = :novelId ORDER BY source")
    fun observe(novelId: Long): Flow<List<GlossaryEntry>>

    @Query("SELECT * FROM glossary_entries WHERE novelId = :novelId ORDER BY source")
    suspend fun list(novelId: Long): List<GlossaryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GlossaryEntry): Long

    @Query("DELETE FROM glossary_entries WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChapterSummaryDao {
    @Query("SELECT * FROM chapter_summaries WHERE chapterId = :chapterId")
    suspend fun get(chapterId: Long): ChapterSummary?

    /** Spoiler-safe: summaries for chapters of the novel up to (and including) [maxIndex]. */
    @Query(
        """
        SELECT s.* FROM chapter_summaries s JOIN chapters c ON c.id = s.chapterId
        WHERE c.novelId = :novelId AND c.chapterIndex <= :maxIndex ORDER BY c.chapterIndex
        """,
    )
    suspend fun upToIndex(novelId: Long, maxIndex: Int): List<ChapterSummary>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: ChapterSummary)
}

@Dao
interface AdapterDao {
    @Query("SELECT * FROM site_adapters WHERE domain = :domain AND status = 'ACTIVE' ORDER BY version DESC LIMIT 1")
    suspend fun active(domain: String): SiteAdapter?

    @Query("SELECT * FROM site_adapters WHERE domain = :domain ORDER BY version DESC")
    suspend fun versions(domain: String): List<SiteAdapter>

    @Query("SELECT * FROM site_adapters ORDER BY domain, version DESC")
    fun observeAll(): Flow<List<SiteAdapter>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(adapter: SiteAdapter)

    @Query("UPDATE site_adapters SET status = :status WHERE domain = :domain AND version = :version")
    suspend fun setStatus(domain: String, version: Int, status: AdapterStatus)

    @Query("UPDATE site_adapters SET successCount = successCount + 1, lastSuccessAt = :now WHERE domain = :domain AND version = :version")
    suspend fun recordSuccess(domain: String, version: Int, now: Long)

    @Query("UPDATE site_adapters SET failCount = failCount + 1 WHERE domain = :domain AND version = :version")
    suspend fun recordFailure(domain: String, version: Int)

    @Query("SELECT * FROM adapter_fixtures WHERE id = :id")
    suspend fun fixture(id: Long): AdapterFixture?

    @Query("SELECT * FROM adapter_fixtures WHERE domain = :domain ORDER BY id DESC")
    suspend fun fixtures(domain: String): List<AdapterFixture>

    @Insert
    suspend fun insertFixture(fixture: AdapterFixture): Long
}
