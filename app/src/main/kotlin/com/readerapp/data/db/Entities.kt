package com.readerapp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ChapterStatus { NOT_FETCHED, FETCHED, LOCKED, FAILED }

enum class TextVariant { ORIGINAL, FIXED, TRANSLATED }

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
