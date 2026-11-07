"""A fake web-novel site (httpx.MockTransport) and fake Jev for the link-crawl tests. No real network."""
import json
import re

import httpx

from readerserver.ai.jev import JevClient
from readerserver.ai.spend import SpendTracker
from readerserver.config import Settings
from readerserver.crawl.http import PoliteClient
from readerserver.linkcrawl.recipes import ChapterSection, ListingSection, NovelSection

DOMAIN = "novels.example"
TITLES = {1: "Moonlit Bakery", 2: "Sword Saint Rises", 3: "Cloud Garden Keeper", 4: "Iron Harbor Chronicle"}
AUTHORS = {1: "Ann Baker", 2: "Bo Swords", 3: "Cy Gardner", 4: "Di Harbor"}
THEMES = {1: "baker kneaded zephyrcrown dough", 2: "swordsman climbed tournament ladder", 3: "gardener tended cloud orchids",
          4: "dockworker repaired iron harbor cranes"}
CHAPTERS = {1: 5, 2: 3, 3: 3, 4: 3}
SENTINEL = "zephyrcrown"  # appears only in novel 1's chapter prose


def prose(n: int, k: int, edit: str = "") -> list[str]:
    return [f"In part {i} of chapter {k} the {THEMES[n]} while the evening wind carried distant voices over the quiet rooftops. {edit}".strip()
            for i in range(1, 7)]


class Site:
    """Serves listing / novel / chapter-list / chapter pages. `locked`, `block`, `layout` and `edits` change what it serves."""

    def __init__(self):
        self.requests: list[str] = []
        self.locked: set[tuple[int, int]] = {(1, 4)}
        self.block: set[str] = set()
        self.layout = "v1"  # v2 renames the chapter body container (#content -> #story): a recipe-breaking redesign
        self.edits: dict[tuple[int, int], str] = {}
        self.crumbs = True  # chapter pages link back to their novel
        self.paywall: set[tuple[int, int]] = set()  # chapter pages replaced by a lock page with no chapter container
        self.dup = False  # listing page 2 repeats novel 1
        self.missing: set[str] = set()  # paths that 404
        self.empty: set[tuple[int, int]] = set()  # chapter pages whose body container exists but holds nothing (illustrations-only)
        self.lists: dict[str, dict[int, list[int]]] = {"/list-a": {1: [1, 2]}, "/list-b": {1: [1, 2, 3], 2: [4]}, "/list-c": {1: [1, 2], 2: [3]}}  # extra listings
        self.toc2_empty = False  # novel 1's second chapter-list page shows no chapter links

    def count(self, path: str) -> int:
        return self.requests.count(path)

    def handle(self, req: httpx.Request) -> httpx.Response:
        path = req.url.path + (f"?{req.url.query.decode()}" if req.url.query else "")
        if req.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nAllow: /\n")
        self.requests.append(path)
        if path in self.block:
            return httpx.Response(403, text="Forbidden")
        if path in self.missing:
            return httpx.Response(404, text="gone")
        body = self.page(req.url.path, req.url.query.decode())
        return httpx.Response(200 if body else 404, text=body or "not found", headers={"content-type": "text/html; charset=utf-8"})

    def page(self, path: str, query: str) -> str | None:
        page = int(re.search(r"page=(\d+)", query).group(1)) if "page=" in query else 1
        if path == "/list":
            ids = {1: [1, 2, 3], 2: [4, 1] if self.dup else [4]}.get(page, [])
            items = "".join(f'<li><a href="/novel/{i}">{TITLES[i]}</a></li>' for i in ids)
            nxt = '<a class="next" href="/list?page=2">Next</a>' if page == 1 else ""
            return f'<html><head><title>Novels {page}</title><script>var x=1</script></head><body><nav><a href="/">Home</a><a href="/login">Login</a></nav><ul class="novels">{items}</ul>{nxt}</body></html>'
        if path in self.lists:
            pages = self.lists[path]
            items = "".join(f'<li><a href="/novel/{i}">{TITLES[i]}</a></li>' for i in pages.get(page, []))
            nxt = f'<a class="next" href="{path}?page={page + 1}">Next</a>' if page + 1 in pages else ""
            return f'<html><head><title>Novels {page}</title></head><body><nav><a href="/">Home</a></nav><ul class="novels">{items}</ul>{nxt}</body></html>'
        if m := re.fullmatch(r"/novel/(\d+)", path):
            n = int(m.group(1))
            if n not in TITLES:
                return None
            return (f'<html><head><title>{TITLES[n]}</title></head><body><nav><a href="/">Home</a></nav><h1 class="title">{TITLES[n]}</h1>'
                    f'<span class="author">{AUTHORS[n]}</span><div class="synopsis"><p>A story about {TITLES[n]} and the people around it.</p></div>'
                    f'<ul class="tags"><li>Cooking</li><li>Fantasy</li></ul><span class="status">Ongoing</span><img class="cover" src="/c/{n}.jpg">'
                    f'<a class="toc-link" href="/novel/{n}/chapters">All chapters</a></body></html>')
        if m := re.fullmatch(r"/novel/(\d+)/chapters", path):
            n = int(m.group(1))
            if n not in CHAPTERS:
                return None
            ks = list(range(1, CHAPTERS[n] + 1))
            if n == 1:  # the chapter list of novel 1 is paginated: 3 + 2
                ks = ks[:3] if page == 1 else ks[3:]
            if n == 1 and page == 2 and self.toc2_empty:
                ks = []
            items = "".join(f'<li><a href="/novel/{n}/chapter/{k}">Chapter {k}</a></li>' for k in ks)
            nxt = f'<a class="next" href="/novel/{n}/chapters?page=2">Next page</a>' if n == 1 and page == 1 and CHAPTERS[1] > 3 else ""
            return f'<html><body><a class="crumb" href="/novel/{n}">{TITLES[n]}</a><ul class="chapters">{items}</ul>{nxt}</body></html>'
        if m := re.fullmatch(r"/novel/(\d+)/chapter/(\d+)", path):
            n, k = int(m.group(1)), int(m.group(2))
            if n not in CHAPTERS or k > CHAPTERS[n]:
                return None
            if (n, k) in self.paywall:
                return '<html><body><a class="crumb" href="/novel/%d">x</a><h1>Chapter %d</h1><div class="lock">This chapter is locked. Subscribe to read.</div></body></html>' % (n, k)
            paras = ["This chapter is locked. Subscribe to read."] if (n, k) in self.locked else prose(n, k, self.edits.get((n, k), ""))
            empty = (n, k) in self.empty
            if empty:
                paras = []
            body = "".join(f"<p>{p}</p>" for p in paras)
            container = "content" if self.layout == "v1" else "story"
            crumb = f'<a class="crumb" href="/novel/{n}">{TITLES[n]}</a>' if self.crumbs else ""
            return (f'<html><head><title>Chapter {k}</title></head><body><div class="nav">{crumb}<a href="/novel/{n}/chapter/{k + 1}">Next chapter</a></div>'
                    f'<h1 class="ch-title">Chapter {k}</h1><div id="{container}">{body}'
                    + ("" if empty else '<p class="ads">Advertisement: buy now</p><p class="share">Share on social media</p>') + '</div></body></html>')
        if path == "/about":
            return "<html><head><title>About us</title></head><body><p>We are a small translation group. Contact us.</p></body></html>"
        return None

    def client(self, domain: str = DOMAIN) -> PoliteClient:
        return PoliteClient("TestAgent/1.0", min_delay=0.0, transport=httpx.MockTransport(self.handle), allowed_hosts={domain})


