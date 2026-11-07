"""Empty chapter pages are page-local skips; recipes recover from history; chapters_per_novel counts usable chapters."""
import asyncio
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select, text, update

from readerserver.linkcrawl.builder import RecipeSession
from readerserver.linkcrawl.recipes import ChapterSection, RecipeStore, apply_chapter
from readerserver.models import LinkCrawlJob, SampleEmbedding, SiteRecipe, SourceRecord

from .linksite import CHAPTER, DOMAIN, LISTING, NOVEL, queue_recipes
from .test_linkcrawl_jobs import HookEmbedder, LIST, add_job, chapter_indexes, env, job, make, run_next  # noqa: F401

FULL = {"listing": LISTING.model_dump(), "novel": NOVEL.model_dump(), "chapter": CHAPTER.model_dump()}


async def versions(maker) -> list[tuple[int, str]]:
    return [(r.version, r.status) for r in sorted(await RecipeStore(maker).versions(DOMAIN), key=lambda r: r.version)]


async def expire_lease(maker, jid):
    async with maker() as s:
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == jid).values(lease_expires_at=datetime.now(UTC) - timedelta(seconds=1)))
        await s.commit()


async def test_empty_chapter_is_skipped_without_repair_and_the_crawl_continues(env, fake_embedder):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    site.empty = {(1, 1)}  # chapter index 0: container present, nothing inside
    jid = await add_job(maker, LIST, "site")
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert llm.calls == [] and await versions(maker) == [(1, "active")]

    async with maker() as s:
        novel1 = sorted((await s.scalars(select(SampleEmbedding.chapter_index).join(SourceRecord, SourceRecord.id == SampleEmbedding.source_record_id)
                                         .where(SourceRecord.title == "Moonlit Bakery").distinct())).all())
    assert novel1 == [1, 2, 4]  # chapter index 0 skipped; 3 is the locked one
    assert j.novels_found == 4 and site.count("/list?page=2") == 1  # next novel and next listing page were reached
    assert j.checkpoint["empty_streak"] == 0


async def test_empty_chapter_with_every_version_broken_restores_sections_from_history(env, fake_embedder):
    maker, llm, site, jev = env
    store = RecipeStore(maker)
    await store.create(DOMAIN, FULL, "v1")
    await store.create(DOMAIN, FULL, "v2")
    await store.create(DOMAIN, {"listing": None, "novel": None, "chapter": CHAPTER.model_dump()}, "v3: chapter only")
    for r in await store.versions(DOMAIN):
        await store.mark_broken(r.id)
    site.empty = {(1, 1)}
    jid = await add_job(maker, LIST, "site")
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert llm.calls == []  # nothing was authored
    assert await versions(maker) == [(1, "broken"), (2, "broken"), (3, "broken"), (4, "active")]
    active = await store.active(DOMAIN)
    assert active.recipe["listing"] == LISTING.model_dump() and active.recipe["novel"] == NOVEL.model_dump() and active.recipe["chapter"]["body"] == "#content"
    assert j.novels_found == 4 and site.count("/list?page=2") == 1


async def test_consecutive_empty_chapters_reach_the_repair_path_and_a_success_resets_the_streak(env, fake_embedder):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    site.locked = set()
    site.empty = {(1, 1), (1, 2), (1, 4), (1, 5)}  # two empties, a good chapter, two empties: never 3 in a row
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev, link_consecutive_empty_chapters=3))
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert llm.calls == [] and await chapter_indexes(maker) == [2]

    llm.add("ChapterSection", ChapterSection(body="#content"))  # a "repair" that cannot work on an empty page
    site.empty = {(1, 1), (1, 2), (1, 3)}
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev, link_consecutive_empty_chapters=3))
    j = await job(maker, jid)
    assert j.status == "failed" and j.stop_reason == "recipe_error"
    assert len(llm.calls) == 2  # the third empty page in a row went to repair (2 attempts), the first two were skipped


async def test_empty_streak_survives_a_restart(env):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    site.locked = set()
    site.empty = {(1, 1), (1, 2)}

    async def crash(n):
        raise asyncio.CancelledError  # the worker dies while embedding the first good chapter

    c = make(maker, HookEmbedder(crash), llm, site, jev, link_consecutive_empty_chapters=3)
    jid = await add_job(maker)
    with pytest.raises(asyncio.CancelledError):
        await c.run_job(*(await c.claim()))
    j = await job(maker, jid)
    assert j.checkpoint["empty_streak"] == 2 and j.checkpoint["novel"]["pos"] == 2
    site.empty = {(1, 1), (1, 2), (1, 3)}  # after the restart the next page is empty too: the third in a row
    await expire_lease(maker, jid)
    llm.add("ChapterSection", ChapterSection(body="#content"))
    await run_next(make(maker, FakeEmbedderLike(), llm, site, jev, link_consecutive_empty_chapters=3))
    j = await job(maker, jid)
    assert j.status == "failed" and j.stop_reason == "recipe_error" and len(llm.calls) == 2


