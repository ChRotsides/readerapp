"""HyDE search: Claude hypotheticals -> Voyage query embeddings -> kNN (chunks + profiles) -> RRF fusion -> filters ->
rerank of the top candidates with chapter excerpts: Jev `score` questions (default; Claude Haiku then writes one-line reasons
for the returned results) or a Claude Sonnet ranking with reasons; falls back Jev -> Claude -> retrieval order."""
import asyncio
import json
import logging
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from ..ai.embeddings import Embedder
from ..ai.jev import JevClient, JevScore
from ..ai.llm import LLM, LLMError
from ..ai.spend import BudgetExceeded
from ..config import Settings
from ..resolution.merge import canonicalize_tags
from ..resolution.resolver import load_tag_maps
from ..works import load_works
from .fusion import Fused, fuse
from .retrieval import Excerpt, filter_clauses, knn_chunks, knn_profiles, work_excerpts
from .schemas import MAX_RERANK_POOL, MAX_SCENES, HydeOutput, ReasonsOutput, RerankItem, RerankOutput, SearchFilters, SearchRequest, SearchResponse, SearchResult

log = logging.getLogger(__name__)
PROFILE_WEIGHTS = {"synopsis": 1.0}  # scene-vs-profile lists get 0.5 each

HYDE_SYSTEM = """You help find web novels / light novels. The user describes a novel they want to read.
Return:
- filters: only constraints the user actually stated (original language ja/ko/zh/en, status, chapter count range, tags they require or exclude). Leave everything else empty.
- scenes: exactly {n} DISTINCT hypothetical scene(s) from such a novel, each ~150-220 words of web-novel prose (the kind of text found in an early chapter). Cover, in this order: the opening setup, the signature trope/hook in action, the overall tone/atmosphere, then other characteristic moments (a key relationship, a turning point or power-up, a typical mid-story chapter). Do NOT use character names (use pronouns / roles), do not mention the user's request. Write the scenes in English even if the request is in another language.
- synopsis: a hypothetical NovelUpdates-style synopsis (60-120 words) of a novel matching the description.
- tags: 6-15 NovelUpdates-style tags/genres (e.g. "Transported to Another World", "Overpowered Protagonist", "Cultivation")."""

RERANK_SYSTEM = """You rerank candidate novels for a reader. Given the user's description, hypothetical scenes illustrating what they
want, and candidates (id, title, tags, synopsis, matched_scenes = how many of the scenes retrieval matched this candidate
with, and excerpts = actual chapter passages that best matched the scenes), return the candidates ordered best match first,
each with a one-line reason (max ~25 words) grounded in the candidate's synopsis/tags/excerpts. The scenes only illustrate
the wanted kind of story; do not require their details. Candidates without excerpts have none stored: judge them on
synopsis and tags. Use only the provided ids. Drop candidates that clearly do not fit."""

REASON_SYSTEM = """You explain search results to a reader. Given the user's description, hypothetical scenes illustrating what they
want, and ranked candidate novels (id, title, tags, synopsis, excerpts = actual chapter passages), write for EACH candidate
id one short reason (max ~25 words) why it fits the description, grounded in its synopsis/tags/excerpts. Use only the provided ids."""

REASON_SIMILAR_SYSTEM = """You explain "similar novels" results to a reader. `description` names the novel the reader is reading and its synopsis;
`seed_excerpts` are actual passages from it. Given ranked candidate novels (id, title, tags, synopsis, excerpts = actual chapter passages),
write for EACH candidate id one short reason (max ~25 words) why it is similar to that novel (premise, tone, kind of scenes),
grounded in the candidate's synopsis/tags/excerpts. Use only the provided ids."""

RERANK_SIMILAR_SYSTEM = """You rerank candidate novels that may be similar to a novel the reader is reading. `description` names that novel and its
synopsis, `seed_excerpts` are actual passages from it. Candidates have id, title, tags, synopsis and excerpts = actual chapter passages
that best matched the seed's text. Return the
candidates ordered most similar first (same kind of story: premise, hook, tone, characteristic moments; not just shared genre),
each with a one-line reason (max ~25 words) grounded in the candidate's synopsis/tags/excerpts. Candidates without excerpts have
none stored: judge them on synopsis and tags. Use only the provided ids. Drop candidates that clearly are not similar."""

