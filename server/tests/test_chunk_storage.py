import hashlib

import pytest
from sqlalchemy import select

from readerserver.config import Settings
from readerserver.indexing import chunk_text, index_sample_text, legacy_chunk_text
from readerserver.linkcrawl.chapters import embed_chapter_text
from readerserver.models import SampleEmbedding, SourceRecord
from readerserver.text import approx_tokens

CFG = Settings(voyage_model="test-model")
TEXT = "\n\n".join(["The dragon returned to the forest. " * 65] * 4)


async def record(session):
    rec = SourceRecord(source="test", source_id="1", title="Dragon")
    session.add(rec)
    await session.commit()
    return rec


async def rows(session):
    return list((await session.scalars(select(SampleEmbedding).order_by(SampleEmbedding.chunk_index))).all())


async def embed(session, fake, rec, text=TEXT):
    return await embed_chapter_text(session, fake, CFG, rec.id, 1, text, purpose="test")


@pytest.mark.parametrize("partial", [False, True])
async def test_legacy_backfill_preserves_vectors_without_api_calls(session, fake_embedder, partial):
    rec = await record(session)
    digest = hashlib.sha256(TEXT.encode()).hexdigest()
    for i, chunk in enumerate(legacy_chunk_text(TEXT)):
        session.add(SampleEmbedding(source_record_id=rec.id, chapter_index=1, chunk_index=i,
                                    chapter_hash=digest, model=CFG.voyage_model, token_count=approx_tokens(chunk),
                                    chunk_text=chunk if partial and i == 0 else None, embedding=fake_embedder._vec(chunk)))
    await session.commit()
    before = [(r.id, list(r.embedding)) for r in await rows(session)]
    assert (await embed(session, fake_embedder, rec)).outcome == "unchanged"
    after = await rows(session)
    assert [r.chunk_text for r in after] == legacy_chunk_text(TEXT)
    assert [(r.id, list(r.embedding)) for r in after] == before
    assert fake_embedder.calls == []


@pytest.mark.parametrize("damage", ["index", "tokens", "text", "hash", "model", "missing"])
async def test_unreliable_legacy_layout_is_reembedded(session, fake_embedder, damage):
    rec = await record(session)
    await embed(session, fake_embedder, rec)
    stored = await rows(session)
    for row in stored:
        row.chunk_text = None
    if damage == "index":
        stored[0].chunk_index = 999
    elif damage == "tokens":
        stored[0].token_count += 1
    elif damage == "text":
        stored[0].chunk_text = "Different text with identical metadata"
    elif damage == "hash":
        stored[0].chapter_hash = "0" * 64
    elif damage == "model":
        stored[0].model = "old-model"
    else:
        await session.delete(stored[-1])
    await session.commit()
    assert (await embed(session, fake_embedder, rec)).outcome == "embedded"
    assert len(fake_embedder.calls) == 2
    assert [r.chunk_text for r in await rows(session)] == chunk_text(TEXT)


async def test_backfill_uses_frozen_chunker(session, fake_embedder, monkeypatch):
    rec = await record(session)
    await embed(session, fake_embedder, rec)
    for row in await rows(session):
        row.chunk_text = None
    await session.commit()
    monkeypatch.setattr("readerserver.linkcrawl.chapters.chunk_text", lambda text: ["different boundaries"])
    assert (await embed(session, fake_embedder, rec)).outcome == "unchanged"
    assert len(fake_embedder.calls) == 1
    assert [r.chunk_text for r in await rows(session)] == legacy_chunk_text(TEXT)


@pytest.mark.parametrize("failure", ["exception", "count"])
async def test_failed_replacement_preserves_old_passages(session, fake_embedder, failure):
    rec = await record(session)
    await embed(session, fake_embedder, rec)
    before = [(r.id, r.chunk_text, list(r.embedding)) for r in await rows(session)]

    class Broken:
        async def embed(self, *args, **kwargs):
            if failure == "exception":
                raise RuntimeError("provider unavailable")
            return []

    with pytest.raises((RuntimeError, ValueError)):
        await embed(session, Broken(), rec, TEXT + " changed")
    await session.commit()  # even if the caller commits, deletion has not happened
    assert [(r.id, r.chunk_text, list(r.embedding)) for r in await rows(session)] == before


