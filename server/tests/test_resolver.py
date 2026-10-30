import pytest
from sqlalchemy import func, select

from readerserver.ai.jev import MatchVerdict
from readerserver.crawl.runner import upsert_record
from readerserver.models import LinkBlock, SourceRecord, Work, WorkLink, WorkRelation
from readerserver.resolution.resolver import Resolver, active_records, seed_tags, unlink_record
from readerserver.sources.base import SourceRecordData as D


class FakeVerdicts:
    def __init__(self, v=None, exc=None):
        self.v, self.exc, self.calls = v, exc, 0

    async def verdict(self, a, b):
        self.calls += 1
        if self.exc:
            raise self.exc
        return self.v


async def add(session, **kw):
    rec, _ = await upsert_record(session, D(**kw))
    await session.commit()
    return rec


async def resolve(session, resolver, rec):
    out = await resolver.resolve(session, rec)
    await session.commit()
    return out


async def n_works(session):
    return await session.scalar(select(func.count()).select_from(Work))


async def test_hard_id_match_via_mal_id(session):
    await seed_tags(session)
    r = Resolver(None)
    mal = await add(session, source="mal", source_id="120", title="Maburaho", authors=["Toshihiko Tsukiji"], external_ids={"mal": "120"})
    o1 = await resolve(session, r, mal)
    ani = await add(session, source="anilist", source_id="30120", title="Totally Different Romaji", external_ids={"anilist": "30120", "mal": "120"})
    o2 = await resolve(session, r, ani)
    assert o1["action"] == "new" and o2["action"] == "merged" and o2["method"] == "hard_id"
    assert o2["work_id"] == o1["work_id"] and await n_works(session) == 1
    link = await session.scalar(select(WorkLink).where(WorkLink.source_record_id == ani.id))
    assert link.method == "hard_id" and link.confidence >= 0.95


async def test_native_title_bridge_trigram_cjk_and_auto_merge(session):
    r = Resolver(None)
    syo = await add(session, source="syosetu", source_id="n9669bk", title="無職転生 - 異世界行ったら本気だす -", authors=["理不尽な孫の手"],
                    language="ja", start_year=2012, chapter_count=286, synopsis="三十四歳の無職の男が異世界に転生する物語")
    await resolve(session, r, syo)
    mu = await add(session, source="mangaupdates", source_id="1", title="Mushoku Tensei", alt_titles=["無職転生 - 異世界行ったら本気だす -", "Jobless Reincarnation"],
                   authors=["Rifujin na Magonote"], language="ja", start_year=2012)
    out = await resolve(session, r, mu)
    assert out["action"] == "merged" and out["method"] == "hard_alias"
    work = await session.get(Work, out["work_id"])
    assert work.native_title and "無職転生" in work.native_title
    assert "Jobless Reincarnation" in work.aliases


async def test_fuzzy_auto_merge_and_separate(session):
    r = Resolver(None)
    a = await add(session, source="royalroad", source_id="1", title="The Beginning After The End", authors=["TurtleMe"], language="en", start_year=2018,
                  synopsis="A king is reborn into a new world and has to start again from nothing.")
    await resolve(session, r, a)
    b = await add(session, source="novelupdates", source_id="2", title="Beginning After the End, The (WN)", authors=["TurtleMe"], language="en", start_year=2018)
    ob = await resolve(session, r, b)
    c = await add(session, source="novelupdates", source_id="3", title="Overlord", authors=["Kugane Maruyama"], language="ja")
    oc = await resolve(session, r, c)
    assert ob["action"] == "merged" and ob["method"] == "auto_score"
    assert oc["action"] == "new" and await n_works(session) == 2


