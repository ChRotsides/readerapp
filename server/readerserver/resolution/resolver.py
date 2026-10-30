"""Entity resolution pipeline: hard matches -> candidates -> scoring -> auto-merge / separate / typed verdict.
Records are never overwritten; they are linked (reversibly) to canonical Works."""
import logging
import uuid
from datetime import UTC, datetime

from sqlalchemy import and_, delete, func, select, text, update
from sqlalchemy.ext.asyncio import AsyncSession

from ..ai.jev import VerdictProvider
from ..models import (
    CanonicalTag,
    LinkBlock,
    SourceRecord,
    SourceTitle,
    TagMapping,
    Work,
    WorkLink,
    WorkRelation,
)
from ..text import normalize_title, version_marker
from .merge import canonicalize_tags, merge_records, normalize_tag
from .scoring import VERDICT_SAME_MIN, Score, decide, hard_match, score_pair
from .tag_seed import BASE_TAGS, SYNONYMS

log = logging.getLogger(__name__)
TRGM_MIN = 0.4
TRGM_LIMIT = 30
KNN_LIMIT = 10


# ---------------------------------------------------------------- tags
async def seed_tags(session: AsyncSession) -> None:
    existing = set((await session.scalars(select(CanonicalTag.name))).all())
    for name in BASE_TAGS:
        if name not in existing:
            session.add(CanonicalTag(name=name, origin="base"))
    await session.flush()
    ids = {t.name: t.id for t in (await session.scalars(select(CanonicalTag))).all()}
    have = set((await session.execute(select(TagMapping.source, TagMapping.source_tag))).all())
    for syn, canon in SYNONYMS.items():
        key = ("*", normalize_tag(syn))
        if key not in have and canon in ids:
            session.add(TagMapping(source="*", source_tag=key[1], canonical_tag_id=ids[canon]))
    await session.commit()


async def load_tag_maps(session: AsyncSession) -> tuple[dict[tuple[str, str], str], dict[str, str]]:
    rows = (await session.execute(
        select(TagMapping.source, TagMapping.source_tag, CanonicalTag.name).join(CanonicalTag, CanonicalTag.id == TagMapping.canonical_tag_id)
    )).all()
    mapping = {(s, t): n for s, t, n in rows}
    vocab = {normalize_tag(n): n for n in (await session.scalars(select(CanonicalTag.name))).all()}
    return mapping, vocab


async def _register_new_tags(session: AsyncSession, records: list[SourceRecord], mapping, vocab) -> None:
    """Auto-create canonical tags (and source mappings) for tags nobody mapped yet, so they are visible/editable."""
    for r in records:
        for t in r.tags or []:
            n = normalize_tag(t)
            if not n or (r.source, n) in mapping or ("*", n) in mapping or n in vocab:
                continue
            name = canonicalize_tags([t], r.source, mapping, vocab)[0]
            ct = await session.scalar(select(CanonicalTag).where(CanonicalTag.name == name))
            if ct is None:
                ct = CanonicalTag(name=name, origin=r.source)
                session.add(ct)
                await session.flush()
            session.add(TagMapping(source=r.source, source_tag=n, canonical_tag_id=ct.id))
            mapping[(r.source, n)] = name
            vocab[normalize_tag(name)] = name
    await session.flush()


# ---------------------------------------------------------------- work rebuild
async def active_records(session: AsyncSession, work_id: uuid.UUID) -> list[SourceRecord]:
    q = (select(SourceRecord).join(WorkLink, WorkLink.source_record_id == SourceRecord.id)
         .where(WorkLink.work_id == work_id, WorkLink.undone_at.is_(None)))
    return list((await session.scalars(q)).all())


