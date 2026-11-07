"""Site recipes: Claude-written CSS-selector descriptions of one site (listing / novel page / chapter page), and the
plain-parsing code that applies them. Applying a recipe never calls an AI. Recipes are data (JSON), versioned per domain
in `site_recipes`; see builder.py for how they are written, validated and repaired."""
import re
from dataclasses import dataclass, field
from typing import Literal
from urllib.parse import urldefrag, urljoin, urlsplit

import soupsieve
from bs4 import BeautifulSoup, Comment, NavigableString, Tag
from pydantic import BaseModel
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..models import SiteRecipe
from ..sources.htmlutil import block_text, soup, text_of

SectionKind = Literal["listing", "novel", "chapter"]
KINDS: tuple[str, ...] = ("listing", "novel", "chapter")


# ---------------------------------------------------------------- recipe sections (also the structured-output schemas)
# A selector is a CSS selector, optionally suffixed with "@attr" to read an attribute instead of the text,
# e.g. "div.cover img@src". Several matches are allowed where a list makes sense (authors, tags, links).
class ListingSection(BaseModel):
    novel_links: str  # <a> elements linking to each novel's page
    next_page: str | None = None  # <a> to the next listing page


class NovelSection(BaseModel):
    title: str
    authors: str | None = None
    synopsis: str | None = None
    tags: str | None = None
    genres: str | None = None
    cover: str | None = None  # img@src
    status: str | None = None  # element whose text says ongoing/completed/...
    chapter_links: str  # <a> elements, one per chapter
    chapter_list_next: str | None = None  # <a> to the next page of the chapter list
    toc_link: str | None = None  # <a> to a separate table-of-contents page (chapter_links then apply on that page)
    chapter_order: Literal["asc", "desc", "auto"] = "auto"  # order of chapter_links in the document


class ChapterSection(BaseModel):
    title: str | None = None
    body: str  # the prose container(s)
    drop: list[str] = []  # junk inside the body to remove (ads, share bars, "next chapter" buttons, author-note boxes...)
    novel_link: str | None = None  # <a> back to the novel's page or table of contents (breadcrumb); lets a pasted chapter URL find its novel


SECTION_MODELS: dict[str, type[BaseModel]] = {"listing": ListingSection, "novel": NovelSection, "chapter": ChapterSection}


def parse_sel(spec: str) -> tuple[str, str | None]:
    css, sep, attr = spec.strip().rpartition("@")
    if sep and css and re.fullmatch(r"[A-Za-z_:][\w:.-]*", attr):
        return css.strip(), attr
    return spec.strip(), None


def selector_problems(kind: str, section: BaseModel) -> list[str]:
    """Selectors that do not compile (Claude output is never trusted to be valid CSS)."""
    out = []
    for name, value in section.model_dump().items():
        specs = value if isinstance(value, list) else [value]
        for spec in specs:
            if isinstance(spec, str) and spec.strip():
                try:
                    soupsieve.compile(parse_sel(spec)[0])
                except Exception as e:
                    out.append(f"{kind}.{name}: invalid CSS selector {spec!r} ({type(e).__name__})")
    return out


# ---------------------------------------------------------------- extraction
@dataclass
class Link:
    url: str
    text: str = ""


@dataclass
class ListingResult:
    novel_links: list[Link]
    next_page: str | None


@dataclass
class NovelResult:
    title: str
    authors: list[str] = field(default_factory=list)
    synopsis: str | None = None
    tags: list[str] = field(default_factory=list)
    genres: list[str] = field(default_factory=list)
    cover_url: str | None = None
    status: str | None = None
    chapter_links: list[Link] = field(default_factory=list)
    next_toc: str | None = None  # next page of the chapter list (pagination)
    toc_url: str | None = None  # separate TOC page


@dataclass
class ChapterResult:
    title: str | None
    text: str
    empty_body: bool = False  # the body selector matched element(s) that held no text at all before any drop/cleanup (e.g. an illustrations-only page)


