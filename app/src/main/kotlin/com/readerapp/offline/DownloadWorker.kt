package com.readerapp.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.readerapp.data.db.Chapter
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.scrape.ChapterFetcher
import com.readerapp.scrape.FetchOutcome
import com.readerapp.scrape.toc.TocImporter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of a novel's background download, for the novel screen. */
data class DownloadProgress(val done: Int, val total: Int, val running: Boolean)

/**
 * Downloads up to [KEY_LIMIT] not yet downloaded chapters of a novel, in reading order starting after the reading
 * position (from the first chapter when the novel was never read), through the normal [ChapterFetcher] path (per-domain
 * politeness included). Refreshes the table of contents first when it was never imported or is old. Shows a progress
 * notification with a Cancel action unless [KEY_SILENT].
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val novels: NovelRepository,
    private val chapters: ChapterRepository,
    private val fetcher: ChapterFetcher,
    private val toc: TocImporter,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val novel = novels.get(inputData.getLong(KEY_NOVEL_ID, -1)) ?: return Result.success()
        val limit = inputData.getInt(KEY_LIMIT, 0)
        if (limit <= 0) return Result.success()
        val silent = inputData.getBoolean(KEY_SILENT, false)
        if (!silent) ensureChannel()

        var title = novel.title
        suspend fun report(done: Int, total: Int, text: String) {
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            if (!silent) setForegroundSafely(notification(title, text, done, total))
        }
        report(0, 0, "Preparing...")

        val staleToc = novel.tocFetchedAt?.let { System.currentTimeMillis() - it > TOC_MAX_AGE_MS } ?: true
        if (staleToc) {
            try {
                toc.import(novel.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without a table of contents the chapters are discovered through next links while downloading.
            }
        }
        title = novels.get(novel.id)?.title ?: title

        // Walk one successor at a time: fetching a chapter can create the placeholder of the next one (next links).
        // Starts at the current chapter (the one before it is the cursor); a whole-novel download also covers the
        // chapters before the reading position.
        var cursor: Chapter? = if (novel.downloadAll) null
        else novel.lastReadChapterId?.let { chapters.get(it) }?.let { chapters.previous(it) }
        var done = 0
        var failures = 0
        var stopReason: String? = null
        while (done < limit) {
            val remaining = chapters.unfetchedCountFrom(novel.id, cursor)
            if (remaining == 0) break
            val total = done + minOf(limit - done, remaining)
            report(done, total, "$done / $total chapters")
            val next = chapters.nextUnfetchedFrom(novel.id, cursor) ?: break
            cursor = next
            when (val outcome = fetcher.ensureFetched(next.id)) {
                is FetchOutcome.Success -> { done++; failures = 0; cursor = outcome.chapter }
                is FetchOutcome.Locked -> failures = 0 // marked LOCKED; the next ones may still be readable
                is FetchOutcome.NeedsBrowser -> { stopReason = outcome.reason; break }
                is FetchOutcome.Failed -> if (++failures >= MAX_FAILURES) { stopReason = outcome.reason; break }
            }
        }
        report(done, done, "Done")
        if (!silent) notifyDone(title, stopReason?.let { "Stopped after $done chapters: $it" } ?: "Downloaded $done chapters")
        return Result.success()
    }

    private suspend fun setForegroundSafely(notification: Notification) {
        try {
            setForeground(ForegroundInfo(NOTIFICATION_BASE + id.hashCode().mod(1000), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (_: Exception) {
            // Not allowed to start a foreground service right now; the download still runs as ordinary work.
        }
    }

    private fun notification(title: String, text: String, done: Int, total: Int): Notification {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(id)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading: $title")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
    }

    private fun notifyDone(title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_BASE + 1000 + id.hashCode().mod(1000), n) }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Offline downloads", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val KEY_NOVEL_ID = "novelId"
        const val KEY_LIMIT = "limit"
        const val KEY_SILENT = "silent"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        /** "All remaining". */
        const val ALL = Int.MAX_VALUE
        private const val CHANNEL_ID = "offline_download"
        private const val NOTIFICATION_BASE = 5000
        private const val MAX_FAILURES = 5
        private const val TOC_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

@Singleton
class DownloadScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /**
     * Queues the download of the next [limit] chapters of [novelId] ([DownloadWorker.ALL] = everything remaining).
     * Unique per novel: [replace] restarts a running/pending job (a user asking for a new amount), otherwise the
     * existing job is kept (it already picks up newly discovered chapters).
     */
    fun enqueue(novelId: Long, limit: Int, silent: Boolean = false, replace: Boolean = true) {
        if (limit <= 0) return
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_NOVEL_ID to novelId, DownloadWorker.KEY_LIMIT to limit, DownloadWorker.KEY_SILENT to silent))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName(novelId), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(novelId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(novelId))
    }

    /** null when no download is queued or running for the novel. */
    fun observe(novelId: Long): Flow<DownloadProgress?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueName(novelId)).map { infos ->
            val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } ?: return@map null
            info.progress.toDownloadProgress(running = info.state == WorkInfo.State.RUNNING)
        }

    private fun Data.toDownloadProgress(running: Boolean) = DownloadProgress(getInt(DownloadWorker.KEY_DONE, 0), getInt(DownloadWorker.KEY_TOTAL, 0), running)

    private fun uniqueName(novelId: Long) = "download-$novelId"
}
