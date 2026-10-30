"""Crawl runner: per-source state, checkpointed incremental crawl, upsert of source records, sample-chapter indexing,
then the shared resolve+index pipeline."""
import hashlib
import json
import logging
from dataclasses import asdict
from datetime import UTC, datetime

from sqlalchemy import select, text
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..ai.embeddings import Embedder
from ..config import Settings
from ..indexing import embed_synopses, index_profiles, index_sample_text
from ..models import CrawlRun, CrawlState, SourceRecord, SourceTitle
from ..resolution.resolver import Resolver
from ..sources.base import Source, SourceRecordData
from ..text import normalize_title
from .http import BlockedError, RobotsDisallowed

log = logging.getLogger(__name__)
FIELDS = ["url", "title", "alt_titles", "authors", "synopsis", "tags", "genres", "status", "chapter_count", "rating",
          "rating_scale", "rating_votes", "language", "start_year", "cover_url", "external_ids", "related_urls"]


def content_hash(d: SourceRecordData) -> str:
    payload = {k: getattr(d, k) for k in FIELDS}
    return hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False, default=str).encode()).hexdigest()


async def upsert_record(session: AsyncSession, d: SourceRecordData) -> tuple[SourceRecord, bool]:
    """Insert/update a record as crawled. Returns (record, changed)."""
    h = content_hash(d)
    rec = await session.scalar(select(SourceRecord).where(SourceRecord.source == d.source, SourceRecord.source_id == d.source_id))
    values = {k: getattr(d, k) for k in FIELDS} | {"raw": d.raw, "content_hash": h}
    if rec is None:
        rec = SourceRecord(source=d.source, source_id=d.source_id, **values)
        session.add(rec)
        changed = True
    else:
        changed = rec.content_hash != h
        rec.crawled_at = datetime.now(UTC)
        rec.raw = d.raw
        if changed:
            if d.synopsis != rec.synopsis:
                rec.synopsis_embedding = None
            for k, v in values.items():
                setattr(rec, k, v)
            rec.needs_resolution = True
            rec.needs_index = True
    await session.flush()
    if changed:
        await session.execute(text("DELETE FROM source_titles WHERE source_record_id = :i"), {"i": rec.id})
        for n in {normalize_title(t) for t in [d.title, *d.alt_titles]}:
            if n:
                session.add(SourceTitle(source_record_id=rec.id, norm_title=n))
    return rec, changed


async def get_state(session: AsyncSession, source: str) -> CrawlState:
    st = await session.get(CrawlState, source)
    if st is None:
        st = CrawlState(source=source)
        session.add(st)
        await session.flush()
    return st


class CrawlRunner:
    def __init__(self, maker: async_sessionmaker[AsyncSession], settings: Settings, embedder: Embedder | None, resolver: Resolver):
        self.maker, self.settings, self.embedder, self.resolver = maker, settings, embedder, resolver

    async def crawl(self, source: Source, max_items: int | None = None, fresh: bool = False) -> dict:
        stats = {"seen": 0, "changed": 0, "samples": 0}
        async with self.maker() as s:
            st = await get_state(s, source.name)
            if not source.available():
                st.status, st.last_error = "disabled", "source unavailable (missing credentials?)"
                await s.commit()
                return {"status": "disabled"}
            if st.status == "blocked":
                return {"status": "blocked", "reason": st.blocked_reason}
            if st.status == "running" and st.last_started_at and (datetime.now(UTC) - st.last_started_at).total_seconds() < 6 * 3600:
                return {"status": "already_running"}
            checkpoint = None if fresh else (st.checkpoint or None)
            st.status, st.last_started_at, st.requested_at, st.last_error = "running", datetime.now(UTC), None, None
            run = CrawlRun(source=source.name)
            s.add(run)
            await s.commit()
            rows = (await s.execute(text("SELECT source_id, raw->>'_version' FROM source_records WHERE source = :s"), {"s": source.name})).all()
            source.known_versions = {a: b for a, b in rows if b}  # type: ignore[attr-defined]
        status, err = "idle", None
        try:
            async for batch in source.iter_catalog(checkpoint):
                async with self.maker() as s:
                    for d in batch.records:
                        rec, changed = await upsert_record(s, d)
                        stats["seen"] += 1
                        stats["changed"] += int(changed)
                    st = await get_state(s, source.name)
                    st.checkpoint = {} if batch.done else batch.checkpoint
                    st.items_seen = (st.items_seen or 0) + len(batch.records)
                    await s.commit()
                if max_items and stats["seen"] >= max_items:
                    break
        except BlockedError as e:
            status, err = "blocked", str(e)
            log.error("source %s blocked: %s", source.name, e)
        except RobotsDisallowed as e:
            status, err = "blocked", f"robots.txt disallows {e}"
        except Exception as e:
            status, err = "error", f"{type(e).__name__}: {e}"
            log.exception("crawl of %s failed", source.name)
        async with self.maker() as s:
            st = await get_state(s, source.name)
            st.status = status
            st.last_finished_at = datetime.now(UTC)
            st.last_error = err
            st.blocked_reason = err if status == "blocked" else None
            run = await s.scalar(select(CrawlRun).where(CrawlRun.source == source.name).order_by(CrawlRun.id.desc()).limit(1))
            if run:
                run.finished_at, run.status, run.stats = datetime.now(UTC), status, stats | ({"error": err} if err else {})
            await s.commit()
        return {"status": status, "error": err, **stats}

    async def sample_chapters(self, source: Source, limit: int = 20) -> int:
        """Embed the first N free chapters (sources that support it); text is discarded right after embedding."""
        if self.embedder is None or self.settings.sample_chapters <= 0:
            return 0
        done = 0
        async with self.maker() as s:
            recs = list((await s.scalars(select(SourceRecord).where(
                SourceRecord.source == source.name, SourceRecord.samples_done.is_(False)).limit(limit))).all())
            for r in recs:
                try:
                    texts = await source.fetch_sample_chapters(SourceRecordData(source=r.source, source_id=r.source_id, title=r.title, url=r.url), self.settings.sample_chapters)
                except (BlockedError, RobotsDisallowed):
                    break
                done += await index_sample_text(s, self.embedder, self.settings, r, texts)
        return done

    async def process_pending(self, batch: int = 200) -> dict:
        """Shared pipeline for all sources: synopsis vectors -> resolve -> profile vectors."""
        out = {"synopses": 0, "resolved": 0, "profiles": 0}
        async with self.maker() as s:
            if self.embedder:
                out["synopses"] = await embed_synopses(s, self.embedder, batch)
        while True:
            async with self.maker() as s:
                recs = list((await s.scalars(select(SourceRecord).where(SourceRecord.needs_resolution.is_(True)).order_by(SourceRecord.id).limit(50))).all())
                if not recs:
                    break
                for r in recs:
                    try:
                        await self.resolver.resolve(s, r)
                    except Exception:
                        log.exception("resolve failed for record %s", r.id)
                        r.needs_resolution = False
                    out["resolved"] += 1
                await s.commit()
        if self.embedder:
            while True:
                async with self.maker() as s:
                    n = await index_profiles(s, self.embedder, self.settings, batch)
                    out["profiles"] += n
                    if n == 0:
                        break
        return out
