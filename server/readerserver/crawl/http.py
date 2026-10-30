"""Polite HTTP client: per-domain rate limiting, robots.txt (incl. Crawl-delay), honest UA,
exponential backoff on 429/5xx, and stop-don't-evade on 403 / Cloudflare challenges."""
import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from urllib.parse import urlsplit
from urllib.robotparser import RobotFileParser

import httpx

log = logging.getLogger(__name__)


class BlockedError(Exception):
    """The site actively blocks us (403 / challenge). The caller must stop and record the source as blocked."""


class RobotsDisallowed(Exception):
    pass


class FetchError(Exception):
    pass


def _is_challenge(resp: httpx.Response) -> bool:
    if resp.status_code == 403:
        return True
    if resp.headers.get("cf-mitigated", "").lower() == "challenge":
        return True
    if resp.status_code in (429, 503) and resp.headers.get("server", "").lower() == "cloudflare":
        body = resp.text[:2000].lower() if resp.content else ""
        return "just a moment" in body or "cf-chl" in body or "attention required" in body
    return False


class PoliteClient:
    def __init__(
        self,
        user_agent: str,
        min_delay: float = 3.0,
        respect_robots: bool = True,
        max_retries: int = 4,
        backoff_base: float = 2.0,
        backoff_max: float = 120.0,
        transport: httpx.AsyncBaseTransport | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        clock: Callable[[], float] = time.monotonic,
        timeout: float = 30.0,
    ):
        self.user_agent = user_agent
        self.min_delay = min_delay
        self.respect_robots = respect_robots
        self.max_retries = max_retries
        self.backoff_base = backoff_base
        self.backoff_max = backoff_max
        self._sleep = sleep
        self._clock = clock
        self._client = httpx.AsyncClient(
            headers={"User-Agent": user_agent, "Accept-Encoding": "gzip"},
            transport=transport,
            timeout=timeout,
            follow_redirects=True,
        )
        self._locks: dict[str, asyncio.Lock] = {}
        self._next_ok: dict[str, float] = {}
        self._robots: dict[str, RobotFileParser | None] = {}
        self._domain_delay: dict[str, float] = {}

    async def aclose(self) -> None:
        await self._client.aclose()

    @staticmethod
    def origin(url: str) -> str:
        p = urlsplit(url)
        return f"{p.scheme}://{p.netloc}"

    def delay_for(self, origin: str) -> float:
        return max(self.min_delay, self._domain_delay.get(origin, 0.0))

    def set_min_delay_for(self, origin: str, seconds: float) -> None:
        self._domain_delay[origin] = max(self._domain_delay.get(origin, 0.0), seconds)

    async def _wait_turn(self, origin: str) -> None:
        lock = self._locks.setdefault(origin, asyncio.Lock())
        async with lock:
            now = self._clock()
            wait = self._next_ok.get(origin, 0.0) - now
            if wait > 0:
                await self._sleep(wait)
            self._next_ok[origin] = self._clock() + self.delay_for(origin)

    def _push_back(self, origin: str, seconds: float) -> None:
        self._next_ok[origin] = max(self._next_ok.get(origin, 0.0), self._clock() + seconds)

    async def _load_robots(self, origin: str) -> RobotFileParser | None:
        if origin in self._robots:
            return self._robots[origin]
        await self._wait_turn(origin)
        rp: RobotFileParser | None = RobotFileParser()
        try:
            resp = await self._client.get(f"{origin}/robots.txt")
            if resp.status_code in (401, 403):
                rp.parse(["User-agent: *", "Disallow: /"])  # per RFC 9309: unreachable-by-auth = disallow all
            elif resp.status_code >= 400:
                rp.parse([])  # no robots.txt -> allowed
            else:
                rp.parse(resp.text.splitlines())
        except httpx.HTTPError:
            rp = None  # cannot read robots.txt -> treat as disallowed (conservative)
        self._robots[origin] = rp
        if rp is not None:
            cd = rp.crawl_delay(self.user_agent) or rp.crawl_delay("*")
            if cd:
                self.set_min_delay_for(origin, float(cd))
        return rp

    async def can_fetch(self, url: str) -> bool:
        if not self.respect_robots:
            return True
        rp = await self._load_robots(self.origin(url))
        return rp is not None and rp.can_fetch(self.user_agent, url)

    async def request(self, method: str, url: str, **kw) -> httpx.Response:
        origin = self.origin(url)
        if self.respect_robots and not await self.can_fetch(url):
            raise RobotsDisallowed(url)
        last: httpx.Response | None = None
        for attempt in range(self.max_retries + 1):
            await self._wait_turn(origin)
            try:
                resp = await self._client.request(method, url, **kw)
            except httpx.TransportError as e:
                if attempt == self.max_retries:
                    raise FetchError(f"{url}: {e}") from e
                await self._sleep(min(self.backoff_base ** (attempt + 1), self.backoff_max))
                continue
            if _is_challenge(resp):
                raise BlockedError(f"{origin} returned {resp.status_code} (blocked/challenge)")
            if resp.status_code == 429 or resp.status_code >= 500:
                last = resp
                retry_after = resp.headers.get("retry-after", "")
                wait = float(retry_after) if retry_after.replace(".", "", 1).isdigit() else self.backoff_base ** (attempt + 1)
                wait = min(wait, self.backoff_max)
                self._push_back(origin, wait)
                if attempt == self.max_retries:
                    break
                log.warning("%s -> %s, backing off %.1fs", url, resp.status_code, wait)
                continue
            return resp
        raise FetchError(f"{url}: giving up after retries (last status {last.status_code if last else 'n/a'})")

    async def get(self, url: str, **kw) -> httpx.Response:
        return await self.request("GET", url, **kw)

    async def post(self, url: str, **kw) -> httpx.Response:
        return await self.request("POST", url, **kw)
