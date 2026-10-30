import httpx
import pytest
from sqlalchemy import select

from readerserver.ai.spend import BudgetExceeded, SpendTracker
from readerserver.api.main import create_app
from readerserver.config import Settings
from readerserver.crawl.runner import CrawlRunner
from readerserver.indexing import index_sample_text
from readerserver.models import SourceRecord
from readerserver.resolution.resolver import Resolver, seed_tags
from readerserver.search.pipeline import SearchService
from readerserver.search.schemas import HydeOutput, RerankItem, RerankOutput, SearchFilters, SearchRequest
from readerserver.services import Services
from readerserver.sources.base import SourceRecordData as D

from .test_pipeline import FakeSource

CORPUS = [
    D(source="fake", source_id="1", url="https://fake.example/n/1", title="Dragon Chef", language="en", status="completed", chapter_count=300, tags=["Cooking", "Dragons"],
      synopsis="A cook befriends a dragon and opens a restaurant in a fantasy world", authors=["A One"]),
    D(source="fake", source_id="2", url="https://fake.example/n/2", title="Sword Saint Rises", language="en", status="ongoing", chapter_count=1200, tags=["Magic", "Action"],
      synopsis="A swordsman climbs a tournament ladder with his blade", authors=["B Two"]),
    D(source="fake", source_id="3", url="https://fake.example/n/3", title="Moonlit Bakery", language="ko", status="ongoing", chapter_count=80, tags=["Cooking", "Romance"],
      synopsis="A baker and a dragon prince fall in love over bread", authors=["C Three"]),
]
CHUNKS = {
    "1": "The cook stirred the stew while the dragon watched the restaurant kitchen hungrily. " * 30,
    "2": "He drew his sword and faced the tournament champion in the arena. " * 30,
    "3": "The baker kneaded bread dough as the dragon prince smiled at her. " * 30,
}


@pytest.fixture
async def corpus(maker, fake_embedder):
    cfg = Settings(voyage_model="test-model")
    async with maker() as s:
        await seed_tags(s)
    runner = CrawlRunner(maker, cfg, fake_embedder, Resolver(None))
    await runner.crawl(FakeSource([CORPUS]))
    await runner.process_pending()
    async with maker() as s:
        for r in (await s.scalars(select(SourceRecord))).all():
            await index_sample_text(s, fake_embedder, cfg, r, [CHUNKS[r.source_id]])
    return cfg


def hyde(**filters):
    return HydeOutput(
        filters=SearchFilters(**filters),
        scenes=["The cook stirred the stew while the dragon watched the kitchen.", "She opened a restaurant and the dragon guarded the door."],
        synopsis="A cook and a dragon run a restaurant", tags=["Cooking", "Dragons"],
    )


async def run(maker, cfg, embedder, llm, req):
    async with maker() as s:
        return await SearchService(cfg, embedder, llm).search(s, req)


async def test_full_pipeline_with_rerank(maker, corpus, fake_embedder, fake_llm):
    fake_llm.add("HydeOutput", hyde())
    async with maker() as s:
        from readerserver.models import Work
        ids = {w.title: str(w.id) for w in (await s.scalars(select(Work))).all()}
    fake_llm.add("RerankOutput", RerankOutput(ranking=[RerankItem(work_id=ids["Dragon Chef"], reason="Cook and dragon run a restaurant"),
                                                      RerankItem(work_id=ids["Moonlit Bakery"], reason="Dragon prince, baking"),
                                                      RerankItem(work_id="not-a-real-id", reason="hallucinated")]))
    res = await run(maker, corpus, fake_embedder, fake_llm, SearchRequest(description="a cook who befriends a dragon"))
    assert res.reranked and [r.title for r in res.results][:2] == ["Dragon Chef", "Moonlit Bakery"]
    assert res.results[0].reason.startswith("Cook") and res.results[0].matched_scenes == 2
    assert 0 < res.results[0].score <= 1 and "not-a-real-id" not in [r.work_id for r in res.results]
    assert res.scenes == hyde().scenes and res.synopsis and res.tags == ["Cooking", "Dragons"]  # returned for editing/re-search
    # models: Haiku for HyDE, Sonnet for rerank; effort set explicitly
    assert [(c["model"], c["effort"]) for c in fake_llm.calls] == [("claude-haiku-5-5", "low"), ("claude-sonnet-5-5", "medium")]
    top = res.results[0]
    assert top.urls and top.urls[0].source == "fake" and top.tags


