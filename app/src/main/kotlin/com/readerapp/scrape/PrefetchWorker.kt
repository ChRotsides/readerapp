package com.readerapp.scrape

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.repo.ChapterRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes sure the next [KEY_COUNT] chapters after [KEY_CHAPTER_ID] are fetched. Spacing between requests
 * is enforced by the shared per-domain rate limiter inside the fetchers.
 */
@HiltWorker
class PrefetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val chapters: ChapterRepository,
    private val fetcher: ChapterFetcher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val start = chapters.get(inputData.getLong(KEY_CHAPTER_ID, -1)) ?: return Result.success()
        val count = inputData.getInt(KEY_COUNT, 5)
        // The window is the next `count` chapter POSITIONS; chapters already cached count toward it, so reopening the
        // same chapter never walks further into the novel. (Fresh tail placeholders created along the way are
        // found because the successor is looked up after every step.)
        var cursor = start
        var position = 0
        while (position < count) {
            val next = chapters.next(cursor) ?: break
            if (next.status == ChapterStatus.LOCKED || next.status == ChapterStatus.FAILED) break // chain blocked here
            if (next.status != ChapterStatus.FETCHED || chapters.getText(next.id) == null) {
                when (val outcome = fetcher.ensureFetched(next.id)) {
                    is FetchOutcome.Success -> cursor = outcome.chapter
                    // Locked/failed chapter: stop here, the chain beyond it is unknown or inaccessible.
                    is FetchOutcome.Locked, is FetchOutcome.Failed, is FetchOutcome.NeedsBrowser -> return Result.success()
                }
            } else {
                cursor = next
            }
            position++
        }
        return Result.success()
    }

    companion object {
        const val KEY_CHAPTER_ID = "chapterId"
        const val KEY_COUNT = "count"
    }
}

@Singleton
class PrefetchScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** Enqueues prefetch of the [count] chapters following [chapterId]; replaces any pending job for the novel. */
    fun schedule(novelId: Long, chapterId: Long, count: Int) {
        if (count <= 0) return
        val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setInputData(workDataOf(PrefetchWorker.KEY_CHAPTER_ID to chapterId, PrefetchWorker.KEY_COUNT to count))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("prefetch-$novelId", ExistingWorkPolicy.REPLACE, request)
    }
}
