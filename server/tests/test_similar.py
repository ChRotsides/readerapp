"""POST /similar: seed resolution, chunk coverage scoring, exclusions, no-HyDE retrieval, history. Chunk vectors are built by hand
(`vec`) so cosine similarities are exact; Claude is a small fake that echoes the retrieval order and records every call's purpose."""
import json
import math
import uuid

import httpx
import pytest
from sqlalchemy import Integer, Text, cast, column, select, true, values

from readerserver.ai.spend import SpendTracker
from readerserver.api.main import create_app
from readerserver.config import Settings
from readerserver.linkcrawl.crawler import source_id_for
from readerserver.models import SampleEmbedding, SourceRecord, Work, WorkLink
from readerserver.search.pipeline import SearchService
from readerserver.search.retrieval import COVERAGE_POOL, EARLY_CLAUSE, coverage_scores
from readerserver.search.schemas import HydeOutput, ReasonsOutput, RerankItem, RerankOutput, SearchFilters, SimilarRequest
from readerserver.search.similar import dedupe_chunks, load_seed, similar
from readerserver.services import Services

AUTH = {"Authorization": "Bearer test-token"}
URL = "https://reader.example/novel/seed"
DIM = 1024


def vec(base: int, c: float = 1.0, k: int | None = None) -> list[float]:
    """Unit vector with cosine `c` to the basis vector e_base (the rest on e_k)."""
    v = [0.0] * DIM
    v[base] = c
    if k is not None:
        v[k] = math.sqrt(1 - c * c)
    return v


class FakeClaude:
    """Records every call with its purpose. HydeOutput is answered (plain /search uses it; /similar must never ask); the rerank keeps
    the retrieval order; reasons are canned."""

    def __init__(self):
        self.calls = []

    @property
    def purposes(self) -> list[str]:
        return [c["purpose"] for c in self.calls]

    async def parse(self, *, model, system, user, schema, effort, max_tokens, purpose):
        self.calls.append({"schema": schema.__name__, "system": system, "user": user, "model": model, "purpose": purpose})
        if schema is HydeOutput:
            return HydeOutput(filters=SearchFilters(), scenes=["A hero wakes in a strange place.", "He fights a beast."], synopsis="A hero's tale", tags=["Action"])
        if schema is RerankOutput:
            return RerankOutput(ranking=[RerankItem(work_id=c["id"], reason="similar") for c in json.loads(user)["candidates"]])
        return ReasonsOutput(reasons=[])


async def add_work(maker, title, chunks, source="fake", synopsis=None, work: Work | None = None):
    """A Work with one linked record holding `chunks` = [(vector, chapter_index | None)]. Returns (work id, record id)."""
    async with maker() as s:
        w = work or Work(title=title, synopsis=synopsis)
        if work is None:
            s.add(w)
        r = SourceRecord(source=source, source_id=f"{source}-{title}", title=title, synopsis=synopsis)
        s.add(r)
        await s.flush()
        s.add(WorkLink(source_record_id=r.id, work_id=w.id, method="new", confidence=1.0))
        for i, (v, ch) in enumerate(chunks):
            s.add(SampleEmbedding(source_record_id=r.id, chunk_index=i, chunk_text=f"{title} chunk {i}", model="m", embedding=v, chapter_index=ch))
        await s.commit()
        return str(w.id), r.id


async def add_library_seed(maker, chunks, synopsis="A seed synopsis"):
    async with maker() as s:
        r = SourceRecord(source="library", source_id=source_id_for(URL), url=URL, title="Seed Novel", synopsis=synopsis)
        s.add(r)
        await s.flush()
        for i, (v, ch) in enumerate(chunks):
            s.add(SampleEmbedding(source_record_id=r.id, chunk_index=i, chunk_text=f"seed chunk {i}", model="m", embedding=v, chapter_index=ch))
        await s.commit()
        return r.id


def api(maker, embedder, llm):
    sv = Services(Settings(api_token="test-token"), maker, SpendTracker(maker, 5.0), embedder, llm, None)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(sv)), base_url="http://t", headers=AUTH)


async def corpus_with_seed(maker):
    """Seed chunks e0 (ch0), e1 (ch1), e2 (ch7). X covers e0+e1 at 0.9; Y matches e0 at 0.95 only; W7 matches e2; Deep matches e0 exactly
    but only from chapter 50; Filler is orthogonal to everything."""
    await add_library_seed(maker, [(vec(0), 0), (vec(1), 1), (vec(2), 7)])
    ids = {
        "X": (await add_work(maker, "X", [(vec(0, 0.9, 10), 0), (vec(1, 0.9, 11), 1)]))[0],
        "Y": (await add_work(maker, "Y", [(vec(0, 0.95, 12), 0)]))[0],
        "W7": (await add_work(maker, "W7", [(vec(2, 0.9, 13), 0)]))[0],
        "Deep": (await add_work(maker, "Deep", [(vec(0), 50)]))[0],
        "Filler": (await add_work(maker, "Filler", [(vec(30), 0)]))[0],
    }
    return ids


