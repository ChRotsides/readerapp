"""kNN retrieval over sample chunks and profiles, with metadata filters applied in SQL."""
import uuid
from dataclasses import dataclass

from sqlalchemy import column, func, select, text, true, values
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import ProfileEmbedding, SampleEmbedding, Work, WorkLink
from .schemas import SearchFilters

CHUNK_K = 300  # works returned per query
CHUNK_POOL = 1000  # nearest chunks fetched (HNSW kNN) before aggregating per work; grown while too few works are covered
CHUNK_POOL_MAX = 16_000  # ceiling of that growth (pgvector's iterative scan stops at hnsw.max_scan_tuples = 20000)
MIN_WORKS = 100  # distinct works the pool should cover (fusion + rerank only use the top ~20 of each list)
TOP_CHUNKS = 3  # a work scores the mean similarity of its best TOP_CHUNKS chunks in the pool
PROFILE_K = 100


def filter_clauses(f: SearchFilters, include: list[str], exclude: list[str]) -> list:
    c = []
    if f.language:
        c.append(Work.language == f.language)
    if f.status:
        c.append(Work.status == f.status)
    if f.min_chapters is not None:
        c.append(Work.chapter_count >= f.min_chapters)
    if f.max_chapters is not None:
        c.append(Work.chapter_count <= f.max_chapters)
    if include:
        c.append(Work.tags.contains(include))
    if exclude:
        c.append(~Work.tags.overlap(exclude))
    return c


async def _iterative(session: AsyncSession, ef_search: int = 100) -> None:
    try:
        await session.execute(text("SET LOCAL hnsw.iterative_scan = relaxed_order"))
        await session.execute(text(f"SET LOCAL hnsw.ef_search = {min(max(int(ef_search), 100), 1000)}"))  # pgvector caps it at 1000
    except Exception:  # older pgvector: no iterative scan
        pass


def _dedupe(rows) -> list[str]:
    seen: list[str] = []
    for (wid,) in rows:
        s = str(wid)
        if s not in seen:
            seen.append(s)
    return seen


async def knn_chunk_scores(session: AsyncSession, vec: list[float], clauses: list, k: int = CHUNK_K, pool: int = CHUNK_POOL,
                           top: int = TOP_CHUNKS, min_works: int = MIN_WORKS, ceiling: int = CHUNK_POOL_MAX) -> list[tuple[str, float]]:
    """(work id, score) ranked by score.

    The HNSW index supplies the `pool` nearest chunks; each work scores the mean cosine similarity of its `top` best chunks
    among them, so a long novel with thousands of chapter chunks cannot win on volume. A work with fewer than `top` chunks
    in the pool has the missing ones counted at the pool's cutoff similarity (the best a chunk outside the pool could be),
    so a one-chunk work is not favoured over a work that is consistently good. Every chapter of a crawled novel is
    stored, so a couple of long novels can fill the pool: while fewer than `min_works` distinct works are covered and the
    pool was full, it grows 4x (with a matching hnsw.ef_search) up to `ceiling`. All in SQL; only `k` rows come back."""
    while True:
        await _iterative(session, pool)
        dist = SampleEmbedding.embedding.cosine_distance(vec)
        nearest = (select(WorkLink.work_id.label("work_id"), dist.label("dist"))
                   .join(SampleEmbedding, SampleEmbedding.source_record_id == WorkLink.source_record_id)
                   .join(Work, Work.id == WorkLink.work_id)
                   .where(WorkLink.undone_at.is_(None), *clauses)
                   .order_by(dist).limit(pool).subquery())
        ranked = select(nearest.c.work_id, nearest.c.dist,
                        func.row_number().over(partition_by=nearest.c.work_id, order_by=nearest.c.dist).label("rn"),
                        func.max(nearest.c.dist).over().label("worst"),
                        func.count().over().label("pool_rows")).subquery()
        n = func.count()
        score = ((func.sum(1 - ranked.c.dist) + (top - n) * (1 - func.max(ranked.c.worst))) / top).label("score")
        q = (select(ranked.c.work_id, score, func.max(ranked.c.pool_rows)).where(ranked.c.rn <= top).group_by(ranked.c.work_id)
             .order_by(score.desc(), ranked.c.work_id))
        rows = (await session.execute(q)).all()
        pool_rows = max((r[2] for r in rows), default=0)
        if len(rows) >= min_works or pool_rows < pool or pool >= ceiling:  # enough works, or every chunk is already in the pool
            return [(str(wid), float(sc)) for wid, sc, _ in rows[:k]]
        pool = min(pool * 4, ceiling)


async def knn_chunks(session: AsyncSession, vec: list[float], clauses: list, k: int = CHUNK_K) -> list[str]:
    """Ranked distinct work ids by the mean of their top-3 matching chunks."""
    return [wid for wid, _ in await knn_chunk_scores(session, vec, clauses, k)]


async def knn_profiles(session: AsyncSession, vec: list[float], clauses: list, k: int = PROFILE_K) -> list[str]:
    await _iterative(session)
    dist = ProfileEmbedding.embedding.cosine_distance(vec)
    q = select(ProfileEmbedding.work_id).join(Work, Work.id == ProfileEmbedding.work_id).where(*clauses).order_by(dist).limit(k)
    return _dedupe((await session.execute(q)).all())


