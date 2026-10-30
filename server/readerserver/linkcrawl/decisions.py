"""The four typed decisions of a link crawl, answered by Jev (calibrated `choice` questions) with Claude Haiku as the
fallback when Jev is unconfigured, down, or unsure (same policy as ai/jev.py's CompositeVerdict):

  (a) page_type       novel_listing | novel_page | chapter_list | chapter | other
  (b) link_roles      novel | chapter | next_page | skip      (while a recipe is built / validated)
  (c) chapter_content story | author_note_or_announcement | locked_or_placeholder | junk   (only `story` is embedded)
  (d) extraction_quality  clean | has_junk | wrong_content     (anything but clean triggers recipe repair)

With neither provider configured every method returns None and the caller uses its heuristics. BudgetExceeded always
propagates (the job pauses). Spend is recorded by JevClient / ClaudeLLM under purposes tagged `lc<job id>:...`."""
import json
import logging
import re
from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel

from ..ai.jev import JevClient
from ..ai.llm import LLM, LLMError
from ..ai.spend import BudgetExceeded
from ..config import Settings

log = logging.getLogger(__name__)
PageType = Literal["novel_listing", "novel_page", "chapter_list", "chapter", "other"]
LinkRole = Literal["novel", "chapter", "next_page", "skip"]
ContentKind = Literal["story", "author_note_or_announcement", "locked_or_placeholder", "junk"]
Quality = Literal["clean", "has_junk", "wrong_content"]

PAGE_TYPE = (
    "What kind of web-novel site page is this? Judge from the url, title, headings, link and paragraph counts and text sample.",
    {
        "novel_listing": "A list/grid of many novels (ranking, genre page, search results, latest updates) linking to individual novels.",
        "novel_page": "The page of ONE novel: title, author, synopsis, tags and usually its chapter list or a link to it.",
        "chapter_list": "A table of contents listing many chapters of one novel (possibly one page of a paginated list).",
        "chapter": "A page whose main content is the reading text of one chapter.",
        "other": "Anything else: home page, login, search form, forum, blog, error page, ad page.",
    },
)
LINK_ROLE = (
    "What does this link on a web-novel site lead to? Judge from its anchor text and url, and the kind of page it was found on.",
    {
        "novel": "The page of one novel (not a chapter, author, tag or genre page).",
        "chapter": "One chapter of a novel.",
        "next_page": "The next page of a paginated list (next, older, more, page number after the current one).",
        "skip": "Anything else: navigation, login, author/tag/genre pages, ads, social links, external sites.",
    },
)
CHAPTER_CONTENT = (
    "Is this extracted chapter text the actual story prose of a novel chapter that a reader can read? Judge from the "
    "title, the start and end of the text and its length.",
    {
        "story": "Narrative prose of the novel itself (it may contain a short author note at the end).",
        "author_note_or_announcement": "An author's note, announcement, hiatus notice, release schedule, patreon/donation post or similar, not story text.",
        "locked_or_placeholder": "Paywalled / locked / login-required / 'coming soon' / 'chapter removed' placeholder with no real story text.",
        "junk": "Navigation, ads, comments, boilerplate, error text or otherwise not chapter prose.",
    },
)
QUALITY = (
    "A site recipe (CSS selectors) extracted this content from a page. Is the extraction what we wanted?",
    {
        "clean": "Only the wanted content: for a chapter, just the title and story prose; for a novel page, correct title/author/synopsis/tags/status.",
        "has_junk": "The wanted content is there but mixed with junk: menus, ads, 'next chapter' buttons, comments, share bars, copyright footers, script text, repeated titles.",
        "wrong_content": "The extraction is not the wanted content at all (site boilerplate, a menu, a different page section, empty or truncated fragments).",
    },
)


@dataclass
class Decision:
    choice: str
    probability: float
    method: str  # jev | claude


class _Out(BaseModel):
    confidence: float
    reason: str


class PageTypeOut(_Out):
    choice: PageType


class ContentOut(_Out):
    choice: ContentKind


class QualityOut(_Out):
    choice: Quality


class LinkRolesOut(BaseModel):
    roles: list[LinkRole]  # one per input link, same order