async def test_validation_404_409(maker, fake_embedder):
    async with api(maker, fake_embedder, FakeClaude()) as c:
        assert (await c.post("/similar", json={})).status_code == 422
        assert (await c.post("/similar", json={"library_url": URL, "work_id": str(uuid.uuid4())})).status_code == 422
        assert (await c.post("/similar", json={"library_url": URL, "limit": 0})).status_code == 422
        assert (await c.post("/similar", json={"library_url": URL})).status_code == 409  # not uploaded yet: the app uploads and retries
        assert (await c.post("/similar", json={"work_id": str(uuid.uuid4())})).status_code == 404
        assert (await c.post("/similar", json={"work_id": "nope"})).status_code == 404
        await add_library_seed(maker, [(vec(0), 0)], synopsis=None)
        r = await c.post("/similar", json={"library_url": URL, "chapters": [3]})  # no chunks for chapter 3, no synopsis
        assert r.status_code == 409 and r.json()["detail"] == "seed has no embedded chapters"
        assert (await c.post("/similar", json={"library_url": URL})).status_code == 200
        async with maker() as s:  # with a synopsis, an explicit chapter list without chunks is still a 409
            await s.execute(SourceRecord.__table__.update().values(synopsis="has one"))
            await s.commit()
        assert (await c.post("/similar", json={"library_url": URL, "chapters": [3]})).status_code == 409
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(Services(Settings(api_token="test-token"), maker, SpendTracker(maker, 5.0), fake_embedder, None, None))),
                                 base_url="http://t") as c:
        assert (await c.post("/similar", json={"library_url": URL})).status_code == 401


async def test_coverage_ranking_and_empty_hypothetical_response(maker, fake_embedder):
    ids = await corpus_with_seed(maker)
    claude = FakeClaude()
    async with api(maker, fake_embedder, claude) as c:
        r = await c.post("/similar", json={"library_url": URL})
        assert r.status_code == 200
        body = r.json()
        assert body["seed_title"] == "Seed Novel" and body["scenes"] == [] and body["synopsis"] == "" and body["tags"] == []
        assert {x["title"] for x in body["results"]} >= {"X", "Y"} and all(x["matched_scenes"] == 0 for x in body["results"])
        rer = next(x for x in claude.calls if x["schema"] == "RerankOutput")
        payload = json.loads(rer["user"])
        assert payload["description"].startswith("Novels similar to Seed Novel. A seed synopsis") and payload["seed_excerpts"] and "similar" in rer["system"]
        assert "scenes" not in payload and all("matched_scenes" not in cand for cand in payload["candidates"])
        assert "hypothetical" not in rer["system"] and "scenes" not in rer["system"].replace("kind of scenes", "").replace("characteristic moments", "")
        assert "hyde" not in claude.purposes and not any(x["schema"] == "HydeOutput" for x in claude.calls)
        # X covers two seed chunks and beats Y's single better match; chapter-50 chunks are not candidates
        order = [x["work_id"] for x in body["results"]]
        assert order.index(ids["X"]) < order.index(ids["Y"]) and ids["Deep"] not in order
        assert not any("fingerprint" in n for n in body["notes"])
        # explicit chapters switch the seed chunks: only ch 7 (e2) -> W7 leads
        body = (await c.post("/similar", json={"library_url": URL, "chapters": [7]})).json()
        assert body["results"][0]["work_id"] == ids["W7"]


