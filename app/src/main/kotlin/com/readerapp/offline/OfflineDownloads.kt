package com.readerapp.offline

import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.settings.LibrarySettings
import com.readerapp.scrape.toc.TocResult
import javax.inject.Inject
import javax.inject.Singleton

/** A novel was added to the library or a hidden "from search" novel joined it. */
fun interface NovelJoinHook {
    suspend fun joined(novelId: Long)
}

/** The library-wide offline behaviour: "download whole novel when added" and its follow-up on TOC refreshes. */
@Singleton
class OfflineDownloads @Inject constructor(
    private val novels: NovelRepository,
    private val settings: LibrarySettings,
    private val downloads: DownloadScheduler,
) : NovelJoinHook {

    /**
     * Makes the novel a library novel (hidden search novels join here) and, with "Download whole novel when added" on,
     * flags it and enqueues every chapter. Once online the rest of a promoted search novel downloads this way.
     */
    override suspend fun joined(novelId: Long) {
        val novel = novels.get(novelId) ?: return
        if (!novel.inLibrary) novels.setInLibrary(novelId, true)
        if (settings.current().downloadWholeOnAdd && !novel.downloadAll) {
            novels.setDownloadAll(novelId, true)
            downloads.enqueue(novelId, DownloadWorker.ALL)
        }
    }

    /** A TOC refresh that found new chapters of a novel flagged by [joined] downloads them too. */
    suspend fun afterToc(novelId: Long, result: TocResult) {
        if (result !is TocResult.Imported || result.added <= 0) return
        if (novels.get(novelId)?.downloadAll != true) return
        downloads.enqueue(novelId, DownloadWorker.ALL, replace = false)
    }
}
