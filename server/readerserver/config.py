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
    hyde_scene_count: int = 3  # scenes Claude writes per search; a request's scene_count overrides it
    rerank_effort: str = "medium"
    rerank_pool: int = 20  # top fused candidates Claude reranks; a request's rerank_pool overrides it
    verdict_effort: str = "low"
    rerank_provider: str = "jev"  # jev | claude; a request's rerank_provider overrides it
    rerank_excerpts: int = 3  # best-matching chapter chunks shown to the reranker per candidate (0 = none)
    rerank_excerpt_chars: int = 600  # each excerpt is cut to this many characters
    jev_rerank_batch: int = 10  # candidates per Jev request (batches run concurrently)
    jev_rerank_drop_probability: float = 0.6  # drop a candidate whose probability of "Does not fit" is at least this
    rerank_reasons: bool = True  # Jev gives no text: Claude writes one-line reasons for the returned results only
    reason_model: str = "claude-haiku-5-5"
    reason_effort: str = "low"

    voyage_model: str = "voyage-4-large"
    embedding_dim: int = 1024
    voyage_price_per_mtok: float = 0.12  # estimate; override if pricing changes
    # https://docs.typesafe.ai/models: input only; no per-call or output charge.
    jev_price_per_mtok_input: float = Field(default=0.042, ge=0)

    recipe_model: str = "claude-sonnet-5-5"  # writes / repairs site recipes (link crawls)
    recipe_effort: str = "medium"
    decision_model: str = "claude-haiku-5-5"  # Claude fallback for the link-crawl Jev decisions
    decision_effort: str = "low"
    link_first_chapters_judged: int = 3  # chapters per novel always run through the content decision (c)
    link_max_listing_pages: int = Field(default=5000, ge=1)  # runaway guards; hitting one pauses with a checkpoint
    link_max_novels: int = Field(default=50000, ge=1)
    link_max_toc_pages: int = 200
    link_max_page_bytes: int = 3_000_000  # response size cap for crawled pages
    link_max_attempts: int = 3  # times a job may be reclaimed after its worker died before it is failed
    link_consecutive_failures: int = 5  # chapter/novel fetch failures in a row before the job fails
    link_consecutive_empty_chapters: int = Field(default=5, ge=1)  # empty chapter bodies in a row before the recipe is treated as broken

    link_refresh_urls: str = ""  # opt-in recurring refresh: comma-separated listing URLs, each crawled as a site job (empty = off)
    link_refresh_hours: float = Field(default=24.0, gt=0)  # minimum time between refresh jobs for one URL
    link_refresh_chapters_per_novel: int = Field(default=3, ge=1)

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