async def test_seed_work_excluded_and_hide_library(maker, fake_embedder):
    wid, _ = await add_work(maker, "Seed", [(vec(0), 0), (vec(1), 1)], synopsis="seed syn")
    async with maker() as s:
        w = await s.get(Work, uuid.UUID(wid))
    await add_work(maker, "SeedAlt", [(vec(0, 0.99, 5), 0)], source="other", work=w)  # same work, other record: better match, must stay out
    other = (await add_work(maker, "Other", [(vec(0, 0.8, 6), 0)]))[0]
    for i in range(2):
        await add_work(maker, f"Filler{i}", [(vec(30 + i), 0)])
    lib = (await add_work(maker, "InLibrary", [(vec(0, 0.9, 7), 0)], source="library"))[0]
    async with api(maker, fake_embedder, FakeClaude()) as c:
        body = (await c.post("/similar", json={"work_id": wid})).json()
        got = [x["work_id"] for x in body["results"]]
        assert wid not in got and other in got and lib not in got and body["seed_title"] == "Seed"
        got = [x["work_id"] for x in (await c.post("/similar", json={"work_id": wid, "hide_library": False})).json()["results"]]
        assert lib in got and other in got and wid not in got
        # filters apply to the candidates
        await_ids = [x["work_id"] for x in (await c.post("/similar", json={"work_id": wid, "filters": {"language": "ko"}})).json()["results"]]
        assert await_ids == []


async def test_seed_without_chunks_but_synopsis_uses_other_signals(maker, fake_embedder):
    await add_library_seed(maker, [], synopsis="A swordsman climbs a ladder")
    other = (await add_work(maker, "Other", [(vec(0), 0)], synopsis="A swordsman"))[0]
    from readerserver.indexing import profile_text
    from readerserver.models import ProfileEmbedding

    seed_vec = (await fake_embedder.embed([profile_text(Work(title="Seed Novel", synopsis="A swordsman climbs a ladder", tags=[], genres=[], aliases=[], authors=[]))], "document"))[0]
    async with maker() as s:  # Other's stored profile is close to the seed's real-metadata profile
        s.add(ProfileEmbedding(work_id=uuid.UUID(other), text_hash="h", model="m", embedding=seed_vec))
        await s.commit()
    claude = FakeClaude()
    async with api(maker, fake_embedder, claude) as c:
        body = (await c.post("/similar", json={"library_url": URL})).json()
        assert any("synopsis and tags only" in n for n in body["notes"]) and body["seed_title"] == "Seed Novel"
        assert [x["work_id"] for x in body["results"]] == [other]  # the seed's profile (embedded from its real metadata) answers
        assert "hyde" not in claude.purposes and body["scenes"] == [] and not any("fingerprint" in n for n in body["notes"])


async def test_history_saved_with_similar_description(maker, fake_embedder):
    await corpus_with_seed(maker)
    async with api(maker, fake_embedder, FakeClaude()) as c:
        r = await c.post("/similar", json={"library_url": URL})
        hid = r.json()["history_id"]
        full = (await c.get(f"/searches/{hid}")).json()
        assert full["description"] == "Similar to Seed Novel" and full["result_count"] == len(r.json()["results"]) and full["request"]["library_url"] == URL
        assert any(x["id"] == hid and x["description"] == "Similar to Seed Novel" for x in (await c.get("/searches")).json()["searches"])


async def test_chapter_selection(maker):
    await add_library_seed(maker, [(vec(i), i) for i in (0, 1, 2, 3, 4, 5, 9)])
    async with maker() as s:
        n = lambda **kw: load_seed(s, SimilarRequest(library_url=URL, **kw))  # noqa: E731
        assert len((await n()).chunks) == 5  # default: the 5 lowest stored chapters
        assert len((await n(chapters=[5, 9])).chunks) == 2
        assert len((await n(chapters=[9])).chunks) == 1 and (await n(chapters=[77])).chunks == []
    async with maker() as s:  # only catalog samples (chapter_index NULL) left
        rec = await s.scalar(select(SourceRecord))
        await s.execute(SampleEmbedding.__table__.delete())
        s.add_all([SampleEmbedding(source_record_id=rec.id, chunk_index=i, chunk_text=f"t{i}", model="m", embedding=vec(i), chapter_index=None) for i in range(2)])
        await s.commit()
        assert len((await load_seed(s, SimilarRequest(library_url=URL))).chunks) == 2
    # more than 64 chunks: 64 evenly spaced
    async with maker() as s:
        rec = await s.scalar(select(SourceRecord))
        for i in range(2, 100):
            s.add(SampleEmbedding(source_record_id=rec.id, chunk_index=i, chunk_text=f"t{i}", model="m", embedding=vec(i % 50), chapter_index=None))
        await s.commit()
        assert len((await load_seed(s, SimilarRequest(library_url=URL))).chunks) == 64


