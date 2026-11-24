"""Profile catalogue helpers: read intrinsics and pick a close donor profile."""

from __future__ import annotations

from .core import DeviceProfile, read_profile


def profile_info(name: str, path: str) -> dict | None:
    """Return ``{"name", "fx", "fy", "cx", "cy"}`` for a profile file."""
    try:
        intr = read_profile(path).intrinsics()
    except Exception:
        return None
    if intr.get("fx") is None:
        return None
    return {
        "name": name,
        "fx": intr["fx"],
        "fy": intr["fy"],
        "cx": intr["cx"],
        "cy": intr["cy"],
    }


def closest(catalog: list[dict], fx: float, fy: float | None = None) -> dict | None:
    """Pick the catalogue entry whose focal length is closest to ``fx``.

    ``fy`` is used as a tie-breaker (relative difference is what matters, not
    absolute pixels).
    """
    if not catalog:
        return None
    best = None
    best_score = None
    for item in catalog:
        item_fx = item.get("fx")
        if not item_fx:
            continue
        score = abs(item_fx - fx) / fx
        item_fy = item.get("fy")
        if fy and item_fy:
            score += abs(item_fy - fy) / fy
        if best_score is None or score < best_score:
            best, best_score = item, score
    return best
