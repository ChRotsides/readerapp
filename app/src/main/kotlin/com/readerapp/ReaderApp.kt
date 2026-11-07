package com.readerapp

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.readerapp.data.repo.StoredTextCleanup
import com.readerapp.favorites.FavoriteSyncTrigger
import com.readerapp.offline.SearchCleanupWorker
import com.readerapp.search.history.SearchHistorySyncTrigger
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
    @Inject lateinit var favoriteSyncTrigger: FavoriteSyncTrigger
    @Inject lateinit var searchHistorySyncTrigger: SearchHistorySyncTrigger

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SearchCleanupWorker.schedule(this)
        uploadTrigger.start()
        favoriteSyncTrigger.start()
        searchHistorySyncTrigger.start()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { storedTextCleanup.runOnce() }.onFailure { Log.w("ReaderApp", "Stored text cleanup failed", it) }
        }
    }
}