def clean_url(base: str, href: str | None) -> str | None:
    if not href:
        return None
    u = urldefrag(urljoin(base, href.strip()))[0]
    return u if urlsplit(u).scheme in ("http", "https") and urlsplit(u).hostname else None


def effective_base(doc: Tag, url: str) -> str:
    """Base for resolving links: a <base href> if the page has one, else the page URL (callers pass the final URL after redirects)."""
    b = doc.find("base", href=True)
    return urljoin(url, b["href"]) if b is not None else url


def _select(doc: Tag, spec: str | None) -> list[Tag]:
    if not spec or not spec.strip():
        return []
    try:
        return list(doc.select(parse_sel(spec)[0]))
    except Exception:  # an invalid selector extracts nothing; recipe validation reports it
        return []


def _texts(doc: Tag, spec: str | None, base: str) -> list[str]:
    attr = parse_sel(spec)[1] if spec else None
    out: list[str] = []
    for el in _select(doc, spec):
        v = el.get(attr) if attr else text_of(el)
        if isinstance(v, list):
            v = " ".join(v)
        if attr in ("href", "src", "data-src"):
            v = clean_url(base, v)
        if v and v.strip() and v.strip() not in out:
            out.append(v.strip())
    return out


def _links(doc: Tag, spec: str | None, base: str) -> list[Link]:
    out: list[Link] = []
    seen: set[str] = set()
    for el in _select(doc, spec):
        a = el if el.name == "a" else el.find("a")
        u = clean_url(base, a.get("href") if a else None)
        if u and u not in seen:
            seen.add(u)
            out.append(Link(u, text_of(a)[:200]))
    return out


def _first_link(doc: Tag, spec: str | None, base: str) -> str | None:
    links = _links(doc, spec, base)
    return links[0].url if links else None


def normalize_status(s: str | None) -> str | None:
    t = (s or "").lower()
    if re.search(r"complete|finished|ended|完結|完结|완결", t):
        return "completed"
    if re.search(r"hiatus|on break|paused|휴재", t):
        return "hiatus"
    if re.search(r"dropped|cancel|abandon|discontinu", t):
        return "cancelled"
    if re.search(r"ongoing|on-?going|publishing|updating|serializ|连载|連載|연재", t):
        return "ongoing"
    return None


def apply_listing(html: str, url: str, sec: ListingSection) -> ListingResult:
    doc = soup(html)
    url = effective_base(doc, url)
    return ListingResult(_links(doc, sec.novel_links, url), _first_link(doc, sec.next_page, url))


def apply_novel(html: str, url: str, sec: NovelSection) -> NovelResult:
    doc = soup(html)
    url = effective_base(doc, url)
    title = next(iter(_texts(doc, sec.title, url)), "")
    syn_el = next(iter(_select(doc, sec.synopsis)), None)
    if sec.synopsis and parse_sel(sec.synopsis)[1]:
        synopsis = next(iter(_texts(doc, sec.synopsis, url)), None)
    else:
        synopsis = block_text(syn_el)  # keeps paragraph breaks
    return NovelResult(
        title=title, authors=_texts(doc, sec.authors, url), synopsis=synopsis, tags=_texts(doc, sec.tags, url),
        genres=_texts(doc, sec.genres, url), cover_url=next(iter(_texts(doc, sec.cover, url)), None),
        status=normalize_status(" ".join(_texts(doc, sec.status, url))),
        chapter_links=_links(doc, sec.chapter_links, url), next_toc=_first_link(doc, sec.chapter_list_next, url),
        toc_url=_first_link(doc, sec.toc_link, url),
    )


