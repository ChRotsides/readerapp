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
        FROM novels n WHERE n.inLibrary = 1 ORDER BY n.lastOpenedAt DESC
        """,
    )
    fun observeLibrary(): Flow<List<NovelWithProgress>>

    @Query("SELECT * FROM novels WHERE id = :id")
    fun observe(id: Long): Flow<Novel?>

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun get(id: Long): Novel?

    @Query("SELECT * FROM novels WHERE domain = :domain AND title = :title LIMIT 1")
    suspend fun findByDomainAndTitle(domain: String, title: String): Novel?

    @Query("SELECT * FROM novels WHERE domain = :domain AND (novelUrl = :url OR sourceUrl = :url) LIMIT 1")
    suspend fun findByDomainAndUrl(domain: String, url: String): Novel?

    /** The library: hidden "from search" novels are not included. */
    @Query("SELECT * FROM novels WHERE inLibrary = 1")
    fun observeAll(): Flow<List<Novel>>

    /** Hidden novels saved by search prefetch that actually have downloaded chapters (readable offline). */
    @Query("SELECT * FROM novels n WHERE n.inLibrary = 0 AND EXISTS (SELECT 1 FROM chapters c WHERE c.novelId = n.id AND c.status = 'FETCHED')")
    fun observeHidden(): Flow<List<Novel>>

    /** Novels the device already has a usable copy of: the library plus hidden ones with downloaded chapters. */
    @Query("SELECT * FROM novels n WHERE n.inLibrary = 1 OR EXISTS (SELECT 1 FROM chapters c WHERE c.novelId = n.id AND c.status = 'FETCHED')")
    suspend fun withCopy(): List<Novel>

    /** Deletes only while still hidden (the user may have added it meanwhile). Returns rows deleted. */
    @Query("DELETE FROM novels WHERE id = :id AND inLibrary = 0")
    suspend fun deleteIfHidden(id: Long): Int

    /** Deletes only while still hidden and neither added nor opened since [cutoff]. */
    @Query("DELETE FROM novels WHERE id = :id AND inLibrary = 0 AND addedAt < :cutoff AND lastOpenedAt < :cutoff")
    suspend fun deleteIfStale(id: Long, cutoff: Long): Int

    @Query("SELECT * FROM novels WHERE inLibrary = 0")
    suspend fun hidden(): List<Novel>

    /** Every novel, hidden ones included (duplicate checks). */
    @Query("SELECT * FROM novels")
    suspend fun all(): List<Novel>

    @Query("SELECT COUNT(*) FROM novels WHERE inLibrary = 1")
    fun observeLibrarySize(): Flow<Int>

    @Query("UPDATE novels SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: Long, inLibrary: Boolean)

    @Query("UPDATE novels SET downloadAll = :downloadAll WHERE id = :id")
    suspend fun setDownloadAll(id: Long, downloadAll: Boolean)

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

    /** How many chapters after the given position are still NOT_FETCHED. */
    @Query("SELECT COUNT(*) FROM chapters WHERE novelId = :novelId AND status = 'NOT_FETCHED' AND (chapterIndex > :index OR (chapterIndex = :index AND id > :id))")
    suspend fun unfetchedCountAfter(novelId: Long, index: Int, id: Long): Int

    /** Makes room: moves every chapter of the novel at or after [from] [by] places back. (Index is not unique.) */
    @Query("UPDATE chapters SET chapterIndex = chapterIndex + :by WHERE novelId = :novelId AND chapterIndex >= :from")
    suspend fun shiftFrom(novelId: Long, from: Int, by: Int)

    @Query("DELETE FROM chapters WHERE id = :id")
    suspend fun delete(id: Long)

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

    /** The most recently fetched chapter URL of this one novel. */
    @Query("SELECT url FROM chapters WHERE novelId = :novelId AND status = 'FETCHED' ORDER BY fetchedAt DESC, id DESC LIMIT 1")
    suspend fun latestFetchedUrlForNovel(novelId: Long): String?
}

@Dao
interface ChapterTextDao {
    @Query("SELECT * FROM chapter_texts WHERE chapterId = :chapterId AND variant = :variant")
    suspend fun get(chapterId: Long, variant: TextVariant): ChapterText?

    @Query("SELECT * FROM chapter_texts WHERE chapterId = :chapterId")
    fun observeAll(chapterId: Long): Flow<List<ChapterText>>

    @Query("SELECT * FROM chapter_texts WHERE chapterId = :chapterId")
    suspend fun all(chapterId: Long): List<ChapterText>

    @Query("SELECT DISTINCT chapterId FROM chapter_texts")
    suspend fun chapterIdsWithText(): List<Long>

    /** Changes whenever any text is written (Room re-emits on every write to the table). */
    @Query("SELECT MAX(createdAt) FROM chapter_texts")
    fun observeLatestWrite(): Flow<Long?>

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

    /** Drops arcs that reach [fromIndex] or later: their chapters may have moved (re-indexing). */
    @Query("DELETE FROM arc_summaries WHERE novelId = :novelId AND endIndex >= :fromIndex")
    suspend fun deleteFrom(novelId: Long, fromIndex: Int)
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

@Dao
interface UploadedChapterDao {
    @Query("SELECT * FROM uploaded_chapters WHERE chapterId = :chapterId")
    suspend fun get(chapterId: Long): UploadedChapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<UploadedChapter>)

    /**
     * Downloaded chapters of library novels that were never uploaded, whose text was written after the last upload
     * read, or whose position/title/URL or the novel's URL changed since. Only a hint: the uploader compares content hashes before sending.
     */
    @Query(
        """
        SELECT c.id FROM chapters c
        JOIN novels n ON n.id = c.novelId
        LEFT JOIN uploaded_chapters u ON u.chapterId = c.id
        WHERE n.inLibrary = 1 AND c.status = 'FETCHED'
          AND (u.chapterId IS NULL
               OR u.chapterIndex != c.chapterIndex OR u.title != c.title OR u.url != c.url
               OR u.novelUrl != COALESCE(n.novelUrl, n.sourceUrl)
               OR u.uploadedAt < (SELECT MAX(t.createdAt) FROM chapter_texts t WHERE t.chapterId = c.id))
        ORDER BY c.novelId, c.chapterIndex, c.id
        """,
    )
    suspend fun pendingChapterIds(): List<Long>
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE favorite = 1 ORDER BY changedAt DESC")
    fun observeLive(): Flow<List<Favorite>>

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<Favorite>

    @Query("SELECT * FROM favorites WHERE `key` = :key")
    suspend fun get(key: String): Favorite?

    @Query("SELECT * FROM favorites WHERE pendingSync = 1 ORDER BY changedAt")
    suspend fun pending(): List<Favorite>

    /** Changes (0 when nothing is pending) whenever a local toggle is waiting for the server; drives the debounced sync. */
    @Query("SELECT COALESCE(MAX(changedAt), 0) FROM favorites WHERE pendingSync = 1")
    fun observePendingStamp(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: Favorite)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT id, description, createdAt, resultCount, resultsJson IS NOT NULL AS hasResults FROM search_history WHERE deleted = 0 ORDER BY createdAt DESC")
    fun observeLive(): Flow<List<SearchHistoryItem>>

    @Query("SELECT * FROM search_history WHERE id = :id")
    suspend fun get(id: String): SearchHistoryEntry?

    @Query("SELECT id FROM search_history WHERE deleted = 1")
    suspend fun pendingDeletes(): List<String>

    @Query("SELECT id FROM search_history WHERE deleted = 0 AND resultsJson IS NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun idsMissingResults(limit: Int): List<String>

    @Query("UPDATE search_history SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("UPDATE search_history SET deleted = 1")
    suspend fun markAllDeleted()

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun remove(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntry)
}
