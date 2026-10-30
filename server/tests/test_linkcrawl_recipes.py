"""Recipe parsing on HTML fixtures, recipe versioning / repair, and the Jev/Claude decisions of a link crawl."""
import json

import pytest

from readerserver.ai.spend import BudgetExceeded
from readerserver.config import Settings
from readerserver.linkcrawl.builder import RecipeBroken, RecipeSession
from readerserver.linkcrawl.chapters import embed_chapter_text
from readerserver.linkcrawl.crawler import ChapterGate
from readerserver.linkcrawl.decisions import Decider, LinkRolesOut, PageTypeOut, chapter_heuristic, chapter_suspicion
from readerserver.linkcrawl.recipes import (
    ChapterSection,
    Link,
    ListingSection,
    NovelSection,
    RecipeStore,
    apply_chapter,
    apply_listing,
    apply_novel,
    clean_html,
    normalize_status,
    order_chapters,
    parse_sel,
    selector_problems,
)
from readerserver.models import SiteRecipe

from .linksite import CHAPTER, DOMAIN, LISTING, NOVEL, FakeJev, Site, prose

BASE = f"https://{DOMAIN}"


# ---------------------------------------------------------------- parsing (plain selectors, no AI)
def test_listing_novel_links_and_pagination():
    site = Site()
    p1 = apply_listing(site.page("/list", ""), f"{BASE}/list", LISTING)
    assert [(link.url, link.text) for link in p1.novel_links] == [(f"{BASE}/novel/{i}", t) for i, t in ((1, "Moonlit Bakery"), (2, "Sword Saint Rises"), (3, "Cloud Garden Keeper"))]
    assert p1.next_page == f"{BASE}/list?page=2"
    p2 = apply_listing(site.page("/list", "page=2"), f"{BASE}/list?page=2", LISTING)
    assert [link.url for link in p2.novel_links] == [f"{BASE}/novel/4"] and p2.next_page is None


def test_novel_page_metadata_and_separate_toc_page():
    site = Site()
    n = apply_novel(site.page("/novel/1", ""), f"{BASE}/novel/1", NOVEL)
    assert (n.title, n.authors, n.tags, n.status) == ("Moonlit Bakery", ["Ann Baker"], ["Cooking", "Fantasy"], "ongoing")
    assert n.synopsis == "A story about Moonlit Bakery and the people around it." and n.cover_url == f"{BASE}/c/1.jpg"
    assert n.chapter_links == [] and n.toc_url == f"{BASE}/novel/1/chapters"


def test_chapter_list_pagination():
    site = Site()
    t1 = apply_novel(site.page("/novel/1/chapters", ""), f"{BASE}/novel/1/chapters", NOVEL)
    assert [link.text for link in t1.chapter_links] == ["Chapter 1", "Chapter 2", "Chapter 3"] and t1.next_toc == f"{BASE}/novel/1/chapters?page=2"
    t2 = apply_novel(site.page("/novel/1/chapters", "page=2"), f"{BASE}/novel/1/chapters?page=2", NOVEL)
    assert [link.url for link in t2.chapter_links] == [f"{BASE}/novel/1/chapter/4", f"{BASE}/novel/1/chapter/5"] and t2.next_toc is None


def test_chapter_page_drops_junk_and_keeps_paragraphs():
    site = Site()
    c = apply_chapter(site.page("/novel/1/chapter/2", ""), f"{BASE}/novel/1/chapter/2", CHAPTER)
    assert c.title == "Chapter 2"
    assert "Advertisement" not in c.text and "Share on" not in c.text and "Next chapter" not in c.text
    assert c.text.split("\n\n") == prose(1, 2)
    # nested body matches are not duplicated; a missing container extracts nothing (that is what triggers repair)
    html = "<div class='a'><div class='a'><p>Only once please, this paragraph.</p></div></div>"
    assert apply_chapter(html, BASE, ChapterSection(body="div.a")).text == "Only once please, this paragraph."
    assert apply_chapter(html, BASE, ChapterSection(body="#nope")).text == ""


