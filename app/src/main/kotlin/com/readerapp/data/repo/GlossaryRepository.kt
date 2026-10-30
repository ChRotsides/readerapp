package com.readerapp.data.repo

import com.readerapp.data.db.GlossaryDao
import com.readerapp.data.db.GlossaryEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlossaryRepository @Inject constructor(private val dao: GlossaryDao) {
    fun observe(novelId: Long): Flow<List<GlossaryEntry>> = dao.observe(novelId)

    suspend fun list(novelId: Long): List<GlossaryEntry> = dao.list(novelId)

    /** Manual add / edit: the entry is marked user-edited so automatic merging never overwrites it. */
    suspend fun saveByUser(entry: GlossaryEntry) {
        dao.upsert(entry.copy(source = entry.source.trim(), target = entry.target.trim(), userEdited = true))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun saveAll(entries: List<GlossaryEntry>) {
        if (entries.isNotEmpty()) dao.upsertAll(entries)
    }
}
