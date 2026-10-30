"""NovelUpdates (novelupdates.com): series directory (associated names incl. native titles, authors, publishers, tags,
genres, ratings, status, translator/publisher links). Metadata only; the site hosts no chapters.

BLOCKED / UNVERIFIED. Live probe (Oct 2026): every page, with our honest User-Agent, returns `403` + `cf-mitigated:
challenge` (Cloudflare "Just a moment..."). We never evade, so `iter_catalog` raises BlockedError and the runner marks the
source blocked. The parsers below follow NovelUpdates' long-stable markup (div#editassociated, #showauthors, #seriesgenre,
#showtags, #editstatus, ...) and are tested only on a hand-written fixture. Tag origin: unknown tags are auto-created as
canonical tags with origin = source name, i.e. `novelupdates` (resolver)."""
import re

from .base import SourceRecordData
from .htmlutil import block_text, lang_code, soup, text_of, to_int, uniq, year_of
from .listing import ListingDetailSource
from .util import extract_links

BASE = "https://www.novelupdates.com"
STATUS_WORDS = (("hiatus", "hiatus"), ("ongoing", "ongoing"), ("complete", "completed"), ("dropped", "cancelled"), ("cancel", "cancelled"))


def slug_of(url: str) -> str | None:
    m = re.search(r"/series/([^/?#]+)", url)
    return m.group(1) if m else None


def parse_listing_page(html: str) -> list[SourceRecordData]:
    out = []
    for box in soup(html).select("div.search_main_box_nu"):
        a = box.select_one(".search_title a")
        slug = slug_of(a["href"]) if a and a.get("href") else None
        if not slug:
            continue
        img = box.select_one(".search_img_nu img")
        votes = text_of(box.select_one(".search_ratings"))
        out.append(SourceRecordData(
            source="novelupdates", source_id=slug, url=f"{BASE}/series/{slug}/", title=text_of(a),
            genres=uniq([text_of(g) for g in box.select(".search_genre a")]),
            synopsis=(text_of(box.select_one(".search_body_nu")) or None),
            cover_url=img.get("src") if img else None, external_ids={"novelupdates": slug},
            raw={"list_rating": votes, "_version": votes + "|" + text_of(box.select_one(".search_stats"))},
        ))
    return out


def parse_series_page(html: str, slug: str) -> SourceRecordData | None:
    doc = soup(html)
    title = text_of(doc.select_one(".seriestitlenu"))
    if not title:
        return None
    assoc = doc.select_one("#editassociated")
    alts = [t.strip() for t in (assoc.get_text("\n") if assoc else "").split("\n") if t.strip()]
    authors = uniq([text_of(a) for a in doc.select("#showauthors a")])
    status_txt = text_of(doc.select_one("#editstatus"))
    status = next((v for k, v in STATUS_WORDS if k in status_txt.lower()), None)
    rating_txt = text_of(doc.select_one(".uvotes"))
    rm = re.search(r"([\d.]+)\s*/\s*([\d.]+),\s*([\d,]+)\s*votes", rating_txt)
    desc_el = doc.select_one("#editdescription")
    for more in (desc_el.select("a.morelink") if desc_el else []):
        more.extract()
    desc = block_text(desc_el)
    img = doc.select_one(".seriesimg img")
    link_scope = doc.select("#showtranslated a, #showepublisher a, #showopublisher a, #showlicensed a, #editdescription a")
    hrefs = uniq([a["href"] for a in link_scope if a.get("href", "").startswith("http") and "novelupdates.com" not in a["href"]])
    ids, urls = extract_links(" ".join([desc or "", *hrefs]))
    chapters = to_int(status_txt)
    return SourceRecordData(
        source="novelupdates", source_id=slug, url=f"{BASE}/series/{slug}/", title=title,
        alt_titles=uniq(alts), authors=authors, synopsis=desc,
        tags=uniq([text_of(a) for a in doc.select("#showtags a")]),
        genres=uniq([text_of(a) for a in doc.select("#seriesgenre a")]),
        status=status, chapter_count=chapters,
        rating=float(rm.group(1)) if rm else None, rating_scale=float(rm.group(2)) if rm else None,
        rating_votes=to_int(rm.group(3)) if rm else None,
        language=lang_code(text_of(doc.select_one("#showlang a"))), start_year=year_of(text_of(doc.select_one("#edityear"))),
        cover_url=img.get("src") if img else None,
        external_ids={"novelupdates": slug, **ids},
        related_urls=uniq([*hrefs, *urls]),
        raw={
            "type": text_of(doc.select_one("#showtype")),
            "original_publisher": uniq([text_of(a) for a in doc.select("#showopublisher a")]),
            "english_publisher": uniq([text_of(a) for a in doc.select("#showepublisher a")]),
            "licensed": text_of(doc.select_one("#showlicensed")), "artists": uniq([text_of(a) for a in doc.select("#showartists a")]),
            "completely_translated": "yes" in text_of(doc.select_one("#showtranslated")).lower() if doc.select_one("#showtranslated") else None,
            "_version": f"{status_txt}|{rating_txt}",
        },
    )


class NovelUpdatesSource(ListingDetailSource):
    name = "novelupdates"
    listings = [
        f"{BASE}/series-ranking/?rank=popular&pg={{page}}",
        f"{BASE}/series-finder/?sf=1&sort=sdate&order=desc&pg={{page}}",
    ]

    def parse_listing(self, html: str) -> list[SourceRecordData]:
        return parse_listing_page(html)

    def parse_detail(self, html: str, stub: SourceRecordData) -> SourceRecordData | None:
        return parse_series_page(html, stub.source_id)

    def detail_url(self, source_id: str) -> str:
        return f"{BASE}/series/{source_id}/"
