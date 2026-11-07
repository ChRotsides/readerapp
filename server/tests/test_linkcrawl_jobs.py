"""Link crawl jobs end to end against a fake site, fake Jev, fake Claude and fake embedder (no network, no paid calls)."""
import asyncio
import json
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import func, inspect, select, text, update

from readerserver.config import Settings
from readerserver.crawl.runner import CrawlRunner
from readerserver.linkcrawl.crawler import LinkCrawler
from readerserver.models import LinkCrawlJob, SampleEmbedding, SiteRecipe, SourceRecord, Work, WorkLink
from readerserver.resolution.resolver import Resolver, seed_tags

from .conftest import FakeEmbedder
from .linksite import CHAPTER as CHAPTER_SECTION, DOMAIN, NOVEL as NOVEL_SECTION, SENTINEL, FakeJev, Site, queue_recipes

NOVEL1 = f"https://{DOMAIN}/novel/1"
LIST = f"https://{DOMAIN}/list"


class HookEmbedder(FakeEmbedder):
    """Calls `hook(n)` on the n-th *chapter* embedding of a link job (purpose lc<id>:embed)."""

    def __init__(self, hook):
        super().__init__()
        self.hook, self.n = hook, 0

    async def embed(self, texts, input_type, purpose="embed", patient=True):
        if purpose.startswith("lc"):
            self.n += 1
            await self.hook(self.n)
        return await super().embed(texts, input_type, purpose, patient)


def make(maker, embedder, llm, site, jev=None, **cfg):
    settings = Settings(voyage_model="test-model", jev_api_key="k", jev_base_url="https://jev.test", **cfg)
    runner = CrawlRunner(maker, settings, embedder, Resolver(None))
    return LinkCrawler(maker, settings, embedder, llm, jev.client if jev else None, runner, http_factory=lambda d: site.client(d))


async def add_job(maker, url=NOVEL1, scope="novel", per=None, domain=DOMAIN) -> int:
    async with maker() as s:
        j = LinkCrawlJob(url=url, domain=domain, scope=scope, chapters_per_novel=per)
        s.add(j)
        await s.commit()
        return j.id


async def job(maker, jid) -> LinkCrawlJob:
    async with maker() as s:
        return await s.get(LinkCrawlJob, jid)


async def set_status(maker, jid, status):
    async with maker() as s:
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == jid).values(status=status))
        await s.commit()


async def run_next(c: LinkCrawler) -> int | None:
    claimed = await c.claim()
    if claimed is None:
        return None
    await c.run_job(*claimed)
    return claimed[0]


async def chapter_indexes(maker) -> list[int]:
    async with maker() as s:
        return sorted({i for (i,) in (await s.execute(select(SampleEmbedding.chapter_index).where(SampleEmbedding.chapter_index.is_not(None)))).all()})


@pytest.fixture
async def env(maker, fake_llm):
    async with maker() as s:
        await seed_tags(s)
    return maker, fake_llm, Site(), FakeJev(maker)


