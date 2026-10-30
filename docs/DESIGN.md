# ReaderApp — Design

Personal Android app for reading web novels scraped from sites the user reads on (Webnovel and others),
with on-device TTS, AI grammar fixing, AI translation, highlight Q&A, and description-based novel search.
Single user, sideloaded APK (no Play Store), target device Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2).

## Stack
- Kotlin, Jetpack Compose (Material 3), single-activity, MVVM + coroutines/Flow.
- Room for storage, DataStore + Android Keystore (encrypted) for API keys and settings.
- OkHttp + Jsoup for HTTP fetching, Android WebView for JS-heavy / logged-in sites.
- Readability4J as generic content-extraction fallback.
- WorkManager for background jobs (update checks, prefetch, AI processing queue).
- Hilt for DI. kotlinx.serialization for JSON.
- minSdk 31, target/compile SDK 36.

## Package layout (`com.readerapp`)
```
data/db        Room entities, DAOs, database
data/repo      Repositories (NovelRepository, ChapterRepository, AdapterRepository)
data/settings  Encrypted settings / API keys
scrape/        Fetchers (HttpFetcher, WebViewFetcher), adapter runtime, generic extractor, cleaner
scrape/heal    Self-healing adapter system (validation, Jev verdicts, LLM repair loop)
ai/            Claude client, Jev client, grammar fix, translation, glossary, Q&A, summaries
tts/           Kokoro (sherpa-onnx) engine, playback service (Media3 MediaSessionService)
search/        HyDE novel search client
ui/            Compose screens: library, novel detail, reader, in-app browser, settings, search
```

## Data model (core)
- `Novel`: id, title, author, coverUrl, synopsis, sourceUrl, domain, language, followed, lastReadChapterId, scrollPos.
- `Chapter`: id, novelId, index, title, url, status (NOT_FETCHED | FETCHED | LOCKED | FAILED), fetchedAt.
- `ChapterText`: chapterId, variant (ORIGINAL | FIXED | TRANSLATED), paragraphs (JSON list of strings), model, createdAt.
  Chapters are always stored as a list of paragraphs, never raw HTML (reader, TTS sync, AI all work per paragraph).
- `GlossaryEntry`: novelId, source term, target term, note.
- `ChapterSummary`: chapterId, summary (used for "story so far" Q&A context, spoiler-safe: only chapters <= current).
- `SiteAdapter`: domain, version, kind (JSON | JS), code, writtenBy (model id), status (ACTIVE | BROKEN | RETIRED),
  successCount, failCount, lastSuccessAt, fixtureId.
- `AdapterFixture`: id, domain, url, trimmedHtml, expectedOutput (JSON).

## Scraping
Two fetch modes sharing cookies via `CookieManager`:
1. **HTTP** (OkHttp + Jsoup): fast, background-friendly.
2. **WebView**: runs site JS, keeps logins, user can solve bot checks once in the in-app browser.
The in-app browser lets the user log in and browse any site; "Add to library" imports the novel/chapter from the page.

Politeness: per-domain rate limit (>= 2–3 s between requests), never refetch stored chapters, cache.
Respect access: only chapters the user can already read. Locked/paywalled chapters are marked LOCKED, never bypassed.
If a site deliberately obfuscates text, do not try to defeat it.

### Site adapters — LLM-written, self-healing, versioned
- Adapters are **data, not compiled code**:
  - `JSON` (preferred): CSS selectors for title / paragraphs / next / prev / chapter list / novel info + regex cleanup patterns.
  - `JS` (escape hatch): an extraction function run in a sandbox (QuickJS or an isolated WebView context) with
    **no network/file access**; input = page HTML, output = `{title, paragraphs, nextUrl, prevUrl}` validated against schema.
- New site: Sonnet 5.5 (`claude-sonnet-5-5`) writes v1 from trimmed HTML (scripts/styles/svg/tracking stripped).
- Break detection:
  1. Cheap rule checks (empty, too short, too few paragraphs, identical to previous chapter, missing title).
  2. **Jev (TypeSafe AI)** typed classification with calibrated probability:
     `VALID_CHAPTER | TRUNCATED | NAVIGATION_OR_JUNK | WRONG_CHAPTER | LOGIN_WALL | LOCKED_CHAPTER | BOT_CHECK | ERROR_PAGE`.
     Fallback to Haiku with the same typed schema if Jev is unavailable.
  - Thresholds: VALID >= 0.90 → accept. Adapter problem (TRUNCATED/NAVIGATION_OR_JUNK/WRONG_CHAPTER) >= 0.80 → count failure.
    Page problem (LOGIN_WALL/LOCKED_CHAPTER/BOT_CHECK/ERROR_PAGE) >= 0.80 → handle the page issue, never touch the adapter.
    In between → retry once, then save but flag.
  - Two consecutive adapter failures = broken.
- Repair: try all older versions newest-first; if one passes, reactivate it. Else Haiku 5.5 (`claude-haiku-5-5`) writes
  v(n+1) given old code + failing trimmed page + error + example good output; validate on the failing chapter + 1–2 others
  (Jev must say VALID with high confidence); up to 3 attempts with error feedback; then escalate to Sonnet 5.5 with the
  same loop; then fall back to Readability4J and notify the user.
