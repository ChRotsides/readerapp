"""Crawl runner: per-source state, checkpointed incremental crawl, upsert of source records, sample-chapter indexing,
then the shared resolve+index pipeline."""
import asyncio
import hashlib
import json
import logging
from dataclasses import asdict
from datetime import UTC, datetime, timedelta

from sqlalchemy import or_, select, text
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..ai.embeddings import Embedder
from ..config import Settings
from ..indexing import embed_record_synopses, index_profiles, index_sample_text
from ..models import CrawlRun, CrawlState, SourceRecord, SourceTitle
from ..resolution.resolver import Resolver, settle_resolution
from ..sources.base import Source, SourceRecordData
from ..text import normalize_title
from .http import BlockedError, CrawlDeferred, RobotsDisallowed

log = logging.getLogger(__name__)
RESOLVE_LOCK_KEY = 7_270_002  # pg advisory lock serializing resolution across worker processes
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
            now = datetime.now(UTC)
            if st.next_eligible_at and st.next_eligible_at > now:
                return {"status": "deferred", "until": st.next_eligible_at.isoformat(), "reason": st.last_error}
            if st.status == "running" and st.lease_expires_at and st.lease_expires_at > now:
                return {"status": "already_running"}
            if st.status == "running":  # lease expired: the previous worker died; resume from its checkpoint
                log.warning("source %s: resuming abandoned run (lease expired %s)", source.name, st.lease_expires_at)
                await s.execute(text("UPDATE crawl_runs SET status='abandoned', finished_at=now() WHERE source=:s AND finished_at IS NULL"),
                                {"s": source.name})
            checkpoint = None if fresh else (st.checkpoint or None)
            st.status, st.last_started_at, st.requested_at, st.last_error = "running", datetime.now(UTC), None, None
            st.next_eligible_at, st.lease_expires_at = None, datetime.now(UTC) + self._lease()
            run = CrawlRun(source=source.name)
            s.add(run)
            await s.commit()
            rows = (await s.execute(text("SELECT source_id, raw->>'_version' FROM source_records WHERE source = :s"), {"s": source.name})).all()
            source.known_versions = {a: b for a, b in rows if b}  # type: ignore[attr-defined]
        status, err, defer_for = "idle", None, None
        beat = asyncio.create_task(self._heartbeat(source.name))
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
                    st.lease_expires_at = datetime.now(UTC) + self._lease()
                    await s.commit()
                if max_items and stats["seen"] >= max_items:
                    break
        except BlockedError as e:
            status, err = "blocked", str(e)
            log.error("source %s blocked: %s", source.name, e)
        except RobotsDisallowed as e:
            status, err = "blocked", f"robots.txt disallows {e}"
        except CrawlDeferred as e:  # Retry-After too long to sleep inline / robots.txt temporarily unavailable
            status, err, defer_for = "deferred", f"deferred: {e}", e.seconds
            log.warning("source %s deferred for %.0fs: %s", source.name, e.seconds, e)
        except Exception as e:
            status, err = "error", f"{type(e).__name__}: {e}"
            log.exception("crawl of %s failed", source.name)
        finally:
            beat.cancel()
        async with self.maker() as s:
            st = await get_state(s, source.name)
            st.lease_expires_at = None
            st.next_eligible_at = datetime.now(UTC) + timedelta(seconds=defer_for) if defer_for is not None else None
            st.status = status
            st.last_finished_at = datetime.now(UTC)
            st.last_error = err
            st.blocked_reason = err if status == "blocked" else None
            run = await s.scalar(select(CrawlRun).where(CrawlRun.source == source.name).order_by(CrawlRun.id.desc()).limit(1))
            if run:
                run.finished_at, run.status, run.stats = datetime.now(UTC), status, stats | ({"error": err} if err else {})
            await s.commit()
        return {"status": status, "error": err, **stats}

    def _lease(self) -> timedelta:
        return timedelta(seconds=self.settings.crawl_lease_seconds)

    async def _heartbeat(self, source: str) -> None:
        """Keep the run's lease alive while the crawl is making slow progress inside a single batch."""
        interval = max(self.settings.crawl_lease_seconds / 3, 0.05)
        while True:
            await asyncio.sleep(interval)
            try:
                async with self.maker() as s:
                    await s.execute(text("UPDATE crawl_state SET lease_expires_at = :t WHERE source = :s AND status = 'running'"),
                                    {"t": datetime.now(UTC) + self._lease(), "s": source})
                    await s.commit()
            except Exception:  # pragma: no cover - best effort
                log.exception("heartbeat failed for %s", source)

    async def _stop_source(self, source: str, status: str, reason: str, defer_for: float | None = None) -> None:
        """Persist a sampling-time stop: blocked (never retried automatically) or deferred (retried after the delay)."""
        async with self.maker() as s:
            st = await get_state(s, source)
            st.status, st.last_error = status, reason
            st.blocked_reason = reason if status == "blocked" else None
            st.next_eligible_at = datetime.now(UTC) + timedelta(seconds=defer_for) if defer_for is not None else None
            await s.commit()

    async def sample_chapters(self, source: Source, limit: int = 20) -> int:
        """Embed the first N free chapters (sources that support it), storing their chunks with the vectors.
        A 403/challenge/robots block here marks the whole source blocked, exactly as in the catalog crawl."""
        if self.embedder is None or self.settings.sample_chapters <= 0:
            return 0
        done = 0
        async with self.maker() as s:
            st = await get_state(s, source.name)
            if st.status in ("blocked", "disabled") or (st.next_eligible_at and st.next_eligible_at > datetime.now(UTC)):
                return 0
            await s.commit()
            recs = list((await s.scalars(select(SourceRecord).where(
                SourceRecord.source == source.name, SourceRecord.samples_done.is_(False)).limit(limit))).all())
            for r in recs:
                try:
                    texts = await source.fetch_sample_chapters(SourceRecordData(source=r.source, source_id=r.source_id, title=r.title, url=r.url), self.settings.sample_chapters)
                except BlockedError as e:
                    log.error("source %s blocked while sampling: %s", source.name, e)
                    await self._stop_source(source.name, "blocked", f"sampling: {e}")
                    break
                except RobotsDisallowed as e:
                    await self._stop_source(source.name, "blocked", f"robots.txt disallows {e}")
                    break
                except CrawlDeferred as e:
                    await self._stop_source(source.name, "deferred", f"sampling deferred: {e}", e.seconds)
                    break
                except Exception:
                    log.exception("sampling failed for %s/%s", r.source, r.source_id)
                    continue
                done += await index_sample_text(s, self.embedder, self.settings, r, texts)
        return done

    async def process_pending(self, batch: int = 200, resolve_batch: int = 50, record_ids: list[int] | None = None) -> dict:
        """Shared pipeline for all sources: per bounded batch, synopsis vectors -> resolve; then profile vectors.
        Serialized across processes by a Postgres advisory lock. Records whose prerequisites are missing (embedding
        unavailable, verdict provider down) stay pending with a durable reason + retry time; one bad record never
        loses the batch (each is isolated in a savepoint). `record_ids` limits resolution to those records (profile indexing
        still covers every work that needs it)."""
        out = {"synopses": 0, "resolved": 0, "deferred": 0, "failed": 0, "profiles": 0}
        async with self.maker() as lock_session:
            await lock_session.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": RESOLVE_LOCK_KEY})  # held until rollback
            try:
                await self._process_locked(out, batch, resolve_batch, record_ids)
            finally:
                await lock_session.rollback()
        return out

    async def _process_locked(self, out: dict, batch: int, resolve_batch: int, record_ids: list[int] | None = None) -> None:
        last_id = 0
        while True:
            async with self.maker() as s:
                recs = list((await s.scalars(select(SourceRecord).where(
                    SourceRecord.needs_resolution.is_(True), SourceRecord.id > last_id,
                    or_(SourceRecord.resolve_retry_at.is_(None), SourceRecord.resolve_retry_at <= datetime.now(UTC)),
                    *([SourceRecord.id.in_(record_ids)] if record_ids is not None else []),
                ).order_by(SourceRecord.id).limit(resolve_batch))).all())
                if not recs:
                    break
                last_id = recs[-1].id
                if self.embedder:
                    out["synopses"] += await embed_record_synopses(s, self.embedder, recs)
                for r in recs:
                    rid = r.id
                    if self.embedder and r.synopsis and r.synopsis_embedding is None:
                        settle_resolution(r, "awaiting_embedding")  # kNN candidates need this vector; retry later
                        out["deferred"] += 1
                        continue
                    try:
                        async with s.begin_nested():
                            outcome = await self.resolver.resolve(s, r)
                        out["resolved"] += 1
                        out["deferred"] += int(bool(outcome.get("pending_verdict")))
                    except Exception:
                        log.exception("resolve failed for record %s", rid)
                        await s.refresh(r)  # the savepoint rollback expired it
                        settle_resolution(r, "resolve_error")
                        out["failed"] += 1
                await s.commit()
        if self.embedder:
            while True:
                async with self.maker() as s:
                    n = await index_profiles(s, self.embedder, self.settings, batch)
                    out["profiles"] += n
                    if n == 0:
                        break