async def test_coverage_scoring(maker):
    # many chunks beat one excellent chunk: seed e0,e1,e2
    for i in range(2):
        await add_work(maker, f"Filler{i}", [(vec(30 + i), 0)])  # orthogonal to the seed: the pool cutoff is ~0
    cover = (await add_work(maker, "Cover", [(vec(0, 0.8, 10), 0), (vec(1, 0.8, 11), 0), (vec(2, 0.8, 12), 0)]))[0]
    single = (await add_work(maker, "Single", [(vec(0, 0.99, 13), 0)]))[0]
    async with maker() as s:
        out = dict((await coverage_scores(s, [vec(0), vec(1), vec(2)], []))[0])
        assert out[cover] > out[single] and abs(out[cover] - 0.8) < 1e-3 and abs(out[single] - 0.99 / 3) < 1e-3
        assert (await coverage_scores(s, [vec(0), vec(1), vec(2)], []))[0][0][0] == cover
        assert await coverage_scores(s, [], []) == ([], set())

    # a generic seed chunk (many near neighbours -> high cutoff) contributes less than a distinctive one
    gen = (await add_work(maker, "Gen", [(vec(40, 0.9, 20), 0)]))[0]
    dis = (await add_work(maker, "Dis", [(vec(41, 0.9, 21), 0)]))[0]
    for i in range(4):  # near-duplicates of the generic chunk e40
        await add_work(maker, f"Near{i}", [(vec(40, 0.85, 22 + i), 0)])
    async with maker() as s:
        g = dict((await coverage_scores(s, [vec(40)], [], pool=5))[0])
        d = dict((await coverage_scores(s, [vec(41)], [], pool=5))[0])
        assert abs(g[gen] - 0.05) < 1e-3 and abs(d[dis] - 0.9) < 1e-3 and d[dis] > 10 * g[gen]
        # candidates beyond chapter 10 and excluded records are not considered
        deep = (await add_work(maker, "Deep", [(vec(41), 12)]))[0]
        assert deep not in dict((await coverage_scores(s, [vec(41)], [], pool=5))[0])
        rec = await s.scalar(select(SourceRecord.id).where(SourceRecord.title == "Dis"))
        assert dis not in dict((await coverage_scores(s, [vec(41)], [], exclude_records=[rec], pool=5))[0])


class RecordingEmbedder:
    """Wraps the fake embedder and keeps every text it is asked to embed (and the call's input type)."""

    def __init__(self, inner):
        self.inner, self.texts = inner, []

    async def embed(self, texts, input_type, purpose="embed", patient=True):
        self.texts.append((input_type, list(texts)))
        return await self.inner.embed(texts, input_type, purpose, patient)


async def test_similar_never_calls_hyde_and_embeds_no_hypothetical_text(maker, fake_embedder):
    await corpus_with_seed(maker)  # chunks, a synopsis, no stored profile -> exactly one embed call: the seed's real metadata
    emb, claude = RecordingEmbedder(fake_embedder), FakeClaude()
    async with maker() as s:
        res = await similar(SearchService(Settings(), emb, claude), s, SimilarRequest(library_url=URL, scene_count=5))
    assert res.results and res.scenes == [] and res.synopsis == "" and res.tags == []
    assert "hyde" not in claude.purposes and not any(x["schema"] == "HydeOutput" for x in claude.calls)
    assert [(k, len(v)) for k, v in emb.texts] == [("document", 1)] and "A seed synopsis" in emb.texts[0][1][0]
    assert all(v for _, v in emb.texts)  # never an empty embed call
    assert all("hero" not in x["user"].lower() for x in claude.calls)  # FakeClaude's canned hypothetical scenes never appear


async def test_similar_with_stored_profile_embeds_nothing_and_reranks_with_real_excerpts(maker, fake_embedder):
    from readerserver.models import ProfileEmbedding

    ids = await corpus_with_seed(maker)
    async with maker() as s:  # the seed has a Work with a stored profile: no embedding call at all
        seed_work = Work(title="Seed Novel", synopsis="A seed synopsis")
        s.add(seed_work)
        await s.flush()
        s.add(ProfileEmbedding(work_id=seed_work.id, text_hash="h", model="m", embedding=vec(0)))
        rec = await s.scalar(select(SourceRecord).where(SourceRecord.source == "library"))
        s.add(WorkLink(source_record_id=rec.id, work_id=seed_work.id, method="new", confidence=1.0))
        await s.commit()
    emb, claude = RecordingEmbedder(fake_embedder), FakeClaude()
    async with maker() as s:
        res = await similar(SearchService(Settings(), emb, claude), s, SimilarRequest(library_url=URL))
    assert emb.texts == [] and res.results
    rer = json.loads(next(x for x in claude.calls if x["schema"] == "RerankOutput")["user"])
    assert rer["seed_excerpts"] and all(e.startswith("seed chunk") for e in rer["seed_excerpts"])
    by_title = {c["title"]: c for c in rer["candidates"]}
    assert by_title["X"]["excerpts"] and all(e.startswith("X chunk") for e in by_title["X"]["excerpts"])  # real candidate passages, matched via seed vectors
    assert ids["Deep"] not in [x.work_id for x in res.results]


