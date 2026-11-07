import pytest
from sqlalchemy import select

from readerserver.models import LinkCrawlJob, SampleEmbedding

from .linksite import queue_recipes
from .test_api import H, client
from .test_linkcrawl_jobs import LIST, add_job, env, job, make, run_next, set_status  # noqa: F401


@pytest.mark.parametrize("limit", [2, 3])
async def test_novel_cap_preserves_midpage_and_next_page_work(env, fake_embedder, limit):
    maker, llm, site, jev = env
    queue_recipes(llm)
    crawler = make(maker, fake_embedder, llm, site, jev, link_max_novels=limit)
    jid = await add_job(maker, LIST, scope="site", per=1)
    await run_next(crawler)
    stopped = await job(maker, jid)
    assert stopped.status == "paused" and stopped.stop_reason == "novel_limit"
    assert stopped.novels_found == limit and stopped.error and stopped.lease_owner is None
    assert stopped.checkpoint["listing_next"] or stopped.checkpoint["listing_pending"]
    counts = {path: site.count(path) for path in site.requests if "/novel/" in path}
    calls = len(fake_embedder.calls)
    async with client(maker, embedder=fake_embedder) as api:
        resumed = await api.post(f"/crawl-jobs/{jid}/resume", headers=H)
        assert resumed.status_code == 200 and resumed.json()["stop_reason"] is None
    await run_next(make(maker, fake_embedder, llm, site, jev, link_max_novels=10))
    done = await job(maker, jid)
    assert done.status == "done" and done.stop_reason == "catalog_exhausted" and done.novels_found == 4
    assert done.checkpoint["phase"] == "crawl"
    assert all(site.count(path) == n for path, n in counts.items())
    assert len(fake_embedder.calls) > calls  # only newly discovered novels are embedded


async def test_exact_cap_at_actual_catalog_end_finishes_successfully(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm)
    jid = await add_job(maker, LIST, scope="site", per=1)
    await run_next(make(maker, fake_embedder, llm, site, jev, link_max_novels=4))
    done = await job(maker, jid)
    assert done.status == "done" and done.stop_reason == "catalog_exhausted"


async def test_listing_cap_retains_next_url_and_resumes_without_refetching(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm)
    jid = await add_job(maker, LIST, scope="site", per=1)
    await run_next(make(maker, fake_embedder, llm, site, jev, link_max_listing_pages=1))
    stopped = await job(maker, jid)
    assert stopped.status == "paused" and stopped.stop_reason == "listing_page_limit"
    assert stopped.checkpoint["listing_next"] == LIST + "?page=2"
    assert stopped.novels_found == 3 and site.count("/list?page=2") == 0
    await set_status(maker, jid, "queued")
    await run_next(make(maker, fake_embedder, llm, site, jev, link_max_listing_pages=10))
    assert (await job(maker, jid)).novels_found == 4
    assert site.count("/list") == 1


async def test_toc_limit_pauses_with_next_page_instead_of_discarding_it(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev, link_max_toc_pages=2))
    stopped = await job(maker, jid)
    assert stopped.status == "paused" and stopped.stop_reason == "toc_page_limit"
    assert stopped.checkpoint["novel"]["toc_next"].endswith("page=2")
    await set_status(maker, jid, "queued")
    await run_next(make(maker, fake_embedder, llm, site, jev, link_max_toc_pages=10))
    done = await job(maker, jid)
    assert done.status == "done" and done.stop_reason == "novel_complete"
    assert done.chapters_embedded == 4


async def test_missing_later_listing_is_failed_and_retryable(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm)
    site.missing.add("/list?page=2")
    jid = await add_job(maker, LIST, scope="site", per=1)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    stopped = await job(maker, jid)
    assert stopped.status == "failed" and stopped.stop_reason == "listing_error"
    assert stopped.checkpoint["listing_next"] == LIST + "?page=2"
    site.missing.clear()
    await set_status(maker, jid, "queued")
    await run_next(make(maker, fake_embedder, llm, site, jev))
    assert (await job(maker, jid)).novels_found == 4


async def test_legacy_done_recovery_skips_processed_novels_and_vectors(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm)
    jid = await add_job(maker, LIST, scope="site", per=1)
    await run_next(make(maker, fake_embedder, llm, site, jev, link_max_novels=2))
    async with maker() as s:
        old = await s.get(LinkCrawlJob, jid)
        old.status, old.checkpoint, old.stop_reason = "done", {}, "legacy_completion"
        await s.commit()
        before = [(r.id, list(r.embedding)) for r in (await s.scalars(select(SampleEmbedding))).all()]
    old_novel_paths = {path: site.count(path) for path in site.requests if "/novel/" in path}
    async with client(maker, embedder=fake_embedder) as api:
        response = await api.post(f"/crawl-jobs/{jid}/resume", headers=H)
        assert response.status_code == 200 and response.json()["status"] == "queued"
    await run_next(make(maker, fake_embedder, llm, site, jev, link_max_novels=10))
    done = await job(maker, jid)
    assert done.status == "done" and done.novels_found == 4
    assert done.checkpoint["recovered_novels"] == 2
    assert all(site.count(path) == n for path, n in old_novel_paths.items())
    async with maker() as s:
        after = {r.id: list(r.embedding) for r in (await s.scalars(select(SampleEmbedding))).all()}
    assert all(after[rid] == vector for rid, vector in before)
    async with client(maker, embedder=fake_embedder) as api:
        # New successful runs have an explicit end-of-catalog reason, so resume cannot restart them accidentally.
        assert (await api.post(f"/crawl-jobs/{jid}/resume", headers=H)).status_code == 409
