"""HTTP routes for favorite novels (`/favorites`), synced with the phone. Registered by create_app; every route needs the bearer token.

Deletes are tombstones (deleted_at) so they sync. Conflicts are last-write-wins on the client's `changed_at`; the
`since` pull cursor is the server's `updated_at`, so a change pushed late from a device with an old clock still reaches the others."""
import re
import uuid
from datetime import UTC, datetime

from fastapi import Depends, FastAPI, HTTPException, Query
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Favorite, SourceRecord, Work, WorkLink

_URL_RE = re.compile(r"^(https?)://([^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$", re.IGNORECASE)


def normalize_novel_url(url: str) -> str:
    """The one place novel URLs are normalized for favorite keys (`url:<this>`). The app mirrors it in
    FavoriteKeys.normalizeUrl; both are tested against the same cases: scheme -> https, host lowercased without `www.`,
    fragment dropped, trailing slashes of the path dropped, query kept. Anything that is not http(s) is returned trimmed."""
    u = url.strip()
    m = _URL_RE.match(u)
    if m is None:
        return u
    host = m.group(2).lower().removeprefix("www.")
    query = m.group(4) if m.group(4) and m.group(4) != "?" else ""
    return f"https://{host}{m.group(3).rstrip('/')}{query}"


def _url_variants(norm: str) -> list[str]:
    """Spellings a SourceRecord URL may have been stored with (scheme, www, trailing slash)."""
    rest = norm.removeprefix("https://")
    out = []
    for scheme in ("https://", "http://"):
        for www in ("", "www."):
            out += [f"{scheme}{www}{rest}", f"{scheme}{www}{rest}/"] if "?" not in rest else [f"{scheme}{www}{rest}"]
    return out


class FavoriteIn(BaseModel):
    key: str = Field(min_length=1, max_length=2100)
    work_id: uuid.UUID | None = None
    novel_url: str | None = Field(default=None, max_length=2000)
    title: str = Field(min_length=1, max_length=500)
    author: str | None = None
    cover_url: str | None = None
    domain: str | None = Field(default=None, max_length=255)
    favorite: bool
    changed_at: datetime

    @field_validator("key")
    @classmethod
    def _key_shape(cls, v: str):
        v = v.strip()
        if v.startswith("work:"):
            v = f"work:{uuid.UUID(v[5:])}"  # canonical lowercase; ValueError -> 422
        elif v.startswith("url:") and len(v) > 4:
            v = "url:" + normalize_novel_url(v[4:])
        else:
            raise ValueError("key must be work:<work_id> or url:<novel url>")
        return v


class FavoriteOut(BaseModel):
    key: str
    work_id: str | None
    novel_url: str | None
    title: str
    author: str | None
    cover_url: str | None
    domain: str | None
    favorite: bool
    changed_at: datetime
    updated_at: datetime


class FavoritesOut(BaseModel):
    favorites: list[FavoriteOut]
    server_time: datetime


def _out(f: Favorite) -> FavoriteOut:
    return FavoriteOut(key=f.key, work_id=str(f.work_id) if f.work_id else None, novel_url=f.novel_url, title=f.title, author=f.author,
                       cover_url=f.cover_url, domain=f.domain, favorite=f.deleted_at is None, changed_at=f.changed_at, updated_at=f.updated_at)


def _utc(d: datetime) -> datetime:
    return d.replace(tzinfo=UTC) if d.tzinfo is None else d.astimezone(UTC)


async def favorite_work_ids(s: AsyncSession, ids: list[uuid.UUID]) -> set[uuid.UUID]:
    """Which of these works are currently starred (used to fill `favorite` on WorkOut)."""
    if not ids:
        return set()
    return set((await s.scalars(select(Favorite.work_id).where(Favorite.work_id.in_(ids), Favorite.deleted_at.is_(None)))).all())


def register(app: FastAPI, svc, session, auth: list) -> None:
    @app.get("/favorites", response_model=FavoritesOut, dependencies=auth)
    async def list_favorites(since: datetime | None = Query(None), s: AsyncSession = Depends(session)):
        now = datetime.now(UTC)
        q = select(Favorite).order_by(Favorite.updated_at, Favorite.id)
        q = q.where(Favorite.updated_at > _utc(since)) if since is not None else q.where(Favorite.deleted_at.is_(None))
        return FavoritesOut(favorites=[_out(f) for f in (await s.scalars(q)).all()], server_time=now)

    @app.put("/favorites", response_model=FavoriteOut, dependencies=auth)
    async def put_favorite(req: FavoriteIn, s: AsyncSession = Depends(session)):
        changed = _utc(req.changed_at)
        now = datetime.now(UTC)
        work_id = req.work_id or (uuid.UUID(req.key[5:]) if req.key.startswith("work:") else None)
        novel_url = req.novel_url.strip() if req.novel_url and req.novel_url.strip() else None
        if work_id is None and novel_url:
            variants = _url_variants(normalize_novel_url(novel_url))
            work_id = await s.scalar(select(WorkLink.work_id).join(SourceRecord, SourceRecord.id == WorkLink.source_record_id)
                                     .where(SourceRecord.url.in_(variants), WorkLink.undone_at.is_(None)).limit(1))
        if work_id is not None and await s.get(Work, work_id) is None:
            work_id = None  # unknown (or merged away) work: keep the favorite by key only
        values = dict(key=req.key, work_id=work_id, novel_url=novel_url, title=req.title.strip(), author=req.author, cover_url=req.cover_url,
                      domain=req.domain, changed_at=changed, updated_at=now, deleted_at=None if req.favorite else changed)
        inserted = await s.execute(pg_insert(Favorite).values(**values).on_conflict_do_nothing(index_elements=["key"]).returning(Favorite.id))
        if inserted.scalar() is None:
            row = await s.scalar(select(Favorite).where(Favorite.key == req.key).with_for_update())
            if row is not None and changed >= row.changed_at:  # an older change than what is stored is ignored
                for k, v in values.items():
                    setattr(row, k, v)
        await s.commit()
        return _out(await s.scalar(select(Favorite).where(Favorite.key == req.key)))
