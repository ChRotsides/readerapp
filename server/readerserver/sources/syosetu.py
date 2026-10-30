"""Syosetu official novel API (https://dev.syosetu.com/man/api/). JP serials; metadata only.

Query: GET https://api.syosetu.com/novelapi/api/?out=json&of=...&lim=500&st=1..2000&order=hyoka&biggenre=N&type=re
Response is a JSON array; element 0 is {"allcount": N}. Limits: lim<=500, st<=2000 per query, so we sweep per biggenre
ordered by global points (popular first)."""
import re
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData

API = "https://api.syosetu.com/novelapi/api/"
FIELDS = "t-n-u-w-s-bg-g-k-gf-gl-nt-e-ga-l-ti-gp"
BIG_GENRES = [1, 2, 3, 4, 99, 98]
GENRE_NAMES = {
    101: "異世界〔恋愛〕", 102: "現実世界〔恋愛〕", 201: "ハイファンタジー〔ファンタジー〕", 202: "ローファンタジー〔ファンタジー〕",
    301: "純文学〔文芸〕", 302: "ヒューマンドラマ〔文芸〕", 303: "歴史〔文芸〕", 304: "推理〔文芸〕", 305: "ホラー〔文芸〕",
    306: "アクション〔文芸〕", 307: "コメディー〔文芸〕", 401: "VRゲーム〔SF〕", 402: "宇宙〔SF〕", 403: "空想科学〔SF〕",
    404: "パニック〔SF〕", 9901: "童話〔その他〕", 9902: "詩〔その他〕", 9903: "エッセイ〔その他〕", 9904: "リプレイ〔その他〕",
    9999: "その他〔その他〕", 9801: "ノンジャンル〔ノンジャンル〕",
}
GENRE_EN = {
    101: "Romance (Isekai)", 102: "Romance (Modern)", 201: "High Fantasy", 202: "Low Fantasy", 301: "Literary",
    302: "Drama", 303: "Historical", 304: "Mystery", 305: "Horror", 306: "Action", 307: "Comedy",
    401: "VR Game", 402: "Space", 403: "Sci-fi", 404: "Panic",
}


def parse_item(it: dict[str, Any]) -> SourceRecordData | None:
    if it.get("noveltype") == 2 or "ncode" not in it:  # skip short stories
        return None
    ncode = it["ncode"].lower()
    first = str(it.get("general_firstup", ""))
    year = int(first[:4]) if first[:4].isdigit() else None
    genre = GENRE_EN.get(it.get("genre"))
    return SourceRecordData(
        source="syosetu",
        source_id=ncode,
        url=f"https://ncode.syosetu.com/{ncode}/",
        title=it["title"],
        authors=[it["writer"]] if it.get("writer") else [],
        synopsis=(it.get("story") or "").strip() or None,
        tags=[k for k in re.split(r"\s+", it.get("keyword", "").strip()) if k],
        genres=[genre] if genre else [],
        status="ongoing" if it.get("end") == 1 else "completed",
        chapter_count=it.get("general_all_no"),
        language="ja",
        start_year=year,
        external_ids={"syosetu": ncode},
        raw={k: v for k, v in it.items() if k != "story"} | {"_version": str(it.get("general_lastup", ""))},
    )


class SyosetuSource(Source):
    name = "syosetu"
    respect_robots = False  # documented public API

    def __init__(self, http: PoliteClient, per_genre_limit: int = 2000, page_size: int = 500):
        self.http = http
        self.per_genre_limit = min(per_genre_limit, 2000)
        self.page_size = min(page_size, 500)

    async def _query(self, **params) -> list[dict]:
        q = {"out": "json", "of": FIELDS, "type": "re", **params}
        resp = await self.http.get(API, params=q)
        resp.raise_for_status()
        data = resp.json()
        return data[1:] if data else []

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        cp = checkpoint or {}
        gi, st = cp.get("genre_index", 0), cp.get("st", 1)
        while gi < len(BIG_GENRES):
            items = await self._query(biggenre=BIG_GENRES[gi], order="hyoka", lim=self.page_size, st=st)
            recs = [r for r in (parse_item(i) for i in items) if r]
            st += self.page_size
            if len(items) < self.page_size or st > self.per_genre_limit:
                gi, st = gi + 1, 1
            yield CatalogBatch(recs, {"genre_index": gi, "st": st}, done=gi >= len(BIG_GENRES))

    async def fetch_work(self, source_id: str) -> SourceRecordData | None:
        items = await self._query(ncode=source_id, lim=1)
        return parse_item(items[0]) if items else None
