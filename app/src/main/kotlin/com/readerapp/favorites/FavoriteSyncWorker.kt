package com.readerapp.favorites

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
import com.readerapp.data.db.FavoriteDao
import com.readerapp.data.settings.SecureSettings
import com.readerapp.search.DiscoveryApi
import com.readerapp.search.SearchException
import com.readerapp.search.ServerConfigProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Syncs favorites with the discovery server (see [FavoriteSync]). Does nothing while no server is configured; the next trigger tries again. */
@HiltWorker
class FavoriteSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: FavoriteStore,
    private val api: DiscoveryApi,
    private val config: ServerConfigProvider,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (config.config() == null) return Result.success()
        return try {
            repeat(MAX_PASSES) {
                val o = FavoriteSync(store, api).run()
                if (o.pushed > 0 || o.pulled > 0) Log.i(TAG, "Favorites synced: pushed ${o.pushed}, pulled ${o.pulled}")
                if (!o.moreWork) return Result.success()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchException) {
            when (e) {
                // Nothing a retry can fix; the next trigger (settings change, toggle, app start) tries again.
                is SearchException.NotConfigured, is SearchException.Unauthorized -> Result.success()
                else -> if (FavoriteSync.isPermanent(e)) Result.success() else retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Favorites sync failed", e)
            retry()
        }
    }

    private fun retry() = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    private companion object {
        const val TAG = "FavoriteSync"
        const val MAX_ATTEMPTS = 6
        const val MAX_PASSES = 3
    }
}

@Singleton
class FavoriteSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    /** One pending/running sync at a time; the worker re-checks for toggles made while it ran, so KEEP loses nothing. */
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<FavoriteSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_NAME = "favorites-sync"
    }
}

/**
 * Starts the sync on app start, when the server URL / token changes (including the first time they are set), and
 * debounced after each local toggle.
 */
@Singleton
class FavoriteSyncTrigger @Inject constructor(
    private val dao: FavoriteDao,
    private val secure: SecureSettings,
    private val scheduler: FavoriteSyncScheduler,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        scope.launch {
            val server = secure.secrets.map { it.serverUrl to it.serverToken }.distinctUntilChanged().map { }
            val toggled = dao.observePendingStamp().filter { it > 0 }.map { }
            merge(server, toggled).debounce(DEBOUNCE_MS).collect { scheduler.enqueue() }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