async def test_novel_job_runs_to_done_and_builds_recipe(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    jid = await add_job(maker)
    assert await run_next(c) == jid
    j = await job(maker, jid)
    assert j.status == "done" and j.error is None and j.stop_reason == "novel_complete"
    assert j.checkpoint["queue"] == [] and j.checkpoint["novel"] is None
    # novel page + toc (cached from validation) + toc page 2 + 5 chapters
    assert (j.novels_found, j.chapters_embedded, j.chapters_skipped, j.pages_fetched) == (1, 4, 1, 8)
    assert float(j.spend_usd) > 0
    assert await chapter_indexes(maker) == [0, 1, 2, 4]  # chapter 4 (index 3) is locked: judged not-story, never embedded
    async with maker() as s:
        rec = (await s.scalars(select(SourceRecord))).one()
        assert (rec.source, rec.title, rec.authors, rec.status, rec.chapter_count) == ("link", "Moonlit Bakery", ["Ann Baker"], "ongoing", 5)
        assert rec.raw["domain"] == DOMAIN and rec.cover_url == f"https://{DOMAIN}/c/1.jpg" and rec.synopsis.startswith("A story")
        assert (await s.scalar(select(func.count()).select_from(Work))) == 1  # resolved into a Work
        assert (await s.scalar(select(func.count()).select_from(WorkLink))) == 1
        recipes = (await s.scalars(select(SiteRecipe))).all()
        assert [(r.domain, r.version, r.status) for r in recipes] == [(DOMAIN, 1, "active")]
        assert recipes[0].recipe["listing"] is None and recipes[0].recipe["chapter"]["body"] == "#content"
    # Claude wrote each missing section once; no AI per chapter page. Jev judged the page type, links, quality and chapters.
    assert [x["schema"] for x in llm.calls] == ["NovelSection", "ChapterSection"]
    assert jev.calls.count("page_type") == 1 and jev.calls.count("quality") == 2 and jev.calls.count("role") == 3  # the 3 chapter links of the first toc page
    assert jev.calls.count("chapter") == 4  # first 3 chapters always, plus the suspicious (locked, very short) one, chapter 3 counted once
    assert all(r.startswith(f"/novel/1") for r in site.requests)


async def test_site_scope_crawls_listing_pages_and_every_novel(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm)
    c = make(maker, fake_embedder, llm, site, jev)
    jid = await add_job(maker, LIST, "site", per=2)
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert (j.novels_found, j.chapters_embedded) == (4, 8)  # 2 chapters of each of the 4 novels, over 2 listing pages
    assert site.count("/list?page=2") == 1
    assert sorted(x["schema"] for x in llm.calls) == ["ChapterSection", "ListingSection", "NovelSection"]  # once per kind, not per page
    async with maker() as s:
        assert (await s.scalar(select(func.count()).select_from(Work))) == 4
        assert sorted((await s.scalars(select(SourceRecord.title))).all()) == sorted(["Moonlit Bakery", "Sword Saint Rises", "Cloud Garden Keeper", "Iron Harbor Chronicle"])
    # 2 chapters each: novel 1's chapters 3-5 were never fetched
    assert site.count("/novel/1/chapter/3") == 0 and site.count("/novel/2/chapter/2") == 1


async def test_only_story_is_embedded_and_unchanged_chapters_are_skipped_then_replaced_on_change(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker)
    await run_next(c)
    async with maker() as s:
        before = await s.scalar(select(func.count()).select_from(SampleEmbedding).where(SampleEmbedding.chapter_index == 1))
    calls_before = list(jev.calls)
    j2 = await add_job(maker)
    await run_next(c)
    j = await job(maker, j2)
    assert j.status == "done" and j.chapters_embedded == 0 and j.chapters_skipped == 5  # nothing changed: nothing re-embedded
    assert jev.calls[len(calls_before):].count("chapter") == 1  # only the (still locked) chapter is judged again; unchanged ones are not
    site.edits[(1, 2)] = "A brand new ending paragraph was added to this chapter. " * 40  # long enough to need extra chunks
    j3 = await add_job(maker)
    await run_next(c)
    j = await job(maker, j3)
    assert j.chapters_embedded == 1
    async with maker() as s:
        after = await s.scalar(select(func.count()).select_from(SampleEmbedding).where(SampleEmbedding.chapter_index == 1))
        dup = await s.scalar(text("SELECT count(*) - count(DISTINCT (source_record_id, chapter_index, chunk_index)) FROM sample_embeddings WHERE chapter_index IS NOT NULL"))
    assert after > before and dup == 0  # old chunks were replaced, not appended to


async def test_pause_resume_and_cancel_take_effect_between_pages(env):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    jid_box: list[int] = []

    async def hook(n):
        if n == 2:
            await set_status(maker, jid_box[0], "paused")  # the user pauses while chapter 2 is being embedded

    emb = HookEmbedder(hook)
    c = make(maker, emb, llm, site, jev)
    jid = await add_job(maker)
    jid_box.append(jid)
    assert (await job(maker, jid)).status == "queued"
    claimed = await c.claim()
    assert (await job(maker, jid)).status == "running"
    await c.run_job(*claimed)
    j = await job(maker, jid)
    assert j.status == "paused" and j.lease_owner is None and j.chapters_embedded == 2 and j.checkpoint["novel"]["pos"] == 2
    assert await c.claim() is None  # paused jobs are not picked up
    await set_status(maker, jid, "queued")  # POST /resume
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "done" and j.chapters_embedded == 4 and j.chapters_skipped == 1
    assert [site.count(f"/novel/1/chapter/{k}") for k in range(1, 6)] == [1, 1, 1, 1, 1]  # resumed after the last finished chapter

    # cancel
    async def cancel(n):
        await set_status(maker, jid2_box[0], "cancelled")

    jid2_box: list[int] = []
    c2 = make(maker, HookEmbedder(cancel), llm, Site(), FakeJev(maker))
    jid2 = await add_job(maker, f"https://{DOMAIN}/novel/2")
    jid2_box.append(jid2)
    await run_next(c2)
    j2 = await job(maker, jid2)
    assert j2.status == "cancelled" and j2.lease_owner is None and j2.chapters_embedded == 1
    assert await c2.claim() is None


async def test_blocked_stops_with_reason_and_resumes_from_checkpoint(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    site.block.add("/novel/1/chapter/3")
    c = make(maker, fake_embedder, llm, site, jev)
    jid = await add_job(maker)
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "blocked" and "403" in j.error and j.chapters_embedded == 2 and j.lease_owner is None
    assert await c.claim() is None  # a block is never retried automatically (we never evade it)
    site.block.clear()
    await set_status(maker, jid, "queued")
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "done" and j.chapters_embedded == 4
    assert site.count("/novel/1/chapter/1") == 1 and site.count("/novel/1/chapter/2") == 1  # not refetched after the block


async def test_crash_is_resumed_from_checkpoint_after_the_lease_expires(env):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)

    async def crash(n):
        if n == 3:
            raise asyncio.CancelledError  # the worker process dies mid-chapter

    c = make(maker, HookEmbedder(crash), llm, site, jev)
    jid = await add_job(maker)
    claimed = await c.claim()
    with pytest.raises(asyncio.CancelledError):
        await c.run_job(*claimed)
    j = await job(maker, jid)
    assert j.status == "running" and j.checkpoint["novel"]["pos"] == 2 and j.lease_expires_at > datetime.now(UTC)
    assert await c.claim() is None  # live lease: nobody else may run it
    async with maker() as s:
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == jid).values(lease_expires_at=datetime.now(UTC) - timedelta(seconds=1)))
        await s.commit()
    c2 = make(maker, FakeEmbedder(), llm, site, jev)  # "restarted worker"
    await run_next(c2)
    j = await job(maker, jid)
    assert j.status == "done" and j.chapters_embedded == 4 and await chapter_indexes(maker) == [0, 1, 2, 4]
    assert site.count("/novel/1/chapter/1") == 1 and site.count("/novel/1/chapter/2") == 1 and site.count("/novel/1/chapter/3") == 2  # only the crashed one again


