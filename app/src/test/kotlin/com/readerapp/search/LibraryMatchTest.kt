package com.readerapp.search

import com.readerapp.data.db.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryMatchTest {
    private fun novel(id: Long, title: String, url: String, domain: String, novelUrl: String? = null) =
        Novel(id = id, title = title, sourceUrl = url, domain = domain, novelUrl = novelUrl)

    private val work = Work(
        workId = "w", title = "Lord of the Mysteries (WN)", aliases = listOf("诡秘之主", "LotM"),
        urls = listOf(SourceUrl("royalroad", "https://www.royalroad.com/fiction/1/lotm"), SourceUrl("webnovel", "https://www.webnovel.com/book/99")),
    )

    @Test fun normalizeTitle() {
        assertEquals("lord of the mysteries", LibraryMatch.normalizeTitle("Lord of the Mysteries (WN)"))
        assertEquals("abc 123", LibraryMatch.normalizeTitle("ＡＢＣ: 123!"))
    }

    @Test fun matchesByNovelPageUrl() {
        val lib = listOf(novel(1, "whatever", "https://x.com/c1", "x.com", novelUrl = "https://royalroad.com/fiction/1/lotm/"))
        assertEquals(1L, LibraryMatch.find(work, lib)?.id)
    }

    @Test fun matchesByDomainAndTitleOrAlias() {
        assertEquals(2L, LibraryMatch.find(work, listOf(novel(2, "Lord of the Mysteries", "https://webnovel.com/c/5", "webnovel.com")))?.id)
        assertEquals(3L, LibraryMatch.find(work, listOf(novel(3, "LotM", "https://webnovel.com/c/5", "webnovel.com")))?.id)
    }

    @Test fun noMatchForSameTitleOnOtherDomainOrDifferentTitle() {
        assertNull(LibraryMatch.find(work, listOf(novel(4, "Lord of the Mysteries", "https://other.com/c", "other.com"))))
        assertNull(LibraryMatch.find(work, listOf(novel(5, "Another Book", "https://webnovel.com/c", "webnovel.com"))))
        assertNull(LibraryMatch.find(work, emptyList()))
    }

    @Test fun matchesMap() {
        val lib = listOf(novel(2, "Lord of the Mysteries", "https://webnovel.com/c/5", "webnovel.com"))
        assertEquals(mapOf("w" to 2L), LibraryMatch.matches(listOf(work, work.copy(workId = "z", title = "Z", aliases = emptyList())), lib))
    }
}
