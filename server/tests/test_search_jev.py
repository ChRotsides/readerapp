"""Chapter excerpts + Jev (score) rerank with Claude fallback; Jev is a fake over httpx.MockTransport."""
import json

import httpx
import pytest
from sqlalchemy import select, update

from readerserver.ai.jev import JevClient
from readerserver.ai.spend import SpendTracker
from readerserver.models import AiUsage, SampleEmbedding
from readerserver.search.pipeline import RUBRIC, SearchService
from readerserver.search.retrieval import work_excerpts
from readerserver.search.schemas import ReasonsOutput, RerankItem, RerankOutput, SearchRequest

from .test_search import corpus, hyde, work_ids  # noqa: F401  (corpus is a fixture)

LEVELS = {"Dragon Chef": [0, 0, 0.1, 0.2, 0.7], "Moonlit Bakery": [0.05, 0.1, 0.25, 0.5, 0.1], "Sword Saint Rises": [0.9, 0.1, 0, 0, 0]}


class FakeJev:
    def __init__(self, maker, settings, cap=5.0):
        self.requests: list[dict] = []
        self.headers: list[dict] = []
        self.down = False
        self.client = JevClient(settings, SpendTracker(maker, cap), httpx.AsyncClient(transport=httpx.MockTransport(self.handler)))

    def handler(self, req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        self.requests.append(body)
        self.headers.append(dict(req.headers))
        if self.down:
            return httpx.Response(500, text="down")
        answers = {}
        for key, q in body["questions"].items():
            assert q["type"] == "score" and q["criteria"] == RUBRIC
            title = body["state"]["candidates"][key]["title"]
            # Real shape (jev-1.14.0): 0-based score, probabilities keyed by level index, legend index -> label.
            probs = {str(i): p for i, p in enumerate(LEVELS[title])}
            answers[key] = {"type": "score", "score": sum(i * p for i, p in enumerate(LEVELS[title])), "confidence": max(probs.values()),
                            "legend": {str(i): lbl for i, lbl in enumerate(RUBRIC)}, "probabilities": probs}
        return httpx.Response(200, json={"model": "jev-latest", "answers": answers, "usage": {"input_tokens": 2000, "output_tokens": 5}})


async def search(maker, cfg, embedder, llm, jev, req):
    async with maker() as s:
        return await SearchService(cfg, embedder, llm, jev).search(s, req)


def jev_cfg(corpus, **kw):
    return corpus.model_copy(update={"jev_api_key": "k", "jev_base_url": "https://jev.test", **kw})


async def test_work_excerpts_best_chunks_per_work_and_null_text_skipped(maker, corpus, fake_embedder):  # noqa: F811
    ids = await work_ids(maker)
    vec = fake_embedder._vec("the dragon watched the restaurant kitchen")
    async with maker() as s:
        out = await work_excerpts(s, list(ids.values()), [vec], k=2)
        assert set(out) == set(ids.values())
        for lst in out.values():
            assert 1 <= len(lst) <= 2 and all(e.text for e in lst) and lst == sorted(lst, key=lambda e: -e.similarity)
        assert out[ids["Dragon Chef"]][0].similarity > out[ids["Sword Saint Rises"]][0].similarity
        # several scene vectors: similarity is the best over them
        multi = await work_excerpts(s, list(ids.values()), [vec, fake_embedder._vec("sword tournament arena")], k=1)
        assert multi[ids["Sword Saint Rises"]][0].similarity > out[ids["Sword Saint Rises"]][0].similarity
        assert len(multi[ids["Dragon Chef"]]) == 1
        # chunks without stored text are skipped; a work with none gets no entry
        sword_rec = select(SampleEmbedding.id).where(SampleEmbedding.chunk_text.like("%sword%"))
        await s.execute(update(SampleEmbedding).where(SampleEmbedding.id.in_(sword_rec)).values(chunk_text=None))
        await s.commit()
        again = await work_excerpts(s, list(ids.values()), [vec], k=3)
        assert ids["Sword Saint Rises"] not in again and ids["Dragon Chef"] in again
        assert await work_excerpts(s, [], [vec]) == {} and await work_excerpts(s, list(ids.values()), [vec], k=0) == {}


async def test_jev_rerank_batches_orders_drops_and_reasons_only_for_returned(maker, corpus, fake_embedder, fake_llm):  # noqa: F811
    cfg = jev_cfg(corpus, jev_rerank_batch=2)
    jev = FakeJev(maker, cfg)
    ids = await work_ids(maker)
    fake_llm.add("HydeOutput", hyde())
    fake_llm.add("ReasonsOutput", ReasonsOutput(reasons=[RerankItem(work_id=ids["Dragon Chef"], reason="Cook runs a dragon restaurant"),
                                                         RerankItem(work_id=ids["Sword Saint Rises"], reason="should be ignored")]))
    res = await search(maker, cfg, fake_embedder, fake_llm, jev.client, SearchRequest(description="a cook who befriends a dragon", limit=1))
    assert res.reranked and res.rerank_provider_used == "jev"
    assert [r.title for r in res.results] == ["Dragon Chef"]  # limit 1; Sword Saint dropped (p(Does not fit) 0.9)
    assert res.results[0].reason == "Cook runs a dragon restaurant" and 0.8 < res.results[0].rerank_score <= 1
    # 3 candidates in batches of 2 -> two concurrent requests with one score question per candidate
    assert sorted(len(r["questions"]) for r in jev.requests) == [1, 2]
    st = jev.requests[0]["state"]
    assert st["description"] and st["scenes"] == hyde().scenes
    cand = next(iter(st["candidates"].values()))
    assert set(cand) >= {"title", "aliases", "tags", "genres", "status", "chapters", "synopsis", "excerpts"} and cand["excerpts"]
    assert all(len(x) <= 600 for r in jev.requests for c in r["state"]["candidates"].values() for x in c["excerpts"])
    # Haiku wrote reasons for the one returned result only; Sonnet was never asked
    assert [c["schema"] for c in fake_llm.calls] == ["HydeOutput", "ReasonsOutput"]
    assert fake_llm.calls[1]["model"] == "claude-haiku-5-5" and json.loads(fake_llm.calls[1]["user"])["candidates"][0]["id"] == ids["Dragon Chef"]
    assert len(json.loads(fake_llm.calls[1]["user"])["candidates"]) == 1
    # spend recorded per request with Jev's usage tokens
    async with maker() as s:
        rows = (await s.scalars(select(AiUsage).where(AiUsage.provider == "jev"))).all()
    assert len(rows) == 2 and {r.purpose for r in rows} == {"rerank"}
    assert all(r.input_tokens == 2000 and float(r.cost_usd) == pytest.approx(2000 * 0.042 / 1_000_000) for r in rows)

    # full list: ordered by Jev score, misfit dropped, no reasons when disabled
    fake_llm.queue.clear()
    fake_llm.calls.clear()
    fake_llm.add("HydeOutput", hyde())
    res = await search(maker, jev_cfg(corpus, rerank_reasons=False), fake_embedder, fake_llm, jev.client, SearchRequest(description="dragon"))
    assert [r.title for r in res.results] == ["Dragon Chef", "Moonlit Bakery"] and all(r.reason == "" for r in res.results)
    assert res.results[0].rerank_score > res.results[1].rerank_score
    assert [c["schema"] for c in fake_llm.calls] == ["HydeOutput"]


async def test_jev_failure_falls_back_to_claude_then_retrieval(maker, corpus, fake_embedder, fake_llm):  # noqa: F811
    cfg = jev_cfg(corpus)
    jev = FakeJev(maker, cfg)
    jev.down = True
    ids = await work_ids(maker)
    fake_llm.add("HydeOutput", hyde())
    fake_llm.add("RerankOutput", RerankOutput(ranking=[RerankItem(work_id=ids["Moonlit Bakery"], reason="claude pick")]))
    res = await search(maker, cfg, fake_embedder, fake_llm, jev.client, SearchRequest(description="dragon"))
    assert res.reranked and res.rerank_provider_used == "claude" and res.results[0].reason == "claude pick" and res.results[0].rerank_score is None
    assert any("Jev rerank unavailable" in n for n in res.notes)

    class Down:
        async def parse(self, *, schema, **kw):
            if schema.__name__ == "HydeOutput":
                return hyde()
            from readerserver.ai.llm import LLMError
            raise LLMError("down")

    res = await search(maker, cfg, fake_embedder, Down(), jev.client, SearchRequest(description="dragon"))
    assert res.results and not res.reranked and res.rerank_provider_used is None

    # Jev unconfigured: silent when it was only the default, noted when asked for explicitly
    fake_llm.queue.clear()
    fake_llm.add("HydeOutput", hyde())
    fake_llm.add("RerankOutput", RerankOutput(ranking=[]))
    res = await search(maker, corpus, fake_embedder, fake_llm, None, SearchRequest(description="dragon", rerank_provider="jev"))
    assert res.rerank_provider_used == "claude" and any("Jev is not configured" in n for n in res.notes)


async def test_request_picks_provider_and_claude_payload_has_scenes_and_excerpts(maker, corpus, fake_embedder, fake_llm):  # noqa: F811
    cfg = jev_cfg(corpus)
    jev = FakeJev(maker, cfg)
    fake_llm.add("HydeOutput", hyde())
    fake_llm.add("RerankOutput", RerankOutput(ranking=[]))
    res = await search(maker, cfg, fake_embedder, fake_llm, jev.client, SearchRequest(description="dragon cook", rerank_provider="claude"))
    assert res.rerank_provider_used == "claude" and not jev.requests  # Jev default overridden per request
    call = next(c for c in fake_llm.calls if c["schema"] == "RerankOutput")
    payload = json.loads(call["user"])
    assert payload["scenes"] == hyde().scenes
    c0 = payload["candidates"][0]
    assert c0["excerpts"] and all(len(e) <= 600 for e in c0["excerpts"]) and c0["matched_scenes"] >= 0 and "id" in c0
    assert "excerpts" in call["system"] and "matched_scenes" in call["system"]

    # setting default "claude" with the request choosing jev
    fake_llm.queue.clear()
    fake_llm.calls.clear()
    fake_llm.add("HydeOutput", hyde())
    fake_llm.add("ReasonsOutput", ReasonsOutput(reasons=[]))
    res = await search(maker, cfg.model_copy(update={"rerank_provider": "claude"}), fake_embedder, fake_llm, jev.client,
                       SearchRequest(description="dragon cook", rerank_provider="jev"))
    assert res.rerank_provider_used == "jev" and jev.requests

    # excerpts disabled
    fake_llm.queue.clear()
    fake_llm.add("HydeOutput", hyde())
    fake_llm.add("RerankOutput", RerankOutput(ranking=[]))
    await search(maker, cfg.model_copy(update={"rerank_excerpts": 0}), fake_embedder, fake_llm, jev.client,
                 SearchRequest(description="dragon cook", rerank_provider="claude"))
    assert all(c["excerpts"] == [] for c in json.loads(fake_llm.calls[-1]["user"])["candidates"])
