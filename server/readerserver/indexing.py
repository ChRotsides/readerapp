"""Indexing: synopsis embeddings (for ER kNN), work profile embeddings, sample-chapter chunk vectors.
Chapter chunks are stored alongside their vectors."""
import hashlib
import logging
import re

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from .ai.embeddings import Embedder
from .ai.spend import BudgetExceeded
from .config import Settings
from .models import ProfileEmbedding, SampleEmbedding, SourceRecord, Work, WorkLink
from .text import approx_tokens

log = logging.getLogger(__name__)
CHUNK_TARGET_TOKENS = 700
CHUNK_MAX_TOKENS = 1000


def profile_text(w: Work) -> str:
    parts = [f"Title: {w.title}"]
    if w.native_title:
        parts.append(f"Original title: {w.native_title}")
    if w.aliases:
        parts.append("Also known as: " + "; ".join(w.aliases[:10]))
    if w.authors:
        parts.append("Author: " + ", ".join(w.authors[:4]))
    if w.genres:
        parts.append("Genres: " + ", ".join(w.genres))
    if w.tags:
        parts.append("Tags: " + ", ".join(w.tags[:30]))
    if w.synopsis:
        parts.append("Synopsis: " + w.synopsis.strip())
    return "\n".join(parts)


def synopsis_input(r: SourceRecord) -> str:
    return f"{r.title}\n{r.synopsis}"


def legacy_chunk_text(text: str, target: int = 700, hard_max: int = 1000) -> list[str]:
    """Frozen pre-text-storage chunker, used to reconstruct legacy chapter inputs.

    Keep this implementation and its defaults unchanged when evolving chunk_text.
    Its sentence splitting could leave oversized chunks; those cannot be backfilled safely.
    """
    paras = [p.strip() for p in re.split(r"\n\s*\n|\r\n\s*\r\n", text) if p.strip()]
    if len(paras) <= 1:
        paras = [p.strip() for p in text.split("\n") if p.strip()]
    units: list[str] = []
    for p in paras:
        if approx_tokens(p) <= hard_max:
            units.append(p)
        else:
            units.extend(s.strip() for s in re.split(r"(?<=[.!?。！？])\s*", p) if s.strip())
    chunks, cur, cur_t = [], [], 0
    for u in units:
        t = approx_tokens(u)
        if cur and cur_t + t > target:
            chunks.append("\n".join(cur))
            cur, cur_t = [], 0
        cur.append(u)
        cur_t += t
    if cur:
        chunks.append("\n".join(cur))
    return chunks


def chunk_text(text: str, target: int = CHUNK_TARGET_TOKENS, hard_max: int = CHUNK_MAX_TOKENS) -> list[str]:
    """Paragraph/sentence aligned chunks, splitting unbroken oversized sentences as well."""
    if target < 1 or hard_max < 1:
        raise ValueError("Chunk token limits must be positive")
    chunks = legacy_chunk_text(text, target, hard_max)
    out = []
    for chunk in chunks:
        # A UTF-8 byte bound also keeps unusual scripts safely below the provider context limit.
        while approx_tokens(chunk) > hard_max or len(chunk.encode()) > hard_max * 4:
            end = min(len(chunk), hard_max)
            while approx_tokens(chunk[:end]) > hard_max or len(chunk[:end].encode()) > hard_max * 4:
                end //= 2
            out.append(chunk[:end])
            chunk = chunk[end:]
        if chunk:
            out.append(chunk)
    return out


def matching_chunk_inputs(rows: list[SampleEmbedding], chunks: list[str], model: str) -> bool:
    """Exact stored text is the only safe match for inputs without a chapter hash."""
    return bool(rows) and len(rows) == len(chunks) and all(
        row.chunk_index == i and row.model == model and row.chunk_text == chunk
        for i, (row, chunk) in enumerate(zip(rows, chunks, strict=True))
    )


def backfill_chunk_inputs(rows: list[SampleEmbedding], chunks: list[str], digest: str, model: str) -> bool:
    """Validate the complete frozen legacy layout before assigning any missing text.

    Small byte-bounded inputs could not have hit Voyage's 32k truncation limit.
    A source hash, contiguous indices and token counts are all required. Never
    infer input identity from indices or token counts alone.
    """
    if not rows or len(rows) != len(chunks):
        return False
    for i, (row, chunk) in enumerate(zip(rows, chunks, strict=True)):
        if (row.chapter_hash != digest or row.model != model or row.chunk_index != i
                or row.token_count != approx_tokens(chunk) or len(chunk.encode()) > 4000
                or (row.chunk_text is not None and row.chunk_text != chunk)):
            return False
    for row, chunk in zip(rows, chunks, strict=True):
        if row.chunk_text is None:
            row.chunk_text = chunk
    return True


