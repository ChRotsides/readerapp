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
    /** URLs of the chapters the novel already has (used to verify a discovered TOC belongs to this novel). */
    suspend fun existingChapterUrls(novelId: Long): List<String>
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
    /**
     * The traversal did not reach the end of the list (a later page failed, or the page limit was hit). Nothing was
     * committed and the TOC is not marked as fetched, so it is retried later.
     */
    data class Incomplete(val reason: String) : TocResult
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
    /** True when [domain] has an ACTIVE JSON adapter with usable TOC selectors, i.e. [import] can list a novel's chapters. */
    suspend fun supports(domain: String): Boolean {
        val adapter = adapters.activeFor(domain) ?: return false
        if (adapter.kind != AdapterKind.JSON) return false
        val spec = runCatching { JsonAdapterSpec.parse(adapter.code) }.getOrNull() ?: return false
        return spec.validate().isEmpty() && spec.toc != null
    }

    suspend fun import(novelId: Long, maxPages: Int = MAX_PAGES): TocResult {
        val novel = store.novel(novelId) ?: return TocResult.NoNovelPage
        val adapter = adapters.activeFor(novel.domain) ?: return TocResult.NoAdapter
        if (adapter.kind != AdapterKind.JSON) return TocResult.NoTocSpec
        val spec = runCatching { JsonAdapterSpec.parse(adapter.code) }.getOrNull() ?: return TocResult.NoTocSpec
        // Same gate as chapter extraction: no regex selectors (catastrophic backtracking) from stored adapter JSON.
        if (spec.validate().isNotEmpty()) return TocResult.NoTocSpec
        if (spec.toc == null) return TocResult.NoTocSpec

        val discovered = novel.novelUrl == null
        val novelUrl = novel.novelUrl ?: discoverNovelUrl(novel) ?: return TocResult.NoNovelPage

        val refs = ArrayList<ChapterRef>()
        val visited = HashSet<String>()
        var info: ExtractedNovelInfo? = null
        var next: String? = novelUrl
        var fetchedPages = 0
        var incomplete: String? = null
        while (next != null) {
            if (fetchedPages >= maxPages) { incomplete = "more than $maxPages TOC pages"; break }
            if (!visited.add(UrlKey.of(next))) {
                // A link back to a page already read is not a verified end of the list.
                incomplete = "TOC page ${fetchedPages + 1} links back to an already-read page ($next)"
                break
            }
            val page = try {
                pages.fetch(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (page == null) {
                if (fetchedPages == 0) return TocResult.FetchFailed("Could not fetch $next")
                incomplete = "could not fetch TOC page ${fetchedPages + 1} ($next)"
                break
            }
            fetchedPages++
            val (pageInfo, toc) = withContext(Dispatchers.Default) { JsonAdapterRunner.runNovelPage(spec, page) }
            if (info == null) info = pageInfo
            val known = refs.map { UrlKey.of(it.url) }.toHashSet()
            val fresh = toc.refs.filter { UrlKey.of(it.url) !in known }
            if (fresh.isEmpty()) {
                // The first page is checked below (FetchFailed). A later page with no new chapters is a login page, a JS
                // shell, changed markup or a repeated list: not a verified end, so nothing may be reconciled.
                if (fetchedPages > 1) incomplete = "TOC page $fetchedPages ($next) listed no new chapters"
                break
            }
            refs += fresh
            // Only a page that listed chapters and has no next link is the verified terminal page.
            next = toc.nextPageUrl
        }
        // Only a complete traversal may re-index the novel: a partial list would renumber from zero and interleave
        // with the chapters already stored.
        if (incomplete != null) return TocResult.Incomplete(incomplete)
        if (refs.isEmpty()) return TocResult.FetchFailed("The novel page had no chapter links matching the adapter's TOC selector")

        if (discovered) {
            // The novel page was guessed from a chapter page; make sure it really lists this novel's chapters.
            val known = store.existingChapterUrls(novelId).map { UrlKey.of(it) }.toHashSet()
            if (known.isNotEmpty() && refs.none { UrlKey.of(it.url) in known }) {
                return TocResult.FetchFailed("The discovered novel page does not list this novel's chapters")
            }
        }
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
        // Not persisted here: it is stored only after the TOC it points to was verified and imported.
        return NovelUrlFinder.find(page.html, page.url)
    }

    companion object {
        const val MAX_PAGES = 400
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
        return chapters.latestFetchedUrlForNovel(novelId)
    }

    override suspend fun existingChapterUrls(novelId: Long): List<String> =
        chapters.all(novelId).map { it.url }

    override suspend fun markTocFetched(novelId: Long, at: Long) = novels.setTocFetchedAt(novelId, at)
}
