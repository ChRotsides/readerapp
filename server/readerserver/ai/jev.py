"""Jev (TypeSafe AI) typed verdicts: POST {base}/v1/systemone, Bearer auth, a `choice` question returns
{choice, confidence, probabilities{option: p}}. https://docs.typesafe.ai/api"""
import json
import logging
from dataclasses import dataclass
from decimal import Decimal
from typing import Literal, Protocol

import httpx
from pydantic import BaseModel

from ..config import Settings
from .llm import LLM
from ..text import approx_tokens
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


LONG_CONTEXT_TOKENS = 20_000  # state larger than this (estimated) is sent with the long-context header


@dataclass
class JevScore:
    score: float  # probability-weighted average level index, 0-based (as returned by Jev)
    confidence: float
    probabilities: dict[str, float]  # rubric level -> probability


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

    async def _post(self, questions: dict, state, purpose: str) -> dict:
        """Reserve a conservative input estimate; settle using Jev's billable input count."""
        price = Decimal(str(self.settings.jev_price_per_mtok_input)) / Decimal(1_000_000)
        payload = {"model": self.settings.jev_model, "state": state, "questions": questions}
        # Include the questions as well as state. A byte-based allowance plus framing
        # headroom avoids relying on our approximate tokenizer for budget reservations.
        estimate = float((len(json.dumps(payload, ensure_ascii=False).encode()) + 1024) * price)
        async with self.spend.reserve(estimate, purpose) as reservation:
            headers = {"Authorization": f"Bearer {self.settings.jev_api_key}"}
            if approx_tokens(json.dumps(state, ensure_ascii=False)) > LONG_CONTEXT_TOKENS:
                headers["TypeSafe-Danger-Zone-Long-Context"] = "true"
            resp = await self.http.post(
                f"{self.settings.jev_base_url.rstrip('/')}/v1/systemone", headers=headers,
                json=payload,
            )
            resp.raise_for_status()
            data = resp.json()
            usage = data["usage"]
            tin, tout = int(usage["input_tokens"]), int(usage.get("output_tokens", 0))
            if tin < 0 or tout < 0:
                raise ValueError("Jev returned negative token usage")
            await reservation.settle("jev", data.get("model", self.settings.jev_model), purpose, tin, tout, tin * price)
        return data["answers"]

    async def choose(self, key: str, instructions: str, criteria: dict[str, str], state: dict, purpose: str) -> tuple[str, float, dict[str, float]]:
        """One `choice` question -> (choice, probability of that choice, all probabilities)."""
        answers = await self._post({key: {"type": "choice", "instructions": instructions, "criteria": criteria}}, state, purpose)
        ans = answers[key]
        probs = {k: float(v) for k, v in (ans.get("probabilities") or {}).items()}
        choice = ans["choice"]
        return choice, probs.get(choice, float(ans.get("confidence", 0))), probs

    async def score(self, questions: dict[str, str], criteria: list[str], state: dict, purpose: str) -> dict[str, JevScore]:
        """One request with a `score` question per key (key -> instructions) over a shared rubric (lowest level first).
        The shared state's cost is included in the API's billable input count; output is free."""
        answers = await self._post({k: {"type": "score", "instructions": ins, "criteria": criteria} for k, ins in questions.items()},
                                   state, purpose)
        out: dict[str, JevScore] = {}
        for k in questions:
            ans = answers[k]
            # Jev keys probabilities by level index ("0".."n-1") with a `legend` index -> label; store them by label.
            legend = {str(i): str(lbl) for i, lbl in (ans.get("legend") or dict(enumerate(criteria))).items()}
            probs = {legend.get(str(a), str(a)): float(b) for a, b in (ans.get("probabilities") or {}).items()}
            out[k] = JevScore(float(ans["score"]), float(ans.get("confidence", 0)), probs)
        return out

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
