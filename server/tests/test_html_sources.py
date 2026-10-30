"""HTML-site sources (5b) on recorded/hand-written fixtures (tests/fixtures/html/_README.txt); no live network."""
import json

import httpx
import pytest

from readerserver.config import Settings
from readerserver.crawl.http import BlockedError, PoliteClient, RobotsDisallowed
from readerserver.services import HTML_SOURCES, build_source, source_names
from readerserver.sources import alphapolis, kakuyomu, munpia, novelupdates, royalroad, scribblehub, tapas, webnovel, wuxiaworld
from readerserver.sources.syosetu import SyosetuSource, parse_chapter

from .conftest import FIXTURES

FX = FIXTURES / "html"


def fx(name: str) -> str:
    return (FX / name).read_text()


def client(handler, robots=False) -> PoliteClient:
    return PoliteClient("TestCrawler/1", min_delay=0, respect_robots=robots, transport=httpx.MockTransport(handler))


def router(routes: dict[str, str], seen: list | None = None):
    """path(+query-less) -> fixture text; unknown -> 404."""

    def handler(req: httpx.Request):
        if seen is not None:
            seen.append(str(req.url))
        for key, body in routes.items():
            if key == "*" or req.url.path == key or (key.endswith("/") and key in req.url.path):
                return httpx.Response(200, text=fx(body) if body.endswith((".html", ".xml", ".json")) else body)
        return httpx.Response(404)

    return handler


async def collect(src, cp=None):
    return [b async for b in src.iter_catalog(cp)]


# ---------------------------------------------------------------- kakuyomu
def test_kakuyomu_parse():
    recs = kakuyomu.parse_ranking(fx("kakuyomu_ranking.html"))
    assert len(recs) == 3
    r = recs[0]
    assert r.source == "kakuyomu" and r.language == "ja" and r.url == f"https://kakuyomu.jp/works/{r.source_id}"
    assert r.title and r.authors and r.tags and r.status in ("ongoing", "completed") and r.chapter_count > 0
    assert r.genres == ["Fantasy"] and r.start_year == 2026 and r.external_ids == {"kakuyomu": r.source_id}
    assert r.raw["_version"].endswith(str(r.chapter_count))
    assert recs[1].alt_titles == ["Placeholder Alternate Title"]


async def test_kakuyomu_paging_checkpoint_resume():
    urls: list[str] = []
    src = kakuyomu.KakuyomuSource(client(router({"*": "kakuyomu_ranking.html"}, urls)), max_pages=2, genres=["fantasy", "action"])
    batches = await collect(src)
    assert [b.checkpoint for b in batches] == [{"genre_index": 0, "page": 2}, {"genre_index": 1, "page": 1}, {"genre_index": 1, "page": 2}, {"genre_index": 2, "page": 1}]
    assert batches[-1].done and not batches[0].done
    assert urls[0] == "https://kakuyomu.jp/rankings/fantasy/entire?work_variation=long&page=1"
    urls.clear()
    resumed = await collect(kakuyomu.KakuyomuSource(client(router({"*": "kakuyomu_ranking.html"}, urls)), max_pages=2, genres=["fantasy", "action"]), {"genre_index": 1, "page": 2})
    assert len(resumed) == 1 and resumed[0].done and urls == ["https://kakuyomu.jp/rankings/action/entire?work_variation=long&page=2"]


# ---------------------------------------------------------------- tapas
def test_tapas_parse_and_skips_comics():
    r = tapas.parse_series(fx("tapas_series.html"), "bluephoenix", "2025-01-01")
    assert r.title == "Blue Phoenix" and r.authors == ["Tinalynge"] and r.genres == ["Action Fantasy"]
    assert "cultivation" in r.tags and "Weak to Strong" in r.tags and "#Dragon" not in r.tags
    assert r.chapter_count == 1459 and r.status == "ongoing" and r.start_year == 2020
    assert r.external_ids == {"tapas": "bluephoenix", "tapas_series_id": "169224"}
    assert r.raw["stats"]["subscribers"] == 8767 and r.raw["_version"] == "2025-01-01"
    assert r.url == "https://tapas.io/series/bluephoenix" and r.synopsis == "Placeholder synopsis text for the fixture."
    assert tapas.parse_series(fx("tapas_comic.html"), "x") is None


