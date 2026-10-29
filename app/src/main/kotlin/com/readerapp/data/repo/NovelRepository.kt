package com.readerapp.data.repo

import com.readerapp.data.db.Novel
import com.readerapp.data.db.NovelDao
import com.readerapp.data.db.NovelWithProgress
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NovelRepository @Inject constructor(private val dao: NovelDao) {
    fun observeLibrary(): Flow<List<NovelWithProgress>> = dao.observeLibrary()

    fun observe(id: Long): Flow<Novel?> = dao.observe(id)

    suspend fun get(id: Long): Novel? = dao.get(id)

    suspend fun findByDomainAndTitle(domain: String, title: String): Novel? =
        dao.findByDomainAndTitle(domain, title)

    suspend fun create(novel: Novel): Long = dao.insert(novel)

    suspend fun update(novel: Novel) = dao.update(novel)

    suspend fun setLastRead(novelId: Long, chapterId: Long, scrollPos: Int = 0) =
        dao.setLastRead(novelId, chapterId, scrollPos, System.currentTimeMillis())

    /** Deletes the novel; chapters, texts, glossary and summaries cascade. */
    suspend fun delete(id: Long) = dao.delete(id)
}
