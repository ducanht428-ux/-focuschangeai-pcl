"""Layer 3 - scheduler (who gets the expensive FaceMesh budget this frame).

A classroom has far more faces than the GPU can run FaceMesh+blendshapes on every
frame, so the scheduler picks the top-K ROI crops per tick. The rule of thumb:
measure the suspicious faces most often, keep everyone else fresh enough to notice
a change, and refuse faces too small to yield trustworthy eye ratios.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional


@dataclass
class FaceCandidate:
    track_id: int
    face_bbox: tuple          # (x0,y0,x1,y1) px in the full frame
    eye_span_px: float        # outer-eye-corner distance; the physical measurability gate
    last_measured_ms: int
    is_hot: bool              # low SEI / dozing / just bound -> wants more samples
    priority_hint: float = 0.0


class Scheduler:
    def __init__(self, max_active_faces: int = 10, min_eye_span_px: float = 12.0,
                 starve_ms: int = 1500, hot_multiplier: float = 3.0) -> None:
        self._k = max_active_faces
        self._min_span = min_eye_span_px
        self._starve_ms = starve_ms
        self._hot_mult = hot_multiplier

    def is_measurable(self, c: FaceCandidate) -> bool:
        return c.eye_span_px >= self._min_span

    def select(self, candidates: List[FaceCandidate], now_ms: int) -> List[FaceCandidate]:
        """Return the candidates to measure this frame, highest priority first."""
        eligible = [c for c in candidates if self.is_measurable(c)]
        for c in eligible:
            c.priority = self._priority(c, now_ms)  # type: ignore[attr-defined]
        eligible.sort(key=lambda c: c.priority, reverse=True)  # type: ignore[attr-defined]
        return eligible[: self._k]

    def _priority(self, c: FaceCandidate, now_ms: int) -> float:
        # How overdue this face is for a fresh measurement.
        since = now_ms - c.last_measured_ms if c.last_measured_ms else 10_000_000
        overdue = since / float(self._starve_ms)
        base = 1.0 + overdue
        if c.is_hot:
            base *= self._hot_mult
        # A larger face is a more reliable measurement, so break ties toward it.
        base += min(c.eye_span_px / 60.0, 1.0) * 0.5
        return base + c.priority_hint