async def test_tapas_catalog_version_skip_and_checkpoint():
    urls: list[str] = []
    h = router({"/sitemap-novel.xml": "tapas_sitemap_novel.xml", "/series/bluephoenix/info": "tapas_series.html"}, urls)
    src = tapas.TapasSource(client(h), batch_size=2)
    entries = await src.sitemap()
    assert len(entries) == 4
    batches = await collect(src)
    assert batches[-1].done and [b.checkpoint["index"] for b in batches] == [2, 4]
    assert sum(len(b.records) for b in batches) == 1  # only bluephoenix has a page; others 404
    lastmod = dict((u.rsplit("/", 1)[-1], lm) for u, lm in entries)["bluephoenix"]
    urls.clear()
    src2 = tapas.TapasSource(client(h), batch_size=2)
    src2.known_versions = {"bluephoenix": lastmod}
    await collect(src2)
    assert not any(u.endswith("/bluephoenix/info") for u in urls)  # unchanged -> detail skipped
    resumed = await collect(tapas.TapasSource(client(h), batch_size=2), {"index": 2})
    assert len(resumed) == 1 and resumed[0].done


# ---------------------------------------------------------------- wuxiaworld
def test_wuxiaworld_parse():
    free = wuxiaworld.parse_novel(fx("wuxiaworld_novel_free.html"), "2026-10-23")
    assert free.source_id == "ready-set-apocalypse" and free.title == "Ready, Set, Apocalypse" and free.language == "zh"
    assert free.authors == ["Prince Noodles"] and free.status == "ongoing" and free.chapter_count == 20
    assert free.synopsis == "Placeholder synopsis." and "Fantasy" in free.genres and free.start_year == 2026
    assert free.raw["free_all"] is True and free.raw["translator"] == "r4gequ1t_cy@" and free.raw["_version"] == "2026-10-23"
    paid = wuxiaworld.parse_novel(fx("wuxiaworld_novel_paid.html"))
    assert paid.authors == ["I Eat Tomatoes (我吃西红柿)", "I Eat Tomatoes", "我吃西红柿"]
    assert paid.alt_titles[:2] == ["盘龙", "Panlong"] and paid.status == "completed" and paid.chapter_count == 807
    assert paid.rating == 0.87 and paid.rating_scale == 1.0 and paid.rating_votes == 33 and paid.raw["free_all"] is False
    assert "Chinese" not in paid.tags and paid.raw["licensed_from"] == "Yuewen"


ROUTES_WW = {
    "/sitemap-novels.xml": "wuxiaworld_sitemap_novels.xml",
    "/novel/ready-set-apocalypse": "wuxiaworld_novel_free.html",
    "/novel/coiling-dragon": "wuxiaworld_novel_paid.html",
    "/novel/ready-set-apocalypse/rsa-chapter-1": "wuxiaworld_chapter1.html",
    "/novel/ready-set-apocalypse/rsa-chapter-2": "wuxiaworld_chapter2.html",
    "/novel/coiling-dragon/cd-book-1-chapter-1": "wuxiaworld_chapter_paid.html",
}


