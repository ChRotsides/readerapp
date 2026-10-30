"""HyDE search: Claude hypotheticals -> Voyage query embeddings -> kNN (chunks + profiles) -> RRF fusion -> filters ->
Claude Sonnet rerank with one-line reasons."""
import json
import logging
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from ..ai.embeddings import Embedder
from ..ai.llm import LLM, LLMError
from ..ai.spend import BudgetExceeded
from ..config import Settings
from ..resolution.merge import canonicalize_tags
from ..resolution.resolver import load_tag_maps
from ..works import load_works
from .fusion import Fused, fuse
from .retrieval import filter_clauses, knn_chunks, knn_profiles
from .schemas import HydeOutput, RerankItem, RerankOutput, SearchFilters, SearchRequest, SearchResponse, SearchResult

log = logging.getLogger(__name__)
RERANK_POOL = 20
PROFILE_WEIGHTS = {"synopsis": 1.0}  # scene-vs-profile lists get 0.5 each

HYDE_SYSTEM = """You help find web novels / light novels. The user describes a novel they want to read.
Return:
- filters: only constraints the user actually stated (original language ja/ko/zh/en, status, chapter count range, tags they require or exclude). Leave everything else empty.
- scenes: 2 or 3 DISTINCT hypothetical scenes from such a novel, each ~150-220 words of web-novel prose (the kind of text found in an early chapter). Cover (1) the opening setup, (2) the signature trope/hook in action, (3) the overall tone/atmosphere. Do NOT use character names (use pronouns / roles), do not mention the user's request. Write the scenes in English even if the request is in another language.
- synopsis: a hypothetical NovelUpdates-style synopsis (60-120 words) of a novel matching the description.
- tags: 6-15 NovelUpdates-style tags/genres (e.g. "Transported to Another World", "Overpowered Protagonist", "Cultivation")."""

RERANK_SYSTEM = """You rerank candidate novels for a reader. Given the user's description and candidates (id, title, tags, synopsis),
return the candidates ordered best match first, each with a one-line reason (max ~25 words) grounded in the candidate's synopsis/tags.
Use only the provided ids. Drop candidates that clearly do not fit."""


class BudgetError(Exception):
    pass


