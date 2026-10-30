"""Alphapolis (alphapolis.co.jp): JP originals. Metadata + first free chapters. robots.txt: Crawl-delay is only declared
for bingbot (our `*` group has no delay), but the task brief asks for >= 3 s and we use >= 3 s everywhere.

Catalog = the public listing `/novel/index?sort=24hpt&page=N` (40 works/page, ~235k works): each `section.c-content` has
title, author, abstract, category, serial status, tags, char count and dates, so no detail request is needed. Chapters:
the novel page embeds its table of contents as JSON (`chapterEpisodes[].episodes[]` with `isPublic` and `rental`); only
public non-rental episodes are fetched, body is `#novelBody`. Verified live (Oct 2026)."""
import json
import re
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData
from .htmlutil import block_text, get_html, soup, text_of, to_int, uniq, year_of

BASE = "https://www.alphapolis.co.jp"
STATUS = {"連載中": "ongoing", "完結": "completed", "休載中": "hiatus"}


def listing_url(page: int) -> str:
    return f"{BASE}/novel/index?sort=24hpt&page={page}" if page > 1 else f"{BASE}/novel/index?sort=24hpt"


def parse_listing(html: str) -> tuple[list[SourceRecordData], int | None]:
    """(records, last page number)."""
    doc = soup(html)
    last = doc.select_one(".c-paginator__nav .is-last a")
    last_page = to_int(last["href"].split("page=")[-1]) if last and "page=" in last.get("href", "") else None
    out = []
    for sec in doc.select("section.c-content"):
        link = sec.select_one(".c-content__title a")
        m = re.search(r"/novel/(\d+)/(\d+)", link["href"]) if link and link.get("href") else None
        if not m:
            continue
        uid, nid = m.groups()
        attrs = [text_of(t) for t in sec.select(".c-content__meta .c-attribute-tags .c-attribute-tag")]
        status = next((STATUS[a] for a in attrs if a in STATUS), None)
        cover = sec.select_one(".c-content-cover img")
        cover_url = cover.get("src") if cover and "no_image" not in cover.get("src", "") else None
        dates = {}
        stats = {}
        for at in sec.select(".c-content__attribute"):
            label, val = text_of(at), text_of(at.select_one(".c-content__attribute-value, .c-point__value"))
            for key, tag in (("updated", "最終更新日"), ("registered", "登録日"), ("chars", "文字数")):
                if tag in label:
                    (dates if key != "chars" else stats)[key] = val
        fav = None
        j = sec.select_one(".app-favorite-button-text script")
        if j and j.string:
            try:
                fav = to_int(json.loads(j.string).get("favoriteTotal"))
            except ValueError:
                pass
        author = sec.select_one(".c-content__author-publink a")
        out.append(SourceRecordData(
            source="alphapolis",
            source_id=nid,
            url=f"{BASE}/novel/{uid}/{nid}",
            title=text_of(link),
            authors=[text_of(author)] if author else [],
            synopsis=block_text(sec.select_one(".c-content__abstract")),
            tags=uniq([text_of(t) for t in sec.select(".c-tags .c-tag")]),
            genres=[attrs[0]] if attrs and attrs[0] not in STATUS else [],
            status=status,
            language="ja",
            start_year=year_of(dates.get("registered", "").replace(".", "-")),
            cover_url=cover_url,
            external_ids={"alphapolis": nid},
            raw={"author_id": uid, "attributes": attrs, "favorites": fav, "characters": to_int(stats.get("chars")),
                 "updated": dates.get("updated"), "_version": f"{dates.get('updated')}|{stats.get('chars')}"},
        ))
    return out, last_page


def parse_toc(html: str) -> list[dict[str, Any]]:
    """Episodes from the page-embedded table-of-contents JSON, in reading order."""
    for s in soup(html).find_all("script", attrs={"type": "application/json"}):
        raw = s.string or ""
        if '"chapterEpisodes"' not in raw:
            continue
        try:
            data = json.loads(raw)
        except ValueError:
            continue
        found = _find_key(data, "chapterEpisodes")
        return [e for ch in (found or []) for e in (ch.get("episodes") or [])]
    return []


def _find_key(o: Any, key: str) -> Any:
    if isinstance(o, dict):
        if key in o:
            return o[key]
        for v in o.values():
            r = _find_key(v, key)
            if r is not None:
                return r
    elif isinstance(o, list):
        for v in o:
            r = _find_key(v, key)
            if r is not None:
                return r
    return None


def free_episodes(toc: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [e for e in toc if e.get("isPublic") and not e.get("rental") and e.get("url")]


def parse_episode(html: str) -> str | None:
    return block_text(soup(html).select_one("#novelBody"))


class AlphapolisSource(Source):
    name = "alphapolis"
    min_delay = 3.0

    def __init__(self, http: PoliteClient, max_pages: int = 100):
        self.http = http
        self.max_pages = max_pages

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        page = (checkpoint or {}).get("page", 1)
        while True:
            html = await get_html(self.http, listing_url(page))
            recs, last = parse_listing(html) if html else ([], None)
            done = not recs or page >= self.max_pages or (last is not None and page >= last)
            recs = [r for r in recs if self.known_versions.get(r.source_id) != r.raw["_version"]]  # type: ignore[attr-defined]
            yield CatalogBatch(recs, {"page": page + 1}, done=done)
            if done:
                return
            page += 1

    async def fetch_sample_chapters(self, record: SourceRecordData, n: int) -> list[str]:
        if not record.url:
            return []
        html = await get_html(self.http, record.url)
        out: list[str] = []
        for ep in free_episodes(parse_toc(html or ""))[: min(n, 3)]:
            page = await get_html(self.http, ep["url"] if ep["url"].startswith("http") else BASE + ep["url"])
            text = parse_episode(page) if page else None
            if text:
                out.append(text)
        return out
