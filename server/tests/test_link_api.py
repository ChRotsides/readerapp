"""HTTP API: /crawl-jobs and /library/chapters (bearer auth, shapes the phone app relies on)."""
from datetime import datetime

import pytest
from sqlalchemy import func, select, text

from readerserver.ai.spend import BudgetExceeded
from readerserver.models import LinkCrawlJob, SampleEmbedding, SourceRecord, Work
from readerserver.resolution.resolver import seed_tags

from .conftest import FakeEmbedder
from .linksite import SENTINEL, prose
from .test_api import H, client

JOB_KEYS = {"id", "url", "scope", "chapters_per_novel", "status", "pages_fetched", "novels_found", "chapters_embedded", "chapters_skipped",
            "spend_usd", "error", "created_at", "updated_at"}


async def test_every_new_route_needs_the_bearer_token(maker):
    async with client(maker, embedder=FakeEmbedder()) as c:
        routes = [("post", "/crawl-jobs"), ("get", "/crawl-jobs"), ("get", "/crawl-jobs/1"), ("post", "/crawl-jobs/1/pause"),
                  ("post", "/crawl-jobs/1/resume"), ("post", "/crawl-jobs/1/cancel"), ("post", "/library/chapters")]
        for method, path in routes:
            for headers in ({}, {"Authorization": "Bearer wrong"}):
                r = await getattr(c, method)(path, headers=headers)
                assert r.status_code == 401, (method, path, headers)


async def test_job_create_list_get_and_shape(maker):
    async with client(maker) as c:
        r = await c.post("/crawl-jobs", json={"url": " https://www.Novels.example/novel/1 ", "scope": "novel", "chapters_per_novel": None}, headers=H)
        assert r.status_code == 202
        j = r.json()
        assert set(j) == JOB_KEYS and j["id"] == 1 and j["status"] == "queued" and j["url"] == "https://www.Novels.example/novel/1"
        assert (j["scope"], j["chapters_per_novel"], j["pages_fetched"], j["novels_found"], j["chapters_embedded"], j["chapters_skipped"]) == ("novel", None, 0, 0, 0, 0)
        assert j["spend_usd"] == 0.0 and isinstance(j["spend_usd"], float) and j["error"] is None
        datetime.fromisoformat(j["created_at"])
        datetime.fromisoformat(j["updated_at"])
        async with maker() as s:
            assert (await s.scalar(select(LinkCrawlJob.domain))) == "novels.example"
        r2 = await c.post("/crawl-jobs", json={"url": "https://a.example/list", "scope": "site", "chapters_per_novel": 3}, headers=H)
        assert r2.status_code == 202 and r2.json()["chapters_per_novel"] == 3
        listing = (await c.get("/crawl-jobs", headers=H)).json()
        assert [x["id"] for x in listing] == [2, 1] and all(set(x) == JOB_KEYS for x in listing)  # newest first
        assert (await c.get("/crawl-jobs/1", headers=H)).json()["id"] == 1
        assert (await c.get("/crawl-jobs/99", headers=H)).status_code == 404
        assert (await c.post("/crawl-jobs/99/pause", headers=H)).status_code == 404


async def test_job_validation(maker):
    async with client(maker) as c:
        for body in ({"url": "ftp://x.example/a", "scope": "novel"}, {"url": "not a url", "scope": "novel"}, {"url": "https://x.example", "scope": "all"},
                     {"url": "https://x.example", "scope": "novel", "chapters_per_novel": 0}, {"scope": "novel"}):
            assert (await c.post("/crawl-jobs", json=body, headers=H)).status_code == 422, body


async def test_pause_resume_cancel_transitions(maker):
    async with client(maker) as c:
        jid = (await c.post("/crawl-jobs", json={"url": "https://x.example/n", "scope": "novel"}, headers=H)).json()["id"]
        p = f"/crawl-jobs/{jid}"
        assert (await c.post(p + "/pause", headers=H)).json()["status"] == "paused"
        assert (await c.post(p + "/pause", headers=H)).json()["status"] == "paused"  # idempotent
        assert (await c.post(p + "/resume", headers=H)).json()["status"] == "queued"
        async with maker() as s:  # the worker is running it
            (await s.get(LinkCrawlJob, jid)).status = "running"
            await s.commit()
        assert (await c.post(p + "/pause", headers=H)).json()["status"] == "paused"  # the worker notices between pages
        async with maker() as s:
            row = await s.get(LinkCrawlJob, jid)
            row.status, row.error = "blocked", "403 blocked"
            await s.commit()
        r = await c.post(p + "/resume", headers=H)
        assert r.json()["status"] == "queued" and r.json()["error"] is None
        assert (await c.post(p + "/cancel", headers=H)).json()["status"] == "cancelled"
        assert (await c.post(p + "/cancel", headers=H)).json()["status"] == "cancelled"
        assert (await c.post(p + "/pause", headers=H)).status_code == 409 and (await c.post(p + "/resume", headers=H)).status_code == 409
        async with maker() as s:
            (await s.get(LinkCrawlJob, jid)).status = "done"
            await s.commit()
        assert (await c.post(p + "/pause", headers=H)).status_code == 409 and (await c.post(p + "/cancel", headers=H)).status_code == 409


