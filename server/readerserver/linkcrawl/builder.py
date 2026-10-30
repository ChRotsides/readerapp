"""Writing, validating, saving and repairing site recipes (Claude writes them; Jev judges them).

One `RecipeSession` per job: it loads the domain's active recipe, builds missing sections from sample pages the
crawler already fetched (a new domain gets its sections as the crawl first meets each kind of page, all saved into one
version), and repairs a section that stopped working: older versions first (newest first, reactivated if they pass),
then Claude writes version n+1 from the failing page. A new section is only saved once it validates on its sample
pages: selectors compile and extract what they should, Jev says the links have the right roles and the extraction is
`clean`."""
import json
import logging
from dataclasses import dataclass
from collections.abc import Awaitable, Callable

from pydantic import BaseModel

from ..ai.llm import LLM
from ..config import Settings
from .decisions import Decider
from .recipes import (
    KINDS,
    SECTION_MODELS,
    ChapterSection,
    Link,
    ListingSection,
    NovelResult,
    NovelSection,
    RecipeStore,
    apply_chapter,
    apply_listing,
    apply_novel,
    clean_html,
    selector_problems,
)

log = logging.getLogger(__name__)
Fetch = Callable[[str], Awaitable[str | None]]
ROLE_SAMPLE = 5
ROLE_PASS = 0.7
MIN_BODY_CHARS = 200
MIN_REPAIR_CHARS = 50  # a repaired chapter recipe must extract at least this much from the failing page itself
AUTHOR_ATTEMPTS = 3
REPAIR_ATTEMPTS = 2

SELECTOR_RULES = (
    "Selectors are CSS selectors (soupsieve syntax). Append '@attr' to read an attribute instead of the text, e.g. "
    "'div.cover img@src'. Prefer stable ids/classes/itemprop over positional selectors. Selectors must work on OTHER pages "
    "of the same kind on this site, not only on the samples. Use null for a field the page does not have. The pages are "
    "cleaned skeletons: scripts removed, attributes reduced, long text and long runs of similar siblings truncated."
)
AUTHOR_SYSTEM = {
    "listing": "You write a scraping recipe for a web-novel site's LISTING page (many novels: ranking, genre, search, latest). "
               "novel_links: selector for the <a> elements that open each novel's own page (one per novel, not author/genre/chapter links). "
               "next_page: selector for the link to the next page of the list, or null. " + SELECTOR_RULES,
    "novel": "You write a scraping recipe for a web-novel site's NOVEL page. title, authors (all matches), synopsis (the container), "
             "tags, genres, cover (img@src), status (element whose text says ongoing/completed). chapter_links: the <a> elements for "
             "each chapter in the list. If the chapters are on a separate table-of-contents page, toc_link is the link to it "
             "(chapter_links then describe the links on THAT page; if you were not shown it, make your best guess from the novel page). "
             "chapter_list_next: link to the next page of a paginated chapter list, or null. chapter_order: 'asc' if the first link "
             "is chapter 1, 'desc' if newest first, otherwise 'auto'. " + SELECTOR_RULES,
    "chapter": "You write a scraping recipe for a web-novel site's CHAPTER page. title: the chapter title element or null. body: the "
               "container(s) holding ONLY the story prose (paragraphs). drop: selectors for junk INSIDE the body container to remove "
               "(ads, share bars, 'next chapter' buttons, comments, author/translator notes boxes, hidden anti-copy text). "
               "novel_link: selector for the link back to the novel's page or table of contents (breadcrumb), or null. "
               + SELECTOR_RULES,
}


@dataclass
class Problem:
    """Why a recipe was rejected. `label` is safe to store and raise (no page content: it ends up in site_recipes.notes and
    link_crawl_jobs.error); `feedback` may quote extracted text and goes only to Claude (never stored)."""

    label: str
    feedback: str = ""

    def __post_init__(self):
        self.feedback = self.feedback or self.label

    @classmethod
    def of(cls, p: "Problem | str") -> "Problem":
        return p if isinstance(p, Problem) else cls(p)


class RecipeBroken(Exception):
    """A recipe section stopped working and could not be repaired."""


class RecipeUnavailable(Exception):
    """A recipe has to be written/repaired but no Claude client is configured."""


def _evenly(items: list[Link], n: int) -> list[Link]:
    if len(items) <= n:
        return items
    return [items[round(i * (len(items) - 1) / (n - 1))] for i in range(n)]