- One repair job at a time per domain (mutex). Automatic, with a notification and a monthly spend cap.
- Model output uses structured output / tool schemas so adapters are always well-formed.

## AI features (Anthropic API, called directly from the device with the user's key)
- Grammar fix: per chapter, paragraph-aligned output, cached as FIXED variant; reader can toggle original/fixed.
- Translation: per chapter, with per-novel glossary injected and grown as new names/terms appear; cached as TRANSLATED.
- Highlight Q&A: selected text + current chapter + running "story so far" summaries of earlier chapters only (no spoilers).
- Default to Haiku for bulk work (fix/translate/summaries), Sonnet for Q&A and adapter authoring.

## TTS
- Kokoro-82M via sherpa-onnx, on device, full precision. Model downloaded on first use (not bundled in APK).
- Media3 `MediaSessionService` foreground service: lock-screen controls, keeps generating while screen is off.
- Paragraph/sentence-level synthesis, generate ahead, cache audio per chapter, sentence highlighting in reader.
- TTS reads whichever variant the reader shows (translated/fixed/original).

## Novel search (HyDE)
- User describes a novel → Claude extracts filters and writes 2–3 distinct hypothetical scenes (setup / signature trope /
  tone), webnovel style, no character names, chunk-sized.
- Each scene embedded → vector search over the server-side chunk corpus → aggregate per novel (reward novels that match
  multiple distinct scenes, e.g. reciprocal rank fusion) + blend novel-profile score → Claude reranks top ~20 with reasons.
- Generated scenes are shown to the user and are editable to refine the search.
- Corpus/vector DB lives on a small server (not on device). Client is behind an interface so the backend can come later.

### Discovery corpus (server-side crawler)
- Purpose: discover novels the user hasn't read yet. No corpus exists yet — it is built by a polite server-side crawler.
- Sources (all in scope, rough priority order):
  - Catalog sites (crawled politely): **Webnovel** (required), NovelUpdates, Kakuyomu, Royal Road, Scribble Hub,
    Wuxiaworld, Tapas (novels), Alphapolis (crawl-delay 3), Munpia (catalog only; viewer disallowed).
  - Official metadata APIs (preferred over crawling when available): Syosetu novel API, AniList GraphQL (format NOVEL),
    MyAnimeList API v2 (light novels / novels), MangaUpdates API (type Novel).
  - Excluded: Wattpad and Novelpia (robots.txt disallows all crawling), Qidian/JJWXC/KakaoPage/Naver Series (app-only,
    heavy anti-scraping), pirate/mirror aggregator sites. Inkitt: robots.txt unreadable — verify before adding.
  - No crawlers/adapters are built that target pirate/mirror aggregator sites. The in-app browser stays general (no blocklist).

### Duplicate handling (entity resolution)
- Never overwrite source data: each site's `SourceRecord` is kept as crawled; records are *linked* to a canonical `Work`.
- Step 1, hard matches: cross-source IDs (AniList↔MAL ids, MangaUpdates cross-links) and alias lists (NovelUpdates /
  AniList / MAL alternate titles incl. native CJK titles → bridge to Syosetu, Kakuyomu, Munpia, etc.).
- Step 2, candidates: normalized titles (lowercase, strip punctuation, drop "(WN)/(LN)", Unicode NFKC) in a trigram
  index over all aliases + synopsis-embedding nearest neighbours.
- Step 3, scoring: best alias title similarity, author match (incl. native script), synopsis cosine, original language,
  start year, chapter count. High → auto-merge, low → separate, middle → **Jev** typed verdict
  `SAME_WORK | DIFFERENT_WORK | RELATED_WORK` with probability; Claude as fallback when Jev is unsure.
- RELATED_WORK (WN vs LN version, sequel, side story, alternate translation) → relation link, not a merge.
- Field merge: synopsis from official source first (Webnovel/Wuxiaworld/Syosetu), then NovelUpdates; tags unioned and
  mapped to a canonical tag vocabulary (NovelUpdates tags as base); ratings kept per source, normalized; all URLs kept.
- Every link stores method + confidence; merges are reversible (unlink, no data loss). Re-crawls run the same pipeline.
- Search results are grouped by `Work`.
- Per catalog novel store **metadata only**: title, author, synopsis, tags, genres, rating, a few review snippets, source URL
  → embedded as the novel profile. Optionally embed the first 2–3 **free** chapters and store their exact input chunks
  alongside the vectors. Never paid/locked chapters. Library chapter chunks use the same storage pipeline.
- Respect robots.txt and site terms, low request rate, cache, back off on blocks; if a site actively blocks the crawler,
  don't evade it — drop to metadata the user imports manually.
- HyDE matches on two levels: Claude-written hypothetical **scenes** → sample-chapter vectors, and a Claude-written
  hypothetical **synopsis + tag list** → novel profiles. Blend, then Claude reranks.
