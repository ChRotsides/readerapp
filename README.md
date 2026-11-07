# ReaderApp

**A personal Android reader for web novels, with LLM-written self-healing site adapters, on-device TTS, AI translation and a semantic discovery server.**

ReaderApp reads web novels from the sites you already use, keeps them offline, fixes grammar, translates with a per-novel glossary, answers questions about what you've read (spoiler-safe), and reads chapters aloud on the phone. A companion server crawls public catalogues politely and lets you find a novel by describing it.

## AI engineering highlights

- **Self-healing scrapers written by LLMs.** Site adapters are data, not compiled code: CSS-selector JSON (or a sandboxed JS function with no network or file access). Claude writes v1 from trimmed HTML; when a page stops parsing, a typed classifier (Jev, with a Claude fallback) decides whether the *adapter* broke or the *page* changed (login wall, locked chapter, bot check). Only adapter failures trigger repair: older versions are retried first, then a Haiku → Sonnet repair loop with error feedback, validated on several chapters before activation. Every adapter is versioned with fixtures and a monthly spend cap.
- **Structured outputs everywhere.** Adapters, verdicts and paragraph-aligned grammar fixes and translations all use typed schemas, so model output is always well-formed.
- **Discovery search with HyDE + pgvector.** "A tired office worker reborn in a fantasy world with a cooking skill" → Claude generates hypothetical synopses and scenes → Voyage embeddings → kNN in Postgres/pgvector → reciprocal-rank fusion → rerank.
- **Entity resolution.** The same work from Syosetu, AniList, MangaUpdates, MyAnimeList and HTML sources is merged into one canonical record via hard IDs, aliases, trigram matching on native titles, synopsis kNN and typed same-work verdicts.
- **On-device TTS.** Kokoro-82M through sherpa-onnx, with background playback and sentence highlighting.
- **Hardened server.** SSRF guards with manual redirects, RFC 9309 robots.txt, per-domain rate limits, crawl leases, advisory locks, and cross-process spend reservations.

## Architecture

```
 Android app (Kotlin, Compose)                         Discovery server (Python, FastAPI)
 ┌───────────────────────────────────┐                  ┌────────────────────────────────────┐
 │ reader · library · in-app browser │ ──bearer token─► │ /search  HyDE → Voyage → pgvector  │
 │ adapters: JSON / sandboxed JS     │                  │          → RRF → rerank            │
 │ heal: Jev verdicts → LLM repair   │                  │ crawl: API + polite HTML sources   │
 │ AI: fix · translate · Q&A · notes │                  │ resolve: works, aliases, relations │
 │ TTS: Kokoro via sherpa-onnx       │                  │ Postgres 17 + pgvector, worker     │
 └───────────────────────────────────┘                  └────────────────────────────────────┘
```

Full design: [`docs/DESIGN.md`](docs/DESIGN.md). Server details: [`server/README.md`](server/README.md).

## Getting started

### Android app

Open the project in Android Studio (JDK 17, Android SDK 36, minSdk 31) and run the `app` configuration, or:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

Add your Anthropic API key in the app's settings; it's stored encrypted with a non-exportable Android Keystore key. The TTS model downloads on first use.

### Discovery server (optional)

```bash
cd server
cp .env.example .env            # set API_TOKEN, ANTHROPIC_API_KEY, VOYAGE_API_KEY
docker compose up -d --build    # Postgres/pgvector, API on :8000, worker
uv run pytest                   # needs the compose database
```

Point the app at the server in Settings. Scheduled crawling is off by default; see `server/README.md`.

## Content and copyright

This repository contains **code only**. It ships no novels, chapters or synopses: test fixtures keep each site's markup but replace all story text with placeholders.

ReaderApp is built for personal reading of content you already have access to. It never bypasses paywalls or locked chapters (they're marked `LOCKED`), never defeats deliberate obfuscation, respects robots.txt, rate-limits every domain, and treats a Cloudflare challenge or 403 as "blocked", not something to evade. You're responsible for complying with each site's terms of service.

## License

ReaderApp is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE): free for personal, research, education and other noncommercial use. It is source-available, not an OSI-approved open-source license.

**Commercial use requires a separate license.** Contact Charalambos Rotsides at [lhampis@gmail.com](mailto:lhampis@gmail.com).

Third-party components keep their own licenses, including sherpa-onnx (Apache-2.0, `app/libs/`) and the Kokoro TTS model, which is downloaded at runtime.