async def test_catalog_stores_exact_inputs_and_reuses_only_proven_matches(session, fake_embedder):
    rec = await record(session)
    await index_sample_text(session, fake_embedder, CFG, rec, [TEXT])
    stored = await rows(session)
    assert [r.chunk_text for r in stored] == chunk_text(TEXT)
    assert all(list(r.embedding) == pytest.approx(fake_embedder._vec(r.chunk_text)) for r in stored)
    await index_sample_text(session, fake_embedder, CFG, rec, [TEXT])
    assert len(fake_embedder.calls) == 1
    for row in stored:
        row.chunk_text = None
    await session.commit()
    # Catalog legacy rows have no source hash, so never guess from token counts.
    await index_sample_text(session, fake_embedder, CFG, rec, [TEXT])
    assert len(fake_embedder.calls) == 2
    assert [r.chunk_text for r in await rows(session)] == chunk_text(TEXT)


async def test_possibly_truncated_legacy_input_is_reembedded(session, fake_embedder):
    rec = await record(session)
    text = "x" * 150_000  # the old chunker emitted one input that Voyage could truncate
    session.add(SampleEmbedding(source_record_id=rec.id, chapter_index=1, chunk_index=0,
                                chapter_hash=hashlib.sha256(text.encode()).hexdigest(),
                                model=CFG.voyage_model, token_count=approx_tokens(text),
                                embedding=fake_embedder._vec("old truncated input")))
    await session.commit()
    assert (await embed(session, fake_embedder, rec, text)).outcome == "embedded"
    assert [r.chunk_text for r in await rows(session)] == chunk_text(text)
    assert len(fake_embedder.calls) == 1


async def test_catalog_replacement_keeps_full_chapter_embeddings(session, fake_embedder):
    rec = await record(session)
    await embed(session, fake_embedder, rec)
    before = [(r.id, r.chunk_text) for r in await rows(session)]
    await index_sample_text(session, fake_embedder, CFG, rec, ["A catalog passage"])
    await index_sample_text(session, fake_embedder, CFG, rec, ["An updated catalog passage"])
    chapters = [r for r in await rows(session) if r.chapter_index == 1]
    assert [(r.id, r.chunk_text) for r in chapters] == before


async def test_migration_round_trip_preserves_legacy_vector(session, fake_embedder, db_engine):
    import importlib.util
    from pathlib import Path

    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    from sqlalchemy import text

    rec = await record(session)
    await embed(session, fake_embedder, rec)
    before = [(r.id, list(r.embedding)) for r in await rows(session)]
    await session.commit()  # release the SELECT lock before DDL on a separate connection
    path = Path(__file__).parents[1] / "alembic/versions/f6c3d9b02e45_sample_chunk_text.py"
    spec = importlib.util.spec_from_file_location("chunk_migration", path)
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)

    def round_trip(conn):
        with Operations.context(MigrationContext.configure(conn)):
            migration.downgrade()
            migration.upgrade()

    async with db_engine.begin() as conn:
        await conn.run_sync(round_trip)
        assert await conn.scalar(text("SELECT count(*) FROM sample_embeddings WHERE chunk_text IS NOT NULL")) == 0
    session.expire_all()
    assert [(r.id, list(r.embedding)) for r in await rows(session)] == before


def test_unbroken_and_multilingual_chunks_are_bounded_without_losing_text():
    for text in ["x" * 150_000, "龍😀" * 20_000]:
        chunks = chunk_text(text)
        assert "".join(chunks) == text
        assert all(approx_tokens(c) <= 1000 and len(c.encode()) <= 4000 for c in chunks)
