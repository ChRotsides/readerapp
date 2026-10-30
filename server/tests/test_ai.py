import json
from types import SimpleNamespace as NS

import httpx
import pytest

from readerserver.ai.embeddings import VoyageEmbedder
from readerserver.ai.jev import ClaudeVerdict, CompositeVerdict, JevClient
from readerserver.ai.llm import FALLBACK_BETA, ClaudeLLM, LLMRefusal, LLMTruncated
from readerserver.ai.spend import BudgetExceeded, SpendTracker
from readerserver.config import Settings
from readerserver.search.schemas import RerankOutput


class FakeMessages:
    def __init__(self, resp):
        self.resp, self.kwargs = resp, None

    async def parse(self, **kw):
        self.kwargs = kw
        return self.resp


def anthropic_fake(resp):
    return NS(messages=FakeMessages(resp), beta=NS(messages=FakeMessages(resp)))


def resp(parsed, stop="end_turn"):
    return NS(parsed_output=parsed, stop_reason=stop, stop_details=None, usage=NS(input_tokens=1000, output_tokens=500))


@pytest.fixture
def spend(maker):
    return SpendTracker(maker, 5.0)


async def test_claude_haiku_uses_plain_parse_with_effort_and_no_sampling_params(spend):
    out = RerankOutput(ranking=[])
    client = anthropic_fake(resp(out))
    llm = ClaudeLLM(Settings(), spend, client=client)
    got = await llm.parse(model="claude-haiku-5-5", system="s", user="u", schema=RerankOutput, effort="low", max_tokens=2000, purpose="hyde")
    assert got is out
    kw = client.messages.kwargs
    assert kw["model"] == "claude-haiku-5-5" and kw["output_format"] is RerankOutput and kw["output_config"] == {"effort": "low"}
    assert not ({"temperature", "top_p", "top_k", "thinking", "tool_choice"} & kw.keys())
    assert kw["messages"] == [{"role": "user", "content": "u"}]  # no prefill
    assert client.beta.messages.kwargs is None
    assert (await spend.summary())["breakdown"][0]["purpose"] == "hyde"


async def test_claude_sonnet_uses_server_side_fallback_beta(spend):
    client = anthropic_fake(resp(RerankOutput(ranking=[])))
    llm = ClaudeLLM(Settings(), spend, client=client)
    await llm.parse(model="claude-sonnet-5-5", system="s", user="u", schema=RerankOutput, effort="medium", max_tokens=2000, purpose="rerank")
    kw = client.beta.messages.kwargs
    assert kw["betas"] == [FALLBACK_BETA] and kw["fallbacks"] == "default" and kw["model"] == "claude-sonnet-5-5"
    assert client.messages.kwargs is None


async def test_claude_stop_reasons(spend):
    for stop, exc in (("refusal", LLMRefusal), ("max_tokens", LLMTruncated)):
        llm = ClaudeLLM(Settings(), spend, client=anthropic_fake(resp(None, stop)))
        with pytest.raises(exc):
            await llm.parse(model="claude-haiku-5-5", system="s", user="u", schema=RerankOutput, effort="low", max_tokens=10, purpose="x")


async def test_claude_respects_budget(maker):
    sp = SpendTracker(maker, 0.0)
    llm = ClaudeLLM(Settings(), sp, client=anthropic_fake(resp(None)))
    with pytest.raises(BudgetExceeded):
        await llm.parse(model="claude-haiku-5-5", system="s", user="u", schema=RerankOutput, effort="low", max_tokens=10, purpose="x")


class FakeVoyage:
    def __init__(self):
        self.calls = []

    async def embed(self, texts, model, input_type=None, truncation=None, output_dimension=None, output_dtype="float"):
        self.calls.append(dict(n=len(texts), model=model, input_type=input_type, dim=output_dimension))
        return NS(embeddings=[[0.1] * 4 for _ in texts], total_tokens=10 * len(texts))


