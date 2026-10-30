"""Review-3 findings #7 #9 #11 #18 #22: worker/runner coordination, deferral, leases, block handling."""
import asyncio
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace as NS

from sqlalchemy import func, select

from readerserver import worker
from readerserver.ai.spend import BudgetExceeded
from readerserver.config import Settings
from readerserver.crawl.http import BlockedError, CrawlDeferred
from readerserver.crawl.runner import CrawlRunner
from readerserver.models import CrawlRun, CrawlState, SourceRecord, Work
from readerserver.resolution.resolver import Resolver
from readerserver.sources.base import CatalogBatch, SourceRecordData as D

from .test_pipeline import FakeSource, d, settings


class DeferringSource(FakeSource):
    def __init__(self, *a, defer_at=1, seconds=7200.0, **kw):
        super().__init__(*a, **kw)
        self.defer_at, self.seconds = defer_at, seconds

    async def iter_catalog(self, checkpoint):
        self.started_from.append(checkpoint)
        start = (checkpoint or {}).get("i", 0)
        for i in range(start, len(self.batches)):
            if self.defer_at is not None and i == self.defer_at:
                raise CrawlDeferred("429 Retry-After", self.seconds)
            yield CatalogBatch(self.batches[i], {"i": i + 1}, done=i == len(self.batches) - 1)


def sv_for(maker, **kw):
    return NS(maker=maker, settings=Settings(enabled_sources="syosetu", crawl_interval_hours=24, **kw))


