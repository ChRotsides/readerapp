"""Scheduler: crawls sources on an interval or when requested via the API, then resolves + indexes.
Run: python -m readerserver.worker  (or `python -m readerserver.worker --once syosetu --max-items 100`)"""
import argparse
import asyncio
import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from .crawl.runner import CrawlRunner
from .linkcrawl.crawler import LinkCrawler
from .models import CrawlState
from .resolution.resolver import Resolver, seed_tags
from .services import Services, build_services, build_source, source_names

log = logging.getLogger("worker")


def make_runner(sv: Services) -> CrawlRunner:
    return CrawlRunner(sv.maker, sv.settings, sv.embedder, Resolver(sv.verdicts))


async def run_source(sv: Services, runner: CrawlRunner, name: str, max_items: int | None = None, fresh: bool = False) -> dict:
    source = build_source(name, sv.settings)
    try:
        res = await runner.crawl(source, max_items=max_items, fresh=fresh)
        if res.get("status") == "idle":  # never sample after a blocked/deferred/failed/skipped catalog run
            await runner.sample_chapters(source)
        pipeline = await runner.process_pending()
        return res | {"pipeline": pipeline}
    finally:
        for attr in ("http", "html_http"):
            http = getattr(source, attr, None)
            if http:
                await http.aclose()


def make_link_crawler(sv: Services, runner: CrawlRunner) -> LinkCrawler:
    return LinkCrawler(sv.maker, sv.settings, sv.embedder, sv.llm, sv.jev, runner)


async def link_loop(crawler: LinkCrawler, poll_seconds: float = 10.0) -> None:
    """Runs user-submitted link-crawl jobs (queued, or running with an expired lease) alongside the source scheduler."""
    while True:
        try:
            await crawler.run_due()
        except Exception:
            log.exception("link crawl iteration failed")
        try:
            await crawler.schedule_refresh()
        except Exception:
            log.exception("link refresh scheduling failed")
        await asyncio.sleep(poll_seconds)


async def due_sources(sv: Services) -> list[str]:
    now = datetime.now(UTC)
    enabled = {s.strip() for s in sv.settings.enabled_sources.split(",") if s.strip()}
    interval = timedelta(hours=sv.settings.crawl_interval_hours)
    out = []
    async with sv.maker() as s:
        states = {st.source: st for st in (await s.scalars(select(CrawlState))).all()}
    for name in source_names():
        st = states.get(name)
        if st and st.status in ("blocked", "disabled") and not st.requested_at:
            continue
        if st and st.next_eligible_at and st.next_eligible_at > now:
            continue  # server asked us to wait (Retry-After): persisted, not slept
        if st and st.status == "running" and st.lease_expires_at and st.lease_expires_at > now:
            continue  # alive worker holds the lease
        # status 'running' with an expired/missing lease = abandoned run: due again, resumed from its checkpoint
        abandoned = bool(st and st.status == "running")
        if st and st.requested_at:
            out.append(name)
        elif name in enabled and (abandoned or st is None or st.last_finished_at is None or now - st.last_finished_at > interval or st.checkpoint):
            out.append(name)
    return out


async def loop(poll_seconds: float = 30.0) -> None:
    sv = build_services()
    runner = make_runner(sv)
    async with sv.maker() as s:
        await seed_tags(s)
    link_task = asyncio.create_task(link_loop(make_link_crawler(sv, runner)))  # noqa: F841 (kept alive for the process lifetime)
    while True:
        try:
            for name in await due_sources(sv):
                log.info("crawling %s", name)
                log.info("result: %s", await run_source(sv, runner, name))
            await runner.process_pending()
        except Exception:
            log.exception("worker iteration failed")
        await asyncio.sleep(poll_seconds)


async def once(name: str, max_items: int | None, fresh: bool) -> None:
    sv = build_services()
    runner = make_runner(sv)
    async with sv.maker() as s:
        await seed_tags(s)
    print(await run_source(sv, runner, name, max_items, fresh))


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", metavar="SOURCE", help="crawl one source then exit")
    ap.add_argument("--max-items", type=int)
    ap.add_argument("--fresh", action="store_true", help="ignore the stored checkpoint")
    a = ap.parse_args()
    if a.once:
        asyncio.run(once(a.once, a.max_items, a.fresh))
    else:
        asyncio.run(loop())


if __name__ == "__main__":
    main()