def apply_chapter(html: str, url: str, sec: ChapterSection) -> ChapterResult:
    doc = soup(html)
    url = effective_base(doc, url)
    for el in doc(["script", "style", "noscript", "template"]):
        el.decompose()
    title = next(iter(_texts(doc, sec.title, url)), None)
    def parts_of() -> tuple[list[Tag], list[str]]:
        matches = _select(doc, sec.body)
        ids = {id(m) for m in matches}
        top = [m for m in matches if not any(id(p) in ids for p in m.parents)]  # nested matches are already covered by their parent
        return top, [t for t in (block_text(m) for m in top) if t]

    top_before, parts_before = parts_of()
    for spec in sec.drop:
        for el in _select(doc, spec):
            el.decompose()
    _, parts = parts_of()
    # empty_body only when the container was there and empty BEFORE the recipe's drop selectors ran: text that the recipe
    # removed (or a container it dropped) means the recipe is wrong, not the page.
    return ChapterResult(title, "\n\n".join(parts), empty_body=bool(top_before) and not parts_before)


def chapter_novel_links(html: str, url: str, sec: ChapterSection | None) -> list[str]:
    """Where a chapter page points back to its novel: the recipe's `novel_link` selector if there is one, else same-host
    links whose path is an ancestor of the chapter's path (deepest first) or whose text looks like a table of contents."""
    doc = soup(html)
    base = effective_base(doc, url)
    out: list[str] = [link.url for link in _links(doc, sec.novel_link, base)] if sec and sec.novel_link else []
    here = urlsplit(url)
    ancestors, hinted = [], []
    for a in doc.find_all("a", href=True):
        u = clean_url(base, a["href"])
        if not u or u == url:
            continue
        p = urlsplit(u)
        if p.hostname != here.hostname:
            continue
        path = p.path.rstrip("/")
        if path and (here.path + "/").startswith(path + "/") and not p.query:
            ancestors.append((len(path), u))
        elif re.search(r"table of contents|all chapters|chapter list|\btoc\b|novel info|back to novel", text_of(a), re.I):
            hinted.append(u)
    out += [u for _, u in sorted(ancestors, reverse=True)] + hinted
    return list(dict.fromkeys(out))[:5]


def order_chapters(links: list[Link], order: str) -> list[Link]:
    """Reading order (oldest first). 'auto' reverses when the chapter numbers in the link texts/urls descend."""
    if order == "desc":
        return links[::-1]
    if order == "asc" or len(links) < 2:
        return links

    def num(link: Link) -> int | None:
        m = re.findall(r"\d+", link.text) or re.findall(r"\d+", urlsplit(link.url).path)
        return int(m[-1]) if m else None

    a, b = num(links[0]), num(links[-1])
    return links[::-1] if a is not None and b is not None and a > b else links


# ---------------------------------------------------------------- cleaning pages for Claude / Jev
JUNK_TAGS = ["script", "style", "noscript", "svg", "iframe", "link", "meta", "template", "head", "form", "button", "input", "select", "canvas", "video", "audio"]
KEEP_ATTRS = {"class", "id", "href", "src", "itemprop", "rel", "property", "name", "role", "data-src"}


def clean_html(html: str, max_chars: int = 14_000, text_cap: int = 160, run_cap: int = 5) -> str:
    """Compact page skeleton for recipe writing: no scripts/styles/svg, few attributes, long text and long runs of
    similar siblings (list items, paragraphs) truncated. Keeps the structure that selectors need, not the content."""
    doc = BeautifulSoup(html, "html.parser")
    title = doc.title.get_text(" ", strip=True)[:200] if doc.title else ""
    for c in doc.find_all(string=lambda s: isinstance(s, Comment)):
        c.extract()
    for t in doc(JUNK_TAGS):
        t.decompose()
    for el in doc.find_all(True):
        el.attrs = {k: (" ".join(v) if isinstance(v, list) else v)[:120] for k, v in el.attrs.items() if k in KEEP_ATTRS}
    for s in doc.find_all(string=True):
        if isinstance(s, NavigableString) and len(s) > text_cap:
            s.replace_with(s[:text_cap].rstrip() + " ...")
    for parent in doc.find_all(True):
        run_key, run = None, []
        for child in list(parent.children):
            if not isinstance(child, Tag):
                continue
            key = (child.name, child.get("class"))
            if key == run_key:
                run.append(child)
            else:
                _trim_run(run, run_cap)
                run_key, run = key, [child]
        _trim_run(run, run_cap)
    body = doc.body or doc
    out = re.sub(r">\s+<", "><", re.sub(r"[ \t\r\n]+", " ", str(body)))
    return (f"<title>{title}</title>" if title else "") + out[:max_chars]


