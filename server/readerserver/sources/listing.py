"""Base for sites crawled as 'listing pages -> detail pages' (webnovel, novelupdates, royalroad, scribblehub)."""
from collections.abc import AsyncIterator
from typing import Any

from ..crawl.http import PoliteClient
from .base import CatalogBatch, Source, SourceRecordData
from .htmlutil import get_html


class ListingDetailSource(Source):
    """Subclasses set `listings` (URL templates with `{page}`), implement `parse_listing` (stub records, each with
    raw['_version'] set) and `parse_detail`. A detail page is fetched only when the stub's version differs from
    `known_versions` (incremental re-crawl); stubs are stored as-is if the detail page is gone."""

    listings: list[str] = []
    min_delay = 3.0
    first_page = 1

    def __init__(self, http: PoliteClient, max_pages: int = 5, fetch_details: bool = True):
        self.http = http
        self.max_pages = max_pages
        self.fetch_details = fetch_details

    def parse_listing(self, html: str) -> list[SourceRecordData]:
        raise NotImplementedError

    def parse_detail(self, html: str, stub: SourceRecordData) -> SourceRecordData | None:
        raise NotImplementedError

    def detail_url(self, source_id: str) -> str:
        raise NotImplementedError

    async def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        cp = checkpoint or {}
        li, page = cp.get("list_index", 0), cp.get("page", self.first_page)
        while li < len(self.listings):
            html = await get_html(self.http, self.listings[li].format(page=page))
            stubs = self.parse_listing(html) if html else []
            recs: list[SourceRecordData] = []
            for stub in stubs:
                version = stub.raw.get("_version")
                if version and self.known_versions.get(stub.source_id) == version:  # type: ignore[attr-defined]
                    continue
                rec = stub
                if self.fetch_details and stub.url:
                    detail = await get_html(self.http, stub.url)
                    parsed = self.parse_detail(detail, stub) if detail else None
                    rec = parsed or stub
                    rec.raw.setdefault("_version", version)
                recs.append(rec)
            if not stubs or page - self.first_page + 1 >= self.max_pages:
                li, page = li + 1, self.first_page
            else:
                page += 1
            yield CatalogBatch(recs, {"list_index": li, "page": page}, done=li >= len(self.listings))

    async def fetch_work(self, source_id: str) -> SourceRecordData | None:
        html = await get_html(self.http, self.detail_url(source_id))
        if not html:
            return None
        stub = SourceRecordData(source=self.name, source_id=source_id, title="", url=self.detail_url(source_id))
        return self.parse_detail(html, stub)