async def test_wuxiaworld_catalog_version_skip_and_samples():
    urls: list[str] = []
    h = router(ROUTES_WW, urls)
    batches = await collect(wuxiaworld.WuxiaworldSource(client(h), batch_size=1))
    assert [len(b.records) for b in batches] == [1, 1] and batches[-1].done
    src = wuxiaworld.WuxiaworldSource(client(h))
    src.known_versions = {"coiling-dragon": "2025-11-06T09:39:56+00:00"}
    urls.clear()
    recs = [r for b in await collect(src) for r in b.records]
    assert [r.source_id for r in recs] == ["ready-set-apocalypse"] and not any("coiling" in u for u in urls)
    # samples: free novel -> chapters 1 and 2 (chapter 3 does not exist -> stop); paid/wait novel -> nothing
    free = wuxiaworld.WuxiaworldSource(client(h))
    texts = await free.fetch_sample_chapters(recs[0], 3)
    assert texts == ["Placeholder chapter one text.\n\nSecond placeholder paragraph.", "Placeholder chapter two text."]
    assert await wuxiaworld.WuxiaworldSource(client(h)).fetch_sample_chapters(
        wuxiaworld.parse_novel(fx("wuxiaworld_novel_paid.html")), 3) == []


async def test_wuxiaworld_paid_or_unknown_never_requests_a_chapter_page():
    urls: list[str] = []
    h = router(ROUTES_WW, urls)
    paid = wuxiaworld.parse_novel(fx("wuxiaworld_novel_paid.html"))
    urls.clear()
    assert await wuxiaworld.WuxiaworldSource(client(h)).fetch_sample_chapters(paid, 3) == []
    assert not any("chapter" in u for u in urls), urls  # novel page only: status came from metadata

    # status cannot be established (no pricingInfo on the first chapter, novel not free) -> skip, no chapter request
    st = wuxiaworld.query_state(fx("wuxiaworld_novel_paid.html"))
    for q in st["queries"]:
        if q["queryKey"][0] == "novel":
            q["state"]["data"]["item"]["chapterInfo"]["firstChapter"].pop("pricingInfo", None)
    html = "<script>window.__REACT_QUERY_STATE__ = " + json.dumps(st) + ";</script>"
    routes = dict(ROUTES_WW)
    unknown = lambda r: httpx.Response(200, text=html) if r.url.path == "/novel/coiling-dragon" else router(routes, urls)(r)
    urls.clear()
    assert await wuxiaworld.WuxiaworldSource(client(unknown)).fetch_sample_chapters(paid, 3) == []
    assert not any("chapter" in u for u in urls)
    assert wuxiaworld.chapter_listed_free({"pricingInfo": {"isFree": True}}, False)
    assert not wuxiaworld.chapter_listed_free({"pricingInfo": {"isFree": True}, "karmaInfo": {"isKarmaRequired": True}}, True)


def test_wuxiaworld_never_free_when_karma_required():
    st = wuxiaworld.query_state(fx("wuxiaworld_chapter1.html"))
    st["queries"][0]["state"]["data"]["item"]["karmaInfo"]["isKarmaRequired"] = True
    html = "<script>window.__REACT_QUERY_STATE__ = " + json.dumps(st) + ";</script>"
    assert wuxiaworld.parse_chapter(html, novel_free=True) == (None, None)


# ---------------------------------------------------------------- alphapolis
def test_alphapolis_parse_listing():
    recs, last = alphapolis.parse_listing(fx("alphapolis_listing.html"))
    assert last == 5875 and len(recs) == 2
    r = recs[0]
    assert r.source_id == "165091864" and r.url == "https://www.alphapolis.co.jp/novel/52747292/165091864"
    assert r.title == "次期王太子妃の座はお返しいたします" and r.authors == ["茶２"] and r.language == "ja"
    assert r.status == "ongoing" and r.genres == ["恋愛"] and r.start_year == 2026
    assert r.tags[:2] == ["恋愛", "異世界"] and "婚約破棄" in r.tags and r.synopsis == "Placeholder synopsis text for the fixture."
    assert r.raw["author_id"] == "52747292" and r.raw["characters"] == 51388 and r.raw["favorites"] == 3764
    assert r.raw["_version"].startswith("2026.10.29")


