"""Low-level numeric helpers, ported from FocusCore.kt / HabitModel.kt.

These are the building blocks the engines are tuned against, so the arithmetic is
kept identical (same constants, same clamps) rather than "equivalent".
"""
from __future__ import annotations

import math
from typing import List, Optional

WINDOW_STALL_MS = 700  # FocusCore.kt: a gap wider than this is a stall, not a reading.


class RunningStat:
    """Welford online mean/std - matches RunningStat in HabitModel.kt."""

    __slots__ = ("n", "mean", "_m2")

    def __init__(self) -> None:
        self.n = 0
        self.mean = 0.0
        self._m2 = 0.0

    def add(self, x: float) -> None:
        self.n += 1
        d = x - self.mean
        self.mean += d / self.n
        self._m2 += d * (x - self.mean)

    def std(self) -> float:
        return math.sqrt(self._m2 / self.n) if self.n > 1 else 0.0


class OneEuroFilter:
    """One Euro filter (FocusCore.kt): smooth when still, responsive when moving."""

    def __init__(self, min_cutoff: float, beta: float, d_cutoff: float = 1.0) -> None:
        self._min_cutoff = min_cutoff
        self._beta = beta
        self._d_cutoff = d_cutoff
        self._initialized = False
        self._prev_raw = 0.0
        self._prev_filtered = 0.0
        self._prev_deriv = 0.0
        self._prev_time_ms = 0

    def reset(self) -> None:
        self._initialized = False

    @staticmethod
    def _alpha(cutoff: float, dt_seconds: float) -> float:
        tau = 1.0 / (2.0 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / dt_seconds)

    def filter(self, value: float, now_ms: int) -> float:
        if not self._initialized:
            self._initialized = True
            self._prev_raw = value
            self._prev_filtered = value
            self._prev_deriv = 0.0
            self._prev_time_ms = now_ms
            return value

        dt = max(1, now_ms - self._prev_time_ms) / 1000.0
        self._prev_time_ms = now_ms

        deriv = (value - self._prev_raw) / dt
        a_d = self._alpha(self._d_cutoff, dt)
        deriv_hat = a_d * deriv + (1.0 - a_d) * self._prev_deriv

        cutoff = self._min_cutoff + self._beta * abs(deriv_hat)
        a = self._alpha(cutoff, dt)
        filtered = a * value + (1.0 - a) * self._prev_filtered

        self._prev_raw = value
        self._prev_deriv = deriv_hat
        self._prev_filtered = filtered
        return filtered


class WindowRatio:
    """Fraction of window time a flag held (FocusCore.kt), 60 one-second buckets.

    Used for PERCLOS and the distraction ratio. Returns None ("not measured enough")
    rather than a fake 0% while the window holds fewer than ``min_sample_ms`` samples.
    """

    def __init__(
        self,
        buckets: int,
        min_sample_ms: int,
        cap_frame_ms: int = 200,
        ref_frame_ms: Optional[int] = None,
    ) -> None:
        self._buckets = buckets
        self._min_sample_ms = min_sample_ms
        self._cap_frame_ms = cap_frame_ms
        self._ref_frame_ms = ref_frame_ms if ref_frame_ms is not None else cap_frame_ms // 3
        self._flagged_ms = [0] * buckets
        self._total_ms = [0] * buckets
        self._bucket_second = [-1] * buckets
        self._last_frame_ms = 0

    def reset(self) -> None:
        self._flagged_ms = [0] * self._buckets
        self._total_ms = [0] * self._buckets
        self._bucket_second = [-1] * self._buckets
        self._last_frame_ms = 0

    def pause(self) -> None:
        self._last_frame_ms = 0

    def record(self, now_ms: int, flagged: bool) -> None:
        raw_dt = 0 if self._last_frame_ms == 0 else now_ms - self._last_frame_ms
        self._last_frame_ms = now_ms
        if raw_dt <= 0 or raw_dt > WINDOW_STALL_MS:
            return
        dt = self._ref_frame_ms if raw_dt > self._ref_frame_ms else min(raw_dt, self._cap_frame_ms)
        if dt == 0:
            return
        sec = now_ms // 1000
        idx = sec % self._buckets
        if self._bucket_second[idx] != sec:
            self._bucket_second[idx] = sec
            self._flagged_ms[idx] = 0
            self._total_ms[idx] = 0
        self._total_ms[idx] += dt
        if flagged:
            self._flagged_ms[idx] += dt

    def percent_or_none(self, now_ms: int) -> Optional[float]:
        now_sec = now_ms // 1000
        flagged = 0
        total = 0
        for i in range(self._buckets):
            sec = self._bucket_second[i]
            if sec < 0:
                continue
            age = now_sec - sec
            if 0 <= age < self._buckets:
                flagged += self._flagged_ms[i]
                total += self._total_ms[i]
        if total < self._min_sample_ms:
            return None
        return flagged * 100.0 / float(total)

    def percent(self, now_ms: int) -> float:
        return self.percent_or_none(now_ms) or 0.0


def median(values: List[float]) -> float:
    s = sorted(values)
    return s[len(s) // 2]


def percentile(values: List[float], p: float) -> float:
    s = sorted(values)
    idx = int((len(s) - 1) * p)
    idx = max(0, min(idx, len(s) - 1))
    return s[idx]
