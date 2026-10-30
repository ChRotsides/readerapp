import httpx
import pytest
from sqlalchemy import select

from readerserver.ai.spend import SpendTracker
from readerserver.api.main import create_app
from readerserver.config import Settings
from readerserver.models import CrawlState, SourceRecord
from readerserver.services import Services

from .test_resolver import add, resolve
from readerserver.resolution.resolver import Resolver


def client(maker, token="test-token", embedder=None, llm=None):
    sv = Services(Settings(api_token=token), maker, SpendTracker(maker, 5.0), embedder, llm, None)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(sv)), base_url="http://t")


async def test_health_is_open_everything_else_needs_bearer(maker):
    async with client(maker) as c:
        assert (await c.get("/health")).json() == {"status": "ok"}
        for method, path in [("get", "/works?query=x"), ("get", "/works/abc"), ("post", "/search"), ("get", "/admin/crawl"),
                             ("post", "/admin/crawl/syosetu"), ("post", "/admin/records/1/unlink"), ("get", "/admin/spend")]:
            r = await getattr(c, method)(path)
            assert r.status_code == 401, (method, path)
            r = await getattr(c, method)(path, headers={"Authorization": "Bearer wrong"})
            assert r.status_code == 401, (method, path)
        ok = await c.get("/admin/spend", headers={"Authorization": "Bearer test-token"})
        assert ok.status_code == 200 and ok.json()["cap_usd"] == 5.0


async def test_server_without_token_configured_refuses(maker):
    async with client(maker, token="") as c:
        assert (await c.get("/admin/spend", headers={"Authorization": "Bearer "})).status_code in (401, 503)
        assert (await c.get("/health")).status_code == 200


async def test_search_requires_configured_providers(maker):
    async with client(maker) as c:
        r = await c.post("/search", json={"description": "x"}, headers={"Authorization": "Bearer test-token"})
        assert r.status_code == 503


H = {"Authorization": "Bearer test-token"}


async def test_admin_trigger_status_unblock_and_unlink(maker):
    async with client(maker) as c:
        assert (await c.post("/admin/crawl/nope", headers=H)).status_code == 404
        r = await c.post("/admin/crawl/syosetu", headers=H)
        assert r.status_code == 202
        status = (await c.get("/admin/crawl", headers=H)).json()
        syo = next(s for s in status["sources"] if s["source"] == "syosetu")
        assert syo["requested"] is True
        assert {s["source"] for s in status["sources"]} >= {"syosetu", "anilist", "mangaupdates", "mal"}
        assert (await c.post("/admin/crawl/syosetu/unblock", headers=H)).status_code == 409
        async with maker() as s:
            (await s.get(CrawlState, "syosetu")).status = "blocked"
            await s.commit()
        assert (await c.post("/admin/crawl/syosetu/unblock", headers=H)).json()["status"] == "idle"

        async with maker() as s:
            r1 = Resolver(None)
            a = await add(s, source="mal", source_id="1", title="Alpha", external_ids={"mal": "1"})
            await resolve(s, r1, a)
            b = await add(s, source="anilist", source_id="2", title="Beta", external_ids={"mal": "1"})
            await resolve(s, r1, b)
            bid = b.id
        from sqlalchemy import select as sel
        async with maker() as s:
            from readerserver.models import WorkLink
            wid = (await s.scalars(sel(WorkLink.work_id).where(WorkLink.source_record_id == bid, WorkLink.undone_at.is_(None)))).one()
        recs = (await c.get(f"/admin/works/{wid}/records", headers=H)).json()
        assert len(recs) == 2 and {x["method"] for x in recs} == {"new", "hard_id"}
        u = await c.post(f"/admin/records/{bid}/unlink", headers=H)
        assert u.status_code == 200 and u.json()["old_work_id"] == str(wid)
        assert len((await c.get(f"/admin/works/{wid}/records", headers=H)).json()) == 1
        assert (await c.post("/admin/records/99999/unlink", headers=H)).status_code == 404