def _trim_run(run: list[Tag], cap: int) -> None:
    if len(run) <= cap:
        return
    run[cap].insert_before(Comment(f" {len(run) - cap} more similar <{run[0].name}> siblings omitted "))
    for el in run[cap:]:
        el.decompose()


def page_digest(html: str, url: str, max_links: int = 12) -> dict:
    """Small structural summary of a page for the page-type decision (never the full text)."""
    doc = soup(html)
    for el in doc(["script", "style", "noscript", "svg"]):
        el.decompose()
    links = [(text_of(a)[:80], clean_url(url, a.get("href"))) for a in doc.find_all("a", href=True)]
    return {
        "url": url, "title": (doc.title.get_text(" ", strip=True)[:150] if doc.title else ""),
        "headings": [text_of(h)[:100] for h in doc.find_all(["h1", "h2"])[:6]],
        "link_count": len(links), "paragraph_count": len(doc.find_all("p")),
        "text_length": len(doc.get_text(" ", strip=True)),
        "text_head": doc.get_text(" ", strip=True)[:500],
        "sample_links": [{"text": t, "url": u} for t, u in links[:max_links] if u],
    }


# ---------------------------------------------------------------- storage
class RecipeStore:
    """Versions of a domain's recipe. Exactly one is `active`; `superseded` ones are older working versions (a section
    was added), `broken` ones failed on a page. Both older kinds are fallbacks when the active recipe breaks."""

    def __init__(self, maker: async_sessionmaker[AsyncSession]):
        self.maker = maker

    async def active(self, domain: str) -> SiteRecipe | None:
        async with self.maker() as s:
            return await s.scalar(select(SiteRecipe).where(SiteRecipe.domain == domain, SiteRecipe.status == "active"))

    async def versions(self, domain: str) -> list[SiteRecipe]:
        async with self.maker() as s:
            return list((await s.scalars(select(SiteRecipe).where(SiteRecipe.domain == domain).order_by(SiteRecipe.version.desc()))).all())

    async def create(self, domain: str, recipe: dict, notes: str, previous_status: str = "superseded") -> SiteRecipe:
        """New version n+1, active; the previously active version becomes `previous_status`. Two jobs racing on the same
        domain collide on (domain, version): the loser reloads the top version and retries."""
        for attempt in range(4):
            try:
                async with self.maker() as s:
                    top = await s.scalar(select(SiteRecipe.version).where(SiteRecipe.domain == domain).order_by(SiteRecipe.version.desc()).limit(1))
                    await s.execute(update(SiteRecipe).where(SiteRecipe.domain == domain, SiteRecipe.status == "active").values(status=previous_status))
                    row = SiteRecipe(domain=domain, version=(top or 0) + 1, recipe=recipe, status="active", notes=notes[:2000])
                    s.add(row)
                    await s.commit()
                    return row
            except IntegrityError:
                if attempt == 3:
                    raise
        raise AssertionError("unreachable")

    async def update(self, row_id: int, recipe: dict, notes: str) -> None:
        """Rewrite a version created earlier in the same build (sections added while bootstrapping a new domain)."""
        async with self.maker() as s:
            await s.execute(update(SiteRecipe).where(SiteRecipe.id == row_id).values(recipe=recipe, notes=notes[:2000]))
            await s.commit()

    async def mark_broken(self, row_id: int) -> None:
        async with self.maker() as s:
            await s.execute(update(SiteRecipe).where(SiteRecipe.id == row_id).values(status="broken"))
            await s.commit()
