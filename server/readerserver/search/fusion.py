"""Reciprocal rank fusion across scene lists and profile lists (pure)."""
from dataclasses import dataclass


@dataclass
class Fused:
    work_id: str
    score: float
    scene_matches: int  # distinct scenes whose chunk results contained this work
    detail: dict[str, float]


def rrf(rank: int, k: int = 60) -> float:
    return 1.0 / (k + rank)


def fuse(
    chunk_lists: list[list[str]],
    profile_lists: dict[str, list[str]] | None = None,
    profile_weights: dict[str, float] | None = None,
    k: int = 60,
    multi_bonus: float = 0.25,
) -> list[Fused]:
    """chunk_lists: per-scene ranked work ids (best first, one entry per work).
    profile_lists: name -> ranked work ids from profile kNN (synopsis query, scenes vs profiles, ...).
    score = (sum of per-scene RRF) * (1 + multi_bonus*(distinct_scenes-1)) + sum(weight * profile RRF)."""
    profile_lists = profile_lists or {}
    profile_weights = profile_weights or {}
    scene_sum: dict[str, float] = {}
    scene_n: dict[str, int] = {}
    for lst in chunk_lists:
        seen: set[str] = set()
        for rank, wid in enumerate(lst, start=1):
            if wid in seen:
                continue
            seen.add(wid)
            scene_sum[wid] = scene_sum.get(wid, 0.0) + rrf(rank, k)
            scene_n[wid] = scene_n.get(wid, 0) + 1
    prof_sum: dict[str, float] = {}
    for name, lst in profile_lists.items():
        w = profile_weights.get(name, 1.0)
        seen = set()
        for rank, wid in enumerate(lst, start=1):
            if wid in seen:
                continue
            seen.add(wid)
            prof_sum[wid] = prof_sum.get(wid, 0.0) + w * rrf(rank, k)
    out = []
    for wid in set(scene_sum) | set(prof_sum):
        n = scene_n.get(wid, 0)
        s = scene_sum.get(wid, 0.0) * (1 + multi_bonus * max(n - 1, 0))
        p = prof_sum.get(wid, 0.0)
        out.append(Fused(wid, s + p, n, {"scenes": s, "profile": p}))
    out.sort(key=lambda f: (-f.score, f.work_id))
    return out
