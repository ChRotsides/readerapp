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
