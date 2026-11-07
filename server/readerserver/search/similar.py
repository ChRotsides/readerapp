"""Find similar novels: seed (library novel or corpus work) -> two ranked lists built only from the seed's real text and metadata
(A chunk coverage of its stored chunks, B its profile kNN) -> RRF fusion -> the usual rerank, with the seed's own works excluded.
No Claude-written hypothetical scenes, synopsis or tags are generated or embedded on this path."""
import logging
import uuid
from dataclasses import dataclass, field

from sqlalchemy import exists, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..indexing import profile_text
from ..linkcrawl.crawler import source_id_for
from ..models import ProfileEmbedding, SampleEmbedding, SourceRecord, SourceTitle, Work, WorkLink
from ..text import normalize_title
from .pipeline import SearchService
from .retrieval import coverage_scores, knn_profiles
from .schemas import SearchFilters, SearchResponse, SimilarRequest

log = logging.getLogger(__name__)
MAX_SEED_CHUNKS = 64
SEED_EXCERPTS = 6  # real seed excerpts shown to the rerankers
SYNOPSIS_CHARS = 1500
DEFAULT_SEED_CHAPTERS = 5  # the lowest stored chapter indexes used when the request names none
COVERAGE_WEIGHT = 1.5
PROFILE_WEIGHT = 1.0


class SeedNotFound(Exception):
    pass


class SeedNotEmbedded(Exception):
    pass


@dataclass
class Seed:
    title: str
    synopsis: str | None
    tags: list[str]
    genres: list[str]
    record_ids: list[int]
    work_ids: list[uuid.UUID]
    chunks: list[tuple[str | None, list[float]]] = field(default_factory=list)  # (chunk_text, embedding)


