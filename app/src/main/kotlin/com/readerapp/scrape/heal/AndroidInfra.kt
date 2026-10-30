package com.readerapp.scrape.heal

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import com.readerapp.data.repo.ChapterRepository
import com.readerapp.data.repo.NovelRepository
import com.readerapp.scrape.toc.TocScheduler
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.HttpFetcher
import com.readerapp.scrape.WebViewFetcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** HTTP first, WebView when the HTTP page is an empty JS shell or fails. Both are rate limited. */
@Singleton
class DefaultPageSource @Inject constructor(
    private val http: HttpFetcher,
    private val webView: WebViewFetcher,
    private val chapters: ChapterRepository,
) : PageSource {
    override suspend fun fetch(url: String): FetchedPage? {
        val viaHttp = try {
            http.fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (viaHttp != null && Jsoup.parse(viaHttp.html).body().text().length >= SHELL_TEXT_CHARS) return viaHttp
        return try {
            webView.fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            viaHttp
        }
    }

    override suspend fun sampleUrls(domain: String, excludeUrl: String, limit: Int) =
        chapters.sampleUrls(domain, excludeUrl, limit)

    override suspend fun latestChapterUrl(domain: String) = chapters.latestFetchedUrl(domain)

    private companion object {
        const val SHELL_TEXT_CHARS = 500
    }
}

@Singleton
class AndroidAdapterNotifier @Inject constructor(@ApplicationContext private val context: Context) : AdapterNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Site adapters", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun fixed(domain: String, version: Int, restored: Boolean) =
        post(domain, if (restored) "Restored adapter for $domain" else "Fixed adapter for $domain", "Now v$version")

    override fun generated(domain: String, version: Int) =
        post(domain, "Adapter ready for $domain", "Created v$version; chapters from this site are now extracted by it")

    override fun failed(domain: String, reason: String) =
        post(domain, "Could not fix adapter for $domain", "Using the generic extractor. $reason")

    override fun spendCapReached(domain: String) =
        post(domain, "AI spend cap reached", "Adapter work for $domain is paused until next month or a higher cap")

    @SuppressLint("MissingPermission")
    private fun post(domain: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        manager.notify(domain.hashCode(), n)
    }

    private companion object {
        const val CHANNEL = "site_adapters"
    }
}

@Singleton
class WorkRepairScheduler @Inject constructor(@ApplicationContext private val context: Context) : RepairScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleRepair(domain: String) = enqueue(domain, AdapterWorker.MODE_REPAIR, null, null)

    override fun scheduleGeneration(domain: String, pageUrl: String, novelUrl: String?) =
        enqueue(domain, AdapterWorker.MODE_GENERATE, pageUrl, novelUrl)

    /** Manual "regenerate now" from the adapters screen. Same unique work name as repair: one job per domain. */
    fun scheduleRegenerate(domain: String) = enqueue(domain, AdapterWorker.MODE_REGENERATE, null, null)

    fun enqueue(domain: String, mode: String, pageUrl: String?, novelUrl: String?) {
        val request = OneTimeWorkRequestBuilder<AdapterWorker>()
            .setInputData(
                workDataOf(
                    AdapterWorker.KEY_DOMAIN to domain,
                    AdapterWorker.KEY_MODE to mode,
                    AdapterWorker.KEY_PAGE_URL to pageUrl,
                    AdapterWorker.KEY_NOVEL_URL to novelUrl,
                ),
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // KEEP: while a job for this domain is queued or running, further requests are dropped.
        WorkManager.getInstance(context).enqueueUniqueWork("adapter-$domain", ExistingWorkPolicy.KEEP, request)
    }
}

@HiltWorker
class AdapterWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: RepairOrchestrator,
    private val novels: NovelRepository,
    private val toc: TocScheduler,
    private val resumer: com.readerapp.ai.resume.SpendResumer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val domain = inputData.getString(KEY_DOMAIN) ?: return Result.failure()
        val mode = inputData.getString(KEY_MODE)
        val outcome = when (mode) {
            MODE_GENERATE -> {
                val url = inputData.getString(KEY_PAGE_URL) ?: return Result.failure()
                orchestrator.generate(domain, url, inputData.getString(KEY_NOVEL_URL))
            }
            MODE_REGENERATE -> orchestrator.regenerate(domain)
            else -> orchestrator.repair(domain)
        }
        // A usable adapter may now know how to read table-of-contents pages: import them for this domain's novels.
        if (outcome is RepairOutcome.Fixed || outcome is RepairOutcome.Reactivated || outcome is RepairOutcome.NotNeeded) {
            runCatching { novels.withoutToc(domain).forEach { toc.schedule(it.id) } }
        }
        if (outcome is RepairOutcome.SpendCapReached) {
            // Durable pause: resumes at the next budget period or as soon as the cap is raised.
            resumer.defer(
                com.readerapp.ai.resume.DeferredJob.Adapter(
                    domain, mode ?: MODE_REPAIR, inputData.getString(KEY_PAGE_URL), inputData.getString(KEY_NOVEL_URL),
                ),
            )
            return Result.success()
        }
        return when {
            outcome is RepairOutcome.Transient && runAttemptCount < MAX_RETRIES -> Result.retry()
            // Verification was not available (outage): nothing is wrong with the adapter, so keep trying much longer.
            outcome is RepairOutcome.VerifierUnavailable && runAttemptCount < MAX_VERIFIER_RETRIES -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_DOMAIN = "domain"
        const val KEY_MODE = "mode"
        const val KEY_PAGE_URL = "pageUrl"
        const val KEY_NOVEL_URL = "novelUrl"
        const val MODE_GENERATE = "generate"
        const val MODE_REPAIR = "repair"
        const val MODE_REGENERATE = "regenerate"
        private const val MAX_RETRIES = 3
        private const val MAX_VERIFIER_RETRIES = 8
    }
}
