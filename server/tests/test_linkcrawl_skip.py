"""Cross-job novel skipping for site jobs, and the opt-in recurring refresh."""
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest
from sqlalchemy import select, update

from readerserver.linkcrawl.crawler import source_id_for
from readerserver.models import DIM, LinkCrawlJob, SampleEmbedding, SourceRecord
from readerserver.resolution.resolver import seed_tags

from .linksite import DOMAIN, FakeJev, Site, queue_recipes
from .test_linkcrawl_jobs import NOVEL1, add_job, job, make, run_next

URL = {n: f"https://{DOMAIN}/novel/{n}" for n in range(1, 5)}


@pytest.fixture
async def env(maker, fake_llm):
    async with maker() as s:
        await seed_tags(s)
    return maker, fake_llm, Site(), FakeJev(maker)


def novel_fetches(site: Site) -> list[str]:
    return [r for r in site.requests if r.startswith("/novel/") and r.count("/") == 2]


async def record(maker, n=1) -> SourceRecord:
    async with maker() as s:
        return await s.scalar(select(SourceRecord).where(SourceRecord.source_id == source_id_for(URL[n])))


async def seed_record(maker, n, raw, embedded=False) -> int:
    async with maker() as s:
        r = SourceRecord(source="link", source_id=source_id_for(URL[n]), title=f"n{n}", url=URL[n], raw=raw)
        s.add(r)
        await s.flush()
        if embedded:
            s.add(SampleEmbedding(source_record_id=r.id, chunk_index=0, chunk_text="x", model="m", embedding=[0.1] * DIM, chapter_index=0, chapter_hash="h"))
        await s.commit()
        return r.id


async def known(c, maker, per, *ns) -> set[int]:
    ids = {source_id_for(URL[n]): n for n in ns}
    return {ids[sid] for sid in await c._known_complete(SimpleNamespace(per_novel=per), list(ids))}


async def test_second_job_skips_completed_novels_and_crawls_new_ones_on_the_same_page(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker, f"https://{DOMAIN}/list-a", "site", per=2)
    await run_next(c)
    assert sorted(novel_fetches(site)) == ["/novel/1", "/novel/2"]
    site.requests.clear()
    jid = await add_job(maker, f"https://{DOMAIN}/list-b", "site", per=2)
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert sorted(novel_fetches(site)) == ["/novel/3", "/novel/4"]  # 1 and 2 are on page 1 with the unknown 3
    assert j.novels_found == 2 and j.checkpoint["novels_queued"] == 2 and j.checkpoint["known_skipped"] == 2
    assert sorted(j.checkpoint["queued_ids"]) == sorted(source_id_for(URL[n]) for n in (3, 4))
    marker = (await record(maker, 3)).raw["crawl"]
    assert marker["job_id"] == jid and marker["chapters_per_novel"] == 2 and marker["completed_at"]


async def test_all_known_first_page_does_not_stop_the_scan(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker, f"https://{DOMAIN}/list-a", "site", per=2)
    await run_next(c)
    site.requests.clear()
    jid = await add_job(maker, f"https://{DOMAIN}/list-c", "site", per=2)  # page 1 = [1, 2] (known), page 2 = [3]
    await run_next(c)
    j = await job(maker, jid)
    assert j.status == "done" and novel_fetches(site) == ["/novel/3"]
    assert j.checkpoint["known_skipped"] == 2 and j.checkpoint["listing_pages"] == 2


async def test_partly_crawled_record_is_retried_but_marked_empty_novel_is_not(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev)
    done = {"crawl": {"job_id": 1, "chapters_per_novel": 2, "completed_at": datetime.now(UTC).isoformat()}}
    await seed_record(maker, 1, {"job_id": 1})  # created, chapters never crawled (and no marker)
    await seed_record(maker, 2, {"job_id": 1} | done)  # marker, zero chapters (only empty pages)
    async with maker() as s:
        s.add(LinkCrawlJob(id=1, url="x", domain=DOMAIN, scope="site", chapters_per_novel=2))
        await s.commit()
    assert await known(c, maker, 2, 1, 2) == {2}


async def test_legacy_record_rule(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev)
    async with maker() as s:
        s.add_all([LinkCrawlJob(id=10, url="x", domain=DOMAIN, scope="site", chapters_per_novel=3),
                   LinkCrawlJob(id=11, url="x", domain=DOMAIN, scope="site", chapters_per_novel=None)])
        await s.commit()
    await seed_record(maker, 1, {"job_id": 10}, embedded=True)
    await seed_record(maker, 2, {"job_id": 10}, embedded=False)  # no stored chapter
    await seed_record(maker, 3, {"job_id": 11}, embedded=True)
    await seed_record(maker, 4, {"job_id": 99}, embedded=True)  # job is gone
    assert await known(c, maker, 3, 1, 2, 3, 4) == {1, 3}
    assert await known(c, maker, 2, 1) == {1}  # job asks for less than 3
    assert await known(c, maker, 5, 1, 3) == {3}  # job asks for more than the capped crawl
    assert await known(c, maker, None, 1, 3) == {3}  # unlimited never skips capped records


