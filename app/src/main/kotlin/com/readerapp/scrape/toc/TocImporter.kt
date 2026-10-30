package com.readerapp.scrape.toc

import com.readerapp.data.db.AdapterKind
import com.readerapp.data.db.Novel
import com.readerapp.data.repo.AdapterStore
import com.readerapp.scrape.ChapterRef
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.scrape.ExtractedNovelInfo
import com.readerapp.scrape.adapter.JsonAdapterRunner
import com.readerapp.scrape.adapter.JsonAdapterSpec
import com.readerapp.scrape.heal.PageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persistence needed by the TOC import (an interface so it can be faked in JVM tests). */
interface TocStore {
    suspend fun novel(novelId: Long): Novel?
    /** Fills author / cover / synopsis only where the novel has none yet; stores the novel page URL. */
    suspend fun saveNovelInfo(novelId: Long, info: ExtractedNovelInfo?, novelUrl: String?)
    suspend fun applyToc(novelId: Long, refs: List<ChapterRef>): TocPlan
    suspend fun latestFetchedChapterUrl(novelId: Long): String?
    suspend fun markTocFetched(novelId: Long, at: Long)
}

sealed interface TocResult {
    data class Imported(val total: Int, val added: Int, val pages: Int) : TocResult
    /** The domain has no ACTIVE adapter (yet). */
    data object NoAdapter : TocResult
    /** The active adapter has no TOC selectors (or is a JS adapter). */
    data object NoTocSpec : TocResult
    data object NoNovelPage : TocResult
    data class FetchFailed(val reason: String) : TocResult
}

/**
 * Imports a novel's table of contents with the domain's ACTIVE JSON adapter: fetches the novel page (through the
 * rate-limited [PageSource]), follows `toc.nextPage` pagination, and gives the chapter list its correct order.
 */
@Singleton
class TocImporter @Inject constructor(
    private val store: TocStore,
    private val adapters: AdapterStore,
    private val pages: PageSource,
) {
    suspend fun import(novelId: Long, maxPages: Int = MAX_PAGES): TocResult {
        val novel = store.novel(novelId) ?: return TocResult.NoNovelPage
        val adapter = adapters.activeFor(novel.domain) ?: return TocResult.NoAdapter
        if (adapter.kind != AdapterKind.JSON) return TocResult.NoTocSpec
        val spec = runCatching { JsonAdapterSpec.parse(adapter.code) }.getOrNull() ?: return TocResult.NoTocSpec
        if (spec.toc == null) return TocResult.NoTocSpec

        val novelUrl = novel.novelUrl ?: discoverNovelUrl(novel) ?: return TocResult.NoNovelPage

        val refs = ArrayList<ChapterRef>()
        val visited = HashSet<String>()
        var info: ExtractedNovelInfo? = null
        var next: String? = novelUrl
        var fetchedPages = 0
        while (next != null && fetchedPages < maxPages && visited.add(UrlKey.of(next))) {
            val page = try {
                pages.fetch(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (page == null) {
                if (fetchedPages == 0) return TocResult.FetchFailed("Could not fetch $next")
                break
            }
            fetchedPages++
            val (pageInfo, toc) = withContext(Dispatchers.Default) { JsonAdapterRunner.runNovelPage(spec, page) }
            if (info == null) info = pageInfo
            val known = refs.map { UrlKey.of(it.url) }.toHashSet()
            val fresh = toc.refs.filter { UrlKey.of(it.url) !in known }
            // A "next page" that adds nothing new would loop forever on sites that repeat the same list.
            if (fresh.isEmpty()) break
            refs += fresh
            next = toc.nextPageUrl
        }
        if (refs.isEmpty()) return TocResult.FetchFailed("The novel page had no chapter links matching the adapter's TOC selector")

        val ordered = TocOrder.normalize(refs)
        val plan = store.applyToc(novelId, ordered)
        store.saveNovelInfo(novelId, info, novelUrl)
        store.markTocFetched(novelId, System.currentTimeMillis())
        return TocResult.Imported(total = ordered.size, added = plan.inserts.size, pages = fetchedPages)
    }

    /** No novel URL stored (imported before M3 or none found): look at the latest fetched chapter page. */
    private suspend fun discoverNovelUrl(novel: Novel): String? {
        val chapterUrl = store.latestFetchedChapterUrl(novel.id) ?: return null
        val page = runCatching { pages.fetch(chapterUrl) }.getOrNull() ?: return null
        val found = NovelUrlFinder.find(page.html, page.url)
        if (found != null) store.saveNovelInfo(novel.id, null, found)
        return found
    }

    companion object {
        const val MAX_PAGES = 60
    }
}

/** [TocStore] on top of the Room-backed repositories. */
@Singleton
class RoomTocStore @Inject constructor(
    private val novels: com.readerapp.data.repo.NovelRepository,
    private val chapters: com.readerapp.data.repo.ChapterRepository,
) : TocStore {
    override suspend fun novel(novelId: Long) = novels.get(novelId)

    override suspend fun saveNovelInfo(novelId: Long, info: ExtractedNovelInfo?, novelUrl: String?) {
        val n = novels.get(novelId) ?: return
        novels.update(
            n.copy(
                author = n.author ?: info?.author,
                coverUrl = n.coverUrl ?: info?.coverUrl,
                synopsis = n.synopsis?.takeIf { it.isNotBlank() } ?: info?.synopsis,
                novelUrl = novelUrl ?: n.novelUrl,
            ),
        )
    }

    override suspend fun applyToc(novelId: Long, refs: List<ChapterRef>) = chapters.applyToc(novelId, refs)

    override suspend fun latestFetchedChapterUrl(novelId: Long): String? {
        val novel = novels.get(novelId) ?: return null
        return chapters.latestFetchedUrl(novel.domain)
    }

    override suspend fun markTocFetched(novelId: Long, at: Long) = novels.setTocFetchedAt(novelId, at)
}
