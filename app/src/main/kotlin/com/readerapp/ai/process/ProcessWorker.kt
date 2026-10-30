package com.readerapp.ai.process

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
import com.readerapp.ai.LlmException
import com.readerapp.ai.SpendGuard
import com.readerapp.ai.summary.SummaryService
import com.readerapp.data.db.AutoProcessMode
import com.readerapp.data.db.ChapterStatus
import com.readerapp.data.db.TextVariant
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.settings.AiSettings
import com.readerapp.scrape.ChapterFetcher
import com.readerapp.scrape.FetchOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background queue for one novel: from the chapter the reader is on, processes it and the next N chapters in order
 * (fix or translate, per the novel's setting), fetching them first when needed, then writes their summaries.
 * Stops quietly when the spend cap is reached or there is no API key; retries on transient API errors.
 */
@HiltWorker
class ProcessWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val chapters: ChapterRepository,
    private val novels: NovelRepository,
    private val fetcher: ChapterFetcher,
    private val processor: ChapterProcessor,
    private val summaries: SummaryService,
    private val settings: AiSettings,
    private val spend: SpendGuard,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val novel = novels.get(inputData.getLong(KEY_NOVEL_ID, -1)) ?: return Result.success()
        val anchor = chapters.get(inputData.getLong(KEY_CHAPTER_ID, -1)) ?: return Result.success()
        if (novel.autoProcess == AutoProcessMode.OFF) return Result.success()
        val variant = if (novel.autoProcess == AutoProcessMode.TRANSLATE) TextVariant.TRANSLATED else TextVariant.FIXED

        val window = listOf(anchor) + chapters.nextN(anchor, settings.currentProcessAhead())
        for (chapter in window) {
            if (spend.capReached()) return Result.success()
            if (chapter.status != ChapterStatus.FETCHED || chapters.getText(chapter.id) == null) {
                if (fetcher.ensureFetched(chapter.id) !is FetchOutcome.Success) break // the chain beyond is unknown/locked
            }
            when (val outcome = processor.process(chapter.id, variant)) {
                is ProcessOutcome.Retry -> return Result.retry()
                ProcessOutcome.CapReached, ProcessOutcome.NoApiKey -> return Result.success()
                // Not needed / failed / done / already done: summaries are still useful, carry on.
                else -> Unit
            }
            try {
                summaries.ensureSummary(chapter.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException.Retryable) {
                return Result.retry()
            } catch (e: LlmException.SpendCapReached) {
                return Result.success()
            } catch (e: LlmException.NotConfigured) {
                return Result.success()
            } catch (e: LlmException) {
                // A bad summary must not stop processing of the following chapters.
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_NOVEL_ID = "novelId"
        const val KEY_CHAPTER_ID = "chapterId"
    }
}

@Singleton
class ProcessScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** Unique work per novel; a newer reading position replaces the pending job (processing is idempotent). */
    fun schedule(novelId: Long, chapterId: Long) {
        val request = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setInputData(workDataOf(ProcessWorker.KEY_NOVEL_ID to novelId, ProcessWorker.KEY_CHAPTER_ID to chapterId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("process-$novelId", ExistingWorkPolicy.REPLACE, request)
    }
}