async def test_one_job_per_domain_at_a_time(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev)
    a = await add_job(maker)
    b = await add_job(maker, f"https://{DOMAIN}/novel/2")
    other = await add_job(maker, "https://other.example/novel/9", domain="other.example")
    first = await c.claim()
    second = await c.claim()
    assert first[0] == a and second[0] == other  # b waits: its domain is busy
    assert await c.claim() is None
    async with maker() as s:  # job a finishes -> b becomes claimable
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == a).values(status="done", lease_owner=None, lease_expires_at=None))
        await s.commit()
    assert (await c.claim())[0] == b


async def test_failed_job_for_unusable_urls(env, fake_embedder):
    maker, llm, site, jev = env
    site.crumbs = False
    c = make(maker, fake_embedder, llm, site, jev)
    cases = [(f"https://{DOMAIN}/list", "novel", "listing"), (f"https://{DOMAIN}/about", "novel", "not a novel page"),
             (f"https://{DOMAIN}/novel/1/chapter/1", "novel", "no link back to the novel"), (f"https://{DOMAIN}/nope", "novel", "not found")]
    for url, scope, expect in cases:
        jid = await add_job(maker, url, scope)
        await run_next(c)
        j = await job(maker, jid)
        assert j.status == "failed" and expect in j.error, (url, j.error)


