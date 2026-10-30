"""Wiring of settings, spend tracker, AI clients and sources (shared by API and worker)."""
from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from .ai.embeddings import Embedder, EmbeddingUnavailable, VoyageEmbedder
from .ai.jev import CompositeVerdict, JevClient, VerdictProvider
from .ai.llm import LLM, ClaudeLLM, LLMError
from .ai.spend import SpendTracker
from .config import Settings, get_settings
from .crawl.http import PoliteClient
from .db import session_maker
from .sources.anilist import AniListSource
from .sources.base import Source
from .sources.html_registry import register as register_html_sources
from .sources.mal import MalSource
from .sources.mangaupdates import MangaUpdatesSource
from .sources.syosetu import SyosetuSource

# HTML-site sources (5b): name -> factory(settings) -> Source, filled by sources.html_registry.register()
HTML_SOURCES: dict = {}
API_SOURCES = ["syosetu", "anilist", "mangaupdates", "mal"]
register_html_sources(HTML_SOURCES)


@dataclass
class Services:
    settings: Settings
    maker: async_sessionmaker[AsyncSession]
    spend: SpendTracker
    embedder: Embedder | None
    llm: LLM | None
    verdicts: VerdictProvider | None


def build_services(settings: Settings | None = None) -> Services:
    settings = settings or get_settings()
    maker = session_maker()
    spend = SpendTracker(maker, settings.monthly_ai_budget_usd)
    embedder: Embedder | None
    try:
        embedder = VoyageEmbedder(settings, spend)
    except EmbeddingUnavailable:
        embedder = None
    try:
        llm: LLM | None = ClaudeLLM(settings, spend)
    except LLMError:
        llm = None
    jev = JevClient(settings, spend) if settings.jev_api_key else None
    verdicts = CompositeVerdict(settings, jev, llm) if (jev or llm) else None
    return Services(settings, maker, spend, embedder, llm, verdicts)


def source_names() -> list[str]:
    return [*API_SOURCES, *HTML_SOURCES.keys()]


def build_source(name: str, settings: Settings) -> Source:
    def client(delay: float | None, robots: bool) -> PoliteClient:
        return PoliteClient(settings.user_agent, min_delay=max(settings.crawl_min_delay_seconds, delay or 0.0), respect_robots=robots)

    if name == "syosetu":
        return SyosetuSource(client(None, False), html_http=client(None, True))
    if name == "anilist":
        return AniListSource(client(2.2, False))
    if name == "mangaupdates":
        return MangaUpdatesSource(client(None, False))
    if name == "mal":
        return MalSource(client(None, False), settings.mal_client_id)
    if name in HTML_SOURCES:
        return HTML_SOURCES[name](settings)
    raise KeyError(name)