async def test_scene_count_is_accepted_and_ignored(maker, fake_embedder):
    await corpus_with_seed(maker)
    outs = []
    for extra in ({}, {"scene_count": 1}, {"scene_count": 6}):
        claude = FakeClaude()
        async with api(maker, fake_embedder, claude) as c:
            r = await c.post("/similar", json={"library_url": URL, **extra})
        assert r.status_code == 200 and r.json()["scenes"] == [] and "hyde" not in claude.purposes
        outs.append([(x["work_id"], x["score"]) for x in r.json()["results"]])
    assert outs[0] == outs[1] == outs[2] and outs[0]
    async with api(maker, fake_embedder, FakeClaude()) as c:
        assert (await c.post("/similar", json={"library_url": URL, "scene_count": 99})).status_code == 422  # still validated


async def test_plain_search_still_calls_hyde(maker, fake_embedder):
    await corpus_with_seed(maker)
    claude = FakeClaude()
    async with api(maker, fake_embedder, claude) as c:
        r = await c.post("/search", json={"description": "a hero in a strange place"})
    assert r.status_code == 200 and "hyde" in claude.purposes and r.json()["scenes"]
    rer = json.loads(next(x for x in claude.calls if x["schema"] == "RerankOutput")["user"])
    assert rer["scenes"] and "seed_excerpts" not in rer


async def test_short_pool_cutoff_is_the_minimum_returned(maker):
    # Only two candidate chunks exist: the weaker one sets the cutoff, so the stronger contributes the gap, not its raw similarity.
    a = (await add_work(maker, "A", [(vec(0, 0.9, 10), 0)]))[0]
    b = (await add_work(maker, "B", [(vec(0, 0.6, 11), 0)]))[0]
    async with maker() as s:
        ranked, dups = await coverage_scores(s, [vec(0)], [])
    assert dict(ranked) == {a: pytest.approx(0.3, abs=1e-3)} and b not in dict(ranked) and not dups


async def test_early_hnsw_index_matches_the_coverage_predicate(maker):
    from sqlalchemy import text

    from readerserver.search.retrieval import EARLY_CLAUSE

    async with maker() as s:
        await s.execute(text("SET LOCAL enable_seqscan = off"))
        plan = "\n".join(r[0] for r in await s.execute(text(
            f"EXPLAIN SELECT id FROM sample_embeddings WHERE {EARLY_CLAUSE.text} ORDER BY embedding <=> '[{','.join(['0.1'] * DIM)}]'::vector LIMIT 5")))
    assert "ix_sample_embeddings_early_hnsw" in plan


async def test_unresolved_library_seed_excludes_corpus_copy_by_vectors_and_title(maker, fake_embedder):
    from readerserver.models import SourceTitle

    # the library record has no Work yet; the corpus holds the same novel (identical chunks) and another that only shares its title
    await add_library_seed(maker, [(vec(0), 0), (vec(1), 1)])
    copy = (await add_work(maker, "Copy", [(vec(0), 0), (vec(1), 1)]))[0]
    twin, twin_rec = await add_work(maker, "Seed Novel (WN)", [(vec(5, 0.7, 6), 0)])
    async with maker() as s:
        s.add(SourceTitle(source_record_id=twin_rec, norm_title="seed novel"))
        await s.commit()
    near = (await add_work(maker, "Near", [(vec(0, 0.8, 7), 0), (vec(1, 0.8, 8), 1)]))[0]
    for i in range(2):
        await add_work(maker, f"Filler{i}", [(vec(30 + i), 0)])
    async with api(maker, fake_embedder, FakeClaude()) as c:
        got = [x["work_id"] for x in (await c.post("/similar", json={"library_url": URL})).json()["results"]]
    assert near in got and copy not in got and twin not in got


