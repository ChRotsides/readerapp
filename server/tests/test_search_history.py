"""Saved search history: /search saves successful searches, /searches routes (bearer auth, tombstones, since)."""
import uuid

import httpx
from sqlalchemy import select

from readerserver.ai.spend import SpendTracker
from readerserver.api.main import create_app
from readerserver.config import Settings
from readerserver.models import SearchHistory
from readerserver.search.schemas import RerankItem, RerankOutput
from readerserver.services import Services

from .test_search import corpus, hyde, work_ids  # noqa: F401  (corpus is a fixture)

AUTH = {"Authorization": "Bearer test-token"}


def api(maker, embedder, llm):
    sv = Services(Settings(api_token="test-token"), maker, SpendTracker(maker, 5.0), embedder, llm, None)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(sv)), base_url="http://t", headers=AUTH)


async def search(c, llm, ids, description="dragon cook", **kw):
    llm.queue.clear()  # FakeLLM only pops while more than one output is queued
    llm.add("HydeOutput", hyde())
    llm.add("RerankOutput", RerankOutput(ranking=[RerankItem(work_id=i, reason=f"reason {n}") for n, i in enumerate(ids)]))
    return await c.post("/search", json={"description": description, **kw})


async def test_search_saves_an_entry_and_returns_history_id(maker, corpus, fake_embedder, fake_llm):  # noqa: F811
    ids = await work_ids(maker)
    order = [ids["Moonlit Bakery"], ids["Dragon Chef"]]
    async with api(maker, fake_embedder, fake_llm) as c:
        r = await search(c, fake_llm, order)
        assert r.status_code == 200
        hid = r.json()["history_id"]
        uuid.UUID(hid)
        full = (await c.get(f"/searches/{hid}")).json()
        assert full["description"] == "dragon cook" and full["result_count"] == 2 and full["deleted"] is False
        assert [x["work_id"] for x in full["results"]] == order == [x["work_id"] for x in r.json()["results"]]
        first = full["results"][0]
        assert first["reason"] == "reason 0" and "score" in first and "matched_scenes" in first
        assert {"title", "authors", "cover_url", "synopsis", "tags", "status", "chapter_count"} <= set(first)
        assert full["request"]["description"] == "dragon cook"
        assert full["scenes"]["scenes"] == hyde().scenes and full["scenes"]["synopsis"] and full["scenes"]["tags"] == ["Cooking", "Dragons"]
        assert "filters" in full["scenes"] and full["scenes"]["reranked"] is True


async def test_failed_search_is_not_saved(maker, corpus, fake_embedder, fake_llm):  # noqa: F811
    class Boom:
        async def embed(self, *a, **k):
            from readerserver.ai.embeddings import EmbeddingRateLimited

            raise EmbeddingRateLimited("slow down")

    async with api(maker, Boom(), fake_llm) as c:
        fake_llm.add("HydeOutput", hyde())
        assert (await c.post("/search", json={"description": "x"})).status_code == 429
    async with maker() as s:
        assert (await s.scalars(select(SearchHistory))).all() == []


async def test_list_get_delete_and_tombstones(maker, corpus, fake_embedder, fake_llm):  # noqa: F811
    ids = list((await work_ids(maker)).values())
    async with api(maker, fake_embedder, fake_llm) as c:
        a = (await search(c, fake_llm, ids[:1], "first")).json()["history_id"]
        b = (await search(c, fake_llm, ids[:2], "second")).json()["history_id"]
        listing = (await c.get("/searches")).json()
        assert [x["id"] for x in listing["searches"]] == [b, a]  # newest first
        assert set(listing["searches"][0]) == {"id", "description", "result_count", "created_at", "deleted"}
        assert listing["searches"][0]["result_count"] == 2
        t1 = listing["server_time"]
        assert len((await c.get("/searches", params={"limit": 1})).json()["searches"]) == 1

        assert (await c.delete(f"/searches/{a}")).status_code == 204
        assert (await c.delete(f"/searches/{a}")).status_code == 204  # idempotent
        assert (await c.get(f"/searches/{a}")).status_code == 404
        assert [x["id"] for x in (await c.get("/searches")).json()["searches"]] == [b]
        since = (await c.get("/searches", params={"since": t1})).json()["searches"]
        assert [(x["id"], x["deleted"]) for x in since] == [(a, True)]  # tombstone delivered, untouched b not

        t2 = (await c.get("/searches")).json()["server_time"]
        assert (await c.delete("/searches")).json()["deleted"] == 1
        assert (await c.get("/searches")).json()["searches"] == []
        since = (await c.get("/searches", params={"since": t2})).json()["searches"]
        assert [(x["id"], x["deleted"]) for x in since] == [(b, True)]
        assert (await c.delete(f"/searches/{uuid.uuid4()}")).status_code == 404
        assert (await c.get("/searches/not-a-uuid")).status_code == 422


async def test_routes_need_the_bearer_token(maker):
    sv = Services(Settings(api_token="test-token"), maker, SpendTracker(maker, 5.0), None, None, None)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(sv)), base_url="http://t") as c:
        sid = uuid.uuid4()
        for method, path in (("get", "/searches"), ("get", f"/searches/{sid}"), ("delete", f"/searches/{sid}"), ("delete", "/searches")):
            for headers in ({}, {"Authorization": "Bearer wrong"}):
                assert (await getattr(c, method)(path, headers=headers)).status_code == 401, (method, path)
