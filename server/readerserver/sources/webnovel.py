"""Webnovel (webnovel.com, Qidian International): licensed CN translations + English originals. Catalog from the public
browse pages and book pages; sample chapters only from chapters that are not locked.

BLOCKED / UNVERIFIED. Live probe (Oct 2026): `/stories/novel` (allowed by robots.txt) returns `403` +
`cf-mitigated: challenge` (Cloudflare) for our honest User-Agent. We never evade (no headless browser, no UA spoofing,
no login), so `iter_catalog` raises BlockedError and the runner marks the source blocked; the metadata the user imports
manually is the fallback (docs/DESIGN.md). Parsers are therefore written from Webnovel's public markup/JSON-LD/OpenGraph
conventions and tested on hand-written fixtures only; expect selector fixes if the block is ever lifted."""
import re

from .base import SourceRecordData
from .htmlutil import block_text, get_html, json_ld, meta, soup, text_of, to_float, to_int, uniq, year_of
from .listing import ListingDetailSource

BASE = "https://www.webnovel.com"


def book_id(url: str) -> str | None:
    m = re.search(r"/book/(?:[^/?#]*_)?(\d{6,})", url)
    return m.group(1) if m else None


def parse_listing_page(html: str) -> list[SourceRecordData]:
    out, seen = [], set()
    for a in soup(html).select("a[href^='/book/']"):
        bid = book_id(a["href"])
        title = a.get("title") or text_of(a.select_one("h3, .g_thumb_title, strong")) or text_of(a)
        if not bid or bid in seen or not title:
            continue
        seen.add(bid)
        img = a.select_one("img")
        out.append(SourceRecordData(
            source="webnovel", source_id=bid, url=BASE + a["href"], title=title.strip(),
            cover_url=(img.get("data-original") or img.get("src")) if img else None, external_ids={"webnovel": bid}, raw={},
        ))
    return out


def parse_book_page(html: str, stub: SourceRecordData) -> SourceRecordData | None:
    doc = soup(html)
    ld = next((d for d in json_ld(doc) if d.get("@type") in ("Book", "CreativeWork", "Product")), {})
    title = text_of(doc.select_one("h1")) or ld.get("name") or (meta(doc, "og:title") or "").split(" - ")[0]
    if not title:
        return None
    author = ld.get("author")
    author_name = author.get("name") if isinstance(author, dict) else (author if isinstance(author, str) else None)
    author_name = author_name or text_of(doc.select_one("[itemprop='author'], .det-info a[href*='/author']"))
    agg = ld.get("aggregateRating") or {}
    rating = to_float(str(agg.get("ratingValue", ""))) or to_float(text_of(doc.select_one(".g_star_num, ._score, [itemprop='ratingValue']")))
    synopsis = block_text(doc.select_one(".j_synopsis, [itemprop='description'], .det-abt")) or ld.get("description") or meta(doc, "og:description")
    genres = uniq([text_of(a) for a in doc.select("a[href*='/stories/'][href*='category'], .det-hd-detail a[href*='/stories/']")])
    tags = uniq([text_of(a).lstrip("#") for a in doc.select("a[href^='/tag/'], a[href*='/tags/']")])
    stats = text_of(doc.select_one(".det-hd-detail, ._stats, .j_bookStats"))
    chapters = to_int(m.group(0)) if (m := re.search(r"[\d,]+\s*Chapters?", stats, re.I)) else None
    cover = doc.select_one("img[alt][src*='book'], .g_thumb img")
    return SourceRecordData(
        source="webnovel", source_id=stub.source_id, url=stub.url or f"{BASE}/book/{stub.source_id}", title=title.strip(),
        authors=[author_name] if author_name else [], synopsis=synopsis, genres=genres, tags=tags,
        status="completed" if re.search(r"\bcompleted\b", stats, re.I) else ("ongoing" if re.search(r"\bongoing\b", stats, re.I) else None),
        chapter_count=chapters, rating=rating, rating_scale=5.0 if rating else None,
        rating_votes=to_int(str(agg.get("ratingCount") or agg.get("reviewCount") or "")),
        start_year=year_of(str(ld.get("datePublished") or "")),
        cover_url=ld.get("image") if isinstance(ld.get("image"), str) else ((cover.get("src") if cover else None) or meta(doc, "og:image") or stub.cover_url),
        external_ids={"webnovel": stub.source_id}, raw={"stats": stats, "_version": f"{chapters}|{rating}"},
    )


def free_chapter_links(html: str, limit: int = 3) -> list[str]:
    """Chapter links of the catalog list that are not marked locked/paid (stop at the first locked one)."""
    out = []
    for li in soup(html).select("li:has(a[href*='/book/'])"):
        a = li.select_one("a[href*='/book/']")
        if not a or not re.search(r"_\d{6,}$", a["href"].split("?")[0]):
            continue
        if li.select_one("svg._lock, i._lock, ._lock, .j_lock, [class*='lock']"):
            break
        out.append(a["href"])
        if len(out) >= limit:
            break
    return out


def parse_chapter(html: str) -> str | None:
    doc = soup(html)
    body = doc.select_one(".cha-content")
    if body is None or body.select_one("[class*='lock']") or "_lock" in " ".join(body.get("class", [])):
        return None
    return block_text(body)


class WebnovelSource(ListingDetailSource):
    name = "webnovel"
    listings = [
        f"{BASE}/stories/novel?orderBy=1&pageIndex={{page}}",  # popular
        f"{BASE}/stories/novel?orderBy=5&pageIndex={{page}}",  # recently updated
    ]

    def parse_listing(self, html: str) -> list[SourceRecordData]:
        return parse_listing_page(html)

    def parse_detail(self, html: str, stub: SourceRecordData) -> SourceRecordData | None:
        return parse_book_page(html, stub)

    def detail_url(self, source_id: str) -> str:
        return f"{BASE}/book/{source_id}"

    async def fetch_sample_chapters(self, record: SourceRecordData, n: int) -> list[str]:
        html = await get_html(self.http, f"{BASE}/book/{record.source_id}/catalog")
        out: list[str] = []
        for link in free_chapter_links(html or "", min(n, 3)):
            page = await get_html(self.http, link if link.startswith("http") else BASE + link)
            text = parse_chapter(page) if page else None
            if not text:
                break
            out.append(text)
        return out
