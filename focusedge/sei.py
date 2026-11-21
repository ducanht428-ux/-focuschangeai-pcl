"""SEI (Study Engagement Index) composite scoring, ported from MainActivity.kt.

Six sub-scores (0-100, higher = better) weighted into a raw value, then smoothed
with the same time-based alpha the phone uses so the number reacts at the same
speed whether the stream is 12 or 30 fps.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional

from .constants import (
    FATIGUE_SMOOTHING,
    SEI_ANOMALY_CAP,
    SEI_CHRONIC_PENALTY,
    SEI_SMOOTHING,
    SEI_TOO_CLOSE_PENALTY,
    SEI_WATCH_PENALTY,
    W_BPM,
    W_DISTANCE,
    W_EXPRESSION,
    W_EYES,
    W_GAZE,
    W_POSTURE,
    clamp01,
)


def _clamp(v, lo, hi):
    return max(lo, min(hi, v))


def distance_score(d: Optional[float]) -> float:
    if d is None:
        return 70.0
    from .constants import MIN_SAFE_DISTANCE_CM
    if d < MIN_SAFE_DISTANCE_CM:
        return (d / MIN_SAFE_DISTANCE_CM) * 50.0
    if d < 45.0:
        return 60.0 + ((d - MIN_SAFE_DISTANCE_CM) / 15.0) * 40.0
    if d <= 80.0:
        return 100.0
    if d <= 120.0:
        return 100.0 - ((d - 80.0) / 40.0) * 50.0
    return 50.0


def eye_score(closed_duration_ms: int, closed_now: bool) -> float:
    from .constants import DROWSY_CLOSURE_MS
    if closed_now and closed_duration_ms >= DROWSY_CLOSURE_MS:
        return 0.0
    if closed_now and closed_duration_ms >= 500:
        return 40.0
    if closed_now:
        return 90.0
    return 100.0


def bpm_score(bpm: float, reliable: bool, thr_blink_low: float, thr_blink_high: float) -> float:
    if not reliable:
        return 85.0
    lo = thr_blink_low + 4.0
    hi = thr_blink_high - 5.0
    if lo <= bpm <= hi:
        return 100.0
    if bpm < lo:
        return 40.0 + (bpm / lo) * 60.0
    return max(30.0, 100.0 - ((bpm - hi) / 7.0) * 30.0)


def head_score(yaw_deg: float, pitch_deg: float, roll_deg: float, jitter: float) -> float:
    orientation = (100.0
                   - clamp01(abs(yaw_deg) / 25.0) * 50.0
                   - clamp01(abs(pitch_deg) / 25.0) * 30.0
                   - clamp01(abs(roll_deg) / 25.0) * 20.0)
    stability = 100.0 * (1.0 - clamp01(jitter / 0.05))
    return _clamp(orientation * 0.7 + stability * 0.3, 0.0, 100.0)


def gaze_score(available: bool, eyes_open: bool, off_h: float, off_v: float,
               drift_long: bool, thr_gaze_h: float, thr_gaze_v: float) -> float:
    if not available or not eyes_open:
        return 85.0
    base = (100.0
            - clamp01(abs(off_h) / (thr_gaze_h * 1.5)) * 60.0
            - clamp01(abs(off_v) / (thr_gaze_v * 1.5)) * 40.0)
    if drift_long:
        return min(base, 30.0)
    return _clamp(base, 0.0, 100.0)


def expression_score(yawn_confirmed: bool, yawns_recent: int, bfr: float,
                     baseline: float, confused: bool) -> float:
    if yawn_confirmed:
        mouth_penalty = 60.0
    else:
        mouth_penalty = min(30.0, yawns_recent * 10.0)
    deficit = 1.0 - (bfr / baseline if baseline else 1.0)
    furrow_max = 30.0 if confused else 20.0
    furrow_penalty = clamp01((deficit - 0.05) / 0.20) * furrow_max
    return _clamp(100.0 - mouth_penalty - furrow_penalty, 0.0, 100.0)


def frame_alpha(per_frame_at_30fps: float, frame_dt_ms: float) -> float:
    """Time-based smoothing coefficient (MainActivity.frameAlpha)."""
    a = _clamp(per_frame_at_30fps, 0.001, 0.99)
    tau_ms = -33.3 / math.log(1.0 - a)
    return _clamp(1.0 - math.exp(-frame_dt_ms / tau_ms), 0.0, 1.0)


@dataclass
class SeiComponents:
    posture: float
    dist: float
    gaze: float
    eyes: float
    blink_rate: float
    expression: float


class SeiTracker:
    """Holds the EMA state for one person's SEI + fatigue, like the MainActivity fields."""

    def __init__(self) -> None:
        self._sei_ema = 0.0
        self._sei_initialized = False
        self._fatigue_ema = 0.0
        self.sei_sum = 0
        self.sei_count = 0
        self.min_sei = None

    def raw_from_components(self, c: SeiComponents, distance_alert: bool,
                            chronic_level: int, frozen: bool) -> float:
        raw = (c.posture * W_POSTURE + c.dist * W_DISTANCE + c.gaze * W_GAZE
               + c.eyes * W_EYES + c.blink_rate * W_BPM + c.expression * W_EXPRESSION)
        if distance_alert:
            raw -= SEI_TOO_CLOSE_PENALTY
        if chronic_level == 2:
            raw -= SEI_CHRONIC_PENALTY
        elif chronic_level == 1:
            raw -= SEI_WATCH_PENALTY
        if frozen:
            raw = min(raw, SEI_ANOMALY_CAP)
        return raw

    def update_sei(self, raw: float, frame_dt_ms: float, sens_smooth: float = 1.0) -> int:
        target = _clamp(raw, 0.0, 100.0)
        if not self._sei_initialized:
            self._sei_initialized = True
            self._sei_ema = target
        else:
            alpha = frame_alpha(min(0.9, SEI_SMOOTHING * sens_smooth), frame_dt_ms)
            self._sei_ema += (target - self._sei_ema) * alpha
        value = int(round(self._sei_ema))
        value = max(0, min(100, value))
        self.sei_sum += value
        self.sei_count += 1
        if self.min_sei is None or value < self.min_sei:
            self.min_sei = value
        return value

    def update_fatigue(self, perclos_value: float, avg_blink_ms: Optional[float],
                       yawns_recent: int, bpm: float, bpm_reliable: bool,
                       drowsy_now: bool, thr_blink_low: float, thr_blink_high: float,
                       sens_level: int, frame_dt_ms: float) -> int:
        raw = clamp01((perclos_value - 3.0) / 17.0) * 35.0
        if avg_blink_ms is not None:
            from .constants import MuscleSensitivity
            raw += MuscleSensitivity.blink_load(sens_level, avg_blink_ms) * 20.0
        raw += min(25.0, yawns_recent * 8.0)
        if bpm_reliable and (bpm < thr_blink_low or bpm > thr_blink_high):
            raw += 10.0
        if drowsy_now:
            raw += 10.0
        target = min(100.0, raw)
        self._fatigue_ema += (target - self._fatigue_ema) * frame_alpha(FATIGUE_SMOOTHING, frame_dt_ms)
        return int(_clamp(round(self._fatigue_ema), 0, 100))

    def average_sei(self) -> Optional[int]:
        if self.sei_count > 0:
            return int(round(self.sei_sum / self.sei_count))
        return None