async def test_no_crash_without_excerpts_or_synopsis(maker, fake_embedder):
    # synopsis but no chunks -> no excerpt vectors at all; the profile vector (embedded synopsis) is used, no 500
    await add_library_seed(maker, [], synopsis="A swordsman climbs a ladder")
    await add_work(maker, "Other", [(vec(0), 0)], synopsis="A swordsman")
    async with api(maker, fake_embedder, FakeClaude()) as c:
        assert (await c.post("/similar", json={"library_url": URL})).status_code == 200
    # chunks without text vectors still seed coverage and excerpt matching
    async with maker() as s:
        await s.execute(SourceRecord.__table__.delete())
        await s.commit()
    await add_library_seed(maker, [(vec(0), 0), (vec(1), 0)], synopsis=None)
    async with maker() as s:
        await s.execute(SampleEmbedding.__table__.update().where(SampleEmbedding.chunk_text.like("seed%")).values(chunk_text=None))
        await s.commit()
    near = (await add_work(maker, "Near", [(vec(0, 0.8, 7), 0)]))[0]
    await add_work(maker, "Filler", [(vec(30), 0)])
    claude = FakeClaude()
    async with api(maker, fake_embedder, claude) as c:
        r = await c.post("/similar", json={"library_url": URL})
        assert r.status_code == 200 and near in [x["work_id"] for x in r.json()["results"]]
    # no synopsis, no excerpt text: Claude is never asked to invent anything from a title
    assert "hyde" not in claude.purposes and not any("fingerprint" in n for n in r.json()["notes"])


async def test_history_keeps_seed_title(maker, fake_embedder):
    await corpus_with_seed(maker)
    async with api(maker, fake_embedder, FakeClaude()) as c:
        hid = (await c.post("/similar", json={"library_url": URL})).json()["history_id"]
        assert (await c.get(f"/searches/{hid}")).json()["scenes"]["seed_title"] == "Seed Novel"


async def test_long_synopsis_is_truncated_in_the_description(maker, fake_embedder):
    await add_library_seed(maker, [(vec(0), 0)], synopsis="word " * 1000)
    await add_work(maker, "Other", [(vec(0, 0.8, 7), 0)])
    await add_work(maker, "Filler", [(vec(30), 0)])
    claude = FakeClaude()
    async with api(maker, fake_embedder, claude) as c:
        await c.post("/similar", json={"library_url": URL})
    desc = json.loads(next(x for x in claude.calls if x["schema"] == "RerankOutput")["user"])["description"]
    assert len(desc) < 1600


async def lateral_reference(session, seed_vecs, clauses, exclude_records=None, pool=COVERAGE_POOL):
    """The previous single-query LATERAL implementation, kept as a test-only reference: raw (seed index, work id, similarity) rows."""
    vals = values(column("i", Integer), column("vec", Text), name="seed").data([(i, str(v)) for i, v in enumerate(seed_vecs)])
    dist = SampleEmbedding.embedding.cosine_distance(cast(vals.c.vec, SampleEmbedding.embedding.type))
    inner = (select(WorkLink.work_id.label("work_id"), dist.label("dist"))
             .join(SampleEmbedding, SampleEmbedding.source_record_id == WorkLink.source_record_id)
             .join(Work, Work.id == WorkLink.work_id)
             .where(WorkLink.undone_at.is_(None), EARLY_CLAUSE, *clauses))
    if exclude_records:
        inner = inner.where(SampleEmbedding.source_record_id.not_in(exclude_records))
    near = inner.order_by(dist).limit(pool).lateral("near")
    return [(i, str(w), 1 - float(d)) for i, w, d in (await session.execute(select(vals.c.i, near.c.work_id, near.c.dist).select_from(vals.join(near, true())))).all()]


def expected_scores(rows, n_seeds):
    """Corrected scoring from raw reference rows: cutoff = the weakest chunk of the pool, before collapsing by work."""
    per: dict[int, list] = {}
    for i, w, sim in rows:
        per.setdefault(i, []).append((w, sim))
    totals: dict[str, float] = {}
    for pool in per.values():
        cutoff = min(sim for _, sim in pool)
        best: dict[str, float] = {}
        for w, sim in pool:
            best[w] = max(best.get(w, -1.0), sim)
        for w, b in best.items():
            totals[w] = totals.get(w, 0.0) + max(0.0, b - cutoff)
    return {w: t / n_seeds for w, t in totals.items() if t > 1e-12}