def FakeEmbedderLike():
    from .conftest import FakeEmbedder
    return FakeEmbedder()


async def test_selector_matching_nothing_still_repairs(env, fake_embedder):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    site.layout, site.locked = "v2", set()  # #content is gone: the body selector matches nothing
    llm.add("ChapterSection", ChapterSection(title="h1.ch-title", body="#story", drop=[".ads", ".share"]))
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert [x["schema"] for x in llm.calls] == ["ChapterSection"] and await versions(maker) == [(1, "broken"), (2, "active")]


def test_empty_body_distinguishes_three_cases():
    sec = ChapterSection(body="#content", drop=[".gone"])
    nothing = apply_chapter('<div id="other"><p>text here</p></div>', "https://x.test/c", sec)
    empty = apply_chapter('<div id="content"></div>', "https://x.test/c", sec)
    cleaned = apply_chapter('<div id="content"><p class="gone">all the text lives in the dropped element</p></div>', "https://x.test/c", sec)
    assert (nothing.text, nothing.empty_body) == ("", False)  # selector matched nothing -> repair
    assert (empty.text, empty.empty_body) == ("", True)  # truly empty -> skip
    assert (cleaned.text, cleaned.empty_body) == ("", False)  # the recipe's own cleanup removed the text -> repair


async def test_empty_page_in_the_cleanup_case_is_not_skipped(env, fake_embedder):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, {**FULL, "chapter": ChapterSection(title="h1.ch-title", body="#content", drop=["#content p"]).model_dump()}, "v1")
    site.locked = set()
    llm.add("ChapterSection", ChapterSection(title="h1.ch-title", body="#content", drop=[".ads", ".share"]))  # same body, drop fixed
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert len(llm.calls) == 1 and await chapter_indexes(maker) == [0, 1, 2, 3, 4]  # repaired, nothing skipped


async def test_chapters_per_novel_counts_usable_chapters(env, fake_embedder):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    site.empty = {(1, 1)}
    jid = await add_job(maker, per=2)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done" and j.chapters_embedded == 2 and j.chapters_skipped == 1
    assert await chapter_indexes(maker) == [1, 2]  # chapter_index is the real reading-order index
    # spares beyond the limit are kept in the checkpoint (novel 2 has 3 chapters on one list page)
    jid = await add_job(maker, f"https://{DOMAIN}/novel/2", per=1)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.chapters_embedded == 1 and j.checkpoint["novel"] is None
    assert site.count("/novel/2/chapter/1") == 1 and site.count("/novel/2/chapter/2") == 0  # finished once `good` reached the limit


