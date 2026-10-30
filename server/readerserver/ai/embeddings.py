"""Voyage AI embeddings. Model: voyage-4-large (best multilingual retrieval quality; 32k context; 1024 dims default;
input_type query|document). https://docs.voyageai.com/docs/embeddings  Max 1000 inputs and 120K tokens per request
for voyage-4-large, so we batch conservatively."""
import asyncio
import logging
from typing import Literal, Protocol

from ..config import Settings
from ..text import approx_tokens
from .spend import SpendTracker

log = logging.getLogger(__name__)
InputType = Literal["query", "document"]


class Embedder(Protocol):
    async def embed(self, texts: list[str], input_type: InputType, purpose: str = "embed") -> list[list[float]]: ...


class EmbeddingUnavailable(Exception):
    pass


def make_batches(texts: list[str], max_items: int = 128, max_tokens: int = 100_000) -> list[list[int]]:
    """Index batches respecting both item and (estimated) token limits."""
    batches: list[list[int]] = []
    cur: list[int] = []
    cur_tok = 0
    for i, t in enumerate(texts):
        tok = min(approx_tokens(t), 32_000)
        if cur and (len(cur) >= max_items or cur_tok + tok > max_tokens):
            batches.append(cur)
            cur, cur_tok = [], 0
        cur.append(i)
        cur_tok += tok
    if cur:
        batches.append(cur)
    return batches


class VoyageEmbedder:
    def __init__(self, settings: Settings, spend: SpendTracker, client=None):
        self.settings = settings
        self.spend = spend
        if client is None:
            if not settings.voyage_api_key:
                raise EmbeddingUnavailable("VOYAGE_API_KEY not set")
            import voyageai

            client = voyageai.AsyncClient(api_key=settings.voyage_api_key, max_retries=3)
        self.client = client

    async def embed(self, texts: list[str], input_type: InputType, purpose: str = "embed") -> list[list[float]]:
        out: list[list[float] | None] = [None] * len(texts)
        for batch in make_batches(texts):
            await self.spend.check()
            res = None
            for attempt in range(4):
                try:
                    res = await self.client.embed(
                        [texts[i] for i in batch],
                        model=self.settings.voyage_model,
                        input_type=input_type,
                        truncation=True,
                        output_dimension=self.settings.embedding_dim,
                    )
                    break
                except Exception as e:  # voyageai raises its own error types; retry transient ones
                    if attempt == 3 or type(e).__name__ in ("AuthenticationError", "InvalidRequestError"):
                        raise
                    await asyncio.sleep(2**attempt)
            assert res is not None
            tokens = int(getattr(res, "total_tokens", 0) or 0)
            await self.spend.record("voyage", self.settings.voyage_model, purpose, tokens, 0,
                                    tokens * self.settings.voyage_price_per_mtok / 1_000_000)
            for i, emb in zip(batch, res.embeddings, strict=True):
                out[i] = list(emb)
        return out  # type: ignore[return-value]