async def test_synopsis_knn_candidate_found_without_title_overlap(session, fake_embedder):
    r = Resolver(None)
    syn = "A tired office worker dies and wakes in a fantasy world with a cooking skill and a talking dragon companion"
    a = await add(session, source="a", source_id="1", title="Zzzz Qqqq", synopsis=syn, authors=["X Y"], language="en")
    a.synopsis_embedding = (await fake_embedder.embed([f"{a.title}\n{a.synopsis}"], "document"))[0]
    await session.commit()
    await resolve(session, r, a)
    b = await add(session, source="b", source_id="2", title="Kkkk Wwww", synopsis=syn, authors=["X Y"], language="en")
    b.synopsis_embedding = (await fake_embedder.embed([f"{b.title}\n{b.synopsis}"], "document"))[0]
    await session.commit()
    verdicts = FakeVerdicts(MatchVerdict("SAME_WORK", 0.93, "jev", {"SAME_WORK": 0.93}))
    out = await resolve(session, Resolver(verdicts), b)
    assert verdicts.calls == 1 and out["action"] == "merged" and out["method"] == "jev"  # middle band -> typed verdict


async def test_verdict_related_creates_relation_not_merge(session):
    r = Resolver(None)
    wn = await add(session, source="royalroad", source_id="1", title="Mushoku Tensei (WN)", authors=["Magonote"], language="ja", start_year=2012)
    await resolve(session, r, wn)
    ln = await add(session, source="novelupdates", source_id="2", title="Mushoku Tensei (LN)", authors=["Magonote"], language="ja", start_year=2012)
    v = FakeVerdicts(MatchVerdict("RELATED_WORK", 0.8, "claude", {}, "rewrite", "LN_VERSION"))
    out = await resolve(session, Resolver(v), ln)
    assert out["action"] == "related" and out["relation"] == "LN_VERSION"
    assert await n_works(session) == 2
    rels = (await session.scalars(select(WorkRelation))).all()
    assert len(rels) == 2 and {x.relation_type for x in rels} == {"LN_VERSION"}  # stored both directions


async def test_verdict_different_and_low_confidence_same_stay_separate(session):
    r = Resolver(None)
    a = await add(session, source="a", source_id="1", title="Dragon King Chronicles", authors=["Alice A"], language="en")
    await resolve(session, r, a)
    b = await add(session, source="b", source_id="2", title="Dragon King Chronicles", authors=["Bob B"], language="en")
    out = await resolve(session, Resolver(FakeVerdicts(MatchVerdict("SAME_WORK", 0.4, "jev", {}))), b)
    assert out["action"] == "new" and await n_works(session) == 2


async def test_no_verdict_provider_is_conservative_and_flags_wn_ln(session):
    r = Resolver(None)
    a = await add(session, source="a", source_id="1", title="Shadow Slave (WN)", authors=["Guiltythree"], language="en", start_year=2021)
    await resolve(session, r, a)
    b = await add(session, source="b", source_id="2", title="Shadow Slave (LN)", authors=["Guiltythree"], language="en", start_year=2021)
    out = await resolve(session, Resolver(FakeVerdicts(exc=RuntimeError("down"))), b)
    assert out["action"] == "new" and out.get("pending_verdict")
    assert await n_works(session) == 2


async def test_unlink_split_is_reversible_and_blocks_remerge(session):
    r = Resolver(None)
    a = await add(session, source="mal", source_id="1", title="Same Title Here", external_ids={"mal": "1"})
    await resolve(session, r, a)
    b = await add(session, source="anilist", source_id="2", title="Other", external_ids={"mal": "1", "anilist": "2"})
    out = await resolve(session, r, b)
    wid = out["work_id"]
    assert len(await active_records(session, wid)) == 2
    res = await unlink_record(session, b.id)
    await session.commit()
    assert res["old_work_id"] == wid and res["new_work_id"] != wid
    assert len(await active_records(session, wid)) == 1
    # audit trail kept: the undone link still exists
    undone = await session.scalar(select(func.count()).select_from(WorkLink).where(WorkLink.source_record_id == b.id, WorkLink.undone_at.is_not(None)))
    assert undone == 1
    assert await session.scalar(select(func.count()).select_from(LinkBlock)) == 1
    # source data untouched
    assert (await session.get(SourceRecord, b.id)).title == "Other"
    # re-running resolution does not re-merge (manual link is locked)
    b.needs_resolution = True
    again = await resolve(session, r, b)
    assert again["action"] == "locked"