async def test_good_count_survives_a_restart_and_old_checkpoints_still_work(env):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    site.locked, site.empty = set(), {(1, 1)}

    async def crash(n):
        if n == 2:
            raise asyncio.CancelledError

    c = make(maker, HookEmbedder(crash), llm, site, jev)
    jid = await add_job(maker, per=2)
    with pytest.raises(asyncio.CancelledError):
        await c.run_job(*(await c.claim()))
    j = await job(maker, jid)
    assert j.checkpoint["novel"]["pos"] == 2 and j.checkpoint["novel"]["good"] == 1
    await expire_lease(maker, jid)
    await run_next(make(maker, FakeEmbedderLike(), llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done" and await chapter_indexes(maker) == [1, 2]

    # an old checkpoint: no `good` key, a plain 3-item chapter list, pos 0
    async with maker() as s:
        await s.execute(text("DELETE FROM sample_embeddings"))
        await s.commit()
    jid = await add_job(maker, per=3)
    c = make(maker, HookEmbedder(crash), llm, site, jev)
    with pytest.raises(asyncio.CancelledError):
        await c.run_job(*(await c.claim()))
    async with maker() as s:
        await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == jid).values(
            checkpoint=text("(checkpoint #- '{novel,good}') || jsonb_build_object('novel', (checkpoint->'novel') - 'good' || '{\"pos\": 0}'::jsonb)")))
        await s.commit()
    assert "good" not in (await job(maker, jid)).checkpoint["novel"]
    await expire_lease(maker, jid)
    await run_next(make(maker, FakeEmbedderLike(), llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done" and await chapter_indexes(maker) == [1, 2]  # 3-item list, empty first chapter: the old format ends with the list


async def test_unlimited_chapters_still_means_all_chapters(env, fake_embedder):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    site.locked, site.empty = set(), {(1, 1)}
    await run_next_job(maker, llm, site, jev, fake_embedder)
    assert await chapter_indexes(maker) == [1, 2, 3, 4]


async def run_next_job(maker, llm, site, jev, emb):
    await add_job(maker)
    await run_next(make(maker, emb, llm, site, jev))


async def test_empty_later_chapter_list_page_does_not_delete_stored_vectors(env, fake_embedder):
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    await run_next_job(maker, llm, site, jev, fake_embedder)
    assert await chapter_indexes(maker) == [0, 1, 2, 4]
    site.toc2_empty = True  # the second chapter-list page now shows no chapter links
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert await chapter_indexes(maker) == [0, 1, 2, 4]  # nothing pruned
    async with maker() as s:
        assert (await s.scalar(select(SourceRecord.chapter_count))) is None  # total unknown now


async def test_empty_page_does_not_delete_old_vectors(env, fake_embedder):
    """embed_chapter_text keeps earlier vectors on an empty outcome: a page-local skip deletes nothing."""
    maker, llm, site, jev = env
    await RecipeStore(maker).create(DOMAIN, FULL, "v1")
    await run_next_job(maker, llm, site, jev, fake_embedder)
    site.empty = {(1, 1)}
    await run_next_job(maker, llm, site, jev, fake_embedder)
    assert await chapter_indexes(maker) == [0, 1, 2, 4]


async def test_bootstrap_samples_past_empty_chapters_with_no_active_recipe(env, fake_embedder):
    maker, llm, site, jev = env
    queue_recipes(llm, listing=False)
    site.locked, site.empty = set(), {(1, 1), (1, 2)}
    jid = await add_job(maker)
    await run_next(make(maker, fake_embedder, llm, site, jev))
    j = await job(maker, jid)
    assert j.status == "done", j.error
    assert [x["schema"] for x in llm.calls] == ["NovelSection", "ChapterSection"]
    assert await versions(maker) == [(1, "active")] and await chapter_indexes(maker) == [2, 3, 4]
    assert site.count("/novel/1/chapter/4") == 1 and site.count("/novel/1/chapter/5") == 1


# ---------------------------------------------------------------- builder: repairs must not break good pages
from .test_linkcrawl_recipes import chapter_page, recipe, session_for  # noqa: E402


async def test_repair_that_breaks_a_remembered_good_sample_is_rejected(maker, fake_llm):
    store = RecipeStore(maker)
    await store.create(DOMAIN, recipe(CHAPTER), "v1")
    s = session_for(maker, fake_llm)
    await s.load()
    old_url, old_html = chapter_page("v1")
    s.remember("chapter", old_url, old_html)
    url, html = chapter_page("v2")
    fake_llm.add("ChapterSection", ChapterSection(title="h1.ch-title", body="#story", drop=[".ads", ".share"]))  # fixes v2, breaks the sample
    fake_llm.add("ChapterSection", ChapterSection(title="h1.ch-title", body="#content, #story", drop=[".ads", ".share"]))
    await s.repair("chapter", url, html, "empty")
    assert len(fake_llm.calls) == 2 and s.sections["chapter"]["body"] == "#content, #story"
    assert "worked before" in fake_llm.calls[1]["user"]


async def test_restored_old_version_must_also_work_on_remembered_samples(maker, fake_llm):
    store = RecipeStore(maker)
    await store.create(DOMAIN, recipe(ChapterSection(title="h1.ch-title", body="#story", drop=[".ads", ".share"])), "v1 (other layout)")
    await store.create(DOMAIN, recipe(CHAPTER), "v2")
    s = session_for(maker, fake_llm)
    await s.load()
    s.remember("chapter", *chapter_page("v1"))
    url, html = chapter_page("v2")
    fake_llm.add("ChapterSection", ChapterSection(title="h1.ch-title", body="#content, #story", drop=[".ads", ".share"]))
    await s.repair("chapter", url, html, "empty")
    assert len(fake_llm.calls) == 1  # v1 fixes the failing page but breaks the sample: Claude was asked instead
    assert s.sections["chapter"]["body"] == "#content, #story"


async def test_session_ensure_restores_from_history_before_authoring(maker, fake_llm):
    store = RecipeStore(maker)
    await store.create(DOMAIN, recipe(CHAPTER), "v1")
    await store.mark_broken((await store.active(DOMAIN)).id)
    s = session_for(maker, fake_llm)
    await s.load()
    assert not s.has("chapter")
    await s.ensure("chapter", [chapter_page("v1")])
    assert fake_llm.calls == [] and s.has("chapter") and (await store.active(DOMAIN)).version == 2
