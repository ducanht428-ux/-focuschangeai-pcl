"""Per-frame state machines, ported from the Android app.

  EyeEngine        <- EyeEngine.kt   (fused eye openness, blink FSM, discomfort ratios)
  SituationEngine  <- SituationEngine.kt (per-person posture contexts)
  PersonGuard      <- PersonGuard.kt (is this still the calibrated face?)

The arithmetic follows the Kotlin line-for-line where it matters (thresholds,
hysteresis, EMA updates) so a blink or a situation reads the same on both sides.
"""
from __future__ import annotations

import math
from collections import deque
from typing import List, Optional

from .constants import (
    EYE_L_IN,
    EYE_L_LOW,
    EYE_L_OUT,
    EYE_L_UP,
    EYE_R_IN,
    EYE_R_LOW,
    EYE_R_OUT,
    EYE_R_UP,
    Situation,
)
from .landmarks import Landmarks
from .stats import OneEuroFilter, RunningStat, WindowRatio, median


# ===========================================================================
# EyeEngine
# ===========================================================================
class EyeFrame:
    """One frame's eye result (EyeEngine.EyeFrame). Reused object, read-only downstream."""

    def __init__(self) -> None:
        self.valid = False
        self.openness = 1.0
        self.closed = False
        self.closed_start_ms = 0
        self.blink_duration_ms = 0
        self.blink_incomplete = False
        self.noisy = False
        self.sigma = 0.0
        self.squint_pct = 0.0
        self.asym_pct = 0.0
        self.incomplete_ratio = float("nan")
        self.squint_alert = False
        self.asym_alert = False
        self.incomplete_alert = False
        self.using_blendshapes = False


