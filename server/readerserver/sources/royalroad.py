"""Royal Road (royalroad.com): EN originals, metadata only (robots.txt also asks AI-search agents not to read
/fiction/*/chapter/, and we do not read chapters at all).

BLOCKED / UNVERIFIED. Live probe (Oct 2026): the listing pages, with our honest User-Agent, return `403` +
`cf-mitigated: challenge` (Cloudflare). We never evade, so the runner marks the source blocked. Parsers follow Royal
Road's list/detail markup (div.fiction-list-item, h2.fiction-title, a.fiction-tag, #description-<id>, h4 > a[/profile/])
and are tested on hand-written fixtures only."""
import re

from .base import SourceRecordData
from .htmlutil import block_text, json_ld, meta, soup, text_of, to_float, to_int, uniq, year_of
from .listing import ListingDetailSource

BASE = "https://www.royalroad.com"
STATUS = {"ONGOING": "ongoing", "COMPLETED": "completed", "HIATUS": "hiatus", "DROPPED": "cancelled", "STUB": "ongoing"}
NON_TAGS = set(STATUS) | {"ORIGINAL", "FAN FICTION"}


def fiction_id(url: str) -> str | None:
    m = re.search(r"/fiction/(\d+)", url)
    return m.group(1) if m else None


def parse_listing_page(html: str) -> list[SourceRecordData]:
    out = []
    for item in soup(html).select("div.fiction-list-item"):
        a = item.select_one("h2.fiction-title a")
        fid = fiction_id(a["href"]) if a and a.get("href") else None
        if not fid:
            continue
        labels = [text_of(t).upper() for t in item.select(".label, .tags a.fiction-tag")]
        stats = text_of(item.select_one(".stats"))
        star = item.select_one("[data-content*='/ 5'], span.star")
        rating = to_float((star.get("data-content") if star else "") or "")
        desc = item.select_one(f"#description-{fid}") or item.select_one("[id^='description-']")
        img = item.select_one("img[data-type='cover'], figure img")
        out.append(SourceRecordData(
            source="royalroad", source_id=fid, url=BASE + a["href"], title=text_of(a),
            tags=uniq([text_of(t) for t in item.select(".tags a.fiction-tag") if text_of(t).upper() not in NON_TAGS]),
            status=next((STATUS[x] for x in labels if x in STATUS), None),
            chapter_count=to_int(m.group(0)) if (m := re.search(r"[\d,]+\s*Chapters", stats)) else None,
            rating=rating, rating_scale=5.0 if rating else None, language="en",
            synopsis=block_text(desc), cover_url=img.get("src") if img else None, external_ids={"royalroad": fid},
            raw={"stats": stats, "_version": stats},
        ))
    return out


def parse_fiction_page(html: str, stub: SourceRecordData) -> SourceRecordData | None:
    doc = soup(html)
    title = text_of(doc.select_one(".fic-header h1")) or text_of(doc.find("h1"))
    if not title:
        return None
    ld = next((d for d in json_ld(doc) if d.get("@type") in ("Book", "CreativeWork")), {})
    author = doc.select_one(".fic-header h4 a[href*='/profile/']") or doc.select_one("a[href^='/profile/']")
    authors = [text_of(author)] if author else ([ld["author"]["name"]] if isinstance(ld.get("author"), dict) and ld["author"].get("name") else [])
    desc = block_text(doc.select_one(".description .hidden-content") or doc.select_one(".description"))
    labels = [text_of(t).upper() for t in doc.select(".fic-header span.label, .fiction-info .label")]
    tags = [text_of(t) for t in doc.select("span.tags a.fiction-tag")]
    rating = to_float(meta(doc, "books:rating:value") or (doc.select_one("[data-content*='/ 5']") or {}).get("data-content", ""))
    votes = to_int(text_of(doc.select_one("[data-original-title='Ratings'], .ratings-count")) or "")
    cover = doc.select_one("img.thumbnail, img[data-type='cover']")
    return SourceRecordData(
        source="royalroad", source_id=stub.source_id, url=stub.url or f"{BASE}/fiction/{stub.source_id}", title=title,
        authors=authors, synopsis=desc or stub.synopsis,
        tags=uniq([t for t in tags if t.upper() not in NON_TAGS]) or stub.tags,
        status=next((STATUS[x] for x in labels if x in STATUS), stub.status),
        chapter_count=to_int(text_of(doc.select_one("[data-original-title='Chapters']")) or "") or stub.chapter_count,
        rating=rating or stub.rating, rating_scale=5.0 if (rating or stub.rating) else None, rating_votes=votes,
        language="en", start_year=year_of(ld.get("datePublished") or meta(doc, "books:release_date") or ""),
        cover_url=(cover.get("src") if cover else None) or meta(doc, "og:image") or stub.cover_url,
        external_ids={"royalroad": stub.source_id}, raw={**stub.raw},
    )


class RoyalRoadSource(ListingDetailSource):
    name = "royalroad"
    listings = [f"{BASE}/fictions/best-rated?page={{page}}", f"{BASE}/fictions/rising-stars?page={{page}}"]

    def parse_listing(self, html: str) -> list[SourceRecordData]:
        return parse_listing_page(html)

    def parse_detail(self, html: str, stub: SourceRecordData) -> SourceRecordData | None:
        return parse_fiction_page(html, stub)

    def detail_url(self, source_id: str) -> str:
        return f"{BASE}/fiction/{source_id}"