async def test_alphapolis_paging_and_robots_delay():
    urls: list[str] = []
    batches = await collect(alphapolis.AlphapolisSource(client(router({"/novel/index": "alphapolis_listing.html"}, urls)), max_pages=2))
    assert urls == ["https://www.alphapolis.co.jp/novel/index?sort=24hpt", "https://www.alphapolis.co.jp/novel/index?sort=24hpt&page=2"]
    assert batches[0].checkpoint == {"page": 2} and batches[-1].done and not batches[0].done


async def test_alphapolis_unchanged_skipped():
    src = alphapolis.AlphapolisSource(client(router({"/novel/index": "alphapolis_listing.html"})), max_pages=1)
    recs, _ = alphapolis.parse_listing(fx("alphapolis_listing.html"))
    src.known_versions = {recs[0].source_id: recs[0].raw["_version"]}
    batch = (await collect(src))[0]
    assert [r.source_id for r in batch.records] == [recs[1].source_id]


async def test_alphapolis_samples_skip_rental_episodes():
    urls: list[str] = []
    toc = alphapolis.parse_toc(fx("alphapolis_novel.html"))
    assert len(toc) == 3 and len(alphapolis.free_episodes(toc)) == 2
    h = router({"/novel/52747292/165091864": "alphapolis_novel.html", "/episode/": "alphapolis_episode.html"}, urls)
    recs, _ = alphapolis.parse_listing(fx("alphapolis_listing.html"))
    texts = await alphapolis.AlphapolisSource(client(h)).fetch_sample_chapters(recs[0], 3)
    assert texts == ["Placeholder episode line one.\n\nPlaceholder episode line two."] * 2
    assert len([u for u in urls if "/episode/" in u]) == 2  # the third (rental) episode is never requested


# ---------------------------------------------------------------- munpia (partial / unverified)
def test_munpia_parse_and_missing():
    r = munpia.parse_detail(json.loads(fx("munpia_detail.json")), "1001", "2026-07-08")
    assert r.title == "예시 소설" and r.authors == ["예시작가"] and r.tags == ["회귀", "헌터"] and r.genres == ["현대판타지"]
    assert r.status == "ongoing" and r.chapter_count == 321 and r.start_year == 2021 and r.language == "ko"
    assert r.url == "https://www.munpia.com/novel/detail/1001" and r.raw["_version"] == "2026-07-08"
    assert munpia.parse_detail(json.loads(fx("munpia_missing.json")), "1") is None


async def test_munpia_catalog_skips_missing_and_unchanged():
    def h(req: httpx.Request):
        if req.url.path.endswith("sitemap_pl_serial.xml"):
            return httpx.Response(200, text=fx("munpia_sitemap.xml"))
        if req.url.path.endswith("/1001"):
            return httpx.Response(200, text=fx("munpia_detail.json"))
        return httpx.Response(400, text=fx("munpia_missing.json"))

    batches = await collect(munpia.MunpiaSource(client(h)))
    assert [r.source_id for b in batches for r in b.records] == ["1001"] and batches[-1].done
    src = munpia.MunpiaSource(client(h))
    src.known_versions = {"1001": "2026-07-08"}
    assert [r for b in await collect(src) for r in b.records] == []


# ---------------------------------------------------------------- blocked sites (hand-written fixtures)
def test_novelupdates_parse():
    stubs = novelupdates.parse_listing_page(fx("novelupdates_listing.html"))
    assert [s.source_id for s in stubs] == ["example-novel", "second-novel"] and stubs[0].genres == ["Fantasy", "Action"]
    r = novelupdates.parse_series_page(fx("novelupdates_series.html"), "example-novel")
    assert r.title == "Example Novel" and r.alt_titles == ["示例小说", "Shili Xiaoshuo", "Example Novel (Alt)"]
    assert r.authors == ["Wo Chi Xihongshi", "我吃西红柿"] and r.language == "zh" and r.start_year == 2013
    assert r.tags == ["Cultivation", "Strong to Stronger"] and r.genres == ["Fantasy", "Action"]
    assert (r.rating, r.rating_scale, r.rating_votes) == (4.5, 5.0, 1234) and r.status == "ongoing" and r.chapter_count == 528
    assert r.external_ids == {"novelupdates": "example-novel"}  # description-derived MAL link is descriptive only
    assert any("myanimelist.net/manga/12345" in u for u in r.related_urls)
    assert "https://translator.example/example-novel" in r.related_urls and "https://original.example/book/1" in r.related_urls
    assert r.raw["original_publisher"] == ["Example Publisher"] and r.synopsis.startswith("Placeholder description")


