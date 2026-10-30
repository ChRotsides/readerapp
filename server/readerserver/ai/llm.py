"""Claude via the official Anthropic SDK: structured output with Pydantic (`messages.parse`).

Rules followed: no prefill, no sampling params, no forced tool_choice, thinking left on (adaptive default), effort set
explicitly, stop_reason checked. claude-sonnet-5-5 calls use server-side fallback (beta
server-side-fallback-2026-07-01, fallbacks="default")."""
import logging
from typing import Protocol, TypeVar

from pydantic import BaseModel

from ..config import Settings
from .spend import SpendTracker, claude_cost

log = logging.getLogger(__name__)
T = TypeVar("T", bound=BaseModel)
FALLBACK_BETA = "server-side-fallback-2026-07-01"


class LLMError(Exception):
    pass


class LLMRefusal(LLMError):
    pass


class LLMTruncated(LLMError):
    pass


class LLM(Protocol):
    async def parse(
        self, *, model: str, system: str, user: str, schema: type[T], effort: str, max_tokens: int, purpose: str
    ) -> T: ...


class ClaudeLLM:
    def __init__(self, settings: Settings, spend: SpendTracker, client=None):
        self.settings = settings
        self.spend = spend
        if client is None:
            if not settings.anthropic_api_key:
                raise LLMError("ANTHROPIC_API_KEY not set")
            import anthropic

            client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
        self.client = client

    async def parse(
        self, *, model: str, system: str, user: str, schema: type[T], effort: str, max_tokens: int, purpose: str
    ) -> T:
        await self.spend.check()
        kwargs = dict(
            model=model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": "user", "content": user}],
            output_format=schema,
            output_config={"effort": effort},
        )
        if model.startswith("claude-sonnet-5-5"):
            resp = await self.client.beta.messages.parse(betas=[FALLBACK_BETA], fallbacks="default", **kwargs)
        else:
            resp = await self.client.messages.parse(**kwargs)
        usage = resp.usage
        in_t, out_t = int(usage.input_tokens or 0), int(usage.output_tokens or 0)
        await self.spend.record("anthropic", model, purpose, in_t, out_t, claude_cost(model, in_t, out_t))
        if resp.stop_reason == "refusal":
            raise LLMRefusal(f"{model} refused ({getattr(resp, 'stop_details', None)})")
        if resp.stop_reason == "max_tokens":
            raise LLMTruncated(f"{model} hit max_tokens={max_tokens} for {purpose}")
        parsed = resp.parsed_output
        if parsed is None:
            raise LLMError(f"{model} returned no parsed output for {purpose}")
        return parsed
