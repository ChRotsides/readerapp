"""Wuxiaworld (wuxiaworld.com): licensed CN/KR translations. Metadata + first FREE chapters.

Catalog = `/sitemap-novels.xml` (~250 novels, `lastmod` = version token) -> server-rendered `/novel/{slug}` pages. Every
page embeds the React-Query cache as `window.__REACT_QUERY_STATE__ = {...}`; the `['novel', slug, ...]` query holds the
structured novel record (genres, tags, translator, synopsis, status, chapter count, pricing model), and
`['chapter', slug, chapterSlug, ...]` on a chapter page holds the chapter body. Verified live (Oct 2026).

Free chapters: only chapters whose own `pricingInfo.isFree` is true, or every chapter when the novel's active pricing
model is `free`; never when `karmaInfo.isKarmaRequired`. Fetching stops at the first chapter that is not free."""
import json
import re
from datetime import UTC, datetime
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData
from .htmlutil import block_text, get_html, lang_code, parse_sitemap, soup, split_author, uniq

BASE = "https://www.wuxiaworld.com"
STATUS = {0: "completed", 1: "ongoing", 2: "hiatus"}


def query_state(html: str) -> dict[str, Any]:
    m = re.search(r"window\.__REACT_QUERY_STATE__\s*=\s*", html)
    if not m:
        return {}
    try:
        return json.JSONDecoder().raw_decode(html[m.end():])[0]
    except ValueError:
        return {}


def find_query(state: dict[str, Any], kind: str) -> dict[str, Any] | None:
    for q in state.get("queries", []):
        key = q.get("queryKey") or []
        if key and key[0] == kind:
            data = (q.get("state") or {}).get("data") or {}
            if isinstance(data, dict) and "item" in data:
                return data["item"]
    return None


def _val(x: Any) -> Any:
    return x.get("value") if isinstance(x, dict) else x


def html_text(fragment: str | None) -> str | None:
    return block_text(soup(f"<div>{fragment}</div>").div) if fragment else None


def alt_names(description_html: str | None) -> list[str]:
    """Translators often put 'native title, aka Pinyin, aka English' in the first description paragraph."""
    first = (html_text(description_html) or "").split("\n\n")[0]
    if "aka" not in first.lower() or len(first) > 300:
        return []
    return [p.strip() for p in re.split(r",?\s*\baka\b\s*", first, flags=re.I) if 0 < len(p.strip()) <= 100]


def free_pricing(item: dict[str, Any]) -> bool:
    series = item.get("series") or {}
    return ((series.get("pricingModel") or {}).get("pricing") or {}).get("oneofKind") == "free"


def parse_novel(html: str, lastmod: str = "") -> SourceRecordData | None:
    item = find_query(query_state(html), "novel")
    if not item or item.get("isHidden") or (item.get("series") or {}).get("isDelisted"):
        return None
    slug = item["slug"]
    lang_name = _val(item.get("language"))
    code = lang_code(lang_name)
    authors = split_author(_val(item.get("authorName")) or "")
    review = item.get("reviewInfo") or {}
    rating = _val(review.get("rating"))
    created = (item.get("createdAt") or {}).get("seconds")
    desc = item.get("description", {})
    chapter_info = item.get("chapterInfo") or {}
    first = chapter_info.get("firstChapter") or {}
    return SourceRecordData(
        source="wuxiaworld",
        source_id=slug,
        url=f"{BASE}/novel/{slug}",
        title=item["name"],
        alt_titles=uniq(alt_names(_val(desc))),
        authors=authors,
        synopsis=html_text(_val(item.get("synopsis"))),
        tags=[t for t in item.get("tags", []) if t.lower() != (lang_name or "").lower()],
        genres=list(item.get("genres", [])),
        status=STATUS.get(item.get("status")),
        chapter_count=_val(chapter_info.get("chapterCount")),
        rating=rating,
        rating_scale=1.0 if rating is not None else None,
        rating_votes=review.get("count") if rating is not None else None,
        language=code,
        start_year=datetime.fromtimestamp(created, UTC).year if created else None,
        cover_url=_val(item.get("coverUrl")),
        external_ids={"wuxiaworld": slug},
        raw={
            "translator": _val(item.get("translatorName")) or (item.get("translator") or {}).get("userName"),
            "licensed_from": _val(item.get("licensedFrom")), "free_all": free_pricing(item),
            "first_chapter": first.get("slug"), "_version": lastmod or str(_val(chapter_info.get("chapterCount"))),
        },
    )


def parse_chapter(html: str, novel_free: bool) -> tuple[str | None, str | None]:
    """(text, next_chapter_slug) of a chapter page, or (None, None) if the chapter is not free / has no body."""
    item = find_query(query_state(html), "chapter")
    if not item:
        return None, None
    free = novel_free or (item.get("pricingInfo") or {}).get("isFree")
    if not free or (item.get("karmaInfo") or {}).get("isKarmaRequired"):
        return None, None
    body = html_text(_val(item.get("content")))
    nxt = ((item.get("relatedChapterInfo") or {}).get("nextChapter") or {}).get("slug")
    return body, nxt


class WuxiaworldSource(Source):
    name = "wuxiaworld"
    min_delay = 3.0

    def __init__(self, http: PoliteClient, batch_size: int = 10):
        self.http = http
        self.batch_size = batch_size

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        xml = await get_html(self.http, f"{BASE}/sitemap-novels.xml")
        entries = [(u, lm) for u, lm in parse_sitemap(xml or "<urlset/>") if "/novel/" in u]
        i = (checkpoint or {}).get("index", 0)
        while i < len(entries):
            recs = []
            end = min(i + self.batch_size, len(entries))
            for url, lastmod in entries[i:end]:
                slug = url.rstrip("/").rsplit("/", 1)[-1]
                if lastmod and self.known_versions.get(slug) == lastmod:  # type: ignore[attr-defined]
                    continue
                html = await get_html(self.http, url)
                rec = parse_novel(html, lastmod) if html else None
                if rec:
                    recs.append(rec)
            i = end
            yield CatalogBatch(recs, {"index": i}, done=i >= len(entries))

    async def fetch_work(self, source_id: str) -> SourceRecordData | None:
        html = await get_html(self.http, f"{BASE}/novel/{source_id}")
        return parse_novel(html) if html else None

    async def fetch_sample_chapters(self, record: SourceRecordData, n: int) -> list[str]:
        slug = record.source_id
        html = await get_html(self.http, f"{BASE}/novel/{slug}")
        item = find_query(query_state(html or ""), "novel") if html else None
        if not item:
            return []
        free_all = free_pricing(item)
        chapter = (((item.get("chapterInfo") or {}).get("firstChapter")) or {}).get("slug")
        out: list[str] = []
        while chapter and len(out) < min(n, 3):
            page = await get_html(self.http, f"{BASE}/novel/{slug}/{chapter}")
            text, chapter = parse_chapter(page, free_all) if page else (None, None)
            if not text:
                break
            out.append(text)
        return out