def test_royalroad_parse():
    stub = royalroad.parse_listing_page(fx("royalroad_listing.html"))[0]
    assert stub.source_id == "21220" and stub.status == "ongoing" and stub.tags == ["Fantasy", "Action"]
    assert stub.rating == 4.62 and stub.chapter_count == 100 and stub.url == "https://www.royalroad.com/fiction/21220/example-fiction"
    r = royalroad.parse_fiction_page(fx("royalroad_fiction.html"), stub)
    assert r.authors == ["Example Author"] and r.status == "completed" and r.tags == ["Fantasy", "Time Loop"]
    assert r.synopsis == "Placeholder long description." and r.chapter_count == 112 and r.language == "en"


def test_scribblehub_parse_and_chapter():
    stub = scribblehub.parse_listing_page(fx("scribblehub_listing.html"))[0]
    assert stub.source_id == "12345" and stub.genres == ["Fantasy"] and stub.tags == ["LitRPG"]
    r = scribblehub.parse_series_page(fx("scribblehub_series.html"), stub)
    assert r.authors == ["Example Author"] and r.status == "ongoing" and r.genres == ["Fantasy", "Adventure"]
    assert r.raw["first_chapter"].endswith("/chapter/1/")
    assert scribblehub.parse_chapter(fx("scribblehub_chapter.html")) == ("Placeholder chapter text.\n\nSecond paragraph.", None)


async def test_scribblehub_samples():
    h = router({"/series/12345/": "scribblehub_series.html", "/chapter/1/": "scribblehub_chapter.html"})
    src = scribblehub.ScribbleHubSource(client(h))
    rec = scribblehub.parse_series_page(fx("scribblehub_series.html"), scribblehub.parse_listing_page(fx("scribblehub_listing.html"))[0])
    assert len(await src.fetch_sample_chapters(rec, 3)) == 1  # chapter page has no next link


def test_webnovel_parse_and_free_chapters():
    stubs = webnovel.parse_listing_page(fx("webnovel_listing.html"))
    assert [s.source_id for s in stubs] == ["11111111111", "22222222222"] and stubs[0].title == "Example Novel"
    r = webnovel.parse_book_page(fx("webnovel_book.html"), stubs[0])
    assert r.authors == ["Example Author"] and r.rating == 4.6 and r.rating_votes == 1500 and r.chapter_count == 350
    assert r.status == "ongoing" and r.genres == ["Fantasy"] and r.tags == ["System", "Reincarnation"] and r.start_year == 2019
    links = webnovel.free_chapter_links(fx("webnovel_catalog.html"))
    assert len(links) == 2 and all("_1000000" in link for link in links)  # chapter 3 is locked
    assert webnovel.parse_chapter(fx("webnovel_chapter.html")) == "Placeholder chapter text."
    assert webnovel.parse_chapter(fx("webnovel_chapter_locked.html")) is None


