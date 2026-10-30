"""Polite HTTP client: per-domain rate limiting, robots.txt (RFC 9309 semantics, TTL-cached), honest UA, exponential
backoff on 429/5xx honoring Retry-After (as a minimum; very long waits defer the crawl instead of sleeping), manual
redirect handling (every hop gets the host allowlist, SSRF check, robots and rate limiting), and stop-don't-evade on
403 / Cloudflare challenges."""
import asyncio
import ipaddress
import logging
import socket
import time
from collections.abc import Awaitable, Callable, Iterable
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from urllib.parse import urljoin, urlsplit
from urllib.robotparser import RobotFileParser

import httpx

log = logging.getLogger(__name__)

REDIRECT_CODES = (301, 302, 303, 307, 308)
CGNAT = ipaddress.ip_network("100.64.0.0/10")  # RFC 6598 shared space; includes Tailscale


class BlockedError(Exception):
    """The site actively blocks us (403 / challenge). The caller must stop and record the source as blocked."""


class ResponseTooLarge(Exception):
    """The body exceeded the caller's `max_bytes` cap (the connection is closed without reading the rest)."""


class RobotsDisallowed(Exception):
    pass


class FetchError(Exception):
    pass


class UnsafeURL(FetchError):
    """URL rejected before any request: wrong scheme, host outside the source's allowlist, or internal destination."""


class CrawlDeferred(Exception):
    """The server asked us to wait longer than we are willing to sleep inline. The crawl should be resumed later
    from its checkpoint, no earlier than `seconds` from now."""

    def __init__(self, message: str, seconds: float):
        super().__init__(message)
        self.seconds = seconds


class RobotsUnavailable(CrawlDeferred):
    """robots.txt could not be obtained (5xx / 429 / network error): crawl nothing on this origin until it can."""


def ip_is_internal(ip: str) -> bool:
    a = ipaddress.ip_address(ip.split("%")[0])
    if isinstance(a, ipaddress.IPv6Address) and a.ipv4_mapped:
        a = a.ipv4_mapped
    if isinstance(a, ipaddress.IPv6Address) and a.sixtofour:
        a = a.sixtofour
    return (a.is_private or a.is_loopback or a.is_link_local or a.is_multicast or a.is_reserved or a.is_unspecified
            or (isinstance(a, ipaddress.IPv4Address) and a in CGNAT))


async def system_resolve(host: str) -> list[str]:
    infos = await asyncio.get_running_loop().getaddrinfo(host, None, type=socket.SOCK_STREAM)
    return sorted({i[4][0] for i in infos})


def parse_retry_after(value: str | None, now: datetime | None = None) -> float | None:
    """Retry-After as seconds (delta-seconds or HTTP-date); None if absent/unparseable."""
    if not value or not value.strip():
        return None
    v = value.strip()
    try:
        return max(0.0, float(v))
    except ValueError:
        pass
    try:
        dt = parsedate_to_datetime(v)
    except (TypeError, ValueError):
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return max(0.0, (dt - (now or datetime.now(UTC))).total_seconds())