def test_selector_helpers_ordering_status_and_validation():
    assert parse_sel("div.cover img@src") == ("div.cover img", "src") and parse_sel("a[href^='x']") == ("a[href^='x']", None)
    assert selector_problems("chapter", ChapterSection(body="div[", drop=[".ok"])) and not selector_problems("chapter", CHAPTER)
    links = [Link(f"{BASE}/c/{n}", f"Chapter {n}") for n in (3, 2, 1)]
    assert [x.text for x in order_chapters(links, "auto")] == ["Chapter 1", "Chapter 2", "Chapter 3"]  # newest-first list detected
    assert [x.text for x in order_chapters(links, "asc")] == ["Chapter 3", "Chapter 2", "Chapter 1"]
    assert [x.text for x in order_chapters(links, "desc")] == ["Chapter 1", "Chapter 2", "Chapter 3"]
    assert [normalize_status(s) for s in ("Completed", "OnGoing", "On Hiatus", "Dropped", "???")] == ["completed", "ongoing", "hiatus", "cancelled", None]


def test_clean_html_strips_noise_and_truncates_runs():
    items = "".join(f"<li class='n'><a href='/novel/{i}' onclick='x()' style='color:red'>Novel {i}</a></li>" for i in range(40))
    html = f"<html><head><title>T</title><script>evil()</script><style>a{{}}</style></head><body><ul>{items}</ul><svg><path/></svg><p>{'word ' * 200}</p></body></html>"
    out = clean_html(html)
    assert "evil" not in out and "<svg" not in out and "onclick" not in out and "style=" not in out and "<title>T</title>" in out
    assert out.count("<li class") == 5 and "35 more similar" in out and "word " * 60 not in out
    assert len(clean_html("<p>" + "x" * 100_000 + "</p>", max_chars=500)) < 700


# ---------------------------------------------------------------- recipe versions: fallback, repair, validation
def session_for(maker, llm, decider=None, fetch=None):
    async def no_fetch(url):
        raise AssertionError("unexpected fetch " + url)

    return RecipeSession(DOMAIN, RecipeStore(maker), Settings(), llm, decider or Decider(Settings(), None, None), fetch or no_fetch, "lc1")


def recipe(chapter: ChapterSection) -> dict:
    return {"listing": None, "novel": NOVEL.model_dump(), "chapter": chapter.model_dump()}


def chapter_page(layout="v1") -> tuple[str, str]:
    site = Site()
    site.layout = layout
    return f"{BASE}/novel/1/chapter/1", site.page("/novel/1/chapter/1", "")


async def statuses(maker) -> dict[int, str]:
    return {r.version: r.status for r in await RecipeStore(maker).versions(DOMAIN)}


async def test_repair_tries_older_versions_before_asking_claude(maker, fake_llm):
    store = RecipeStore(maker)
    await store.create(DOMAIN, recipe(CHAPTER), "v1")
    await store.create(DOMAIN, recipe(ChapterSection(body="#renamed-container")), "v2 (a bad rewrite)")
    s = session_for(maker, fake_llm)
    await s.load()
    url, html = chapter_page()
    assert s.chapter(html, url).text == ""  # the active v2 extracts nothing
    await s.repair("chapter", url, html, "body selector matched nothing")
    assert fake_llm.calls == []  # no Claude call: v1 still works on this page
    # the old chapter section is restored as a NEW version (only that section is taken from the old row)
    assert await statuses(maker) == {1: "superseded", 2: "broken", 3: "active"} and s.chapter(html, url).text
    row = (await store.versions(DOMAIN))[0]
    assert row.version == 3 and row.notes.startswith("restored chapter from version 1") and row.recipe["novel"] == NOVEL.model_dump()


async def test_repair_asks_claude_for_the_next_version_when_older_ones_fail(maker, fake_llm):
    store = RecipeStore(maker)
    await store.create(DOMAIN, recipe(CHAPTER), "v1")
    s = session_for(maker, fake_llm)
    await s.load()
    url, html = chapter_page("v2")  # the site renamed #content -> #story
    fake_llm.add("ChapterSection", ChapterSection(title="h1.ch-title", body="#story", drop=[".ads", ".share"]))
    await s.repair("chapter", url, html, "body selector matched nothing")
    assert await statuses(maker) == {1: "broken", 2: "active"}
    row = (await store.versions(DOMAIN))[0]
    assert row.version == 2 and row.recipe["chapter"]["body"] == "#story" and "repaired chapter" in row.notes
    sent = json.loads(fake_llm.calls[0]["user"])
    assert sent["problem_to_fix"] == "body selector matched nothing" and sent["previous_recipe"]["body"] == "#content"
    assert sent["pages"][0]["url"] == url and "story" in sent["pages"][0]["html"]
    assert "Advertisement" not in s.chapter(html, url).text


