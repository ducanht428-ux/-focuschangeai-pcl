"""Per-student habit profile, ported from HabitModel.kt.

Answers two questions the whole system is built around:
  * "how did this session go"   -> SessionSummary (one observation_sessions row + feature_records)
  * "what is normal for this student right now" -> HabitBaseline / ToleranceBand
    (one baseline_profiles row)

Nothing here raises an alert; the band is a description, alerts keep their own
thresholds. That separation is deliberate and copied from the app.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import List, Optional

from .constants import HabitConfig, HabitFeature
from .stats import RunningStat


class BandPosition(Enum):
    IN = 0
    BELOW = 1
    ABOVE = 2
    UNKNOWN = 3

    def to_csv_code(self) -> int:
        return {BandPosition.BELOW: 0, BandPosition.IN: 1, BandPosition.ABOVE: 2,
                BandPosition.UNKNOWN: -1}[self]


@dataclass
class ToleranceBand:
    mean: float
    min: float
    max: float

    def position(self, value: Optional[float]) -> BandPosition:
        if value is None or value != value:  # None or NaN
            return BandPosition.UNKNOWN
        if value < self.min:
            return BandPosition.BELOW
        if value > self.max:
            return BandPosition.ABOVE
        return BandPosition.IN


@dataclass
class BandOutcome:
    feature: int
    mean: float
    min: float
    max: float
    samples: int
    below: int
    above: int

    @property
    def in_samples(self) -> int:
        return self.samples - self.below - self.above


@dataclass
class SessionSummary:
    id: int
    valid_ms: int
    face_ratio: float
    valid_ratio: float
    quality: float
    mean: List[float]
    std: List[float]
    count: List[int]
    quality_count: int = 0
    band_in: int = 0
    band_below: int = 0
    band_above: int = 0
    sensitivity_level: int = -1

    @property
    def band_samples(self) -> int:
        return self.band_in + self.band_below + self.band_above


class HabitSession:
    """Running-session accumulator (HabitSession in HabitModel.kt)."""

    def __init__(self, session_id: int) -> None:
        c = HabitFeature.COUNT
        self._stats = [RunningStat() for _ in range(c)]
        self._quality = RunningStat()
        self._bands: List[Optional[ToleranceBand]] = [None] * c
        self._band_in = [0] * c
        self._band_below = [0] * c
        self._band_above = [0] * c
        self._last_tick_ms = 0
        self.id = session_id
        self.active_ms = 0
        self.face_ms = 0
        self.valid_ms = 0
        self.sensitivity_level = -1

    def tick(self, now_ms: int, face_visible: bool, valid: bool) -> None:
        dt = 0 if self._last_tick_ms == 0 else now_ms - self._last_tick_ms
        self._last_tick_ms = now_ms
        if dt <= 0:
            return
        self.active_ms += dt
        if dt > HabitConfig.MAX_FRAME_GAP_MS:
            return
        if face_visible:
            self.face_ms += dt
        if valid:
            self.valid_ms += dt

    def observe(self, feature: int, value: float) -> None:
        if value is None or value != value or value == float("inf"):
            return
        v = max(HabitFeature.LO[feature], min(HabitFeature.HI[feature], value))
        self._stats[feature].add(v)

    def set_bands(self, base: "HabitBaseline", features: List[int]) -> None:
        for f in features:
            self._bands[f] = base.band(f)

    def observe_band(self, feature: int, value: Optional[float]) -> BandPosition:
        band = self._bands[feature]
        if band is None or value is None:
            return BandPosition.UNKNOWN
        pos = band.position(value)
        if pos == BandPosition.IN:
            self._band_in[feature] += 1
        elif pos == BandPosition.BELOW:
            self._band_below[feature] += 1
        elif pos == BandPosition.ABOVE:
            self._band_above[feature] += 1
        return pos

    def add_quality(self, q: float) -> None:
        self._quality.add(q)

    def band_outcomes(self) -> List[BandOutcome]:
        out = []
        for f in HabitFeature.BAND_FEATURES:
            band = self._bands[f]
            if band is None:
                continue
            n = self._band_in[f] + self._band_below[f] + self._band_above[f]
            if n == 0:
                continue
            out.append(BandOutcome(f, band.mean, band.min, band.max, n,
                                   self._band_below[f], self._band_above[f]))
        return out

    def to_summary(self) -> SessionSummary:
        c = HabitFeature.COUNT
        mean = [self._stats[f].mean for f in range(c)]
        std = [self._stats[f].std() for f in range(c)]
        cnt = [self._stats[f].n for f in range(c)]
        face_ratio = self.face_ms / self.active_ms if self.active_ms > 0 else 0.0
        valid_ratio = self.valid_ms / self.face_ms if self.face_ms > 0 else 0.0
        b_in = sum(self._band_in[f] for f in HabitFeature.BAND_FEATURES)
        b_below = sum(self._band_below[f] for f in HabitFeature.BAND_FEATURES)
        b_above = sum(self._band_above[f] for f in HabitFeature.BAND_FEATURES)
        return SessionSummary(
            id=self.id, valid_ms=self.valid_ms, face_ratio=face_ratio,
            valid_ratio=valid_ratio, quality=self._quality.mean, mean=mean, std=std,
            count=cnt, quality_count=self._quality.n, band_in=b_in, band_below=b_below,
            band_above=b_above, sensitivity_level=self.sensitivity_level,
        )


class HabitBaseline:
    def __init__(self, means: List[float], spreads: List[float], ok: List[bool]) -> None:
        self._means = means
        self._spreads = spreads
        self._ok = ok

    def mean(self, f: int) -> Optional[float]:
        return self._means[f] if self._ok[f] else None

    def spread(self, f: int) -> Optional[float]:
        return self._spreads[f] if self._ok[f] else None

    def threshold(self, f: int, k: float, lo: float, hi: float) -> Optional[float]:
        """threshold() in HabitModel.kt:418: |mean| + k * spread, clamped into [lo, hi].

        The absolute value is on purpose and is what makes a signed feature usable as a gate: a
        student who habitually turns 5 degrees left and one who turns 5 degrees right get the same
        threshold, because the alert is about deviation from frontal, not about direction.
        """
        if not self._ok[f]:
            return None
        return max(lo, min(hi, abs(self._means[f]) + k * self._spreads[f]))

    def band(self, f: int, k: float = HabitConfig.BAND_K) -> Optional[ToleranceBand]:
        if not self._ok[f]:
            return None
        half = k * self._spreads[f]
        return ToleranceBand(
            mean=self._means[f],
            min=max(HabitFeature.LO[f], self._means[f] - half),
            max=min(HabitFeature.HI[f], self._means[f] + half),
        )


class HabitProfile:
    def __init__(self, pid: str, name: str) -> None:
        self.id = pid
        self.name = name
        self.sessions: List[SessionSummary] = []
        self.distance_scale: float = float("nan")
        self.cal_raw_distance_cm: float = float("nan")
        self.calibrated_at_ms: int = 0

    def has_calibration(self) -> bool:
        return _is_finite(self.distance_scale) and self.distance_scale > 0

    def session_count(self) -> int:
        return len(self.sessions)

    def total_valid_ms(self) -> int:
        return sum(s.valid_ms for s in self.sessions)

    def is_ready(self) -> bool:
        return (len(self.sessions) >= HabitConfig.MIN_SESSIONS
                and self.total_valid_ms() >= HabitConfig.MIN_TOTAL_MS)

    def confidence(self) -> float:
        if not self.is_ready():
            return 0.0
        raw = (len(self.sessions) - HabitConfig.MIN_SESSIONS + 1) / 5.0
        return max(0.2, min(1.0, raw))

    def ineligible_reason(self, s: SessionSummary) -> Optional[str]:
        if s.valid_ms < HabitConfig.MIN_SESSION_MS:
            return "chua du %d phut hop le" % (HabitConfig.MIN_SESSION_MS // 60000)
        if s.face_ratio < HabitConfig.MIN_FACE_RATIO:
            return "it khi thay khuon mat"
        if s.valid_ratio < HabitConfig.MIN_VALID_RATIO:
            return "tin hieu hoac tu the khong on dinh"
        if s.quality < HabitConfig.MIN_QUALITY:
            return "chat luong tin hieu thap"
        if s.count[HabitFeature.SEI] > 0 and s.mean[HabitFeature.SEI] < HabitConfig.MIN_SESSION_SEI:
            return "muc tap trung trung binh thap"
        return None

    def commit(self, s: SessionSummary) -> bool:
        pos = next((i for i, x in enumerate(self.sessions) if x.id == s.id), -1)
        if self.ineligible_reason(s) is not None:
            if pos >= 0:
                self.sessions.pop(pos)
                return True
            return False
        if pos >= 0:
            self.sessions[pos] = s
        else:
            self.sessions.append(s)
        while len(self.sessions) > HabitConfig.MAX_SESSIONS:
            self.sessions.pop(0)
        return True

    def baseline(self) -> HabitBaseline:
        import math
        c = HabitFeature.COUNT
        means = [0.0] * c
        spreads = [0.0] * c
        ok = [False] * c
        n = len(self.sessions)
        for f in range(c):
            min_count = 1 if f == HabitFeature.EAR else HabitConfig.MIN_FRAMES_PER_FEATURE
            idx = [i for i in range(n) if self.sessions[i].count[f] >= min_count]
            if not idx:
                continue
            keep = idx
            if len(idx) >= 5:
                vals = sorted(self.sessions[i].mean[f] for i in idx)
                med = vals[len(vals) // 2]
                dev = sorted(abs(self.sessions[i].mean[f] - med) for i in idx)
                mad = max(dev[len(dev) // 2], HabitFeature.MIN_SPREAD[f] * 0.5)
                limit = 3.5 * 1.4826 * mad
                filtered = [i for i in idx if abs(self.sessions[i].mean[f] - med) <= limit]
                if len(filtered) >= 3:
                    keep = filtered
            w = []
            sum_w = 0.0
            sum_m = 0.0
            for i in keep:
                s = self.sessions[i]
                age = n - 1 - i
                minutes = min(s.valid_ms / 60000.0, HabitConfig.MAX_WEIGHT_MINUTES)
                wi = max(minutes, 0.5) * (HabitConfig.RECENCY_DECAY ** age)
                w.append(wi)
                sum_w += wi
                sum_m += wi * s.mean[f]
            mean = sum_m / sum_w
            between = 0.0
            within = 0.0
            for j, i in enumerate(keep):
                s = self.sessions[i]
                d = s.mean[f] - mean
                between += w[j] * d * d
                within += w[j] * s.std[f] * s.std[f]
            spread = math.sqrt(between / sum_w + within / sum_w)
            means[f] = mean
            spreads[f] = max(spread, HabitFeature.MIN_SPREAD[f])
            ok[f] = True
        return HabitBaseline(means, spreads, ok)


def _is_finite(v) -> bool:
    import math
    try:
        return math.isfinite(v)
    except (TypeError, ValueError):
        return False