class SearchService:
    def __init__(self, settings: Settings, embedder: Embedder, llm: LLM):
        self.settings, self.embedder, self.llm = settings, embedder, llm

    async def generate_hyde(self, description: str) -> HydeOutput:
        return await self.llm.parse(
            model=self.settings.hyde_model, system=HYDE_SYSTEM, user=description, schema=HydeOutput,
            effort=self.settings.hyde_effort, max_tokens=8000, purpose="hyde",
        )

    async def search(self, session: AsyncSession, req: SearchRequest) -> SearchResponse:
        notes: list[str] = []
        scenes, synopsis, tags, filters = req.scenes, req.synopsis, req.tags, req.filters
        supplied = bool([x for x in (scenes or []) if x.strip()]) and bool(synopsis)
        if not supplied:  # supplied scenes + synopsis = caller-provided hypotheticals: no HyDE call, default filters
            try:
                if self.llm is None:
                    raise LLMError("Claude is not configured")
                h = await self.generate_hyde(req.description)
            except BudgetExceeded as e:
                notes.append(f"monthly AI budget reached: hypothetical generation skipped ({e})")
                h = None
            except LLMError as e:  # incl. translated provider errors (network, rate limit, 5xx)
                notes.append(f"hypothetical generation unavailable ({e}); using the description for retrieval only")
                h = None
            if h is not None:
                scenes = scenes or h.scenes[:3]
                synopsis = synopsis or h.synopsis
                tags = tags if tags is not None else h.tags
                filters = filters or h.filters
        scenes = [s for s in (scenes or []) if s.strip()][:4]
        synopsis = synopsis or req.description
        tags = tags or []
        filters = filters or SearchFilters()

        mapping, vocab = await load_tag_maps(session)
        inc = canonicalize_tags(filters.include_tags, "*", mapping, vocab)
        exc = canonicalize_tags(filters.exclude_tags, "*", mapping, vocab)
        clauses = filter_clauses(filters, inc, exc)

        # Synopsis is combined with its tag list, as profiles are embedded that way.
        syn_query = synopsis + ("\nTags: " + ", ".join(tags) if tags else "")
        vecs = await self.embedder.embed([*scenes, syn_query], "query", "search", patient=False)
        scene_vecs, syn_vec = vecs[:-1], vecs[-1]

        chunk_lists = [await knn_chunks(session, v, clauses) for v in scene_vecs]
        profile_lists = {"synopsis": await knn_profiles(session, syn_vec, clauses)}
        weights = dict(PROFILE_WEIGHTS)
        for i, v in enumerate(scene_vecs):
            profile_lists[f"scene{i}"] = await knn_profiles(session, v, clauses)
            weights[f"scene{i}"] = 0.5
        fused = fuse(chunk_lists, profile_lists, weights)
        pool = fused[: max(RERANK_POOL, req.limit)]
        if not pool:
            return SearchResponse(results=[], scenes=scenes, synopsis=synopsis, tags=tags, filters=filters, reranked=False, notes=notes)

        details = await load_works(session, [uuid.UUID(f.work_id) for f in pool])
        reasons: dict[str, str] = {}
        order = [f.work_id for f in pool]
        reranked = False
        cands = [details[uuid.UUID(f.work_id)] for f in pool if uuid.UUID(f.work_id) in details]
        if self.llm is None:
            notes.append("rerank unavailable (Claude is not configured); showing retrieval order")
        else:
            try:
                rr = await self._rerank(req.description, scenes, cands)
                in_pool = set(order)
                valid: list[RerankItem] = []
                seen: set[str] = set()
                for i in rr.ranking:
                    if i.work_id in in_pool and i.work_id not in seen:
                        seen.add(i.work_id)
                        valid.append(i)
                if rr.ranking and not valid:
                    notes.append("rerank returned no usable ids; showing retrieval order")
                else:
                    # A successful verdict is respected, including an empty or partial list (omitted = dropped).
                    reasons = {i.work_id: i.reason for i in valid}
                    order = [i.work_id for i in valid]
                    reranked = True
            except BudgetExceeded:
                notes.append("monthly AI budget reached: showing retrieval order without rerank")
            except LLMError as e:
                notes.append(f"rerank unavailable ({e}); showing retrieval order")

        top = max((f.score for f in pool), default=1.0) or 1.0
        by_id: dict[str, Fused] = {f.work_id: f for f in pool}
        results = []
        for wid in order[: req.limit]:
            d = details.get(uuid.UUID(wid))
            if d is None:
                continue
            f = by_id[wid]
            results.append(SearchResult(**d.model_dump(), score=round(f.score / top, 4), reason=reasons.get(wid, ""), matched_scenes=f.scene_matches))
        return SearchResponse(results=results, scenes=scenes, synopsis=synopsis, tags=tags, filters=filters, reranked=reranked, notes=notes)

    async def _rerank(self, description: str, scenes: list[str], cands) -> RerankOutput:
        payload = {
            "description": description,
            "candidates": [
                {"id": c.work_id, "title": c.title, "aliases": c.aliases[:4], "tags": c.tags[:15], "genres": c.genres[:5],
                 "status": c.status, "chapters": c.chapter_count, "synopsis": (c.synopsis or "")[:600]}
                for c in cands
            ],
        }
        return await self.llm.parse(
            model=self.settings.rerank_model, system=RERANK_SYSTEM, user=json.dumps(payload, ensure_ascii=False),
            schema=RerankOutput, effort=self.settings.rerank_effort, max_tokens=12000, purpose="rerank",
        )