async def test_new_recipe_is_validated_on_sample_pages_before_activation(maker, fake_llm):
    jev = FakeJev(maker)
    s = session_for(maker, fake_llm, Decider(Settings(jev_api_key="k"), jev.client, None))
    pages = [chapter_page(), (f"{BASE}/novel/1/chapter/2", Site().page("/novel/1/chapter/2", ""))]
    fake_llm.add("ChapterSection", ChapterSection(body="#content"))  # no drop list: the ad and share paragraphs stay in -> Jev says has_junk
    fake_llm.add("ChapterSection", ChapterSection(body="div[", drop=[]))  # not even valid CSS
    fake_llm.add("ChapterSection", CHAPTER)
    await s.ensure("chapter", pages)
    assert len(fake_llm.calls) == 3 and "problem_to_fix" not in fake_llm.calls[0]["user"]
    assert "has_junk" in json.loads(fake_llm.calls[1]["user"])["problem_to_fix"] and "invalid CSS" in json.loads(fake_llm.calls[2]["user"])["problem_to_fix"]
    rows = await RecipeStore(maker).versions(DOMAIN)
    assert [(r.version, r.status, r.recipe["chapter"]["body"]) for r in rows] == [(1, "active", "#content")]  # only the validated one was saved


async def test_recipe_that_never_validates_is_not_saved_and_repair_failure_marks_it_broken(maker, fake_llm):
    s = session_for(maker, fake_llm)
    fake_llm.add("ChapterSection", ChapterSection(body="#nothing-here"))
    with pytest.raises(RecipeBroken):
        await s.ensure("chapter", [chapter_page()])
    assert await RecipeStore(maker).versions(DOMAIN) == []
    store = RecipeStore(maker)
    await store.create(DOMAIN, recipe(CHAPTER), "v1")
    s2 = session_for(maker, fake_llm)
    await s2.load()
    url, html = chapter_page("v2")
    with pytest.raises(RecipeBroken):
        await s2.repair("chapter", url, html, "empty")  # Claude keeps proposing #nothing-here
    assert await statuses(maker) == {1: "broken"} and await store.active(DOMAIN) is None  # next job rebuilds from scratch


async def test_sections_added_later_become_a_new_superseding_version(maker, fake_llm):
    store = RecipeStore(maker)
    s = session_for(maker, fake_llm)
    fake_llm.add("ChapterSection", CHAPTER)
    await s.ensure("chapter", [chapter_page()])
    s2 = session_for(maker, fake_llm)  # a later job on the same domain needs the listing section too
    await s2.load()
    assert s2.has("chapter") and not s2.has("listing")
    fake_llm.add("ListingSection", LISTING)
    await s2.ensure("listing", [(f"{BASE}/list", Site().page("/list", ""))])
    assert await statuses(maker) == {1: "superseded", 2: "active"}
    active = await store.active(DOMAIN)
    assert active.recipe["chapter"]["body"] == "#content" and active.recipe["listing"]["novel_links"] == "ul.novels li a"


# ---------------------------------------------------------------- decisions: Jev first, Claude fallback
DIGEST = {"url": f"{BASE}/novel/3", "title": "x"}


async def test_decider_uses_jev_when_confident_and_tags_spend_with_the_job(maker, fake_llm):
    jev = FakeJev(maker)
    d = Decider(Settings(jev_api_key="k"), jev.client, fake_llm, tag="lc7")
    out = await d.page_type(DIGEST)
    assert (out.choice, out.method, out.probability) == ("novel_page", "jev", 0.95) and fake_llm.calls == []
    from readerserver.ai.spend import SpendTracker

    assert [(b["purpose"], b["provider"]) for b in (await SpendTracker(maker, 5).summary())["breakdown"]] == [("lc7:page_type", "jev")]


async def test_decider_falls_back_to_claude_when_jev_is_unsure_down_or_missing(maker, fake_llm):
    fake_llm.add("PageTypeOut", PageTypeOut(choice="other", confidence=0.8, reason="r"))
    jev = FakeJev(maker)
    jev.confidence = 0.4
    out = await Decider(Settings(jev_api_key="k"), jev.client, fake_llm).page_type(DIGEST)
    assert (out.choice, out.method) == ("other", "claude") and fake_llm.calls[0]["model"] == "claude-haiku-5-5"
    jev.confidence, jev.down = 0.95, True
    assert (await Decider(Settings(jev_api_key="k"), jev.client, fake_llm).page_type(DIGEST)).method == "claude"
    assert (await Decider(Settings(), None, fake_llm).page_type(DIGEST)).method == "claude"
    jev.down = False
    unsure_no_llm = FakeJev(maker)
    unsure_no_llm.confidence = 0.4
    out = await Decider(Settings(jev_api_key="k"), unsure_no_llm.client, None).page_type(DIGEST)
    assert out.method == "jev" and out.probability == 0.4  # nothing better available: keep Jev's answer
    assert await Decider(Settings(), None, None).page_type(DIGEST) is None  # callers use heuristics
    assert not Decider(Settings(), None, None).available()


