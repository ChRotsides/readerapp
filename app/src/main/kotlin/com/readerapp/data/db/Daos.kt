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

    @Query("UPDATE novels SET autoProcess = :mode, targetLanguage = :target, translateWithSonnet = :sonnet, language = :language WHERE id = :id")
    suspend fun setAiSettings(id: Long, mode: AutoProcessMode, target: String?, sonnet: Boolean, language: String?)

    @Query("UPDATE novels SET novelUrl = :url WHERE id = :id")
    suspend fun setNovelUrl(id: Long, url: String?)

    @Query("UPDATE novels SET tocFetchedAt = :at WHERE id = :id")
    suspend fun setTocFetchedAt(id: Long, at: Long)

    /** Novels of [domain] that know their novel page but never imported a table of contents. */
    @Query("SELECT * FROM novels WHERE domain = :domain AND tocFetchedAt IS NULL")
    suspend fun withoutToc(domain: String): List<Novel>
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

    @Query("SELECT * FROM chapters WHERE novelId = :novelId")
    suspend fun all(novelId: Long): List<Chapter>

    @Query("UPDATE chapters SET chapterIndex = :index, title = :title WHERE id = :id")
    suspend fun setIndexAndTitle(id: Long, index: Int, title: String)

    /** The [limit] chapters after the given one in reading order. */
    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND (chapterIndex > :index OR (chapterIndex = :index AND id > :id)) ORDER BY chapterIndex, id LIMIT :limit")
    suspend fun nextN(novelId: Long, index: Int, id: Long, limit: Int): List<Chapter>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND chapterIndex BETWEEN :from AND :to ORDER BY chapterIndex, id")
    suspend fun inRange(novelId: Long, from: Int, to: Int): List<Chapter>

    /** Fetched chapters strictly before [index], nearest first. */
    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND status = 'FETCHED' AND chapterIndex < :index ORDER BY chapterIndex DESC, id DESC LIMIT :limit")
    suspend fun fetchedBefore(novelId: Long, index: Int, limit: Int): List<Chapter>

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

    @Query("UPDATE chapters SET flagged = :flagged WHERE id = :id")
    suspend fun setFlagged(id: Long, flagged: Boolean)

    /** Fetched chapter URLs of [domain] (other than [excludeUrl]), newest first: known-readable validation samples. */
    @Query(
        """
        SELECT c.url FROM chapters c JOIN novels n ON n.id = c.novelId
        WHERE n.domain = :domain AND c.status = 'FETCHED' AND c.url != :excludeUrl
        ORDER BY c.id DESC LIMIT :limit
        """,
    )
    suspend fun sampleUrls(domain: String, excludeUrl: String, limit: Int): List<String>

    @Query(
        """
        SELECT c.url FROM chapters c JOIN novels n ON n.id = c.novelId
        WHERE n.domain = :domain AND c.status = 'FETCHED' ORDER BY c.id DESC LIMIT 1
        """,
    )
    suspend fun latestFetchedUrl(domain: String): String?
}

@Dao
interface ChapterTextDao {
    @Query("SELECT * FROM chapter_texts WHERE chapterId = :chapterId AND variant = :variant")
    suspend fun get(chapterId: Long, variant: TextVariant): ChapterText?

    @Query("SELECT * FROM chapter_texts WHERE chapterId = :chapterId")
    fun observeAll(chapterId: Long): Flow<List<ChapterText>>

    @Query("SELECT * FROM chapter_texts WHERE chapterId = :chapterId")
    suspend fun all(chapterId: Long): List<ChapterText>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<GlossaryEntry>)

    @Query("DELETE FROM glossary_entries WHERE id = :id")
    suspend fun delete(id: Long)
}

/** A chapter summary together with the chapter's ordering index. */
data class IndexedSummary(val chapterId: Long, val chapterIndex: Int, val summary: String)

@Dao
interface ArcSummaryDao {
    @Query("SELECT * FROM arc_summaries WHERE novelId = :novelId ORDER BY arcIndex")
    suspend fun forNovel(novelId: Long): List<ArcSummary>

    @Query("SELECT * FROM arc_summaries WHERE novelId = :novelId AND arcIndex = :arcIndex")
    suspend fun get(novelId: Long, arcIndex: Int): ArcSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(arc: ArcSummary)
}

@Dao
interface ChapterSummaryDao {
    @Query("SELECT * FROM chapter_summaries WHERE chapterId = :chapterId")
    suspend fun get(chapterId: Long): ChapterSummary?

    /** Summaries of chapters of the novel with index strictly below [index] (spoiler-safe), in reading order. */
    @Query(
        """
        SELECT s.chapterId AS chapterId, c.chapterIndex AS chapterIndex, s.summary AS summary
        FROM chapter_summaries s JOIN chapters c ON c.id = s.chapterId
        WHERE c.novelId = :novelId AND c.chapterIndex < :index ORDER BY c.chapterIndex, c.id
        """,
    )
    suspend fun indexedBefore(novelId: Long, index: Int): List<IndexedSummary>

    @Query(
        """
        SELECT s.chapterId AS chapterId, c.chapterIndex AS chapterIndex, s.summary AS summary
        FROM chapter_summaries s JOIN chapters c ON c.id = s.chapterId
        WHERE c.novelId = :novelId AND c.chapterIndex BETWEEN :from AND :to ORDER BY c.chapterIndex, c.id
        """,
    )
    suspend fun indexedInRange(novelId: Long, from: Int, to: Int): List<IndexedSummary>

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

    @Query("UPDATE site_adapters SET successCount = successCount + 1, consecutiveFailures = 0, lastSuccessAt = :now WHERE domain = :domain AND version = :version")
    suspend fun recordSuccess(domain: String, version: Int, now: Long)

    @Query("UPDATE site_adapters SET failCount = failCount + 1, consecutiveFailures = consecutiveFailures + 1 WHERE domain = :domain AND version = :version")
    suspend fun recordFailure(domain: String, version: Int)

    @Query("UPDATE site_adapters SET consecutiveFailures = 0 WHERE domain = :domain AND version = :version")
    suspend fun resetFailures(domain: String, version: Int)

    @Query("SELECT * FROM site_adapters WHERE domain = :domain AND version = :version")
    suspend fun get(domain: String, version: Int): SiteAdapter?

    @Query("SELECT * FROM adapter_fixtures WHERE id = :id")
    suspend fun fixture(id: Long): AdapterFixture?

    @Query("SELECT * FROM adapter_fixtures WHERE domain = :domain ORDER BY id DESC")
    suspend fun fixtures(domain: String): List<AdapterFixture>

    @Insert
    suspend fun insertFixture(fixture: AdapterFixture): Long
}

@Dao
interface DomainStateDao {
    @Query("SELECT * FROM domain_state WHERE domain = :domain")
    suspend fun get(domain: String): DomainState?

    @Query("SELECT * FROM domain_state")
    fun observeAll(): Flow<List<DomainState>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: DomainState)
}

@Dao
interface AiUsageDao {
    @Insert
    suspend fun insert(usage: AiUsage)

    @Query("SELECT COALESCE(SUM(costMicros), 0) FROM ai_usage WHERE createdAt >= :since")
    suspend fun costSince(since: Long): Long

    @Query("SELECT COALESCE(SUM(costMicros), 0) FROM ai_usage WHERE createdAt >= :since")
    fun observeCostSince(since: Long): Flow<Long>
}
