"""Title / author normalization and similarity helpers (pure functions)."""
import re
import unicodedata
from difflib import SequenceMatcher

_VERSION_MARKERS = {
    "WN": re.compile(r"[\(\[（【]\s*(?:wn|web\s*novel)\s*[\)\]）】]", re.I),
    "LN": re.compile(r"[\(\[（【]\s*(?:ln|light\s*novel)\s*[\)\]）】]", re.I),
}
_GENERIC_SUFFIX = re.compile(r"[\(\[（【]\s*(?:novel|wn|ln|web\s*novel|light\s*novel|official|completed?|raw)\s*[\)\]）】]", re.I)
_PUNCT = re.compile(r"[^\w\s]|_", re.UNICODE)
_WS = re.compile(r"\s+")


def normalize_title(title: str) -> str:
    """NFKC, lowercase, drop (WN)/(LN)/(Novel) markers, strip punctuation, collapse whitespace."""
    t = unicodedata.normalize("NFKC", title or "")
    t = _GENERIC_SUFFIX.sub(" ", t)
    t = t.lower()
    t = _PUNCT.sub(" ", t)
    return _WS.sub(" ", t).strip()


def version_marker(title: str) -> str | None:
    t = unicodedata.normalize("NFKC", title or "")
    for name, rx in _VERSION_MARKERS.items():
        if rx.search(t):
            return name
    return None


def normalize_author(name: str) -> str:
    t = unicodedata.normalize("NFKC", name or "").lower()
    t = _PUNCT.sub(" ", t)
    return _WS.sub(" ", t).strip()


def _author_keys(name: str) -> tuple[str, frozenset[str]]:
    n = normalize_author(name)
    return n.replace(" ", ""), frozenset(n.split())


def _script(name: str) -> str:
    return "cjk" if any(ord(c) >= 0x2E80 for c in name) else "latin"


def authors_match(a: list[str], b: list[str]) -> bool | None:
    """True = share an author, False = comparable names (same script) that are all different,
    None = unknown (a side is empty, or names are only in different scripts, e.g. native vs romanized)."""
    a = [x for x in a if x and x.strip()]
    b = [x for x in b if x and x.strip()]
    if not a or not b:
        return None
    comparable = False
    for x in a:
        kx, tx = _author_keys(x)
        for y in b:
            ky, ty = _author_keys(y)
            if kx and kx == ky:
                return True
            if tx and ty and tx == ty:  # "Sun Rio" vs "Rio Sun"
                return True
            if _script(x) == _script(y):
                comparable = True
    return False if comparable else None


def trigrams(s: str) -> set[str]:
    """pg_trgm-like trigram set (per word, padded)."""
    out: set[str] = set()
    for w in s.split():
        p = f"  {w} "
        out.update(p[i : i + 3] for i in range(len(p) - 2))
    return out


def trigram_similarity(a: str, b: str) -> float:
    ta, tb = trigrams(a), trigrams(b)
    if not ta or not tb:
        return 0.0
    return len(ta & tb) / len(ta | tb)


def title_similarity(a: str, b: str) -> float:
    """Similarity of two already-normalized titles in [0,1]."""
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    return 0.5 * trigram_similarity(a, b) + 0.5 * SequenceMatcher(None, a, b).ratio()


def best_title_similarity(titles_a: list[str], titles_b: list[str]) -> float:
    na = {normalize_title(t) for t in titles_a if t}
    nb = {normalize_title(t) for t in titles_b if t}
    best = 0.0
    for x in na:
        for y in nb:
            best = max(best, title_similarity(x, y))
    return best


def guess_language_from_script(text: str) -> str | None:
    """Hangul -> ko, kana -> ja; Han-only is ambiguous -> None."""
    if re.search(r"[가-힯]", text):
        return "ko"
    if re.search(r"[぀-ヿ]", text):
        return "ja"
    return None


def approx_tokens(text: str) -> int:
    cjk = len(re.findall(r"[぀-ヿ㐀-鿿가-힯]", text))
    return int(cjk * 0.8 + (len(text) - cjk) / 4) + 1
