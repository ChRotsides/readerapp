package com.readerapp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ChapterStatus { NOT_FETCHED, FETCHED, LOCKED, FAILED }

enum class TextVariant { ORIGINAL, FIXED, TRANSLATED }

/** What the background queue does to chapters ahead of the reader. */
enum class AutoProcessMode { OFF, FIX, TRANSLATE }

enum class AdapterKind { JSON, JS }

enum class AdapterStatus { ACTIVE, BROKEN, RETIRED }

@Entity(tableName = "novels", indices = [Index("sourceUrl")])
data class Novel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val synopsis: String? = null,
    val sourceUrl: String,
    val domain: String,
    val language: String? = null,
    val followed: Boolean = false,
    val lastReadChapterId: Long? = null,
    /** Paragraph index of the scroll position inside [lastReadChapterId]. */
    val scrollPos: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = addedAt,
    /** The novel's main page (table of contents / info page), when known. */
    val novelUrl: String? = null,
    /** When the table of contents was last imported; null = never. */
    val tocFetchedAt: Long? = null,
    @ColumnInfo(defaultValue = "'OFF'") val autoProcess: AutoProcessMode = AutoProcessMode.OFF,
    /** Translation target for this novel; null = the global default from settings. */
    val targetLanguage: String? = null,
    /** Translate with Sonnet (quality) instead of Haiku. */
    @ColumnInfo(defaultValue = "0") val translateWithSonnet: Boolean = false,
    /**
     * False = a hidden "from search" novel (opening chapters saved after a discovery search): not shown in the library
     * until the user adds or starts reading it, and deleted after [com.readerapp.offline.SearchRetention.MAX_AGE_DAYS].
     */
    @ColumnInfo(defaultValue = "1") val inLibrary: Boolean = true,
    /** "Download whole novel when added" applied to this novel: new chapters found by a TOC refresh are downloaded too. */
    @ColumnInfo(defaultValue = "0") val downloadAll: Boolean = false,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["novelId", "url"], unique = true), Index(value = ["novelId", "chapterIndex"])],
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    /** Ordering key. Chapters discovered by following links may get negative indices (previous chapters). */
    @ColumnInfo(name = "chapterIndex") val index: Int,
    val title: String = "",
    val url: String,
    val status: ChapterStatus = ChapterStatus.NOT_FETCHED,
    val fetchedAt: Long? = null,
    val read: Boolean = false,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    /** Saved although the break-detection verdict was inconclusive; shown as "possibly incomplete". */
    @ColumnInfo(defaultValue = "0") val flagged: Boolean = false,
)