@pytest.mark.parametrize("path", ["/novel/1/chapter/2", "/novel/1/chapters"])
async def test_pasted_chapter_or_chapter_list_url_continues_from_its_novel(env, fake_embedder, path):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    jid = await add_job(maker, f"https://{DOMAIN}{path}")
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert (j.novels_found, j.chapters_embedded, j.chapters_skipped) == (1, 4, 1)
    async with maker() as s:
        assert (await s.scalar(select(SourceRecord.title))) == "Moonlit Bakery"


async def test_ssrf_guard_and_missing_embedder_fail_the_job(env, maker):
    maker, llm, site, jev = env
    runner = CrawlRunner(maker, Settings(voyage_model="t"), None, Resolver(None))
    c = LinkCrawler(maker, Settings(voyage_model="t"), None, llm, None, runner, http_factory=lambda d: site.client(d))
    jid = await add_job(maker)
    await run_next(c)
    assert "VOYAGE_API_KEY" in (await job(maker, jid)).error
    # a real client (no mock transport) refuses internal addresses before any request
    c2 = make(maker, FakeEmbedder(), llm, site, jev)
    from readerserver.crawl.http import PoliteClient

    c2.http_factory = lambda d: PoliteClient("t", min_delay=0, allowed_hosts={d})
    jid2 = await add_job(maker, "http://127.0.0.1/novel/1", domain="127.0.0.1")
    await run_next(c2)
    j = await job(maker, jid2)
    assert j.status == "failed" and "internal" in j.error


async def test_chapter_text_is_persisted_only_with_embeddings(env, fake_embedder, maker):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    async with maker() as s:
        assert (await s.scalar(select(func.count()).select_from(SampleEmbedding).where(SampleEmbedding.chapter_index.is_not(None)))) > 0
        tables = (await s.execute(text("SELECT tablename FROM pg_tables WHERE schemaname='public'"))).scalars().all()
        for t in tables:
            if t == "sample_embeddings":
                chunks = (await s.scalars(select(SampleEmbedding.chunk_text))).all()
                assert chunks and all(chunks)
                assert any(SENTINEL in chunk for chunk in chunks)
                assert all("Subscribe to read" not in chunk for chunk in chunks)
                continue
            hits = (await s.execute(text(f'SELECT count(*) FROM "{t}" x WHERE x::text ILIKE :a OR x::text ILIKE :b OR x::text ILIKE :c'),
                                    {"a": f"%{SENTINEL}%", "b": "%evening wind%", "c": "%Subscribe to read%"})).scalar()
            assert hits == 0, t
    cols = {c.name for c in inspect(SampleEmbedding).columns}
    assert cols == {"id", "source_record_id", "chunk_index", "chunk_text", "token_count", "model", "embedding", "chapter_index", "chapter_hash"}
    # the Claude calls only ever saw cleaned/truncated page skeletons (text nodes capped), never whole chapters
    assert all(len(x["user"]) < 40_000 for x in llm.calls)


async def test_site_redesign_repairs_the_recipe_mid_job_without_per_page_ai(env, fake_embedder):
    from readerserver.linkcrawl.recipes import ChapterSection

    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker)
    await run_next(c)
    site.layout = "v2"  # #content -> #story: every chapter now extracts nothing with recipe v1
    llm.queue["ChapterSection"] = [ChapterSection(title="h1.ch-title", body="#story", drop=[".ads", ".share"])]
    llm.calls.clear()
    jid = await add_job(maker)
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert [x["schema"] for x in llm.calls] == ["ChapterSection"]  # one repair for the whole job, then plain parsing again
    assert (j.chapters_embedded, j.chapters_skipped) == (0, 5)  # same text as before: recognised as unchanged through the new recipe
    async with maker() as s:
        rows = (await s.scalars(select(SiteRecipe).order_by(SiteRecipe.version))).all()
    assert [(r.version, r.status) for r in rows] == [(1, "broken"), (2, "active")] and rows[1].recipe["chapter"]["body"] == "#story"
    assert "repaired chapter" in rows[1].notes