RUBRIC = ["Does not fit", "Weak fit", "Partial fit", "Good fit", "Excellent fit"]
JEV_INSTRUCTIONS = (
    "Rate how well this candidate novel fits what the reader describes in `description`. Judge from the candidate's "
    "synopsis, tags and chapter excerpts; `scenes` are hypothetical scenes that illustrate the kind of story the reader wants "
    "(do not require their specific details). A candidate with no excerpts should be judged on synopsis and tags alone."
)
JEV_SIMILAR_INSTRUCTIONS = (
    "Rate how similar this candidate novel is to the novel the reader is reading, described in `description` (title and synopsis); "
    "`seed_excerpts` are actual passages from that novel. Judge "
    "similarity of premise, hook, tone and kind of scenes from the candidate's synopsis, tags and chapter excerpts, not just shared genre. "
    "A candidate with no excerpts should be judged on synopsis and tags alone."
)


def fit_value(sc: JevScore) -> float:
    """Jev score -> 0-1 fit. Uses the rubric probabilities when they cover the levels, else the 0-based score."""
    n = len(RUBRIC)
    probs = {k: v for k, v in sc.probabilities.items() if k in RUBRIC}
    tot = sum(probs.values())
    if probs and tot > 0:
        return max(0.0, min(1.0, sum(RUBRIC.index(k) * v for k, v in probs.items()) / tot / (n - 1)))
    return max(0.0, min(1.0, sc.score / (n - 1)))


def cand_info(c, matched: int | None = None, excerpts: list[Excerpt] | None = None) -> dict:
    d = {"title": c.title, "aliases": c.aliases[:4], "tags": c.tags[:15], "genres": c.genres[:5], "status": c.status,
         "chapters": c.chapter_count, "synopsis": (c.synopsis or "")[:600], "excerpts": [e.text for e in excerpts or []]}
    if matched is not None:
        d["matched_scenes"] = matched
    return d


class BudgetError(Exception):
    pass