async def rebuild_work(session: AsyncSession, work_id: uuid.UUID) -> Work | None:
    work = await session.get(Work, work_id)
    if work is None:
        return None
    recs = await active_records(session, work_id)
    if not recs:
        await session.delete(work)  # empty canonical work
        return None
    mapping, vocab = await load_tag_maps(session)
    await _register_new_tags(session, recs, mapping, vocab)
    m = merge_records(recs, mapping, vocab)
    for f in ("title", "native_title", "aliases", "authors", "synopsis", "synopsis_source", "tags", "genres",
              "status", "chapter_count", "language", "start_year", "cover_url"):
        setattr(work, f, getattr(m, f))
    work.updated_at = datetime.now(UTC)
    return work


# ---------------------------------------------------------------- links
async def current_link(session: AsyncSession, rec_id: int) -> WorkLink | None:
    return await session.scalar(select(WorkLink).where(WorkLink.source_record_id == rec_id, WorkLink.undone_at.is_(None)))


async def link_record(session: AsyncSession, rec: SourceRecord, work_id: uuid.UUID, method: str, confidence: float, evidence: dict) -> None:
    cur = await current_link(session, rec.id)
    if cur is not None:
        if cur.work_id == work_id:
            return
        cur.undone_at = datetime.now(UTC)
        await session.flush()
    session.add(WorkLink(source_record_id=rec.id, work_id=work_id, method=method, confidence=confidence, evidence=evidence))
    await session.flush()


async def new_work_for(session: AsyncSession, rec: SourceRecord, method: str = "new", evidence: dict | None = None) -> uuid.UUID:
    w = Work(title=rec.title)
    session.add(w)
    await session.flush()
    await link_record(session, rec, w.id, method, 1.0, evidence or {})
    return w.id


async def add_relation(session: AsyncSession, a: uuid.UUID, b: uuid.UUID, rtype: str, method: str, confidence: float) -> None:
    if a == b:
        return
    for x, y in ((a, b), (b, a)):
        exists = await session.scalar(select(WorkRelation.id).where(
            WorkRelation.work_id == x, WorkRelation.related_work_id == y, WorkRelation.relation_type == rtype))
        if not exists:
            session.add(WorkRelation(work_id=x, related_work_id=y, relation_type=rtype, method=method, confidence=confidence))
    await session.flush()


def record_summary(r: SourceRecord) -> dict:
    return {
        "source": r.source, "title": r.title, "aliases": list(r.alt_titles or [])[:12], "authors": list(r.authors or []),
        "synopsis": (r.synopsis or "")[:700], "language": r.language, "start_year": r.start_year,
        "chapter_count": r.chapter_count, "status": r.status, "tags": list(r.tags or [])[:15],
    }


# ---------------------------------------------------------------- candidate generation
async def _linked_work(session: AsyncSession, rec_id: int) -> uuid.UUID | None:
    l = await current_link(session, rec_id)
    return l.work_id if l else None


async def hard_candidates(session: AsyncSession, rec: SourceRecord) -> list[SourceRecord]:
    ids: set[int] = set()
    for k, v in (rec.external_ids or {}).items():
        q = select(SourceRecord.id).where(SourceRecord.external_ids.contains({k: v}), SourceRecord.id != rec.id)
        ids.update((await session.scalars(q)).all())
    if rec.url:
        q = select(SourceRecord.id).where(SourceRecord.related_urls.contains([rec.url]), SourceRecord.id != rec.id)
        ids.update((await session.scalars(q)).all())
    if rec.related_urls:
        q = select(SourceRecord.id).where(SourceRecord.url.in_(list(rec.related_urls)), SourceRecord.id != rec.id)
        ids.update((await session.scalars(q)).all())
    norms = {normalize_title(t) for t in [rec.title, *(rec.alt_titles or [])]}
    norms.discard("")
    if norms:
        q = select(SourceTitle.source_record_id).where(SourceTitle.norm_title.in_(norms), SourceTitle.source_record_id != rec.id)
        ids.update((await session.scalars(q)).all())
    if not ids:
        return []
    return list((await session.scalars(select(SourceRecord).where(SourceRecord.id.in_(ids)))).all())