async def test_unrepairable_recipe_fails_the_job_with_a_clear_error(env, fake_embedder):
    from readerserver.linkcrawl.recipes import ChapterSection

    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker)
    await run_next(c)
    site.layout = "v2"
    llm.queue["ChapterSection"] = [ChapterSection(body="#still-wrong")]
    jid = await add_job(maker)
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "failed" and "could not write a working chapter recipe" in j.error
    async with maker() as s:
        assert (await s.scalars(select(SiteRecipe.status).where(SiteRecipe.status == "active"))).first() is None


async def test_budget_exhaustion_pauses_the_job(env, fake_embedder, maker):
    maker, llm, site, _ = env
    queue_recipes(llm, listing=False)
    broke = FakeJev(maker, cap=0.0)  # Jev's first paid call exceeds the cap
    c = make(maker, fake_embedder, llm, site, broke)
    jid = await add_job(maker)
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "paused" and "budget" in j.error and j.lease_owner is None


async def test_claim_skips_deferred_jobs_and_robots_block_is_blocked(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev)
    jid = await add_job(maker)
    async with maker() as s:
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == jid).values(next_eligible_at=datetime.now(UTC) + timedelta(hours=1)))
        await s.commit()
    assert await c.claim() is None  # Retry-After deferral is persisted, not slept
    import httpx

    site2 = Site()
    base = site2.handle
    site2.handle = lambda req: httpx.Response(200, text="User-agent: *\nDisallow: /novel/\n") if req.url.path == "/robots.txt" else base(req)
    c2 = make(maker, fake_embedder, llm, site2, jev)
    async with maker() as s:
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == jid).values(next_eligible_at=None))
        await s.commit()
    await run_next(c2)
    j = await job(maker, jid)
    assert j.status == "blocked" and "robots.txt" in j.error


# ---------------------------------------------------------------- review fixes
async def assert_no_prose(maker):
    """Prose belongs only in embeddings; recipes, notes and errors must not leak it."""
    async with maker() as s:
        for t in (await s.execute(text("SELECT tablename FROM pg_tables WHERE schemaname='public'"))).scalars().all():
            if t == "sample_embeddings":
                continue
            n = (await s.execute(text(f'SELECT count(*) FROM "{t}" x WHERE x::text ILIKE :a OR x::text ILIKE :b OR x::text ILIKE :c'),
                                 {"a": f"%{SENTINEL}%", "b": "%evening wind%", "c": "%Subscribe to read%"})).scalar()
            assert n == 0, t


async def test_run_due_tops_up_without_waiting_for_long_jobs(env):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    gate = asyncio.Event()

    slow: list[str] = []

    class Slow(FakeEmbedder):
        async def embed(self, texts, input_type, purpose="embed", patient=True):
            if purpose in slow:
                await gate.wait()  # job a's chapters take "forever"
            return await super().embed(texts, input_type, purpose, patient)

    c = make(maker, Slow(), llm, site, jev)
    a = await add_job(maker)
    slow.append(f"lc{a}:embed")
    b = await add_job(maker, f"https://other.example/novel/2", domain="other.example")
    assert await c.run_due(max_jobs=2) == 2
    for _ in range(200):
        await asyncio.sleep(0.02)
        if (await job(maker, b)).status == "done":
            break
    assert (await job(maker, b)).status == "done" and (await job(maker, a)).status == "running"  # b finished while a is stuck
    third = await add_job(maker, f"https://third.example/novel/3", domain="third.example")
    same = await add_job(maker, f"https://{DOMAIN}/novel/2")  # same domain as the running a: must wait
    assert await c.run_due(max_jobs=2) == 1  # tops up one slot: third, not `same`
    await asyncio.sleep(0.2)
    assert (await job(maker, same)).status == "queued"
    gate.set()
    await c.wait_idle()
    assert await c.run_due(max_jobs=2) == 1  # a is done: the same-domain job is now claimable
    await c.wait_idle()
    assert {(await job(maker, i)).status for i in (a, b, same)} <= {"done", "failed"} and (await job(maker, a)).status == "done"
    assert (await job(maker, third)).status in ("done", "failed")