async def test_recrawl_unchanged_record_does_not_reset_flags(session):
    rec = await add(session, source="a", source_id="1", title="T")
    rec.needs_resolution = False
    await session.commit()
    _, changed = await upsert_record(session, D(source="a", source_id="1", title="T"))
    assert changed is False and rec.needs_resolution is False
    _, changed = await upsert_record(session, D(source="a", source_id="1", title="T2"))
    assert changed is True and rec.needs_resolution is True


# ---------------------------------------------------------------- review-3 resolution findings
async def test_manual_link_record_recrawl_rebuilds_work_and_clears_pending(session):
    r = Resolver(None)
    a = await add(session, source="mal", source_id="1", title="Same Title Here", external_ids={"mal": "1"}, chapter_count=10)
    await resolve(session, r, a)
    b = await add(session, source="anilist", source_id="2", title="Other", external_ids={"mal": "1", "anilist": "2"}, chapter_count=10)
    await resolve(session, r, b)
    res = await unlink_record(session, b.id)
    await session.commit()
    _, changed = await upsert_record(session, D(source="anilist", source_id="2", title="Other Renamed", external_ids={"mal": "1", "anilist": "2"}, chapter_count=99))
    await session.commit()
    assert changed and b.needs_resolution
    out = await resolve(session, r, b)
    assert out["action"] == "locked" and b.needs_resolution is False and b.needs_index is True
    work = await session.get(Work, res["new_work_id"])
    assert work.title == "Other Renamed" and work.chapter_count == 99  # canonical metadata refreshed
    assert (await session.scalar(select(func.count()).select_from(SourceRecord).where(SourceRecord.needs_resolution.is_(True)))) == 0


async def test_split_constraint_is_symmetric_when_the_other_record_changes(session):
    from readerserver.models import LinkSeparation

    r = Resolver(None)
    a = await add(session, source="mal", source_id="1", title="Same Title Here", external_ids={"mal": "1"}, chapter_count=5)
    await resolve(session, r, a)
    b = await add(session, source="anilist", source_id="2", title="Other", external_ids={"mal": "1", "anilist": "2"})
    out = await resolve(session, r, b)
    wid_ab = out["work_id"]
    res = await unlink_record(session, b.id)
    await session.commit()
    assert await session.scalar(select(func.count()).select_from(LinkSeparation)) == 1
    # A changes; it is a singleton now and shares an authoritative id with B, yet must not merge into B's work
    await upsert_record(session, D(source="mal", source_id="1", title="Same Title Here", external_ids={"mal": "1"}, chapter_count=6))
    await session.commit()
    outa = await resolve(session, r, a)
    assert outa["work_id"] == wid_ab and outa["work_id"] != res["new_work_id"]
    assert await n_works(session) == 2
    # and the converse: an incoming brand-new record linking to both does not bridge them
    c = await add(session, source="mu", source_id="9", title="Same Title Here", external_ids={"mal": "1"})
    outc = await resolve(session, r, c)
    assert outc["action"] == "merged"
    assert {await session.scalar(select(WorkLink.work_id).where(WorkLink.source_record_id == x.id, WorkLink.undone_at.is_(None))) for x in (a, b)} == {wid_ab, res["new_work_id"]}


async def test_singleton_update_keeps_its_work_id_and_history(session):
    r = Resolver(None)
    a = await add(session, source="a", source_id="1", title="Lonely Novel", chapter_count=10, authors=["Zed Q"], language="en")
    wid = (await resolve(session, r, a))["work_id"]
    await add(session, source="b", source_id="2", title="Unrelated Thing Entirely", authors=["Other P"], language="ko")
    await upsert_record(session, D(source="a", source_id="1", title="Lonely Novel", chapter_count=250, authors=["Zed Q"], language="en"))
    await session.commit()
    out = await resolve(session, r, a)
    assert out["work_id"] == wid and out["action"] == "new"
    assert (await session.get(Work, wid)).chapter_count == 250
    assert await session.scalar(select(func.count()).select_from(WorkLink).where(WorkLink.source_record_id == a.id)) == 1  # no churn


