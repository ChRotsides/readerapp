package com.readerapp.offline

import com.readerapp.data.db.Novel
import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.settings.LibrarySettings
import com.readerapp.scrape.DomainRateLimiter
import com.readerapp.scrape.toc.TocImporter
import com.readerapp.search.LibraryMatch
import com.readerapp.search.NovelPageImport
import com.readerapp.search.NovelPageImporter
import com.readerapp.search.NovelPageRequest
import com.readerapp.search.SourceUrl
import com.readerapp.search.Work
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Called with the results of every discovery search. */
fun interface SearchResultPrefetch {
    fun onResults(results: List<Work>)
}

object SearchPrefetchPlanner {
    /**
     * The top [topN] of [results] that the device has no copy of yet: results matching a library novel or an already
     * saved hidden one ([existing] holds both) are dropped, not replaced by lower-ranked ones.
     */
    fun pick(results: List<Work>, topN: Int, existing: List<Novel>): List<Work> =
        results.take(topN.coerceAtLeast(0)).filter { LibraryMatch.find(it, existing) == null }

    /** First source whose domain can list chapters ([supported]); the importer needs the same, see [NovelPageImporter]. */
    suspend fun resolveSource(work: Work, supported: suspend (domain: String) -> Boolean): SourceUrl? =
        work.urls.firstOrNull { supported(DomainRateLimiter.domainOf(it.url)) }
}

/** Hidden search novels nobody opened are deleted after this long. */
object SearchRetention {
    const val MAX_AGE_DAYS = 14
    const val MAX_AGE_MS = MAX_AGE_DAYS * 24 * 60 * 60 * 1000L

    /** Hidden, never added/read (neither saved nor opened within the window). */
    fun isStale(novel: Novel, now: Long): Boolean =
        !novel.inLibrary && now - maxOf(novel.addedAt, novel.lastOpenedAt) > MAX_AGE_MS
}

/**
 * After a search returns: for the top N results, resolves a readable source (same capability test as "Add to library"
 * from a result), stores the novel hidden ([Novel.inLibrary] = false) and downloads its first M chapters in the
 * background. Runs in an app-wide scope so leaving the search screen does not stop it.
 */
@Singleton
class SearchPrefetcher(
    private val novels: NovelRepository,
    private val toc: TocImporter,
    private val importer: NovelPageImporter,
    private val downloads: DownloadScheduler,
    private val settings: LibrarySettings,
    private val scope: CoroutineScope,
) : SearchResultPrefetch {
    @Inject constructor(
        novels: NovelRepository,
        toc: TocImporter,
        importer: NovelPageImporter,
        downloads: DownloadScheduler,
        settings: LibrarySettings,
    ) : this(novels, toc, importer, downloads, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private var job: Job? = null

    override fun onResults(results: List<Work>) {
        job?.cancel()
        job = scope.launch {
            try {
                prefetch(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: a failed prefetch only means the novel is fetched when the user opens it.
            }
        }
    }

    suspend fun prefetch(results: List<Work>) {
        val options = settings.current()
        if (options.searchPrefetchResults <= 0 || options.searchPrefetchChapters <= 0) return
        val picks = SearchPrefetchPlanner.pick(results, options.searchPrefetchResults, novels.withCopy())
        for (work in picks) {
            val source = SearchPrefetchPlanner.resolveSource(work) { toc.supports(it) } ?: continue
            val request = NovelPageRequest(
                url = source.url, title = work.title, author = work.authors.firstOrNull(), coverUrl = work.coverUrl,
                synopsis = work.synopsis, language = work.language,
            )
            when (val r = importer.import(request, hidden = true)) {
                is NovelPageImport.Imported -> enqueueIfHidden(r.novelId, options.searchPrefetchChapters)
                is NovelPageImport.AlreadyInLibrary -> enqueueIfHidden(r.novelId, options.searchPrefetchChapters)
                // No chapter list after all: do not leave an empty hidden novel behind (never one the user added meanwhile).
                is NovelPageImport.NeedsChapter -> novels.deleteIfHidden(r.novelId)
            }
        }
    }

    /**
     * KEEP, not REPLACE: a "download whole novel" job the user started by adding the novel meanwhile must not be
     * cancelled. The hidden check is repeated right before enqueueing for the same reason.
     */
    private suspend fun enqueueIfHidden(novelId: Long, chapters: Int) {
        if (novels.get(novelId)?.inLibrary == false) downloads.enqueue(novelId, chapters, silent = true, replace = false)
    }
}

interface HiddenNovelStore {
    suspend fun hidden(): List<Novel>
    /** Deletes the novel (chapters and texts cascade) only if it is still hidden and not added/opened since [cutoff]. */
    suspend fun deleteIfStale(id: Long, cutoff: Long): Boolean
}

@Singleton
class RoomHiddenNovelStore @Inject constructor(private val novels: NovelRepository) : HiddenNovelStore {
    override suspend fun hidden(): List<Novel> = novels.hidden()
    override suspend fun deleteIfStale(id: Long, cutoff: Long) = novels.deleteIfStale(id, cutoff)
}

/** Deletes hidden search novels (and, by cascade, their chapters) that nobody opened for [SearchRetention.MAX_AGE_DAYS] days. */
@Singleton
class SearchCleanup @Inject constructor(private val store: HiddenNovelStore) {
    /** Returns how many novels were deleted. */
    suspend fun run(now: Long = System.currentTimeMillis()): Int {
        val stale = store.hidden().filter { SearchRetention.isStale(it, now) }
        // The delete re-checks hidden/staleness, so a novel added or opened since the list was read survives.
        return stale.count { store.deleteIfStale(it.id, now - SearchRetention.MAX_AGE_MS) }
    }
}