async def test_decider_budget_exhaustion_propagates(maker):
    jev = FakeJev(maker, cap=0.0)
    with pytest.raises(BudgetExceeded):
        await Decider(Settings(jev_api_key="k"), jev.client, None).chapter_content("t", "text", {})


async def test_link_roles_unsure_links_go_to_claude_in_one_batch(maker, fake_llm):
    jev = FakeJev(maker)
    links = [("Moonlit", f"{BASE}/novel/1"), ("Ch 1", f"{BASE}/novel/1/chapter/1"), ("Next", f"{BASE}/list?page=2")]
    out = await Decider(Settings(jev_api_key="k"), jev.client, fake_llm).link_roles(links, f"{BASE}/list")
    assert [d.choice for d in out] == ["novel", "chapter", "next_page"] and fake_llm.calls == [] and jev.calls == ["role"] * 3
    jev.confidence = 0.3
    fake_llm.add("LinkRolesOut", LinkRolesOut(roles=["novel", "skip", "next_page"]))
    out = await Decider(Settings(jev_api_key="k"), jev.client, fake_llm).link_roles(links, f"{BASE}/list")
    assert [(d.choice, d.method) for d in out] == [("novel", "claude"), ("skip", "claude"), ("next_page", "claude")]
    assert [c["schema"] for c in fake_llm.calls] == ["LinkRolesOut"]  # one batch call, not three
    assert await Decider(Settings(), None, None).link_roles(links, "x") is None


def test_chapter_heuristics():
    normal = "The baker kneaded the dough for a long while. " * 30
    assert chapter_suspicion(normal, None) == [] and chapter_suspicion(normal, 1350) == []
    assert "very_short" in chapter_suspicion("tiny", None)
    assert "lock_words" in chapter_suspicion("Please log in to read this chapter. " + normal[:200], None)
    assert chapter_suspicion(normal * 5, 1350) == ["length_deviation"] and chapter_suspicion(normal[:200] + "x" * 220, 1350) == ["length_deviation"]
    assert chapter_heuristic("This chapter is locked. Subscribe to read.", ["lock_words", "very_short"]) == "locked_or_placeholder"
    assert chapter_heuristic("hi", ["very_short"]) == "junk" and chapter_heuristic(normal, []) == "story"


async def test_chapter_gate_spends_jev_only_on_first_chapters_and_suspicious_ones(maker):
    jev = FakeJev(maker)
    gate = ChapterGate(Decider(Settings(jev_api_key="k"), jev.client, None), first_n=2)
    normal = "The baker kneaded the dough for a long while. " * 30
    verdicts = [await gate(normal) for _ in range(6)]
    assert verdicts == [True] * 6 and jev.calls == ["chapter"] * 2  # first N only; the rest are heuristics-only
    assert await gate("This chapter is locked. Subscribe to read.") is False and len(jev.calls) == 3  # suspicious: judged, rejected
    assert await gate(normal * 6) is True and len(jev.calls) == 4  # 6x the median length: judged (story)
    assert await gate("Author's note: " + normal) is True and len(jev.calls) == 4  # unremarkable length: not judged
    gate2 = ChapterGate(Decider(Settings(), None, None), first_n=3)  # no provider: heuristics only
    assert await gate2(normal) is True and await gate2("short") is False


async def test_embed_chapter_text_function_contract(maker, fake_embedder, session):
    from readerserver.models import SourceRecord

    rec = SourceRecord(source="t", source_id="1", title="T")
    session.add(rec)
    await session.commit()
    cfg = Settings(voyage_model="m")
    text = "\n\n".join(prose(1, 1))
    out = await embed_chapter_text(session, fake_embedder, cfg, rec.id, 0, text, purpose="t")
    assert out.embedded and out.chunks >= 1
    assert (await embed_chapter_text(session, fake_embedder, cfg, rec.id, 0, text, purpose="t")).outcome == "unchanged"
    assert (await embed_chapter_text(session, fake_embedder, Settings(voyage_model="m2"), rec.id, 0, text, purpose="t")).embedded  # model change re-embeds
    assert (await embed_chapter_text(session, fake_embedder, cfg, rec.id, 1, "  ", purpose="t")).outcome == "empty"

    async def reject(_t):
        return False

    assert (await embed_chapter_text(session, fake_embedder, cfg, rec.id, 0, text + " changed", purpose="t", gate=reject)).outcome == "rejected"
    from sqlalchemy import func, select

    from readerserver.models import SampleEmbedding

    assert await session.scalar(select(func.count()).select_from(SampleEmbedding).where(SampleEmbedding.source_record_id == rec.id)) == 0  # rejected: old vectors removed

    async def fetch():
        return text

    assert (await embed_chapter_text(session, fake_embedder, cfg, rec.id, 2, fetch, purpose="t")).embedded  # callable source = the fetch step


