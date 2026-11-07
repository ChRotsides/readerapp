"""Read-side helpers: build WorkOut (with per-source ratings and all URLs) and title search."""
import uuid

from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from .api.favorites import favorite_work_ids
from .models import SourceRecord, Work, WorkLink, WorkRelation
from .resolution.merge import prio, ratings_for
from .search.schemas import Rating, SourceUrl, WorkOut
from .text import normalize_title


async def load_works(session: AsyncSession, ids: list[uuid.UUID]) -> dict[uuid.UUID, WorkOut]:
    if not ids:
        return {}
    works = {w.id: w for w in (await session.scalars(select(Work).where(Work.id.in_(ids)))).all()}
    rows = (await session.execute(
        select(WorkLink.work_id, SourceRecord).join(SourceRecord, SourceRecord.id == WorkLink.source_record_id)
        .where(WorkLink.work_id.in_(ids), WorkLink.undone_at.is_(None))
    )).all()
    by_work: dict[uuid.UUID, list[SourceRecord]] = {}
    for wid, r in rows:
        by_work.setdefault(wid, []).append(r)
    rel_rows = (await session.execute(
        select(WorkRelation.work_id, WorkRelation.related_work_id, WorkRelation.relation_type, Work.title)
        .join(Work, Work.id == WorkRelation.related_work_id).where(WorkRelation.work_id.in_(ids))
    )).all()
    rels: dict[uuid.UUID, list[dict]] = {}
    for wid, rid, rtype, title in rel_rows:
        rels.setdefault(wid, []).append({"work_id": str(rid), "relation": rtype, "title": title})
    starred = await favorite_work_ids(session, ids)
    out: dict[uuid.UUID, WorkOut] = {}
    for wid, w in works.items():
        recs = sorted(by_work.get(wid, []), key=lambda r: prio(r.source))
        out[wid] = WorkOut(
            work_id=str(wid), title=w.title, native_title=w.native_title, aliases=list(w.aliases or []),
            authors=list(w.authors or []), cover_url=w.cover_url, synopsis=w.synopsis, tags=list(w.tags or []),
            genres=list(w.genres or []), status=w.status, chapter_count=w.chapter_count, language=w.language,
            start_year=w.start_year,
            ratings=[Rating(**r) for r in ratings_for(recs)],
            urls=[SourceUrl(source=r.source, url=r.url) for r in recs if r.url],
            related=rels.get(wid, []), favorite=wid in starred,
        )
    return out


async def title_search(session: AsyncSession, query: str, limit: int = 20) -> list[uuid.UUID]:
    n = normalize_title(query)
    if not n:
        return []
    rows = (await session.execute(text(
        """SELECT wl.work_id, max(GREATEST(similarity(st.norm_title, :q),
                  CASE WHEN st.norm_title LIKE '%' || :q || '%' THEN 0.6 ELSE 0 END)) AS sim
           FROM source_titles st JOIN work_links wl ON wl.source_record_id = st.source_record_id AND wl.undone_at IS NULL
           WHERE st.norm_title % :q OR st.norm_title LIKE '%' || :q || '%'
           GROUP BY wl.work_id ORDER BY sim DESC LIMIT :lim"""), {"q": n, "lim": limit})).all()
    return [r[0] for r in rows]
