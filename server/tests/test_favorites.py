"""HTTP API: /favorites (bearer auth, tombstones, last-write-wins) and the `favorite` flag on works."""
import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from readerserver.api.favorites import normalize_novel_url
from readerserver.models import Favorite, Work
from readerserver.resolution.resolver import Resolver

from .test_api import H, client
from .test_resolver import add, resolve

T0 = datetime(2026, 10, 1, 12, 0, tzinfo=UTC)

# Shared with the app's FavoriteKeysTest: keep both lists identical.
NORMALIZE_CASES = [
    ("https://www.Example.com/Novel/Abc/", "https://example.com/Novel/Abc"),
    ("http://example.com/novel/1#frag", "https://example.com/novel/1"),
    ("  HTTP://WWW.Example.COM:8080/a//?x=1&y=2#z ", "https://example.com:8080/a?x=1&y=2"),
    ("https://example.com", "https://example.com"),
    ("https://example.com/?", "https://example.com"),
    ("https://example.com/book?id=5", "https://example.com/book?id=5"),
    ("not a url", "not a url"),
]


def body(key="url:https://example.com/n/1", favorite=True, changed_at=T0, **kw):
    return {"key": key, "title": "Title", "favorite": favorite, "changed_at": changed_at.isoformat(), **kw}


def test_normalize_novel_url():
    for raw, want in NORMALIZE_CASES:
        assert normalize_novel_url(raw) == want, raw
        assert normalize_novel_url(want) == want  # idempotent


async def test_routes_need_the_bearer_token(maker):
    async with client(maker) as c:
        for method, kw in (("get", {}), ("put", {"json": body()})):
            for headers in ({}, {"Authorization": "Bearer wrong"}):
                assert (await getattr(c, method)("/favorites", headers=headers, **kw)).status_code == 401


async def test_put_get_and_tombstone(maker):
    async with client(maker) as c:
        r = await c.put("/favorites", json=body(author="A", cover_url="https://x/c.jpg", domain="example.com", novel_url="https://www.example.com/n/1/"), headers=H)
        assert r.status_code == 200
        j = r.json()
        assert (j["key"], j["favorite"], j["title"], j["author"], j["domain"], j["work_id"]) == ("url:https://example.com/n/1", True, "Title", "A", "example.com", None)
        listing = (await c.get("/favorites", headers=H)).json()
        assert [f["key"] for f in listing["favorites"]] == ["url:https://example.com/n/1"]
        t1 = listing["server_time"]
        datetime.fromisoformat(t1)

        await c.put("/favorites", json=body(favorite=False, changed_at=T0 + timedelta(minutes=1)), headers=H)
        assert (await c.get("/favorites", headers=H)).json()["favorites"] == []  # live only without since
        since = (await c.get("/favorites", params={"since": t1}, headers=H)).json()["favorites"]
        assert len(since) == 1 and since[0]["favorite"] is False  # tombstone is delivered
        later = (await c.get("/favorites", params={"since": (await c.get("/favorites", headers=H)).json()["server_time"]}, headers=H)).json()
        assert later["favorites"] == []
        async with maker() as s:
            assert (await s.scalar(select(Favorite.deleted_at))) is not None


async def test_last_write_wins_on_changed_at(maker):
    async with client(maker) as c:
        await c.put("/favorites", json=body(favorite=False, changed_at=T0 + timedelta(minutes=5), title="New"), headers=H)
        r = await c.put("/favorites", json=body(favorite=True, changed_at=T0, title="Old"), headers=H)  # older: ignored
        assert r.json()["favorite"] is False and r.json()["title"] == "New"
        r = await c.put("/favorites", json=body(favorite=True, changed_at=T0 + timedelta(minutes=9), title="Newest"), headers=H)
        assert r.json()["favorite"] is True and r.json()["title"] == "Newest"
        async with maker() as s:
            row = (await s.scalars(select(Favorite))).one()
            assert row.deleted_at is None  # re-favoriting clears the tombstone


async def test_validation(maker):
    async with client(maker) as c:
        for b in (body(key="nope"), body(key="work:not-a-uuid"), body(key="url:"), {"key": "url:https://a.example/x", "title": "T"},
                  body(title="")):
            assert (await c.put("/favorites", json=b, headers=H)).status_code == 422, b


async def test_work_key_sets_work_id_and_flag_on_work_json(maker):
    async with maker() as s:
        s.add(w := Work(title="Alpha"))
        await s.commit()
        wid = w.id
    async with client(maker) as c:
        assert (await c.get(f"/works/{wid}", headers=H)).json()["favorite"] is False
        r = await c.put("/favorites", json=body(key=f"work:{wid}"), headers=H)
        assert r.json()["work_id"] == str(wid)
        assert (await c.get(f"/works/{wid}", headers=H)).json()["favorite"] is True
        assert (await c.get("/works", params={"query": "Alpha"}, headers=H)).status_code == 200
        await c.put("/favorites", json=body(key=f"work:{wid}", favorite=False, changed_at=T0 + timedelta(minutes=1)), headers=H)
        assert (await c.get(f"/works/{wid}", headers=H)).json()["favorite"] is False
        # a work that does not exist keeps the favorite by key only
        ghost = uuid.uuid4()
        assert (await c.put("/favorites", json=body(key=f"work:{ghost}"), headers=H)).json()["work_id"] is None


async def test_novel_url_fills_work_id_from_a_linked_source_record(maker):
    async with maker() as s:
        rec = await add(s, source="royalroad", source_id="9", title="Beta Novel", url="https://www.royalroad.com/fiction/9/beta/")
        await resolve(s, Resolver(None), rec)
    async with client(maker) as c:
        r = await c.put("/favorites", json=body(key="url:https://royalroad.com/fiction/9/beta", novel_url="http://royalroad.com/fiction/9/beta"), headers=H)
        wid = r.json()["work_id"]
        assert wid is not None
        assert (await c.get(f"/works/{wid}", headers=H)).json()["favorite"] is True
        r = await c.put("/favorites", json=body(key="url:https://other.example/z", novel_url="https://other.example/z"), headers=H)
        assert r.json()["work_id"] is None
