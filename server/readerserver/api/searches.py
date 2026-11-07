"""HTTP routes for saved search history (`/searches`), synced with the phone. Registered by create_app; bearer token required.

`/search` calls save_search after a successful run. Deletes are tombstones (deleted_at) so they sync; the `since` pull
cursor is the server's `updated_at`."""
import logging
import uuid
from datetime import UTC, datetime

from fastapi import Depends, FastAPI, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import SearchHistory
from ..search.schemas import SearchRequest, SearchResponse

log = logging.getLogger(__name__)


class SearchItemOut(BaseModel):
    id: str
    description: str
    result_count: int
    created_at: datetime
    deleted: bool


class SearchListOut(BaseModel):
    searches: list[SearchItemOut]
    server_time: datetime


class SearchEntryOut(SearchItemOut):
    request: dict
    scenes: dict
    results: list[dict]


def _item(h: SearchHistory) -> dict:
    return dict(id=str(h.id), description=h.description, result_count=h.result_count, created_at=h.created_at, deleted=h.deleted_at is not None)


def _utc(d: datetime) -> datetime:
    return d.replace(tzinfo=UTC) if d.tzinfo is None else d.astimezone(UTC)


async def save_search(s: AsyncSession, req: BaseModel, res: SearchResponse, description: str | None = None) -> str | None:
    """Store a successful search; returns its id, or None when saving failed (the search itself is never failed by it)."""
    try:
        dump = res.model_dump(mode="json")
        row = SearchHistory(
            description=description or req.description, request=req.model_dump(mode="json"), result_count=len(res.results), results=dump["results"],
            scenes={k: dump[k] for k in ("scenes", "synopsis", "tags", "filters", "reranked", "notes")} | ({"seed_title": res.seed_title} if res.seed_title else {}))
        s.add(row)
        await s.commit()
        return str(row.id)
    except Exception:
        log.exception("saving search history failed")
        await s.rollback()
        return None


def register(app: FastAPI, svc, session, auth: list) -> None:
    @app.get("/searches", response_model=SearchListOut, dependencies=auth)
    async def list_searches(since: datetime | None = Query(None), limit: int = Query(50, ge=1, le=500), s: AsyncSession = Depends(session)):
        now = datetime.now(UTC)
        q = select(SearchHistory).order_by(SearchHistory.created_at.desc(), SearchHistory.id)
        q = q.where(SearchHistory.updated_at > _utc(since)) if since is not None else q.where(SearchHistory.deleted_at.is_(None))
        rows = (await s.scalars(q.limit(limit))).all()
        return SearchListOut(searches=[SearchItemOut(**_item(h)) for h in rows], server_time=now)

    @app.get("/searches/{search_id}", response_model=SearchEntryOut, dependencies=auth)
    async def get_search(search_id: uuid.UUID, s: AsyncSession = Depends(session)):
        h = await s.get(SearchHistory, search_id)
        if h is None or h.deleted_at is not None:
            raise HTTPException(404, "search not found")
        return SearchEntryOut(**_item(h), request=h.request, scenes=h.scenes, results=h.results)

    @app.delete("/searches/{search_id}", status_code=204, dependencies=auth)
    async def delete_search(search_id: uuid.UUID, s: AsyncSession = Depends(session)):
        h = await s.get(SearchHistory, search_id)
        if h is None:
            raise HTTPException(404, "search not found")
        if h.deleted_at is None:
            now = datetime.now(UTC)
            h.deleted_at, h.updated_at = now, now
            await s.commit()

    @app.delete("/searches", dependencies=auth)
    async def clear_searches(s: AsyncSession = Depends(session)):
        now = datetime.now(UTC)
        r = await s.execute(update(SearchHistory).where(SearchHistory.deleted_at.is_(None)).values(deleted_at=now, updated_at=now))
        await s.commit()
        return {"deleted": r.rowcount, "server_time": now}
