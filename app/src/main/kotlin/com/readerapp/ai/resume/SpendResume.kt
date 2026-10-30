package com.readerapp.ai.resume

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.readerapp.ai.ModelPricing
import com.readerapp.ai.SpendGuard
import com.readerapp.ai.process.ProcessScheduler
import com.readerapp.scrape.heal.WorkRepairScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background work that stopped because the monthly spend cap was reached and must run again once it can. */
@Serializable
sealed interface DeferredJob {
    /** Dedup key: queuing the same logical job twice keeps one entry (the newest). */
    val key: String

    @Serializable
    @SerialName("adapter")
    data class Adapter(val domain: String, val mode: String, val pageUrl: String? = null, val novelUrl: String? = null) : DeferredJob {
        override val key get() = "adapter:$domain:$mode"
    }

    @Serializable
    @SerialName("process")
    data class Process(val novelId: Long, val chapterId: Long) : DeferredJob {
        override val key get() = "process:$novelId"
    }
}

/** Durable list of [DeferredJob]s (DataStore, so it survives process death and reboots). */
@Singleton
class DeferredWorkStore @Inject constructor(private val store: DataStore<Preferences>) {
    private val keyPref = stringPreferencesKey("spend_deferred_jobs")
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(text: String?): List<DeferredJob> =
        text?.let { runCatching { json.decodeFromString<List<DeferredJob>>(it) }.getOrNull() }.orEmpty()

    suspend fun all(): List<DeferredJob> = decode(store.data.first()[keyPref])

    suspend fun add(job: DeferredJob) {
        store.edit { prefs ->
            val jobs = decode(prefs[keyPref]).filterNot { it.key == job.key } + job
            prefs[keyPref] = json.encodeToString(jobs)
        }
    }

    /** Returns every queued job and empties the list, atomically. */
    suspend fun takeAll(): List<DeferredJob> {
        var taken: List<DeferredJob> = emptyList()
        store.edit { prefs ->
            taken = decode(prefs[keyPref])
            prefs.remove(keyPref)
        }
        return taken
    }
}

/** Starts a job again (through the same schedulers that created it). */
fun interface DeferredJobDispatcher {
    fun dispatch(job: DeferredJob)
}

/** Wakes [SpendResumer] up: at the start of the next budget month, or right now (cap raised). */
interface SpendResumeScheduler {
    fun scheduleAtNextPeriod()
    fun resumeNow()
}

/**
 * Re-queues deferred work when the budget allows: once the cap is no longer reached, everything queued is dispatched;
 * if it still is, the wake-up is re-armed for the next month.
 */
@Singleton
class SpendResumer @Inject constructor(
    private val store: DeferredWorkStore,
    private val spend: SpendGuard,
    private val dispatcher: DeferredJobDispatcher,
    private val scheduler: SpendResumeScheduler,
) {
    /** Returns the jobs that were dispatched. */
    suspend fun run(): List<DeferredJob> {
        if (store.all().isEmpty()) return emptyList()
        if (spend.capReached()) {
            scheduler.scheduleAtNextPeriod()
            return emptyList()
        }
        val jobs = store.takeAll()
        jobs.forEach { dispatcher.dispatch(it) }
        return jobs
    }

    /** Called by workers that stopped on the cap. */
    suspend fun defer(job: DeferredJob) {
        store.add(job)
        scheduler.scheduleAtNextPeriod()
    }
}

@Singleton
class WorkSpendResumeScheduler @Inject constructor(@ApplicationContext private val context: Context) : SpendResumeScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleAtNextPeriod() {
        val now = System.currentTimeMillis()
        val delayMs = (ModelPricing.nextMonthStartMillis(now) - now + TimeUnit.MINUTES.toMillis(1)).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<SpendResumeWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        // One wake-up per target month, named by it: the running worker re-arms for a LATER month under a different
        // name, so KEEP cannot discard its own replacement (it would while the current work is still unfinished).
        WorkManager.getInstance(context).enqueueUniqueWork(ModelPricing.resumeWorkName(now), ExistingWorkPolicy.KEEP, request)
    }

    override fun resumeNow() {
        val request = OneTimeWorkRequestBuilder<SpendResumeWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("spend-resume-now", ExistingWorkPolicy.REPLACE, request)
    }
}

@Singleton
class WorkJobDispatcher @Inject constructor(
    private val repairs: WorkRepairScheduler,
    private val process: ProcessScheduler,
) : DeferredJobDispatcher {
    override fun dispatch(job: DeferredJob) {
        when (job) {
            is DeferredJob.Adapter -> repairs.enqueue(job.domain, job.mode, job.pageUrl, job.novelUrl)
            is DeferredJob.Process -> process.schedule(job.novelId, job.chapterId)
        }
    }
}

@HiltWorker
class SpendResumeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val resumer: SpendResumer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        resumer.run()
        return Result.success()
    }
}
