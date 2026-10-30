"""Munpia (munpia.com): KR originals. Catalog/metadata only: robots.txt disallows /novel/viewer/, so chapters are never read.

PARTIAL / UNVERIFIED. The site is a client-rendered SPA (the HTML of /novel/detail/{id} is an empty shell), so metadata
comes from the JSON endpoint the page itself calls, `GET /api/v1/pc/novel-detail/{id}` (robots.txt: `Allow: /`).
Enumeration uses the robots-advertised `/sitemap/sitemap_pl_serial.xml` (~10.7k works, lastmod = version token).
During development every probed id returned `400 {"code":"A002_12002|3","message":"작품이 없거나 비공개 상태입니다."}`
(work missing/private) and the response schema of a successful call could not be confirmed within the live-request
budget, so `parse_detail` accepts several plausible key spellings and the test fixture is hand-written from them.
Records that cannot be parsed are skipped, never fatal. Verify against a live id before enabling this source."""
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData
from .htmlutil import get_html, parse_sitemap, split_author, to_int, uniq, year_of

BASE = "https://www.munpia.com"
SITEMAP = f"{BASE}/sitemap/sitemap_pl_serial.xml"


def pick(d: dict[str, Any], *keys: str) -> Any:
    for k in keys:
        if d.get(k) not in (None, "", []):
            return d[k]
    return None


def names(v: Any) -> list[str]:
    if isinstance(v, str):
        return [v]
    if isinstance(v, dict):
        v = pick(v, "name", "nickname", "nick", "title", "label")
        return [v] if isinstance(v, str) else []
    if isinstance(v, list):
        return [n for x in v for n in names(x)]
    return []


def parse_detail(payload: dict[str, Any], novel_id: str, lastmod: str = "") -> SourceRecordData | None:
    res = payload.get("result") if isinstance(payload, dict) else None
    if not isinstance(res, dict):
        return None
    info = res.get("novel") if isinstance(res.get("novel"), dict) else res
    title = pick(info, "title", "novelName", "name", "subject")
    if not isinstance(title, str):
        return None
    done = pick(info, "isComplete", "isCompleted", "complete", "completed", "isFinished")
    status_txt = str(pick(info, "status", "serialStatus", "serialize") or "").lower()
    status = "completed" if done is True or status_txt in ("complete", "completed", "완결") else (
        "ongoing" if done is False or status_txt in ("ongoing", "serial", "연재", "연재중") else None)
    syn = pick(info, "synopsis", "intro", "introduction", "description", "summary", "story")
    created = pick(info, "regDate", "createdAt", "registeredAt", "startDate", "openDate")
    authors = [a for n in names(pick(info, "author", "authorName", "writer", "nickname")) for a in split_author(n)]
    rating = pick(info, "ratingAverage", "rating", "score", "avgScore")
    return SourceRecordData(
        source="munpia",
        source_id=str(novel_id),
        url=f"{BASE}/novel/detail/{novel_id}",
        title=title.strip(),
        alt_titles=[],
        authors=uniq(authors),
        synopsis=syn.strip() if isinstance(syn, str) and syn.strip() else None,
        tags=uniq(names(pick(info, "tags", "tagList", "hashtags"))),
        genres=uniq(names(pick(info, "genre", "genres", "genreName", "category"))),
        status=status,
        chapter_count=to_int(str(pick(info, "entryCount", "episodeCount", "totalEntries", "chapterCount", "articleCount") or "")),
        rating=float(rating) if isinstance(rating, (int, float)) else None,
        rating_scale=10.0 if isinstance(rating, (int, float)) else None,
        language="ko",
        start_year=year_of(str(created)) if created else None,
        cover_url=pick(info, "coverImage", "coverUrl", "cover", "thumbnail", "imageUrl"),
        external_ids={"munpia": str(novel_id)},
        raw={"_version": lastmod or str(pick(info, "lastUpdated", "updatedAt", "lastEntryDate") or "")},
    )


class MunpiaSource(Source):
    name = "munpia"
    min_delay = 3.0

    def __init__(self, http: PoliteClient, batch_size: int = 20):
        self.http = http
        self.batch_size = batch_size

    async def _detail(self, novel_id: str, lastmod: str = "") -> SourceRecordData | None:
        resp = await self.http.get(f"{BASE}/api/v1/pc/novel-detail/{novel_id}")
        if resp.status_code in (400, 404):  # {"code": "A002_1200x", "message": "work missing/private"}
            return None
        resp.raise_for_status()
        try:
            return parse_detail(resp.json(), novel_id, lastmod)
        except ValueError:
            return None

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        xml = await get_html(self.http, SITEMAP)
        entries = [(u, lm) for u, lm in parse_sitemap(xml or "<urlset/>") if "/novel/detail/" in u]
        i = (checkpoint or {}).get("index", 0)
        while i < len(entries):
            recs = []
            end = min(i + self.batch_size, len(entries))
            for url, lastmod in entries[i:end]:
                nid = url.rstrip("/").rsplit("/", 1)[-1]
                if lastmod and self.known_versions.get(nid) == lastmod:  # type: ignore[attr-defined]
                    continue
                rec = await self._detail(nid, lastmod)
                if rec:
                    recs.append(rec)
            i = end
            yield CatalogBatch(recs, {"index": i}, done=i >= len(entries))

    async def fetch_work(self, source_id: str) -> SourceRecordData | None:
        return await self._detail(source_id)