async def test_voyage_embedder_params_batching_and_spend(spend):
    fv = FakeVoyage()
    emb = VoyageEmbedder(Settings(voyage_model="voyage-4-large", embedding_dim=1024), spend, client=fv)
    out = await emb.embed(["x"] * 300, "document", "profile")
    assert len(out) == 300 and len(fv.calls) == 3  # 128-item batches
    assert fv.calls[0] == dict(n=128, model="voyage-4-large", input_type="document", dim=1024)
    await emb.embed(["q"], "query")
    assert fv.calls[-1]["input_type"] == "query"
    assert (await spend.summary())["spent_usd"] > 0


def jev_client(spend, handler, key="k"):
    s = Settings(jev_api_key=key, jev_base_url="https://jev.test")
    return JevClient(s, spend, http=httpx.AsyncClient(transport=httpx.MockTransport(handler)))


def jev_answer(choice, probs):
    return {"model": "jev-latest", "answers": {"match": {"type": "choice", "choice": choice, "confidence": probs[choice], "probabilities": probs}},
            "usage": {"input_tokens": 50, "output_tokens": 3}}


async def test_jev_request_shape_and_confident_verdict(spend):
    seen = {}

    def handler(req):
        seen["url"], seen["auth"], seen["body"] = str(req.url), req.headers["authorization"], json.loads(req.content)
        return httpx.Response(200, json=jev_answer("SAME_WORK", {"SAME_WORK": 0.9, "DIFFERENT_WORK": 0.05, "RELATED_WORK": 0.05}))

    jev = jev_client(spend, handler)
    cv = CompositeVerdict(Settings(jev_api_key="k"), jev, None)
    v = await cv.verdict({"title": "a"}, {"title": "b"})
    assert (v.verdict, v.method) == ("SAME_WORK", "jev") and v.probability == 0.9
    assert seen["url"] == "https://jev.test/v1/systemone" and seen["auth"] == "Bearer k"
    q = seen["body"]["questions"]["match"]
    assert seen["body"]["model"] == "jev-latest" and q["type"] == "choice"
    assert set(q["criteria"]) == {"SAME_WORK", "DIFFERENT_WORK", "RELATED_WORK"}
    assert seen["body"]["state"]["record_a"] == {"title": "a"}


async def test_jev_unsure_or_down_falls_back_to_claude(spend, fake_llm):
    fake_llm.add("ClaudeVerdict", ClaudeVerdict(verdict="RELATED_WORK", confidence=0.77, reason="sequel", relation="SEQUEL"))
    unsure = jev_client(spend, lambda r: httpx.Response(200, json=jev_answer("SAME_WORK", {"SAME_WORK": 0.4, "DIFFERENT_WORK": 0.35, "RELATED_WORK": 0.25})))
    cv = CompositeVerdict(Settings(jev_api_key="k"), unsure, fake_llm)
    v = await cv.verdict({}, {})
    assert (v.verdict, v.method, v.relation) == ("RELATED_WORK", "claude", "SEQUEL")
    assert fake_llm.calls[0]["model"] == "claude-haiku-5-5"

    down = jev_client(spend, lambda r: httpx.Response(500, text="err"))
    v = await CompositeVerdict(Settings(jev_api_key="k"), down, fake_llm).verdict({}, {})
    assert v.method == "claude"

    # no Jev key configured -> Claude directly
    v = await CompositeVerdict(Settings(), None, fake_llm).verdict({}, {})
    assert v.method == "claude"


# ---------------------------------------------------------------- review-3 #15 / #16 / #20
async def test_concurrent_reservations_cannot_overshoot_cap(maker):
    import asyncio

    sp = SpendTracker(maker, 1.0)

    async def one():
        try:
            async with sp.reserve(0.6, "t") as r:
                await asyncio.sleep(0.2)  # in-flight while the other tries
                await r.settle("anthropic", "m", "t", 1, 1, 0.5)
            return "ok"
        except BudgetExceeded:
            return "denied"

    results = await asyncio.gather(one(), one(), one())
    assert sorted(results) == ["denied", "denied", "ok"]
    assert (await sp.summary())["spent_usd"] == 0.5


