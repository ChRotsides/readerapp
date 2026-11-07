package com.readerapp.search

import com.readerapp.data.db.Chapter
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.db.Novel
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.settings.LibrarySettings
import com.readerapp.data.settings.SearchTuning
import com.readerapp.scrape.ChapterFetcher
import com.readerapp.scrape.FetchOutcome
import com.readerapp.sync.LibraryUploader
import com.readerapp.sync.UploadStore
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** What a similar-novels search starts from. */
sealed interface SimilarSeed {
    /** A library novel; [currentChapterId] is the chapter being read (null = the novel's saved reading position). */
    data class LibraryNovel(val novelId: Long, val currentChapterId: Long? = null) : SimilarSeed
    /** A work of the discovery corpus; nothing needs uploading. */
    data class CorpusWork(val workId: String) : SimilarSeed
    /** A saved similar search run again exactly as it was sent (its chapters are already on the server). */
    data class Replay(val request: SimilarRequest) : SimilarSeed
}

/** [UPLOADING_UNASKED]: uploading although the "Upload chapters" option is off (the user asked for this search). */
enum class SimilarStage { PREPARING, UPLOADING, UPLOADING_UNASKED, SEARCHING }

/** Whether library chapters may be uploaded in the background (the user's "Upload chapters" option). */
fun interface UploadOption {
    suspend fun enabled(): Boolean
}

class SettingsUploadOption @Inject constructor(private val settings: LibrarySettings) : UploadOption {
    override suspend fun enabled(): Boolean = settings.current().uploadChapters
}

/** [seedTitle] is what the server called the seed, else the library novel's own title (null when neither is known). */
data class SimilarResult(val response: SearchResponse, val seedTitle: String?, val request: SimilarRequest? = null)

fun interface SimilarSearch {
    suspend fun find(seed: SimilarSeed, onStage: (SimilarStage) -> Unit): SimilarResult
}

object SimilarSeedChapters {
    /** How many opening chapters show what the novel is about. */
    private const val OPENING = 3

    /**
     * The chapter indexes to compare a library novel by: the lowest [OPENING] existing non-negative indexes (a novel
     * imported mid-story has no chapter 0), plus the existing chapter before the one being read and that chapter itself
     * if it exists; ascending, without duplicates.
     */
    fun indexes(existing: Collection<Int>, current: Int?): List<Int> {
        val have = existing.toSet()
        val opening = have.filter { it >= 0 }.sorted().take(OPENING)
        val reading = if (current == null || current !in have) emptyList() else listOfNotNull(have.filter { it < current }.maxOrNull(), current)
        return (opening + reading).distinct().sorted()
    }
}

/** What the similar search needs from the device's library (an interface so it can be faked in JVM tests). */
interface SimilarSource {
    suspend fun novel(novelId: Long): Novel?
    suspend fun chapters(novelId: Long): List<Chapter>
    /** True when the chapter's text is on the device, downloading it first when it is missing; false when that fails or it is locked. */
    suspend fun ensureDownloaded(chapter: Chapter): Boolean
}

class RoomSimilarSource @Inject constructor(
    private val novels: NovelRepository,
    private val chapters: ChapterRepository,
    private val fetcher: ChapterFetcher,
) : SimilarSource {
    override suspend fun novel(novelId: Long): Novel? = novels.get(novelId)

    override suspend fun chapters(novelId: Long): List<Chapter> = chapters.all(novelId)

    override suspend fun ensureDownloaded(chapter: Chapter): Boolean {
        if (chapter.status == ChapterStatus.LOCKED) return false
        return try {
            fetcher.ensureFetched(chapter.id) is FetchOutcome.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * "Find similar": for a library novel it downloads the seed chapters (see [SimilarSeedChapters]) that are missing, uploads
 * them through [LibraryUploader] (so they are recorded as sent), then asks the server. A 409 ("seed has no embedded
 * chapters") means the server does not hold them, so they are sent again once and the search retried; any other error
 * propagates. A corpus work goes straight to the server.
 */
class SimilarFinder @Inject constructor(
    private val source: SimilarSource,
    private val store: UploadStore,
    private val api: DiscoveryApi,
    private val config: ServerConfigProvider,
    private val tuning: SearchTuning,
    private val uploadOption: UploadOption,
) : SimilarSearch {

    override suspend fun find(seed: SimilarSeed, onStage: (SimilarStage) -> Unit): SimilarResult {
        // Fail before downloading anything.
        if (config.config() == null) throw SearchException.NotConfigured()
        return when (seed) {
            is SimilarSeed.CorpusWork -> {
                onStage(SimilarStage.SEARCHING)
                val request = tuning.applySimilar(SimilarRequest(workId = seed.workId))
                val r = api.similar(request)
                SimilarResult(r, r.seedTitle, request)
            }
            is SimilarSeed.Replay -> {
                onStage(SimilarStage.SEARCHING)
                val r = api.similar(seed.request)
                SimilarResult(r, r.seedTitle, seed.request)
            }
            is SimilarSeed.LibraryNovel -> findForLibrary(seed, onStage)
        }
    }

    private suspend fun findForLibrary(seed: SimilarSeed.LibraryNovel, onStage: (SimilarStage) -> Unit): SimilarResult {
        onStage(SimilarStage.PREPARING)
        val novel = source.novel(seed.novelId) ?: throw SearchException.NotFound()
        val chapters = source.chapters(novel.id).sortedBy { it.id }
        val byIndex = chapters.groupBy { it.index }.mapValues { it.value.first() }
        val currentId = seed.currentChapterId ?: novel.lastReadChapterId
        val current = chapters.firstOrNull { it.id == currentId }?.index
        val ids = SimilarSeedChapters.indexes(byIndex.keys, current).mapNotNull { byIndex[it] }
            .filter { source.ensureDownloaded(it) }.map { it.id }
        if (ids.isEmpty()) throw SearchException.NoSeedChapters()

        val uploader = LibraryUploader(store, api)
        // Always forced: the server skips re-embedding unchanged text, and the records may predate a change of the
        // novel's url, under which the server would not find these chapters.
        suspend fun upload(): List<Int> {
            onStage(if (uploadOption.enabled()) SimilarStage.UPLOADING else SimilarStage.UPLOADING_UNASKED)
            return uploader.uploadChapters(ids, force = true).indexes.ifEmpty { throw SearchException.SeedRejected() }
        }
        suspend fun search(request: SimilarRequest): SearchResponse {
            onStage(SimilarStage.SEARCHING)
            return try {
                api.similar(request)
            } catch (e: SearchException.Http) {
                if (e.code == 429) throw SearchException.RateLimited() else throw e
            }
        }

        var request = tuning.applySimilar(SimilarRequest(libraryUrl = LibraryUploader.libraryUrl(novel), chapters = upload()))
        // 409 (the library record or its chapters are not embedded) and 404 (seed unknown): upload again once, then retry once.
        val response = try {
            search(request)
        } catch (e: SearchException) {
            if (!(e is SearchException.Http && e.code == 409) && e !is SearchException.NotFound) throw e
            try {
                request = request.copy(chapters = upload())
                search(request)
            } catch (again: SearchException) {
                if ((again is SearchException.Http && again.code == 409) || again is SearchException.NotFound) throw SearchException.SeedNotOnServer()
                throw again
            }
        }
        return SimilarResult(response, response.seedTitle ?: novel.title, request)
    }
}