async def test_singleton_that_later_merges_still_releases_old_work(session):
    r = Resolver(None)
    a = await add(session, source="a", source_id="1", title="Unique Zebra Quest Tale", language="en")
    wa = (await resolve(session, r, a))["work_id"]
    b = await add(session, source="b", source_id="2", title="Another Distinct Name", language="en", external_ids={"x": "1"})
    wb = (await resolve(session, r, b))["work_id"]
    await upsert_record(session, D(source="a", source_id="1", title="Unique Zebra Quest Tale", language="en", external_ids={"x": "1"}))
    await session.commit()
    out = await resolve(session, r, a)
    assert out["work_id"] == wb and await session.get(Work, wa) is None


async def test_split_marks_both_works_profiles_stale(session):
    r = Resolver(None)
    a = await add(session, source="mal", source_id="1", title="Same Title Here", external_ids={"mal": "1"})
    await resolve(session, r, a)
    b = await add(session, source="anilist", source_id="2", title="Other", external_ids={"mal": "1", "anilist": "2"})
    await resolve(session, r, b)
    for x in (a, b):
        x.needs_index = False
    await session.commit()
    await unlink_record(session, b.id)
    await session.commit()
    await session.refresh(a)
    assert a.needs_index is True and b.needs_index is True  # old work's remaining record too, so its vector is rebuilt


async def test_descriptive_cross_link_does_not_auto_merge_sequel(session):
    r = Resolver(None)
    first = await add(session, source="mal", source_id="120", title="Maburaho", external_ids={"mal": "120"}, language="ja")
    await resolve(session, r, first)
    seq = await add(session, source="novelupdates", source_id="s", title="Totally Other Sequel Name", language="ja",
                    external_ids={"novelupdates": "s"}, related_urls=["https://myanimelist.net/manga/120/Maburaho"])
    out = await resolve(session, r, seq)
    assert out["action"] == "new" and await n_works(session) == 2  # before: unconditional merge via external_ids


async def test_wn_ln_with_shared_id_is_not_auto_merged(session):
    r = Resolver(None)
    wn = await add(session, source="mal", source_id="5", title="Shadow Slave (WN)", external_ids={"mal": "5"})
    await resolve(session, r, wn)
    ln = await add(session, source="anilist", source_id="6", title="Shadow Slave (LN)", external_ids={"anilist": "6", "mal": "5"})
    out = await resolve(session, r, ln)
    assert out["action"] == "new" and await n_works(session) == 2


async def test_verdict_outage_keeps_record_pending_with_retry_time(session):
    r0 = Resolver(None)
    a = await add(session, source="a", source_id="1", title="Shadow Slave (WN)", authors=["Guiltythree"], language="en", start_year=2021)
    await resolve(session, r0, a)
    b = await add(session, source="b", source_id="2", title="Shadow Slave (LN)", authors=["Guiltythree"], language="en", start_year=2021)
    out = await resolve(session, Resolver(FakeVerdicts(exc=RuntimeError("down"))), b)
    assert out["pending_verdict"] and b.needs_resolution is True and b.resolve_reason == "pending_verdict" and b.resolve_retry_at is not None
    # later the provider works: the same record is re-decided (and its provisional work id reused when still separate)
    v = FakeVerdicts(MatchVerdict("RELATED_WORK", 0.9, "claude", {}, "rewrite", "LN_VERSION"))
    out2 = await resolve(session, Resolver(v), b)
    assert out2["action"] == "related" and out2["work_id"] == out["work_id"] and b.needs_resolution is False and b.resolve_reason is None