class EyeEngine:
    CLOSE_O = 0.60
    OPEN_O = 0.78
    MIN_CLOSE_DWELL_MS = 40
    MIN_BLINK_MS = 25
    MAX_BLINK_MS = 1000
    INCOMPLETE_MIN_O = 0.45
    INCOMPLETE_MAX_DT_MS = 55.0
    ASYM_EXTRA_THRESHOLD = 0.25
    PARTIAL_LO = 0.45
    PARTIAL_HI = 0.80
    DEG2RAD = 0.017453292
    NOISY_ON = 0.12
    NOISY_OFF = 0.09
    NOISY_HOLD_MS = 10000
    SQUINT_ALERT_PCT = 40.0
    ASYM_ALERT_PCT = 50.0
    INCOMPLETE_ALERT_RATIO = 0.6
    RING = 20

    def __init__(self) -> None:
        self.out = EyeFrame()
        self._open_filter = OneEuroFilter(4.0, 1.0)
        self._squint_ratio = WindowRatio(60, 20000)
        self._asym_ratio = WindowRatio(60, 30000)
        self._cal_e1: List[float] = []
        self._cal_e2: List[float] = []
        self._cal_bs: List[float] = []
        self._cal_asym: List[float] = []
        self._ready = False
        self._cal_base1 = 0.3
        self._cal_base2 = 0.3
        self._base1 = 0.3
        self._base2 = 0.3
        self._bs_base = 0.08
        self._bs_asym_base = 0.0
        self.close_o = self.CLOSE_O
        self.open_o = self.OPEN_O
        # blink state
        self._closed = False
        self._cand_start = 0
        self._cand_min = 1.0
        self._closed_start = 0
        self._min_during = 1.0
        # noise
        self._sigma = 0.03
        self._noisy_start = 0
        self._prev_wr = 0.0
        self._prev_wl = 0.0
        self._glitch_streak = 0
        self._last_update_ms = 0
        self._dt_ema = 33.0
        # incomplete blinks
        self._ring = [False] * self.RING
        self._ring_idx = 0
        self._ring_count = 0

    @property
    def ready(self) -> bool:
        return self._ready

    def set_sensitivity(self, level: int) -> None:
        self.close_o = {0: 0.54, 2: 0.66, 3: 0.70}.get(level, self.CLOSE_O)
        self.open_o = min(self.OPEN_O, self.close_o + 0.18)

    @staticmethod
    def _d(lm: Landmarks, a: int, b: int, w: int, h: int) -> float:
        dx = (lm.x(a) - lm.x(b)) * w
        dy = (lm.y(a) - lm.y(b)) * h
        return math.sqrt(dx * dx + dy * dy)

    def _ear5(self, lm: Landmarks, up, low, a: int, b: int, w: int, h: int) -> float:
        vals = [self._d(lm, up[i], low[i], w, h) for i in range(5)]
        hor = self._d(lm, a, b, w, h)
        if hor < 1.0:
            return float("nan")
        trimmed = sum(vals) - min(vals) - max(vals)
        return (trimmed / 3.0) / hor

    def reset(self) -> None:
        self._cal_e1.clear()
        self._cal_e2.clear()
        self._cal_bs.clear()
        self._cal_asym.clear()
        self._ready = False
        self._sigma = 0.03
        self._noisy_start = 0
        self._ring_idx = 0
        self._ring_count = 0
        self._squint_ratio.reset()
        self._asym_ratio.reset()
        self.pause()

    def pause(self) -> None:
        self._closed = False
        self._cand_start = 0
        self._open_filter.reset()
        self._squint_ratio.pause()
        self._asym_ratio.pause()
        self._prev_wr = 0.0
        self._prev_wl = 0.0
        self._glitch_streak = 0
        self._last_update_ms = 0
        self.out.valid = False
        self.out.closed = False

    def add_calibration_sample(self, lm: Landmarks, w: int, h: int, bs: Optional[List[float]]) -> None:
        e1 = self._ear5(lm, EYE_R_UP, EYE_R_LOW, EYE_R_OUT, EYE_R_IN, w, h)
        e2 = self._ear5(lm, EYE_L_UP, EYE_L_LOW, EYE_L_IN, EYE_L_OUT, w, h)
        if math.isnan(e1) or math.isnan(e2):
            return
        self._cal_e1.append(e1)
        self._cal_e2.append(e2)
        if bs is not None:
            self._cal_bs.append((bs[0] + bs[1]) / 2.0)
            self._cal_asym.append(abs(bs[0] - bs[1]))

    def finish_calibration(self, scale: float) -> None:
        if len(self._cal_e1) < 5:
            self._ready = False
            return
        from .stats import percentile
        # floor the openness baseline so a degenerate (all-closed / tiny) calibration
        # cannot produce a divide-by-zero later in update(); real EAR bases are ~0.2-0.35.
        self._cal_base1 = max(1e-3, percentile(self._cal_e1, 0.70) * scale)
        self._cal_base2 = max(1e-3, percentile(self._cal_e2, 0.70) * scale)
        self._base1 = self._cal_base1
        self._base2 = self._cal_base2
        self._bs_base = percentile(self._cal_bs, 0.5) if len(self._cal_bs) >= 5 else 0.08
        self._bs_base = max(0.0, min(0.5, self._bs_base))
        self._bs_asym_base = percentile(self._cal_asym, 0.5) if len(self._cal_asym) >= 5 else 0.0
        self._bs_asym_base = max(0.0, min(0.3, self._bs_asym_base))
        self._ready = True
        self._sigma = 0.03
        self._noisy_start = 0
        self._ring_idx = 0
        self._ring_count = 0
        self._squint_ratio.reset()
        self._asym_ratio.reset()
        self.pause()

    def _record_blink(self, incomplete: bool) -> None:
        self._ring[self._ring_idx] = incomplete
        self._ring_idx = (self._ring_idx + 1) % self.RING
        if self._ring_count < self.RING:
            self._ring_count += 1

    def _incomplete_ratio(self) -> float:
        if self._ring_count < 8:
            return float("nan")
        n = sum(1 for i in range(self._ring_count) if self._ring[i])
        return n / float(self._ring_count)

    def _clear_conclusions(self, o: EyeFrame) -> None:
        o.squint_pct = 0.0
        o.asym_pct = 0.0
        o.incomplete_ratio = float("nan")
        o.squint_alert = False
        o.asym_alert = False
        o.incomplete_alert = False
        o.noisy = False
        o.sigma = float("nan")

    def _mark_invalid(self, o: EyeFrame, closed_state: bool) -> EyeFrame:
        o.valid = False
        o.closed = closed_state
        self._clear_conclusions(o)
        return o

    def update(
        self,
        lm: Landmarks,
        w: int,
        h: int,
        now_ms: int,
        yaw_dev: float,
        pitch_dev: float,
        gaze_v_dev: float,
        bs: Optional[List[float]],
        pose_ok: bool,
    ) -> EyeFrame:
        o = self.out
        o.blink_duration_ms = 0
        o.blink_incomplete = False
        o.using_blendshapes = bs is not None

        if not self._ready:
            return self._mark_invalid(o, False)

        e1 = self._ear5(lm, EYE_R_UP, EYE_R_LOW, EYE_R_OUT, EYE_R_IN, w, h)
        e2 = self._ear5(lm, EYE_L_UP, EYE_L_LOW, EYE_L_IN, EYE_L_OUT, w, h)
        wr = self._d(lm, EYE_R_OUT, EYE_R_IN, w, h)
        wl = self._d(lm, EYE_L_IN, EYE_L_OUT, w, h)

        if self._last_update_ms != 0:
            dt_frame = float(now_ms - self._last_update_ms)
            if 1.0 <= dt_frame <= 300.0:
                self._dt_ema = self._dt_ema * 0.95 + dt_frame * 0.05
        self._last_update_ms = now_ms

        # 1) reject landmark "jumps"
        glitch = math.isnan(e1) or math.isnan(e2)
        if not glitch and self._prev_wr > 0 and self._prev_wl > 0:
            rr = wr / self._prev_wr
            rl = wl / self._prev_wl
            if rr > 1.3 or rr < 0.77 or rl > 1.3 or rl < 0.77:
                glitch = True
        if glitch:
            self._glitch_streak += 1
            if self._glitch_streak > 8:
                self._prev_wr = 0.0
                self._prev_wl = 0.0
                self._glitch_streak = 0
            return self._mark_invalid(o, self._closed)
        self._glitch_streak = 0
        self._prev_wr = wr
        self._prev_wl = wl

        # 2) too oblique / weak signal
        if not pose_ok:
            self._closed = False
            self._cand_start = 0
            self._open_filter.reset()
            return self._mark_invalid(o, False)

        # 3) angle-compensated geometric openness
        cos_y = math.cos(min(abs(yaw_dev), 50.0) * 0.9 * self.DEG2RAD)
        down = max(pitch_dev, 0.0) * 0.006 + max(gaze_v_dev, 0.0) * 0.5
        expected = max(0.7, min(1.0, 1.0 - down))
        c1 = e1 * cos_y / expected
        c2 = e2 * cos_y / expected
        og1 = max(0.0, min(1.5, c1 / self._base1))
        og2 = max(0.0, min(1.5, c2 / self._base2))
        og = max(og1, og2)

        # 4) blendshape fusion
        fused = og
        squint_bs = 0.0
        asym_bs = 0.0
        if bs is not None:
            blink = (bs[0] + bs[1]) / 2.0
            denom = max(0.5, 1.0 - self._bs_base)
            ob = max(0.0, min(1.3, 1.0 - (blink - self._bs_base) / denom))
            if abs(og - ob) > 0.45:
                fused = 0.8 * ob + 0.2 * og
            else:
                fused = 0.65 * ob + 0.35 * og
            squint_bs = (bs[2] + bs[3]) / 2.0
            asym_bs = abs(bs[0] - bs[1]) - self._bs_asym_base

        # 5) time filtering + noise estimate
        f = self._open_filter.filter(fused, now_ms)
        if not self._closed and f > 0.85:
            self._sigma = self._sigma * 0.98 + abs(fused - f) * 1.25 * 0.02
        self._sigma = max(0.0, min(0.3, self._sigma))
        if self._sigma > self.NOISY_ON:
            if self._noisy_start == 0:
                self._noisy_start = now_ms
        elif self._sigma < self.NOISY_OFF:
            self._noisy_start = 0
        noisy = self._noisy_start != 0 and now_ms - self._noisy_start >= self.NOISY_HOLD_MS

        # 6) adaptive thresholds
        close_thr = max(0.40, min(self.close_o, self.close_o - 1.5 * self._sigma))
        open_thr = min(self.open_o, max(close_thr + 0.12, self.open_o - self._sigma))
        open_thr = min(open_thr, 0.88)

        # 7) blink state machine
        if not self._closed:
            if f < close_thr:
                if self._cand_start == 0:
                    self._cand_start = now_ms
                    self._cand_min = f
                self._cand_min = min(self._cand_min, f)
                dwell = now_ms - self._cand_start
                if dwell >= self.MIN_CLOSE_DWELL_MS or f < close_thr - 0.15:
                    self._closed = True
                    self._closed_start = self._cand_start
                    self._min_during = self._cand_min
            else:
                self._cand_start = 0
        else:
            self._min_during = min(self._min_during, f)
            if f > open_thr:
                dur = now_ms - self._closed_start
                if self.MIN_BLINK_MS <= dur <= self.MAX_BLINK_MS:
                    o.blink_duration_ms = dur
                    if self._dt_ema <= self.INCOMPLETE_MAX_DT_MS:
                        o.blink_incomplete = self._min_during > self.INCOMPLETE_MIN_O
                        self._record_blink(o.blink_incomplete)
                self._closed = False
                self._cand_start = 0

        # 8) discomfort indicators
        partial = (not self._closed) and self.PARTIAL_LO <= f < self.PARTIAL_HI
        if not partial and not self._closed and squint_bs > 0.45 and f < 0.9:
            partial = True
        self._squint_ratio.record(now_ms, partial)

        frontal = abs(yaw_dev) < 15.0
        if bs is not None:
            asym_flag = asym_bs > self.ASYM_EXTRA_THRESHOLD
        else:
            asym_flag = abs(og1 - og2) > 0.35
        self._asym_ratio.record(now_ms, frontal and not self._closed and f > 0.35 and asym_flag)

        sq = self._squint_ratio.percent(now_ms)
        asym = self._asym_ratio.percent(now_ms)
        inc = self._incomplete_ratio()

        # 9) slow baseline adaptation
        if f > 0.8 and not self._closed:
            self._base1 = self._adapt_base(self._base1, c1, self._cal_base1)
            self._base2 = self._adapt_base(self._base2, c2, self._cal_base2)
            self._base1 = max(self._cal_base1 * 0.85, min(self._cal_base1 * 1.25, self._base1))
            self._base2 = max(self._cal_base2 * 0.85, min(self._cal_base2 * 1.25, self._base2))

        o.valid = True
        o.openness = f
        o.closed = self._closed
        o.closed_start_ms = self._closed_start
        o.sigma = self._sigma
        o.noisy = noisy
        o.squint_pct = sq
        o.asym_pct = asym
        o.incomplete_ratio = inc
        o.squint_alert = sq >= self.SQUINT_ALERT_PCT
        o.asym_alert = asym >= self.ASYM_ALERT_PCT
        o.incomplete_alert = (not math.isnan(inc)) and inc >= self.INCOMPLETE_ALERT_RATIO
        return o

    @staticmethod
    def _adapt_base(base: float, c: float, cal_base: float) -> float:
        if c > base:
            if c < base * 1.35:
                base += 0.02 * (c - base)
        elif c > base * 0.85:
            base -= 0.0004 * (base - c)
        return base