async def test_filters_applied_in_retrieval(maker, corpus, fake_embedder, fake_llm):
    fake_llm.add("HydeOutput", hyde(language="ko"))
    fake_llm.add("RerankOutput", RerankOutput(ranking=[]))
    res = await run(maker, corpus, fake_embedder, fake_llm, SearchRequest(description="dragon romance bread"))
    assert [r.title for r in res.results] == ["Moonlit Bakery"]
    assert not res.reranked  # empty rerank -> retrieval order kept

    fake_llm.queue.clear()
    fake_llm.add("HydeOutput", hyde(min_chapters=250, exclude_tags=["isekai", "Romance"], include_tags=["cooking"]))
    fake_llm.add("RerankOutput", RerankOutput(ranking=[]))
    res = await run(maker, corpus, fake_embedder, fake_llm, SearchRequest(description="x"))
    assert [r.title for r in res.results] == ["Dragon Chef"]


async def test_supplied_scenes_skip_generation_and_request_filters_win(maker, corpus, fake_embedder, fake_llm):
    fake_llm.add("RerankOutput", RerankOutput(ranking=[]))
    req = SearchRequest(description="swords", scenes=["He drew his sword in the arena."], synopsis="A swordsman in a tournament",
                        filters=SearchFilters(status="ongoing"))
    res = await run(maker, corpus, fake_embedder, fake_llm, req)
    assert all(c["schema"] != "HydeOutput" for c in fake_llm.calls)
    assert res.results[0].title == "Sword Saint Rises" and res.scenes == ["He drew his sword in the arena."]
    assert {r.status for r in res.results} == {"ongoing"}


async def test_rerank_budget_exhausted_degrades(maker, corpus, fake_embedder):
    class LLM:
        async def parse(self, *, schema, **kw):
            if schema is HydeOutput:
                return hyde()
            raise BudgetExceeded("cap")

    res = await run(maker, corpus, fake_embedder, LLM(), SearchRequest(description="dragon cook"))
    assert not res.reranked and res.results and any("budget" in n for n in res.notes)


async def test_title_search_and_work_detail_via_api(maker, corpus, fake_embedder, fake_llm):
    sv = Services(Settings(api_token="test-token"), maker, SpendTracker(maker, 5.0), fake_embedder, fake_llm, None)
    app = create_app(sv)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t", headers={"Authorization": "Bearer test-token"}) as c:
        r = await c.get("/works", params={"query": "sword saint"})
        assert r.status_code == 200 and r.json()[0]["title"] == "Sword Saint Rises"
        wid = r.json()[0]["work_id"]
        d = (await c.get(f"/works/{wid}")).json()
        assert d["urls"] == [{"source": "fake", "url": "https://fake.example/n/2"}] and d["tags"]
        assert (await c.get("/works/00000000-0000-0000-0000-000000000000")).status_code == 404
        assert (await c.get("/works/not-a-uuid")).status_code == 404
        fake_llm.add("HydeOutput", hyde())
        fake_llm.add("RerankOutput", RerankOutput(ranking=[]))
        s = await c.post("/search", json={"description": "dragon cook", "limit": 2})
        assert s.status_code == 200 and len(s.json()["results"]) == 2
        assert set(s.json()) >= {"results", "scenes", "synopsis", "tags", "filters", "reranked"}