class Decider:
    def __init__(self, settings: Settings, jev: JevClient | None, llm: LLM | None, tag: str = "lc"):
        self.settings, self.jev, self.llm, self.tag = settings, jev, llm, tag

    def available(self) -> bool:
        return bool((self.jev and self.jev.available()) or self.llm)

    async def _jev(self, key: str, question: tuple[str, dict], state: dict) -> Decision | None:
        if not (self.jev and self.jev.available()):
            return None
        try:
            choice, p, _ = await self.jev.choose(key, question[0], question[1], state, f"{self.tag}:{key}"[:40])
            return Decision(choice, p, "jev")
        except BudgetExceeded:
            raise
        except Exception as e:  # network / validation: fall back to Claude
            log.warning("Jev unavailable for %s (%s); falling back to Claude", key, e)
            return None

    async def _claude(self, key: str, question: tuple[str, dict], state: dict, schema) -> Decision | None:
        if self.llm is None:
            return None
        try:
            out = await self.llm.parse(
                model=self.settings.decision_model,
                system=question[0] + " Options: " + json.dumps(question[1]),
                user=json.dumps(state, ensure_ascii=False), schema=schema, effort=self.settings.decision_effort,
                max_tokens=2000, purpose=f"{self.tag}:{key}"[:40])
        except BudgetExceeded:
            raise
        except LLMError as e:
            log.warning("Claude decision %s failed: %s", key, e)
            return None
        return Decision(out.choice, max(0.0, min(1.0, out.confidence)), "claude")

    async def _decide(self, key: str, question: tuple[str, dict], state: dict, schema) -> Decision | None:
        jev = await self._jev(key, question, state)
        if jev is not None and jev.probability >= self.settings.jev_min_probability:
            return jev
        return (await self._claude(key, question, state, schema)) or jev

    # (a)
    async def page_type(self, digest: dict) -> Decision | None:
        return await self._decide("page_type", PAGE_TYPE, digest, PageTypeOut)

    # (c)
    async def chapter_content(self, title: str | None, text: str, length_info: dict) -> Decision | None:
        state = {"title": title, "text_head": text[:1500], "text_tail": text[-500:] if len(text) > 2000 else "", "length": len(text), **length_info}
        return await self._decide("chapter", CHAPTER_CONTENT, state, ContentOut)

    # (d)
    async def extraction_quality(self, kind: str, extracted: dict) -> Decision | None:
        return await self._decide("quality", QUALITY, {"page_kind": kind, "extracted": extracted}, QualityOut)

    # (b)
    async def link_roles(self, links: list[tuple[str, str]], found_on: str) -> list[Decision] | None:
        """Role of each (anchor text, url) link. Jev per link (a calibrated answer each); links Jev is unsure of go to
        Claude in one batch call. None when no provider is configured."""
        if not self.available():
            return None
        out: list[Decision | None] = []
        for text, url in links:
            out.append(await self._jev("role", LINK_ROLE, {"anchor_text": text, "url": url, "found_on": found_on}))
        todo = [i for i, d in enumerate(out) if d is None or d.probability < self.settings.jev_min_probability]
        if todo and self.llm is not None:
            try:
                res = await self.llm.parse(
                    model=self.settings.decision_model,
                    system=LINK_ROLE[0] + " Options: " + json.dumps(LINK_ROLE[1]) + " Return one role per link, in order.",
                    user=json.dumps({"found_on": found_on, "links": [{"anchor_text": links[i][0], "url": links[i][1]} for i in todo]}, ensure_ascii=False),
                    schema=LinkRolesOut, effort=self.settings.decision_effort, max_tokens=2000, purpose=f"{self.tag}:roles"[:40])
                if len(res.roles) == len(todo):
                    for i, role in zip(todo, res.roles, strict=True):
                        out[i] = Decision(role, 0.8, "claude")
            except BudgetExceeded:
                raise
            except LLMError as e:
                log.warning("Claude link-role decision failed: %s", e)
        return [d or Decision("skip", 0.0, "none") for d in out]


# ---------------------------------------------------------------- heuristics (no provider / cheap pre-checks)
LOCK_WORDS = re.compile(
    r"(this chapter is (locked|premium|for (subscribers|vip|members))|unlock (this|the) chapter|subscribe to (read|continue)|"
    r"log ?in to (read|continue)|sign ?in to (read|continue)|paywall|premium chapter|coming soon|purchase (this )?chapter|"
    r"buy (this|the) chapter|to unlock|advance chapter|chapter (is )?(not available|removed|deleted)|members only|vip only)", re.I)
SHORT_CHARS = 400
DEVIATION = 3.0


def chapter_suspicion(text: str, median_len: float | None) -> list[str]:
    """Reasons to spend a Jev call on this chapter (empty = looks like an ordinary chapter)."""
    reasons = []
    if len(text) < SHORT_CHARS:
        reasons.append("very_short")
    if LOCK_WORDS.search(text[:3000]) or (len(text) < 2000 and LOCK_WORDS.search(text)):
        reasons.append("lock_words")
    if median_len and (len(text) < median_len / DEVIATION or len(text) > median_len * DEVIATION):
        reasons.append("length_deviation")
    return reasons


def chapter_heuristic(text: str, reasons: list[str]) -> str:
    """Decision (c) without a provider."""
    if "lock_words" in reasons and len(text) < 2000:
        return "locked_or_placeholder"
    if len(text) < 150:
        return "junk"
    return "story"