async def embed_synopses(session: AsyncSession, embedder: Embedder, limit: int = 256) -> int:
    """Synopsis vectors for records lacking one; needed *before* resolution for the kNN candidate step."""
    recs = list((await session.scalars(
        select(SourceRecord).where(SourceRecord.synopsis_embedding.is_(None), SourceRecord.synopsis.is_not(None)).limit(limit)
    )).all())
    if not recs:
        return 0
    try:
        vecs = await embedder.embed([synopsis_input(r) for r in recs], "document", "synopsis")
    except BudgetExceeded:
        log.warning("budget reached; skipping synopsis embeddings")
        return 0
    for r, v in zip(recs, vecs, strict=True):
        r.synopsis_embedding = v
    await session.commit()
    return len(recs)


async def embed_record_synopses(session: AsyncSession, embedder: Embedder, recs: list[SourceRecord]) -> int:
    """Synopsis vectors for exactly these records (the current resolution batch). Failures never raise: records left
    without a vector are deferred by the caller and retried later."""
    todo = [r for r in recs if r.synopsis and r.synopsis_embedding is None]
    if not todo:
        return 0
    try:
        vecs = await embedder.embed([synopsis_input(r) for r in todo], "document", "synopsis")
    except BudgetExceeded:
        log.warning("budget reached; synopsis embeddings deferred")
        return 0
    except Exception:
        log.exception("synopsis embedding failed; deferred")
        return 0
    for r, v in zip(todo, vecs, strict=True):
        r.synopsis_embedding = v
    await session.flush()
    return len(todo)


async def index_profiles(session: AsyncSession, embedder: Embedder, settings: Settings, limit: int = 256) -> int:
    """(Re)embed profiles of works touched by records with needs_index, when the profile text changed.
    Returns the number of works processed (0 = nothing left or deferred by the budget)."""
    work_ids = list((await session.scalars(
        select(WorkLink.work_id).join(SourceRecord, SourceRecord.id == WorkLink.source_record_id)
        .where(SourceRecord.needs_index.is_(True), WorkLink.undone_at.is_(None)).distinct().limit(limit)
    )).all())
    if not work_ids:
        return 0
    works = list((await session.scalars(select(Work).where(Work.id.in_(work_ids)))).all())
    existing = {p.work_id: p for p in (await session.scalars(select(ProfileEmbedding).where(ProfileEmbedding.work_id.in_(work_ids)))).all()}
    todo: list[tuple[Work, str, str]] = []
    for w in works:
        txt = profile_text(w)
        h = hashlib.sha256(txt.encode()).hexdigest()
        if w.id not in existing or existing[w.id].text_hash != h or existing[w.id].model != settings.voyage_model:
            todo.append((w, txt, h))
    n = 0
    if todo:
        try:
            vecs = await embedder.embed([t for _, t, _ in todo], "document", "profile")
        except BudgetExceeded:
            log.warning("budget reached; profile indexing deferred")
            return 0
        for (w, _, h), v in zip(todo, vecs, strict=True):
            if w.id in existing:
                existing[w.id].embedding, existing[w.id].text_hash, existing[w.id].model = v, h, settings.voyage_model
            else:
                session.add(ProfileEmbedding(work_id=w.id, text_hash=h, model=settings.voyage_model, embedding=v))
            n += 1
    await session.execute(
        SourceRecord.__table__.update().where(SourceRecord.id.in_(
            select(WorkLink.source_record_id).where(WorkLink.work_id.in_(work_ids), WorkLink.undone_at.is_(None))
        )).values(needs_index=False)
    )
    await session.commit()
    return len(work_ids)


async def index_sample_text(session: AsyncSession, embedder: Embedder, settings: Settings, rec: SourceRecord, chapters: list[str]) -> int:
    """Store catalog sample chunks and vectors, reusing only exactly matched inputs."""
    chunks: list[str] = []
    for ch in chapters:
        chunks.extend(chunk_text(ch))
    if not chunks:
        rec.samples_done = True
        await session.commit()
        return 0
    cond = (SampleEmbedding.source_record_id == rec.id, SampleEmbedding.chapter_index.is_(None))
    stored = list((await session.scalars(select(SampleEmbedding).where(*cond).order_by(SampleEmbedding.chunk_index))).all())
    if matching_chunk_inputs(stored, chunks, settings.voyage_model):
        rec.samples_done = True
        await session.commit()
        return 0
    # Legacy catalog samples have no source hash: indices/token counts alone cannot prove a match.
    vecs = await embedder.embed(chunks, "document", "sample")
    if len(vecs) != len(chunks):
        raise ValueError("Embedding count does not match chunk count")
    await session.execute(delete(SampleEmbedding).where(
        SampleEmbedding.source_record_id == rec.id, SampleEmbedding.chapter_index.is_(None)))  # chapter embeddings are kept
    for i, (c, v) in enumerate(zip(chunks, vecs, strict=True)):
        session.add(SampleEmbedding(source_record_id=rec.id, chunk_index=i, chunk_text=c, token_count=approx_tokens(c), model=settings.voyage_model, embedding=v))
    rec.samples_done = True
    await session.commit()
    return len(chunks)
