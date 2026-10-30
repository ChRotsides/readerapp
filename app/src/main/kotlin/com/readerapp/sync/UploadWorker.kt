package com.readerapp.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.readerapp.data.db.ChapterDao
import com.readerapp.data.db.NovelDao
import com.readerapp.data.db.ChapterTextDao
import com.readerapp.data.db.UploadedChapter
import com.readerapp.data.db.UploadedChapterDao
import com.readerapp.data.settings.LibrarySettings
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.SearchException
import com.readerapp.search.ServerConfigProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomUploadStore @Inject constructor(
    private val uploaded: UploadedChapterDao,
    private val chapters: ChapterDao,
    private val novels: NovelDao,
    private val texts: ChapterTextDao,
) : UploadStore {
    override suspend fun pendingChapterIds(): List<Long> = uploaded.pendingChapterIds()

    override suspend fun item(chapterId: Long): UploadItem? {
        val chapter = chapters.get(chapterId) ?: return null
        val novel = novels.get(chapter.novelId) ?: return null
        return UploadItem(chapterId, novel, chapter.index, chapter.title, chapter.url, texts.all(chapterId), uploaded.get(chapterId)?.hash)
    }

    override suspend fun record(rows: List<UploadedChapter>) = uploaded.upsertAll(rows)
}

/**
 * Uploads downloaded chapters to the discovery server's search index (see [LibraryUploader]). Does nothing when the
 * option is off or the server is not configured; retries with backoff when the server cannot be reached.
 */
@HiltWorker
class LibraryUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: RoomUploadStore,
    private val api: DiscoveryApi,
    private val config: ServerConfigProvider,
    private val settings: LibrarySettings,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.current().uploadChapters || config.config() == null) return Result.success()
        return try {
            val s = LibraryUploader(store, api).run()
            if (s.sent > 0) Log.i(TAG, "Uploaded ${s.sent} chapters (${s.embedded} embedded, ${s.skippedByServer} skipped by server)")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchException) {
            when (e) {
                // Nothing a retry can fix; the next trigger (settings change, new chapter, app start) tries again.
                is SearchException.NotConfigured, is SearchException.Unauthorized, is SearchException.BadResponse -> Result.success()
                // 429 / 5xx are transient (402 budget and 503 are their own types and retry below); other 4xx will not change.
                is SearchException.Http -> if (e.code == 429 || e.code >= 500) retry() else Result.success()
                else -> retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Library upload failed", e)
            retry()
        }
    }

    private fun retry() = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    private companion object {
        const val TAG = "LibraryUpload"
        const val MAX_ATTEMPTS = 6
    }
}

@Singleton
class LibraryUploadScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** One pending/running upload job at a time; the uploader loops until nothing is pending, so KEEP loses nothing. */
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<LibraryUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_NAME = "library-upload"
    }
}
