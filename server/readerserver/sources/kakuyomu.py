"""Kakuyomu (kakuyomu.jp): JP originals, metadata only (robots disallows /works/*/episodes/*/read, so we never read episodes).

Ranking pages (`/rankings/{genre}/entire?work_variation=long&page=N`) are Next.js pages whose `__NEXT_DATA__` carries an
Apollo cache with full `Work` objects (title, introduction, tag labels, serial status, episode count, author), so the
catalog needs no per-work detail requests. Verified live (Oct 2026)."""
import json
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData
from .htmlutil import get_html, soup, year_of

GENRES = ["all", "fantasy", "action", "sf", "love_story", "romance", "drama", "horror", "mystery", "history", "nonfiction", "criticism", "others"]
GENRE_NAMES = {
    "FANTASY": "Fantasy", "ACTION": "Action", "SF": "Sci-fi", "LOVE_STORY": "Romance (Love story)", "ROMANCE": "Romance (Rom-com)",
    "DRAMA": "Drama", "HORROR": "Horror", "MYSTERY": "Mystery", "HISTORY": "Historical", "NONFICTION": "Non-fiction",
    "CRITICISM": "Criticism", "OTHERS": "Other",
}
STATUS = {"RUNNING": "ongoing", "COMPLETED": "completed"}


def next_data(html: str) -> dict[str, Any]:
    el = soup(html).find("script", id="__NEXT_DATA__")
    return json.loads(el.string) if el and el.string else {}


def parse_ranking(html: str) -> list[SourceRecordData]:
    """Records of the ranked works on one ranking page, in rank order."""
    cache = next_data(html).get("props", {}).get("pageProps", {}).get("__APOLLO_STATE__", {})
    root = cache.get("ROOT_QUERY", {})
    nodes: list[dict] = []
    for key, val in root.items():
        if key.startswith("rankedWorks(") and isinstance(val, dict):
            nodes = val.get("nodes", [])
            break
    out = []
    for ref in nodes:
        w = cache.get(ref.get("__ref", ""))
        if w:
            out.append(parse_work(w, cache))
    return out


def parse_work(w: dict[str, Any], cache: dict[str, Any]) -> SourceRecordData:
    wid = str(w["id"])
    author = cache.get((w.get("author") or {}).get("__ref", ""), {})
    names = [author.get("activityName"), w.get("alternateAuthorName")]
    alt = [w["alternateTitle"]] if w.get("alternateTitle") else []
    return SourceRecordData(
        source="kakuyomu",
        source_id=wid,
        url=f"https://kakuyomu.jp/works/{wid}",
        title=w["title"],
        alt_titles=alt,
        authors=[n for i, n in enumerate(names) if n and n not in names[:i]],
        synopsis=(w.get("introduction") or "").strip() or None,
        tags=list(w.get("tagLabels") or []),
        genres=[GENRE_NAMES.get(w.get("genre"), w.get("genre"))] if w.get("genre") else [],
        status=STATUS.get(w.get("serialStatus")),
        chapter_count=w.get("publicEpisodeCount"),
        language="ja",
        start_year=year_of(w.get("publishedAt")),
        external_ids={"kakuyomu": wid},
        raw={
            "catchphrase": w.get("catchphrase"), "review_points": w.get("totalReviewPoint"),
            "characters": w.get("totalCharacterCount"), "published_at": w.get("publishedAt"),
            "last_episode_at": w.get("lastEpisodePublishedAt"),
            "_version": f"{w.get('lastEpisodePublishedAt')}|{w.get('publicEpisodeCount')}",
        },
    )


class KakuyomuSource(Source):
    name = "kakuyomu"
    min_delay = 3.0

    def __init__(self, http: PoliteClient, max_pages: int = 3, genres: list[str] | None = None):
        self.http = http
        self.max_pages = max_pages
        self.genres = genres or GENRES

    def url(self, genre: str, page: int) -> str:
        return f"https://kakuyomu.jp/rankings/{genre}/entire?work_variation=long&page={page}"

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        cp = checkpoint or {}
        gi, page = cp.get("genre_index", 0), cp.get("page", 1)
        while gi < len(self.genres):
            html = await get_html(self.http, self.url(self.genres[gi], page))
            recs = parse_ranking(html) if html else []
            if not recs or page >= self.max_pages:
                gi, page = gi + 1, 1
            else:
                page += 1
            yield CatalogBatch(recs, {"genre_index": gi, "page": page}, done=gi >= len(self.genres))

    async def fetch_work(self, source_id: str) -> SourceRecordData | None:
        # Work pages (`/works/{id}`) are allowed, but the ranking cache already holds everything we use; refresh via catalog.
        return None
