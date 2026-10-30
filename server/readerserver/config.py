from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "postgresql+asyncpg://readerapp:readerapp@localhost:5435/readerapp"
    api_token: str = ""

    anthropic_api_key: str = ""
    voyage_api_key: str = ""
    jev_api_key: str = ""
    jev_base_url: str = "https://api.typesafe.ai"
    jev_model: str = "jev-latest"
    jev_min_probability: float = 0.7
    mal_client_id: str = ""

    hyde_model: str = "claude-haiku-5-5"
    rerank_model: str = "claude-sonnet-5-5"
    verdict_model: str = "claude-haiku-5-5"
    hyde_effort: str = "low"
    rerank_effort: str = "medium"
    verdict_effort: str = "low"

    voyage_model: str = "voyage-4-large"
    embedding_dim: int = 1024
    voyage_price_per_mtok: float = 0.12  # estimate; override if pricing changes
    jev_price_per_call: float = 0.002  # estimate

    monthly_ai_budget_usd: float = Field(default=5.0)

    crawler_contact: str = "unset@example.invalid"
    crawl_min_delay_seconds: float = 3.0
    crawl_interval_hours: float = 24.0
    sample_chapters: int = 3
    crawl_lease_seconds: float = 900.0  # a running crawl renews this lease; an expired one is resumed from its checkpoint
    enabled_sources: str = ""  # scheduled crawling is opt-in: ENABLED_SOURCES=syosetu,anilist,mangaupdates,mal

    @property
    def user_agent(self) -> str:
        return f"ReaderAppCrawler/0.1 (personal novel-discovery index; low rate, honors robots.txt; contact: {self.crawler_contact})"


@lru_cache
def get_settings() -> Settings:
    return Settings()
