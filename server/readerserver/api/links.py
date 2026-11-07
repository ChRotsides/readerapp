"""HTTP routes for link-crawl jobs (`/crawl-jobs`) and for adding chapters the user has read to search
(`/library/chapters`). Registered by create_app; every route needs the bearer token."""
from datetime import UTC, datetime
from typing import Literal
from urllib.parse import urlsplit

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import exists, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..ai.embeddings import EmbeddingRateLimited
from ..ai.spend import BudgetExceeded
from ..crawl.runner import upsert_record
from ..linkcrawl.chapters import embed_chapter_text
from ..linkcrawl.crawler import domain_of, source_id_for
from ..models import LinkCrawlJob, SourceRecord
from ..sources.base import SourceRecordData

MAX_LIBRARY_CHAPTERS = 50
MAX_CHAPTER_CHARS = 200_000


class CrawlJobIn(BaseModel):
    url: str
    scope: Literal["novel", "site"]
    chapters_per_novel: int | None = Field(default=None, ge=1, le=100_000)


class JobOut(BaseModel):
    id: int
    url: str
    scope: str
    chapters_per_novel: int | None
    status: str
    stop_reason: str | None
    pages_fetched: int
    novels_found: int
    chapters_embedded: int
    chapters_skipped: int
    spend_usd: float
    error: str | None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}

    @field_validator("spend_usd", mode="before")
    @classmethod
    def _float(cls, v):
        return float(v or 0)


class LibraryNovel(BaseModel):
    title: str = Field(min_length=1, max_length=500)
    author: str | None = None
    synopsis: str | None = None
    url: str = Field(min_length=1, max_length=2000)
    domain: str = Field(min_length=1, max_length=255)


class LibraryChapter(BaseModel):
    index: int = Field(ge=0)
    title: str | None = None
    url: str | None = None
    text: str = Field(max_length=MAX_CHAPTER_CHARS)


class LibraryChaptersIn(BaseModel):
    novel: LibraryNovel
    chapters: list[LibraryChapter] = Field(min_length=1, max_length=MAX_LIBRARY_CHAPTERS)

    @field_validator("chapters")
    @classmethod
    def _unique_indexes(cls, v: list[LibraryChapter]):
        if len({c.index for c in v}) != len(v):
            raise ValueError("duplicate chapter index")
        return v


class LibraryChaptersOut(BaseModel):
    embedded: int
    skipped: int


def register(app: FastAPI, svc, session, auth: list) -> None:
    async def get_job(s: AsyncSession, job_id: int, lock: bool = False) -> LinkCrawlJob:
        q = select(LinkCrawlJob).where(LinkCrawlJob.id == job_id)
        job = await s.scalar(q.with_for_update() if lock else q)
        if job is None:
            raise HTTPException(404, "crawl job not found")
        return job

    @app.post("/crawl-jobs", response_model=JobOut, status_code=202, dependencies=auth)
    async def create_job(req: CrawlJobIn, s: AsyncSession = Depends(session)):
        url = req.url.strip()
        p = urlsplit(url)
        if p.scheme not in ("http", "https") or not p.hostname:
            raise HTTPException(422, "url must be an absolute http(s) URL")
        job = LinkCrawlJob(url=url, domain=domain_of(url), scope=req.scope, chapters_per_novel=req.chapters_per_novel, status="queued", checkpoint={})
        s.add(job)
        await s.commit()
        await s.refresh(job)
        return job

    @app.get("/crawl-jobs", response_model=list[JobOut], dependencies=auth)
    async def list_jobs(limit: int = Query(100, ge=1, le=500), s: AsyncSession = Depends(session)):
        return list((await s.scalars(select(LinkCrawlJob).order_by(LinkCrawlJob.id.desc()).limit(limit))).all())

    @app.get("/crawl-jobs/{job_id}", response_model=JobOut, dependencies=auth)
    async def read_job(job_id: int, s: AsyncSession = Depends(session)):
        return await get_job(s, job_id)

    async def transition(s: AsyncSession, job_id: int, allowed: tuple[str, ...], to: str) -> LinkCrawlJob:
        """Status change that the running worker notices between pages (it stops when the status is no longer 'running')."""
        job = await get_job(s, job_id, lock=True)
        if job.status == to:
            return job  # idempotent
        if job.status not in allowed:
            raise HTTPException(409, f"cannot {to if to != 'queued' else 'resume'} a job that is {job.status}")
        job.status, job.updated_at = to, datetime.now(UTC)
        if to == "queued":
            job.error, job.next_eligible_at = None, None
            job.stop_reason = None
        elif to in ("paused", "cancelled"):
            job.stop_reason = "manual_pause" if to == "paused" else "user_cancelled"
            job.error = None
        await s.commit()
        return job

    @app.post("/crawl-jobs/{job_id}/pause", response_model=JobOut, dependencies=auth)
    async def pause_job(job_id: int, s: AsyncSession = Depends(session)):
        return await transition(s, job_id, ("queued", "running"), "paused")

    @app.post("/crawl-jobs/{job_id}/resume", response_model=JobOut, dependencies=auth)
    async def resume_job(job_id: int, s: AsyncSession = Depends(session)):
        job = await get_job(s, job_id, lock=True)
        if job.status in ("queued", "running"):
            return job  # already going
        if job.status == "done" and job.scope == "site" and job.stop_reason in (None, "legacy_completion") and not job.checkpoint:
            completed = await s.scalar(select(exists().where(
                SourceRecord.source == "link", SourceRecord.raw["job_id"].as_integer() == job.id,
            )))
            if not completed:
                raise HTTPException(409, "no saved novel records are available to recover this completed crawl")
            # The old worker deleted its checkpoint. Rescan listings only, skipping
            # this job's already processed novels by stable source ID.
            job.checkpoint = {"recover_completed": True}
            return await transition(s, job_id, ("done",), "queued")
        return await transition(s, job_id, ("paused", "blocked", "failed"), "queued")

    @app.post("/crawl-jobs/{job_id}/cancel", response_model=JobOut, dependencies=auth)
    async def cancel_job(job_id: int, s: AsyncSession = Depends(session)):
        return await transition(s, job_id, ("queued", "running", "paused", "blocked"), "cancelled")

    @app.post("/library/chapters", response_model=LibraryChaptersOut, dependencies=auth)
    async def library_chapters(req: LibraryChaptersIn, request: Request, s: AsyncSession = Depends(session)):
        sv = svc(request)
        if sv.embedder is None:
            raise HTTPException(503, "VOYAGE_API_KEY is not configured")
        n = req.novel
        rec, _ = await upsert_record(s, SourceRecordData(
            source="library", source_id=source_id_for(n.url), title=n.title.strip(), url=n.url, authors=[n.author.strip()] if n.author and n.author.strip() else [],
            synopsis=n.synopsis, raw={"domain": n.domain.lower()}))
        await s.commit()
        embedded = skipped = 0
        try:
            for ch in req.chapters:
                out = await embed_chapter_text(s, sv.embedder, sv.settings, rec.id, ch.index, ch.text, purpose="library", patient=False)
                embedded += int(out.embedded)
                skipped += int(not out.embedded)
        except BudgetExceeded as e:
            raise HTTPException(402, f"monthly AI budget reached: {e}") from e
        except EmbeddingRateLimited as e:
            raise HTTPException(429, "Voyage embedding rate limit reached; try again in a minute.") from e
        # Resolution into a Work and profile indexing are left to the worker loop (it runs process_pending every poll):
        # the request never waits on the global resolve lock or on paid verdict calls.
        return LibraryChaptersOut(embedded=embedded, skipped=skipped)
