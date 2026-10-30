import re

from ..text import guess_language_from_script  # noqa: F401  (re-export)

_TAG_RX = re.compile(r"<[^>]+>")
_MAL_RX = re.compile(r"myanimelist\.net/manga/(\d+)")
_ANILIST_RX = re.compile(r"anilist\.co/manga/(\d+)")
_SYOSETU_RX = re.compile(r"ncode\.syosetu\.com/(n\d+[a-z]{1,2})", re.I)
_MU_RX = re.compile(r"mangaupdates\.com/series(?:\.html\?id=|/)([a-z0-9]+)", re.I)
_URL_RX = re.compile(r"https?://[^\s)\]>\"']+")


def strip_html(s: str | None) -> str | None:
    if not s:
        return None
    s = re.sub(r"<br\s*/?>", "\n", s, flags=re.I)
    s = _TAG_RX.sub("", s)
    s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&#039;", "'")
    return re.sub(r"\n{3,}", "\n\n", s).strip() or None


def extract_links(text: str | None) -> tuple[dict[str, str], list[str]]:
    """Find cross-source ids and URLs mentioned in free text (descriptions often link sibling sites)."""
    ids: dict[str, str] = {}
    if not text:
        return ids, []
    urls = [u.rstrip(".,;") for u in _URL_RX.findall(text)]
    for u in urls:
        for key, rx in (("mal", _MAL_RX), ("anilist", _ANILIST_RX), ("syosetu", _SYOSETU_RX)):
            m = rx.search(u)
            if m:
                ids.setdefault(key, m.group(1).lower() if key == "syosetu" else m.group(1))
    return ids, list(dict.fromkeys(urls))
