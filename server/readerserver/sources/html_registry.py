"""Registers the 9 HTML-site sources (5b) in `services.HTML_SOURCES`. Each factory builds its own robots-respecting
`PoliteClient` with the honest UA and a delay of at least `crawl_min_delay_seconds` (default 3 s)."""
from ..config import Settings
from ..crawl.http import PoliteClient
from .alphapolis import AlphapolisSource
from .kakuyomu import KakuyomuSource
from .munpia import MunpiaSource
from .novelupdates import NovelUpdatesSource
from .royalroad import RoyalRoadSource
from .scribblehub import ScribbleHubSource
from .tapas import TapasSource
from .webnovel import WebnovelSource
from .wuxiaworld import WuxiaworldSource

SOURCES = {
    "webnovel": WebnovelSource, "novelupdates": NovelUpdatesSource, "kakuyomu": KakuyomuSource, "royalroad": RoyalRoadSource,
    "scribblehub": ScribbleHubSource, "wuxiaworld": WuxiaworldSource, "tapas": TapasSource, "alphapolis": AlphapolisSource,
    "munpia": MunpiaSource,
}


def polite(settings: Settings, min_delay: float = 3.0) -> PoliteClient:
    return PoliteClient(settings.user_agent, min_delay=max(settings.crawl_min_delay_seconds, min_delay), respect_robots=True)


def register(registry: dict) -> None:
    for name, cls in SOURCES.items():
        registry[name] = lambda settings, cls=cls: cls(polite(settings, cls.min_delay or 3.0))
