"""Layer 4 - one PersonPipeline per tracked person.

This is the server's copy of the phone's ``buildHudSnapshot`` chain: landmarks come
in from the FaceAnalyzer, and the ported engines (EyeEngine, SituationEngine,
PersonGuard, habit session, SEI tracker) turn them into the same features and the
same Study Engagement Index the Android app produces - just one instance per student
instead of one global instance.
"""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field
from typing import Deque, Dict, List, Optional

from .constants import (
    BFR_FURROW_RATIO,
    BFR_SUSTAIN_MS,
    BROW_INNER_L,
    BROW_INNER_R,
    BPM_RELIABLE_AFTER_MS,
    DROWSY_CLOSURE_MS,
    EYE_MAX_PITCH_DEG,
    EYE_MAX_YAW_DEG,
    GAZE_H_DRIFT,
    GAZE_V_DRIFT,
    HabitFeature,
    HEAD_FRONTAL_YAW_DEG,
    HIGH_BLINK_BPM,
    LOOK_AWAY_MS,
    LOOK_AWAY_PITCH_DEG,
    LOOK_AWAY_YAW_DEG,
    LOW_BLINK_BPM,
    MAR_YAWN,
    MOUTH_L,
    MOUTH_LOW,
    MOUTH_R,
    MOUTH_UP,
    PERCLOS_P70_OPEN,
    PERCLOS_P80_OPEN,
    REF_DISTANCE_CM,
    Situation,
    VISUAL_DRIFT_MS,
    JAW_OPEN_YAWN,
    LM_COUNT,
    MuscleSensitivity,
    YAWN_MIN_MS,
    YAWN_WINDOW_MS,
    clamp01,
)
from .engines import EyeEngine, PersonGuard, SituationEngine, SituationFrame
from .habit import HabitBaseline, HabitProfile, HabitSession
from .landmarks import (
    Landmarks,
    estimate_distance_cm,
    eye_corner_span_px,
    focal_px,
    gaze_offsets,
    head_pose_deg,
    point_dist_px,
)
from .quality import signal_quality
from .sei import (
    SeiComponents,
    SeiTracker,
    bpm_score,
    distance_score,
    expression_score,
    eye_score,
    gaze_score,
    head_score,
)
from .stats import OneEuroFilter, WindowRatio, median


@dataclass
class PersonResult:
    """Everything the aggregator/writer needs about one person for one frame."""

    track_id: int
    student_id: Optional[str]
    student_name: str
    identity_conf: str
    measurable: bool
    calibrated: bool
    sei: Optional[int]
    situation: Situation
    eye_valid: bool
    openness: float
    perclos: Optional[float]
    bpm: Optional[float]
    distance_cm: Optional[float]
    yaw: Optional[float]
    pitch: Optional[float]
    alert: Optional[str]
    features: Dict[int, float] = field(default_factory=dict)
    # 0-100 signal quality of the frame this result came from, None when the frame carried
    # no readable light data. Shown so a low number is never mistaken for a measurement.
    quality: Optional[int] = None


