"""AniList GraphQL (https://docs.anilist.co/). Format NOVEL (type MANGA). Rate limit: 90/min nominal, currently
30/min (X-RateLimit-Limit header) -> we use >= 2.2s between requests and honor Retry-After on 429."""
import re
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData
from .util import extract_links, strip_html

API = "https://graphql.anilist.co"
QUERY = """
query($page:Int,$perPage:Int){Page(page:$page,perPage:$perPage){
 pageInfo{total currentPage lastPage hasNextPage perPage}
 media(type:MANGA,format:NOVEL,sort:ID){
  id idMal title{romaji english native} synonyms description(asHtml:false) genres
  tags{name rank isMediaSpoiler} status chapters volumes averageScore meanScore popularity
  countryOfOrigin startDate{year} coverImage{large} siteUrl updatedAt
  staff(perPage:6){edges{role node{name{full native}}}} externalLinks{url site}
 }}}
"""
STATUS = {"FINISHED": "completed", "RELEASING": "ongoing", "HIATUS": "hiatus", "CANCELLED": "cancelled"}
LANG = {"JP": "ja", "KR": "ko", "CN": "zh", "TW": "zh"}


def parse_media(m: dict[str, Any]) -> SourceRecordData:
    t = m["title"]
    primary = t.get("english") or t.get("romaji") or t.get("native") or ""
    alts = [x for x in [t.get("romaji"), t.get("english"), t.get("native"), *(m.get("synonyms") or [])] if x and x != primary]
    authors: list[str] = []
    for e in (m.get("staff") or {}).get("edges", []):
        if "story" in (e.get("role") or "").lower() or not authors and "art" not in (e.get("role") or "").lower():
            n = e["node"]["name"]
            authors += [x for x in (n.get("full"), n.get("native")) if x]
    desc = strip_html(m.get("description"))
    if desc:
        desc = re.sub(r"\(Source:[^)]*\)\s*$", "", desc).strip()
    ext_text = " ".join(l["url"] for l in (m.get("externalLinks") or []))
    _, urls = extract_links(ext_text)  # descriptive cross-links: kept as related_urls only, never as identity ids
    ids = {"anilist": str(m["id"])}
    if m.get("idMal"):  # AniList's own structured MAL mapping IS authoritative
        ids["mal"] = str(m["idMal"])
    score = m.get("averageScore") or m.get("meanScore")
    return SourceRecordData(
        source="anilist",
        source_id=str(m["id"]),
        url=m.get("siteUrl"),
        title=primary,
        alt_titles=list(dict.fromkeys(alts)),
        authors=list(dict.fromkeys(authors)),
        synopsis=desc,
        tags=[x["name"] for x in m.get("tags") or [] if not x.get("isMediaSpoiler") and (x.get("rank") or 0) >= 30],
        genres=m.get("genres") or [],
        status=STATUS.get(m.get("status") or ""),
        chapter_count=m.get("chapters"),
        rating=float(score) if score else None,
        rating_scale=100.0 if score else None,
        rating_votes=None,
        language=LANG.get(m.get("countryOfOrigin") or ""),
        start_year=(m.get("startDate") or {}).get("year"),
        cover_url=(m.get("coverImage") or {}).get("large"),
        external_ids=ids,
        related_urls=urls,
        raw={**{k: v for k, v in m.items() if k != "description"}, "_version": str(m.get("updatedAt", ""))},
    )


class AniListSource(Source):
    name = "anilist"
    min_delay = 2.2
    respect_robots = False

    def __init__(self, http: PoliteClient, per_page: int = 50):
        self.http = http
        self.per_page = per_page

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        page = (checkpoint or {}).get("page", 1)
        while True:
            resp = await self.http.post(API, json={"query": QUERY, "variables": {"page": page, "perPage": self.per_page}})
            resp.raise_for_status()
            data = resp.json()["data"]["Page"]
            recs = [parse_media(m) for m in data["media"]]
            remaining = resp.headers.get("x-ratelimit-remaining")
            if remaining is not None and remaining.isdigit() and int(remaining) < 3:
                self.http.set_min_delay_for(self.http.origin(API), 10.0)
            has_next = data["pageInfo"]["hasNextPage"]
            page += 1
            yield CatalogBatch(recs, {"page": page}, done=not has_next)
            if not has_next:
                return
