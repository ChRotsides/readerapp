"""Shared chapter embedding and passage storage for the crawler and library API."""
import hashlib
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Literal

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..ai.embeddings import Embedder
from ..config import Settings
from ..indexing import backfill_chunk_inputs, chunk_text, legacy_chunk_text
from ..models import SampleEmbedding
from ..text import approx_tokens

log = logging.getLogger(__name__)
Outcome = Literal["embedded", "unchanged", "rejected", "empty"]
TextSource = str | Callable[[], Awaitable[str | None]]
Gate = Callable[[str], Awaitable[bool]]


@dataclass
class ChapterOutcome:
    outcome: Outcome
    chunks: int = 0

    @property
    def embedded(self) -> bool:
        return self.outcome == "embedded"


async def embed_chapter_text(
    session: AsyncSession, embedder: Embedder, settings: Settings, record_id: int, chapter_index: int,
    source: TextSource, *, purpose: str, gate: Gate | None = None, patient: bool = True,
) -> ChapterOutcome:
    """Fetch -> (gate) -> chunk -> embed -> atomically store chunks and vectors.

    `source` is the chapter text, or an async callable that fetches it (None/blank -> "empty"). A chapter whose text
    hash (and embedding model) matches what is stored is "unchanged" when its input text is present or safely
    recoverable. Missing text with an unverifiable layout requires re-embedding. `gate(text)`
    decides whether the text is story prose; a rejected chapter ("rejected") has any earlier vectors removed. Chunks of a
    re-embedded chapter replace the old ones atomically. Raises whatever the embedder raises (BudgetExceeded...) with
    the old vectors untouched. `patient=False` fails fast on embedding rate limits (interactive callers)."""
    text = await source() if callable(source) else source
    if not text or not text.strip():
        return ChapterOutcome("empty")
    digest = hashlib.sha256(text.encode()).hexdigest()
    cond = (SampleEmbedding.source_record_id == record_id, SampleEmbedding.chapter_index == chapter_index)
    stored = list((await session.scalars(select(SampleEmbedding).where(*cond).order_by(SampleEmbedding.chunk_index))).all())
    unchanged = bool(stored) and all(row.chapter_hash == digest and row.model == settings.voyage_model for row in stored)
    if unchanged:
        if all(row.chunk_text is not None for row in stored):
            return ChapterOutcome("unchanged")
        if backfill_chunk_inputs(stored, legacy_chunk_text(text), digest, settings.voyage_model):
            await session.commit()
            return ChapterOutcome("unchanged")
        # Unverifiable layout or possible legacy truncation: embed again rather than attach guessed text.
    if not unchanged and gate is not None and not await gate(text):
        await session.execute(delete(SampleEmbedding).where(*cond))
        await session.commit()
        return ChapterOutcome("rejected")
    chunks = chunk_text(text)
    vecs = await embedder.embed(chunks, "document", purpose, patient=patient)
    if len(vecs) != len(chunks):
        raise ValueError("Embedding count does not match chunk count")
    await session.execute(delete(SampleEmbedding).where(*cond))
    for i, (c, v) in enumerate(zip(chunks, vecs, strict=True)):
        session.add(SampleEmbedding(source_record_id=record_id, chapter_index=chapter_index, chunk_index=i, chapter_hash=digest,
                                    chunk_text=c, token_count=approx_tokens(c), model=settings.voyage_model, embedding=v))
    await session.commit()
    return ChapterOutcome("embedded", len(vecs))