# ===========================================================================
# SituationEngine
# ===========================================================================
class SituationFrame:
    def __init__(self) -> None:
        self.yaw_deg = 0.0
        self.pitch_deg = 0.0
        self.roll_deg = 0.0
        self.gaze_v_dev = 0.0
        self.openness = float("nan")
        self.closed_ms = 0
        self.pose_std_deg = float("nan")


class SituationEngine:
    ALWAYS_ALERT_CLOSED_MS = 2500
    HOLD_MAX_MS = 700
    MIN_FRAMES_PER_SITUATION = 60
    SWITCH_HOLD_MS = 400
    YAW_TURN_DEG = 20.0
    ROLL_TILT_DEG = 12.0
    PITCH_DOWN_DEG = 12.0
    PITCH_DOZE_DEG = 20.0
    GAZE_DOWN = 0.25
    STILL_STD_DEG = 0.35
    DOZE_OPENNESS = 0.55
    DOZE_CLOSED_MS = 300
    BAND_K = 2.5

    def __init__(self, band_k: float = BAND_K) -> None:
        self.band_k = band_k
        self.raw_situation = Situation.UNKNOWN
        self.situation = Situation.UNKNOWN
        self._candidate = Situation.UNKNOWN
        self._candidate_start_ms = 0
        self._stats = [RunningStat() for _ in Situation]
        self._hold_start_ms = 0
        self.intervened_episodes = 0
        self.suppressed_episodes = 0

    def frames_in(self, s: Situation) -> int:
        return self._stats[s.value].n

    def classify(self, f: SituationFrame) -> Situation:
        if abs(f.yaw_deg) > self.YAW_TURN_DEG:
            return Situation.TURNED
        pitched = f.pitch_deg > self.PITCH_DOWN_DEG
        gazes_down = f.gaze_v_dev > self.GAZE_DOWN
        still = _is_finite(f.pose_std_deg) and f.pose_std_deg < self.STILL_STD_DEG
        if (pitched and f.pitch_deg > self.PITCH_DOZE_DEG and still
                and f.openness < self.DOZE_OPENNESS and f.closed_ms >= self.DOZE_CLOSED_MS):
            return Situation.DOZING
        if abs(f.roll_deg) > self.ROLL_TILT_DEG:
            return Situation.TILT
        if pitched or gazes_down:
            return Situation.DOWN
        return Situation.FORWARD

    def update(self, f: SituationFrame, now_ms: int) -> None:
        raw = self.classify(f)
        self.raw_situation = raw
        if raw == self.situation:
            self._candidate = raw
            self._candidate_start_ms = 0
        elif self.situation == Situation.UNKNOWN:
            self._candidate = raw
            self._candidate_start_ms = 0
            self.situation = raw
        elif raw != self._candidate:
            self._candidate = raw
            self._candidate_start_ms = now_ms
        elif now_ms - self._candidate_start_ms >= self.SWITCH_HOLD_MS:
            self._candidate_start_ms = 0
            self.situation = self._candidate
        if _is_finite(f.openness):
            self._stats[self.situation.value].add(f.openness)

    def openness_floor(self, s: Situation) -> float:
        st = self._stats[s.value]
        if st.n < self.MIN_FRAMES_PER_SITUATION:
            return float("nan")
        return st.mean - self.band_k * st.std()

    def allow(self, eyelid_evidence: bool, f: SituationFrame, now_ms: int) -> bool:
        if not eyelid_evidence:
            if self._hold_start_ms != 0:
                self.suppressed_episodes += 1
            return self._clear_hold()
        if f.closed_ms >= self.ALWAYS_ALERT_CLOSED_MS:
            return self._clear_hold()
        if self.situation in (Situation.DOZING, Situation.UNKNOWN):
            return self._clear_hold()
        floor = self.openness_floor(self.situation)
        if not _is_finite(floor):
            return self._clear_hold()
        if f.openness >= floor:
            if self._hold_start_ms == 0:
                self._hold_start_ms = now_ms
                self.intervened_episodes += 1
            if now_ms - self._hold_start_ms < self.HOLD_MAX_MS:
                return False
        else:
            self._hold_start_ms = 0
        return self._clear_hold()

    def _clear_hold(self) -> bool:
        self._hold_start_ms = 0
        return True

    def reset(self) -> None:
        self.raw_situation = Situation.UNKNOWN
        self.situation = Situation.UNKNOWN
        self._candidate = Situation.UNKNOWN
        self._candidate_start_ms = 0
        self._hold_start_ms = 0
        self.intervened_episodes = 0
        self.suppressed_episodes = 0
        self._stats = [RunningStat() for _ in Situation]


