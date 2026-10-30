import httpx
import pytest

from readerserver.crawl.http import BlockedError, FetchError, PoliteClient, RobotsDisallowed


class Clock:
    def __init__(self):
        self.t = 1000.0
        self.sleeps: list[float] = []

    def now(self):
        return self.t

    async def sleep(self, s):
        self.sleeps.append(s)
        self.t += s


def make(handler, clock, **kw):
    return PoliteClient("ReaderAppCrawler/0.1 (test)", transport=httpx.MockTransport(handler), sleep=clock.sleep, clock=clock.now, **kw)


async def test_min_delay_between_requests_same_domain_only():
    clock = Clock()
    c = make(lambda r: httpx.Response(200, text="ok"), clock, min_delay=3.0, respect_robots=False)
    await c.get("https://a.example/1")
    await c.get("https://a.example/2")
    assert clock.sleeps == [3.0]
    await c.get("https://b.example/1")  # other domain is not delayed
    assert clock.sleeps == [3.0]


async def test_user_agent_is_honest():
    seen = {}

    def h(r):
        seen["ua"] = r.headers["user-agent"]
        return httpx.Response(200)

    c = make(h, Clock(), respect_robots=False)
    await c.get("https://a.example/")
    assert seen["ua"].startswith("ReaderAppCrawler/")


ROBOTS = "User-agent: *\nDisallow: /private\nCrawl-delay: 10\n"


async def test_robots_disallow_and_crawl_delay():
    clock = Clock()
    paths = []

    def h(r):
        paths.append(r.url.path)
        return httpx.Response(200, text=ROBOTS if r.url.path == "/robots.txt" else "ok")

    c = make(h, clock, min_delay=3.0)
    with pytest.raises(RobotsDisallowed):
        await c.get("https://a.example/private/x")
    assert "/private/x" not in paths
    await c.get("https://a.example/open")
    await c.get("https://a.example/open2")
    assert max(clock.sleeps) >= 10.0  # Crawl-delay raises the per-domain delay above the 3 s default


async def test_unreadable_robots_is_conservative():
    def h(r):
        raise httpx.ConnectError("boom")

    c = make(h, Clock(), max_retries=0)
    assert not await c.can_fetch("https://a.example/x")


async def test_robots_403_means_disallow_all():
    c = make(lambda r: httpx.Response(403), Clock())
    assert not await c.can_fetch("https://a.example/x")


async def test_backoff_on_429_and_5xx_then_success():
    clock = Clock()
    codes = iter([429, 503, 200])

    def h(r):
        code = next(codes)
        return httpx.Response(code, text="ok", headers={"retry-after": "7"} if code == 429 else {})

    c = make(h, clock, min_delay=0, respect_robots=False)
    resp = await c.get("https://a.example/")
    assert resp.status_code == 200
    assert clock.sleeps[0] >= 7 - 1e-9 and len(clock.sleeps) >= 2  # Retry-After honored, then exponential


async def test_gives_up_after_retries():
    c = make(lambda r: httpx.Response(500), Clock(), min_delay=0, respect_robots=False, max_retries=2)
    with pytest.raises(FetchError):
        await c.get("https://a.example/")


async def test_403_stops_without_retry():
    n = {"c": 0}

    def h(r):
        n["c"] += 1
        return httpx.Response(403, text="forbidden")

    c = make(h, Clock(), min_delay=0, respect_robots=False)
    with pytest.raises(BlockedError):
        await c.get("https://a.example/")
    assert n["c"] == 1


async def test_cloudflare_challenge_is_blocked():
    h = lambda r: httpx.Response(503, text="<title>Just a moment...</title>", headers={"server": "cloudflare"})
    c = make(h, Clock(), min_delay=0, respect_robots=False)
    with pytest.raises(BlockedError):
        await c.get("https://a.example/")
