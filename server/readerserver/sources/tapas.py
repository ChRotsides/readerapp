"""Tapas (tapas.io): NOVELS only, metadata only. `/search` is disallowed by robots.txt and listings are client-rendered,
so the catalog is enumerated from the public novel sitemap (`/sitemap-novel.xml`, ~16k series; its `lastmod` is the
version token) and each series is read from its server-rendered `/series/{slug}/info` page. Verified live (Oct 2026).
Note the host is `tapas.io` (www.tapas.io does not resolve)."""
import re
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from ..text import guess_language_from_script
from .base import CatalogBatch, Source, SourceRecordData
from .htmlutil import block_text, get_html, meta, parse_sitemap, soup, text_of, to_int, uniq

BASE = "https://tapas.io"
SITEMAP = f"{BASE}/sitemap-novel.xml"


def parse_series(html: str, slug: str, lastmod: str = "") -> SourceRecordData | None:
    doc = soup(html)
    if "Novels" not in (meta(doc, "og:title") or "") and "Novel" not in text_of(doc.select_one(".info__right .stats")):
        return None  # comic (or unknown page): out of scope
    title_el = doc.select_one(".info__right a.title") or doc.select_one(".title-wrapper .title")
    if not title_el:
        return None
    sid = None
    m = re.search(r"series_id=(\d+)", html) or re.search(r"\bid:\s*(\d+),\s*type:\s*'series_id'", html)
    if m:
        sid = m.group(1)
    stats = {}
    for a in doc.select(".info__right .stats .stats__row"):
        t = a.get("data-title") or text_of(a)
        for key in ("views", "subscribers", "likes"):
            if key in t:
                stats[key] = to_int(t)
    tags = [re.sub(r"_", " ", text_of(a).lstrip("#")) for a in doc.select(".tags .tags__item")]
    genres = uniq([text_of(a) for a in doc.select(".detail-row__body--genre a.genre-btn")] or [text_of(a) for a in doc.select(".info-detail a.genre-btn")])
    creator = doc.select_one(".detail-row__body--creator .creator-info .name") or doc.select_one(".creator .creator__item")
    desc = block_text(doc.select_one(".detail-row__body--desc .description__body") or doc.select_one(".description__body"))
    colophon = text_of(doc.select_one(".colophon"))
    cm = re.search(r"©\s*(\d{4})", colophon)
    schedule = text_of(doc.select_one(".schedule"))
    status = "ongoing" if "Updates every" in schedule else ("completed" if re.search(r"\bCompleted\b", text_of(doc.select_one(".info--bottom"))) else None)
    cover = doc.select_one(".info--bottom .thumb img")
    ep = to_int(text_of(doc.select_one(".episode-cnt")))
    return SourceRecordData(
        source="tapas",
        source_id=slug,
        url=f"{BASE}/series/{slug}",
        title=text_of(title_el),
        authors=[text_of(creator)] if creator else [],
        synopsis=desc,
        tags=uniq(tags),
        genres=genres,
        status=status,
        chapter_count=ep,
        language=guess_language_from_script(desc or ""),  # original language is not published: only non-Latin scripts are detected, otherwise unknown
        start_year=int(cm.group(1)) if cm else None,
        cover_url=(cover.get("src") if cover else None) or meta(doc, "og:image"),
        external_ids={"tapas": slug, **({"tapas_series_id": sid} if sid else {})},
        raw={"stats": stats, "_version": lastmod or str(ep)},
    )


class TapasSource(Source):
    name = "tapas"
    min_delay = 3.0

    def __init__(self, http: PoliteClient, batch_size: int = 20):
        self.http = http
        self.batch_size = batch_size

    async def sitemap(self) -> list[tuple[str, str]]:
        xml = await get_html(self.http, SITEMAP)
        return [(u, lm) for u, lm in parse_sitemap(xml or "<urlset/>") if "/series/" in u]

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        entries = await self.sitemap()
        i = (checkpoint or {}).get("index", 0)
        while i < len(entries):
            recs: list[SourceRecordData] = []
            end = min(i + self.batch_size, len(entries))
            for url, lastmod in entries[i:end]:
                slug = url.rstrip("/").rsplit("/", 1)[-1]
                if lastmod and self.known_versions.get(slug) == lastmod:  # type: ignore[attr-defined]
                    continue
                html = await get_html(self.http, f"{url}/info")
                rec = parse_series(html, slug, lastmod) if html else None
                if rec:
                    recs.append(rec)
            i = end
            yield CatalogBatch(recs, {"index": i}, done=i >= len(entries))

    async def fetch_work(self, source_id: str) -> SourceRecordData | None:
        html = await get_html(self.http, f"{BASE}/series/{source_id}/info")
        return parse_series(html, source_id) if html else None
