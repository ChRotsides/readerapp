package com.readerapp.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.readerapp.data.db.AppDatabase
import com.readerapp.data.db.ChapterText
import com.readerapp.data.db.ChapterTextDao
import com.readerapp.data.db.TextVariant
import com.readerapp.scrape.TextCleaner
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time repair of chapters saved before counter paragraphs (Webnovel comment counts) were filtered at extraction.
 * The indices are taken from ORIGINAL and removed from every variant, so paragraph-aligned FIXED/TRANSLATED text
 * stays aligned without re-running the AI.
 */
@Singleton
class StoredTextCleanup @Inject constructor(
    private val db: AppDatabase,
    private val texts: ChapterTextDao,
    private val store: DataStore<Preferences>,
) {
    suspend fun runOnce() {
        if (store.data.first()[DONE] == true) return
        for (chapterId in texts.chapterIdsWithText()) {
            db.withTransaction { cleanChapter(texts.all(chapterId))?.forEach { texts.upsert(it) } }
        }
        store.edit { it[DONE] = true }
    }

    companion object {
        private val DONE = booleanPreferencesKey("cleanup_counter_paragraphs_v1")

        /** The rewritten rows, or null when nothing changes. */
        fun cleanChapter(rows: List<ChapterText>): List<ChapterText>? {
            val original = rows.firstOrNull { it.variant == TextVariant.ORIGINAL } ?: return null
            val drop = original.paragraphs.indices.filter { TextCleaner.COUNTER_PARAGRAPH.matches(original.paragraphs[it]) }.toSet()
            if (drop.isEmpty()) return null
            return rows.map { row ->
                val kept = if (row.paragraphs.size == original.paragraphs.size) {
                    row.paragraphs.filterIndexed { i, _ -> i !in drop }
                } else {
                    // Not aligned with ORIGINAL (shouldn't happen); fall back to filtering by content.
                    row.paragraphs.filterNot { TextCleaner.COUNTER_PARAGRAPH.matches(it) }
                }
                row.copy(paragraphs = kept)
            }
        }
    }
}