# ===========================================================================
# PersonGuard
# ===========================================================================
class PersonGuard:
    WIDTH_TOLERANCE = 0.12
    HOLD_MS = 10000
    DIST_LO_CM = 40.0
    DIST_HI_CM = 62.0
    MIN_SAMPLES = 10
    WINDOW_SIZE = 30
    CALIBRATION_REJECT_RATIO = 0.20

    def __init__(self, tolerance: float = WIDTH_TOLERANCE, hold_ms: int = HOLD_MS,
                 distance_lo_cm: float = DIST_LO_CM, distance_hi_cm: float = DIST_HI_CM,
                 min_samples: int = MIN_SAMPLES, window_size: int = WINDOW_SIZE) -> None:
        self._tolerance = tolerance
        self._hold_ms = hold_ms
        self._dist_lo = distance_lo_cm
        self._dist_hi = distance_hi_cm
        self._min_samples = min_samples
        self._window = window_size
        self._raws: deque = deque()
        self._suspect_since_ms = 0
        self.deviation = float("nan")

    @staticmethod
    def calibration_deviation(new_scale: float, stored_scale: float) -> float:
        if not _is_finite(new_scale) or not _is_finite(stored_scale) or stored_scale <= 0:
            return float("nan")
        return (new_scale - stored_scale) / stored_scale

    def sample_count(self) -> int:
        return len(self._raws)

    def reset(self) -> None:
        self._raws.clear()
        self._suspect_since_ms = 0
        self.deviation = float("nan")

    def observe(self, now_ms: int, raw_distance_cm: Optional[float],
                scaled_distance_cm: Optional[float], reference_raw_cm: Optional[float]) -> bool:
        raw = raw_distance_cm if (raw_distance_cm and _is_finite(raw_distance_cm) and raw_distance_cm > 0) else None
        scaled = scaled_distance_cm if (scaled_distance_cm and _is_finite(scaled_distance_cm)) else None
        ref = reference_raw_cm if (reference_raw_cm and _is_finite(reference_raw_cm) and reference_raw_cm > 0) else None

        if raw is None or scaled is None or ref is None or scaled < self._dist_lo or scaled > self._dist_hi:
            self._suspect_since_ms = 0
            self.deviation = float("nan")
            return False

        self._raws.append(raw)
        while len(self._raws) > self._window:
            self._raws.popleft()
        if len(self._raws) < self._min_samples:
            return False

        dev = (median(list(self._raws)) - ref) / ref
        self.deviation = dev
        if abs(dev) <= self._tolerance:
            self._suspect_since_ms = 0
            return False
        if self._suspect_since_ms == 0:
            self._suspect_since_ms = now_ms
        return now_ms - self._suspect_since_ms >= self._hold_ms


def _is_finite(v) -> bool:
    try:
        return math.isfinite(v)
    except (TypeError, ValueError):
        return False
