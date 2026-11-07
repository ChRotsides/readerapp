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

class FavoritesApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = ServerApi({ ServerConfig(server.url("/").toString().trimEnd('/'), "tok") })
    }

    @After fun tearDown() = server.shutdown()

    private val dtoJson = """{"key":"work:abc","work_id":"abc","novel_url":null,"title":"Book","author":"A","cover_url":null,"domain":null,
        "favorite":false,"changed_at":"2026-10-31T10:00:00.250000Z","updated_at":"2026-10-31T10:00:01Z","future":1}"""

    @Test fun putSendsTheFavoriteAndParsesTheStoredState() = runTest {
        server.enqueue(MockResponse().setBody(dtoJson))
        val stored = api.putFavorite(FavoriteDto(key = "work:abc", workId = "abc", title = "Book", favorite = false, changedAt = "2026-10-31T09:00:00Z"))
        assertFalse(stored.favorite); assertEquals("2026-10-31T10:00:00.250000Z", stored.changedAt); assertNull(stored.novelUrl)
        val req = server.takeRequest()
        assertEquals("PUT", req.method); assertEquals("/favorites", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"key\":\"work:abc\"") && body.contains("\"work_id\":\"abc\""))
        assertTrue(body, body.contains("\"favorite\":false") && body.contains("\"changed_at\":\"2026-10-31T09:00:00Z\""))
        assertFalse(body, body.contains("updated_at"))
    }

    @Test fun pullPassesSinceAndParsesTheList() = runTest {
        server.enqueue(MockResponse().setBody("""{"favorites":[$dtoJson],"server_time":"2026-10-31T10:05:00.5+00:00"}"""))
        val r = api.favorites("2026-10-31T10:00:00.123456+00:00")
        assertEquals(1, r.favorites.size); assertEquals("2026-10-31T10:05:00.5+00:00", r.serverTime)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("2026-10-31T10:00:00.123456+00:00", req.requestUrl!!.queryParameter("since"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test fun pullWithoutSinceHasNoQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"favorites":[],"server_time":"2026-10-31T10:05:00Z"}"""))
        api.favorites(null)
        assertEquals("/favorites", server.takeRequest().path)
    }

    @Test fun errorsAreTyped() = runTest {
        suspend fun code(c: Int): SearchException {
            server.enqueue(MockResponse().setResponseCode(c).setBody("""{"detail":"nope"}"""))
            try { api.favorites(null) } catch (e: SearchException) { return e }
            fail("expected a failure"); throw AssertionError()
        }
        assertTrue(code(401) is SearchException.Unauthorized)
        assertTrue(code(422).let { it is SearchException.Http && it.code == 422 })
        assertTrue(code(429).let { it is SearchException.Http && it.code == 429 })
        assertTrue(code(500).let { it is SearchException.Http && it.code == 500 })
        assertTrue(code(503) is SearchException.ServerUnavailable)
    }
}
