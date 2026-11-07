package com.readerapp.search

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SearchHistoryApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = ServerApi({ ServerConfig(server.url("/").toString().trimEnd('/'), "tok") })
    }

    @After fun tearDown() = server.shutdown()

    private val itemJson = """{"id":"11111111-1111-1111-1111-111111111111","description":"a cook and a dragon","result_count":2,
        "created_at":"2026-10-31T10:00:00.250000Z","deleted":false,"future":1}"""

    @Test fun listPassesSinceAndLimitAndParsesRows() = runTest {
        server.enqueue(MockResponse().setBody("""{"searches":[$itemJson,{"id":"2","description":"x","result_count":0,"created_at":"2026-10-31T09:00:00Z","deleted":true}],"server_time":"2026-10-31T10:05:00Z"}"""))
        val r = api.searches("2026-10-31T10:00:00.123456+00:00", 50)
        assertEquals(2, r.searches.size); assertEquals("a cook and a dragon", r.searches[0].description); assertEquals(2, r.searches[0].resultCount)
        assertTrue(r.searches[1].deleted); assertEquals("2026-10-31T10:05:00Z", r.serverTime)
        val req = server.takeRequest()
        assertEquals("GET", req.method); assertEquals("/searches", req.requestUrl!!.encodedPath)
        assertEquals("2026-10-31T10:00:00.123456+00:00", req.requestUrl!!.queryParameter("since"))
        assertEquals("50", req.requestUrl!!.queryParameter("limit"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test fun listWithoutSinceHasNoSinceParameter() = runTest {
        server.enqueue(MockResponse().setBody("""{"searches":[],"server_time":"2026-10-31T10:05:00Z"}"""))
        api.searches(null)
        assertNull(server.takeRequest().requestUrl!!.queryParameter("since"))
    }

    @Test fun entryParsesResultsRequestAndGeneratedParts() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"abc","description":"d","result_count":1,"created_at":"2026-10-31T10:00:00Z","deleted":false,
            "request":{"description":"d","limit":20},
            "scenes":{"scenes":["s1"],"synopsis":"syn","tags":["t"],"filters":{"status":"ongoing"},"reranked":true,"notes":["n"]},
            "results":[{"work_id":"w1","title":"Book","authors":["A"],"cover_url":"https://x/c.jpg","synopsis":"S","tags":["t"],"status":"ongoing",
                       "chapter_count":12,"score":0.9,"reason":"fits","matched_scenes":2}]}"""))
        val e = api.searchEntry("abc")
        assertEquals("/searches/abc", server.takeRequest().path)
        val w = e.results.single()
        assertEquals("Book", w.title); assertEquals(0.9, w.score, 0.0); assertEquals("fits", w.reason); assertEquals(2, w.matchedScenes)
        assertEquals(12, w.chapterCount); assertEquals("https://x/c.jpg", w.coverUrl)
        val bundle = ServerApi.json.decodeFromString(SearchBundle.serializer(), e.scenes.toString())
        assertEquals(listOf("s1"), bundle.scenes); assertEquals("ongoing", bundle.filters.status); assertTrue(bundle.reranked)
        assertEquals(SearchRequest("d"), ServerApi.json.decodeFromString(SearchRequest.serializer(), e.request.toString()))
    }

    @Test fun deleteSendsDeleteAndTreatsAlreadyGoneAsDone() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteSearch("abc")
        server.takeRequest().let { assertEquals("DELETE", it.method); assertEquals("/searches/abc", it.path); assertEquals("Bearer tok", it.getHeader("Authorization")) }
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"search not found"}"""))
        api.deleteSearch("gone")
        server.enqueue(MockResponse().setResponseCode(500))
        try { api.deleteSearch("abc"); fail("expected a failure") } catch (e: SearchException.Http) { assertEquals(500, e.code) }
    }

    @Test fun searchResponseCarriesTheHistoryId() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[],"scenes":[],"synopsis":"","tags":[],"filters":{},"reranked":false,"history_id":"h-1"}"""))
        assertEquals("h-1", api.search(SearchRequest("x")).historyId)
        server.enqueue(MockResponse().setBody("""{"results":[],"scenes":[],"synopsis":"","tags":[],"filters":{},"reranked":false}"""))
        assertNull(api.search(SearchRequest("x")).historyId)
    }

    @Test fun errorsAreTyped() = runTest {
        suspend fun code(c: Int): SearchException {
            server.enqueue(MockResponse().setResponseCode(c).setBody("""{"detail":"nope"}"""))
            try { api.searchEntry("abc") } catch (e: SearchException) { return e }
            fail("expected a failure"); throw AssertionError()
        }
        assertTrue(code(401) is SearchException.Unauthorized)
        assertTrue(code(404) is SearchException.NotFound)
        assertFalse(code(500) is SearchException.NotFound)
    }
}
