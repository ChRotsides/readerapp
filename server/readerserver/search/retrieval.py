"""kNN retrieval over sample chunks and profiles, with metadata filters applied in SQL."""
from sqlalchemy import and_, func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import ProfileEmbedding, SampleEmbedding, Work, WorkLink
from .schemas import SearchFilters

CHUNK_K = 300
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


async def _iterative(session: AsyncSession) -> None:
    try:
        await session.execute(text("SET LOCAL hnsw.iterative_scan = relaxed_order"))
        await session.execute(text("SET LOCAL hnsw.ef_search = 100"))
    except Exception:  # older pgvector: no iterative scan
        pass


def _dedupe(rows) -> list[str]:
    seen: list[str] = []
    for (wid,) in rows:
        s = str(wid)
        if s not in seen:
            seen.append(s)
    return seen


async def knn_chunks(session: AsyncSession, vec: list[float], clauses: list, k: int = CHUNK_K) -> list[str]:
    """Ranked distinct work ids by best-matching sample chunk."""
    await _iterative(session)
    dist = SampleEmbedding.embedding.cosine_distance(vec)
    q = (select(WorkLink.work_id)
         .join(SampleEmbedding, SampleEmbedding.source_record_id == WorkLink.source_record_id)
         .join(Work, Work.id == WorkLink.work_id)
         .where(WorkLink.undone_at.is_(None), *clauses)
         .order_by(dist).limit(k))
    return _dedupe((await session.execute(q)).all())


async def knn_profiles(session: AsyncSession, vec: list[float], clauses: list, k: int = PROFILE_K) -> list[str]:
    await _iterative(session)
    dist = ProfileEmbedding.embedding.cosine_distance(vec)
    q = select(ProfileEmbedding.work_id).join(Work, Work.id == ProfileEmbedding.work_id).where(*clauses).order_by(dist).limit(k)
    return _dedupe((await session.execute(q)).all())