LISTING = ListingSection(novel_links="ul.novels li a", next_page="a.next")
NOVEL = NovelSection(title="h1.title", authors="span.author", synopsis="div.synopsis", tags="ul.tags li", cover="img.cover@src", status="span.status",
                     chapter_links="ul.chapters a", chapter_list_next="a.next", toc_link="a.toc-link", chapter_order="asc")
CHAPTER = ChapterSection(title="h1.ch-title", body="#content", drop=[".ads", ".share"])


def queue_recipes(llm, listing=True) -> None:
    if listing:
        llm.add("ListingSection", LISTING)
    llm.add("NovelSection", NOVEL)
    llm.add("ChapterSection", CHAPTER)


class FakeJev:
    """Jev over a mock transport; answers by simple rules on the question and its state. `calls` lists the question keys."""

    def __init__(self, maker, settings: Settings | None = None, cap: float = 5.0):
        self.calls: list[str] = []
        self.confidence, self.down = 0.95, False
        self.settings = settings or Settings(jev_api_key="k", jev_base_url="https://jev.test")
        self.client = JevClient(self.settings, SpendTracker(maker, cap), httpx.AsyncClient(transport=httpx.MockTransport(self.handler)))

    def handler(self, req: httpx.Request) -> httpx.Response:
        body = json.loads(req.content)
        key = next(iter(body["questions"]))
        state = body["state"]
        self.calls.append(key)
        if self.down:
            return httpx.Response(500, text="down")
        choice = getattr(self, f"answer_{key}")(state)
        return httpx.Response(200, json={"model": "jev-latest", "answers": {key: {"type": "choice", "choice": choice, "confidence": self.confidence,
                                                                                  "probabilities": {choice: self.confidence}}}, "usage": {"input_tokens": 10, "output_tokens": 1}})

    def answer_page_type(self, st: dict) -> str:
        u = st["url"]
        for frag, kind in (("/list", "novel_listing"), ("/chapter/", "chapter"), ("/chapters", "chapter_list"), ("/novel/", "novel_page")):
            if frag in u:
                return kind
        return "other"

    def answer_role(self, st: dict) -> str:
        u = st["url"]
        if re.search(r"/novel/\d+$", u):
            return "novel"
        if "/chapter/" in u:
            return "chapter"
        return "next_page" if "page=" in u else "skip"

    def answer_chapter(self, st: dict) -> str:
        t = st["text_head"].lower()
        if "locked" in t:
            return "locked_or_placeholder"
        if "author's note" in t:
            return "author_note_or_announcement"
        return "story"

    def answer_quality(self, st: dict) -> str:
        blob = json.dumps(st["extracted"])
        return "has_junk" if ("Advertisement" in blob or "Share on" in blob) else "clean"