async def test_repair_requires_the_failing_page_itself_to_extract(maker, fake_llm):
    store = RecipeStore(maker)
    await store.create(DOMAIN, recipe(CHAPTER), "v1")
    s = session_for(maker, fake_llm)
    await s.load()
    old_url, old_html = chapter_page("v1")
    s.remember("chapter", old_url, old_html)  # an earlier good page in the old layout
    url, html = chapter_page("v2")
    fake_llm.add("ChapterSection", ChapterSection(body="#content"))  # works on the remembered sample, not on the failing page
    fake_llm.add("ChapterSection", ChapterSection(title="h1.ch-title", body="#story", drop=[".ads", ".share"]))
    await s.repair("chapter", url, html, "empty")
    assert len(fake_llm.calls) == 2 and s.sections["chapter"]["body"] == "#story"


async def test_quality_only_repair_failure_keeps_the_active_recipe(maker, fake_llm):
    store = RecipeStore(maker)
    await store.create(DOMAIN, recipe(CHAPTER), "v1")
    s = session_for(maker, fake_llm)
    await s.load()
    url, html = chapter_page()
    fake_llm.add("ChapterSection", ChapterSection(body="#nothing"))
    with pytest.raises(RecipeBroken):
        await s.repair("chapter", url, html, "judged has_junk", keep_on_failure=True)
    assert (await store.active(DOMAIN)).version == 1 and s.sections["chapter"]["body"] == "#content"


def test_problem_labels_carry_no_page_text():
    from readerserver.linkcrawl.builder import Problem

    p = Problem("chapter extraction judged has_junk (900 characters)", "... Start of extracted text: 'secret prose' ...")
    assert "secret" not in p.label and "secret" in p.feedback and Problem.of("x").feedback == "x"


def test_base_href_is_honoured_for_relative_links():
    html = '<html><head><base href="https://cdn.example/books/"></head><body><ul class="novels"><li><a href="n1">One</a></li></ul></body></html>'
    res = apply_listing(html, "https://novels.example/list", LISTING)
    assert [link.url for link in res.novel_links] == ["https://cdn.example/books/n1"]


async def test_fetch_follows_redirects_remembers_final_url_streams_under_a_cap(maker):
    import gzip
    from types import SimpleNamespace

    import httpx

    from readerserver.crawl.http import FetchError, PoliteClient
    from readerserver.linkcrawl.crawler import LinkCrawler, purl

    def handler(req: httpx.Request) -> httpx.Response:
        p = req.url.path
        if p == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nAllow: /\n")
        if p == "/old":
            return httpx.Response(301, headers={"location": "/new/dir/page"})
        if p == "/new/dir/page":
            return httpx.Response(200, content=gzip.compress(b"<html><a href='rel'>x</a></html>"), headers={"content-type": "text/html", "content-encoding": "gzip"})
        if p == "/big":
            return httpx.Response(200, content=b"<p>" + b"x" * 50_000, headers={"content-type": "text/html"})
        if p == "/img":
            return httpx.Response(200, content=b"\x89PNG" * 100, headers={"content-type": "image/png"})
        return httpx.Response(404)

    c = LinkCrawler(maker, Settings(link_max_page_bytes=10_000), None, None, None, None)  # type: ignore[arg-type]
    ctx = SimpleNamespace(http=PoliteClient("t", min_delay=0, transport=httpx.MockTransport(handler)), pages=0, cache={})
    page = await c._get(ctx, "https://novels.example/old")
    assert "<a href='rel'>x</a>" in page and purl(page, "https://novels.example/old") == "https://novels.example/new/dir/page"  # gzip decoded, final URL kept
    with pytest.raises(FetchError, match="larger than"):
        await c._get(ctx, "https://novels.example/big")  # body capped, not downloaded whole
    assert await c._get(ctx, "https://novels.example/img") is None  # non-text: skipped by content type
    assert await c._get(ctx, "https://novels.example/missing") is None
