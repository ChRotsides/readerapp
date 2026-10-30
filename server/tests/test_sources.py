import json

import httpx
import pytest

from readerserver.crawl.http import PoliteClient
from readerserver.sources.anilist import AniListSource, parse_media
from readerserver.sources.mal import MalSource, parse_node
from readerserver.sources.mangaupdates import MangaUpdatesSource, parse_series
from readerserver.sources.syosetu import SyosetuSource, parse_item
from readerserver.sources.util import extract_links, strip_html

from .conftest import FIXTURES


def load(name):
    return json.loads((FIXTURES / name).read_text())


def test_syosetu_parse():
    data = load("syosetu_list.json")
    rec = parse_item(data[1])
    assert rec.source == "syosetu" and rec.source_id == "n2710db"
    assert rec.url == "https://ncode.syosetu.com/n2710db/"
    assert rec.language == "ja" and rec.status == "ongoing" and rec.start_year == 2016
    assert rec.chapter_count == 662 and "異世界" in rec.tags and rec.authors
    assert rec.external_ids == {"syosetu": "n2710db"}
    assert parse_item({"noveltype": 2, "ncode": "N1"}) is None  # short stories skipped


def test_anilist_parse():
    media = load("anilist_page.json")["data"]["Page"]["media"]
    rec = parse_media(media[0])
    assert rec.source == "anilist" and rec.source_id == "30120"
    assert rec.external_ids["mal"] == "120" and rec.external_ids["anilist"] == "30120"
    assert rec.language == "ja" and rec.rating == 75.0 and rec.rating_scale == 100.0
    assert "Toshihiko Tsukiji" in rec.authors and "築地俊彦" in rec.authors
    assert "まぶらほ" in rec.alt_titles and rec.start_year == 2000
    assert "(Source" not in rec.synopsis and "<br>" not in rec.synopsis
    assert "Magic" in rec.tags


def test_mangaupdates_parse():
    rec = parse_series(load("mu_series.json"))
    assert rec.title == "Damn Reincarnation" and "Damn Reincarnation (Novel)" in rec.alt_titles
    assert "빌어먹을 환생 (소설)" in rec.alt_titles and rec.language == "ko"
    assert rec.status == "completed" and rec.chapter_count == 625
    assert rec.rating_scale == 10.0 and rec.rating_votes == 4 and rec.start_year == 2020
    assert rec.authors == ["Wooden Horse"]
    ids = rec.external_ids
    assert ids["mangaupdates"] == "73074336025"
    assert any("munpia" in u for u in rec.related_urls)  # sibling-site links kept for hard matching
    assert "Original Novel" not in (rec.synopsis or "")


def test_mal_parse():
    nodes = load("mal_ranking.json")["data"]
    rec = parse_node(nodes[0]["node"])
    assert rec.source_id == "96792" and rec.rating == 8.1 and rec.rating_votes == 12345
    assert rec.status == "ongoing" and rec.language == "ja" and rec.start_year == 2009
    assert "Reki Kawahara" in rec.authors and "Kawahara Reki" in rec.authors
    assert parse_node(nodes[1]["node"]).title == "Minimal Novel"


def test_extract_links_and_html():
    ids, urls = extract_links("see https://myanimelist.net/manga/42/x and https://ncode.syosetu.com/N1234AB/.")
    assert ids == {"mal": "42", "syosetu": "n1234ab"} and len(urls) == 2
    assert strip_html("a<br>b &amp; c") == "a\nb & c"


def client_for(handler, **kw):
    return PoliteClient("TestCrawler/1", min_delay=0, respect_robots=False, transport=httpx.MockTransport(handler), **kw)


async def test_syosetu_paging_and_checkpoint():
    item = load("syosetu_list.json")[1]
    calls = []

    def handler(req: httpx.Request):
        calls.append(dict(req.url.params))
        return httpx.Response(200, json=[{"allcount": 1}, item])

    src = SyosetuSource(client_for(handler), per_genre_limit=2, page_size=1)
    batches = [b async for b in src.iter_catalog(None)]
    assert calls[0]["biggenre"] == "1" and calls[0]["type"] == "re" and calls[0]["order"] == "hyoka"
    assert batches[0].checkpoint == {"genre_index": 0, "st": 2}
    assert batches[-1].done and len(batches) >= 6
    resumed = [b async for b in SyosetuSource(client_for(handler), per_genre_limit=2, page_size=1).iter_catalog({"genre_index": 5, "st": 1})]
    assert len(resumed) == 2 and resumed[-1].done


async def test_anilist_paging():
    page = load("anilist_page.json")
    pages = []

    def handler(req: httpx.Request):
        body = json.loads(req.content)
        pages.append(body["variables"]["page"])
        data = json.loads(json.dumps(page))
        data["data"]["Page"]["pageInfo"]["hasNextPage"] = body["variables"]["page"] < 2
        return httpx.Response(200, json=data, headers={"x-ratelimit-remaining": "28"})

    src = AniListSource(client_for(handler))
    batches = [b async for b in src.iter_catalog(None)]
    assert pages == [1, 2] and batches[-1].done and len(batches[0].records) == 3
    assert batches[0].checkpoint == {"page": 2}


async def test_mangaupdates_skips_unchanged_detail():
    search, series = load("mu_search.json"), load("mu_series.json")
    detail_calls = []

    def handler(req: httpx.Request):
        if req.method == "POST":
            return httpx.Response(200, json={**search, "total_hits": 2})
        detail_calls.append(req.url.path)
        return httpx.Response(200, json=series)

    src = MangaUpdatesSource(client_for(handler))
    unchanged = search["results"][0]["record"]
    src.known_versions = {str(unchanged["series_id"]): str(unchanged["last_updated"]["timestamp"])}
    batches = [b async for b in src.iter_catalog(None)]
    assert len(detail_calls) == 1  # first record unchanged -> no detail request
    assert batches[-1].done


async def test_mal_skipped_without_client_id_and_header_when_set():
    assert not MalSource(client_for(lambda r: httpx.Response(500)), "").available()
    seen = {}

    def handler(req: httpx.Request):
        seen["h"] = req.headers.get("x-mal-client-id")
        seen.setdefault("types", []).append(req.url.params["ranking_type"])
        return httpx.Response(200, json={"data": load("mal_ranking.json")["data"], "paging": {}})

    src = MalSource(client_for(handler), "cid")
    assert src.available()
    batches = [b async for b in src.iter_catalog(None)]
    assert seen["h"] == "cid" and seen["types"] == ["lightnovels", "novels"] and batches[-1].done