@dataclass
class Excerpt:
    chapter_index: int | None
    chunk_index: int
    text: str
    similarity: float


async def work_excerpts(session: AsyncSession, work_ids: list[str], vecs: list[list[float]], k: int = 3) -> dict[str, list[Excerpt]]:
    """For each work, its `k` chunks (with stored text) closest to any of `vecs` (similarity = 1 - the smallest cosine
    distance to any vec), best first. One query: a LATERAL scan of each work's own chunks over its active records, exact
    (no ANN). Works without chunk text are simply absent."""
    if not work_ids or not vecs or k <= 0:
        return {}
    ids = values(column("work_id", UUID(as_uuid=True)), name="w").data([(uuid.UUID(w),) for w in work_ids])
    dist = func.least(*[SampleEmbedding.embedding.cosine_distance(v) for v in vecs]) if len(vecs) > 1 else SampleEmbedding.embedding.cosine_distance(vecs[0])
    best = (select(SampleEmbedding.chapter_index, SampleEmbedding.chunk_index, SampleEmbedding.chunk_text, dist.label("dist"))
            .join(WorkLink, WorkLink.source_record_id == SampleEmbedding.source_record_id)
            .where(WorkLink.work_id == ids.c.work_id, WorkLink.undone_at.is_(None), SampleEmbedding.chunk_text.is_not(None))
            .order_by(dist).limit(k).lateral("best"))
    q = select(ids.c.work_id, best.c.chapter_index, best.c.chunk_index, best.c.chunk_text, best.c.dist).select_from(ids.join(best, true()))
    out: dict[str, list[Excerpt]] = {}
    for wid, ch, ci, txt, d in (await session.execute(q)).all():
        out.setdefault(str(wid), []).append(Excerpt(ch, ci, txt, 1 - float(d)))
    for lst in out.values():
        lst.sort(key=lambda e: -e.similarity)
    return out


COVERAGE_POOL = 200  # nearest candidate chunks per seed chunk
EARLY_CHAPTERS = 10  # candidates: catalog samples and chapters below this index (beginnings vs beginnings); literal in the SQL below
# Must match the predicate of ix_sample_embeddings_early_hnsw textually, as a literal (a bind param would stop the planner using it).
EARLY_CLAUSE = text(f"(sample_embeddings.chapter_index IS NULL OR sample_embeddings.chapter_index < {EARLY_CHAPTERS})")
DUPLICATE_SIM = 0.97  # a work this similar on at least half the seed chunks is the same novel


async def coverage_scores(session: AsyncSession, seed_vecs: list[list[float]], clauses: list, exclude_records: list[int] | None = None,
                          k: int = 100, pool: int = COVERAGE_POOL, dup_sim: float = DUPLICATE_SIM) -> tuple[list[tuple[str, float]], set[str]]:
    """((work id, score) ranked, near-duplicate work ids): how much of the seed's chunks a work covers. One kNN query per seed
    chunk, run sequentially on the session, over candidate chunks from the first chapters gives each its pool of `pool` rows
    (the vector is a bound parameter, as in knn_chunk_scores, so it is parsed once per query; the planner picks the plan, an
    exact scan is fine at this corpus size and the HNSW index is used only if the planner chooses it). The pool's last row's
    similarity is the cutoff c_i (the minimum over ALL returned chunks, so a short pool never falls back to 0; generic
    chunks have a high one) and a work contributes max(0, its best similarity in the pool - c_i). The score is the mean
    contribution over ALL seed chunks (missing = 0), so covering many distinct seed chunks beats one excellent match. Works
    whose best similarity is >= dup_sim on at least half of the seed chunks are the seed novel itself (e.g. an unresolved
    copy) and are returned separately, not ranked."""
    if not seed_vecs:
        return [], set()
    await _iterative(session, pool)
    totals: dict[str, float] = {}
    dups: dict[str, int] = {}
    for vec in seed_vecs:
        dist = SampleEmbedding.embedding.cosine_distance(vec)
        q = (select(WorkLink.work_id, dist.label("dist"))
             .join(SampleEmbedding, SampleEmbedding.source_record_id == WorkLink.source_record_id)
             .join(Work, Work.id == WorkLink.work_id)
             .where(WorkLink.undone_at.is_(None), EARLY_CLAUSE, *clauses))
        if exclude_records:
            q = q.where(SampleEmbedding.source_record_id.not_in(exclude_records))
        rows = (await session.execute(q.order_by(dist).limit(pool))).all()
        if not rows:
            continue
        best: dict[str, float] = {}
        cutoff = 1.0
        for wid, d in rows:
            sim = 1 - float(d)
            cutoff = min(cutoff, sim)  # the pool's weakest chunk, before rows collapse by work
            best[str(wid)] = max(best.get(str(wid), -1.0), sim)
        for wid, b in best.items():
            totals[wid] = totals.get(wid, 0.0) + max(0.0, b - cutoff)
            dups[wid] = dups.get(wid, 0) + (b >= dup_sim)
    dupset = {w for w, n in dups.items() if n * 2 >= len(seed_vecs)}
    ranked = sorted(((w, t / len(seed_vecs)) for w, t in totals.items() if t > 0 and w not in dupset), key=lambda x: (-x[1], x[0]))
    return ranked[:k], dupset