class SearchService:
    def __init__(self, settings: Settings, embedder: Embedder, llm: LLM, jev: JevClient | None = None):
        self.settings, self.embedder, self.llm, self.jev = settings, embedder, llm, jev

    def scene_count(self, req: SearchRequest) -> int:
        """Scenes Claude writes: the request's scene_count, else the HYDE_SCENE_COUNT setting."""
        return max(1, min(MAX_SCENES, req.scene_count or self.settings.hyde_scene_count))

    def rerank_pool(self, req: SearchRequest) -> int:
        """Top fused candidates Claude reranks: the request's rerank_pool, else the RERANK_POOL setting (at least `limit`)."""
        return max(req.limit, min(MAX_RERANK_POOL, req.rerank_pool or self.settings.rerank_pool))

    def rerank_provider(self, req: SearchRequest) -> str:
        p = req.rerank_provider or self.settings.rerank_provider
        return p if p in ("jev", "claude") else "jev"

    async def generate_hyde(self, description: str, n: int = 3) -> HydeOutput:
        return await self.llm.parse(
            model=self.settings.hyde_model, system=HYDE_SYSTEM.replace("{n}", str(n)), user=description, schema=HydeOutput,
            effort=self.settings.hyde_effort, max_tokens=8000, purpose="hyde",
        )

    async def search(self, session: AsyncSession, req: SearchRequest) -> SearchResponse:
        notes: list[str] = []
        n_scenes = self.scene_count(req)
        scenes, synopsis, tags, filters = req.scenes, req.synopsis, req.tags, req.filters
        supplied = bool([x for x in (scenes or []) if x.strip()]) and bool(synopsis)
        if not supplied:  # supplied scenes + synopsis = caller-provided hypotheticals: no HyDE call, default filters
            try:
                if self.llm is None:
                    raise LLMError("Claude is not configured")
                h = await self.generate_hyde(req.description, n_scenes)
            except BudgetExceeded as e:
                notes.append(f"monthly AI budget reached: hypothetical generation skipped ({e})")
                h = None
            except LLMError as e:  # incl. translated provider errors (network, rate limit, 5xx)
                notes.append(f"hypothetical generation unavailable ({e}); using the description for retrieval only")
                h = None
            if h is not None:
                scenes = scenes or h.scenes[:n_scenes]
                synopsis = synopsis or h.synopsis
                tags = tags if tags is not None else h.tags
                filters = filters or h.filters
        scenes = [s for s in (scenes or []) if s.strip()][:MAX_SCENES]
        synopsis = synopsis or req.description
        tags = tags or []
        filters = filters or SearchFilters()

        clauses = await self.canonical_clauses(session, filters)
        return await self.run_core(session, req, description=req.description, scenes=scenes, synopsis=synopsis, tags=tags, filters=filters,
                                   clauses=clauses, notes=notes)

    async def canonical_clauses(self, session: AsyncSession, filters: SearchFilters) -> list:
        mapping, vocab = await load_tag_maps(session)
        return filter_clauses(filters, canonicalize_tags(filters.include_tags, "*", mapping, vocab), canonicalize_tags(filters.exclude_tags, "*", mapping, vocab))

    async def run_core(self, session: AsyncSession, req, *, description: str, scenes: list[str], synopsis: str, tags: list[str], filters: SearchFilters,
                       clauses: list, notes: list[str], extra_lists: dict[str, list[str]] | None = None, extra_weights: dict[str, float] | None = None,
                       exclude: set[str] | None = None, seed_excerpts: list[str] | None = None, seed_vecs: list[list[float]] | None = None) -> SearchResponse:
        """Shared by /search and /similar: embed the (hypothetical) scenes/synopsis, kNN chunk + profile lists, fuse them with any
        `extra_lists` (name -> ranked work ids, weights in `extra_weights`), drop `exclude`d works, rerank, write reasons.
        `description` is what the rerankers are told the reader wants; `seed_excerpts` (similar mode) are shown to them."""
        exclude = exclude or set()
        await session.rollback()  # reads so far are done: don't hold a transaction open across the embedding call
        # Synopsis is combined with its tag list, as profiles are embedded that way.
        syn_query = synopsis + ("\nTags: " + ", ".join(tags) if tags else "") if synopsis else ""
        texts = [*scenes, *([syn_query] if syn_query else [])]
        vecs = await self.embedder.embed(texts, "query", "search", patient=False) if texts else []
        scene_vecs = vecs[:len(scenes)]
        syn_vec = vecs[len(scenes)] if syn_query else None

        chunk_lists = [await knn_chunks(session, v, clauses) for v in scene_vecs]
        profile_lists = dict(extra_lists or {})
        weights = dict(extra_weights or {})
        if syn_vec is not None:
            profile_lists["synopsis"] = await knn_profiles(session, syn_vec, clauses)
            weights.update(PROFILE_WEIGHTS)
        for i, v in enumerate(scene_vecs):
            profile_lists[f"scene{i}"] = await knn_profiles(session, v, clauses)
            weights[f"scene{i}"] = 0.5
        fused = [f for f in fuse(chunk_lists, profile_lists, weights) if f.work_id not in exclude]
        pool = fused[: self.rerank_pool(req)]
        if not pool:
            return SearchResponse(results=[], scenes=scenes, synopsis=synopsis, tags=tags, filters=filters, reranked=False, notes=notes)

        details = await load_works(session, [uuid.UUID(f.work_id) for f in pool])
        cands = [details[uuid.UUID(f.work_id)] for f in pool if uuid.UUID(f.work_id) in details]
        by_id: dict[str, Fused] = {f.work_id: f for f in pool}
        excerpts: dict[str, list[Excerpt]] = {}
        if self.settings.rerank_excerpts > 0 and cands:
            ex_vecs = [*scene_vecs, *(seed_vecs or [])] or [v for v in [syn_vec] if v is not None]
            if ex_vecs:
                excerpts = await work_excerpts(session, [c.work_id for c in cands], ex_vecs, self.settings.rerank_excerpts)
                cut = self.settings.rerank_excerpt_chars
                for lst in excerpts.values():
                    for e in lst:
                        e.text = e.text[:cut]
        await session.rollback()  # no transaction held open across the rerank / reasons calls
        reasons: dict[str, str] = {}
        rscores: dict[str, float] = {}
        order = [f.work_id for f in pool]
        used: str | None = None
        provider = self.rerank_provider(req)
        if provider == "jev":
            if self.jev is None or not self.jev.available():
                if req.rerank_provider == "jev":  # the default provider being unconfigured is not worth a note on every search
                    notes.append("Jev is not configured; reranking with Claude")
            else:
                try:
                    order, rscores = await self._jev_rerank(description, scenes, cands, by_id, excerpts, seed_excerpts)
                    used = "jev"
                except BudgetExceeded:
                    notes.append("monthly AI budget reached: Jev rerank skipped")
                except Exception as e:  # network / HTTP / malformed answer
                    log.warning("Jev rerank failed: %s", e)
                    notes.append(f"Jev rerank unavailable ({type(e).__name__}); reranking with Claude")
        if used is None:
            if self.llm is None:
                notes.append("rerank unavailable (Claude is not configured); showing retrieval order")
            else:
                try:
                    rr = await self._rerank(description, scenes, cands, by_id, excerpts, seed_excerpts)
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
                        used = "claude"
                except BudgetExceeded:
                    notes.append("monthly AI budget reached: showing retrieval order without rerank")
                except LLMError as e:
                    notes.append(f"rerank unavailable ({e}); showing retrieval order")
        reranked = used is not None
        if used == "jev" and self.settings.rerank_reasons and self.llm is not None and order:
            reasons = await self._reasons(description, scenes, [details[uuid.UUID(w)] for w in order[: req.limit]], excerpts, notes, seed_excerpts)

        top = max((f.score for f in pool), default=1.0) or 1.0
        results = []
        for wid in order[: req.limit]:
            d = details.get(uuid.UUID(wid))
            if d is None:
                continue
            f = by_id[wid]
            rs = rscores.get(wid)
            results.append(SearchResult(**d.model_dump(), score=round(f.score / top, 4), reason=reasons.get(wid, ""), matched_scenes=f.scene_matches,
                                        rerank_score=None if rs is None else round(rs, 4)))
        return SearchResponse(results=results, scenes=scenes, synopsis=synopsis, tags=tags, filters=filters, reranked=reranked,
                              rerank_provider_used=used, notes=notes)

    async def _jev_rerank(self, description: str, scenes: list[str], cands, by_id: dict[str, Fused],
                          excerpts: dict[str, list[Excerpt]], seed_excerpts: list[str] | None = None) -> tuple[list[str], dict[str, float]]:
        """Jev scores every candidate (batches of jev_rerank_batch, concurrently). Returns (work ids best first with the
        likely misfits dropped, work id -> 0-1 fit). Any batch failing fails the whole rerank."""
        size = max(1, self.settings.jev_rerank_batch)
        keyed = [(f"c{i}", c) for i, c in enumerate(cands)]

        async def one(batch: list) -> dict[str, JevScore]:
            state = {"description": description, "candidates": {k: cand_info(c, excerpts=excerpts.get(c.work_id)) for k, c in batch}}
            if scenes or seed_excerpts is None:  # similar mode has no scenes: do not send an empty key
                state["scenes"] = scenes
            if seed_excerpts is not None:
                state["seed_excerpts"] = seed_excerpts
            return await self.jev.score({k: JEV_SIMILAR_INSTRUCTIONS if seed_excerpts is not None else JEV_INSTRUCTIONS for k, _ in batch}, RUBRIC, state, "rerank")

        parts = await asyncio.gather(*[one(keyed[i:i + size]) for i in range(0, len(keyed), size)])
        scored = {k: v for part in parts for k, v in part.items()}
        drop = self.settings.jev_rerank_drop_probability
        kept: list[tuple[float, float, str, float]] = []
        for k, c in keyed:
            sc = scored[k]
            if sc.probabilities.get(RUBRIC[0], 0.0) >= drop:
                continue
            fit = fit_value(sc)
            kept.append((fit, by_id[c.work_id].score, c.work_id, fit))
        kept.sort(key=lambda t: (-t[0], -t[1]))
        return [t[2] for t in kept], {t[2]: t[3] for t in kept}

    async def _reasons(self, description: str, scenes: list[str], shown, excerpts: dict[str, list[Excerpt]], notes: list[str],
                       seed_excerpts: list[str] | None = None) -> dict[str, str]:
        payload = {"description": description,
                   "candidates": [{"id": c.work_id, **cand_info(c, excerpts=excerpts.get(c.work_id))} for c in shown]}
        if scenes or seed_excerpts is None:
            payload["scenes"] = scenes
        if seed_excerpts is not None:
            payload["seed_excerpts"] = seed_excerpts
        try:
            out = await self.llm.parse(
                model=self.settings.reason_model, system=REASON_SIMILAR_SYSTEM if seed_excerpts is not None else REASON_SYSTEM, user=json.dumps(payload, ensure_ascii=False),
                schema=ReasonsOutput, effort=self.settings.reason_effort, max_tokens=4000, purpose="reasons",
            )
        except BudgetExceeded:
            notes.append("monthly AI budget reached: result reasons skipped")
            return {}
        except LLMError as e:
            notes.append(f"result reasons unavailable ({e})")
            return {}
        ids = {c.work_id for c in shown}
        return {i.work_id: i.reason for i in out.reasons if i.work_id in ids}

    async def _rerank(self, description: str, scenes: list[str], cands, by_id: dict[str, Fused], excerpts: dict[str, list[Excerpt]],
                      seed_excerpts: list[str] | None = None) -> RerankOutput:
        payload = {
            "description": description,
            "candidates": [{"id": c.work_id, **cand_info(c, by_id[c.work_id].scene_matches if seed_excerpts is None else None, excerpts.get(c.work_id))} for c in cands],
        }
        if scenes or seed_excerpts is None:
            payload["scenes"] = scenes
        if seed_excerpts is not None:
            payload["seed_excerpts"] = seed_excerpts
        return await self.llm.parse(
            model=self.settings.rerank_model, system=RERANK_SIMILAR_SYSTEM if seed_excerpts is not None else RERANK_SYSTEM, user=json.dumps(payload, ensure_ascii=False),
            schema=RerankOutput, effort=self.settings.rerank_effort, max_tokens=12000, purpose="rerank",
        )
