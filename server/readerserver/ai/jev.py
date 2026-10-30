"""Jev (TypeSafe AI) typed verdicts: POST {base}/v1/systemone, Bearer auth, a `choice` question returns
{choice, confidence, probabilities{option: p}}. https://docs.typesafe.ai/api"""
import json
import logging
from dataclasses import dataclass
from typing import Literal, Protocol

import httpx
from pydantic import BaseModel

from ..config import Settings
from .llm import LLM
from .spend import SpendTracker

log = logging.getLogger(__name__)
Verdict = Literal["SAME_WORK", "DIFFERENT_WORK", "RELATED_WORK"]
CRITERIA = {
    "SAME_WORK": "Both records describe the same novel (same story and author), possibly under different titles, languages or sites.",
    "DIFFERENT_WORK": "The records are unrelated novels that merely look similar (shared tropes, similar title).",
    "RELATED_WORK": "Distinct but related works: web-novel vs light-novel rewrite, sequel, prequel, side story, spin-off, or a separate alternate translation.",
}
INSTRUCTIONS = (
    "Decide whether record_a and record_b are the same novel. Compare titles/aliases (including native-script titles), "
    "authors, synopses, language, start year and chapter counts."
)


@dataclass
class MatchVerdict:
    verdict: Verdict
    probability: float
    method: str  # jev | claude
    probabilities: dict[str, float]
    reason: str = ""
    relation: str | None = None


class VerdictProvider(Protocol):
    async def verdict(self, a: dict, b: dict) -> MatchVerdict: ...


class ClaudeVerdict(BaseModel):
    verdict: Verdict
    confidence: float
    reason: str
    relation: Literal["LN_VERSION", "SEQUEL", "SIDE_STORY", "ALT_TRANSLATION", "RELATED"] | None = None


class JevClient:
    def __init__(self, settings: Settings, spend: SpendTracker, http: httpx.AsyncClient | None = None):
        self.settings = settings
        self.spend = spend
        self.http = http or httpx.AsyncClient(timeout=60)

    def available(self) -> bool:
        return bool(self.settings.jev_api_key)

    async def choose(self, key: str, instructions: str, criteria: dict[str, str], state: dict, purpose: str) -> tuple[str, float, dict[str, float]]:
        """One `choice` question -> (choice, probability of that choice, all probabilities)."""
        async with self.spend.reserve(self.settings.jev_price_per_call, purpose) as reservation:
            resp = await self.http.post(
                f"{self.settings.jev_base_url.rstrip('/')}/v1/systemone",
                headers={"Authorization": f"Bearer {self.settings.jev_api_key}"},
                json={
                    "model": self.settings.jev_model,
                    "state": state,
                    "questions": {key: {"type": "choice", "instructions": instructions, "criteria": criteria}},
                },
            )
            resp.raise_for_status()
            data = resp.json()
            ans = data["answers"][key]
            probs = {k: float(v) for k, v in (ans.get("probabilities") or {}).items()}
            choice = ans["choice"]
            usage = data.get("usage") or {}
            await reservation.settle("jev", data.get("model", self.settings.jev_model), purpose,
                                     int(usage.get("input_tokens", 0)), int(usage.get("output_tokens", 0)),
                                     self.settings.jev_price_per_call)
        return choice, probs.get(choice, float(ans.get("confidence", 0))), probs

    async def ask(self, a: dict, b: dict) -> MatchVerdict:
        choice, p, probs = await self.choose("match", INSTRUCTIONS, CRITERIA, {"record_a": a, "record_b": b}, "verdict")
        return MatchVerdict(choice, p, "jev", probs)


class CompositeVerdict:
    """Jev first; Claude Haiku structured output when Jev is unconfigured, errors, or is unsure."""

    def __init__(self, settings: Settings, jev: JevClient | None, llm: LLM | None):
        self.settings = settings
        self.jev = jev
        self.llm = llm

    async def verdict(self, a: dict, b: dict) -> MatchVerdict:
        jev_result: MatchVerdict | None = None
        if self.jev and self.jev.available():
            try:
                jev_result = await self.jev.ask(a, b)
                if jev_result.probability >= self.settings.jev_min_probability:
                    return jev_result
            except Exception as e:  # network/budget/validation: fall back
                log.warning("Jev unavailable (%s); falling back to Claude", e)
        if self.llm is None:
            if jev_result:
                return jev_result
            raise RuntimeError("no verdict provider available")
        out = await self.llm.parse(
            model=self.settings.verdict_model,
            system="You judge whether two catalog records describe the same web/light novel. " + INSTRUCTIONS
            + " Options: " + json.dumps(CRITERIA),
            user=json.dumps({"record_a": a, "record_b": b}, ensure_ascii=False),
            schema=ClaudeVerdict,
            effort=self.settings.verdict_effort,
            max_tokens=4000,
            purpose="verdict",
        )
        return MatchVerdict(out.verdict, max(0.0, min(1.0, out.confidence)), "claude", {out.verdict: out.confidence}, out.reason, out.relation)
