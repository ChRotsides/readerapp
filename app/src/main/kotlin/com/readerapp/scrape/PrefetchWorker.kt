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
import com.readerapp.data.repo.ChapterRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the next [KEY_COUNT] unfetched chapters after [KEY_CHAPTER_ID]. Spacing between requests
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
        var cursor = start
        var fetched = 0
        while (fetched < count) {
            val target = chapters.nextUnfetched(cursor) ?: break
            when (val outcome = fetcher.ensureFetched(target.id)) {
                is FetchOutcome.Success -> cursor = outcome.chapter
                // Locked/failed chapter: stop here, the chain beyond it is unknown or inaccessible.
                is FetchOutcome.Locked, is FetchOutcome.Failed -> return Result.success()
            }
            fetched++
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
