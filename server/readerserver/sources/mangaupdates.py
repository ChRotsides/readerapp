"""MangaUpdates API v1 (https://api.mangaupdates.com/). POST /v1/series/search {type:["Novel"]} lists novels;
GET /v1/series/{id} gives associated (alias) titles, authors, status. Detail fetch is skipped for unchanged items."""
import re
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData
from .util import extract_links, guess_language_from_script, strip_html

BASE = "https://api.mangaupdates.com/v1"


def _status(s: str | None) -> str | None:
    s = (s or "").lower()
    if "complete" in s:
        return "completed"
    if "hiatus" in s:
        return "hiatus"
    if "cancel" in s or "discontinued" in s:
        return "cancelled"
    if "ongoing" in s:
        return "ongoing"
    return None


def parse_series(s: dict[str, Any]) -> SourceRecordData:
    title = re.sub(r"\s*\((?:novel)\)\s*$", "", s["title"], flags=re.I)  # "X (Novel)" -> "X" (alias keeps original)
    alts = [a["title"] for a in s.get("associated") or []]
    if title != s["title"]:
        alts.append(s["title"])
    desc = s.get("description")
    _, urls = extract_links(desc)  # links in free-text descriptions are descriptive (sequels, adaptations), not identity
    ids = {"mangaupdates": str(s["series_id"])}
    chapters = None
    m = re.search(r"(\d[\d,]*)\s*Chapters?", s.get("status") or "")
    if m:
        chapters = int(m.group(1).replace(",", ""))
    authors = [a["name"] for a in s.get("authors") or [] if a.get("type") in (None, "Author")]
    cats = sorted(s.get("categories") or [], key=lambda c: -(c.get("votes") or 0))
    lang = next((g for g in (guess_language_from_script(a) for a in alts) if g), None)
    year = s.get("year")
    rating = s.get("bayesian_rating")
    return SourceRecordData(
        source="mangaupdates",
        source_id=str(s["series_id"]),
        url=s.get("url"),
        title=title,
        alt_titles=list(dict.fromkeys(a for a in alts if a != title)),
        authors=authors,
        synopsis=strip_html(re.sub(r"\*\*Original Novel:\*\*.*", "", desc or "", flags=re.S)) if desc else None,
        tags=[c["category"] for c in cats if (c.get("votes") or 0) >= 3][:20],
        genres=[g["genre"] for g in s.get("genres") or []],
        status=_status(s.get("status")),
        chapter_count=chapters,
        rating=float(rating) if rating else None,
        rating_scale=10.0 if rating else None,
        rating_votes=s.get("rating_votes"),
        language=lang,
        start_year=int(year) if year and str(year).isdigit() else None,
        cover_url=((s.get("image") or {}).get("url") or {}).get("original"),
        external_ids=ids,
        related_urls=urls,
        raw={**s, "_version": str((s.get("last_updated") or {}).get("timestamp", ""))},
    )


class MangaUpdatesSource(Source):
    name = "mangaupdates"
    respect_robots = False

    def __init__(self, http: PoliteClient, per_page: int = 100):
        self.http = http
        self.per_page = per_page

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        page = (checkpoint or {}).get("page", 1)
        while True:
            resp = await self.http.post(
                f"{BASE}/series/search",
                json={"type": ["Novel"], "page": page, "perpage": self.per_page, "orderby": "date_added"},
            )
            resp.raise_for_status()
            data = resp.json()
            hits = [h["record"] for h in data["results"]]
            recs: list[SourceRecordData] = []
            for rec in hits:
                version = str((rec.get("last_updated") or {}).get("timestamp", ""))
                sid = str(rec["series_id"])
                if version and self.known_versions.get(sid) == version:  # type: ignore[attr-defined]
                    continue
                d = await self.http.get(f"{BASE}/series/{sid}")
                d.raise_for_status()
                recs.append(parse_series(d.json()))
            total_pages = -(-data["total_hits"] // self.per_page)
            page += 1
            done = page > total_pages or not hits
            yield CatalogBatch(recs, {"page": page}, done=done)
            if done:
                return

    async def fetch_work(self, source_id: str) -> SourceRecordData | None:
        d = await self.http.get(f"{BASE}/series/{source_id}")
        return parse_series(d.json()) if d.status_code == 200 else None
