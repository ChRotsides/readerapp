package com.readerapp.search

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CrawlApiTest {
    private lateinit var server: MockWebServer
    private var config: ServerConfig? = null
    private lateinit var api: ServerApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        config = ServerConfig(server.url("/").toString().trimEnd('/'), "tok")
        api = ServerApi({ config })
    }

    @After fun tearDown() = server.shutdown()

    private val jobJson = """{"id":3,"url":"https://www.royalroad.com/fiction/1","scope":"novel","chapters_per_novel":null,
        "status":"running","pages_fetched":4,"novels_found":1,"chapters_embedded":30,"chapters_skipped":2,"spend_usd":0.0123,
        "error":null,"created_at":"2026-10-30T10:00:00Z","updated_at":"2026-10-30T10:01:00Z","future_field":true}"""

    @Test fun startCrawlPostsExplicitNullForAllChaptersAndParsesJob() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody(jobJson))
        val job = api.startCrawl(CrawlRequest("https://www.royalroad.com/fiction/1", "novel", null))
        assertEquals(3L, job.id); assertEquals("running", job.status)
        assertEquals(30, job.chaptersEmbedded); assertEquals(2, job.chaptersSkipped); assertEquals(0.0123, job.spendUsd, 0.0)
        assertEquals(null, job.chaptersPerNovel); assertEquals(null, job.error)
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/crawl-jobs", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"url\":\"https://www.royalroad.com/fiction/1\""))
        assertTrue(body, body.contains("\"scope\":\"novel\""))
        assertTrue(body, body.contains("\"chapters_per_novel\":null"))
    }

    @Test fun startCrawlSendsChapterCount() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody(jobJson))
        api.startCrawl(CrawlRequest("https://x.example/", "site", 25))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"scope\":\"site\"") && body.contains("\"chapters_per_novel\":25"))
    }

    @Test fun listAndActions() = runTest {
        server.enqueue(MockResponse().setBody("[$jobJson]"))
        assertEquals(listOf(3L), api.crawlJobs().map { it.id })
        assertEquals("GET", server.takeRequest().method)

        suspend fun check(path: String, call: suspend () -> CrawlJob) {
            server.enqueue(MockResponse().setBody(jobJson.replace("running", "paused")))
            assertEquals("paused", call().status)
            val r = server.takeRequest()
            assertEquals("POST", r.method); assertEquals(path, r.path)
            assertEquals("Bearer tok", r.getHeader("Authorization"))
        }
        check("/crawl-jobs/3/pause") { api.pauseCrawl(3) }
        check("/crawl-jobs/3/resume") { api.resumeCrawl(3) }
        check("/crawl-jobs/3/cancel") { api.cancelCrawl(3) }
    }

    @Test fun errorStatesAreTyped() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad token"}"""))
        try { api.crawlJobs(); fail() } catch (e: SearchException) { assertTrue(e is SearchException.Unauthorized) }
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":[{"msg":"invalid url"}]}"""))
        try { api.startCrawl(CrawlRequest("x")); fail() } catch (e: SearchException) {
            assertTrue(e is SearchException.Http && e.message!!.contains("invalid url"))
        }
        config = null
        try { api.crawlJobs(); fail() } catch (e: SearchException) { assertTrue(e is SearchException.NotConfigured) }
        config = ServerConfig(server.url("/").toString().trimEnd('/'), "tok")
        server.shutdown()
        try { api.crawlJobs(); fail() } catch (e: SearchException) { assertTrue(e is SearchException.Network) }
    }

    @Test fun jobActionAvailability() {
        fun job(status: String) = CrawlJob(id = 1, status = status)
        assertTrue(job("running").canPause && job("queued").canPause)
        assertFalse(job("paused").canPause)
        assertTrue(job("paused").canResume && job("blocked").canResume && job("failed").canResume && !job("running").canResume)
        assertFalse(job("done").canResume || job("cancelled").canResume)
        assertTrue(job("paused").canCancel && job("running").canCancel && job("queued").canCancel && job("blocked").canCancel)
        assertFalse(job("done").canCancel || job("failed").canCancel || job("cancelled").canCancel)
    }

    @Test fun libraryUploadPostsBodyAndParsesResult() = runTest {
        server.enqueue(MockResponse().setBody("""{"embedded":2,"skipped":1}"""))
        val r = api.uploadLibraryChapters(
            LibraryUploadRequest(
                LibraryNovelPayload("T", null, null, "https://x.example/n", "x.example"),
                listOf(LibraryChapterPayload(0, "One", null, "text")),
            ),
        )
        assertEquals(LibraryUploadResult(2, 1), r)
        val req = server.takeRequest()
        assertEquals("/library/chapters", req.path); assertEquals("POST", req.method)
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"novel\":{\"title\":\"T\",\"author\":null,\"synopsis\":null,\"url\":\"https://x.example/n\",\"domain\":\"x.example\"}"))
        assertTrue(body, body.contains("\"chapters\":[{\"index\":0,\"title\":\"One\",\"url\":null,\"text\":\"text\"}]"))
    }
}