async def test_marker_limits(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev)
    stamp = datetime.now(UTC).isoformat()
    await seed_record(maker, 1, {"crawl": {"job_id": 1, "chapters_per_novel": 3, "completed_at": stamp}})
    await seed_record(maker, 2, {"crawl": {"job_id": 1, "chapters_per_novel": None, "completed_at": stamp}})
    assert await known(c, maker, 3, 1, 2) == {1, 2}
    assert await known(c, maker, 4, 1, 2) == {2}
    assert await known(c, maker, None, 1, 2) == {2}


async def test_larger_limit_job_recrawls_capped_novels(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker, f"https://{DOMAIN}/list-a", "site", per=1)
    await run_next(c)
    site.requests.clear()
    jid = await add_job(maker, f"https://{DOMAIN}/list-a", "site", per=None)
    await run_next(c)
    j = await job(maker, jid)
    assert sorted(novel_fetches(site)) == ["/novel/1", "/novel/2"] and "known_skipped" not in j.checkpoint
    assert (await record(maker, 1)).raw["crawl"]["chapters_per_novel"] is None


async def test_novel_scope_always_crawls(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    for _ in range(2):
        site.requests.clear()
        jid = await add_job(maker, NOVEL1, "novel", per=2)
        await run_next(c)
        assert (await job(maker, jid)).status == "done" and "/novel/1" in site.requests


async def test_failed_recrawl_no_longer_looks_complete(env, fake_embedder):
    """Decision: finalizing a re-crawl replaces raw (upsert_record), which drops the completion marker and leaves an
    in-progress one (completed_at null). If the re-crawl dies partway, the novel is retried by the next job."""
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    c = make(maker, fake_embedder, llm, site, jev)
    await add_job(maker, NOVEL1, "novel", per=2)
    await run_next(c)
    assert await known(c, maker, 2, 1) == {1}
    site.block = {"/novel/1/chapter/1"}
    jid = await add_job(maker, NOVEL1, "novel", per=2)
    await run_next(c)
    assert (await job(maker, jid)).status == "blocked"
    assert (await record(maker, 1)).raw["crawl"]["completed_at"] is None
    assert await known(c, maker, 2, 1) == set()
    site.block = set()
    await c.claim()  # nothing runnable is left after the block; the retry is a new job
    jid = await add_job(maker, NOVEL1, "novel", per=2)
    await run_next(c)
    assert await known(c, maker, 2, 1) == {1}


# ---------------------------------------------------------------- refresh
A, B = f"https://{DOMAIN}/new-novel", f"https://{DOMAIN}/hot-novel"


async def jobs(maker, url=None) -> list[LinkCrawlJob]:
    async with maker() as s:
        q = select(LinkCrawlJob).order_by(LinkCrawlJob.id)
        return list((await s.scalars(q.where(LinkCrawlJob.url == url) if url else q)).all())


async def test_refresh_is_off_by_default(env, fake_embedder):
    maker, llm, site, jev = env
    assert make(maker, fake_embedder, llm, site, jev).settings.link_refresh_urls == ""
    assert await make(maker, fake_embedder, llm, site, jev).schedule_refresh() == 0
    assert await jobs(maker) == []


async def test_refresh_creates_one_job_and_no_duplicates(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev, link_refresh_urls=f" {A} , ,{B}", link_refresh_chapters_per_novel=2)
    assert await c.schedule_refresh() == 2
    assert await c.schedule_refresh() == 0  # both queued
    got = await jobs(maker, A)
    assert len(got) == 1 and (got[0].scope, got[0].status, got[0].chapters_per_novel, got[0].domain) == ("site", "queued", 2, DOMAIN)
    for status in ("running", "paused", "blocked"):
        async with maker() as s:
            await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.url == A).values(status=status, updated_at=datetime.now(UTC) - timedelta(days=30)))
            await s.commit()
        assert await c.schedule_refresh() == 0, status


async def _age(maker, url, status, age):
    async with maker() as s:
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.url == url).values(status=status, updated_at=datetime.now(UTC) - age))
        await s.commit()


async def test_refresh_waits_for_the_interval_then_creates_again(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev, link_refresh_urls=A, link_refresh_hours=24)
    await c.schedule_refresh()
    await _age(maker, A, "done", timedelta(hours=2))
    assert await c.schedule_refresh() == 0
    await _age(maker, A, "done", timedelta(hours=25))
    assert await c.schedule_refresh() == 1
    assert [j.status for j in await jobs(maker, A)] == ["done", "queued"]


async def test_failed_refresh_job_is_not_retried_inside_the_interval(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev, link_refresh_urls=A, link_refresh_hours=24)
    await c.schedule_refresh()
    await _age(maker, A, "failed", timedelta(hours=1))
    assert await c.schedule_refresh() == 0
    await _age(maker, A, "failed", timedelta(hours=30))
    assert await c.schedule_refresh() == 1


async def test_refresh_ignores_bad_urls_and_never_raises(env, fake_embedder):
    maker, llm, site, jev = env
    c = make(maker, fake_embedder, llm, site, jev, link_refresh_urls=f"ftp://x/y,notaurl,{A}")
    assert await c.schedule_refresh() == 1
    broken = make(maker, fake_embedder, llm, site, jev, link_refresh_urls=A)
    broken.maker = lambda: (_ for _ in ()).throw(RuntimeError("db down"))
    assert await broken.schedule_refresh() == 0