async def test_listing_detail_base_skips_unchanged_and_resumes():
    urls: list[str] = []
    h = router({"/series-ranking/": "novelupdates_listing.html", "/series-finder/": "novelupdates_listing.html",
                "/series/example-novel/": "novelupdates_series.html"}, urls)
    src = novelupdates.NovelUpdatesSource(client(h), max_pages=1)
    batches = await collect(src)
    assert [b.checkpoint for b in batches] == [{"list_index": 1, "page": 1}, {"list_index": 2, "page": 1}] and batches[-1].done
    full = next(r for b in batches for r in b.records if r.source_id == "example-novel")
    assert full.tags and full.raw["_version"]
    urls.clear()
    src2 = novelupdates.NovelUpdatesSource(client(h), max_pages=1)
    src2.known_versions = {"example-novel": full.raw["_version"]}
    # the listing stub version differs from the detail version, so listing-level skip needs the stub version:
    stub_version = novelupdates.parse_listing_page(fx("novelupdates_listing.html"))[0].raw["_version"]
    src2.known_versions = {"example-novel": stub_version}
    await collect(src2, {"list_index": 1, "page": 1})
    assert not any(u.endswith("/series/example-novel/") for u in urls)


# ---------------------------------------------------------------- syosetu samples
async def test_syosetu_sample_chapters():
    urls: list[str] = []
    h = router({"/n2710db/1/": "syosetu_chapter.html", "/n2710db/2/": "syosetu_chapter.html"}, urls)
    assert parse_chapter(fx("syosetu_chapter.html")) == "Placeholder line one.\n\nPlaceholder line two."
    src = SyosetuSource(client(lambda r: httpx.Response(500)), html_http=client(h))
    from readerserver.sources.base import SourceRecordData

    texts = await src.fetch_sample_chapters(SourceRecordData(source="syosetu", source_id="n2710db", title="x"), 3)
    assert len(texts) == 2 and urls[0] == "https://ncode.syosetu.com/n2710db/1/"  # chapter 3 -> 404 -> stop
    assert await SyosetuSource(client(h)).fetch_sample_chapters(SourceRecordData(source="syosetu", source_id="n1", title="x"), 3) == []


# ---------------------------------------------------------------- blocking / robots / registry
def challenge(req: httpx.Request):
    return httpx.Response(403, text="<title>Just a moment...</title>", headers={"cf-mitigated": "challenge", "server": "cloudflare"})


@pytest.mark.parametrize("name", ["webnovel", "novelupdates", "kakuyomu", "royalroad", "scribblehub", "wuxiaworld", "tapas", "alphapolis", "munpia"])
async def test_blocked_propagates_for_every_source(name):
    src = HTML_SOURCES[name](Settings(crawler_contact="t@example.com"))
    await src.http.aclose()
    src.http = client(challenge)
    with pytest.raises(BlockedError):
        await collect(src)


async def test_robots_disallow_propagates():
    def h(req: httpx.Request):
        if req.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nDisallow: /rankings/")
        return httpx.Response(200, text=fx("kakuyomu_ranking.html"))

    with pytest.raises(RobotsDisallowed):
        await collect(kakuyomu.KakuyomuSource(client(h, robots=True)))


def test_registry_polite_defaults():
    settings = Settings(crawler_contact="t@example.com")
    assert {"webnovel", "novelupdates", "kakuyomu", "royalroad", "scribblehub", "wuxiaworld", "tapas", "alphapolis", "munpia"} <= set(source_names())
    for name in HTML_SOURCES:
        src = build_source(name, settings)
        assert src.http.respect_robots is True and src.http.min_delay >= 3.0 and "ReaderAppCrawler" in src.http.user_agent
    syo = build_source("syosetu", settings)
    assert syo.html_http.respect_robots is True and syo.html_http.min_delay >= 3.0 and syo.http.respect_robots is False


def test_scribblehub_locked_first_chapter_link_is_skipped():
    from readerserver.sources.htmlutil import soup

    ok = soup('<a class="read_buttons" href="https://www.scribblehub.com/read/1-x/chapter/2/">Read</a>')
    locked = soup('<a class="read_buttons" href="https://www.scribblehub.com/read/1-x/chapter/2/"><i class="fa fa-lock"></i>Read</a>')
    assert scribblehub.first_chapter_url(ok) and scribblehub.first_chapter_url(locked) is None
