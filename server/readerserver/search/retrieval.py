"""kNN retrieval over sample chunks and profiles, with metadata filters applied in SQL."""
from sqlalchemy import and_, func, select, text
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