async def test_claim_never_overwrites_a_pause_the_api_is_committing(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev)
    jid = await add_job(maker)
    async with maker() as api:  # the API's pause: row locked, not yet committed
        row = (await api.scalars(select(LinkCrawlJob).where(LinkCrawlJob.id == jid).with_for_update())).one()
        row.status = "paused"
        assert await c.claim() is None  # SKIP LOCKED: the locked row is left alone
        await api.commit()
    assert await c.claim() is None and (await job(maker, jid)).status == "paused"


async def test_reclaims_are_limited_and_live_leases_block_the_domain(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev, link_max_attempts=2)
    dead = await add_job(maker)
    expired = datetime.now(UTC) - timedelta(minutes=1)
    for attempts in (0, 1):
        async with maker() as s:
            await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == dead).values(status="running", lease_owner="x", lease_expires_at=expired, attempts=attempts))
            await s.commit()
        claimed = await c.claim()
        assert claimed and claimed[0] == dead and (await job(maker, dead)).attempts == attempts + 1
    async with maker() as s:  # third reclaim: the worker keeps dying on this job
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == dead).values(status="running", lease_owner="x", lease_expires_at=expired))
        await s.commit()
    assert await c.claim() is None
    j = await job(maker, dead)
    assert j.status == "failed" and "giving up" in j.error
    # a paused-then-resumed job whose old worker still holds a live lease blocks other jobs of the domain
    old = await add_job(maker)
    other = await add_job(maker)
    async with maker() as s:
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == old).values(status="queued", lease_owner="old-worker", lease_expires_at=datetime.now(UTC) + timedelta(minutes=5)))
        await s.commit()
    assert await c.claim() is None and (await job(maker, other)).status == "queued"


async def test_recipe_version_collisions_are_retried(maker):
    from readerserver.linkcrawl.recipes import RecipeStore

    store = RecipeStore(maker)
    rows = await asyncio.gather(*(store.create(DOMAIN, {"chapter": {"body": f"#{i}"}}, f"n{i}") for i in range(4)))
    assert sorted(r.version for r in rows) == [1, 2, 3, 4]
    assert [r.status for r in await store.versions(DOMAIN)].count("active") == 1


async def test_site_crawl_dedupes_novels_across_listing_pages(env, fake_embedder):
    maker, llm, site, jev = env
    site.dup = True  # listing page 2 repeats novel 1
    queue_recipes(llm)
    jid = await add_job(maker, LIST, "site", per=1)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done" and j.novels_found == 4 and site.count("/novel/1") == 1


async def test_chapters_per_novel_stops_chapter_list_paging_early(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    jid = await add_job(maker, per=2)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done" and j.chapters_embedded == 2
    assert site.count("/novel/1/chapters?page=2") == 0  # 3 chapters on the first toc page already cover the 2 wanted (ascending list)
    async with maker() as s:
        assert (await s.scalar(select(SourceRecord.chapter_count))) is None  # total unknown: it was not read to the end


async def test_chapters_that_disappear_from_the_toc_lose_their_vectors(env, fake_embedder, monkeypatch):
    from . import linksite

    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker)
    await run_next(c)
    assert await chapter_indexes(maker) == [0, 1, 2, 4]
    monkeypatch.setitem(linksite.CHAPTERS, 1, 3)  # the novel now has 3 chapters
    await add_job(maker)
    await run_next(c)
    assert await chapter_indexes(maker) == [0, 1, 2]  # index 4 was stale


