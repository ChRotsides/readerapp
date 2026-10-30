"""Source interface shared by the official-API sources (5a) and the HTML-site crawlers (5b)."""
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any


@dataclass
class SourceRecordData:
    source: str
    source_id: str
    title: str
    url: str | None = None
    alt_titles: list[str] = field(default_factory=list)
    authors: list[str] = field(default_factory=list)
    synopsis: str | None = None
    tags: list[str] = field(default_factory=list)
    genres: list[str] = field(default_factory=list)
    status: str | None = None  # ongoing|completed|hiatus|cancelled
    chapter_count: int | None = None
    rating: float | None = None
    rating_scale: float | None = None  # e.g. 10, 5, 100
    rating_votes: int | None = None
    language: str | None = None  # ORIGINAL language: ja|ko|zh|en
    start_year: int | None = None
    cover_url: str | None = None
    external_ids: dict[str, str] = field(default_factory=dict)  # cross-source ids: {"mal": "123", "anilist": "9"}
    related_urls: list[str] = field(default_factory=list)  # other sites' URLs discovered in the record
    raw: dict[str, Any] = field(default_factory=dict)


@dataclass
class CatalogBatch:
    records: list[SourceRecordData]
    checkpoint: dict[str, Any]  # persisted after the batch is stored; pass back to resume
    done: bool = False


class Source(ABC):
    """Implement this for each site/API.

    HTML sources must fetch through a `PoliteClient` (crawl/http.py): it enforces robots.txt, per-domain delay,
    backoff and stops on blocks. Raise BlockedError (let it propagate) on 403/challenges; never try to evade.
    """

    name: str  # short stable id, e.g. "syosetu"
    min_delay: float | None = None  # per-request delay override (None -> settings default)
    respect_robots: bool = True  # False only for documented public APIs

    def available(self) -> bool:
        """False -> skipped gracefully (e.g. missing credentials)."""
        return True

    @abstractmethod
    def iter_catalog(self, checkpoint: dict[str, Any] | None) -> AsyncIterator[CatalogBatch]:
        """Iterate the catalog in batches; resume from `checkpoint`. Each batch carries the next checkpoint.
        Implementations are async generators."""

    async def fetch_work(self, source_id: str) -> SourceRecordData | None:
        """Fetch one work's metadata (used for refreshes). Default: unsupported."""
        return None

    async def fetch_sample_chapters(self, record: SourceRecordData, n: int) -> list[str]:
        """Optional: text of the first `n` FREE chapters (never paid/locked) for embedding. The caller embeds
        and discards the text; it must never be persisted. Default: none."""
        return []


# Set by the crawl runner before iteration: {source_id: version token stored from the previous crawl}.
# Sources that need a per-item detail request can skip unchanged items (incremental re-crawl).
Source.known_versions = {}  # type: ignore[attr-defined]