async def test_per_seed_queries_match_the_lateral_reference(maker):
    multi = (await add_work(maker, "Multi", [(vec(0, 0.9, 10), 0), (vec(0, 0.7, 11), 1), (vec(1, 0.8, 12), 2)]))[0]
    a = (await add_work(maker, "A", [(vec(1, 0.95, 13), 0), (vec(2, 0.6, 14), 3)]))[0]
    b = (await add_work(maker, "B", [(vec(2, 0.85, 15), 0)]))[0]
    de = (await add_work(maker, "Deep", [(vec(0), 50)]))[0]
    tagged = (await add_work(maker, "Tagged", [(vec(0, 0.5, 16), 0)]))[0]
    excl_rec = (await add_work(maker, "Excl", [(vec(0, 0.99, 17), 0)]))[1]
    await add_work(maker, "Filler", [(vec(30), 0)])
    async with maker() as s:
        await s.execute(Work.__table__.update().where(Work.id == uuid.UUID(tagged)).values(language="ko"))
        await s.commit()
    seeds = [vec(0), vec(1), vec(2), vec(40)]
    cases = [([], None, COVERAGE_POOL), ([], None, 3), ([], [excl_rec], COVERAGE_POOL), ([Work.language == "ko"], None, COVERAGE_POOL),
             ([Work.language == "zz"], None, COVERAGE_POOL)]  # last: empty pools
    async with maker() as s:
        for clauses, excl, pool in cases:
            ref = await lateral_reference(s, seeds, clauses, excl, pool)
            got, dups = await coverage_scores(s, seeds, clauses, excl, k=1000, pool=pool, dup_sim=2.0)
            assert not dups and dict(got) == pytest.approx(expected_scores(ref, len(seeds)), abs=1e-6)
            for i, sv in enumerate(seeds):  # same pool per seed: a single-seed call scores exactly that seed's reference rows
                one = await coverage_scores(s, [sv], clauses, excl, k=1000, pool=pool, dup_sim=2.0)
                assert dict(one[0]) == pytest.approx(expected_scores([r for r in ref if r[0] == i] and [(0, w, x) for _, w, x in ref if _ == i], 1), abs=1e-6)
        # the pool for a seed matches the reference at the (work, best similarity) level through the dup detector
        ref = await lateral_reference(s, [vec(0)], [], None, COVERAGE_POOL)
        best = {}
        for _, w, sim in ref:
            best[w] = max(best.get(w, -1.0), sim)
        _, dups = await coverage_scores(s, [vec(0)], [], dup_sim=0.9)
        assert dups == {w for w, v in best.items() if v >= 0.9} and {multi, de} & dups == {multi} and a not in dups and b not in dups


async def test_cutoff_is_the_weakest_returned_chunk_not_the_weakest_work(maker):
    # one work holds three chunks in the pool: only 0.9 is its best; the pool's weakest chunk (its own 0.5) is the cutoff
    solo = (await add_work(maker, "Solo", [(vec(0, 0.9, 10), 0), (vec(0, 0.7, 11), 1), (vec(0, 0.5, 12), 2)]))[0]
    async with maker() as s:
        ranked, _ = await coverage_scores(s, [vec(0)], [])
    assert dict(ranked) == {solo: pytest.approx(0.4, abs=1e-3)}  # single-work pool: previously 0 and unranked
    # two works: A's weaker chunks lower the cutoff below B's best; B (0.6) is the pool's weakest work but gets 0.6 - 0.5 = 0.1
    b = (await add_work(maker, "B", [(vec(0, 0.6, 13), 0)]))[0]
    async with maker() as s:
        ranked, _ = await coverage_scores(s, [vec(0)], [])
    assert dict(ranked) == {solo: pytest.approx(0.4, abs=1e-3), b: pytest.approx(0.1, abs=1e-3)}


def test_dedupe_chunks_keeps_distinct_text_and_distinct_null_embeddings():
    rows = [("a", vec(0)), ("b", vec(1)), ("a", vec(2)), (None, vec(3)), (None, vec(3)), (None, vec(4)), ("b", vec(1))]
    assert [(t, v.index(max(v))) for t, v in dedupe_chunks(rows)] == [("a", 0), ("b", 1), (None, 3), (None, 4)]


async def test_seed_with_two_source_records_counts_identical_chunks_once(maker):
    text_of = lambda i: f"passage {i}"  # noqa: E731
    async with maker() as s:
        lib = SourceRecord(source="library", source_id=source_id_for(URL), url=URL, title="Seed Novel")
        crawl = SourceRecord(source="crawl", source_id="c1", title="Seed Novel")
        w = Work(title="Seed Novel")
        s.add_all([lib, crawl, w])
        await s.flush()
        s.add_all([WorkLink(source_record_id=lib.id, work_id=w.id, method="new", confidence=1), WorkLink(source_record_id=crawl.id, work_id=w.id, method="new", confidence=1)])
        for r in (lib, crawl):
            for i in range(3):
                s.add(SampleEmbedding(source_record_id=r.id, chunk_index=i, chunk_text=text_of(i), model="m", embedding=vec(i), chapter_index=i))
        s.add(SampleEmbedding(source_record_id=crawl.id, chunk_index=9, chunk_text="only in the crawl", model="m", embedding=vec(9), chapter_index=3))
        await s.commit()
    async with maker() as s:
        assert [t for t, _ in (await load_seed(s, SimilarRequest(library_url=URL))).chunks] == ["passage 0", "passage 1", "passage 2"]  # own record only
        wid = await s.scalar(select(Work.id))
        seed = await load_seed(s, SimilarRequest(work_id=str(wid)))  # corpus seed reads both records: the repeats count once
    assert [t for t, _ in seed.chunks] == ["passage 0", "passage 1", "passage 2", "only in the crawl"]