async def test_missing_pages_in_a_row_fail_the_job_instead_of_ending_done(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker)
    await run_next(c)
    site.missing |= {f"/novel/1/chapter/{k}" for k in range(1, 6)}
    jid = await add_job(maker)
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "failed" and "not found" in j.error


async def test_locked_page_with_no_chapter_container_is_skipped_not_a_recipe_repair(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    site.locked, site.paywall = set(), {(1, 4)}  # chapter 4 is a lock page without the recipe's container
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done" and (j.chapters_embedded, j.chapters_skipped) == (4, 1)
    async with maker() as s:
        assert [(r.version, r.status) for r in (await s.scalars(select(SiteRecipe))).all()] == [(1, "active")]  # nothing was "repaired"
    assert [x["schema"] for x in llm.calls] == ["NovelSection", "ChapterSection"]


async def test_quality_complaint_alone_never_takes_the_recipe_away(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker)
    await run_next(c)  # recipe v1 built and validated
    grumpy = FakeJev(maker)
    grumpy.answer_quality = lambda st: "has_junk"  # e.g. every chapter carries translator notes
    c2 = make(maker, fake_embedder, llm, site, grumpy)
    jid = await add_job(maker)
    await run_next(c2)
    j = await job(maker, jid)
    assert j.status == "done", j.error  # the job carries on with the recipe it has
    async with maker() as s:
        rows = (await s.scalars(select(SiteRecipe))).all()
    assert [(r.version, r.status) for r in rows] == [(1, "active")]
    assert "quality" in grumpy.calls


async def test_prose_is_not_leaked_into_metadata_on_repair_paths(env, fake_embedder):
    from readerserver.linkcrawl.recipes import ChapterSection

    maker, llm, site, _ = env
    # 1) Jev always says has_junk when the extracted text contains chapter prose: no recipe ever validates -> the job fails
    #    with a label-only error; the excerpts of prose in the feedback went to Claude only.
    class Picky(FakeJev):
        def answer_quality(self, st):
            return "has_junk" if SENTINEL in json.dumps(st["extracted"]) else super().answer_quality(st)

    llm.add("NovelSection", NOVEL_SECTION)
    llm.add("ChapterSection", CHAPTER_SECTION)
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, Picky(maker)))
    j = await job(maker, jid)
    assert j.status == "failed" and "could not write a working chapter recipe" in j.error and "judged has_junk" in j.error
    assert SENTINEL in "".join(x["user"] for x in llm.calls)  # the excerpt really was sent to Claude as feedback...
    await assert_no_prose(maker)  # ...but is nowhere in the database
    # 2) a repair that succeeds after a has_junk attempt: notes store the label only
    llm.calls.clear()
    llm.queue.clear()
    queue_recipes(llm, listing=False)
    await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, FakeJev(maker)))
    site.layout = "v2"
    llm.queue["ChapterSection"] = [ChapterSection(body="#story"), ChapterSection(title="h1.ch-title", body="#story", drop=[".ads", ".share"])]
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, FakeJev(maker)))
    j = await job(maker, jid)
    assert j.status == "done", j.error
    async with maker() as s:
        active = (await s.scalars(select(SiteRecipe).where(SiteRecipe.status == "active"))).one()
    assert active.notes.startswith("repaired chapter") and "Advertisement" not in active.notes
    assert "has_junk" in llm.calls[-1]["user"] and "Advertisement" in llm.calls[-1]["user"]  # feedback to Claude quoted the junk
    await assert_no_prose(maker)
    # 3) repair failure: Claude never finds a working recipe
    llm.queue["ChapterSection"] = [ChapterSection(body="#still-wrong")]
    site.layout = "v1"
    site.layout = "v2"
    async with maker() as s:  # break the active recipe again so the next job must repair
        await s.execute(update(SiteRecipe).where(SiteRecipe.status == "active").values(recipe=text("jsonb_set(recipe, '{chapter,body}', '\"#content\"')")))
        await s.commit()
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, FakeJev(maker)))
    j = await job(maker, jid)
    assert j.status == "failed" and SENTINEL not in (j.error or "")
    await assert_no_prose(maker)
