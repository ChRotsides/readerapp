package com.readerapp.scrape.toc

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Imports the table of contents of one novel in the background (the adapter may only exist a bit later). */
@HiltWorker
class TocWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val importer: TocImporter,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val novelId = inputData.getLong(KEY_NOVEL_ID, -1)
        if (novelId < 0) return Result.failure()
        return when (importer.import(novelId)) {
            is TocResult.FetchFailed, is TocResult.Incomplete -> if (runAttemptCount < 4) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_NOVEL_ID = "novelId"
    }
}

@Singleton
class TocScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule(novelId: Long) {
        val request = OneTimeWorkRequestBuilder<TocWorker>()
            .setInputData(workDataOf(TocWorker.KEY_NOVEL_ID to novelId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("toc-$novelId", ExistingWorkPolicy.KEEP, request)
    }
}
