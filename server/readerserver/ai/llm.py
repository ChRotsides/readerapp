"""Claude via the official Anthropic SDK: structured output with Pydantic (`messages.parse`).

Rules followed: no prefill, no sampling params, no forced tool_choice, thinking left on (adaptive default), effort set
explicitly, stop_reason checked. claude-sonnet-5-5 calls use server-side fallback (beta
server-side-fallback-2026-07-01, fallbacks="default")."""
import logging
from typing import Protocol, TypeVar

from pydantic import BaseModel

from ..config import Settings
from ..text import approx_tokens
from .spend import BudgetExceeded, SpendTracker, claude_cost, max_claude_cost

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
        kwargs = dict(
            model=model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": "user", "content": user}],
            output_format=schema,
            output_config={"effort": effort},
        )
        # Reserve the worst case (prompt + max_tokens at the dearest model a fallback may use) before the call.
        estimate = max_claude_cost(approx_tokens(system) + approx_tokens(user), max_tokens)
        async with self.spend.reserve(estimate, purpose) as reservation:
            try:
                if model.startswith("claude-sonnet-5-5"):
                    resp = await self.client.beta.messages.parse(betas=[FALLBACK_BETA], fallbacks="default", **kwargs)
                else:
                    resp = await self.client.messages.parse(**kwargs)
            except (LLMError, BudgetExceeded):
                raise
            except Exception as e:  # anthropic.APIConnectionError / RateLimitError / APIStatusError / parse errors ...
                raise LLMError(f"{model} request failed for {purpose}: {type(e).__name__}: {e}") from e
            rows = billed_attempts(resp, model, purpose)
            await reservation.settle_many(rows)
        if resp.stop_reason == "refusal":
            raise LLMRefusal(f"{model} refused ({getattr(resp, 'stop_details', None)})")
        if resp.stop_reason == "max_tokens":
            raise LLMTruncated(f"{model} hit max_tokens={max_tokens} for {purpose}")
        parsed = resp.parsed_output
        if parsed is None:
            raise LLMError(f"{model} returned no parsed output for {purpose}")
        return parsed


def _n(d, key: str) -> int:
    v = d.get(key) if isinstance(d, dict) else getattr(d, key, None)
    return int(v or 0)


def _effective_input(d) -> int:
    """Cache writes bill at 1.25x and cache reads at 0.1x of the input price; fold into one input-token figure."""
    return _n(d, "input_tokens") + round(_n(d, "cache_creation_input_tokens") * 1.25 + _n(d, "cache_read_input_tokens") * 0.1)


def billed_attempts(resp, requested_model: str, purpose: str) -> list[tuple[str, str, str, int, int, float]]:
    """Usage rows to record: with server-side fallback, `usage.iterations` lists every attempt (each charged at ITS
    model's price, cache tokens included); otherwise one row at the model that actually served the response."""
    usage = resp.usage
    served = getattr(resp, "model", None)
    served = served if isinstance(served, str) and served else requested_model
    iterations = getattr(usage, "iterations", None)
    if iterations is None:
        extra = getattr(usage, "model_extra", None) or getattr(usage, "_additionalProperties", None) or {}
        iterations = extra.get("iterations") if isinstance(extra, dict) else None
    rows: list[tuple[str, str, str, int, int, float]] = []
    for it in iterations or []:
        i_t, o_t = _effective_input(it), _n(it, "output_tokens")
        if not i_t and not o_t:
            continue
        m = (it.get("model") if isinstance(it, dict) else getattr(it, "model", None)) or served
        rows.append(("anthropic", m, purpose, i_t, o_t, claude_cost(m, i_t, o_t)))
    if rows:
        return rows
    i_t, o_t = _effective_input(usage), _n(usage, "output_tokens")
    return [("anthropic", served, purpose, i_t, o_t, claude_cost(served, i_t, o_t))]
