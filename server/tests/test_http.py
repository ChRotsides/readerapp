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


async def test_robots_4xx_means_allow_all_but_challenge_blocks():
    c = make(lambda r: httpx.Response(404) if r.url.path == "/robots.txt" else httpx.Response(200, text="ok"), Clock(), min_delay=0)
    assert await c.can_fetch("https://a.example/x")  # RFC 9309: 4xx = unavailable -> allow all
    ch = make(lambda r: httpx.Response(403, headers={"cf-mitigated": "challenge"}), Clock(), min_delay=0)
    assert not await ch.can_fetch("https://a.example/x")
    with pytest.raises(BlockedError):
        await ch.get("https://a.example/x")


async def test_robots_5xx_is_temporary_disallow_all_then_retried_after_ttl():
    from readerserver.crawl.http import RobotsUnavailable

    clock = Clock()
    state = {"robots": 500, "robots_calls": 0}

    def h(r):
        if r.url.path == "/robots.txt":
            state["robots_calls"] += 1
            return httpx.Response(state["robots"], text="User-agent: *\nDisallow: /private\n")
        return httpx.Response(200, text="ok")

    c = make(h, clock, min_delay=0, robots_retry=300)
    with pytest.raises(RobotsUnavailable):
        await c.get("https://a.example/open")
    with pytest.raises(RobotsUnavailable):  # cached for the retry window: no hammering robots.txt
        await c.get("https://a.example/open")
    assert state["robots_calls"] == 1
    clock.t += 301
    state["robots"] = 200
    assert (await c.get("https://a.example/open")).status_code == 200
    assert state["robots_calls"] == 2
    with pytest.raises(RobotsDisallowed):
        await c.get("https://a.example/private")
    # successful robots.txt is cached with a TTL, then refreshed
    clock.t += 7 * 3600
    await c.get("https://a.example/open")
    assert state["robots_calls"] == 3


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


# ---------------------------------------------------------------- SSRF (#1)
async def fake_resolver(host):
    return {"evil.example": ["10.0.0.5"], "tail.example": ["100.100.1.1"], "ok.example": ["93.184.216.34"],
            "v6.example": ["fd00::1"], "ll.example": ["169.254.169.254"], "lo.example": ["::1"]}.get(host, ["93.184.216.34"])


def make_ssrf(handler, **kw):
    return PoliteClient("UA", transport=httpx.MockTransport(handler), resolver=fake_resolver, min_delay=0, respect_robots=False,
                        sleep=Clock().sleep, **kw)


@pytest.mark.parametrize("url", [
    "http://127.0.0.1/x", "http://[::1]/x", "http://10.1.2.3/", "http://192.168.0.1/", "http://169.254.169.254/latest",
    "http://100.64.0.1/", "http://100.127.255.254/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/", "http://evil.example/",
    "http://tail.example/", "http://v6.example/", "http://ll.example/", "http://lo.example/", "ftp://ok.example/", "file:///etc/passwd",
    "https://user:pw@ok.example/",
])
async def test_internal_and_odd_urls_rejected_before_any_request(url):
    from readerserver.crawl.http import UnsafeURL

    called = []
    c = make_ssrf(lambda r: called.append(r) or httpx.Response(200))
    with pytest.raises(UnsafeURL):
        await c.get(url)
    assert not called


async def test_public_ip_and_cgnat_boundary():
    c = make_ssrf(lambda r: httpx.Response(200))
    assert (await c.get("http://93.184.216.34/")).status_code == 200
    assert (await c.get("http://100.128.0.1/")).status_code == 200  # just outside 100.64/10


async def test_host_allowlist_applies_to_subdomains_only():
    from readerserver.crawl.http import UnsafeURL

    c = make_ssrf(lambda r: httpx.Response(200), allowed_hosts=["site.example"])
    assert (await c.get("https://site.example/a")).status_code == 200
    assert (await c.get("https://www.site.example/a")).status_code == 200
    for bad in ("https://other.example/", "https://notsite.example/", "https://site.example.evil.example/"):
        with pytest.raises(UnsafeURL):
            await c.get(bad)