@Entity(
    tableName = "chapter_texts",
    primaryKeys = ["chapterId", "variant"],
    foreignKeys = [
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterText(
    val chapterId: Long,
    val variant: TextVariant,
    /** Stored as a JSON list of strings (see [Converters]). */
    val paragraphs: List<String>,
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** What was last sent to the discovery server's search index for a chapter (see [com.readerapp.sync.LibraryUploader]). */
@Entity(
    tableName = "uploaded_chapters",
    foreignKeys = [
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class UploadedChapter(
    @PrimaryKey val chapterId: Long,
    /** SHA-256 of the uploaded index/title/url/text (or [REJECTED_PREFIX] + that hash when the server refused it). */
    val hash: String,
    /** When the text was read for that upload; texts created later are re-checked. */
    val uploadedAt: Long,
    /** Position, title and URL as uploaded: a change in any of them (TOC re-index) makes the chapter pending again. */
    val chapterIndex: Int,
    val title: String,
    val url: String,
    /** The novel url the chapter was uploaded under (`novel.url`); a later change makes the chapter pending again. */
    @ColumnInfo(defaultValue = "''") val novelUrl: String = "",
) {
    companion object {
        /** Marks content the server permanently rejected (422); skipped until the content changes. */
        const val REJECTED_PREFIX = "rejected:"
    }
}

@Entity(
    tableName = "glossary_entries",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["novelId", "source"], unique = true)],
)
data class GlossaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val source: String,
    val target: String,
    val note: String? = null,
    /** Added or edited by the user: automatic glossary merging never overwrites these. */
    @ColumnInfo(defaultValue = "0") val userEdited: Boolean = false,
)

@Entity(
    tableName = "chapter_summaries",
    foreignKeys = [
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterSummary(
    @PrimaryKey val chapterId: Long,
    val summary: String,
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Compression of the 50 chapter summaries of chapter indices [arcIndex]*50 .. arcIndex*50+49 (floor division, so
 * the grouping does not depend on how many chapters have been discovered).
 */
@Entity(
    tableName = "arc_summaries",
    primaryKeys = ["novelId", "arcIndex"],
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArcSummary(
    val novelId: Long,
    val arcIndex: Int,
    val startIndex: Int,
    val endIndex: Int,
    val summary: String,
    val model: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "site_adapters", primaryKeys = ["domain", "version"])
data class SiteAdapter(
    val domain: String,
    val version: Int,
    val kind: AdapterKind,
    val code: String,
    /** Model id that wrote this version, e.g. `claude-sonnet-5-5`; null if handwritten. */
    val writtenBy: String? = null,
    val status: AdapterStatus = AdapterStatus.ACTIVE,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastSuccessAt: Long? = null,
    val fixtureId: Long? = null,
    /** Adapter failures since the last accepted chapter; two in a row mark the version BROKEN. */
    @ColumnInfo(defaultValue = "0") val consecutiveFailures: Int = 0,
)

@Entity(tableName = "adapter_fixtures", indices = [Index("domain")])
data class AdapterFixture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val url: String,
    val trimmedHtml: String,
    /** JSON of the expected extraction output. */
    val expectedOutput: String,
)

/** Per-domain adapter bookkeeping that is not tied to a single adapter version. */
@Entity(tableName = "domain_state")
data class DomainState(
    @PrimaryKey val domain: String,
    /** Repair gave up: the generic extractor is used until the user regenerates the adapter. */
    @ColumnInfo(defaultValue = "0") val genericFallback: Boolean = false,
    /** Set when a page problem (login wall / bot check) needs the user in the in-app browser. */
    val browserActionNeeded: String? = null,
    /** Trimmed copy of the page that made the active adapter fail (input for repair). */
    val failingUrl: String? = null,
    val failingHtml: String? = null,
    val lastMessage: String? = null,
    val updatedAt: Long = 0,
)

/** One Anthropic API call, for spend tracking. [costMicros] is millionths of a US dollar. */
@Entity(tableName = "ai_usage", indices = [Index("createdAt")])
data class AiUsage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val model: String,
    val purpose: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costMicros: Long,
)

/**
 * A starred novel, mirrored with the discovery server's `favorites` table (see [com.readerapp.favorites.FavoriteSync]).
 * [key] is `work:<work_id>` or `url:<normalized novel url>` ([com.readerapp.favorites.FavoriteKeys]); the star may sit on
 * a novel that is not in the library, so there is no foreign key to [Novel].
 */
@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val key: String,
    val workId: String? = null,
    val novelUrl: String? = null,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val domain: String? = null,
    /** False = a tombstone (un-starred); kept so the deletion syncs. */
    val favorite: Boolean = true,
    /** Epoch ms of the last change (device or server-confirmed); the later change wins when device and server differ. */
    val changedAt: Long,
    /** A change the server has not confirmed yet. */
    @ColumnInfo(defaultValue = "0") val pendingSync: Boolean = false,
)

/**
 * A saved discovery search, mirrored with the server's `search_history` table (see [com.readerapp.search.history.SearchHistorySync]).
 * [resultsJson] is the ranked works as the server returned them; null until the full entry has been fetched, so the list can be
 * shown from a pull alone. [deleted] is a local tombstone: hidden at once, removed after the server confirmed the delete.
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntry(
    @PrimaryKey val id: String,
    val description: String,
    /** Epoch ms. */
    val createdAt: Long,
    val resultCount: Int,
    /** The SearchRequest as sent. */
    val requestJson: String = "{}",
    /** A [com.readerapp.search.SearchBundle]: scenes, synopsis, tags, filters, reranked, notes. */
    val bundleJson: String = "{}",
    val resultsJson: String? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** [SearchHistoryEntry] without the heavy JSON, for list rows. */
data class SearchHistoryItem(
    val id: String,
    val description: String,
    val createdAt: Long,
    val resultCount: Int,
    val hasResults: Boolean,
)