# ---------------------------------------------------------------- #11 lease
async def test_abandoned_running_crawl_is_resumed_from_checkpoint(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    async with maker() as s:
        s.add(CrawlState(source="syosetu", status="running", checkpoint={"i": 1}, last_started_at=datetime.now(UTC) - timedelta(hours=1),
                         lease_expires_at=datetime.now(UTC) - timedelta(minutes=1)))
        s.add(CrawlRun(source="syosetu"))
        await s.commit()
    assert "syosetu" in await worker.due_sources(sv_for(maker))  # before: 'running' was excluded forever
    src = FakeSource([[d(1)], [d(2)], [d(3)]])
    src.name = "syosetu"
    res = await runner.crawl(src)
    assert res["status"] == "idle" and src.started_from == [{"i": 1}] and res["seen"] == 2
    async with maker() as s:
        runs = (await s.scalars(select(CrawlRun).order_by(CrawlRun.id))).all()
        assert runs[0].status == "abandoned" and runs[-1].status == "idle"


async def test_live_lease_is_respected_and_heartbeat_renews_it(maker, fake_embedder):
    cfg = Settings(voyage_model="t", crawl_lease_seconds=0.3)
    runner = CrawlRunner(maker, cfg, fake_embedder, Resolver(None))
    started = asyncio.Event()
    release = asyncio.Event()

    class Slow(FakeSource):
        async def iter_catalog(self, checkpoint):
            started.set()
            await release.wait()
            yield CatalogBatch([d(1)], {}, done=True)

    first = asyncio.create_task(runner.crawl(Slow([[d(1)]])))
    await started.wait()
    await asyncio.sleep(0.8)  # > 2 lease periods: only the heartbeat keeps it alive
    assert (await runner.crawl(FakeSource([[d(9)]])))["status"] == "already_running"
    release.set()
    assert (await first)["status"] == "idle"
    async with maker() as s:
        assert (await s.get(CrawlState, "fake")).lease_expires_at is None


# ---------------------------------------------------------------- #22 deferral
async def test_long_retry_after_defers_crawl_and_persists_eligibility(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    src = DeferringSource([[d(1)], [d(2)]], defer_at=1)
    res = await runner.crawl(src)
    assert res["status"] == "deferred" and res["seen"] == 1
    async with maker() as s:
        st = await s.get(CrawlState, "fake")
        assert st.status == "deferred" and st.checkpoint == {"i": 1}
        assert timedelta(seconds=7100) < st.next_eligible_at - datetime.now(UTC) <= timedelta(seconds=7200)
    assert (await runner.crawl(FakeSource([[d(9)]])))["status"] == "deferred"  # not retried early
    async with maker() as s:
        st = await s.get(CrawlState, "fake")
        st.next_eligible_at = datetime.now(UTC) - timedelta(seconds=1)
        await s.commit()
    src2 = FakeSource([[d(1)], [d(2)]])
    res = await runner.crawl(src2)
    assert res["status"] == "idle" and src2.started_from == [{"i": 1}]


async def test_due_sources_skips_deferred(maker):
    async with maker() as s:
        s.add(CrawlState(source="syosetu", status="deferred", checkpoint={"i": 1}, next_eligible_at=datetime.now(UTC) + timedelta(hours=1)))
        await s.commit()
    assert "syosetu" not in await worker.due_sources(sv_for(maker))
    async with maker() as s:
        st = await s.get(CrawlState, "syosetu")
        st.next_eligible_at = datetime.now(UTC) - timedelta(seconds=1)
        await s.commit()
    assert "syosetu" in await worker.due_sources(sv_for(maker))


# ---------------------------------------------------------------- #18 blocks during sampling
class BlockingSampleSource(FakeSource):
    async def fetch_sample_chapters(self, record, n):
        raise BlockedError("403 on chapter")


async def test_sampling_block_marks_source_blocked(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    src = BlockingSampleSource([[d(1), d(2)]])
    await runner.crawl(src)
    assert await runner.sample_chapters(src) == 0
    async with maker() as s:
        st = await s.get(CrawlState, "fake")
        assert st.status == "blocked" and "403" in st.blocked_reason
    # blocked: neither a new crawl nor sampling goes ahead
    assert (await runner.crawl(FakeSource([[d(9)]])))["status"] == "blocked"
    calls = []

    class Spy(FakeSource):
        async def fetch_sample_chapters(self, record, n):
            calls.append(record.source_id)
            return ["text"]

    assert await runner.sample_chapters(Spy([[d(1)]])) == 0 and not calls


async def test_run_source_never_samples_after_blocked_catalog(maker, fake_embedder, monkeypatch):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    sampled = []

    class Src(FakeSource):
        async def fetch_sample_chapters(self, record, n):
            sampled.append(record.source_id)
            return ["x " * 50]

    # first batch stores records, the second hits a block: seen > 0 but the status is blocked
    src = Src([[d(1)], [d(2)]], fail_after=1)
    monkeypatch.setattr(worker, "build_source", lambda name, settings: src)
    res = await worker.run_source(NS(settings=settings()), runner, "fake")
    assert res["status"] == "blocked" and res["seen"] == 1 and sampled == []


# ---------------------------------------------------------------- #7 serialization / isolation
async def test_concurrent_process_pending_does_not_duplicate_works(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    recs = [D(source=f"s{i}", source_id="1", title=f"Totally Different Title {i}", external_ids={"mal": "42", f"s{i}": "1"}) for i in range(6)]
    async with maker() as s:
        from readerserver.crawl.runner import upsert_record
        for r in recs:
            await upsert_record(s, r)
        await s.commit()
    r1, r2 = await asyncio.gather(runner.process_pending(resolve_batch=2), runner.process_pending(resolve_batch=2))
    assert r1["resolved"] + r2["resolved"] == 6
    async with maker() as s:
        assert await s.scalar(select(func.count()).select_from(Work)) == 1


class FlakyResolver(Resolver):
    def __init__(self, bad_id):
        super().__init__(None)
        self.bad_id = bad_id

    async def resolve(self, session, rec):
        if rec.source_id == self.bad_id:
            rec.title = "half-written"  # partial state must be rolled back with the savepoint
            await session.flush()
            raise RuntimeError("boom")
        return await super().resolve(session, rec)


async def test_one_bad_record_does_not_lose_the_batch(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, FlakyResolver("2"))
    await runner.crawl(FakeSource([[d(1), d(2), d(3)]]))
    out = await runner.process_pending()
    assert out["resolved"] == 2 and out["failed"] == 1
    async with maker() as s:
        assert await s.scalar(select(func.count()).select_from(Work)) == 2
        bad = (await s.scalars(select(SourceRecord).where(SourceRecord.source_id == "2"))).one()
        assert bad.needs_resolution and bad.resolve_reason == "resolve_error" and bad.resolve_retry_at > datetime.now(UTC)
        assert bad.title == "Novel 2 Alpha"  # savepoint rolled back the half-written change
        ok = (await s.scalars(select(SourceRecord).where(SourceRecord.source_id == "3"))).one()
        assert ok.needs_resolution is False


# ---------------------------------------------------------------- #9 embeddings + resolution together, durable deferral
async def test_all_records_embedded_before_their_resolution_in_bounded_batches(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    await runner.crawl(FakeSource([[d(i) for i in range(1, 8)]]))
    out = await runner.process_pending(batch=3, resolve_batch=3)
    assert out["synopses"] == 7 and out["resolved"] == 7
    async with maker() as s:
        assert await s.scalar(select(func.count()).select_from(SourceRecord).where(SourceRecord.synopsis_embedding.is_(None))) == 0
    assert all(n <= 3 for _, n in fake_embedder.calls if _ == "document" and n != 0)


class FlakyEmbedder:
    def __init__(self, inner):
        self.inner, self.fail = inner, True

    async def embed(self, texts, input_type, purpose="embed", patient=True):
        if self.fail:
            raise BudgetExceeded("cap")
        return await self.inner.embed(texts, input_type, purpose)


async def test_embedding_outage_defers_resolution_durably_then_retries(maker, fake_embedder):
    emb = FlakyEmbedder(fake_embedder)
    runner = CrawlRunner(maker, settings(), emb, Resolver(None))
    await runner.crawl(FakeSource([[d(1), d(2)]]))
    out = await runner.process_pending()
    assert out["resolved"] == 0 and out["deferred"] == 2
    async with maker() as s:
        recs = (await s.scalars(select(SourceRecord))).all()
        assert all(r.needs_resolution and r.resolve_reason == "awaiting_embedding" and r.resolve_retry_at for r in recs)
        assert await s.scalar(select(func.count()).select_from(Work)) == 0
    assert (await runner.process_pending())["resolved"] == 0  # not hammered before the retry time
    emb.fail = False
    async with maker() as s:
        for r in (await s.scalars(select(SourceRecord))).all():
            r.resolve_retry_at = datetime.now(UTC) - timedelta(seconds=1)
        await s.commit()
    out = await runner.process_pending()
    assert out["resolved"] == 2
    async with maker() as s:
        assert all(not r.needs_resolution and r.resolve_reason is None for r in (await s.scalars(select(SourceRecord))).all())