# ---------------------------------------------------------------- /library/chapters
def body(n=3, **over):
    chapters = [{"index": i, "title": f"Chapter {i}", "url": f"https://novels.example/n/1/{i}", "text": "\n\n".join(prose(1, i + 1))} for i in range(n)]
    novel = {"title": "Moonlit Bakery", "author": "Ann Baker", "synopsis": "A baker and a dragon.", "url": "https://novels.example/n/1", "domain": "novels.example"}
    return {"novel": novel, "chapters": chapters} | over


async def test_library_chapters_embed_dedupe_and_store_chunks(maker, fake_embedder):
    async with maker() as s:
        await seed_tags(s)
    async with client(maker, embedder=fake_embedder) as c:
        r = await c.post("/library/chapters", json=body(), headers=H)
        assert r.status_code == 200 and r.json() == {"embedded": 3, "skipped": 0}
        assert (await c.post("/library/chapters", json=body(), headers=H)).json() == {"embedded": 0, "skipped": 3}  # unchanged (hash)
        b = body(4)
        b["chapters"][1]["text"] += " " + "A changed closing paragraph. " * 60
        b["chapters"][3]["text"] = "   "
        assert (await c.post("/library/chapters", json=b, headers=H)).json() == {"embedded": 1, "skipped": 3}  # edited one re-embedded, blank skipped
    async with maker() as s:  # the request only stores + embeds; resolution is the worker's job (it must not wait on the resolve lock)
        assert (await s.scalars(select(SourceRecord.needs_resolution))).one() is True and (await s.scalar(select(func.count()).select_from(Work))) == 0
    from readerserver.config import Settings
    from readerserver.crawl.runner import CrawlRunner
    from readerserver.resolution.resolver import Resolver

    await CrawlRunner(maker, Settings(voyage_model="test-model"), fake_embedder, Resolver(None)).process_pending()  # what the worker loop does
    async with maker() as s:
        rec = (await s.scalars(select(SourceRecord))).one()
        assert (rec.source, rec.title, rec.authors, rec.url, rec.raw) == ("library", "Moonlit Bakery", ["Ann Baker"], "https://novels.example/n/1", {"domain": "novels.example"})
        assert (await s.scalar(select(func.count()).select_from(Work))) == 1 and not rec.needs_resolution
        idx = sorted({i for (i,) in (await s.execute(select(SampleEmbedding.chapter_index).where(SampleEmbedding.source_record_id == rec.id))).all()})
        assert idx == [0, 1, 2]
        dup = await s.scalar(text("SELECT count(*) - count(DISTINCT (source_record_id, chapter_index, chunk_index)) FROM sample_embeddings"))
        assert dup == 0
        for t in (await s.execute(text("SELECT tablename FROM pg_tables WHERE schemaname='public'"))).scalars().all():
            if t == "sample_embeddings":
                rows = (await s.scalars(select(SampleEmbedding))).all()
                assert rows and all(row.chunk_text for row in rows)
                assert all(list(row.embedding) == pytest.approx(fake_embedder._vec(row.chunk_text)) for row in rows)
                continue
            n = (await s.execute(text(f'SELECT count(*) FROM "{t}" x WHERE x::text ILIKE :a OR x::text ILIKE :b'), {"a": f"%{SENTINEL}%", "b": "%evening wind%"})).scalar()
            assert n == 0, t


async def test_library_chapters_limits_and_provider_errors(maker, fake_embedder):
    async with client(maker, embedder=fake_embedder) as c:
        assert (await c.post("/library/chapters", json=body(51), headers=H)).status_code == 422  # over 50 chapters
        assert (await c.post("/library/chapters", json=body(0), headers=H)).status_code == 422
        dup = body(2)
        dup["chapters"][1]["index"] = 0
        assert (await c.post("/library/chapters", json=dup, headers=H)).status_code == 422
        bad = body(1)
        bad["novel"]["title"] = ""
        assert (await c.post("/library/chapters", json=bad, headers=H)).status_code == 422
        huge = body(1)
        huge["chapters"][0]["text"] = "x" * 200_001
        assert (await c.post("/library/chapters", json=huge, headers=H)).status_code == 422
    async with client(maker) as c:  # no embedder configured
        assert (await c.post("/library/chapters", json=body(), headers=H)).status_code == 503

    class Broke(FakeEmbedder):
        async def embed(self, *a, **k):
            raise BudgetExceeded("cap")

    async with client(maker, embedder=Broke()) as c:
        assert (await c.post("/library/chapters", json=body(), headers=H)).status_code == 402


async def test_library_embeds_impatiently_and_maps_rate_limits_to_429(maker):
    from readerserver.ai.embeddings import EmbeddingRateLimited

    seen = []

    class Limited(FakeEmbedder):
        async def embed(self, texts, input_type, purpose="embed", patient=True):
            seen.append(patient)
            raise EmbeddingRateLimited("3 rpm")

    async with client(maker, embedder=Limited()) as c:
        r = await c.post("/library/chapters", json=body(1), headers=H)
    assert r.status_code == 429 and seen == [False]  # never sleeps in the request