async def test_library_seed_reads_its_own_record_for_chapter_indexes(maker):
    # the crawl record numbers chapters differently (its chapter 5 is other text); chapters=[5] must mean the library record's chapter 5
    async with maker() as s:
        lib = SourceRecord(source="library", source_id=source_id_for(URL), url=URL, title="Seed Novel")
        crawl = SourceRecord(source="crawl", source_id="c2", title="Seed Novel")
        w = Work(title="Seed Novel")
        s.add_all([lib, crawl, w])
        await s.flush()
        s.add_all([WorkLink(source_record_id=lib.id, work_id=w.id, method="new", confidence=1), WorkLink(source_record_id=crawl.id, work_id=w.id, method="new", confidence=1)])
        s.add(SampleEmbedding(source_record_id=lib.id, chunk_index=0, chunk_text="lib ch5", model="m", embedding=vec(0), chapter_index=5))
        s.add(SampleEmbedding(source_record_id=crawl.id, chunk_index=0, chunk_text="crawl ch5", model="m", embedding=vec(1), chapter_index=5))
        s.add(SampleEmbedding(source_record_id=crawl.id, chunk_index=1, chunk_text="crawl ch8", model="m", embedding=vec(2), chapter_index=8))
        await s.commit()
        lib_id, crawl_id = lib.id, crawl.id
    async with maker() as s:
        seed = await load_seed(s, SimilarRequest(library_url=URL, chapters=[5]))
        assert [t for t, _ in seed.chunks] == ["lib ch5"] and sorted(seed.record_ids) == sorted([lib_id, crawl_id]) and len(seed.work_ids) == 1
        assert [t for t, _ in (await load_seed(s, SimilarRequest(library_url=URL))).chunks] == ["lib ch5"]
        # the library record has nothing for chapter 8: fall back to every linked record
        assert [t for t, _ in (await load_seed(s, SimilarRequest(library_url=URL, chapters=[8]))).chunks] == ["crawl ch8"]
        # a corpus seed uses all of its records
        got = await load_seed(s, SimilarRequest(work_id=str(seed.work_ids[0]), chapters=[5]))
        assert sorted(t for t, _ in got.chunks) == ["crawl ch5", "lib ch5"]


async def test_cutoff_uses_raw_minimum_across_works_and_negative_similarities(maker):
    # A has 0.9 and 0.1 in the pool, B has 0.8: the cutoff is 0.1 (not B's 0.8), so A gets 0.8 and B 0.7
    a = (await add_work(maker, "A", [(vec(0, 0.9, 10), 0), (vec(0, 0.1, 11), 1)]))[0]
    b = (await add_work(maker, "B", [(vec(0, 0.8, 12), 0)]))[0]
    async with maker() as s:
        ranked, _ = await coverage_scores(s, [vec(0)], [])
    assert dict(ranked) == {a: pytest.approx(0.8, abs=1e-3), b: pytest.approx(0.7, abs=1e-3)}
    # a negative-similarity chunk lowers the cutoff below 0 and is not floored
    c = (await add_work(maker, "C", [(vec(0, -0.5, 13), 0)]))[0]
    async with maker() as s:
        ranked, _ = await coverage_scores(s, [vec(0)], [])
    assert dict(ranked)[b] == pytest.approx(1.3, abs=1e-3) and c not in dict(ranked)


async def test_repeated_seed_queries_on_one_connection_stay_correct(maker):
    # asyncpg may switch to a generic plan after 5 executions of the same statement; results must not change
    near = (await add_work(maker, "Near", [(vec(0, 0.8, 10), 0)]))[0]
    await add_work(maker, "Filler", [(vec(30), 0)])
    async with maker() as s:
        outs = [dict((await coverage_scores(s, [vec(0)] * 4, []))[0]) for _ in range(4)]
        outs += [dict((await coverage_scores(s, [vec(0)], []))[0]) for _ in range(8)]
    assert all(o == outs[0] or o == outs[-1] for o in outs) and outs[-1] == {near: pytest.approx(0.8, abs=1e-3)}
