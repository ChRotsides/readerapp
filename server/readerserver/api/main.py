import logging
import secrets
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import func, select, text, update
from sqlalchemy.ext.asyncio import AsyncSession

from ..ai.embeddings import EmbeddingRateLimited
from ..ai.spend import BudgetExceeded
from ..config import Settings, get_settings
from ..models import CrawlState, SourceRecord, Work, WorkLink
from ..resolution.resolver import seed_tags, unlink_record
from ..search.pipeline import BudgetError, SearchService
from ..search.schemas import SearchRequest, SearchResponse, SimilarRequest, WorkOut
from ..search.similar import SeedNotEmbedded, SeedNotFound, similar
from ..services import Services, build_services, source_names
from ..works import load_works, title_search
from .favorites import register as register_favorite_routes
from .links import register as register_link_routes
from .searches import register as register_search_routes, save_search

log = logging.getLogger(__name__)
bearer = HTTPBearer(auto_error=False)


def create_app(services: Services | None = None, settings: Settings | None = None) -> FastAPI:
    cfg = settings or (services.settings if services else get_settings())

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if getattr(app.state, "services", None) is None:
            app.state.services = services or build_services(cfg)
        try:
            async with app.state.services.maker() as s:
                await seed_tags(s)
        except Exception:  # DB not migrated yet; /health still answers
            log.exception("tag seeding failed")
        yield

    app = FastAPI(title="ReaderApp discovery server", lifespan=lifespan)
    if services is not None:
        app.state.services = services

    def svc(request: Request) -> Services:
        return request.app.state.services

    async def session(request: Request):
        async with svc(request).maker() as s:
            yield s

    async def require_auth(creds: HTTPAuthorizationCredentials | None = Depends(bearer)) -> None:
        if not cfg.api_token:
            raise HTTPException(503, "API_TOKEN is not configured on the server")
        if creds is None or not secrets.compare_digest(creds.credentials.encode(), cfg.api_token.encode()):
            raise HTTPException(401, "invalid or missing bearer token", headers={"WWW-Authenticate": "Bearer"})

    auth = [Depends(require_auth)]

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    # ------------------------------------------------------------ search / works
    @app.post("/search", response_model=SearchResponse, dependencies=auth)
    async def search(req: SearchRequest, request: Request, s: AsyncSession = Depends(session)):
        sv = svc(request)
        if sv.embedder is None:
            raise HTTPException(503, "VOYAGE_API_KEY is not configured")
        if sv.llm is None and not (req.scenes and req.synopsis):
            raise HTTPException(503, "ANTHROPIC_API_KEY is not configured")
        service = SearchService(sv.settings, sv.embedder, sv.llm, sv.jev)  # type: ignore[arg-type]
        try:
            res = await service.search(s, req)
        except (BudgetError, BudgetExceeded) as e:
            raise HTTPException(402, f"monthly AI budget reached: {e}") from e
        except EmbeddingRateLimited as e:
            raise HTTPException(429, "Voyage embedding rate limit reached. Without a payment method on the Voyage "
                                     "account it allows only 3 requests/minute; add one at dashboard.voyageai.com, "
                                     "or try again in a minute.") from e
        res.history_id = await save_search(s, req, res)
        return res

    @app.post("/similar", response_model=SearchResponse, dependencies=auth)
    async def find_similar(req: SimilarRequest, request: Request, s: AsyncSession = Depends(session)):
        sv = svc(request)
        if sv.embedder is None:
            raise HTTPException(503, "VOYAGE_API_KEY is not configured")
        service = SearchService(sv.settings, sv.embedder, sv.llm, sv.jev)  # type: ignore[arg-type]
        try:
            res = await similar(service, s, req)
        except SeedNotFound as e:
            raise HTTPException(404, str(e)) from e
        except SeedNotEmbedded as e:
            raise HTTPException(409, str(e)) from e
        except (BudgetError, BudgetExceeded) as e:
            raise HTTPException(402, f"monthly AI budget reached: {e}") from e
        except EmbeddingRateLimited as e:
            raise HTTPException(429, "Voyage embedding rate limit reached; try again in a minute.") from e
        res.history_id = await save_search(s, req, res, f"Similar to {res.seed_title}")
        return res

    @app.get("/works/{work_id}", response_model=WorkOut, dependencies=auth)
    async def get_work(work_id: str, s: AsyncSession = Depends(session)):
        import uuid

        try:
            wid = uuid.UUID(work_id)
        except ValueError:
            raise HTTPException(404, "work not found") from None
        w = (await load_works(s, [wid])).get(wid)
        if w is None:
            raise HTTPException(404, "work not found")
        return w

    @app.get("/works", response_model=list[WorkOut], dependencies=auth)
    async def list_works(query: str = Query(min_length=1), limit: int = Query(20, ge=1, le=50), s: AsyncSession = Depends(session)):
        ids = await title_search(s, query, limit)
        found = await load_works(s, ids)
        return [found[i] for i in ids if i in found]

    # ------------------------------------------------------------ admin
    @app.get("/admin/crawl", dependencies=auth)
    async def crawl_status(request: Request, s: AsyncSession = Depends(session)):
        states = {st.source: st for st in (await s.scalars(select(CrawlState))).all()}
        counts = dict((await s.execute(select(SourceRecord.source, func.count()).group_by(SourceRecord.source))).all())
        out = []
        for name in source_names():
            st = states.get(name)
            out.append({
                "source": name, "status": st.status if st else "never_run", "records": counts.get(name, 0),
                "checkpoint": st.checkpoint if st else {}, "requested": bool(st and st.requested_at),
                "last_started_at": st.last_started_at if st else None, "last_finished_at": st.last_finished_at if st else None,
                "last_error": st.last_error if st else None, "blocked_reason": st.blocked_reason if st else None,
            })
        works = await s.scalar(select(func.count()).select_from(Work))
        pending = await s.scalar(select(func.count()).select_from(SourceRecord).where(SourceRecord.needs_resolution.is_(True)))
        return {"sources": out, "works": works, "pending_resolution": pending, "spend": await svc(request).spend.summary()}

    @app.post("/admin/crawl/{source}", dependencies=auth, status_code=202)
    async def trigger_crawl(source: str, fresh: bool = False, s: AsyncSession = Depends(session)):
        if source not in source_names():
            raise HTTPException(404, f"unknown source {source!r}")
        from datetime import UTC, datetime

        st = await s.get(CrawlState, source) or CrawlState(source=source)
        s.add(st)
        st.requested_at = datetime.now(UTC)
        if fresh:
            st.checkpoint = {}
        await s.commit()
        return {"source": source, "queued": True, "note": "the worker picks this up within ~30s"}

    @app.post("/admin/crawl/{source}/unblock", dependencies=auth)
    async def unblock(source: str, s: AsyncSession = Depends(session)):
        st = await s.get(CrawlState, source)
        if st is None or st.status != "blocked":
            raise HTTPException(409, "source is not blocked")
        st.status, st.blocked_reason = "idle", None
        await s.commit()
        return {"source": source, "status": "idle"}

    @app.post("/admin/records/{record_id}/unlink", dependencies=auth)
    async def unlink(record_id: int, s: AsyncSession = Depends(session)):
        try:
            res = await unlink_record(s, record_id)
        except LookupError as e:
            raise HTTPException(404, str(e)) from e
        await s.commit()
        return {k: str(v) for k, v in res.items()}

    @app.get("/admin/works/{work_id}/records", dependencies=auth)
    async def work_records(work_id: str, s: AsyncSession = Depends(session)):
        rows = (await s.execute(
            select(SourceRecord.id, SourceRecord.source, SourceRecord.source_id, SourceRecord.title, SourceRecord.url,
                   WorkLink.method, WorkLink.confidence, WorkLink.evidence)
            .join(WorkLink, WorkLink.source_record_id == SourceRecord.id)
            .where(WorkLink.work_id == work_id, WorkLink.undone_at.is_(None)))).all()
        return [dict(zip(("record_id", "source", "source_id", "title", "url", "method", "confidence", "evidence"), r)) for r in rows]

    @app.get("/admin/spend", dependencies=auth)
    async def spend(request: Request):
        return await svc(request).spend.summary()

    register_link_routes(app, svc, session, auth)
    register_favorite_routes(app, svc, session, auth)
    register_search_routes(app, svc, session, auth)
    return app


def _default_app() -> FastAPI:
    return create_app()


app = _default_app()
