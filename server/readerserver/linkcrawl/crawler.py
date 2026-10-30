"""Link crawl jobs: "crawl this URL" in the background, resumable, one job per domain at a time.

A job walks listing pages -> novel pages -> chapter-list pages -> chapter pages, one polite fetch per step. Between
steps it checks that it still owns the job (pause / cancel / resume take effect there) and persists a checkpoint, so a
restarted worker resumes from it once the lease expires. Pages are parsed with the domain's recipe (recipes.py /
builder.py, no AI per page); Jev/Claude only judge page types, link roles, chapter content and extraction quality
(decisions.py). Each chapter goes through chapters.embed_chapter_text (fetch -> embed -> store chunks); novels become
`link` source records that the usual resolver dedups into Works."""
import asyncio
import hashlib
import logging
import statistics
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from urllib.parse import urlsplit

import httpx
from bs4 import BeautifulSoup
from sqlalchemy import Integer, and_, cast, delete, exists, func, or_, select, text, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import aliased

from ..ai.embeddings import Embedder, EmbeddingRateLimited
from ..ai.jev import JevClient
from ..ai.llm import LLM
from ..ai.spend import BudgetExceeded
from ..config import Settings
from ..crawl.http import BlockedError, CrawlDeferred, FetchError, PoliteClient, RobotsDisallowed, UnsafeURL
from ..crawl.runner import CrawlRunner, upsert_record
from ..models import AiUsage, LinkCrawlJob, SampleEmbedding
from ..sources.base import SourceRecordData
from .builder import RecipeBroken, RecipeSession, RecipeUnavailable
from .chapters import embed_chapter_text
from .decisions import LOCK_WORDS, Decider, chapter_heuristic, chapter_suspicion
from .recipes import Link, RecipeStore, chapter_novel_links, clean_url, order_chapters, page_digest

log = logging.getLogger(__name__)
CLAIM_LOCK_KEY = 7_270_003  # pg advisory lock serializing job claims across worker processes
MAX_REPAIRS_PER_KIND = 3  # per job: a recipe that keeps breaking is not going to be fixed by more attempts
CACHE_PAGES = 4
EMBED_DEFER_SECONDS = 300.0
SPEND_EVERY = 25  # chapter saves between job-spend recomputations (structural saves always recompute)


class Page(str):
    """Page text that remembers its final URL (after redirects): links must resolve against that, not the requested URL."""

    url: str


def purl(html: str, url: str) -> str:
    return getattr(html, "url", url)


class JobStopped(Exception):
    """The job was paused / cancelled / resumed elsewhere, or its lease was lost: stop without touching its status."""


class JobFailed(Exception):
    pass


def domain_of(url: str) -> str:
    host = (urlsplit(url).hostname or "").lower().rstrip(".")
    return host.removeprefix("www.")


def source_id_for(url: str) -> str:
    """Stable id of a novel URL (fragment dropped, trailing slash ignored)."""
    return hashlib.sha1((clean_url(url, url) or url).rstrip("/").encode()).hexdigest()


def default_http_factory(settings: Settings) -> Callable[[str], PoliteClient]:
    def make(domain: str) -> PoliteClient:
        return PoliteClient(settings.user_agent, min_delay=settings.crawl_min_delay_seconds, respect_robots=True, allowed_hosts={domain})
    return make


class ChapterGate:
    """Decision (c) for one novel's chapters. Jev/Claude judge the first few chapters and every suspicious one (very
    short, paywall/lock words, big length deviation from the novel's median); the rest are taken as story."""

    def __init__(self, decider: Decider, first_n: int, judged: int = 0):
        self.decider, self.first_n, self.judged = decider, first_n, judged
        self.lengths: list[int] = []
        self.title: str | None = None

    async def __call__(self, text: str) -> bool:
        median = statistics.median(self.lengths) if len(self.lengths) >= 3 else None
        reasons = chapter_suspicion(text, median)
        if self.judged >= self.first_n and not reasons:
            self.lengths.append(len(text))
            return True
        self.judged += 1
        decision = await self.decider.chapter_content(self.title, text, {"suspicious": reasons, "novel_median_length": median})
        label = decision.choice if decision else chapter_heuristic(text, reasons)
        if label == "story":
            self.lengths.append(len(text))
        return label == "story"


