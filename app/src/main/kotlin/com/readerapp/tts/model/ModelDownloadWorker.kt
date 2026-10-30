package com.readerapp.tts.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Runs [ModelInstaller.install] with a progress notification. Failures are shown in the app (state) and here. */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val manager: ModelManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = coroutineScope {
        ensureChannel()
        setForegroundSafely(notification("Preparing speech model...", 0, indeterminate = true))
        val progress = launch {
            manager.state.collect { s ->
                when (s) {
                    is ModelState.Downloading -> {
                        val pct = if (s.total > 0) (s.downloaded * 100 / s.total).toInt() else 0
                        setForegroundSafely(notification("Downloading speech model - ${s.downloaded / MB} / ${s.total / MB} MB", pct, false))
                    }
                    ModelState.Verifying -> setForegroundSafely(notification("Verifying speech model...", 0, true))
                    ModelState.Extracting -> setForegroundSafely(notification("Unpacking speech model...", 0, true))
                    else -> Unit
                }
            }
        }
        val result = manager.installer.install()
        progress.cancel()
        when (result) {
            is ModelState.Installed -> {
                notifyDone("Speech model ready", "You can now read chapters aloud.")
                Result.success()
            }
            is ModelState.Failed -> {
                notifyDone("Speech model download failed", result.reason)
                Result.failure()
            }
            else -> Result.failure()
        }
    }

    private suspend fun setForegroundSafely(notification: android.app.Notification) {
        try {
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (_: Exception) {
            // Not allowed to start a foreground service right now; the download still runs as ordinary work.
        }
    }

    private fun notification(text: String, percent: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Speech model")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()

    private fun notifyDone(title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, n) }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Speech model download", NotificationManager.IMPORTANCE_LOW))
    }

    private companion object {
        const val CHANNEL_ID = "tts_model_download"
        const val NOTIFICATION_ID = 4101
        const val MB = 1_000_000L
    }
}
