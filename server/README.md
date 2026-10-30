# ReaderApp discovery server (Milestone 5a)

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
    sources/     base.py (Source interface), syosetu.py anilist.py mangaupdates.py mal.py
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

Scheduled crawling is **off by default**. Opt in with `ENABLED_SOURCES=syosetu,anilist,mangaupdates,mal` (the worker
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
   re-merge) and blocks re-merging into the old work. Already-linked records in multi-record works keep their link on
   re-crawl; singletons are re-evaluated.

**Search** (`search/pipeline.py`): Claude Haiku 5.5 (`HYDE_MODEL`) -> filters + 2-3 scenes + hypothetical synopsis/tags.
Voyage query embeddings -> kNN over sample chunks per scene, over profiles with the synopsis and (at half weight) each
scene -> reciprocal rank fusion (k=60): per-scene RRF summed, times `1 + 0.25*(distinct scenes - 1)`, plus the weighted
profile RRF. Metadata filters (language, status, chapter range, include tags = all, exclude tags = none; tags mapped to
canonical names) are applied inside the kNN SQL. Top ~20 -> Claude Sonnet 5.5 rerank with one-line reasons. If the
budget is exhausted or the rerank fails, results come back in retrieval order with a `notes` entry.

**Embeddings**: Voyage `voyage-4-large` (best multilingual quality, 1024 dims, 32k context), `input_type=document` for
profiles/chunks and `query` for searches. Chunks are ~700 tokens, vectors only; chapter text is discarded after embedding
and never stored. HNSW (cosine) indexes on all vector columns; GIN trigram index on titles.

## Cost notes

`MONTHLY_AI_BUDGET_USD` (default 5) caps all providers per calendar month; each call is recorded in `ai_usage`
(`GET /admin/spend`). Before every Claude/Voyage/Jev call the month-to-date total is checked, so one call can overshoot
slightly. Embedding stops (indexing is deferred, searches get HTTP 402) when the cap is reached; rerank degrades
gracefully. Prices used: Claude Sonnet 5.5 $2/$10 and Haiku 5.5 $0.10/$0.50 per 1M tokens; Voyage
`VOYAGE_PRICE_PER_MTOK` (default 0.12, an estimate - adjust) and `JEV_PRICE_PER_CALL` (default 0.002, an estimate).
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
