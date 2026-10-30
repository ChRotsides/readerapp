package com.readerapp.tts.model

import android.content.Context
import android.os.StatFs
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.readerapp.tts.TtsConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** OkHttp download with HTTP Range resume. */
class OkHttpModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) : ModelDownloader {
    override suspend fun download(url: String, dest: File, resumeFrom: Long, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }.build()
        client.newCall(request).execute().use { response ->
            val resumed = response.code == 206
            if (response.code == 416) return@use // already complete; the caller verifies size and checksum
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response")
            // A 200 to a ranged request means the server ignored Range: start over.
            var written = if (resumed) resumeFrom else 0L
            RandomAccessFile(dest, "rw").use { out ->
                out.seek(written)
                if (!resumed) out.setLength(0)
                val buffer = ByteArray(64 * 1024)
                var lastReport = 0L
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastReport >= 256 * 1024) {
                            lastReport = written
                            onProgress(written)
                        }
                    }
                }
                onProgress(written)
            }
        }
    }
}

/** .tar.bz2 extraction with Apache Commons Compress; refuses entries that would escape [destDir]. */
class CommonsArchiveExtractor : ArchiveExtractor {
    override suspend fun extract(archive: File, destDir: File) = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val base = destDir.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(256 * 1024))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                val target = File(base, entry.name).canonicalFile
                if (target != base && !target.path.startsWith(base.path + File.separator)) {
                    throw IOException("Unsafe path in archive: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else if (entry.isFile) {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { tar.copyTo(it) }
                }
            }
        }
    }
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** App-wide owner of the model installer; starts the download as a foreground-service WorkManager job. */
@Singleton
class ModelManager @Inject constructor(@ApplicationContext private val context: Context) {
    val installer = ModelInstaller(
        spec = ModelSpec(
            url = TtsConfig.MODEL_URL,
            archiveBytes = TtsConfig.MODEL_ARCHIVE_BYTES,
            sha256 = TtsConfig.MODEL_SHA256,
            dirName = TtsConfig.MODEL_DIR_NAME,
            requiredFiles = TtsConfig.REQUIRED_FILES,
            extractedBytesEstimate = TtsConfig.EXTRACTED_BYTES_ESTIMATE,
            freeSpaceMarginBytes = TtsConfig.FREE_SPACE_MARGIN_BYTES,
        ),
        root = File(context.getExternalFilesDir(null) ?: context.filesDir, "tts"),
        downloader = OkHttpModelDownloader(),
        extractor = CommonsArchiveExtractor(),
        freeSpace = FreeSpace { dir -> StatFs(dir.absolutePath).availableBytes },
        sha256Of = ::sha256Hex,
    )

    val state get() = installer.state

    /** Queues the download (resumes a partial one). Safe to call repeatedly. */
    fun startDownload() {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "tts-model-download"
    }
}
