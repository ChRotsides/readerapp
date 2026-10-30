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
