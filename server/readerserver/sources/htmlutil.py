"""Helpers shared by the HTML-site sources (5b)."""
import json
import logging
import re
from typing import Any
from xml.etree import ElementTree

from bs4 import BeautifulSoup, Tag

from ..crawl.http import PoliteClient, UnsafeURL

log = logging.getLogger(__name__)

LANG_NAMES = {
    "chinese": "zh", "japanese": "ja", "korean": "ko", "english": "en", "中国": "zh", "日本": "ja", "한국": "ko",
}


def soup(html: str) -> BeautifulSoup:
    return BeautifulSoup(html, "html.parser")


def text_of(el: Tag | None) -> str:
    return re.sub(r"[ \t ]+", " ", el.get_text(" ", strip=True)).strip() if el else ""


def block_text(el: Tag | None) -> str | None:
    """Text of a prose container keeping paragraph/line breaks (<br>, <p>)."""
    if el is None:
        return None
    for br in el.find_all("br"):
        br.replace_with("\n")
    parts = [p.get_text("", strip=False).strip() for p in el.find_all("p")] if el.find("p") else [el.get_text("", strip=False)]
    out = "\n\n".join(p for p in (re.sub(r"[ \t ]+", " ", x).strip() for x in parts) if p)
    out = re.sub(r"\n{3,}", "\n\n", out)
    return out or None


def to_int(s: str | None) -> int | None:
    m = re.search(r"\d[\d,\.]*", s or "")
    if not m:
        return None
    t = m.group(0).replace(",", "")
    try:
        return int(float(t))
    except ValueError:
        return None


def to_float(s: str | None) -> float | None:
    m = re.search(r"\d+(?:\.\d+)?", (s or "").replace(",", ""))
    return float(m.group(0)) if m else None


def year_of(s: str | None) -> int | None:
    m = re.search(r"\b(19[5-9]\d|20[0-4]\d)\b", s or "")
    return int(m.group(1)) if m else None


def lang_code(name: str | None) -> str | None:
    return LANG_NAMES.get((name or "").strip().lower())


def split_author(name: str) -> list[str]:
    """'I Eat Tomatoes (我吃西红柿)' -> full string plus each part, so native-script names bridge across sources."""
    out = [name.strip()]
    m = re.match(r"^(.*?)\s*[\(（]([^\)）]+)[\)）]\s*$", name.strip())
    if m:
        out += [m.group(1).strip(), m.group(2).strip()]
    return [a for i, a in enumerate(out) if a and a not in out[:i]]


def uniq(items) -> list:
    return [x for i, x in enumerate(items) if x and x not in items[:i]]


def json_ld(doc: BeautifulSoup) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for s in doc.find_all("script", attrs={"type": "application/ld+json"}):
        try:
            data = json.loads(s.string or s.get_text() or "")
        except ValueError:
            continue
        out += [d for d in (data if isinstance(data, list) else [data]) if isinstance(d, dict)]
    return out


def meta(doc: BeautifulSoup, prop: str) -> str | None:
    el = doc.find("meta", attrs={"property": prop}) or doc.find("meta", attrs={"name": prop})
    return el.get("content") if el and el.get("content") else None


def parse_sitemap(xml: str) -> list[tuple[str, str]]:
    """[(loc, lastmod)] from a sitemap/urlset (namespace-agnostic)."""
    out = []
    for el in ElementTree.fromstring(xml.encode() if isinstance(xml, str) else xml):
        loc = lastmod = ""
        for c in el:
            tag = c.tag.rsplit("}", 1)[-1]
            if tag == "loc":
                loc = (c.text or "").strip()
            elif tag == "lastmod":
                lastmod = (c.text or "").strip()
        if loc:
            out.append((loc, lastmod))
    return out


async def get_html(http: PoliteClient, url: str, **kw) -> str | None:
    """GET -> text, or None for 404/410 (work removed). Blocks/robots propagate as BlockedError/RobotsDisallowed."""
    try:
        resp = await http.get(url, **kw)
    except UnsafeURL as e:  # poisoned sitemap/link: skip the item instead of wedging the whole crawl
        log.warning("skipping unsafe URL: %s", e)
        return None
    if resp.status_code in (404, 410):
        return None
    resp.raise_for_status()
    return resp.text