async def test_redirects_are_followed_manually_with_checks_on_each_hop():
    from readerserver.crawl.http import UnsafeURL

    seen = []

    def h(r):
        seen.append(str(r.url))
        if r.url.path == "/a":
            return httpx.Response(302, headers={"location": "/b"})
        if r.url.path == "/b":
            return httpx.Response(301, headers={"location": "http://evil.example/secret"})
        return httpx.Response(200, text="ok")

    c = make_ssrf(h, allowed_hosts=["ok.example", "evil.example"])
    with pytest.raises(UnsafeURL):  # allowlisted by name but resolves internal
        await c.get("https://ok.example/a")
    assert seen == ["https://ok.example/a", "https://ok.example/b"]
    c2 = make_ssrf(h, allowed_hosts=["ok.example"])
    with pytest.raises(UnsafeURL):  # off-allowlist hop
        await c2.get("https://ok.example/a")


async def test_redirect_hops_are_rate_limited_and_robots_checked_and_capped():
    clock = Clock()
    paths = []

    def h(r):
        paths.append((r.url.host, r.url.path))
        if r.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nDisallow: /private\n")
        if r.url.path == "/a":
            return httpx.Response(302, headers={"location": "/private/x"})
        if r.url.path == "/ok":
            return httpx.Response(302, headers={"location": "/done"})
        if r.url.path.startswith("/loop"):
            return httpx.Response(302, headers={"location": "/loop"})
        return httpx.Response(200, text="done")

    c = PoliteClient("UA", transport=httpx.MockTransport(h), resolver=fake_resolver, min_delay=3.0, sleep=clock.sleep, clock=clock.now)
    with pytest.raises(RobotsDisallowed):  # redirect target disallowed by robots
        await c.get("https://ok.example/a")
    assert ("ok.example", "/private/x") not in paths
    clock.sleeps.clear()
    assert (await c.get("https://ok.example/ok")).text == "done"
    assert clock.sleeps.count(3.0) >= 2  # both hops waited their turn
    with pytest.raises(FetchError):
        await c.get("https://ok.example/loop")
    assert sum(1 for p in paths if p[1] == "/loop") == 6  # initial + 5 hops


async def test_post_redirect_303_becomes_get_without_body():
    seen = []

    def h(r):
        seen.append((r.method, r.content))
        return httpx.Response(303, headers={"location": "/x"}) if len(seen) == 1 else httpx.Response(200)

    c = make_ssrf(h)
    await c.post("https://ok.example/p", json={"a": 1})
    assert seen[1] == ("GET", b"")


# ---------------------------------------------------------------- Retry-After (#22)
def test_parse_retry_after_forms():
    from datetime import UTC, datetime

    from readerserver.crawl.http import parse_retry_after

    now = datetime(2026, 10, 30, 12, 0, 0, tzinfo=UTC)
    assert parse_retry_after("600") == 600
    assert parse_retry_after("Fri, 30 Oct 2026 12:05:00 GMT", now) == 300
    assert parse_retry_after("Thu, 29 Oct 2026 12:05:00 GMT", now) == 0
    assert parse_retry_after("garbage") is None and parse_retry_after(None) is None


async def test_retry_after_longer_than_backoff_cap_is_honored_as_minimum():
    clock = Clock()
    codes = iter([429, 200])
    h = lambda r: httpx.Response(next(codes), headers={"retry-after": "250"})
    c = make(h, clock, min_delay=0, respect_robots=False, backoff_max=120)
    await c.get("https://a.example/")
    assert clock.sleeps[0] >= 250  # not shortened to backoff_max


async def test_retry_after_http_date_honored():
    from datetime import UTC, datetime

    clock = Clock()
    codes = iter([503, 200])
    h = lambda r: httpx.Response(next(codes), headers={"retry-after": "Fri, 30 Oct 2026 12:03:00 GMT"})
    c = make(h, clock, min_delay=0, respect_robots=False, now=lambda: datetime(2026, 10, 30, 12, 0, 0, tzinfo=UTC))
    await c.get("https://a.example/")
    assert clock.sleeps[0] >= 180


async def test_very_long_retry_after_defers_instead_of_sleeping():
    from readerserver.crawl.http import CrawlDeferred

    clock = Clock()
    h = lambda r: httpx.Response(429, headers={"retry-after": "7200"})
    c = make(h, clock, min_delay=0, respect_robots=False)
    with pytest.raises(CrawlDeferred) as ei:
        await c.get("https://a.example/")
    assert ei.value.seconds == 7200 and clock.sleeps == []
