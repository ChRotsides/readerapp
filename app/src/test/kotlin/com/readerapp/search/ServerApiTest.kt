package com.readerapp.search

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ServerApiTest {
    private lateinit var server: MockWebServer
    private var config: ServerConfig? = null
    private lateinit var api: ServerApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        config = ServerConfig(server.url("/").toString().trimEnd('/'), "tok")
        api = ServerApi({ config })
    }

    @After fun tearDown() = server.shutdown()

    private val workJson = """{"work_id":"w1","title":"Lord of Mysteries","native_title":null,"aliases":["LotM"],
        "authors":["Cuttlefish"],"cover_url":"https://x/c.jpg","synopsis":"s","tags":["Magic"],"genres":["Fantasy"],"status":"completed",
        "chapter_count":1432,"language":"zh","start_year":2018,
        "ratings":[{"source":"novelupdates","rating":4.5,"scale":5.0,"votes":100,"normalized":9.0}],
        "urls":[{"source":"webnovel","url":"https://www.webnovel.com/book/1"}],"related":[{"kind":"sequel"}]}"""

    @Test fun searchParsesResponseAndSendsBearerAndBody() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[${workJson.dropLast(1)},"score":0.8,"reason":"fits","matched_scenes":2,"rerank_score":0.75}],
            "scenes":["a","b"],"synopsis":"syn","tags":["t1"],"filters":{"language":"zh","status":null,"min_chapters":null,"max_chapters":null,
            "include_tags":[],"exclude_tags":["harem"]},"reranked":true,"rerank_provider_used":"jev","notes":["n"],"extra_field":1}"""))
        val r = api.search(SearchRequest(description = "dark mystery", rerankProvider = "claude", scenes = listOf("x"), synopsis = "y", tags = listOf("z")))
        assertEquals("w1", r.results.single().workId)
        assertEquals(2, r.results.single().matchedScenes)
        assertEquals("fits", r.results.single().reason)
        assertEquals(9.0, r.results.single().ratings.single().normalized, 0.0)
        assertEquals(listOf("harem"), r.filters.excludeTags)
        assertTrue(r.reranked)
        assertEquals("jev", r.rerankProviderUsed)
        assertEquals(0.75, r.results.single().rerankScore!!, 0.0)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/search", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"description\":\"dark mystery\"") && body.contains("\"scenes\":[\"x\"]") && body.contains("\"synopsis\":\"y\""))
        assertTrue(body, body.contains("\"rerank_provider\":\"claude\""))
        assertTrue(body, !body.contains("filters"))
    }

    @Test fun worksByIdAndTitleQuery() = runTest {
        server.enqueue(MockResponse().setBody(workJson))
        assertEquals("Lord of Mysteries", api.work("w1").title)
        assertEquals("/works/w1", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("[$workJson]"))
        assertEquals(1, api.searchTitle("lord of").size)
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/works?query=lord%20of") || path.startsWith("/works?query=lord+of"))
    }

    private suspend fun expectError(code: Int, body: String): SearchException {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        try { api.search(SearchRequest("x")); fail("expected exception") } catch (e: SearchException) { return e }
        throw AssertionError()
    }

    @Test fun statusCodesMapToTypedErrors() = runTest {
        assertTrue(expectError(401, """{"detail":"invalid or missing bearer token"}""") is SearchException.Unauthorized)
        val b = expectError(402, """{"detail":"monthly AI budget reached: 5.0"}""")
        assertTrue(b is SearchException.BudgetReached && b.message!!.contains("monthly AI budget reached"))
        val u = expectError(503, """{"detail":"VOYAGE_API_KEY is not configured"}""")
        assertTrue(u is SearchException.ServerUnavailable && u.detail == "VOYAGE_API_KEY is not configured")
        val h = expectError(500, "boom")
        assertTrue(h is SearchException.Http && h.code == 500)
    }

    @Test fun notFoundForUnknownWork() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"work not found"}"""))
        try { api.work("nope"); fail() } catch (e: SearchException) { assertTrue(e is SearchException.NotFound) }
    }

    @Test fun malformedBodyIsBadResponse() = runTest {
        server.enqueue(MockResponse().setBody("not json"))
        try { api.search(SearchRequest("x")); fail() } catch (e: SearchException) { assertTrue(e is SearchException.BadResponse) }
    }

    @Test fun unreachableServerIsNetworkError() = runTest {
        server.shutdown()
        try { api.search(SearchRequest("x")); fail() } catch (e: SearchException) { assertTrue(e is SearchException.Network) }
    }

    @Test fun notConfigured() = runTest {
        config = null
        try { api.work("w"); fail() } catch (e: SearchException) { assertTrue(e is SearchException.NotConfigured) }
        assertEquals(ConnectionResult.NotConfigured, api.testConnection())
    }

    @Test fun connectionTestOutcomes() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"sources":[],"works":42,"pending_resolution":0,"spend":{}}"""))
        val ok = api.testConnection()
        assertTrue(ok is ConnectionResult.Ok && ok.works == 42)

        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad"}"""))
        assertEquals(ConnectionResult.BadToken, api.testConnection())

        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"API_TOKEN is not configured on the server"}"""))
        val keys = api.testConnection()
        assertTrue(keys is ConnectionResult.ServerKeysMissing && keys.detail!!.contains("API_TOKEN"))

        server.shutdown()
        assertEquals(ConnectionResult.Unreachable, api.testConnection())
    }

    @Test fun serverUrlNormalization() {
        assertEquals("http://100.64.0.1:8000", com.readerapp.data.settings.normalizeServerUrl("100.64.0.1:8000/"))
        assertEquals("https://pc.tail1234.ts.net", com.readerapp.data.settings.normalizeServerUrl("pc.tail1234.ts.net"))
        assertEquals("https://a.example.com", com.readerapp.data.settings.normalizeServerUrl("https://a.example.com/x?y=1"))
        assertEquals(null, com.readerapp.data.settings.normalizeServerUrl("ftp://a.b"))
        assertEquals(null, com.readerapp.data.settings.normalizeServerUrl("   "))
    }

    @Test fun bodyIsReadOffTheCallerThreadAndUnexpectedFailuresAreTyped() = runTest {
        val readers = mutableListOf<String>()
        val spying = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.proceed(chain.request())
            val orig = r.body
            r.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentType() = orig.contentType()
                override fun contentLength() = orig.contentLength()
                override fun source(): okio.BufferedSource { readers += Thread.currentThread().name; return orig.source() }
            }).build()
        }.build()
        val spyApi = ServerApi({ config }, spying)
        server.enqueue(MockResponse().setBody(workJson))
        spyApi.work("w1")
        assertTrue(readers.toString(), readers.isNotEmpty() && readers.none { it.startsWith("Test worker") })

        val exploding = okhttp3.OkHttpClient.Builder().addInterceptor { _ -> throw IllegalStateException("boom") }.build()
        try { ServerApi({ config }, exploding).work("w1"); fail() } catch (e: SearchException) { assertTrue(e is SearchException.Network) }
        assertTrue(ServerApi({ config }, exploding).testConnection().let { it is ConnectionResult.Other || it == ConnectionResult.Unreachable })
    }
}
