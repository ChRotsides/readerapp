# ReaderApp discovery server (Milestones 5a + 5b)

Python/FastAPI server for novel discovery: a polite crawler framework, official-API sources (Syosetu, AniList,
MangaUpdates, MyAnimeList), entity resolution into canonical works, Voyage embeddings in Postgres/pgvector, and the HyDE
search API (`POST /search`). Single user; the phone reaches it over Tailscale. See `docs/DESIGN.md` ("Novel search",
"Discovery corpus", "Duplicate handling").

## Layout

```
server/
  readerserver/
    config.py db.py models.py text.py services.py worker.py indexing.py works.py
    crawl/       http.py (PoliteClient: rate limit, robots, backoff, block detection), runner.py (checkpointed crawl)
    sources/     base.py (Source interface), syosetu.py anilist.py mangaupdates.py mal.py,
                 5b HTML sources: webnovel novelupdates kakuyomu royalroad scribblehub wuxiaworld tapas alphapolis munpia
                 (htmlutil.py, listing.py shared helpers; html_registry.py registers them in services.HTML_SOURCES)
    ai/          llm.py (Claude), embeddings.py (Voyage), jev.py (Jev + Claude fallback), spend.py (monthly cap)
    resolution/  scoring.py (hard matches, scoring, decisions), resolver.py (DB pipeline, unlink), merge.py, tag_seed.py
    search/      pipeline.py (HyDE search), fusion.py (RRF), retrieval.py (kNN), schemas.py
    api/main.py  FastAPI app
  alembic/       migrations      tests/  pytest (no live network)
```

## Setup

```bash
cd server
cp .env.example .env     # set API_TOKEN (long random), ANTHROPIC_API_KEY, VOYAGE_API_KEY, optional JEV_*, MAL_CLIENT_ID
docker compose up -d --build      # db (pgvector/pg17, host port 127.0.0.1:5435), api (:8000), worker
curl localhost:8000/health
```

The `api` container runs `alembic upgrade head` on start. To run migrations manually against the compose db:
`uv run alembic upgrade head` (default `DATABASE_URL=postgresql+asyncpg://readerapp:readerapp@localhost:5435/readerapp`).

Without API keys the server still starts: `/search` returns 503 until `VOYAGE_API_KEY` and `ANTHROPIC_API_KEY` are set
(clients may send `scenes` + `synopsis` themselves to skip Claude generation). A missing `MAL_CLIENT_ID` just disables
the MAL source.

All endpoints except `/health` require `Authorization: Bearer $API_TOKEN`.

## Running crawls

Scheduled crawling is **off by default**. Opt in with `ENABLED_SOURCES=syosetu,anilist,mangaupdates,mal,kakuyomu,tapas,wuxiaworld,alphapolis` (never list a source in `blocked` state; see below) (the worker
re-crawls every `CRAWL_INTERVAL_HOURS`, default 24, and continues from stored checkpoints). Manual:

```bash
# via API (the worker picks it up within ~30 s)
curl -XPOST -H "Authorization: Bearer $API_TOKEN" localhost:8000/admin/crawl/anilist
curl -XPOST -H "Authorization: Bearer $API_TOKEN" "localhost:8000/admin/crawl/anilist?fresh=true"   # ignore checkpoint
curl -H "Authorization: Bearer $API_TOKEN" localhost:8000/admin/crawl      # status, record counts, spend
# or directly (small trial run)
docker compose run --rm worker python -m readerserver.worker --once anilist --max-items 100
```

A source that returns 403/Cloudflare challenge (or whose robots.txt disallows the path) is marked `blocked` and is not
retried (we never evade); `POST /admin/crawl/{source}/unblock` re-enables it. Each crawl is followed by the shared
pipeline: synopsis embeddings -> entity resolution -> profile embeddings. Re-crawls only touch changed records.

### Endpoints

| | |
|---|---|
| `GET /health` | open |
| `POST /search` `{description, filters?, scenes?, synopsis?, tags?, limit?}` | HyDE search; returns `results`, `scenes`, `synopsis`, `tags`, `filters`, `reranked`, `notes` |
| `GET /works/{id}`, `GET /works?query=` | detail / fuzzy title search (all aliases, native titles) |
| `GET /admin/crawl`, `POST /admin/crawl/{source}[?fresh=true]`, `POST /admin/crawl/{source}/unblock` | crawl control |
| `GET /admin/works/{id}/records`, `POST /admin/records/{record_id}/unlink` | inspect links (method, confidence, evidence) / split a record out |
| `GET /admin/spend` | month-to-date spend per provider/purpose |