async def test_reservation_released_on_failure_and_held_while_in_flight(maker):
    sp = SpendTracker(maker, 1.0)
    with pytest.raises(RuntimeError):
        async with sp.reserve(0.9, "t"):
            with pytest.raises(BudgetExceeded):
                async with sp.reserve(0.2, "t"):
                    pass
            raise RuntimeError("call failed")
    async with sp.reserve(0.9, "t") as r:  # budget came back
        await r.settle("anthropic", "m", "t", 1, 1, 0.1)
    assert (await sp.summary())["spent_usd"] == 0.1


async def test_expired_reservations_do_not_hold_budget(maker):
    from datetime import UTC, datetime, timedelta

    from readerserver.models import SpendReservation

    sp = SpendTracker(maker, 1.0)
    async with maker() as s:
        s.add(SpendReservation(amount_usd=1.0, expires_at=datetime.now(UTC) - timedelta(minutes=1)))
        await s.commit()
    async with sp.reserve(0.5, "t"):
        pass


async def test_fallback_iterations_billed_per_attempt_model_with_cache_tokens(spend):
    usage = NS(input_tokens=300, output_tokens=200, iterations=[
        {"type": "message", "model": "claude-sonnet-5-5", "input_tokens": 1000, "output_tokens": 0},
        {"type": "fallback_message", "model": "claude-haiku-5-5", "input_tokens": 100, "output_tokens": 200,
         "cache_creation_input_tokens": 400, "cache_read_input_tokens": 1000},
    ])
    r = NS(parsed_output=RerankOutput(ranking=[]), stop_reason="end_turn", stop_details=None, usage=usage, model="claude-haiku-5-5")
    llm = ClaudeLLM(Settings(), spend, client=anthropic_fake(r))
    await llm.parse(model="claude-sonnet-5-5", system="s", user="u", schema=RerankOutput, effort="low", max_tokens=100, purpose="rerank")
    # sonnet: 1000 in @2/M; haiku: (100 + 400*1.25 + 1000*0.1)=700 in @0.1/M + 200 out @0.5/M
    expected = 1000 * 2.0 / 1e6 + (700 * 0.10 + 200 * 0.50) / 1e6
    assert (await spend.summary())["spent_usd"] == pytest.approx(expected, abs=1e-4)


async def test_without_iterations_actual_serving_model_is_priced(spend):
    usage = NS(input_tokens=1_000_000, output_tokens=0)
    r = NS(parsed_output=RerankOutput(ranking=[]), stop_reason="end_turn", stop_details=None, usage=usage, model="claude-haiku-5-5")
    llm = ClaudeLLM(Settings(), spend, client=anthropic_fake(r))
    await llm.parse(model="claude-sonnet-5-5", system="s", user="u", schema=RerankOutput, effort="low", max_tokens=100, purpose="rerank")
    assert (await spend.summary())["spent_usd"] == pytest.approx(0.10, abs=1e-4)  # not the $2.00 sonnet price


async def test_sdk_errors_become_llm_error_and_release_reservation(spend):
    from readerserver.ai.llm import LLMError

    class Boom:
        async def parse(self, **kw):
            raise ConnectionError("network down")

    client = NS(messages=Boom(), beta=NS(messages=Boom()))
    llm = ClaudeLLM(Settings(), spend, client=client)
    for model in ("claude-haiku-5-5", "claude-sonnet-5-5"):
        with pytest.raises(LLMError):
            await llm.parse(model=model, system="s", user="u", schema=RerankOutput, effort="low", max_tokens=100, purpose="x")
    assert await spend._held() == 0
