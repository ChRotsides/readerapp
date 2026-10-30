"""Field-merge rules for a canonical Work built from its source records (pure)."""
import re
import unicodedata
from dataclasses import dataclass, field

from ..text import normalize_author, normalize_title

# Official/first-party synopsis sources first, then NovelUpdates, then aggregators/metadata APIs.
SOURCE_PRIORITY = [
    "webnovel", "wuxiaworld", "syosetu", "novelupdates", "royalroad", "scribblehub", "kakuyomu", "alphapolis",
    "tapas", "munpia", "anilist", "mal", "mangaupdates",
]


def prio(source: str) -> int:
    return SOURCE_PRIORITY.index(source) if source in SOURCE_PRIORITY else len(SOURCE_PRIORITY)


def normalize_tag(tag: str) -> str:
    t = unicodedata.normalize("NFKC", tag).lower().strip()
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]|_", " ", t)).strip()


def is_latin(s: str) -> bool:
    letters = [c for c in s if c.isalpha()]
    return bool(letters) and sum(c.isascii() for c in letters) / len(letters) > 0.7


def has_cjk(s: str) -> bool:
    return any("぀" <= c <= "鿿" or "가" <= c <= "힯" for c in s)


def canonicalize_tags(tags: list[str], source: str, mapping: dict[tuple[str, str], str], vocabulary: dict[str, str]) -> list[str]:
    out: list[str] = []
    for t in tags:
        n = normalize_tag(t)
        if not n:
            continue
        name = mapping.get((source, n)) or mapping.get(("*", n)) or vocabulary.get(n)
        if name is None:
            name = t.strip() if has_cjk(t) else " ".join(w.capitalize() for w in t.strip().split())
        if name not in out:
            out.append(name)
    return out


@dataclass
class MergedFields:
    title: str
    native_title: str | None
    aliases: list[str]
    authors: list[str]
    synopsis: str | None
    synopsis_source: str | None
    tags: list[str]
    genres: list[str]
    status: str | None
    chapter_count: int | None
    language: str | None
    start_year: int | None
    cover_url: str | None
    ratings: list[dict] = field(default_factory=list)


def merge_records(records: list, mapping: dict[tuple[str, str], str], vocabulary: dict[str, str]) -> MergedFields:
    recs = sorted(records, key=lambda r: (prio(r.source), r.id if hasattr(r, "id") and r.id else 0))
    title = next((r.title for r in recs if is_latin(r.title)), None)
    if title is None:
        title = next((t for r in recs for t in r.alt_titles if is_latin(t)), recs[0].title)
    native = next((t for r in recs for t in [r.title, *r.alt_titles] if has_cjk(t)), None)
    seen = {normalize_title(title)}
    aliases: list[str] = []
    for r in recs:
        for t in [r.title, *r.alt_titles]:
            n = normalize_title(t)
            if n and n not in seen:
                seen.add(n)
                aliases.append(t)
    authors: list[str] = []
    akeys: set[str] = set()
    for r in recs:
        for a in r.authors or []:
            k = normalize_author(a).replace(" ", "")
            if k and k not in akeys:
                akeys.add(k)
                authors.append(a)
    syn_rec = next((r for r in recs if r.synopsis and len(r.synopsis.strip()) >= 20), None) or next((r for r in recs if r.synopsis), None)
    tags: list[str] = []
    for r in recs:
        for t in canonicalize_tags(list(r.tags or []), r.source, mapping, vocabulary):
            if t not in tags:
                tags.append(t)
    genres: list[str] = []
    for r in recs:
        for g in r.genres or []:
            if g not in genres:
                genres.append(g)
    years = [r.start_year for r in recs if r.start_year]
    chapters = [r.chapter_count for r in recs if r.chapter_count]
    return MergedFields(
        title=title,
        native_title=native if native and native != title else None,
        aliases=aliases,
        authors=authors,
        synopsis=syn_rec.synopsis if syn_rec else None,
        synopsis_source=syn_rec.source if syn_rec else None,
        tags=tags,
        genres=genres,
        status=next((r.status for r in recs if r.status), None),
        chapter_count=max(chapters) if chapters else None,
        language=next((r.language for r in recs if r.language), None),
        start_year=min(years) if years else None,
        cover_url=next((r.cover_url for r in recs if r.cover_url), None),
    )


def normalized_rating(r) -> float | None:
    """0-10 scale."""
    if r.rating is None or not r.rating_scale:
        return None
    return round(10.0 * r.rating / r.rating_scale, 2)


def ratings_for(records: list) -> list[dict]:
    out = []
    for r in sorted(records, key=lambda r: prio(r.source)):
        n = normalized_rating(r)
        if n is not None:
            out.append({"source": r.source, "rating": r.rating, "scale": r.rating_scale, "votes": r.rating_votes, "normalized": n})
    return out