### Crawler safety

Link crawls default to at most **50,000 novels** and **5,000 listing pages** per job, configurable with
`LINK_MAX_NOVELS` and `LINK_MAX_LISTING_PAGES`. Hitting either guard pauses the job, preserves all remaining links
(including an unfinished listing page), and reports `novel_limit` or `listing_page_limit` in `stop_reason`.
The chapter-list guard similarly reports `toc_page_limit`. Raise the applicable limit and use
`POST /crawl-jobs/{id}/resume` to continue. Budget stops, provider deferrals, blocking, pagination loops, failures,
manual pauses and cancellations also report a distinct `stop_reason`; `error` contains the explanation.
Site jobs skip novels an earlier job already completed (the stored record carries `raw.crawl` with the job's
`chapters_per_novel` and `completed_at`; an earlier crawl covers a job only if its limit is unlimited or at least as large).
A record crawled before markers existed counts only if its `raw.job_id` job covers the new one and it has a stored chapter
embedding. Listings are always paged to the end; skipped novels are counted in the job checkpoint (`known_skipped`).
Single-novel jobs never skip.

Recurring refresh is off by default. Set `LINK_REFRESH_URLS` (comma-separated listing URLs) and the worker queues a site
job per URL, with `LINK_REFRESH_CHAPTERS_PER_NOVEL` (default 3), unless a job for that URL is queued, running, paused or
blocked, or one was updated within `LINK_REFRESH_HOURS` (default 24; this also spaces out retries after a failed job).

`done` now means `catalog_exhausted` or `novel_complete`, and checkpoints are retained even on completion.
Old completed site jobs whose checkpoint was discarded are marked `legacy_completion`. Resuming one reconstructs
the processed IDs from that job's stored novel records, rescans listing pages, and skips the processed novels without
fetching their chapters or regenerating embeddings. A new job is required to refresh an explicitly exhausted catalog.

`PoliteClient` follows redirects manually (max 5 hops); every hop is checked against the source's host allowlist
(`services.SOURCE_HOSTS`), resolved and rejected if private/loopback/link-local/CGNAT (100.64/10, Tailscale)/unique-local,
then robots-checked and rate-limited. robots.txt follows RFC 9309 (2xx parsed; 4xx except 429 = allow all; 5xx/429/network
= temporary disallow-all, retried after `robots_retry`; challenge = blocked) and is cached with a TTL. `Retry-After`
(seconds or HTTP-date) is a minimum wait; waits above 5 min defer the crawl (`crawl_state.next_eligible_at`, checkpoint
kept). A running crawl holds a heartbeat lease (`CRAWL_LEASE_SECONDS`); an expired lease means the worker died and the
run is resumed from its checkpoint. A 403/challenge during chapter sampling marks the source blocked; sampling never
starts after a blocked catalog run. Known limit: DNS is resolved for the check and again by the HTTP stack (no pinning).

## Tailscale

Tailscale is installed but logged out. Once: `sudo tailscale up`. The API container binds to loopback only
(`API_BIND=127.0.0.1`); expose it to your tailnet with TLS and no public exposure:

```bash
tailscale serve --bg 8000          # https://<machine>.<tailnet>.ts.net  ->  localhost:8000
tailscale serve status
```

The phone (on the tailnet) calls that URL with the bearer token. Do not use `tailscale funnel` (public). Alternatively set
`API_BIND=<tailscale-ip>` to bind straight to the tailnet address.

## How it works

**Entity resolution** (`resolution/`): records are never overwritten; each is linked to a `Work` through `work_links`
(method + confidence + evidence; undone links are kept with `undone_at`).
1. Hard matches: shared cross-source ids (`external_ids`, e.g. AniList `idMal` = MAL id, ids/URLs found in MangaUpdates
   descriptions), URL cross-references, exact distinctive alias equality (native CJK titles included) unless authors
   contradict, languages differ, or WN-vs-LN markers differ.
2. Candidates: pg_trgm similarity over normalized titles/aliases (NFKC, lowercase, punctuation and `(WN)/(LN)/(Novel)`
   stripped) plus synopsis-embedding kNN (cosine).
