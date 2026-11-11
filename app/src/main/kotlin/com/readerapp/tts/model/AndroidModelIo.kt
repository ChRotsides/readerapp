package com.readerapp.tts.model

import android.content.Context
import android.os.StatFs
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.readerapp.tts.TtsModels
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/** App-wide owner of one [ModelInstaller] per registry model; downloads run as foreground-service WorkManager jobs. */
@Singleton
class ModelManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "tts")
    private val downloader = OkHttpModelDownloader()
    private val extractor = CommonsArchiveExtractor()

    private val installers: Map<String, ModelInstaller> = TtsModels.ALL.associate { m ->
        m.id to ModelInstaller(
            spec = m.spec,
            root = root,
            downloader = downloader,
            extractor = extractor,
            freeSpace = FreeSpace { dir -> StatFs(dir.absolutePath).availableBytes },
            sha256Of = ::sha256Hex,
        )
    }

    fun installer(modelId: String): ModelInstaller = installers.getValue(TtsModels.byId(modelId).id)

    fun state(modelId: String): StateFlow<ModelState> = installer(modelId).state

    /** All models' states, keyed by model id. */
    val states: StateFlow<Map<String, ModelState>> = combine(TtsModels.ALL.map { m -> state(m.id).map { m.id to it } }) { it.toMap() }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, TtsModels.ALL.associate { it.id to state(it.id).value })

    /** Queues the download of [modelId] (resumes a partial one). Safe to call repeatedly. */
    fun startDownload(modelId: String) {
        val id = TtsModels.byId(modelId).id
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(modelId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(TtsModels.byId(modelId).id))
    }

    private fun workName(id: String) = "tts-model-download-$id"
}
