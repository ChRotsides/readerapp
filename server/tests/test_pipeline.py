import pytest
from sqlalchemy import func, inspect, select

from readerserver.ai.spend import BudgetExceeded, SpendTracker
from readerserver.config import Settings
from readerserver.crawl.http import BlockedError
from readerserver.crawl.runner import CrawlRunner
from readerserver.models import CrawlState, ProfileEmbedding, SampleEmbedding, SourceRecord, Work
from readerserver.resolution.resolver import Resolver, seed_tags
from readerserver.sources.base import CatalogBatch, Source, SourceRecordData as D


class FakeSource(Source):
    name = "fake"
    respect_robots = False

    def __init__(self, batches, fail_after=None, avail=True, chapters=None):
        self.batches, self.fail_after, self.avail, self.chapters = batches, fail_after, avail, chapters or []
        self.started_from = []

    def available(self):
        return self.avail

    async def iter_catalog(self, checkpoint):
        self.started_from.append(checkpoint)
        start = (checkpoint or {}).get("i", 0)
        for i in range(start, len(self.batches)):
            if self.fail_after is not None and i == self.fail_after:
                raise BlockedError("403 from fake")
            yield CatalogBatch(self.batches[i], {"i": i + 1}, done=i == len(self.batches) - 1)

    async def fetch_sample_chapters(self, record, n):
        return self.chapters[:n]


def settings():
    return Settings(voyage_model="test-model", sample_chapters=2)


def d(i, **kw):
    return D(source="fake", source_id=str(i), title=f"Novel {i} Alpha", synopsis=f"Synopsis number {i} about dragons and swords " * 3, **kw)


async def test_crawl_checkpoint_resume_and_block(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    src = FakeSource([[d(1), d(2)], [d(3)], [d(4)]], fail_after=1)
    res = await runner.crawl(src)
    assert res["status"] == "blocked" and res["seen"] == 2
    async with maker() as s:
        st = await s.get(CrawlState, "fake")
        assert st.status == "blocked" and st.checkpoint == {"i": 1} and "403" in st.blocked_reason
    # blocked sources are not retried automatically (we never evade)
    again = await runner.crawl(FakeSource([[d(9)]]))
    assert again["status"] == "blocked"
    async with maker() as s:
        st = await s.get(CrawlState, "fake")
        st.status = "idle"  # what POST /admin/crawl/{source}/unblock does
        await s.commit()
    src2 = FakeSource([[d(1), d(2)], [d(3)], [d(4)]])
    res = await runner.crawl(src2)
    assert src2.started_from == [{"i": 1}] and res["status"] == "idle" and res["seen"] == 2
    async with maker() as s:
        assert await s.scalar(select(func.count()).select_from(SourceRecord)) == 4
        assert (await s.get(CrawlState, "fake")).checkpoint == {}  # finished -> next run starts fresh


async def test_unavailable_source_skipped_gracefully(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    res = await runner.crawl(FakeSource([[d(1)]], avail=False))
    assert res["status"] == "disabled"
    async with maker() as s:
        assert await s.scalar(select(func.count()).select_from(SourceRecord)) == 0


async def test_process_pending_resolves_and_indexes_profiles(maker, fake_embedder):
    async with maker() as s:
        await seed_tags(s)
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    await runner.crawl(FakeSource([[d(1, tags=["Isekai"]), d(2)]]))
    out = await runner.process_pending()
    assert out["resolved"] == 2 and out["synopses"] == 2 and out["profiles"] >= 2
    async with maker() as s:
        assert await s.scalar(select(func.count()).select_from(Work)) == 2
        assert await s.scalar(select(func.count()).select_from(ProfileEmbedding)) == 2
        w = (await s.scalars(select(Work).where(Work.title == "Novel 1 Alpha"))).one()
        assert "Transported to Another World" in w.tags
    calls_before = len(fake_embedder.calls)
    await runner.process_pending()  # idempotent: nothing pending
    assert len(fake_embedder.calls) == calls_before


async def test_sample_chapters_store_vectors_only(maker, fake_embedder):
    runner = CrawlRunner(maker, settings(), fake_embedder, Resolver(None))
    text = "\n\n".join(["The hero walked into the dark forest and drew his sword. " * 40] * 6)
    src = FakeSource([[d(1)]], chapters=[text, text, text])
    await runner.crawl(src)
    n = await runner.sample_chapters(src)
    assert n > 1
    cols = {c.name for c in inspect(SampleEmbedding).columns}
    assert not ({"text", "content", "body", "chapter_text"} & cols)  # chapter text is never stored
    async with maker() as s:
        assert await s.scalar(select(func.count()).select_from(SampleEmbedding)) == n
        assert (await s.scalars(select(SourceRecord))).one().samples_done is True


async def test_spend_cap_blocks_and_records(maker):
    sp = SpendTracker(maker, cap_usd=0.01)
    await sp.check()
    await sp.record("voyage", "m", "test", 100, 0, 0.004)
    await sp.check()
    await sp.record("anthropic", "claude-haiku-5-5", "hyde", 1000, 500, 0.02)
    with pytest.raises(BudgetExceeded):
        await sp.check()
    summ = await sp.summary()
    assert summ["spent_usd"] == pytest.approx(0.024) and summ["remaining_usd"] == 0
    assert {b["provider"] for b in summ["breakdown"]} == {"voyage", "anthropic"}