3. Score = weighted mean of the available signals: title 0.40, author 0.20 (script-aware: native vs romanized is
   "unknown", not a mismatch), synopsis cosine 0.22, language 0.06, start year 0.06, chapter ratio 0.06; penalties for
   original-language mismatch, differing authors (cap 0.7), years > 3 apart. `>= 0.85` with title >= 0.8 (or synopsis
   cosine >= 0.88) and no author conflict -> auto-merge; `< 0.45` -> separate work; otherwise (and always for WN vs LN) a
   typed verdict `SAME_WORK | DIFFERENT_WORK | RELATED_WORK` from Jev (probability >= `JEV_MIN_PROBABILITY`, default 0.7)
   with Claude Haiku structured output as the fallback (also when Jev is unset/down). SAME needs probability >= 0.6.
   RELATED -> separate work + `work_relations` row (LN_VERSION, SEQUEL, SIDE_STORY, ALT_TRANSLATION, RELATED).
4. Field merge: synopsis from the first of webnovel/wuxiaworld/syosetu/novelupdates/... then metadata APIs; tags unioned
   through `canonical_tags`/`tag_mappings` (seeded NovelUpdates-style vocabulary + synonyms incl. JP keywords; unknown tags
   become new canonical tags tagged with their origin); ratings stay per source (normalized to 0-10 in responses); all
   source URLs are returned.
