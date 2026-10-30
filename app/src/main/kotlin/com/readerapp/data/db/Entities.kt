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
