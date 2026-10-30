"""Entity-resolution scoring and decisions. Pure functions over duck-typed records (SourceRecord or SourceRecordData)."""
import math
from dataclasses import dataclass, field
from typing import Literal

from ..sources.util import extract_links
from ..text import authors_match, best_title_similarity, normalize_title, version_marker

W_TITLE, W_AUTHOR, W_SYNOPSIS, W_LANG, W_YEAR, W_CHAPTERS = 0.40, 0.20, 0.22, 0.06, 0.06, 0.06
MERGE_THRESHOLD = 0.85
SEPARATE_THRESHOLD = 0.45
MIN_TITLE_FOR_MERGE = 0.80
VERDICT_SAME_MIN = 0.60

Decision = Literal["merge", "separate", "verdict"]


@dataclass
class Score:
    total: float
    title: float
    author: bool | None
    synopsis_cos: float | None
    language_match: bool | None
    year_diff: int | None
    chapter_ratio: float | None
    marker_conflict: bool = False
    notes: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {k: v for k, v in self.__dict__.items()}


def cosine(a, b) -> float | None:
    if a is None or b is None:
        return None
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na, nb = math.sqrt(sum(x * x for x in a)), math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else None


def all_titles(r) -> list[str]:
    return [r.title, *(r.alt_titles or [])]


def score_pair(a, b, synopsis_cos: float | None = None) -> Score:
    if synopsis_cos is None:
        synopsis_cos = cosine(getattr(a, "synopsis_embedding", None), getattr(b, "synopsis_embedding", None))
    title = best_title_similarity(all_titles(a), all_titles(b))
    author = authors_match(list(a.authors or []), list(b.authors or []))
    lang = None if not a.language or not b.language else a.language == b.language
    ydiff = abs(a.start_year - b.start_year) if a.start_year and b.start_year else None
    cratio = None
    if a.chapter_count and b.chapter_count:
        cratio = min(a.chapter_count, b.chapter_count) / max(a.chapter_count, b.chapter_count)

    parts: list[tuple[float, float]] = [(W_TITLE, title)]
    if author is not None:
        parts.append((W_AUTHOR, 1.0 if author else 0.0))
    if synopsis_cos is not None:
        parts.append((W_SYNOPSIS, max(0.0, min(1.0, (synopsis_cos - 0.4) / 0.45))))
    if lang is not None:
        parts.append((W_LANG, 1.0 if lang else 0.0))
    if ydiff is not None:
        parts.append((W_YEAR, 1.0 if ydiff <= 1 else 0.5 if ydiff <= 3 else 0.0))
    if cratio is not None:
        parts.append((W_CHAPTERS, 1.0 if cratio >= 0.8 else cratio))
    total = sum(w * s for w, s in parts) / sum(w for w, _ in parts)

    notes: list[str] = []
    if cross_linked(a, b):  # descriptive link (description/related URL): evidence, never identity
        total = min(1.0, total + 0.10)
        notes.append("description cross-link")
    if lang is False:
        total *= 0.7
        notes.append("original language differs")
    if author is False:
        total = min(total, 0.7)
        notes.append("authors differ")
    if ydiff is not None and ydiff > 3:
        total *= 0.85
        notes.append("start years far apart")
    ma, mb = version_marker(a.title), version_marker(b.title)
    conflict = bool(ma and mb and ma != mb)
    return Score(total, title, author, synopsis_cos, lang, ydiff, cratio, conflict, notes)


def decide(s: Score) -> Decision:
    if s.marker_conflict:
        return "verdict"  # WN vs LN: never auto-merge, ask for the typed verdict (likely RELATED_WORK)
    strong_identity = s.title >= MIN_TITLE_FOR_MERGE or (s.synopsis_cos is not None and s.synopsis_cos >= 0.88)
    if s.total >= MERGE_THRESHOLD and strong_identity and s.author is not False:
        return "merge"
    if s.total < SEPARATE_THRESHOLD:
        return "separate"
    return "verdict"


def _norm_url(u: str) -> str:
    u = u.strip().lower().split("#")[0].rstrip("/")
    return u.replace("http://", "https://").replace("://www.", "://")


def distinctive(norm: str) -> bool:
    has_cjk = any(ord(c) > 0x2E80 for c in norm)
    return len(norm.replace(" ", "")) >= (4 if has_cjk else 8)


def cross_linked(a, b) -> bool:
    """True when either record's descriptive links (related URLs / ids found in descriptions) point at the other.
    Such links also cover sequels, adaptations and LN/WN siblings, so they are only scoring evidence."""
    a_rel = {_norm_url(u) for u in (a.related_urls or [])}
    b_rel = {_norm_url(u) for u in (b.related_urls or [])}
    if (b.url and _norm_url(b.url) in a_rel) or (a.url and _norm_url(a.url) in b_rel):
        return True
    for x, y in ((a, b), (b, a)):
        linked_ids, _ = extract_links(" ".join(x.related_urls or []))
        yid = y.external_ids or {}
        if any(yid.get(k) == v for k, v in linked_ids.items()):
            return True
    return False


def hard_match(a, b) -> tuple[str, dict] | None:
    """Step 1: AUTHORITATIVE cross-source ids (a source's own id, AniList's idMal) and exact distinctive alias equality.
    Descriptive links never match here (see `cross_linked`); WN-vs-LN/version-marker conflicts never auto-merge."""
    ma, mb = version_marker(a.title), version_marker(b.title)
    version_conflict = bool(ma and mb and ma != mb)
    ida, idb = a.external_ids or {}, b.external_ids or {}
    for k in ida.keys() & idb.keys():
        if ida[k] == idb[k] and not version_conflict:
            return "hard_id", {"id": k, "value": ida[k]}
    if a.source == b.source:
        return None
    if version_conflict:
        return None  # WN vs LN of the same title: related, not identical -> goes through the typed verdict
    na = {normalize_title(t) for t in all_titles(a)}
    nb = {normalize_title(t) for t in all_titles(b)}
    shared = [t for t in na & nb if t and distinctive(t)]
    if shared and authors_match(list(a.authors or []), list(b.authors or [])) is not False:
        if not (a.language and b.language and a.language != b.language):
            return "hard_alias", {"alias": shared[0]}
    return None