class RecipeSession:
    def __init__(self, domain: str, store: RecipeStore, settings: Settings, llm: LLM | None, decider: Decider, fetch: Fetch, tag: str):
        self.domain, self.store, self.settings, self.llm, self.decider, self.fetch, self.tag = domain, store, settings, llm, decider, fetch, tag
        self.sections: dict[str, dict] = {}
        self.row_id: int | None = None
        self._built_here = False  # the active row was created by this session: add sections to it instead of a new version
        self.samples: dict[str, list[tuple[str, str]]] = {k: [] for k in KINDS}  # raw pages that worked, for validating repairs
        self.repairs: dict[str, int] = {k: 0 for k in KINDS}
        self.notes: list[str] = []
        self.validated: set[str] = set()  # sections written + validated (incl. decision (d)) in this session

    async def load(self) -> None:
        row = await self.store.active(self.domain)
        if row is not None:
            self.row_id, self.sections = row.id, {k: v for k, v in row.recipe.items() if v}

    def has(self, kind: str) -> bool:
        return kind in self.sections

    def section(self, kind: str):
        return SECTION_MODELS[kind].model_validate(self.sections[kind])

    def remember(self, kind: str, url: str, html: str) -> None:
        self.samples[kind] = [*self.samples[kind][-1:], (url, html)]

    # ------------------------------------------------------------ apply (plain parsing)
    def listing(self, html: str, url: str):
        return apply_listing(html, url, self.section("listing"))

    def novel(self, html: str, url: str):
        return apply_novel(html, url, self.section("novel"))

    def chapter(self, html: str, url: str):
        return apply_chapter(html, url, self.section("chapter"))

    # ------------------------------------------------------------ writing
    async def _author(self, kind: str, pages: list[tuple[str, str]], previous: dict | None, problem: Problem | None) -> BaseModel:
        if self.llm is None:
            raise RecipeUnavailable("ANTHROPIC_API_KEY is not configured: cannot write a recipe for " + self.domain)
        user = {"site": self.domain, "pages": [{"url": u, "html": clean_html(h)} for u, h in pages]}
        if previous is not None:
            user["previous_recipe"] = previous
        if problem:
            user["problem_to_fix"] = problem.feedback
        return await self.llm.parse(
            model=self.settings.recipe_model, system=AUTHOR_SYSTEM[kind], user=json.dumps(user, ensure_ascii=False),
            schema=SECTION_MODELS[kind], effort=self.settings.recipe_effort, max_tokens=8000, purpose=f"{self.tag}:recipe")

    async def _write(self, kind: str, pages: list[tuple[str, str]], previous: dict | None, problem: Problem | None, attempts: int,
                     check: list[tuple[str, str]] | None = None, min_chars: int = MIN_BODY_CHARS) -> dict:
        """Claude writes the section; it must validate on `check` (default: the pages it was shown), with the problem fed
        back on each retry."""
        last = problem
        for _ in range(attempts):
            sec = await self._author(kind, pages, previous, last)
            last = await self.validate(kind, sec, check or pages, min_chars)
            if last is None:
                self.validated.add(kind)
                return sec.model_dump()
            log.info("recipe %s/%s attempt rejected: %s", self.domain, kind, last.label)
            previous = sec.model_dump()
        raise RecipeBroken(f"could not write a working {kind} recipe for {self.domain}: {last.label if last else 'unknown'}")

    # ------------------------------------------------------------ validation
    async def validate(self, kind: str, sec: BaseModel, pages: list[tuple[str, str]], min_chars: int = MIN_BODY_CHARS) -> Problem | None:
        """None when the section works on the sample pages, else what is wrong (the label is storable, the feedback is
        for Claude only)."""
        if bad := selector_problems(kind, sec):
            return Problem("invalid CSS selector", "; ".join(bad))
        if kind == "listing":
            return await self._validate_listing(sec, pages[0])  # type: ignore[arg-type]
        if kind == "novel":
            return await self._validate_novel(sec, pages[0])  # type: ignore[arg-type]
        return await self._validate_chapter(sec, pages, min_chars)  # type: ignore[arg-type]

    async def _roles_problem(self, links: list[Link], expect: str, found_on: str) -> Problem | None:
        sample = _evenly(links, ROLE_SAMPLE)
        roles = await self.decider.link_roles([(link.text, link.url) for link in sample], found_on)
        if roles is None:
            return None
        ok = sum(1 for r in roles if r.choice == expect)
        if ok / len(sample) < ROLE_PASS:
            bad = [f"{link.text!r} -> {r.choice}" for link, r in zip(sample, roles, strict=True) if r.choice != expect]
            return Problem(f"links extracted as {expect} links were judged otherwise", f"links extracted as {expect} links are not: {'; '.join(bad)}")
        return None

    async def _validate_listing(self, sec: ListingSection, page: tuple[str, str]) -> Problem | None:
        url, html = page
        res = apply_listing(html, url, sec)
        if len(res.novel_links) < 2:
            return Problem(f"novel_links matched {len(res.novel_links)} links; a listing page has many")
        if p := await self._roles_problem(res.novel_links, "novel", url):
            return p
        if res.next_page:
            roles = await self.decider.link_roles([("next", res.next_page)], url)
            if roles and roles[0].choice != "next_page":
                return Problem("next_page link was judged not a next-page link", f"next_page selects {res.next_page}, judged {roles[0].choice}, not a next-page link")
        return None

    async def _validate_novel(self, sec: NovelSection, page: tuple[str, str]) -> Problem | None:
        url, html = page
        res = apply_novel(html, url, sec)
        if not res.title:
            return Problem("title selector matched nothing")
        links, found_on = res.chapter_links, url
        if not links and res.toc_url:
            toc = await self.fetch(res.toc_url)
            links = apply_novel(toc, getattr(toc, "url", res.toc_url), sec).chapter_links if toc else []
            found_on = res.toc_url
        if not links:
            return Problem("chapter_links matched nothing (and no toc_link led to a page where they match)")
        if p := await self._roles_problem(links, "chapter", found_on):
            return p
        return await self.novel_quality_problem(res)

    async def _validate_chapter(self, sec: ChapterSection, pages: list[tuple[str, str]], min_chars: int) -> Problem | None:
        best = None
        for url, html in pages:
            r = apply_chapter(html, url, sec)
            if best is None or len(r.text) > len(best.text):
                best = r
        if best is None or len(best.text) < min_chars:
            return Problem(f"body selector extracted {len(best.text) if best else 0} characters from the chapter page(s)")
        return await self.quality_problem("chapter", best.title, best.text)

    async def novel_quality_problem(self, res: NovelResult) -> Problem | None:
        q = await self.decider.extraction_quality("novel", {"title": res.title, "authors": res.authors, "synopsis": (res.synopsis or "")[:600],
                                                           "tags": res.tags[:12], "genres": res.genres[:6], "status": res.status})
        if q and q.choice != "clean":
            return Problem(f"novel metadata extraction judged {q.choice}",
                           f"novel metadata extraction judged {q.choice}: title={res.title!r} authors={res.authors} synopsis={(res.synopsis or '')[:200]!r}")
        return None

    async def quality_problem(self, kind: str, title: str | None, text: str) -> Problem | None:
        """Decision (d) on an extracted chapter. The excerpt of the text is feedback for Claude only."""
        q = await self.decider.extraction_quality(kind, {"title": title, "text_head": text[:1500], "text_tail": text[-600:], "length": len(text)})
        if q and q.choice != "clean":
            return Problem(f"chapter extraction judged {q.choice} ({len(text)} characters)",
                           f"chapter extraction judged {q.choice}. Start of extracted text: {text[:300]!r} ... end: {text[-200:]!r}")
        return None

    # ------------------------------------------------------------ building and saving
    async def ensure(self, kind: str, pages: list[tuple[str, str]]) -> None:
        """Make sure the recipe has this section, writing it from the sample pages if the domain lacks it."""
        if self.has(kind):
            return
        self.sections[kind] = await self._write(kind, pages, None, None, AUTHOR_ATTEMPTS)
        self.notes.append(f"wrote {kind}")
        await self._save()

    async def _save(self) -> None:
        recipe = {k: self.sections.get(k) for k in KINDS}
        notes = f"{', '.join(self.notes)} ({self.settings.recipe_model})"
        if self.row_id is not None and self._built_here:
            await self.store.update(self.row_id, recipe, notes)
        else:
            row = await self.store.create(self.domain, recipe, notes, "superseded")
            self.row_id, self._built_here = row.id, True

    async def repair(self, kind: str, url: str, html: str, problem: Problem | str, keep_on_failure: bool = False) -> None:
        """The `kind` section failed on this page. Older versions first (newest first): the first whose `kind` section
        works on the failing page itself becomes a NEW version (only that section is taken from the old row, the other
        sections stay as they are now). Otherwise Claude writes version n+1 given the failing page (and earlier good
        samples), and it must work on the failing page. Raises RecipeBroken when nothing works; the active version is
        then marked broken unless `keep_on_failure` (quality-only complaints must not take a working recipe away)."""
        problem = Problem.of(problem)
        self.repairs[kind] += 1
        failing = [(url, html)]
        pages = [*failing, *self.samples[kind]]
        min_chars = MIN_REPAIR_CHARS
        current = self.sections.get(kind)
        for row in await self.store.versions(self.domain):
            old = row.recipe.get(kind)
            if row.id == self.row_id or not old or old == current:
                continue
            if await self.validate(kind, SECTION_MODELS[kind].model_validate(old), failing, min_chars) is None:
                self.sections[kind] = old
                new = await self.store.create(self.domain, {k: self.sections.get(k) for k in KINDS}, f"restored {kind} from version {row.version}", "broken")
                self.row_id, self._built_here = new.id, True
                log.info("recipe %s: restored %s section of version %s", self.domain, kind, row.version)
                return
        try:
            written = await self._write(kind, pages, current, problem, REPAIR_ATTEMPTS, check=failing, min_chars=min_chars)
        except RecipeBroken:
            if self.row_id is not None and not keep_on_failure:
                await self.store.mark_broken(self.row_id)
            raise
        self.sections[kind] = written
        row = await self.store.create(self.domain, {k: self.sections.get(k) for k in KINDS},
                                      f"repaired {kind}: {problem.label[:300]} ({self.settings.recipe_model})", "broken")
        self.row_id, self._built_here = row.id, True