@dataclass
class _Ctx:
    job_id: int
    url: str
    domain: str
    scope: str
    per_novel: int | None
    owner: str
    started: datetime
    http: PoliteClient
    recipe: RecipeSession
    decider: Decider
    ck: dict
    pages: int = 0
    novels: int = 0
    embedded: int = 0
    skipped: int = 0
    failures: int = 0
    cache: dict[str, str] = field(default_factory=dict)
    quality_done: set[str] = field(default_factory=set)
    gate: ChapterGate | None = None
    spend: float = 0.0
    saves: int = 0


class LinkCrawler:
    def __init__(self, maker: async_sessionmaker[AsyncSession], settings: Settings, embedder: Embedder | None, llm: LLM | None,
                 jev: JevClient | None, runner: CrawlRunner, http_factory: Callable[[str], PoliteClient] | None = None):
        self.maker, self.settings, self.embedder, self.llm, self.jev, self.runner = maker, settings, embedder, llm, jev, runner
        self.http_factory = http_factory or default_http_factory(settings)
        self.store = RecipeStore(maker)
        self._tasks: set[asyncio.Task] = set()

    # ------------------------------------------------------------ scheduling
    def _lease(self) -> timedelta:
        return timedelta(seconds=self.settings.crawl_lease_seconds)

    async def claim(self) -> tuple[int, str] | None:
        """Atomically take the oldest runnable job: queued, or running with an expired lease (its worker died). Never a
        job whose domain has another job with a live lease (running, or paused/resumed while its old worker is still
        finishing a page). Rows are locked (SKIP LOCKED) so a pause/cancel the API is committing is never overwritten. A
        job reclaimed from a dead worker more than `link_max_attempts` times is failed instead of crashing workers forever."""
        owner = uuid.uuid4().hex
        other = aliased(LinkCrawlJob)
        async with self.maker() as s, s.begin():
            await s.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": CLAIM_LOCK_KEY})
            for _ in range(10):
                now = datetime.now(UTC)
                job = await s.scalar(
                    select(LinkCrawlJob).where(
                        or_(LinkCrawlJob.status == "queued", LinkCrawlJob.status == "running"),
                        or_(LinkCrawlJob.lease_expires_at.is_(None), LinkCrawlJob.lease_expires_at < now),
                        or_(LinkCrawlJob.next_eligible_at.is_(None), LinkCrawlJob.next_eligible_at <= now),
                        ~exists().where(and_(other.domain == LinkCrawlJob.domain, other.id != LinkCrawlJob.id, other.lease_expires_at > now)),
                    ).order_by(LinkCrawlJob.id).limit(1).with_for_update(skip_locked=True, of=LinkCrawlJob))
                if job is None:
                    return None
                if job.status == "running":
                    job.attempts += 1
                    log.warning("link job %s: resuming abandoned run (lease expired %s), attempt %s", job.id, job.lease_expires_at, job.attempts)
                    if job.attempts > self.settings.link_max_attempts:
                        job.status, job.lease_owner, job.lease_expires_at = "failed", None, None
                        job.error = f"the worker died {job.attempts - 1} times while running this job; giving up (resume it to try again)"
                        await s.flush()
                        continue
                job.status, job.lease_owner, job.lease_expires_at, job.next_eligible_at = "running", owner, now + self._lease(), None
                return job.id, owner
            return None

    async def run_due(self, max_jobs: int = 3) -> int:
        """Top up the set of running jobs to `max_jobs` (claims respect one-job-per-domain and expired leases) and return
        how many were started. Jobs run as background tasks, so a long site crawl never blocks claiming other jobs;
        call it on every poll."""
        self._tasks = {t for t in self._tasks if not t.done()}
        started = 0
        while len(self._tasks) < max_jobs:
            c = await self.claim()
            if c is None:
                break
            self._tasks.add(asyncio.create_task(self._run_logged(*c)))
            started += 1
        return started

    async def _run_logged(self, job_id: int, owner: str) -> None:
        try:
            await self.run_job(job_id, owner)
        except Exception:
            log.exception("link job %s crashed", job_id)

    async def wait_idle(self) -> None:
        await asyncio.gather(*self._tasks, return_exceptions=True)

    # ------------------------------------------------------------ one job
    async def run_job(self, job_id: int, owner: str) -> None:
        async with self.maker() as s:
            job = await s.get(LinkCrawlJob, job_id)
            assert job is not None
        decider = Decider(self.settings, self.jev, self.llm, tag=f"lc{job_id}")
        http = self.http_factory(job.domain)
        ctx = _Ctx(job.id, job.url, job.domain, job.scope, job.chapters_per_novel, owner, job.created_at, http, None, decider,  # type: ignore[arg-type]
                   dict(job.checkpoint or {}), pages=job.pages_fetched, novels=job.novels_found, embedded=job.chapters_embedded, skipped=job.chapters_skipped)
        ctx.recipe = RecipeSession(job.domain, self.store, self.settings, self.llm, decider, lambda u: self._get_item(ctx, u), f"lc{job_id}")
        beat = asyncio.create_task(self._heartbeat(job_id, owner))
        status, error, defer_for = "done", None, None
        try:
            await self._drive(ctx)
        except JobStopped:
            beat.cancel()
            await self._release(ctx)
            return
        except BlockedError as e:
            status, error = "blocked", str(e)
            log.error("link job %s blocked: %s", job_id, e)
        except RobotsDisallowed as e:
            status, error = "blocked", f"robots.txt disallows {e}"
        except CrawlDeferred as e:
            status, error, defer_for = "queued", f"deferred: {e}", e.seconds
        except EmbeddingRateLimited as e:
            status, error, defer_for = "queued", f"embedding rate limit: {e}", EMBED_DEFER_SECONDS
        except BudgetExceeded as e:
            status, error = "paused", f"monthly AI budget reached: {e}"
        except (JobFailed, RecipeBroken, RecipeUnavailable) as e:
            status, error = "failed", str(e)
        except Exception as e:
            status, error = "failed", f"{type(e).__name__}: {e}"
            log.exception("link job %s failed", job_id)
        finally:
            beat.cancel()
            await http.aclose()
        await self._finish(ctx, status, error, defer_for)

    async def _heartbeat(self, job_id: int, owner: str) -> None:
        interval = max(self.settings.crawl_lease_seconds / 3, 0.05)
        while True:
            await asyncio.sleep(interval)
            try:
                async with self.maker() as s:
                    await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == job_id, LinkCrawlJob.lease_owner == owner)
                                    .values(lease_expires_at=datetime.now(UTC) + self._lease()))
                    await s.commit()
            except Exception:  # pragma: no cover - best effort
                log.exception("heartbeat failed for link job %s", job_id)

    async def _job_spend(self, ctx: _Ctx) -> float:
        """Jev + Claude + Voyage spend of this job: every purpose it records is tagged `lc<id>:` / `embed:lc<id>:`."""
        async with self.maker() as s:
            v = await s.scalar(select(func.coalesce(func.sum(AiUsage.cost_usd), 0)).where(
                AiUsage.ts >= ctx.started,
                or_(AiUsage.purpose.like(f"lc{ctx.job_id}:%"), AiUsage.purpose.like(f"embed:lc{ctx.job_id}:%"))))
            return float(v or 0)

    async def _save(self, ctx: _Ctx, structural: bool = True) -> None:
        """Persist counters + checkpoint and renew the lease. `structural=False` is a chapter-position-only update (the
        chapter list can be thousands of URLs, so only `novel.pos` is rewritten). Raises JobStopped if the job is no
        longer ours."""
        ctx.saves += 1
        if structural or ctx.saves % SPEND_EVERY == 0:  # the spend query scans ai_usage: not on every chapter
            ctx.spend = await self._job_spend(ctx)
        vals = dict(pages_fetched=ctx.pages, novels_found=ctx.novels, chapters_embedded=ctx.embedded, chapters_skipped=ctx.skipped,
                    spend_usd=ctx.spend, lease_expires_at=datetime.now(UTC) + self._lease(), updated_at=datetime.now(UTC))
        async with self.maker() as s:
            if structural or not ctx.ck.get("novel"):
                vals["checkpoint"] = ctx.ck
            else:
                vals["checkpoint"] = func.jsonb_set(LinkCrawlJob.checkpoint, text("'{novel,pos}'"), func.to_jsonb(cast(ctx.ck["novel"]["pos"], Integer)))
            res = await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == ctx.job_id, LinkCrawlJob.lease_owner == ctx.owner).values(**vals))
            await s.commit()
        if res.rowcount == 0:
            raise JobStopped

    async def _alive(self, ctx: _Ctx) -> None:
        """Between-pages gate: stop quietly when paused/cancelled or when another worker holds the job."""
        async with self.maker() as s:
            row = (await s.execute(select(LinkCrawlJob.status, LinkCrawlJob.lease_owner).where(LinkCrawlJob.id == ctx.job_id))).first()
        if row is None or row.status != "running" or row.lease_owner != ctx.owner:
            raise JobStopped

    async def _release(self, ctx: _Ctx) -> None:
        async with self.maker() as s:
            await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == ctx.job_id, LinkCrawlJob.lease_owner == ctx.owner)
                            .values(lease_owner=None, lease_expires_at=None))
            await s.commit()

    async def _finish(self, ctx: _Ctx, status: str, error: str | None, defer_for: float | None) -> None:
        values = dict(status=status, error=error, lease_owner=None, lease_expires_at=None, spend_usd=await self._job_spend(ctx),
                      pages_fetched=ctx.pages, novels_found=ctx.novels, chapters_embedded=ctx.embedded, chapters_skipped=ctx.skipped,
                      next_eligible_at=datetime.now(UTC) + timedelta(seconds=defer_for) if defer_for is not None else None,
                      updated_at=datetime.now(UTC))
        if status == "done":
            values["checkpoint"] = {}
        async with self.maker() as s:  # only if still ours and still running: a pause/cancel that raced us wins
            res = await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == ctx.job_id, LinkCrawlJob.lease_owner == ctx.owner,
                                                             LinkCrawlJob.status == "running").values(**values))
            if res.rowcount == 0:
                await s.execute(update(LinkCrawlJob).where(LinkCrawlJob.id == ctx.job_id, LinkCrawlJob.lease_owner == ctx.owner)
                                .values(lease_owner=None, lease_expires_at=None))
            await s.commit()

    # ------------------------------------------------------------ fetching
    async def _get(self, ctx: _Ctx, url: str) -> Page | None:
        """One polite GET (robots, delay, backoff, SSRF guards in PoliteClient; body streamed under a size cap, content type
        checked before the body is read). None for 404/410/non-text. The result remembers its final URL. Blocks, robots and
        deferrals propagate and end the job."""
        if url in ctx.cache:
            return ctx.cache[url]  # type: ignore[return-value]
        resp = await ctx.http.get(url, max_bytes=self.settings.link_max_page_bytes)
        ctx.pages += 1
        if resp.status_code in (404, 410):
            return None
        resp.raise_for_status()
        if not resp.content:
            return None
        page = Page(resp.text)
        page.url = str(resp.url)
        ctx.cache[url] = page
        while len(ctx.cache) > CACHE_PAGES:
            ctx.cache.pop(next(iter(ctx.cache)))
        return page

    async def _get_item(self, ctx: _Ctx, url: str) -> str | None:
        """Fetch one item's page. A failed fetch or a missing page (404/410/not text) skips the item and counts toward
        `link_consecutive_failures` in a row, which fail the job (a site that now 404s everything is not a success)."""
        try:
            html = await self._get(ctx, url)
        except (FetchError, UnsafeURL, httpx.HTTPError) as e:
            ctx.failures += 1
            log.warning("link job %s: %s failed (%s)", ctx.job_id, url, e)
            if ctx.failures >= self.settings.link_consecutive_failures:
                raise JobFailed(f"{ctx.failures} fetches in a row failed; last: {e}") from e
            return None
        if html is None:
            ctx.failures += 1
            if ctx.failures >= self.settings.link_consecutive_failures:
                raise JobFailed(f"{ctx.failures} pages in a row were not found (404) or not HTML; last: {url}")
            return None
        ctx.failures = 0
        return html

    # ------------------------------------------------------------ driver
    async def _drive(self, ctx: _Ctx) -> None:
        if self.embedder is None:
            raise JobFailed("VOYAGE_API_KEY is not configured: chapters cannot be embedded")
        await ctx.recipe.load()
        ck = ctx.ck
        if "phase" not in ck:
            await self._start(ctx)
        while True:
            await self._alive(ctx)
            if ck.get("novel"):
                await self._novel_step(ctx)
            elif ck["queue"]:
                await self._begin_novel(ctx, ck["queue"][0])
            elif ck.get("listing_next"):
                await self._listing_step(ctx)
            else:
                return

    async def _start(self, ctx: _Ctx) -> None:
        try:
            html = await self._get(ctx, ctx.url)
        except (FetchError, UnsafeURL, httpx.HTTPError) as e:
            raise JobFailed(f"cannot fetch {ctx.url}: {e}") from e
        if html is None:
            raise JobFailed(f"{ctx.url} was not found or is not an HTML page")
        decision = await ctx.decider.page_type(await asyncio.to_thread(page_digest, html, purl(html, ctx.url)))
        ptype = decision.choice if decision else self._guess_page_type(ctx, html, ctx.url)
        ck = ctx.ck
        ck.update(phase="crawl", queue=[], listing_next=None, listing_pages=0, novels_queued=0, novel=None)
        if ptype == "novel_page":
            ck["queue"] = [ctx.url]
        elif ptype == "novel_listing" and ctx.scope == "site":
            ck["listing_next"] = ctx.url
        elif ptype == "novel_listing":
            raise JobFailed("the URL is a novel listing; submit it with scope 'site' to crawl the novels on it")
        elif ptype in ("chapter", "chapter_list"):  # the user pasted the chapter they are reading: continue from its novel page
            novel = await self._find_novel_page(ctx, ctx.url, html)
            if novel is None:
                raise JobFailed(f"the URL is a {ptype.replace('_', ' ')} page and no link back to the novel's own page was found; submit the novel's page")
            ck["queue"] = [novel]
        else:
            raise JobFailed("the URL is not a novel page or a novel listing")
        await self._save(ctx)

    async def _find_novel_page(self, ctx: _Ctx, url: str, html: str, depth: int = 2) -> str | None:
        """From a chapter (or chapter-list) page find the novel's own page: candidate links (the recipe's novel_link selector,
        ancestor paths, "table of contents" links) are fetched and classified; a chapter list is followed one more hop."""
        sec = ctx.recipe.section("chapter") if ctx.recipe.has("chapter") else None
        for cand in (await asyncio.to_thread(chapter_novel_links, html, purl(html, url), sec))[:4]:
            page = await self._get_item(ctx, cand)
            if page is None:
                continue
            d = await ctx.decider.page_type(await asyncio.to_thread(page_digest, page, purl(page, cand)))
            kind = d.choice if d else self._guess_page_type(ctx, page, cand)
            if kind == "novel_page":
                return cand
            if kind == "chapter_list" and depth > 0 and (found := await self._find_novel_page(ctx, cand, page, depth - 1)):
                return found
        return None

    def _guess_page_type(self, ctx: _Ctx, html: str, url: str) -> str:
        """Page type without Jev/Claude: try the domain's recipe sections on the page."""
        r = ctx.recipe
        url = purl(html, url)
        if r.has("listing") and len(r.listing(html, url).novel_links) >= 3:
            return "novel_listing"
        if r.has("novel"):
            n = r.novel(html, url)
            if n.title and n.chapter_links:
                return "novel_page"
        if r.has("chapter") and len(r.chapter(html, url).text) > 500:
            return "chapter"
        return "other"

    async def _is_locked_page(self, ctx: _Ctx, html: str, url: str) -> bool:
        """A chapter page with no extractable body that is a paywall/lock/placeholder is skipped, not a broken recipe."""
        txt = await asyncio.to_thread(lambda: BeautifulSoup(html, "html.parser").get_text(" ", strip=True))
        if not LOCK_WORDS.search(txt[:6000]):
            return False
        d = await ctx.decider.chapter_content(None, txt[:3000], {"raw_page": True})
        return d is None or d.choice == "locked_or_placeholder"

    async def _extract(self, ctx: _Ctx, kind: str, url: str, html: str, check: Callable | None = None):
        """Apply the recipe to a page (parsing runs in a thread). Empty extraction -> not a locked/placeholder page, and of the
        right kind (Jev/Claude page type; other kinds yield None so the caller skips the page) -> repair the recipe (older
        versions, then Claude) and retry once. A recipe that keeps needing repairs raises RecipeBroken: the job fails
        loudly instead of skipping every page."""
        expected = {"listing": "novel_listing", "novel": "novel_page", "chapter": "chapter"}[kind]
        base = purl(html, url)
        for attempt in range(2):
            res = await asyncio.to_thread(getattr(ctx.recipe, kind), html, base)
            problem = check(res) if check else None
            if problem is None:
                ctx.recipe.remember(kind, base, html)
                return res
            if attempt == 1:
                return None
            if kind == "chapter" and await self._is_locked_page(ctx, html, base):
                return None
            decision = await ctx.decider.page_type(await asyncio.to_thread(page_digest, html, base))
            if decision and decision.choice != expected:
                return None
            if ctx.recipe.repairs[kind] >= MAX_REPAIRS_PER_KIND:
                raise RecipeBroken(f"the {kind} recipe for {ctx.domain} keeps failing ({problem}); {MAX_REPAIRS_PER_KIND} repairs used in this job")
            await ctx.recipe.repair(kind, base, html, problem)
        return None

    # ------------------------------------------------------------ listings
    async def _listing_step(self, ctx: _Ctx) -> None:
        ck, url = ctx.ck, ctx.ck["listing_next"]
        try:
            html = await self._get(ctx, url)
        except (FetchError, UnsafeURL, httpx.HTTPError) as e:
            if ck["listing_pages"] == 0:
                raise JobFailed(f"cannot fetch listing {url}: {e}") from e
            html = None
        res = None
        if html is not None:
            await ctx.recipe.ensure("listing", [(purl(html, url), html)])
            res = await self._extract(ctx, "listing", url, html, lambda r: None if r.novel_links else "novel_links matched nothing on this listing page")
        ck["listing_pages"] += 1
        ck["listing_next"] = None
        if res is None:
            if ck["listing_pages"] == 1:
                raise JobFailed("no novel links found on the listing page")
            await self._save(ctx)
            return
        seen = set(ck.setdefault("listing_seen", []))
        seen.add(url)
        queued = set(ck.setdefault("queued_ids", []))  # a novel linked from several listing pages is crawled once
        for link in res.novel_links:
            if ck["novels_queued"] >= self.settings.link_max_novels:
                break
            sid = source_id_for(link.url)
            if sid not in queued and (domain_of(link.url) == ctx.domain or domain_of(link.url).endswith("." + ctx.domain)):
                queued.add(sid)
                ck["queue"].append(link.url)
                ck["novels_queued"] += 1
        ck["queued_ids"] = sorted(queued)
        if res.next_page and res.next_page not in seen and ck["listing_pages"] < self.settings.link_max_listing_pages \
                and ck["novels_queued"] < self.settings.link_max_novels:
            ck["listing_next"] = res.next_page
        ck["listing_seen"] = sorted(seen)[-200:]
        await self._save(ctx)

    # ------------------------------------------------------------ novels
    async def _begin_novel(self, ctx: _Ctx, url: str) -> None:
        ck = ctx.ck
        html = await self._get_item(ctx, url)
        res = None
        if html is not None:
            await ctx.recipe.ensure("novel", [(purl(html, url), html)])
            res = await self._extract(ctx, "novel", url, html, lambda r: None if r.title and (r.chapter_links or r.toc_url) else
                                      "title/chapter_links matched nothing on this novel page")
        if res is not None and "novel" not in ctx.quality_done | ctx.recipe.validated:
            ctx.quality_done.add("novel")  # decision (d), once per job: Jev says whether the recipe extracts this page cleanly
            problem = await ctx.recipe.novel_quality_problem(res)
            if problem and ctx.recipe.repairs["novel"] < MAX_REPAIRS_PER_KIND:
                try:  # a quality complaint alone never takes a working recipe away: keep it if no better one is found
                    await ctx.recipe.repair("novel", purl(html, url), html, problem, keep_on_failure=True)  # type: ignore[arg-type]
                    res = ctx.recipe.novel(html, purl(html, url))  # type: ignore[arg-type]
                except (RecipeBroken, RecipeUnavailable) as e:
                    log.warning("link job %s: novel extraction judged poor but no better recipe found (%s); keeping the current one", ctx.job_id, e)
        ck["queue"].pop(0)
        if res is None:
            await self._save(ctx)
            return
        ctx.novels += 1
        order = ctx.recipe.sections["novel"].get("chapter_order", "auto")
        toc_next = res.toc_url or res.next_toc
        early = bool(ctx.per_novel and order == "asc" and len(res.chapter_links) >= ctx.per_novel)  # enough chapters already
        ck["novel"] = {
            "url": url, "record_id": None, "ready": False, "pos": 0, "toc_pages": 1, "chapters": [[link.url, link.text] for link in res.chapter_links],
            "toc_next": None if early else toc_next, "toc_seen": [url], "toc_complete": not (early and toc_next),
            "meta": {"title": res.title, "authors": res.authors, "synopsis": res.synopsis, "tags": res.tags, "genres": res.genres,
                     "status": res.status, "cover_url": res.cover_url, "order": order},
        }
        await self._save(ctx)

    async def _novel_step(self, ctx: _Ctx) -> None:
        nv = ctx.ck["novel"]
        if nv["toc_next"]:
            await self._toc_step(ctx, nv)
        elif not nv["ready"]:
            await self._finalize_toc(ctx, nv)
        elif nv["pos"] < len(nv["chapters"]):
            await self._chapter_step(ctx, nv)
        else:
            await self._end_novel(ctx, nv)

    async def _toc_step(self, ctx: _Ctx, nv: dict) -> None:
        url = nv["toc_next"]
        nv["toc_next"] = None
        html = await self._get_item(ctx, url) if url not in nv["toc_seen"] else None
        if html is not None:
            nv["toc_seen"].append(url)
            nv["toc_pages"] += 1
            res = await asyncio.to_thread(ctx.recipe.novel, html, purl(html, url))  # chapter_links / chapter_list_next apply on every page
            known = {c[0] for c in nv["chapters"]}
            nv["chapters"] += [[link.url, link.text] for link in res.chapter_links if link.url not in known]
            enough = bool(ctx.per_novel and nv["meta"]["order"] == "asc" and len(nv["chapters"]) >= ctx.per_novel)
            if res.next_toc and res.next_toc not in nv["toc_seen"] and nv["toc_pages"] < self.settings.link_max_toc_pages and not enough:
                nv["toc_next"] = res.next_toc
            elif res.next_toc and enough:
                nv["toc_complete"] = False  # stopped paging early: the novel's total chapter count is not known
        await self._save(ctx)

    async def _finalize_toc(self, ctx: _Ctx, nv: dict) -> None:
        """Chapter list complete: put it in reading order, store the novel as a `link` source record, cap the chapters."""
        links = order_chapters([Link(u, t) for u, t in nv["chapters"]], nv["meta"]["order"])
        total = len(links)
        nv["total"] = total if nv.get("toc_complete", True) else None
        if ctx.per_novel:
            links = links[: ctx.per_novel]
        nv["chapters"] = [[link.url, link.text] for link in links]
        m = nv["meta"]
        data = SourceRecordData(
            source="link", source_id=source_id_for(nv["url"]), title=m["title"], url=nv["url"], authors=m["authors"], synopsis=m["synopsis"],
            tags=m["tags"], genres=m["genres"], status=m["status"], chapter_count=nv["total"] or None, cover_url=m["cover_url"],
            raw={"domain": ctx.domain, "job_id": ctx.job_id, "recipe_version_id": ctx.recipe.row_id})
        async with self.maker() as s:
            rec, _ = await upsert_record(s, data)
            await s.commit()
            nv["record_id"] = rec.id
        nv["ready"] = True
        ctx.gate = ChapterGate(ctx.decider, self.settings.link_first_chapters_judged)
        await self._save(ctx)

    async def _chapter_step(self, ctx: _Ctx, nv: dict) -> None:
        pos = nv["pos"]
        url, hint = nv["chapters"][pos]
        if ctx.gate is None:  # resumed mid-novel: the first chapters were already judged before the restart
            ctx.gate = ChapterGate(ctx.decider, self.settings.link_first_chapters_judged, judged=pos)
        gate = ctx.gate
        if not ctx.recipe.has("chapter"):
            pages = []
            for u, _ in nv["chapters"][pos: pos + 2]:
                h = await self._get_item(ctx, u)
                if h:
                    pages.append((u, h))
            if not pages:
                nv["pos"] += 1
                ctx.skipped += 1
                await self._save(ctx, structural=False)
                return
            await ctx.recipe.ensure("chapter", [(purl(h, u), h) for u, h in pages])

        async def fetch_text() -> str | None:
            html = await self._get_item(ctx, url)
            if html is None:
                return None
            res = await self._extract(ctx, "chapter", url, html, lambda r: None if r.text else "body selector matched nothing on this chapter page")
            if res is not None and "chapter" not in ctx.quality_done | ctx.recipe.validated:
                ctx.quality_done.add("chapter")  # decision (d), once per job
                problem = await ctx.recipe.quality_problem("chapter", res.title, res.text)
                if problem and ctx.recipe.repairs["chapter"] < MAX_REPAIRS_PER_KIND:
                    try:  # a quality complaint alone (e.g. translator notes) never takes a working recipe away
                        await ctx.recipe.repair("chapter", purl(html, url), html, problem, keep_on_failure=True)
                        res = ctx.recipe.chapter(html, purl(html, url))
                    except (RecipeBroken, RecipeUnavailable) as e:
                        log.warning("link job %s: chapter extraction judged poor but no better recipe found (%s); keeping the current one", ctx.job_id, e)
            if res is None:
                return None
            gate.title = res.title or hint or None
            return res.text

        async with self.maker() as s:
            out = await embed_chapter_text(s, self.embedder, self.settings, nv["record_id"], pos, fetch_text,  # type: ignore[arg-type]
                                           purpose=f"lc{ctx.job_id}:embed", gate=gate)
        if out.embedded:
            ctx.embedded += 1
        else:
            ctx.skipped += 1  # unchanged since the last crawl, empty, or judged not story (note / locked / junk)
        nv["pos"] += 1
        await self._save(ctx, structural=False)

    async def _end_novel(self, ctx: _Ctx, nv: dict) -> None:
        rid = nv["record_id"]
        if nv.get("total") is not None:  # the whole chapter list was read: vectors of chapters that no longer exist are stale
            async with self.maker() as s:
                await s.execute(delete(SampleEmbedding).where(SampleEmbedding.source_record_id == rid, SampleEmbedding.chapter_index >= nv["total"]))
                await s.commit()
        ctx.ck["novel"], ctx.gate = None, None
        await self._save(ctx)
        try:  # dedup into a Work + profile embedding now; the worker loop would also pick it up
            await self.runner.process_pending(record_ids=[rid])
        except Exception:
            log.exception("resolution after link crawl of record %s failed (the worker will retry)", rid)

