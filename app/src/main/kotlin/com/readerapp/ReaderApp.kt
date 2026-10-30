package com.readerapp

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.readerapp.data.repo.StoredTextCleanup
import com.readerapp.offline.SearchCleanupWorker
import com.readerapp.sync.UploadTrigger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ReaderApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var storedTextCleanup: StoredTextCleanup
    @Inject lateinit var uploadTrigger: UploadTrigger

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SearchCleanupWorker.schedule(this)
        uploadTrigger.start()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { storedTextCleanup.runOnce() }.onFailure { Log.w("ReaderApp", "Stored text cleanup failed", it) }
        }
    }
}