async def fuzzy_candidates(session: AsyncSession, rec: SourceRecord) -> dict[int, float | None]:
    """record id -> synopsis cosine (when found via kNN). Trigram over all aliases + synopsis-embedding kNN."""
    found: dict[int, float | None] = {}
    norms = list({normalize_title(t) for t in [rec.title, *(rec.alt_titles or [])] if normalize_title(t)})[:8]
    for n in norms:
        rows = (await session.execute(
            text("SELECT source_record_id, similarity(norm_title, :q) AS sim FROM source_titles "
                 "WHERE norm_title % :q AND source_record_id != :rid ORDER BY sim DESC LIMIT :lim"),
            {"q": n, "rid": rec.id, "lim": TRGM_LIMIT})).all()
        for rid, sim in rows:
            if sim >= TRGM_MIN:
                found.setdefault(rid, None)
    if rec.synopsis_embedding is not None:
        rows = (await session.execute(
            select(SourceRecord.id, (1 - SourceRecord.synopsis_embedding.cosine_distance(rec.synopsis_embedding)))
            .where(SourceRecord.id != rec.id, SourceRecord.synopsis_embedding.is_not(None))
            .order_by(SourceRecord.synopsis_embedding.cosine_distance(rec.synopsis_embedding)).limit(KNN_LIMIT))).all()
        for rid, cos in rows:
            found[rid] = float(cos)
    return found


