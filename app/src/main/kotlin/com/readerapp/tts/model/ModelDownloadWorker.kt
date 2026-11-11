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
import com.readerapp.tts.TtsModels
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
        val model = TtsModels.byId(inputData.getString(KEY_MODEL_ID))
        val notificationId = NOTIFICATION_BASE_ID + TtsModels.ALL.indexOf(model) * 2
        ensureChannel()
        setForegroundSafely(notificationId, notification("Preparing ${model.name}...", 0, indeterminate = true))
        val installer = manager.installer(model.id)
        val progress = launch {
            installer.state.collect { s ->
                when (s) {
                    is ModelState.Downloading -> {
                        val pct = if (s.total > 0) (s.downloaded * 100 / s.total).toInt() else 0
                        setForegroundSafely(notificationId, notification("Downloading ${model.name} - ${s.downloaded / MB} / ${s.total / MB} MB", pct, false))
                    }
                    ModelState.Verifying -> setForegroundSafely(notificationId, notification("Verifying ${model.name}...", 0, true))
                    ModelState.Extracting -> setForegroundSafely(notificationId, notification("Unpacking ${model.name}...", 0, true))
                    else -> Unit
                }
            }
        }
        val result = installer.install()
        progress.cancel()
        when (result) {
            is ModelState.Installed -> {
                notifyDone(notificationId + 1, "${model.name} ready", "You can now read chapters aloud.")
                Result.success()
            }
            is ModelState.Failed -> {
                notifyDone(notificationId + 1, "${model.name} download failed", result.reason)
                Result.failure()
            }
            else -> Result.failure()
        }
    }

    private suspend fun setForegroundSafely(id: Int, notification: android.app.Notification) {
        try {
            setForeground(ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
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

    private fun notifyDone(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Speech model download", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "tts_model_download"

        /** Each model gets its own pair of ids (progress, result) so parallel downloads don't overwrite each other. */
        private const val NOTIFICATION_BASE_ID = 4200
        private const val MB = 1_000_000L
    }
}
