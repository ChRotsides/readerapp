from typing import Literal

from pydantic import BaseModel, Field


class SearchFilters(BaseModel):
    language: str | None = Field(default=None, description="Original language code: ja, ko, zh or en")
    status: Literal["ongoing", "completed", "hiatus", "cancelled"] | None = None
    min_chapters: int | None = None
    max_chapters: int | None = None
    include_tags: list[str] = Field(default_factory=list, description="Tags the novel MUST have (only if the user insists)")
    exclude_tags: list[str] = Field(default_factory=list, description="Tags the novel must NOT have")


class HydeOutput(BaseModel):
    filters: SearchFilters
    scenes: list[str] = Field(description="2-3 distinct hypothetical scenes (setup / signature trope / tone)")
    synopsis: str = Field(description="Hypothetical NovelUpdates-style synopsis")
    tags: list[str] = Field(description="Hypothetical NovelUpdates-style tag list")


class RerankItem(BaseModel):
    work_id: str
    reason: str = Field(description="One short line on why this novel fits the description")


class RerankOutput(BaseModel):
    ranking: list[RerankItem] = Field(description="Best match first; only ids from the candidate list")


class SearchRequest(BaseModel):
    description: str
    filters: SearchFilters | None = None
    scenes: list[str] | None = None
    synopsis: str | None = None
    tags: list[str] | None = None
    limit: int = Field(default=20, ge=1, le=50)


class Rating(BaseModel):
    source: str
    rating: float
    scale: float
    votes: int | None = None
    normalized: float  # 0-10


class SourceUrl(BaseModel):
    source: str
    url: str


class WorkOut(BaseModel):
    work_id: str
    title: str
    native_title: str | None = None
    aliases: list[str] = []
    authors: list[str] = []
    cover_url: str | None = None
    synopsis: str | None = None
    tags: list[str] = []
    genres: list[str] = []
    status: str | None = None
    chapter_count: int | None = None
    language: str | None = None
    start_year: int | None = None
    ratings: list[Rating] = []
    urls: list[SourceUrl] = []
    related: list[dict] = []


class SearchResult(WorkOut):
    score: float
    reason: str = ""
    matched_scenes: int = 0


class SearchResponse(BaseModel):
    results: list[SearchResult]
    scenes: list[str]
    synopsis: str
    tags: list[str]
    filters: SearchFilters
    reranked: bool
    notes: list[str] = []
