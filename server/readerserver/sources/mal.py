"""MyAnimeList API v2 (https://myanimelist.net/apiconfig/references/api/v2). Needs a client id (X-MAL-CLIENT-ID).
GET /manga/ranking?ranking_type=lightnovels|novels&limit<=500&offset&fields=... Rate limits are undocumented; we stay
at >= 3 s between requests and back off on 429."""
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData
from .util import guess_language_from_script

BASE = "https://api.myanimelist.net/v2"
FIELDS = (
    "id,title,main_picture,alternative_titles,start_date,synopsis,mean,num_scoring_users,media_type,status,"
    "genres,num_chapters,num_volumes,authors{first_name,last_name}"
)
RANKINGS = ["lightnovels", "novels"]
STATUS = {"finished": "completed", "currently_publishing": "ongoing", "on_hiatus": "hiatus", "discontinued": "cancelled"}


def parse_node(n: dict[str, Any]) -> SourceRecordData:
    alt = n.get("alternative_titles") or {}
    native = alt.get("ja") or ""
    alts = [x for x in [alt.get("en"), native, *(alt.get("synonyms") or [])] if x and x != n["title"]]
    authors: list[str] = []
    for a in n.get("authors") or []:
        nd = a["node"]
        first, last = nd.get("first_name", "").strip(), nd.get("last_name", "").strip()
        authors += [x for x in (f"{first} {last}".strip(), f"{last} {first}".strip()) if x]
    year = (n.get("start_date") or "")[:4]
    mean = n.get("mean")
    return SourceRecordData(
        source="mal",
        source_id=str(n["id"]),
        url=f"https://myanimelist.net/manga/{n['id']}",
        title=n["title"],
        alt_titles=list(dict.fromkeys(alts)),
        authors=list(dict.fromkeys(authors)),
        synopsis=(n.get("synopsis") or "").strip() or None,
        genres=[g["name"] for g in n.get("genres") or []],
        status=STATUS.get(n.get("status") or ""),
        chapter_count=n.get("num_chapters") or None,
        rating=float(mean) if mean else None,
        rating_scale=10.0 if mean else None,
        rating_votes=n.get("num_scoring_users"),
        language=guess_language_from_script(native),
        start_year=int(year) if year.isdigit() else None,
        cover_url=(n.get("main_picture") or {}).get("large") or (n.get("main_picture") or {}).get("medium"),
        external_ids={"mal": str(n["id"])},
        raw={**n, "_version": ""},
    )


class MalSource(Source):
    name = "mal"
    respect_robots = False

    def __init__(self, http: PoliteClient, client_id: str, page_size: int = 100):
        self.http = http
        self.client_id = client_id
        self.page_size = page_size

    def available(self) -> bool:
        return bool(self.client_id)

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        cp = checkpoint or {}
        ri, offset = cp.get("ranking_index", 0), cp.get("offset", 0)
        while ri < len(RANKINGS):
            resp = await self.http.get(
                f"{BASE}/manga/ranking",
                params={"ranking_type": RANKINGS[ri], "limit": self.page_size, "offset": offset, "fields": FIELDS},
                headers={"X-MAL-CLIENT-ID": self.client_id},
            )
            resp.raise_for_status()
            data = resp.json()
            recs = [parse_node(d["node"]) for d in data.get("data", [])]
            if (data.get("paging") or {}).get("next") and recs:
                offset += self.page_size
            else:
                ri, offset = ri + 1, 0
            yield CatalogBatch(recs, {"ranking_index": ri, "offset": offset}, done=ri >= len(RANKINGS))
