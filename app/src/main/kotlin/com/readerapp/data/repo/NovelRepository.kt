package com.readerapp.data.repo

import com.readerapp.data.db.AutoProcessMode
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

    suspend fun findByDomainAndUrl(domain: String, url: String): Novel? = dao.findByDomainAndUrl(domain, url)

    /** The library (hidden "from search" novels excluded). */
    fun observeAll(): Flow<List<Novel>> = dao.observeAll()

    fun observeHidden(): Flow<List<Novel>> = dao.observeHidden()

    suspend fun hidden(): List<Novel> = dao.hidden()

    /** Every novel including hidden ones. */
    suspend fun all(): List<Novel> = dao.all()

    suspend fun withCopy(): List<Novel> = dao.withCopy()

    suspend fun deleteIfHidden(id: Long): Boolean = dao.deleteIfHidden(id) > 0

    suspend fun deleteIfStale(id: Long, cutoff: Long): Boolean = dao.deleteIfStale(id, cutoff) > 0

    fun observeLibrarySize(): Flow<Int> = dao.observeLibrarySize()

    suspend fun setInLibrary(id: Long, inLibrary: Boolean) = dao.setInLibrary(id, inLibrary)

    suspend fun setDownloadAll(id: Long, downloadAll: Boolean) = dao.setDownloadAll(id, downloadAll)

    suspend fun create(novel: Novel): Long = dao.insert(novel)

    suspend fun update(novel: Novel) = dao.update(novel)

    suspend fun setLastRead(novelId: Long, chapterId: Long, scrollPos: Int = 0) =
        dao.setLastRead(novelId, chapterId, scrollPos, System.currentTimeMillis())

    suspend fun setAiSettings(
        novelId: Long,
        mode: AutoProcessMode,
        targetLanguage: String?,
        translateWithSonnet: Boolean,
        sourceLanguage: String?,
    ) = dao.setAiSettings(
        novelId, mode, targetLanguage?.trim()?.takeIf { it.isNotEmpty() }, translateWithSonnet,
        sourceLanguage?.trim()?.takeIf { it.isNotEmpty() },
    )

    suspend fun setNovelUrl(novelId: Long, url: String?) = dao.setNovelUrl(novelId, url)

    suspend fun setTocFetchedAt(novelId: Long, at: Long) = dao.setTocFetchedAt(novelId, at)

    suspend fun withoutToc(domain: String): List<Novel> = dao.withoutToc(domain)

    /** Deletes the novel; chapters, texts, glossary and summaries cascade. */
    suspend fun delete(id: Long) = dao.delete(id)
}
