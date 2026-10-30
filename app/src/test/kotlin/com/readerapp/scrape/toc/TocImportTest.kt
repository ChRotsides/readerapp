package com.readerapp.scrape.toc

import com.readerapp.data.db.AdapterKind
import com.readerapp.data.db.AdapterStatus
import com.readerapp.data.db.Chapter
import com.readerapp.data.db.Novel
import com.readerapp.data.db.SiteAdapter
import com.readerapp.scrape.ChapterRef
import com.readerapp.scrape.ExtractedNovelInfo
import com.readerapp.scrape.FetchMode
import com.readerapp.scrape.FetchedPage
import com.readerapp.scrape.adapter.ChapterSpec
import com.readerapp.scrape.adapter.JsonAdapterSpec
import com.readerapp.scrape.adapter.NovelSpec
import com.readerapp.scrape.adapter.TocSpec
import com.readerapp.scrape.heal.FakeStore
import com.readerapp.scrape.heal.PageSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TocImportTest {
    private val domain = "novels.example"

    private class FakePages(val pages: Map<String, String>) : PageSource {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String): FetchedPage? {
            fetched += url
            return pages[url]?.let { FetchedPage(url, it, FetchMode.HTTP) }
        }
        override suspend fun sampleUrls(domain: String, excludeUrl: String, limit: Int) = emptyList<String>()
        override suspend fun latestChapterUrl(domain: String): String? = null
    }

    private class FakeTocStore(var novel: Novel, chapters: List<Chapter> = emptyList()) : TocStore {
        val chapters = chapters.toMutableList()
        var tocAt: Long? = null
        override suspend fun novel(novelId: Long) = novel
        override suspend fun saveNovelInfo(novelId: Long, info: ExtractedNovelInfo?, novelUrl: String?) {
            novel = novel.copy(
                author = novel.author ?: info?.author, coverUrl = novel.coverUrl ?: info?.coverUrl,
                synopsis = novel.synopsis ?: info?.synopsis, novelUrl = novelUrl ?: novel.novelUrl,
            )
        }
        override suspend fun applyToc(novelId: Long, refs: List<ChapterRef>): TocPlan {
            val plan = TocPlan.compute(chapters, refs)
            plan.updates.forEach { u -> chapters.replaceAll { if (it.id == u.chapterId) it.copy(index = u.index, title = u.title) else it } }
            plan.inserts.forEachIndexed { i, ins -> chapters += Chapter(id = 100L + i, novelId = novelId, index = ins.index, title = ins.title, url = ins.url) }
            return plan
        }
        override suspend fun latestFetchedChapterUrl(novelId: Long): String? = chapters.lastOrNull()?.url
        override suspend fun markTocFetched(novelId: Long, at: Long) { tocAt = at }
    }

    private fun spec(withToc: Boolean = true) = JsonAdapterSpec(
        chapter = ChapterSpec(content = "#content"),
        novel = NovelSpec(title = "h1", author = ".author", cover = "img.cover", synopsis = ".synopsis"),
        toc = if (withToc) TocSpec(items = "ul.chapters a", nextPage = "a.next") else null,
    )

    private fun store(spec: JsonAdapterSpec, kind: AdapterKind = AdapterKind.JSON) =
        FakeStore(listOf(SiteAdapter(domain, 1, kind, spec.toJson(), "claude-sonnet-5-5", AdapterStatus.ACTIVE)))

    private val novel = Novel(id = 1, title = "N", sourceUrl = "https://$domain/book/1/ch-57", domain = domain, novelUrl = "https://$domain/book/1")

    private fun listPage(from: Int, to: Int, next: String?) = buildString {
        append("<html><body><h1>Title</h1><span class='author'>Au Thor</span><img class='cover' src='/c.jpg'><p class='synopsis'>Syn.</p><ul class='chapters'>")
        for (i in from..to) append("<li><a href='/book/1/ch-$i'>Chapter $i</a></li>")
        append("</ul>")
        if (next != null) append("<a class='next' href='$next'>next</a>")
        append("</body></html>")
    }

    @Test
    fun `imports a paginated toc with correct indices and keeps existing chapters`() = runTest {
        val existing = Chapter(id = 5, novelId = 1, index = 0, title = "", url = "https://$domain/book/1/ch-3/")
        val tocStore = FakeTocStore(novel, listOf(existing))
        val pages = FakePages(
            mapOf(
                "https://$domain/book/1" to listPage(1, 3, "/book/1?page=2"),
                "https://$domain/book/1?page=2" to listPage(4, 6, "/book/1?page=3"),
                "https://$domain/book/1?page=3" to listPage(7, 8, null),
            ),
        )
        val result = TocImporter(tocStore, store(spec()), pages).import(1)

        assertEquals(TocResult.Imported(total = 8, added = 7, pages = 3), result)
        assertEquals(3, pages.fetched.size)
        val byUrl = tocStore.chapters.associateBy { it.url }
        assertEquals(8, tocStore.chapters.size)
        assertEquals(2, byUrl.getValue("https://$domain/book/1/ch-3/").index) // existing chapter re-indexed, same id
        assertEquals(5L, byUrl.getValue("https://$domain/book/1/ch-3/").id)
        assertEquals("Chapter 3", byUrl.getValue("https://$domain/book/1/ch-3/").title)
        assertEquals(7, byUrl.getValue("https://$domain/book/1/ch-8").index)
        assertEquals("Au Thor", tocStore.novel.author)
        assertEquals("https://$domain/c.jpg", tocStore.novel.coverUrl)
        assertEquals("Syn.", tocStore.novel.synopsis)
        assertNotNull(tocStore.tocAt)
    }

    @Test
    fun `newest-first lists are reversed and repeated next pages stop`() = runTest {
        val html = buildString {
            append("<ul class='chapters'>")
            for (i in 3 downTo 1) append("<li><a href='/book/1/ch-$i'>Chapter $i</a></li>")
            append("</ul><a class='next' href='/book/1'>next</a>")
        }
        val tocStore = FakeTocStore(novel)
        val pages = FakePages(mapOf("https://$domain/book/1" to html))
        val result = TocImporter(tocStore, store(spec()), pages).import(1)
        assertTrue(result is TocResult.Imported)
        assertEquals(1, pages.fetched.size)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), tocStore.chapters.sortedBy { it.index }.map { it.title })
    }

    @Test
    fun `no active adapter, no toc selectors, or unreachable page are reported`() = runTest {
        val pages = FakePages(emptyMap())
        assertEquals(TocResult.NoAdapter, TocImporter(FakeTocStore(novel), FakeStore(), pages).import(1))
        assertEquals(TocResult.NoTocSpec, TocImporter(FakeTocStore(novel), store(spec(withToc = false)), pages).import(1))
        assertTrue(TocImporter(FakeTocStore(novel), store(spec()), pages).import(1) is TocResult.FetchFailed)
        assertTrue(pages.fetched.isNotEmpty())
    }

    @Test
    fun `missing novel url is discovered from the latest chapter page`() = runTest {
        val noUrl = novel.copy(novelUrl = null)
        val chapter = Chapter(id = 9, novelId = 1, index = 0, url = "https://$domain/book/1/ch-3")
        val tocStore = FakeTocStore(noUrl, listOf(chapter))
        val pages = FakePages(
            mapOf(
                chapter.url to "<html><body><div class='breadcrumb'><a href='/'>Home</a><a href='/book/1'>Novel</a></div></body></html>",
                "https://$domain/book/1" to listPage(1, 2, null),
            ),
        )
        val result = TocImporter(tocStore, store(spec()), pages).import(1)
        assertTrue(result is TocResult.Imported)
        assertEquals("https://$domain/book/1", tocStore.novel.novelUrl)
    }

    @Test
    fun `novel url finder uses breadcrumbs, contents links and the parent path`() {
        val page = "https://$domain/book/1/ch-3"
        assertEquals(
            "https://$domain/book/1",
            NovelUrlFinder.find("<div class='breadcrumbs'><a href='/'>Home</a> <a href='/book/1'>Novel</a> <a href='$page'>Ch 3</a></div>", page),
        )
        assertEquals(
            "https://$domain/series/abc",
            NovelUrlFinder.find("<a href='/series/abc'>Table of Contents</a><a href='/other'>Other</a>", page),
        )
        assertEquals("https://$domain/book/1", NovelUrlFinder.find("<p>no links</p>", page))
        assertNull(NovelUrlFinder.find("<p>x</p>", "https://$domain/chapter-5"))
        assertNull("links to other sites are ignored", NovelUrlFinder.find("<a href='https://elsewhere.org/x/y'>Contents</a>", "https://$domain/c-5"))
    }

    @Test
    fun `plan keeps existing ids, only changes what differs and orders by toc position`() {
        val existing = listOf(
            Chapter(id = 1, novelId = 1, index = 0, title = "Keep me", url = "https://a.example/x/1"),
            Chapter(id = 2, novelId = 1, index = 5, title = "", url = "https://www.a.example/x/2#frag"),
        )
        val plan = TocPlan.compute(
            existing,
            listOf(
                ChapterRef("One", "https://a.example/x/1"),
                ChapterRef("Two", "https://a.example/x/2/"),
                ChapterRef("Three", "https://a.example/x/3"),
                ChapterRef("Dup", "https://a.example/x/3"),
            ),
        )
        assertEquals(listOf(TocUpdate(2, 1, "Two")), plan.updates) // chapter 1 already correct => no update
        assertEquals(listOf(TocInsert("https://a.example/x/3", 2, "Three")), plan.inserts)
    }
}
