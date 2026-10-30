"""Scribble Hub (scribblehub.com): EN originals. Metadata + first free chapters (every Scribble Hub chapter is public).

BLOCKED / UNVERIFIED. Live probe (Oct 2026): series-ranking / series-finder return `403` from Cloudflare for our honest
User-Agent (the challenge page, no `cf-mitigated` header). We never evade, so the runner marks the source blocked. The
parsers follow Scribble Hub's markup (div.search_main_box, .search_title, .fic_title, .auth_name_fic, .wi_fic_desc,
.wi_fic_genre, .stag, #chp_raw) and are tested on hand-written fixtures only."""
import re

from .base import SourceRecordData
from .htmlutil import block_text, meta, soup, text_of, to_float, to_int, uniq, year_of
from .listing import ListingDetailSource

BASE = "https://www.scribblehub.com"
STATUS_WORDS = (("hiatus", "hiatus"), ("ongoing", "ongoing"), ("completed", "completed"), ("stub", "ongoing"))


def series_id(url: str) -> str | None:
    m = re.search(r"/series/(\d+)", url)
    return m.group(1) if m else None


def parse_listing_page(html: str) -> list[SourceRecordData]:
    out = []
    for box in soup(html).select("div.search_main_box"):
        a = box.select_one(".search_title a")
        sid = series_id(a["href"]) if a and a.get("href") else None
        if not sid:
            continue
        img = box.select_one(".search_img img")
        stats = text_of(box.select_one(".search_stats"))
        out.append(SourceRecordData(
            source="scribblehub", source_id=sid, url=a["href"], title=text_of(a), language="en",
            genres=uniq([text_of(g) for g in box.select(".search_genre a.fic_genre")]),
            tags=uniq([text_of(t) for t in box.select(".search_genre .stag, .search_tags a")]),
            synopsis=block_text(box.select_one(".search_body")), cover_url=img.get("src") if img else None,
            external_ids={"scribblehub": sid}, raw={"stats": stats, "_version": stats},
        ))
    return out


def parse_series_page(html: str, stub: SourceRecordData) -> SourceRecordData | None:
    doc = soup(html)
    title = text_of(doc.select_one(".fic_title"))
    if not title:
        return None
    author = doc.select_one(".auth_name_fic")
    stat_txt = text_of(doc.select_one(".fic_stats")) + " " + text_of(doc.select_one(".widget_fic_similar, .fic_status, .rnd_stats"))
    status = next((v for k, v in STATUS_WORDS if k in stat_txt.lower()), stub.status)
    rating = to_float(text_of(doc.select_one("#ratefic_user .rate_num, span#ratefic_user, .fic_rate .rating_num")) or "")
    cover = doc.select_one(".fic_image img")
    return SourceRecordData(
        source="scribblehub", source_id=stub.source_id, url=stub.url or f"{BASE}/series/{stub.source_id}/", title=title,
        authors=[text_of(author)] if author else stub.authors, synopsis=block_text(doc.select_one(".wi_fic_desc")) or stub.synopsis,
        genres=uniq([text_of(g) for g in doc.select(".wi_fic_genre a")]) or stub.genres,
        tags=uniq([text_of(t) for t in doc.select(".wi_fic_showtags a.stag")]) or stub.tags,
        status=status, chapter_count=to_int(text_of(doc.select_one(".cnt_toc, .fic_chapters")) or "") or stub.chapter_count,
        rating=rating, rating_scale=5.0 if rating else None, language="en",
        start_year=year_of(text_of(doc.select_one(".fic_date, .published_date")) or ""),
        cover_url=(cover.get("src") if cover else None) or meta(doc, "og:image") or stub.cover_url,
        external_ids={"scribblehub": stub.source_id},
        raw={**stub.raw, "first_chapter": first_chapter_url(doc)},
    )


def first_chapter_url(doc) -> str | None:
    a = doc.select_one("a.read_buttons[href*='/chapter/'], a.btn-first[href*='/chapter/']") or doc.find("a", href=re.compile(r"/read/\d+-[^/]+/chapter/\d+/?$"))
    if a is not None and (a.select_one("[class*='lock'], .fa-lock") or "lock" in " ".join(a.get("class", []))):
        return None  # marked locked/premium in the series page's own metadata: never request it
    return a["href"] if a else None


def parse_chapter(html: str) -> tuple[str | None, str | None]:
    """(text, next chapter url)."""
    doc = soup(html)
    nxt = doc.select_one("a.btn-next[href]")
    return block_text(doc.select_one("#chp_raw")), (nxt["href"] if nxt else None)


class ScribbleHubSource(ListingDetailSource):
    name = "scribblehub"
    listings = [
        f"{BASE}/series-ranking/?sort=1&order=4&pg={{page}}",
        f"{BASE}/series-finder/?sf=1&sort=dateadded&order=desc&pg={{page}}",
    ]

    def parse_listing(self, html: str) -> list[SourceRecordData]:
        return parse_listing_page(html)

    def parse_detail(self, html: str, stub: SourceRecordData) -> SourceRecordData | None:
        return parse_series_page(html, stub)

    def detail_url(self, source_id: str) -> str:
        return f"{BASE}/series/{source_id}/"

    async def fetch_sample_chapters(self, record: SourceRecordData, n: int) -> list[str]:
        from .htmlutil import get_html

        html = await get_html(self.http, record.url or self.detail_url(record.source_id))
        url = first_chapter_url(soup(html)) if html else None
        out: list[str] = []
        while url and len(out) < min(n, 3):
            page = await get_html(self.http, url)
            text, url = parse_chapter(page) if page else (None, None)
            if not text:
                break
            out.append(text)
        return out
