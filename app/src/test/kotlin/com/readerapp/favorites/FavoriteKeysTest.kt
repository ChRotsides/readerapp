package com.readerapp.favorites

import com.readerapp.data.db.Favorite
import com.readerapp.data.db.Novel
import com.readerapp.search.SourceUrl
import com.readerapp.search.Work
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteKeysTest {
    /** The same cases as NORMALIZE_CASES in server/tests/test_favorites.py: keep both lists identical. */
    private val cases = listOf(
        "https://www.Example.com/Novel/Abc/" to "https://example.com/Novel/Abc",
        "http://example.com/novel/1#frag" to "https://example.com/novel/1",
        "  HTTP://WWW.Example.COM:8080/a//?x=1&y=2#z " to "https://example.com:8080/a?x=1&y=2",
        "https://example.com" to "https://example.com",
        "https://example.com/?" to "https://example.com",
        "https://example.com/book?id=5" to "https://example.com/book?id=5",
        "not a url" to "not a url",
    )

    @Test fun normalizationMatchesTheServerCases() {
        for ((raw, want) in cases) {
            assertEquals(raw, want, FavoriteKeys.normalizeUrl(raw))
            assertEquals(want, FavoriteKeys.normalizeUrl(want)) // idempotent
        }
    }

    @Test fun keys() {
        assertEquals("work:abc-1", FavoriteKeys.forWork(" ABC-1 "))
        assertEquals("url:https://example.com/n/1", FavoriteKeys.forUrl("http://www.example.com/n/1/"))
    }

    private fun fav(key: String, workId: String? = null, url: String? = null, on: Boolean = true) =
        Favorite(key = key, workId = workId, novelUrl = url, title = "T", favorite = on, changedAt = 1)

    private val novel = Novel(id = 1, title = "Book", sourceUrl = "https://royalroad.com/fiction/9/ch/1", novelUrl = "https://www.royalroad.com/fiction/9/book/", domain = "royalroad.com")
    private val work = Work(workId = "W1", title = "Book", urls = listOf(SourceUrl("royalroad", "https://royalroad.com/fiction/9/book")))

    @Test fun libraryNovelKeyUsesTheMainPageAndFallsBackToTheSourceUrl() {
        assertEquals("url:https://royalroad.com/fiction/9/book", FavoriteRef.forNovel(novel).key)
        assertEquals("url:https://royalroad.com/fiction/9/ch/1", FavoriteRef.forNovel(novel.copy(novelUrl = null)).key)
    }

    @Test fun aStarShowsHoweverItWasSet() {
        val byWork = FavoriteRef.forWork(work)
        assertEquals("work:w1", byWork.key)
        assertTrue(listOf(fav("work:w1")).isFavorite(byWork))
        // set from the library (url key), seen from the work screen and the other way round
        val byUrl = fav(FavoriteKeys.forUrl("https://royalroad.com/fiction/9/book"), url = "https://royalroad.com/fiction/9/book")
        assertTrue(listOf(byUrl).isFavorite(byWork))
        assertTrue(listOf(fav("work:w1", workId = "w1", url = "https://royalroad.com/fiction/9/book")).isFavorite(FavoriteRef.forNovel(novel)))
        // tombstones and unrelated rows do not count
        assertFalse(listOf(byUrl.copy(favorite = false)).isFavorite(byWork))
        assertFalse(listOf(fav("work:other", workId = "other", url = "https://other.example/x")).isFavorite(byWork))
    }
}
