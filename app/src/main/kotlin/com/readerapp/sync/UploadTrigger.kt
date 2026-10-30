package com.readerapp.sync

import com.readerapp.data.db.ChapterTextDao
import com.readerapp.data.repo.NovelRepository
import com.readerapp.data.settings.LibrarySettings
import com.readerapp.data.settings.SecureSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the chapter upload on app start and, debounced, whenever chapter texts are saved or processed, a novel joins
 * the library, or the option is switched on.
 */
@Singleton
class UploadTrigger @Inject constructor(
    private val texts: ChapterTextDao,
    private val novels: NovelRepository,
    private val settings: LibrarySettings,
    private val secure: SecureSettings,
    private val scheduler: LibraryUploadScheduler,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        scope.launch {
            // Room re-emits on every write to the observed tables, so each save/processing result is a signal.
            // Configuring the server (URL / token) counts too: chapters read before that get uploaded then.
            val server = secure.secrets.map { it.serverUrl to it.serverToken }.distinctUntilChanged()
            combine(texts.observeLatestWrite(), novels.observeLibrarySize(), settings.options, server) { _, _, o, _ -> o.uploadChapters }
                .filter { it }
                .debounce(DEBOUNCE_MS)
                .collect { scheduler.enqueue() }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 20_000L
    }
}
