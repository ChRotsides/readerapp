package com.readerapp.offline

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Daily: drops hidden "from search" novels that were never opened (see [SearchCleanup]). */
@HiltWorker
class SearchCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cleanup: SearchCleanup,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        cleanup.run()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SearchCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("search-cleanup", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
