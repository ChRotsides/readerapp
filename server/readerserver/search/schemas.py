from typing import Literal

from pydantic import BaseModel, Field, model_validator

MAX_SCENES = 8
MAX_RERANK_POOL = 100


class SearchFilters(BaseModel):
    language: str | None = Field(default=None, description="Original language code: ja, ko, zh or en")
    status: Literal["ongoing", "completed", "hiatus", "cancelled"] | None = None
    min_chapters: int | None = None
    max_chapters: int | None = None
    include_tags: list[str] = Field(default_factory=list, description="Tags the novel MUST have (only if the user insists)")
    exclude_tags: list[str] = Field(default_factory=list, description="Tags the novel must NOT have")


class HydeOutput(BaseModel):
    filters: SearchFilters
    scenes: list[str] = Field(description="The requested number of distinct hypothetical scenes (setup / signature trope / tone / other characteristic moments)")
    synopsis: str = Field(description="Hypothetical NovelUpdates-style synopsis")
    tags: list[str] = Field(description="Hypothetical NovelUpdates-style tag list")


class RerankItem(BaseModel):
    work_id: str
    reason: str = Field(description="One short line on why this novel fits the description")


class RerankOutput(BaseModel):
    ranking: list[RerankItem] = Field(description="Best match first; only ids from the candidate list")


class ReasonsOutput(BaseModel):
    reasons: list[RerankItem] = Field(description="One reason per given candidate id")


class SearchRequest(BaseModel):
    description: str
    scene_count: int | None = Field(default=None, ge=1, le=MAX_SCENES, description="Scenes Claude writes (default: HYDE_SCENE_COUNT)")
    rerank_pool: int | None = Field(default=None, ge=1, le=MAX_RERANK_POOL, description="Top candidates Claude reranks (default: RERANK_POOL)")
    rerank_provider: Literal["jev", "claude"] | None = Field(default=None, description="Reranker (default: RERANK_PROVIDER)")
    filters: SearchFilters | None = None
    scenes: list[str] | None = None
    synopsis: str | None = None
    tags: list[str] | None = None
    limit: int = Field(default=20, ge=1, le=50)


class SimilarRequest(BaseModel):
    """Find novels similar to one the user reads (a library novel or a corpus work); exactly one seed is required."""
    library_url: str | None = None
    work_id: str | None = None
    chapters: list[int] | None = Field(default=None, description="Seed chapter indexes (default: stored chapters < 5, else catalog samples)")
    hide_library: bool = True
    filters: SearchFilters | None = None
    scene_count: int | None = Field(default=None, ge=1, le=MAX_SCENES)  # accepted for old clients; ignored (similar writes no scenes)
    rerank_pool: int | None = Field(default=None, ge=1, le=MAX_RERANK_POOL)
    rerank_provider: Literal["jev", "claude"] | None = None
    limit: int = Field(default=20, ge=1, le=50)

    @model_validator(mode="after")
    def _one_seed(self):
        if bool(self.library_url) == bool(self.work_id):
            raise ValueError("exactly one of library_url / work_id is required")
        return self


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
    favorite: bool = False  # starred on this server (see api/favorites.py)


class SearchResult(WorkOut):
    score: float
    reason: str = ""
    matched_scenes: int = 0
    rerank_score: float | None = None  # Jev fit score normalized 0-1 (None when Claude or nothing reranked)


class SearchResponse(BaseModel):
    results: list[SearchResult]
    scenes: list[str]
    synopsis: str
    tags: list[str]
    filters: SearchFilters
    reranked: bool
    rerank_provider_used: str | None = None  # jev | claude when `reranked`
    notes: list[str] = []
    history_id: str | None = None  # id of the saved history entry (see api/searches.py)
    seed_title: str | None = None  # /similar only: the novel the results are similar to
