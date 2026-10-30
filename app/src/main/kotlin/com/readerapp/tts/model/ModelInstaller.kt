package com.readerapp.tts.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Describes the downloadable model archive. */
data class ModelSpec(
    val url: String,
    val archiveBytes: Long,
    val sha256: String,
    /** Directory the archive extracts to (relative to the install root), also the installed directory's name. */
    val dirName: String,
    val requiredFiles: List<String>,
    val extractedBytesEstimate: Long,
    val freeSpaceMarginBytes: Long,
)

sealed interface ModelState {
    data object NotInstalled : ModelState

    data class Downloading(val downloaded: Long, val total: Long) : ModelState

    data object Verifying : ModelState

    data object Extracting : ModelState

    data class Installed(val dir: File) : ModelState

    /** [retryable]: calling install() again may succeed (resumes a partial download). */
    data class Failed(val reason: String, val retryable: Boolean = true) : ModelState
}

/** Downloads [url] to [dest], appending from [resumeFrom] when the server allows. Throws IOException on failure. */
interface ModelDownloader {
    suspend fun download(url: String, dest: File, resumeFrom: Long, onProgress: (Long) -> Unit)
}

interface ArchiveExtractor {
    /** Extracts a .tar.bz2 into [destDir] (created if needed). */
    suspend fun extract(archive: File, destDir: File)
}

fun interface FreeSpace {
    fun bytesFree(dir: File): Long
}

/**
 * Download -> verify -> extract -> install state machine for the Kokoro model. All I/O goes through interfaces so it
 * can be tested with fakes. A partial archive (`<dir>.tar.bz2.part`) is kept after a failure so a retry resumes it.
 */
class ModelInstaller(
    private val spec: ModelSpec,
    private val root: File,
    private val downloader: ModelDownloader,
    private val extractor: ArchiveExtractor,
    private val freeSpace: FreeSpace,
    private val sha256Of: (File) -> String,
) {
    private val _state = MutableStateFlow<ModelState>(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private val mutex = Mutex()

    val modelDir: File get() = File(root, spec.dirName)
    private val partFile get() = File(root, spec.dirName + ".tar.bz2.part")
    private val tmpDir get() = File(root, spec.dirName + ".extracting")

    fun isInstalled(): Boolean = complete(modelDir)

    private fun complete(dir: File) = spec.requiredFiles.all { File(dir, it).exists() }

    private fun initialState(): ModelState = if (isInstalled()) ModelState.Installed(modelDir) else ModelState.NotInstalled

    /** Bytes the user still has to download (0 when installed). */
    fun remainingDownloadBytes(): Long = if (isInstalled()) 0 else (spec.archiveBytes - partFile.length().coerceAtMost(spec.archiveBytes))

    fun delete() {
        modelDir.deleteRecursively()
        partFile.delete()
        tmpDir.deleteRecursively()
        _state.value = ModelState.NotInstalled
    }

    /** Runs the whole pipeline; returns the final state (also published on [state]). Concurrent calls are serialized. */
    suspend fun install(): ModelState = mutex.withLock {
        if (isInstalled()) return publish(ModelState.Installed(modelDir))
        try {
            root.mkdirs()
            val resume = partFile.length().let { if (it > spec.archiveBytes) { partFile.delete(); 0L } else it }

            val needed = spec.archiveBytes - resume + spec.extractedBytesEstimate + spec.freeSpaceMarginBytes
            val free = freeSpace.bytesFree(root)
            if (free < needed) {
                return publish(
                    ModelState.Failed("Not enough free storage: need about ${needed / MB} MB, ${free / MB} MB available.", retryable = true),
                )
            }

            if (resume < spec.archiveBytes) {
                _state.value = ModelState.Downloading(resume, spec.archiveBytes)
                downloader.download(spec.url, partFile, resume) { done ->
                    _state.value = ModelState.Downloading(done, spec.archiveBytes)
                }
            }
            if (partFile.length() != spec.archiveBytes) {
                val got = partFile.length()
                if (got > spec.archiveBytes) partFile.delete()
                return publish(ModelState.Failed("Download incomplete ($got of ${spec.archiveBytes} bytes).", retryable = true))
            }

            _state.value = ModelState.Verifying
            if (!sha256Of(partFile).equals(spec.sha256, ignoreCase = true)) {
                partFile.delete()
                return publish(ModelState.Failed("The downloaded file is corrupt (checksum mismatch). Retry to download it again."))
            }

            _state.value = ModelState.Extracting
            tmpDir.deleteRecursively()
            extractor.extract(partFile, tmpDir)
            val extracted = File(tmpDir, spec.dirName)
            if (!complete(extracted)) {
                tmpDir.deleteRecursively()
                partFile.delete()
                return publish(ModelState.Failed("The archive did not contain the expected model files."))
            }
            modelDir.deleteRecursively()
            if (!extracted.renameTo(modelDir)) throw IOException("Could not move the model into place")
            tmpDir.deleteRecursively()
            partFile.delete()
            publish(ModelState.Installed(modelDir))
        } catch (e: CancellationException) {
            _state.value = initialState().let { if (it is ModelState.NotInstalled && partFile.length() > 0) ModelState.Failed("Download paused. Retry to resume.") else it }
            throw e
        } catch (e: IOException) {
            publish(ModelState.Failed(e.message ?: "Download failed"))
        } catch (e: Exception) {
            publish(ModelState.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun <T : ModelState> publish(value: T): T {
        _state.value = value
        return value
    }

    private companion object {
        const val MB = 1_000_000L
    }
}
