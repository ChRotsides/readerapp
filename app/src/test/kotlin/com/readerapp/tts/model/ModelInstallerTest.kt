package com.readerapp.tts.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ModelInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(1_000) { (it % 251).toByte() }
    private val spec = ModelSpec(
        url = "https://example.test/model.tar.bz2",
        archiveBytes = payload.size.toLong(),
        sha256 = "good",
        dirName = "kokoro",
        requiredFiles = listOf("model.onnx", "tokens.txt"),
        extractedBytesEstimate = 500,
        freeSpaceMarginBytes = 100,
    )

    private class FakeDownloader(val payload: ByteArray, val failAfter: Int? = null) : ModelDownloader {
        val resumes = mutableListOf<Long>()
        override suspend fun download(url: String, dest: File, resumeFrom: Long, onProgress: (Long) -> Unit) {
            resumes += resumeFrom
            val end = failAfter?.let { minOf(it, payload.size) } ?: payload.size
            val out = if (resumeFrom > 0) dest.readBytes() else ByteArray(0)
            dest.writeBytes(out + payload.copyOfRange(resumeFrom.toInt(), end))
            onProgress(end.toLong())
            if (failAfter != null && failAfter < payload.size) throw IOException("connection reset")
        }
    }

    private class FakeExtractor(val files: List<String> = listOf("model.onnx", "tokens.txt")) : ArchiveExtractor {
        var extractions = 0
        override suspend fun extract(archive: File, destDir: File) {
            extractions++
            val top = File(destDir, "kokoro").apply { mkdirs() }
            files.forEach { File(top, it).writeText("x") }
        }
    }

    private fun installer(
        root: File = tmp.newFolder(),
        downloader: ModelDownloader = FakeDownloader(payload),
        extractor: ArchiveExtractor = FakeExtractor(),
        free: Long = 1_000_000,
        goodHash: Boolean = true,
    ) = ModelInstaller(spec, root, downloader, extractor, { free }, { if (goodHash) "good" else "bad" })

    @Test
    fun happyPathInstallsAndCleansUp() = runBlocking {
        val root = tmp.newFolder()
        val i = installer(root)
        assertEquals(ModelState.NotInstalled, i.state.value)
        val result = i.install()
        assertTrue(result is ModelState.Installed)
        assertTrue(i.isInstalled())
        assertTrue(File(root, "kokoro/model.onnx").exists())
        assertFalse(File(root, "kokoro.tar.bz2.part").exists())
        assertFalse(File(root, "kokoro.extracting").exists())
        assertEquals(0, i.remainingDownloadBytes())
    }

    @Test
    fun reportsDownloadingVerifyingExtractingInOrder() = runBlocking {
        val seen = mutableListOf<String>()
        val i = installer(downloader = object : ModelDownloader {
            override suspend fun download(url: String, dest: File, resumeFrom: Long, onProgress: (Long) -> Unit) {
                dest.writeBytes(payload)
                onProgress(500)
                onProgress(1_000)
            }
        })
        val job = launch(Dispatchers.Unconfined) { i.state.collect { seen += it::class.simpleName.orEmpty() } }
        i.install()
        job.cancel()
        val distinct = seen.fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
        assertEquals(listOf("NotInstalled", "Downloading", "Verifying", "Extracting", "Installed"), distinct)
    }

    @Test
    fun failsWithoutEnoughFreeSpaceAndDownloadsNothing() = runBlocking {
        val downloader = FakeDownloader(payload)
        val i = installer(downloader = downloader, free = 1_000) // needs 1000 + 500 + 100
        val result = i.install()
        assertTrue(result is ModelState.Failed)
        assertTrue((result as ModelState.Failed).reason.contains("free storage"))
        assertTrue(downloader.resumes.isEmpty())
    }

    @Test
    fun interruptedDownloadKeepsPartialAndRetryResumes() = runBlocking {
        val root = tmp.newFolder()
        val flaky = FakeDownloader(payload, failAfter = 400)
        val first = installer(root, downloader = flaky)
        val failed = first.install()
        assertTrue(failed is ModelState.Failed)
        assertEquals(400, File(root, "kokoro.tar.bz2.part").length())
        assertEquals(600, first.remainingDownloadBytes())

        val good = FakeDownloader(payload)
        val retry = installer(root, downloader = good)
        assertTrue(retry.install() is ModelState.Installed)
        assertEquals(listOf(400L), good.resumes) // resumed, not restarted
    }

    @Test
    fun checksumMismatchDeletesTheArchive() = runBlocking {
        val root = tmp.newFolder()
        val i = installer(root, goodHash = false)
        val result = i.install()
        assertTrue(result is ModelState.Failed)
        assertTrue((result as ModelState.Failed).reason.contains("checksum"))
        assertFalse(File(root, "kokoro.tar.bz2.part").exists())
    }

    @Test
    fun truncatedDownloadWithoutErrorIsRejected() = runBlocking {
        val i = installer(downloader = object : ModelDownloader {
            override suspend fun download(url: String, dest: File, resumeFrom: Long, onProgress: (Long) -> Unit) {
                dest.writeBytes(payload.copyOf(10))
            }
        })
        val result = i.install()
        assertTrue(result is ModelState.Failed)
        assertTrue((result as ModelState.Failed).reason.contains("incomplete"))
    }

    @Test
    fun incompleteArchiveContentsFail() = runBlocking {
        val i = installer(extractor = FakeExtractor(files = listOf("model.onnx")))
        val result = i.install()
        assertTrue(result is ModelState.Failed)
        assertFalse(i.isInstalled())
    }

    @Test
    fun alreadyInstalledDoesNothing() = runBlocking {
        val root = tmp.newFolder()
        File(root, "kokoro").mkdirs()
        File(root, "kokoro/model.onnx").writeText("x")
        File(root, "kokoro/tokens.txt").writeText("x")
        val downloader = FakeDownloader(payload)
        val i = installer(root, downloader = downloader)
        assertTrue(i.state.value is ModelState.Installed)
        assertTrue(i.install() is ModelState.Installed)
        assertTrue(downloader.resumes.isEmpty())
    }

    @Test
    fun deleteRemovesEverything() = runBlocking {
        val root = tmp.newFolder()
        val i = installer(root)
        i.install()
        i.delete()
        assertEquals(ModelState.NotInstalled, i.state.value)
        assertFalse(File(root, "kokoro").exists())
    }
}