def _is_challenge(resp: httpx.Response, plain_403: bool = True) -> bool:
    if plain_403 and resp.status_code == 403:
        return True
    if resp.headers.get("cf-mitigated", "").lower() == "challenge":
        return True
    if resp.status_code in (403, 429, 503) and resp.headers.get("server", "").lower() == "cloudflare":
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
        allowed_hosts: Iterable[str] | None = None,
        resolver: Callable[[str], Awaitable[list[str]]] | None = None,
        max_redirects: int = 5,
        max_inline_wait: float = 300.0,
        robots_ttl: float = 6 * 3600.0,
        robots_retry: float = 300.0,
        now: Callable[[], datetime] = lambda: datetime.now(UTC),
    ):
        self.user_agent = user_agent
        self.min_delay = min_delay
        self.respect_robots = respect_robots
        self.max_retries = max_retries
        self.backoff_base = backoff_base
        self.backoff_max = backoff_max
        self.max_redirects = max_redirects
        self.max_inline_wait = max_inline_wait
        self.robots_ttl = robots_ttl
        self.robots_retry = robots_retry
        self.allowed_hosts: set[str] | None = {h.lower() for h in allowed_hosts} if allowed_hosts is not None else None
        # Custom transports (tests) have no real DNS; a real client resolves and vets every destination.
        self._resolver = resolver if resolver is not None else (None if transport is not None else system_resolve)
        self._sleep = sleep
        self._clock = clock
        self._now = now
        self._client = httpx.AsyncClient(
            headers={"User-Agent": user_agent, "Accept-Encoding": "gzip"},
            transport=transport,
            timeout=timeout,
            follow_redirects=False,
        )
        self._locks: dict[str, asyncio.Lock] = {}
        self._next_ok: dict[str, float] = {}
        # origin -> (parser | None, expires_at, error). parser None = disallow-all (temporarily unavailable).
        self._robots: dict[str, tuple[RobotFileParser | None, float, CrawlDeferred | BlockedError | None]] = {}
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

    def host_allowed(self, host: str) -> bool:
        if self.allowed_hosts is None:
            return True
        host = host.lower().rstrip(".")
        return any(host == h or host.endswith("." + h) for h in self.allowed_hosts)

    async def check_url(self, url: str) -> None:
        """SSRF guard, applied to the initial URL and to every redirect hop."""
        p = urlsplit(url)
        if p.scheme not in ("http", "https") or not p.hostname:
            raise UnsafeURL(f"{url}: only absolute http(s) URLs are fetched")
        if p.username or p.password:
            raise UnsafeURL(f"{url}: credentials in URL")
        host = p.hostname.lower().rstrip(".")
        if not self.host_allowed(host):
            raise UnsafeURL(f"{url}: host {host} is not on this source's allowlist")
        try:
            ipaddress.ip_address(host)
            ips = [host]
        except ValueError:
            if self._resolver is None:
                return
            try:
                ips = await self._resolver(host)
            except OSError as e:
                raise FetchError(f"{url}: cannot resolve {host}: {e}") from e
        if not ips:
            raise FetchError(f"{url}: {host} resolved to no addresses")
        for ip in ips:
            if ip_is_internal(ip):
                raise UnsafeURL(f"{url}: {host} resolves to internal address {ip}")

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

    # ---------------------------------------------------------------- robots.txt (RFC 9309 section 2.3.1)
    async def _fetch_robots_text(self, origin: str) -> httpx.Response:
        url = f"{origin}/robots.txt"
        for _ in range(self.max_redirects + 1):
            await self.check_url(url)
            await self._wait_turn(self.origin(url))
            resp = await self._client.get(url)
            loc = resp.headers.get("location")
            if resp.status_code in REDIRECT_CODES and loc:
                url = urljoin(url, loc)
                continue
            return resp
        raise FetchError(f"{origin}/robots.txt: too many redirects")

    async def _load_robots(self, origin: str) -> RobotFileParser | None:
        cached = self._robots.get(origin)
        if cached and cached[1] > self._clock():
            if cached[2] is not None:
                raise cached[2]
            return cached[0]
        rp: RobotFileParser | None = RobotFileParser()
        ttl, err = self.robots_ttl, None
        try:
            resp = await self._fetch_robots_text(origin)
            if _is_challenge(resp, plain_403=False):
                rp, ttl, err = None, self.robots_retry, BlockedError(f"{origin} robots.txt returned a challenge")
            elif resp.status_code == 429 or resp.status_code >= 500:
                wait = parse_retry_after(resp.headers.get("retry-after"), self._now())
                ttl = max(self.robots_retry, wait or 0.0)
                rp, err = None, RobotsUnavailable(f"{origin} robots.txt unavailable ({resp.status_code})", ttl)
            elif resp.status_code >= 400:
                rp.parse([])  # 4xx (not 429): "unavailable" -> crawler may access everything
            elif resp.status_code >= 300:
                rp, err = None, RobotsUnavailable(f"{origin} robots.txt redirect without target", self.robots_retry)
                ttl = self.robots_retry
            else:
                rp.parse(resp.text.splitlines())
        except UnsafeURL:
            raise
        except (httpx.HTTPError, FetchError) as e:
            rp, ttl, err = None, self.robots_retry, RobotsUnavailable(f"{origin} robots.txt unreadable: {e}", self.robots_retry)
        self._robots[origin] = (rp, self._clock() + ttl, err)
        if err is not None:
            raise err
        assert rp is not None
        cd = rp.crawl_delay(self.user_agent) or rp.crawl_delay("*")
        if cd:
            self.set_min_delay_for(origin, float(cd))
        return rp

    async def can_fetch(self, url: str) -> bool:
        """False while robots.txt disallows the URL or cannot be obtained (temporary disallow-all)."""
        if not self.respect_robots:
            return True
        try:
            rp = await self._load_robots(self.origin(url))
        except (RobotsUnavailable, BlockedError):
            return False
        return rp is not None and rp.can_fetch(self.user_agent, url)

    async def _check_robots(self, url: str) -> None:
        if not self.respect_robots:
            return
        rp = await self._load_robots(self.origin(url))  # raises RobotsUnavailable / BlockedError
        if rp is None or not rp.can_fetch(self.user_agent, url):
            raise RobotsDisallowed(url)

    # ---------------------------------------------------------------- requests
    async def _send(self, method: str, url: str, kw: dict) -> httpx.Response:
        origin = self.origin(url)
        last: httpx.Response | None = None
        for attempt in range(self.max_retries + 1):
            await self._wait_turn(origin)
            try:
                resp = await self._request_once(method, url, kw)
            except ResponseTooLarge as e:
                raise FetchError(f"{url}: {e}") from e
            except httpx.TransportError as e:
                if attempt == self.max_retries:
                    raise FetchError(f"{url}: {e}") from e
                await self._sleep(min(self.backoff_base ** (attempt + 1), self.backoff_max))
                continue
            if _is_challenge(resp):
                raise BlockedError(f"{origin} returned {resp.status_code} (blocked/challenge)")
            if resp.status_code == 429 or resp.status_code >= 500:
                last = resp
                server_wait = parse_retry_after(resp.headers.get("retry-after"), self._now())
                if server_wait is not None and server_wait > self.max_inline_wait:
                    self._push_back(origin, server_wait)
                    raise CrawlDeferred(f"{url} -> {resp.status_code}, server asked to wait {server_wait:.0f}s", server_wait)
                wait = min(self.backoff_base ** (attempt + 1), self.backoff_max)
                if server_wait is not None:
                    wait = max(wait, server_wait)  # the server's request is a minimum, never shortened
                self._push_back(origin, wait)
                if attempt == self.max_retries:
                    break
                log.warning("%s -> %s, backing off %.1fs", url, resp.status_code, wait)
                continue
            return resp
        raise FetchError(f"{url}: giving up after retries (last status {last.status_code if last else 'n/a'})")

    async def _request_once(self, method: str, url: str, kw: dict) -> httpx.Response:
        """One request. With `max_bytes` the body is streamed and read only if the content type is text-like and it stays
        under the cap (non-text bodies come back empty; oversize ones raise ResponseTooLarge)."""
        max_bytes = kw.get("max_bytes")
        kw = {k: v for k, v in kw.items() if k != "max_bytes"}
        if max_bytes is None:
            return await self._client.request(method, url, **kw)
        req = self._client.build_request(method, url, **kw)
        resp = await self._client.send(req, stream=True)
        try:
            # the body is re-wrapped already decoded, so drop the headers that describe the wire encoding
            headers = httpx.Headers([(k, v) for k, v in resp.headers.multi_items() if k.lower() not in ("content-encoding", "content-length", "transfer-encoding")])
            ctype = resp.headers.get("content-type", "text/html").lower()
            if resp.status_code < 300 and not any(t in ctype for t in ("html", "text", "xml", "json")):
                return httpx.Response(resp.status_code, headers=headers, content=b"", request=req)
            if int(resp.headers.get("content-length", 0) or 0) > max_bytes:
                raise ResponseTooLarge(f"body larger than {max_bytes} bytes")
            data = bytearray()
            async for chunk in resp.aiter_bytes():
                data += chunk
                if len(data) > max_bytes:
                    raise ResponseTooLarge(f"body larger than {max_bytes} bytes")
            return httpx.Response(resp.status_code, headers=headers, content=bytes(data), request=req)
        finally:
            await resp.aclose()

    async def request(self, method: str, url: str, **kw) -> httpx.Response:
        for _ in range(self.max_redirects + 1):
            await self.check_url(url)
            await self._check_robots(url)
            resp = await self._send(method, url, kw)
            loc = resp.headers.get("location")
            if resp.status_code in REDIRECT_CODES and loc:
                url = urljoin(url, loc)
                if resp.status_code == 303 or (resp.status_code in (301, 302) and method != "GET"):
                    method = "GET"
                    kw = {k: v for k, v in kw.items() if k not in ("json", "data", "content", "files")}
                continue
            return resp
        raise FetchError(f"{url}: too many redirects (max {self.max_redirects})")

    async def get(self, url: str, **kw) -> httpx.Response:
        return await self.request("GET", url, **kw)

    async def post(self, url: str, **kw) -> httpx.Response:
        return await self.request("POST", url, **kw)