class PersonPipeline:
    # calibration collects a neutral-pose baseline for this individual
    CALIB_SAMPLES = 40
    # blink / pose windows
    BPM_WINDOW_MS = 60000
    POSE_WINDOW_MS = 10000

    def __init__(self, track_id: int, profile: Optional[HabitProfile] = None,
                 sensitivity: int = 1, min_eye_span_px: float = 12.0) -> None:
        self.track_id = track_id
        self.profile = profile
        self.sens_level = sensitivity
        # The scheduler's own floor, reused as the "this face is too small to trust" line in
        # the quality score. Deliberately not the phone's face-box pixel floors - see quality.py.
        self.min_eye_span_px = min_eye_span_px
        # sensTime() on the phone: the slider stretches alert timers, never the 500 ms evidence
        # gate and never the SEI eye-score penalty. Computed once because the alert path runs per frame.
        self.sens_time = MuscleSensitivity.time_factor(sensitivity)

        self.eye = EyeEngine()
        self.eye.set_sensitivity(sensitivity)
        self.situation = SituationEngine()
        self.guard = PersonGuard()
        self.sei = SeiTracker()

        self.perclos = WindowRatio(60, 20000)
        self.perclos_p70 = WindowRatio(60, 20000)
        self.perclos_p80 = WindowRatio(60, 20000)

        # calibration accumulators
        self._cal_yaw: List[float] = []
        self._cal_pitch: List[float] = []
        self._cal_gaze_v: List[float] = []
        self._cal_gaze_h: List[float] = []
        self._cal_bfr: List[float] = []
        self._cal_dist_raw: List[float] = []
        self._cal_frames = 0
        self.calibrated = False
        self.baseline_yaw = 0.0
        self.baseline_pitch = 0.0
        self.baseline_gaze_v = 0.0
        self.baseline_gaze_h = 0.0
        self.baseline_bfr = 0.2
        self.distance_scale = 1.0

        # blink timing
        self._blink_ts: Deque[int] = deque(maxlen=512)
        self._blink_dur: Deque[int] = deque(maxlen=512)
        self._monitoring_start_ms = 0

        # micro-expression timing (furrowStartMs / yawnStartMs / yawnTimestamps in MainActivity.kt).
        # A frown and a yawn are durations on the phone, not single frames, so the server keeps the
        # same three clocks; without them one noisy blendshape frame costs 60 SEI points.
        self._furrow_start_ms = 0
        self._yawn_start_ms = 0
        self._yawn_counted = False
        self._yawn_ts: Deque[int] = deque(maxlen=64)

        # pose window (jitter + std)
        self._pose: Deque = deque(maxlen=300)

        # smoothing
        self._dist_filter = OneEuroFilter(1.0, 0.007)
        self._prev_nose = (float("nan"), float("nan"))
        self._jitter_ema = 0.0

        # session
        self.session: Optional[HabitSession] = None
        self._baseline: Optional[HabitBaseline] = None
        if profile is not None:
            self._baseline = profile.baseline()

        # transient state
        self.last_result: Optional[PersonResult] = None
        self.last_measured_ms = 0
        self.last_sei: Optional[int] = None
        self.look_away_start_ms = 0
        self.drift_start_ms = 0
        self.closed_start_ms = 0

    # ------------------------------------------------------------------
    def start_session(self, session_id: int) -> None:
        self.session = HabitSession(session_id)
        self.session.sensitivity_level = self.sens_level
        if self._baseline is not None:
            self.session.set_bands(self._baseline, HabitFeature.BAND_FEATURES)

    def commit_session(self) -> Optional[HabitSession]:
        s = self.session
        self.session = None
        return s

    # ------------------------------------------------------------------
    def _bfr(self, lm: Landmarks, w: int, h: int) -> float:
        """Brow-Furrow Ratio: inner-brow corner spacing over the face width (MainActivity.kt:4182).

        The quantity the phone actually learns is the GAP BETWEEN the two inner brow corners divided
        by faceWidthPx (which is the eye-corner span, MainActivity.kt:4020): frowning pulls those
        corners together and the ratio drops. This file used to average two brow-to-lower-lid
        vertical distances instead - a different measurement that happens to move the same way, which
        made the learned BFR band incomparable with the phone's and made the 0.88 trigger, tuned on
        the phone's ratio, mean something else here.
        """
        try:
            brow = point_dist_px(lm, BROW_INNER_R, BROW_INNER_L, w, h)
            return brow / max(1.0, eye_corner_span_px(lm, w, h))
        except IndexError:
            return self.baseline_bfr

    def _mar(self, lm: Landmarks, w: int, h: int) -> float:
        """Mouth Aspect Ratio: lip-to-lip over mouth width, with the phone's own guard.

        MainActivity.kt:4186-4190 returns 0 when the mouth is under a pixel wide, so a collapsed
        mouth box reports a shut mouth rather than a huge one. The jawOpen blendshape is NOT the
        server's MAR: on the phone it is the SECOND yawn clause (MainActivity.kt:4472) and it stays
        in _update_micro_expressions, compared against its own threshold.
        """
        try:
            mouth_w = point_dist_px(lm, MOUTH_R, MOUTH_L, w, h)
            if mouth_w < 1.0:
                return 0.0
            return point_dist_px(lm, MOUTH_UP, MOUTH_LOW, w, h) / mouth_w
        except IndexError:
            return 0.0

    def _update_pose_window(self, now_ms: int, yaw: float, pitch: float, roll: float, ear: float) -> None:
        self._pose.append((now_ms, yaw, pitch, roll, ear))
        while self._pose and now_ms - self._pose[0][0] > self.POSE_WINDOW_MS:
            self._pose.popleft()

    def _pose_std_deg(self) -> float:
        if len(self._pose) < 10:
            return float("nan")
        # std of the angle magnitude over the window
        import statistics
        mags = [math.hypot(p[1], p[2]) for p in self._pose]
        try:
            return statistics.pstdev(mags)
        except statistics.StatisticsError:
            return float("nan")

    def _jitter(self, lm: Landmarks, w: int, h: int) -> float:
        nx, ny = lm.x(1) * w, lm.y(1) * h
        px, py = self._prev_nose
        if px == px:  # not NaN
            step = math.hypot(nx - px, ny - py)
            self._jitter_ema = self._jitter_ema * 0.8 + step * 0.2
        self._prev_nose = (nx, ny)
        return self._jitter_ema / max(1.0, w)

    # ------------------------------------------------------------------
    def _maybe_calibrate(self, yaw, pitch, gaze_v, gaze_h, bfr, raw_dist, lm, w, h, bs) -> bool:
        self._cal_frames += 1
        self._cal_yaw.append(yaw)
        self._cal_pitch.append(pitch)
        if gaze_v is not None:
            self._cal_gaze_v.append(gaze_v)
        if gaze_h is not None:
            self._cal_gaze_h.append(gaze_h)
        self._cal_bfr.append(bfr)
        if raw_dist is not None:
            self._cal_dist_raw.append(raw_dist)
        self.eye.add_calibration_sample(lm, w, h, bs)

        if self._cal_frames >= self.CALIB_SAMPLES:
            self.baseline_yaw = median(self._cal_yaw)
            self.baseline_pitch = median(self._cal_pitch)
            self.baseline_gaze_v = median(self._cal_gaze_v) if self._cal_gaze_v else 0.0
            self.baseline_gaze_h = median(self._cal_gaze_h) if self._cal_gaze_h else 0.0
            self.baseline_bfr = median(self._cal_bfr) if self._cal_bfr else 0.2
            if self._cal_dist_raw:
                raw = median(self._cal_dist_raw)
                if raw > 0:
                    self.distance_scale = REF_DISTANCE_CM / raw
            # scale the eye EAR baseline by the learned distance-independent factor
            self.eye.finish_calibration(1.0)
            self.calibrated = self.eye.ready
            self._monitoring_start_ms = 0  # set on first real frame
            return True
        return False

    # ------------------------------------------------------------------
    def process(
        self,
        lm: Optional[Landmarks],
        bs: Optional[List[float]],
        w: int,
        h: int,
        now_ms: int,
        frame_dt_ms: float,
        student_id: Optional[str],
        student_name: str,
        identity_conf: str,
        nearby_objects: Dict[str, float],
        light: Optional[tuple] = None,
        focal: Optional[float] = None,
    ) -> PersonResult:
        eye_span = eye_corner_span_px(lm, w, h) if lm and lm.count else 0.0
        measurable = lm is not None and lm.count >= 140 and eye_span >= 8.0

        if not measurable:
            self._on_no_face(now_ms)
            return self._result(student_id, student_name, identity_conf, False,
                                Situation.UNKNOWN, None, None)

        pose = head_pose_deg(lm, w, h)
        if pose is None:
            # No usable face plane (see head_pose_deg). This frame has no pose, so it gets no
            # verdict either: scoring a degenerate landmark set as "frontal" would hand it a full
            # posture mark and a 28 degree eye gate it never earned.
            self._on_no_face(now_ms)
            return self._result(student_id, student_name, identity_conf, False,
                                Situation.UNKNOWN, None, None)
        yaw, pitch, roll = pose
        off_h, off_v = gaze_offsets(lm, w, h)
        gaze_v = off_v if off_v is not None else 0.0
        gaze_h = off_h if off_h is not None else 0.0
        bfr = self._bfr(lm, w, h)
        # The crop's pixels ARE the frame's pixels, so the focal length must come from the FRAME
        # width and the camera's field of view. Deriving it from the crop width - which is what
        # focal_px(w) did here - compares a 200 px face box against a ruler built for 1280 px and
        # reports every desk several times closer than it is, with the error wobbling as the YOLO
        # box around the head changes size. Only the caller knows the frame, so it is passed in.
        f_px = focal if focal is not None else focal_px(w)
        raw_dist = estimate_distance_cm(lm, w, h, f_px, abs(yaw))

        # --- calibration phase (per person, one time) ---
        if not self.calibrated:
            done = self._maybe_calibrate(yaw, pitch, gaze_v, gaze_h, bfr, raw_dist, lm, w, h, bs)
            if not done:
                return self._result(student_id, student_name, identity_conf, True,
                                    Situation.UNKNOWN, None, None, calibrated=False)
            self._monitoring_start_ms = now_ms

        if self._monitoring_start_ms == 0:
            self._monitoring_start_ms = now_ms

        # --- pose deviation from this person's neutral ---
        # SIGNED, exactly like the phone's yawRawDev = poseYawAbs - baselineYawDeg
        # (MainActivity.kt:4016). The old line here took abs() first and then subtracted the
        # baseline, which is a different quantity: it never went negative, so it could not tell a
        # head turned to the left from one turned to the right. Nothing used it; now everything
        # below uses this one value instead of re-deriving it six ways.
        yaw_dev = yaw - self.baseline_yaw
        pitch_dev = pitch - self.baseline_pitch
        roll_dev = roll
        self._update_pose_window(now_ms, yaw_dev, pitch_dev, roll, 0.0)
        pose_std = self._pose_std_deg()
        jitter = self._jitter(lm, w, h)

        # Quality of THIS frame as a signal, ported from computeSignalQuality (MainActivity.kt:3309).
        # The phone names that argument yawAbs but feeds it the FILTERED DEVIATION from the neutral
        # baseline (MainActivity.kt:4195 passes yawDeg, which is poseYawAbs - baselineYawDeg), so the
        # deviation is what is compared against 35/30 deg here too - not the raw head angle.
        # light is None when the caller could not read the image: recording a score built on default
        # brightness would hand this frame a near-perfect quality it never earned, so the frame is
        # recorded with no quality sample at all and the session mean stays honest about the gap.
        quality = None
        if light is not None:
            quality = signal_quality(
                light[0], eye_span < self.min_eye_span_px,
                abs(yaw_dev), abs(pitch_dev), lm.count >= LM_COUNT,
                light[1], light[2])

        # --- distance (scaled + smoothed) ---
        dist = raw_dist * self.distance_scale if raw_dist is not None else None
        if dist is not None and math.isfinite(dist):
            dist = self._dist_filter.filter(dist, now_ms)
        too_close = dist is not None and dist < 30.0
        self.guard.observe(now_ms, raw_dist, dist,
                           self.profile.cal_raw_distance_cm if self.profile else None)

        # --- eye engine ---
        pose_ok = abs(yaw_dev) <= EYE_MAX_YAW_DEG and abs(pitch_dev) <= EYE_MAX_PITCH_DEG
        eye = self.eye.update(lm, w, h, now_ms, yaw_dev, pitch_dev,
                              gaze_v - self.baseline_gaze_v, bs, pose_ok)

        if eye.closed:
            if self.closed_start_ms == 0:
                self.closed_start_ms = eye.closed_start_ms
            closed_dur = now_ms - self.closed_start_ms
        else:
            self.closed_start_ms = 0
            closed_dur = 0

        if eye.blink_duration_ms > 0:
            self._blink_ts.append(now_ms)
            self._blink_dur.append(eye.blink_duration_ms)

        # --- PERCLOS ---
        if eye.valid:
            self.perclos.record(now_ms, eye.closed)
            self.perclos_p70.record(now_ms, eye.openness <= PERCLOS_P70_OPEN)
            self.perclos_p80.record(now_ms, eye.openness <= PERCLOS_P80_OPEN)
        else:
            self.perclos.pause()
            self.perclos_p70.pause()
            self.perclos_p80.pause()
        perclos_val = self.perclos.percent_or_none(now_ms)
        perclos_for_score = perclos_val if perclos_val is not None else 0.0

        # --- BPM (60s window) ---
        self._prune_blinks(now_ms)
        elapsed = now_ms - self._monitoring_start_ms
        window = max(20000, min(self.BPM_WINDOW_MS, elapsed)) if elapsed > 0 else 1
        bpm = len(self._blink_ts) * 60000.0 / window
        bpm_reliable = elapsed >= BPM_RELIABLE_AFTER_MS

        # --- gaze / look-away / drift state ---
        head_away = abs(yaw_dev) > LOOK_AWAY_YAW_DEG or pitch_dev > LOOK_AWAY_PITCH_DEG
        if head_away:
            if self.look_away_start_ms == 0:
                self.look_away_start_ms = now_ms
        else:
            self.look_away_start_ms = 0
        look_away_long = self.look_away_start_ms != 0 and now_ms - self.look_away_start_ms >= LOOK_AWAY_MS * self.sens_time

        gaze_h_dev = gaze_h - self.baseline_gaze_h
        gaze_v_dev = gaze_v - self.baseline_gaze_v
        drifting = abs(gaze_h_dev) > GAZE_H_DRIFT or gaze_v_dev > GAZE_V_DRIFT
        if drifting:
            if self.drift_start_ms == 0:
                self.drift_start_ms = now_ms
        else:
            self.drift_start_ms = 0
        drift_long = self.drift_start_ms != 0 and now_ms - self.drift_start_ms >= VISUAL_DRIFT_MS * self.sens_time

        # --- micro-expressions ---
        mar = self._mar(lm, w, h)
        yawn_confirmed, confused, yawns_recent = self._update_micro_expressions(
            mar, bs, bfr, yaw_dev, now_ms)

        # --- situation layer ---
        sf = SituationFrame()
        sf.yaw_deg = yaw_dev
        sf.pitch_deg = pitch_dev
        sf.roll_deg = roll_dev
        sf.gaze_v_dev = gaze_v_dev
        sf.openness = eye.openness if eye.valid else float("nan")
        sf.closed_ms = closed_dur
        sf.pose_std_deg = pose_std
        self.situation.update(sf, now_ms)
        self._last_sit_frame = sf

        # --- SEI components ---
        posture = head_score(yaw_dev, pitch_dev, roll_dev, jitter)
        dist_sc = distance_score(dist)
        gaze_sc = gaze_score(off_h is not None, not eye.closed, gaze_h_dev, gaze_v_dev,
                              drift_long, GAZE_H_DRIFT, GAZE_V_DRIFT)
        eyes_sc = max(0.0, (
            eye_score(closed_dur, eye.closed)
            - clamp01((perclos_for_score - 5.0) / 25.0) * 50.0
            - clamp01(eye.squint_pct / 60.0) * 15.0
        ))
        bpm_sc = bpm_score(bpm, bpm_reliable, LOW_BLINK_BPM, HIGH_BLINK_BPM)
        expr_sc = expression_score(yawn_confirmed, yawns_recent, bfr, self.baseline_bfr, confused)

        # context penalty: a phone in hand while head pitched down reads as inattention
        context_penalty = 0.0
        if nearby_objects.get("phone", 0.0) > 0.4 and (pitch_dev > 12.0 or gaze_v_dev > GAZE_V_DRIFT):
            context_penalty = 25.0

        chronic_level = 0
        if context_penalty > 0 or look_away_long:
            chronic_level = 2
        elif drift_long:
            chronic_level = 1

        comps = SeiComponents(posture, dist_sc, gaze_sc, eyes_sc, bpm_sc, expr_sc)
        raw_sei = self.sei.raw_from_components(comps, too_close, chronic_level, frozen=False) - context_penalty
        sei_val = self.sei.update_sei(raw_sei, frame_dt_ms)
        self.last_sei = sei_val

        # --- habit accumulation ---
        feat_map = self._observe_session(
            now_ms, eye, bpm, bpm_reliable, dist, yaw_dev, pitch_dev,
            gaze_h_dev, gaze_v_dev, mar, bfr, perclos_for_score, sei_val, quality)

        alert = self._pick_alert(eye, closed_dur, perclos_for_score, look_away_long,
                                 drift_long, yawn_confirmed, too_close, sei_val, self._baseline,
                                 sf, now_ms)

        result = self._result(student_id, student_name, identity_conf, True,
                              self.situation.situation, sei_val, perclos_val)
        result.eye_valid = eye.valid
        result.openness = eye.openness
        result.bpm = bpm if bpm_reliable else None
        result.distance_cm = dist
        result.yaw = yaw_dev
        result.pitch = pitch_dev
        result.alert = alert
        result.features = feat_map
        result.quality = quality
        self.last_result = result
        self.last_measured_ms = now_ms
        return result

    # ------------------------------------------------------------------
    def _observe_session(self, now_ms, eye, bpm, bpm_reliable, dist, yaw, pitch,
                         gaze_h, gaze_v, mar, bfr, perclos, sei_val,
                         quality: Optional[int] = None) -> Dict[int, float]:
        hs = self.session
        feats: Dict[int, float] = {}
        if hs is None:
            return feats
        habit_valid = eye.valid and not eye.closed and sei_val is not None
        hs.tick(now_ms, True, habit_valid)
        if not habit_valid:
            return feats
        # Sampled under exactly the same guard as every feature above, which is where
        # MainActivity.kt:4683 calls hs.addQuality(). A frame nobody trusted with a BPM
        # sample must not be allowed to raise the session's mean quality either.
        if quality is not None:
            hs.add_quality(float(quality))
        if bpm_reliable:
            hs.observe(HabitFeature.BPM, bpm)
            feats[HabitFeature.BPM] = bpm
        if self._blink_dur:
            avg_blink = sum(self._blink_dur) / len(self._blink_dur)
            hs.observe(HabitFeature.BLINK_MS, avg_blink)
            feats[HabitFeature.BLINK_MS] = avg_blink
        if dist is not None:
            hs.observe(HabitFeature.DIST, dist)
            feats[HabitFeature.DIST] = dist
        hs.observe(HabitFeature.YAW, yaw); feats[HabitFeature.YAW] = yaw
        hs.observe(HabitFeature.PITCH, pitch); feats[HabitFeature.PITCH] = pitch
        hs.observe(HabitFeature.GAZE_H, gaze_h); feats[HabitFeature.GAZE_H] = gaze_h
        hs.observe(HabitFeature.GAZE_V, gaze_v); feats[HabitFeature.GAZE_V] = gaze_v
        hs.observe(HabitFeature.MAR, mar); feats[HabitFeature.MAR] = mar
        hs.observe(HabitFeature.BFR, bfr); feats[HabitFeature.BFR] = bfr
        hs.observe(HabitFeature.EAR, eye.openness); feats[HabitFeature.EAR] = eye.openness
        hs.observe(HabitFeature.PERCLOS, perclos); feats[HabitFeature.PERCLOS] = perclos
        hs.observe(HabitFeature.SEI, float(sei_val)); feats[HabitFeature.SEI] = float(sei_val)
        # two-sided band verdicts
        if self._baseline is not None:
            for f, v in feats.items():
                hs.observe_band(f, v)
        return feats

    def _update_micro_expressions(self, mar: float, bs: Optional[List[float]], bfr: float,
                                  yaw_dev: float, now_ms: int):
        """Frown and yawn as durations - MainActivity.kt:4454-4489, same clocks.

        Returns (yawn_confirmed, furrow_confused, yawns_recent). Both were single frames here while
        they are state machines on the phone: one noisy blendshape reading cost the full 60-point
        mouth penalty and fired a YAWN alert, while a real two-second yawn counted exactly the same
        as that frame. The sustained frown was not represented at all, so the "confused" input to
        expression_score was a hard-coded False.
        """
        furrow_trigger = MuscleSensitivity.furrow_ratio(self.sens_level, BFR_FURROW_RATIO)
        furrow_window = MuscleSensitivity.furrow_yaw_window(self.sens_level, HEAD_FRONTAL_YAW_DEG)
        # The window is a frontal-face gate: brow geometry reads as lowered when the head is tipped,
        # so a furrow is only counted while the face still points at the camera.
        furrow_now = abs(yaw_dev) < furrow_window and bfr < self.baseline_bfr * furrow_trigger
        if furrow_now:
            if self._furrow_start_ms == 0:
                self._furrow_start_ms = now_ms
        else:
            self._furrow_start_ms = 0
        confused = (self._furrow_start_ms != 0 and
                    now_ms - self._furrow_start_ms >= BFR_SUSTAIN_MS * self.sens_time)

        mar_thr = MuscleSensitivity.mouth_threshold(self.sens_level, MAR_YAWN)
        jaw_thr = MuscleSensitivity.mouth_threshold(self.sens_level, JAW_OPEN_YAWN)
        # MainActivity.kt:4469-4472: jawOpen is read only when the blendshape list actually reaches
        # index 4, and it is the second yawn clause, not a replacement for MAR.
        jaw_open = bs[4] if (bs is not None and len(bs) > 4) else 0.0
        yawn_now = mar > mar_thr or jaw_open > jaw_thr
        if yawn_now:
            if self._yawn_start_ms == 0:
                self._yawn_start_ms = now_ms
                self._yawn_counted = False
        else:
            self._yawn_start_ms = 0
            self._yawn_counted = False
        yawn_confirmed = (yawn_now and
                          now_ms - self._yawn_start_ms >= YAWN_MIN_MS * self.sens_time)
        # Counted once per burst, not once per frame, so a long yawn is one event in the
        # five-minute recency window instead of thirty.
        if yawn_confirmed and not self._yawn_counted:
            self._yawn_counted = True
            self._yawn_ts.append(now_ms)
        while self._yawn_ts and now_ms - self._yawn_ts[0] > YAWN_WINDOW_MS:
            self._yawn_ts.popleft()
        return yawn_confirmed, confused, len(self._yawn_ts)

    def _pick_alert(self, eye, closed_dur, perclos, look_away, drift, yawn, too_close,
                    sei_val, baseline, sf, now_ms) -> Optional[str]:
        eyelid_evidence = eye.closed and closed_dur >= 500
        allowed = self.situation.allow(eyelid_evidence, sf, now_ms) if eyelid_evidence else True
        if eye.closed and closed_dur >= DROWSY_CLOSURE_MS * self.sens_time and allowed:
            return "DROWSY"
        if perclos is not None and perclos >= 30.0:
            return "PERCLOS_HIGH"
        if look_away:
            return "LOOK_AWAY"
        if drift:
            return "VISUAL_DRIFT"
        if yawn:
            return "YAWN"
        if too_close:
            return "TOO_CLOSE"
        if baseline is not None:
            m = baseline.mean(HabitFeature.SEI)
            s = baseline.spread(HabitFeature.SEI)
            if m is not None and s is not None and sei_val is not None and sei_val < m - 2.5 * s:
                return "LOW_VS_PERSONAL"
        return None

    def _current_situation_frame(self) -> SituationFrame:
        # reused for the allow() gate; the exact frame is already fed in update()
        return getattr(self, "_last_sit_frame", SituationFrame())

    def _on_no_face(self, now_ms: int) -> None:
        self.eye.pause()
        self.perclos.pause()
        self.perclos_p70.pause()
        self.perclos_p80.pause()
        self._dist_filter.reset()
        self._prev_nose = (float("nan"), float("nan"))
        self.closed_start_ms = 0
        self.look_away_start_ms = 0
        self.drift_start_ms = 0
        # A face that leaves the frame and comes back is not the second half of one yawn.
        self._furrow_start_ms = 0
        self._yawn_start_ms = 0
        self._yawn_counted = False

    def _result(self, student_id, student_name, identity_conf, measurable, situation,
                sei_val, perclos_val, calibrated=True) -> PersonResult:
        return PersonResult(
            track_id=self.track_id, student_id=student_id, student_name=student_name,
            identity_conf=identity_conf, measurable=measurable, calibrated=calibrated,
            sei=sei_val, situation=situation, eye_valid=False, openness=1.0,
            perclos=perclos_val, bpm=None, distance_cm=None, yaw=None, pitch=None,
            alert=None, features={},
        )

    def _prune_blinks(self, now_ms: int) -> None:
        cutoff = now_ms - self.BPM_WINDOW_MS
        while self._blink_ts and self._blink_ts[0] < cutoff:
            self._blink_ts.popleft()