def even(items: list, n: int) -> list:
    """`n` evenly spaced items (all of them when there are no more than n)."""
    return items if len(items) <= n else [items[i * len(items) // n] for i in range(n)]


def dedupe_chunks(rows: list) -> list:
    """Drop repeats (first kept, order preserved): a seed work with several source records (a crawled copy and a library copy)
    holds the same passages more than once. Identical chunk_text is a repeat; NULL text only when the embedding is identical too."""
    seen: set = set()
    out = []
    for t, v in rows:
        key = ("t", t) if t is not None else ("v", tuple(float(x) for x in v))
        if key not in seen:
            seen.add(key)
            out.append((t, v))
    return out


async def _seed_rows(s: AsyncSession, record_ids: list[int], chapters: list[int] | None) -> list:
    q = select(SampleEmbedding.chunk_text, SampleEmbedding.embedding).where(SampleEmbedding.source_record_id.in_(record_ids))
    q = q.order_by(SampleEmbedding.source_record_id, SampleEmbedding.chapter_index.nulls_last(), SampleEmbedding.chunk_index)
    if chapters is not None:
        return list((await s.execute(q.where(SampleEmbedding.chapter_index.in_(chapters)))).all())
    low = (select(SampleEmbedding.chapter_index).where(SampleEmbedding.source_record_id.in_(record_ids), SampleEmbedding.chapter_index.is_not(None))
           .distinct().order_by(SampleEmbedding.chapter_index).limit(DEFAULT_SEED_CHAPTERS))
    rows = list((await s.execute(q.where(SampleEmbedding.chapter_index.in_(low)))).all())
    if not rows:  # only catalog sample chunks
        rows = list((await s.execute(q.where(SampleEmbedding.chapter_index.is_(None)))).all())
    return rows


async def load_seed(s: AsyncSession, req: SimilarRequest) -> Seed:
    rec: SourceRecord | None = None
    work_ids: list[uuid.UUID] = []
    if req.library_url:
        rec = await s.scalar(select(SourceRecord).where(SourceRecord.source == "library", SourceRecord.source_id == source_id_for(req.library_url)))
        if rec is None:
            raise SeedNotEmbedded("seed has no embedded chapters")  # the app uploads chapters and retries
        work_ids = list((await s.scalars(select(WorkLink.work_id).where(WorkLink.source_record_id == rec.id, WorkLink.undone_at.is_(None)))).all())
    else:
        try:
            wid = uuid.UUID(req.work_id or "")
        except ValueError:
            raise SeedNotFound("work not found") from None
        if await s.get(Work, wid) is None:
            raise SeedNotFound("work not found")
        work_ids = [wid]
    rec_ids = set(await s.scalars(select(WorkLink.source_record_id).where(WorkLink.work_id.in_(work_ids), WorkLink.undone_at.is_(None)))) if work_ids else set()
    if rec is not None:
        rec_ids.add(rec.id)
    work = await s.get(Work, work_ids[0]) if work_ids else None
    title, synopsis, tags, genres = (work.title, work.synopsis, list(work.tags or []), list(work.genres or [])) if work else (rec.title, rec.synopsis, list(rec.tags or []), list(rec.genres or []))  # type: ignore[union-attr]
    seed = Seed(title, synopsis, tags, genres, sorted(rec_ids), work_ids)
    # req.chapters are the device's chapter indexes; other sources need not share that numbering, so a library seed reads its own
    # record first and only falls back to every linked record when that one yields nothing.
    scopes = [[rec.id], seed.record_ids] if rec is not None and seed.record_ids != [rec.id] else [seed.record_ids]
    rows: list = []
    for scope in scopes:
        rows = await _seed_rows(s, scope, req.chapters)
        if rows:
            break
    seed.chunks = [(t, [float(x) for x in v]) for t, v in even(dedupe_chunks(list(rows)), MAX_SEED_CHUNKS)]
    return seed


async def similar(svc: SearchService, s: AsyncSession, req: SimilarRequest) -> SearchResponse:
    """Raises SeedNotFound (404) / SeedNotEmbedded (409)."""
    seed = await load_seed(s, req)
    if not seed.chunks and (req.chapters is not None or not (seed.synopsis or "").strip()):
        raise SeedNotEmbedded("seed has no embedded chapters")
    notes: list[str] = []
    if not seed.chunks:
        notes.append("the seed has no embedded chapters for the requested chapters; matched on synopsis and tags only")
    syn = (seed.synopsis or "").strip()[:SYNOPSIS_CHARS]
    filters = req.filters or SearchFilters()
    clauses = await svc.canonical_clauses(s, filters)
    exclude = {str(w) for w in seed.work_ids}
    # The same novel under another Work (e.g. the library record is not resolved yet): same normalized title or alias.
    norm = normalize_title(seed.title)
    if norm:
        same = await s.scalars(select(WorkLink.work_id).join(SourceTitle, SourceTitle.source_record_id == WorkLink.source_record_id)
                               .where(SourceTitle.norm_title == norm, WorkLink.undone_at.is_(None)).distinct())
        exclude |= {str(w) for w in same}
    if exclude:
        clauses.append(Work.id.not_in([uuid.UUID(w) for w in exclude]))
    if req.hide_library:
        lib = (select(WorkLink.id).join(SourceRecord, SourceRecord.id == WorkLink.source_record_id)
               .where(WorkLink.work_id == Work.id, WorkLink.undone_at.is_(None), SourceRecord.source == "library"))
        clauses.append(~exists(lib))

    extra: dict[str, list[str]] = {}
    weights: dict[str, float] = {}
    # A: chunk coverage
    if seed.chunks:
        cov, dups = await coverage_scores(s, [v for _, v in seed.chunks], clauses, seed.record_ids)
        extra["coverage"], weights["coverage"] = [w for w, _ in cov], COVERAGE_WEIGHT
        exclude |= dups  # near-identical chunks: that is the seed novel itself
    # B: seed profile
    pvec = None
    for wid in seed.work_ids:
        pe = await s.get(ProfileEmbedding, wid)
        if pe is not None:
            pvec = [float(x) for x in pe.embedding]
            break
    if pvec is None and syn:
        await s.rollback()  # no transaction across the embedding call
        w = Work(title=seed.title, synopsis=seed.synopsis, tags=seed.tags, genres=seed.genres, aliases=[], authors=[])
        pvec = (await svc.embedder.embed([profile_text(w)], "document", "search", patient=False))[0]
    if pvec is not None:
        extra["seed_profile"], weights["seed_profile"] = await knn_profiles(s, pvec, clauses), PROFILE_WEIGHT

    # The seed's real excerpts go to the rerankers and, as vectors, pick each candidate's best-matching excerpts.
    cut = svc.settings.rerank_excerpt_chars
    shown = even(seed.chunks, SEED_EXCERPTS)
    sample = [t[:cut] for t, _ in even([c for c in seed.chunks if c[0]], SEED_EXCERPTS)]
    ex_vecs = [v for _, v in shown] or ([pvec] if pvec is not None else [])
    await s.rollback()  # no transaction across the rerank calls

    description = f"Novels similar to {seed.title}." + (f" {syn}" if syn else "")
    res = await svc.run_core(
        s, req, description=description, scenes=[], synopsis="", tags=[], filters=filters, clauses=clauses, notes=notes,
        extra_lists=extra, extra_weights=weights, exclude=exclude, seed_excerpts=sample, seed_vecs=ex_vecs)
    res.seed_title = seed.title
    return res
