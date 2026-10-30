package com.readerapp.search

import com.readerapp.data.db.Novel
import com.readerapp.data.repo.NovelRepository
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.scrape.toc.TocImporter
import com.readerapp.scrape.toc.TocResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** What the search result knows about a novel whose NOVEL page (not a chapter) the user wants to add. */
data class NovelPageRequest(
    val url: String,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val synopsis: String? = null,
    val language: String? = null,
)

sealed interface NovelPageImport {
    data class AlreadyInLibrary(val novelId: Long) : NovelPageImport
    /** The domain's active adapter listed the chapters: the novel is complete. */
    data class Imported(val novelId: Long, val chapters: Int) : NovelPageImport
    /**
     * The novel exists (metadata only). The user must open [browserUrl], navigate to chapter 1 and use the browser's
     * Add to library, which attaches the chapters and, for a new domain, kicks off adapter generation.
     */
    data class NeedsChapter(val novelId: Long, val browserUrl: String, val reason: String) : NovelPageImport
}

interface NovelPageStore {
    suspend fun findExisting(domain: String, url: String, title: String): Novel?
    suspend fun create(novel: Novel): Long
}

fun interface TocRunner {
    suspend fun import(novelId: Long): TocResult
}

@Singleton
class RoomNovelPageStore @Inject constructor(private val novels: NovelRepository) : NovelPageStore {
    override suspend fun findExisting(domain: String, url: String, title: String): Novel? =
        novels.findByDomainAndUrl(domain, url) ?: novels.findByDomainAndTitle(domain, title)

    override suspend fun create(novel: Novel): Long = novels.create(novel)
}

@Singleton
class TocImporterRunner @Inject constructor(private val toc: TocImporter) : TocRunner {
    override suspend fun import(novelId: Long): TocResult = toc.import(novelId)
}

/** "Add to library" from a novel-page URL found by search. */
@Singleton
class NovelPageImporter @Inject constructor(
    private val store: NovelPageStore,
    private val toc: TocRunner,
) {
    suspend fun import(req: NovelPageRequest): NovelPageImport {
        val domain = DomainRateLimiter.domainOf(req.url)
        val existing = store.findExisting(domain, req.url, req.title)
        if (existing != null && existing.tocFetchedAt != null) return NovelPageImport.AlreadyInLibrary(existing.id)
        val novelId = existing?.id ?: store.create(
            Novel(
                title = req.title.trim().ifEmpty { domain },
                author = req.author,
                coverUrl = req.coverUrl,
                synopsis = req.synopsis,
                sourceUrl = req.url,
                domain = domain,
                language = req.language,
                novelUrl = req.url,
            ),
        )
        // Active adapter with TOC selectors: create the chapter list now. Anything else falls back to the browser.
        val result = try {
            toc.import(novelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TocResult.FetchFailed(e.message ?: "unexpected error")
        }
        return when (result) {
            is TocResult.Imported -> NovelPageImport.Imported(novelId, result.total)
            TocResult.NoAdapter, TocResult.NoTocSpec ->
                NovelPageImport.NeedsChapter(novelId, req.url, "No chapter-list recipe for $domain yet.")
            TocResult.NoNovelPage -> NovelPageImport.NeedsChapter(novelId, req.url, "Could not find the novel page.")
            is TocResult.FetchFailed -> NovelPageImport.NeedsChapter(novelId, req.url, result.reason)
            is TocResult.Incomplete -> NovelPageImport.NeedsChapter(novelId, req.url, result.reason)
        }
    }
}

/** The library as plain novels (an interface so view models can be tested with a fake). */
fun interface LibrarySource {
    fun novels(): Flow<List<Novel>>
}

@Singleton
class RoomLibrarySource @Inject constructor(private val repo: NovelRepository) : LibrarySource {
    override fun novels(): Flow<List<Novel>> = repo.observeAll()
}

@Singleton
class SecureServerConfig @Inject constructor(private val settings: com.readerapp.data.settings.SecureSettings) : ServerConfigProvider {
    override suspend fun config(): ServerConfig? {
        val s = settings.current()
        val url = s.serverUrl ?: return null
        val token = s.serverToken ?: return null
        return ServerConfig(url, token)
    }
}