5. Undo: `POST /admin/records/{id}/unlink` splits the record into its own work (method `manual`, locked from auto
   re-merge), blocks re-merging into the old work and stores a symmetric do-not-merge constraint (`link_separations`)
   against every record left behind, enforced for either side. Already-linked records in multi-record works keep their
   link on re-crawl; singletons are re-evaluated but keep their work id when they stay separate. Only authoritative ids
   (a source's own id, AniList `idMal`) hard-match; links found in descriptions/related URLs are scoring evidence only,
   and WN-vs-LN version conflicts never auto-merge. Resolution runs under a Postgres advisory lock, per-record
   savepoints, in bounded batches that embed the batch's synopses first; records whose embedding/verdict provider is
   unavailable stay pending (`resolve_reason`, `resolve_retry_at`) and are retried with backoff.

**Search** (`search/pipeline.py`): Claude Haiku 5.5 (`HYDE_MODEL`) -> filters + 2-3 scenes + hypothetical synopsis/tags.
Voyage query embeddings -> kNN over sample chunks per scene, over profiles with the synopsis and (at half weight) each
scene -> reciprocal rank fusion (k=60): per-scene RRF summed, times `1 + 0.25*(distinct scenes - 1)`, plus the weighted
profile RRF. Metadata filters (language, status, chapter range, include tags = all, exclude tags = none; tags mapped to
canonical names) are applied inside the kNN SQL. Top ~20 -> Claude Sonnet 5.5 rerank with one-line reasons. If the
budget is exhausted or Claude/rerank fails (provider errors are translated to `LLMError`), results come back in retrieval
order with a `notes` entry; a successful rerank is respected (ids de-duplicated, omitted candidates dropped). Supplied
scenes + synopsis skip HyDE (default filters) and work without Claude.

**Embeddings**: Voyage `voyage-4-large` (best multilingual quality, 1024 dims, 32k context), `input_type=document` for
profiles/chunks and `query` for searches. Chunks are ~700 tokens and stored in `sample_embeddings.chunk_text`
alongside their vectors. Voyage truncation is disabled so passages represent the full embedding input.
Run `alembic upgrade head` before deploying this code. Existing rows start with NULL text; when a chapter is
seen again, its hash and frozen legacy chunk layout allow safe backfilling without calling Voyage. Unverifiable
layouts (including possibly truncated legacy inputs) are re-embedded. Catalog samples without a source hash
must be re-embedded when fetched again; exact stored sample inputs can be reused. HNSW (cosine) indexes on all
vector columns; GIN trigram index on titles.

## Cost notes

`MONTHLY_AI_BUDGET_USD` (default 5) caps all providers per calendar month; each call is recorded in `ai_usage`
(`GET /admin/spend`). Before every Claude/Voyage/Jev call the estimated maximum cost is reserved atomically in Postgres
(`ai_spend_reservations`, advisory lock; shared by the API and worker processes), then settled against the real usage
(per `usage.iterations` attempt at each serving model's price incl. cache tokens) or released on failure; stale
reservations expire after 15 min. Embedding stops (indexing is deferred, searches get HTTP 402) when the cap is reached; rerank degrades
gracefully. Prices used: Claude Sonnet 5.5 $2/$10 and Haiku 5.5 $0.10/$0.50 per 1M tokens; Voyage
`VOYAGE_PRICE_PER_MTOK` (default 0.12, an estimate - adjust). Jev uses its API-reported billable input tokens at
`JEV_PRICE_PER_MTOK_INPUT=0.042` USD per million ([published pricing](https://docs.typesafe.ai/models)); output is free,
with no fixed per-call fee. Both choice and score requests use this rate. The obsolete `JEV_PRICE_PER_CALL` setting is
ignored. Usage costs retain 12 decimal places so small Jev calls accumulate correctly. Migration `c9f3a5b8e124`
recalculates historical Jev 1.13/1.14 costs from saved input usage and refreshes crawl-job spending totals.
A search costs roughly one Haiku call + one Sonnet rerank + one small embedding call. Large first crawls cost mostly
Voyage tokens (synopsis + profile per work): crawl one source at a time and watch `/admin/crawl`.
Sonnet calls use server-side fallback (`server-side-fallback-2026-07-01`, `fallbacks: "default"`); fallback-model billing
is not separated in the recorded cost.

## Tests

```bash
docker compose up -d db      # tests create/migrate a separate `readerapp_test` database
uv run pytest
```
No live network: Claude, Voyage and Jev are faked; API sources run on recorded fixtures in `tests/fixtures`
(the MAL fixture is hand-written from the documented response shape, since no client id was available).

## Writing an HTML-site source (Milestone 5b)

Subclass `readerserver.sources.base.Source`, build a `PoliteClient(settings.user_agent, min_delay=..., respect_robots=True)`
and register a factory in `readerserver.services.HTML_SOURCES["name"] = lambda settings: MySource(...)`:

```python
class MySource(Source):
    name = "royalroad"
    async def iter_catalog(self, checkpoint):   # async generator of CatalogBatch(records, checkpoint, done)
        ...
    async def fetch_work(self, source_id): ...                  # optional
    async def fetch_sample_chapters(self, record, n): ...       # optional: first n FREE chapters' text
```
Let `BlockedError` / `RobotsDisallowed` propagate (the runner marks the source blocked). Fill `SourceRecordData`
(`external_ids`, `related_urls`, native-script `alt_titles` and `language` drive entity resolution; put a
`_version` token into `raw` and use `self.known_versions` to skip unchanged detail pages).

## HTML-site sources (Milestone 5b): status per site

All use `PoliteClient` (robots.txt enforced, honest UA with `CRAWLER_CONTACT`, >= 3 s between requests, backoff, stop on
403/challenge). Nothing is evaded: no headless browser, UA spoofing, proxies or logins. A `BlockedError` /
`RobotsDisallowed` marks the source `blocked` (see "Running crawls"). Note: Python's `urllib.robotparser` ignores `*`/`$`
wildcards inside paths (e.g. Kakuyomu's `/works/*/episodes/*/read$`); our sources never request those pages.
Live structure was probed with <= 15 requests per site (Oct 2026); fixtures keep page structure but replace prose with
placeholders (`tests/fixtures/html/_README.txt` lists which are recorded vs hand-written).

| source | status (Oct 2026) | catalog | samples |
|---|---|---|---|
| kakuyomu | working | ranking pages (`/rankings/{genre}/entire?work_variation=long&page=N`): `__NEXT_DATA__` Apollo cache has full work data | no (episodes disallowed) |
| tapas | working | `/sitemap-novel.xml` (lastmod = version) -> `/series/{slug}/info`; novels only; `/search` never used | no |
| wuxiaworld | working | `/sitemap-novels.xml` -> `/novel/{slug}` (`__REACT_QUERY_STATE__`) | yes, only chapters flagged free (`pricingInfo.isFree`, or novel pricing model `free`), never karma-gated |
| alphapolis | working | `/novel/index?sort=24hpt&page=N` listing (title, author, abstract, tags, status, counts) | yes: public, non-rental episodes from the page-embedded TOC |
| munpia | partial / unverified | `/sitemap/sitemap_pl_serial.xml` -> `/api/v1/pc/novel-detail/{id}` (the SPA's own endpoint, robots `Allow: /`); response schema unconfirmed | no (`/novel/viewer/` disallowed) |
| webnovel | **blocked** (Cloudflare 403 challenge) | parsers written from public markup, untested live | implemented (unlocked chapters only), untested |
| novelupdates | **blocked** (Cloudflare 403 challenge) | parsers written from long-stable markup, untested live | n/a (metadata only) |
| royalroad | **blocked** (Cloudflare 403 challenge) | parsers untested live | no |
| scribblehub | **blocked** (Cloudflare 403) | parsers untested live | implemented, untested |

Syosetu also supports sample chapters now (`ncode.syosetu.com/{ncode}/{n}/`, robots Crawl-delay 1, we wait >= 3 s).
For blocked sources the design fallback applies: import their metadata manually. If a block is lifted (`POST
/admin/crawl/{source}/unblock`), re-verify the selectors against live pages first.