# ---------------------------------------------------------------- main
class Resolver:
    def __init__(self, verdicts: VerdictProvider | None):
        self.verdicts = verdicts

    async def resolve(self, session: AsyncSession, rec: SourceRecord) -> dict:
        """Resolve one record. Returns an outcome dict (action, work_id, method, confidence)."""
        cur = await current_link(session, rec.id)
        if cur is not None:
            if cur.method == "manual":
                return {"action": "locked", "work_id": cur.work_id}
            n_in_work = await session.scalar(select(func.count()).select_from(WorkLink).where(
                WorkLink.work_id == cur.work_id, WorkLink.undone_at.is_(None)))
            if n_in_work and n_in_work > 1:
                await rebuild_work(session, cur.work_id)
                rec.needs_resolution = False
                return {"action": "kept", "work_id": cur.work_id}
        old_work = cur.work_id if cur else None
        blocked = set((await session.scalars(select(LinkBlock.work_id).where(LinkBlock.source_record_id == rec.id))).all())

        outcome = await self._decide(session, rec, blocked)
        target = outcome["work_id"]
        rec.needs_resolution = False
        touched = {target}
        if old_work and old_work != target:
            touched.add(old_work)
        for w in touched:
            await rebuild_work(session, w)
        rec.needs_index = True
        return outcome

    async def _decide(self, session: AsyncSession, rec: SourceRecord, blocked: set[uuid.UUID]) -> dict:
        # Step 1: hard matches
        hard: dict[uuid.UUID, tuple[str, dict, int]] = {}
        for cand in await hard_candidates(session, rec):
            hm = hard_match(rec, cand)
            wid = await _linked_work(session, cand.id)
            if hm and wid and wid not in blocked:
                hard.setdefault(wid, (hm[0], hm[1] | {"record_id": cand.id}, 0))
        if hard:
            counts = {w: await session.scalar(select(func.count()).select_from(WorkLink).where(
                WorkLink.work_id == w, WorkLink.undone_at.is_(None))) for w in hard}
            wid = max(hard, key=lambda w: counts[w])
            method, ev, _ = hard[wid]
            conf = 0.99 if ev.get("id") or ev.get("url_crossref") else 0.95
            await link_record(session, rec, wid, method, conf, ev)
            return {"action": "merged", "work_id": wid, "method": method, "confidence": conf}

        # Steps 2-3: candidates and scoring (best per work)
        cands = await fuzzy_candidates(session, rec)
        best: tuple[uuid.UUID, Score, SourceRecord] | None = None
        if cands:
            recs = list((await session.scalars(select(SourceRecord).where(SourceRecord.id.in_(list(cands))))).all())
            for c in recs:
                wid = await _linked_work(session, c.id)
                if wid is None or wid in blocked or c.source == rec.source and c.source_id == rec.source_id:
                    continue
                sc = score_pair(rec, c, cands.get(c.id))
                if best is None or sc.total > best[1].total:
                    best = (wid, sc, c)
        if best is None:
            wid = await new_work_for(session, rec)
            return {"action": "new", "work_id": wid, "method": "new", "confidence": 1.0}
        wid, sc, other = best
        decision = decide(sc)
        ev = {"score": sc.as_dict(), "other_record_id": other.id}
        if decision == "merge":
            await link_record(session, rec, wid, "auto_score", round(sc.total, 3), ev)
            return {"action": "merged", "work_id": wid, "method": "auto_score", "confidence": sc.total}
        if decision == "separate":
            new = await new_work_for(session, rec, "new", ev)
            return {"action": "new", "work_id": new, "method": "new", "confidence": 1.0}

        # middle band (or WN/LN conflict): typed verdict
        verdict = None
        if self.verdicts is not None:
            try:
                verdict = await self.verdicts.verdict(record_summary(rec), record_summary(other))
            except Exception as e:
                log.warning("verdict unavailable for record %s: %s", rec.id, e)
        if verdict is None:  # no verdict possible: be conservative, keep separate and relate if markers conflict
            new = await new_work_for(session, rec, "new", ev | {"verdict": "unavailable"})
            if sc.marker_conflict:
                await add_relation(session, new, wid, "LN_VERSION", "heuristic", 0.5)
            return {"action": "new", "work_id": new, "method": "new", "confidence": 1.0, "pending_verdict": True}
        ev |= {"verdict": verdict.verdict, "probability": verdict.probability, "probs": verdict.probabilities, "reason": verdict.reason}
        if verdict.verdict == "SAME_WORK" and verdict.probability >= VERDICT_SAME_MIN:
            await link_record(session, rec, wid, verdict.method, round(verdict.probability, 3), ev)
            return {"action": "merged", "work_id": wid, "method": verdict.method, "confidence": verdict.probability}
        new = await new_work_for(session, rec, "new", ev)
        if verdict.verdict == "RELATED_WORK":
            rtype = verdict.relation or ("LN_VERSION" if sc.marker_conflict else "RELATED")
            await add_relation(session, new, wid, rtype, verdict.method, verdict.probability)
            return {"action": "related", "work_id": new, "related_to": wid, "relation": rtype, "method": verdict.method}
        return {"action": "new", "work_id": new, "method": "new", "confidence": 1.0}


# ---------------------------------------------------------------- undo
async def unlink_record(session: AsyncSession, rec_id: int) -> dict:
    """Split a record out of its work: undo the link (kept for audit), block re-merging into that work, give the
    record its own Work. Source data is untouched; if the old work empties it is deleted."""
    rec = await session.get(SourceRecord, rec_id)
    if rec is None:
        raise LookupError("record not found")
    cur = await current_link(session, rec_id)
    if cur is None:
        raise LookupError("record is not linked")
    old = cur.work_id
    cur.undone_at = datetime.now(UTC)
    exists = await session.scalar(select(LinkBlock).where(LinkBlock.source_record_id == rec_id, LinkBlock.work_id == old))
    if not exists:
        session.add(LinkBlock(source_record_id=rec_id, work_id=old))
    await session.flush()
    new = await new_work_for(session, rec, "manual", {"split_from": str(old)})
    await rebuild_work(session, old)
    await rebuild_work(session, new)
    rec.needs_index = True
    return {"record_id": rec_id, "old_work_id": old, "new_work_id": new}
