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
from typing import Deque, Dict, List, NamedTuple, Optional, Tuple

from .constants import (
    BFR_FURROW_RATIO,
    BFR_SUSTAIN_MS,
    BPM_RELIABLE_AFTER_MS,
    BREAK_REMINDER_MS,
    BREAK_REMINDER_SHOW_MS,
    BROW_INNER_L,
    BROW_INNER_R,
    BREAK_DETECT_MS,
    DISTANCE_RECOVER_CM,
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
    LOW_VS_PERSONAL_K,
    LOW_VS_PERSONAL_MS,
    MAR_YAWN,
    MOUTH_L,
    MOUTH_LOW,
    MOUTH_R,
    MOUTH_UP,
    MIN_SAFE_DISTANCE_CM,
    PERCLOS_ALERT,
    PERCLOS_P70_OPEN,
    PERCLOS_P80_OPEN,
    PERSONAL_SEI_MIN_SPREAD,
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
from .quality import signal_quality_detail
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
    # --- preview-only payload -------------------------------------------------
    # The writer and the aggregator need none of these; the --show window does. The phone's HUD
    # draws the face mesh and prints "Dang hieu chuan X/40", and the numbers that make that
    # possible (the box this result was measured inside, the landmarks behind it, how far
    # calibration has got) were local to ClassroomPipeline.step() and thrown away. They travel
    # here so the operator sees the same thing on both screens. Nothing is written to Firestore
    # from them, so the documents are byte-for-byte unchanged.
    face_bbox: Optional[Tuple[int, int, int, int]] = None
    landmarks: Optional[Landmarks] = None
    calibration_progress: int = 0
    # The rest of what the phone's HUD prints. See HudState.
    hud: Optional["HudState"] = None


class AlertFacts(NamedTuple):
    """One frame's boolean inputs to the alert chain, in the order the phone reads them.

    The chain used to take nine positional arguments and still had to reach into the eye frame for the
    other five, which is how three of the phone's branches (low blink, high blink, the squint family)
    went missing from it without anybody noticing: adding an input meant editing a call site nobody
    reads. A named bundle makes each branch's input visible, and a test can build one directly.
    """

    reliable: bool = True
    drowsy: bool = False
    perclos: Optional[float] = None
    drift: bool = False
    look_away: bool = False
    yawn: bool = False
    bpm: Optional[float] = None
    bpm_reliable: bool = False
    squint: bool = False
    asym: bool = False
    incomplete: bool = False
    noisy: bool = False
    break_reminder: bool = False
    confused: bool = False
    low_vs_personal: bool = False


@dataclass
class HudState:
    """Every number behind the phone's HUD text lines, from one measured frame.

    The phone writes about 25 text lines inside MainActivity.analyzeFrame from local variables that
    never leave that function; only seven of them ever travelled on PersonResult here, so a preview
    of this machine could not say the same things in the same words. The pipeline already computes
    nearly all of them - they were being dropped at the end of process().

    Preview payload only: the writer, the aggregator and the Firestore documents read none of it, so
    what leaves this machine is byte-for-byte unchanged. Anything the camera leg genuinely does not
    measure stays at its default so the HUD can print the phone's own "--" instead of a guess.
    """

    # --- eye engine (EyeEngine.EyeFrame, MainActivity.kt:4316-4367) ---
    ear1: float = float("nan")
    ear2: float = float("nan")
    openness: float = float("nan")
    sigma: float = float("nan")
    squint_pct: float = 0.0
    asym_pct: float = 0.0
    incomplete_ratio: float = float("nan")
    eye_valid: bool = False
    using_blendshapes: bool = False
    noisy: bool = False
    squint_alert: bool = False
    asym_alert: bool = False
    incomplete_alert: bool = False
    closed: bool = False
    drowsy: bool = False
    blink_total: int = 0
    avg_blink_ms: Optional[float] = None
    perclos: Optional[float] = None
    perclos_p70: Optional[float] = None
    perclos_p80: Optional[float] = None
    # BPM is only ever put on the phone's screen when its window was full enough to trust it, so
    # "None" already carries that decision; a second flag for it would be a field nobody reads.
    bpm: Optional[float] = None

    # --- geometry (distance + head pose) ---
    distance_cm: Optional[float] = None
    distance_scale: float = 1.0
    too_close: bool = False
    yaw: Optional[float] = None
    pitch: Optional[float] = None
    roll: Optional[float] = None

    # --- gaze / drift / look-away (MainActivity.kt:4399-4433, 4370-4380) ---
    iris_available: bool = False
    gaze_h_dev: float = 0.0
    gaze_v_dev: float = 0.0
    eyes_open_for_gaze: bool = False
    drift_now: bool = False
    drift_long: bool = False
    drift_ms: int = 0
    look_away_long: bool = False

    # --- brow / mouth (MainActivity.kt:4450-4496) ---
    mar: float = float("nan")
    bfr: float = float("nan")
    yawn_confirmed: bool = False
    furrow_now: bool = False
    confused_sustained: bool = False
    yawns_recent: int = 0

    # --- scores and signal ---
    sei: Optional[int] = None
    average_sei: Optional[int] = None
    min_sei: Optional[int] = None
    fatigue: Optional[int] = None
    chronic_level: int = 0
    quality: Optional[int] = None
    quality_note: str = "Tốt"
    reliable: bool = True
    personal_sei_mean: Optional[int] = None
    # The sentence the chain picked, named after the phone's own HudSnapshot.alertMessage. It is kept
    # even when the situation layer delayed it, and even for the three branches that print but are
    # never counted, because an operator who sees a shut eye and no line cannot tell "delayed" from
    # "broken" (MainActivity.kt:5074-5078). PersonResult.alert is the separate, counted code.
    alert_message: Optional[str] = None
    alert_held: bool = False

    # --- situation layer counters (MainActivity.kt:4774-4780) ---
    intervened: int = 0
    suppressed: int = 0

    # --- clocks ---
    session_seconds: int = 0
    calibrating: bool = False
    calib_progress: int = 0
    calib_total: int = 0
    baseline_ear: float = float("nan")
    landmark_count: int = 0
    processing_ms: float = float("nan")
    fps: float = float("nan")


def phone_context_penalty(nearby_objects: Dict[str, float], pitch_dev: float,
                          gaze_v_dev: float) -> float:
    """The scene half of the score: how much a visible phone takes off this frame.

    detection.py only reports that a cell phone box is in the frame; this is the rule that turns
    that into a number, and it is the only place the detector touches the measurement. Two tests
    have to pass together: the box has to clear 0.4 - above the detector's own 0.35 bar, so a
    one-frame flicker of an object on someone else's desk cannot move the score - and the head has
    to already be down or looking down, because a phone lying on the table while the student faces
    the screen is not inattention.

    Extracted out of process() so desktop/_dev/parity/gen_parity_cases.py can run it over a table
    of inputs: the browser port reimplements this rule in js/detect.mjs, and a rule that only
    exists inline in two files is a rule nobody can prove the two files share.
    """
    if nearby_objects.get("phone", 0.0) > 0.4 and (pitch_dev > 12.0 or gaze_v_dev > GAZE_V_DRIFT):
        return 25.0
    return 0.0


class PersonPipeline:
    # calibration collects a neutral-pose baseline for this individual. Equal to CALIBRATION_FRAMES
    # in MainActivity.kt:258 on purpose: the phone needs 60 settled frames before it will call an
    # openness ratio a baseline, and a leg that decides after 40 is a second instrument with a
    # looser rule, which is exactly what the preview would then disagree about.
    CALIB_SAMPLES = 60
    # The three chain branches the phone gives alertCode 0 (MainActivity.kt:4740, :4752, :4754).
    # They still print a sentence, but they are notes about the measurement, not episodes of
    # inattention, so the tally, the CSV and the timeline must not see them.
    UNCOUNTED_ALERTS = frozenset(("UNRELIABLE", "CONFUSED", "NOISY"))
    # The two the situation layer is allowed to delay (MainActivity.kt:4765): the ones whose
    # evidence is "the eye looks closed in this frame". PERCLOS is not one of them - it is a ratio
    # over a whole minute, so holding it back frame by frame bends the measurement, not the reading.
    HELD_ALERT_CODES = ("DROWSY", "SQUINT")
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
        # The slider has three levers on the phone, not one. sensThr scales how far a head or an eye
        # has to move before the timer even starts (MainActivity.kt:4371, :4403) and sensSmooth
        # decides how fast the SEI chases the instant score (defined at MainActivity.kt:3151).
        # Neither existed here, so the slider moved only the durations and every SEI this machine
        # printed was smoother than the phone's at the same setting.
        self.sens_thr = MuscleSensitivity.sens_thr(sensitivity)
        self.sens_smooth = MuscleSensitivity.sens_smooth(sensitivity)

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
        # Lifetime blinks for this person, the number behind the phone's "So lan chop mat" line
        # (MainActivity.kt:5112 increments a UI counter per detected blink; the 60 s window above is
        # a different thing and feeds BPM only).
        self._blink_total = 0

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

        # The phone's personal thresholds (applyPersonalParams, MainActivity.kt:2465). A student with
        # no finished profile gets exactly the plain constants below, which is what the phone does
        # when confidence() is still 0; a student with one gets a blend, so the same sitting produces
        # the same verdict on both devices.
        self.thr_look_yaw = LOOK_AWAY_YAW_DEG
        self.thr_look_pitch = LOOK_AWAY_PITCH_DEG
        self.thr_gaze_h = GAZE_H_DRIFT
        self.thr_gaze_v = GAZE_V_DRIFT
        self.thr_blink_low = LOW_BLINK_BPM
        self.thr_blink_high = HIGH_BLINK_BPM
        self.thr_perclos = PERCLOS_ALERT
        self.thr_mar = MAR_YAWN
        self.personal_sei_mean: Optional[float] = None
        self.personal_sei_spread: Optional[float] = None
        self._apply_personal_params(profile)

        # transient state
        self.last_result: Optional[PersonResult] = None
        self.last_measured_ms = 0
        self.last_sei: Optional[int] = None
        self.look_away_start_ms = 0
        self.drift_start_ms = 0
        self.closed_start_ms = 0
        # Three clocks the phone has and this leg did not: how long SEI has been below this student's
        # own usual level, the 30-on/32-off hysteresis for the distance warning, and the 20-20-20
        # work clock. Each one decides whether a line appears, so each one changes a number.
        self._low_vs_personal_start_ms = 0
        self._distance_alert_active = False
        self._work_start_ms = 0
        self._face_lost_since_ms = 0
        # The frame's own clock, kept for _result() so the HUD can still show elapsed session time
        # on a frame that measured nothing - which is what the phone's sessionSeconds does.
        self._last_now_ms = 0

    # ------------------------------------------------------------------
    def _apply_personal_params(self, profile: Optional[HabitProfile]) -> None:
        """applyPersonalParams in MainActivity.kt:2465, threshold for threshold.

        ``mix`` weights the learned value by the profile's confidence, and confidence is 0 until the
        profile holds MIN_SESSIONS sessions and MIN_TOTAL_MS of valid time - so a face this machine
        has never seen keeps the plain constants, which is the same answer the phone gives a stranger.
        The clamps come straight from the phone too: a runaway spread cannot push the look-away gate
        past 30 degrees or the PERCLOS gate past 25 per cent.
        """
        if profile is None:
            return
        base = profile.baseline()
        c = profile.confidence()
        if base is None or c <= 0.0:
            return

        def mix(default: float, personal: Optional[float]) -> float:
            if personal is None:
                return default
            return default * (1.0 - c) + personal * c

        self.thr_look_yaw = mix(LOOK_AWAY_YAW_DEG,
                                base.threshold(HabitFeature.YAW, 2.5, 12.0, 30.0))
        self.thr_look_pitch = mix(LOOK_AWAY_PITCH_DEG,
                                  base.threshold(HabitFeature.PITCH, 2.5, 12.0, 30.0))
        self.thr_gaze_h = mix(GAZE_H_DRIFT,
                              base.threshold(HabitFeature.GAZE_H, 2.5, 0.10, 0.30))
        self.thr_gaze_v = mix(GAZE_V_DRIFT,
                              base.threshold(HabitFeature.GAZE_V, 2.5, 0.14, 0.40))

        bpm_mean = base.mean(HabitFeature.BPM)
        bpm_spread = base.spread(HabitFeature.BPM)
        if bpm_mean is not None and bpm_spread is not None:
            self.thr_blink_low = mix(LOW_BLINK_BPM, max(4.0, min(12.0, bpm_mean - 2.0 * bpm_spread)))
            self.thr_blink_high = mix(HIGH_BLINK_BPM, max(24.0, min(40.0, bpm_mean + 2.0 * bpm_spread)))

        p_mean = base.mean(HabitFeature.PERCLOS)
        p_spread = base.spread(HabitFeature.PERCLOS)
        if p_mean is not None and p_spread is not None:
            self.thr_perclos = mix(PERCLOS_ALERT, max(10.0, min(25.0, p_mean + 3.0 * p_spread)))

        m_mean = base.mean(HabitFeature.MAR)
        m_spread = base.spread(HabitFeature.MAR)
        if m_mean is not None and m_spread is not None:
            self.thr_mar = mix(MAR_YAWN, max(0.45, min(0.70, m_mean + 5.0 * m_spread)))

        s_mean = base.mean(HabitFeature.SEI)
        s_spread = base.spread(HabitFeature.SEI)
        if s_mean is not None and s_spread is not None:
            self.personal_sei_mean = s_mean
            self.personal_sei_spread = max(s_spread, PERSONAL_SEI_MIN_SPREAD)

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
    @property
    def calibration_progress(self) -> int:
        """Calibration samples collected so far; the HUD prints this as "X/60"."""
        return self._cal_frames

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
        self._last_now_ms = now_ms

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
        # The face came back (MainActivity.kt:4003-4007). The 20-20-20 work clock restarts only for
        # a calibrated person and only after the face has been gone long enough to count as a break,
        # so walking out of frame for one second must not hand this student a fresh 20 minutes.
        self._face_lost_since_ms = 0
        if self.calibrated and self._work_start_ms == 0:
            self._work_start_ms = now_ms
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
        quality_note = "Tốt"
        reliable = True
        if light is not None:
            quality, quality_note, reliable = signal_quality_detail(
                light[0], eye_span < self.min_eye_span_px,
                abs(yaw_dev), abs(pitch_dev), lm.count >= LM_COUNT,
                light[1], light[2])

        # --- distance (scaled + smoothed) ---
        dist = raw_dist * self.distance_scale if raw_dist is not None else None
        if dist is not None and math.isfinite(dist):
            dist = self._dist_filter.filter(dist, now_ms)
        # 30 cm switches the warning on, 32 cm switches it off (MainActivity.kt:4139-4144); see
        # _update_distance_alert for why the line is not a bare "< 30".
        too_close = self._update_distance_alert(dist)
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
        # MainActivity.kt:4359. Computed once here and handed to the alert picker, so the line the
        # operator reads ("Nham lau - co the bun ngu") and the DROWSY code that gets recorded cannot
        # be two implementations of the same test that later drift apart.
        drowsy_now = eye.closed and closed_dur >= int(DROWSY_CLOSURE_MS * self.sens_time)

        if eye.blink_duration_ms > 0:
            self._blink_ts.append(now_ms)
            self._blink_dur.append(eye.blink_duration_ms)
            self._blink_total += 1

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
        # Both entry gates now carry this student's own personalised threshold and the slider's angle
        # factor, exactly as MainActivity.kt:4371 and :4402 do. Before this, the server compared
        # against the raw constant, so the slider only moved the timers here and a head turned 17
        # degrees read as "at the screen" on one device and "away" on the other.
        head_away = (abs(yaw_dev) > self.thr_look_yaw * self.sens_thr
                     or abs(pitch_dev) > self.thr_look_pitch * self.sens_thr)
        if head_away:
            if self.look_away_start_ms == 0:
                self.look_away_start_ms = now_ms
        else:
            self.look_away_start_ms = 0
        look_away_long = self.look_away_start_ms != 0 and now_ms - self.look_away_start_ms >= LOOK_AWAY_MS * self.sens_time

        gaze_h_dev = gaze_h - self.baseline_gaze_h
        gaze_v_dev = gaze_v - self.baseline_gaze_v
        # One definition, used by the drift gate below and by the HUD line that explains it, so the
        # sentence "your eyes were open when this was called a lie" cannot disagree with the gate.
        eyes_open_for_gaze = eye.valid and eye.openness >= 0.75
        # Three guards the server was missing: no iris, an unreliable frame, or eyes that are shut
        # (MainActivity.kt:4402). Without them a closed eye's landmark drift counted as a lie-away and
        # a dark frame counted as one too - the phone reports "no iris data" for both cases instead.
        drifting = (off_h is not None and reliable and eyes_open_for_gaze
                    and (abs(gaze_h_dev) > self.thr_gaze_h * self.sens_thr
                         or abs(gaze_v_dev) > self.thr_gaze_v * self.sens_thr))
        if drifting:
            if self.drift_start_ms == 0:
                self.drift_start_ms = now_ms
        else:
            self.drift_start_ms = 0
        drift_long = self.drift_start_ms != 0 and now_ms - self.drift_start_ms >= VISUAL_DRIFT_MS * self.sens_time

        # --- micro-expressions ---
        mar = self._mar(lm, w, h)
        yawn_confirmed, confused, yawns_recent, furrow_now = self._update_micro_expressions(
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
        # The sub-scores take this student's own thresholds, the way the phone's members do
        # (gazeScore MainActivity.kt:3688, bpmScore :3654). Passing the plain constants here made the
        # score itself - not just the alert - disagree with the phone for any student with a profile.
        gaze_sc = gaze_score(off_h is not None and reliable, eyes_open_for_gaze,
                             gaze_h_dev, gaze_v_dev,
                             drift_long, self.thr_gaze_h, self.thr_gaze_v)
        eyes_sc = max(0.0, (
            eye_score(closed_dur, eye.closed)
            - clamp01((perclos_for_score - 5.0) / 25.0) * 50.0
            - clamp01(eye.squint_pct / 60.0) * 15.0
        ))
        bpm_sc = bpm_score(bpm, bpm_reliable, self.thr_blink_low, self.thr_blink_high)
        expr_sc = expression_score(yawn_confirmed, yawns_recent, bfr, self.baseline_bfr, confused)

        # context penalty: a phone in hand while head pitched down reads as inattention
        context_penalty = phone_context_penalty(nearby_objects, pitch_dev, gaze_v_dev)

        chronic_level = 0
        if context_penalty > 0 or look_away_long:
            chronic_level = 2
        elif drift_long:
            chronic_level = 1

        comps = SeiComponents(posture, dist_sc, gaze_sc, eyes_sc, bpm_sc, expr_sc)
        raw_sei = self.sei.raw_from_components(comps, too_close, chronic_level, frozen=False) - context_penalty
        sei_val = self.sei.update_sei(raw_sei, frame_dt_ms, self.sens_smooth)
        self.last_sei = sei_val

        # --- 20-20-20 break reminder (MainActivity.kt:4551-4560) ---
        # The clock starts when this person is calibrated and restarts after each reminder, so the
        # line shows in a 30 s window every 20 minutes rather than forever. The server had no
        # reminder at all, which is one of the phone's alert lines missing from this screen.
        work_ms = 0 if self._work_start_ms == 0 else now_ms - self._work_start_ms
        break_reminder = False
        if work_ms >= BREAK_REMINDER_MS:
            if work_ms < BREAK_REMINDER_MS + BREAK_REMINDER_SHOW_MS:
                break_reminder = True
            else:
                self._work_start_ms = now_ms

        # --- below this student's own usual level (MainActivity.kt:4642-4652) ---
        # The phone holds the comparison for 45 s and floors the spread at 8 SEI points, because one
        # bad frame is not a claim about someone's normal. The server compared against 2.5 spreads
        # with no hold at all, so ordinary noise printed the advisory line.
        below_personal = (reliable and sei_val is not None
                          and self.personal_sei_mean is not None
                          and self.personal_sei_spread is not None
                          and sei_val < self.personal_sei_mean
                          - LOW_VS_PERSONAL_K * self.personal_sei_spread)
        if below_personal:
            if self._low_vs_personal_start_ms == 0:
                self._low_vs_personal_start_ms = now_ms
        else:
            self._low_vs_personal_start_ms = 0
        low_vs_personal = (self._low_vs_personal_start_ms != 0
                           and now_ms - self._low_vs_personal_start_ms >= LOW_VS_PERSONAL_MS)

        # --- habit accumulation ---
        feat_map = self._observe_session(
            now_ms, eye, bpm, bpm_reliable, dist, yaw_dev, pitch_dev,
            gaze_h_dev, gaze_v_dev, mar, bfr, perclos_for_score, sei_val, quality)

        # --- fatigue index (MainActivity.kt:4541, SeiTracker.update_fatigue) ---
        # Computed on the phone's own path and read only by the HUD: no threshold or session feature
        # uses it here, so printing it cannot change a single recorded number.
        avg_blink_ms = (sum(self._blink_dur) / len(self._blink_dur)) if self._blink_dur else None
        fatigue = self.sei.update_fatigue(perclos_for_score, avg_blink_ms, yawns_recent, bpm,
                                          bpm_reliable, drowsy_now, self.thr_blink_low,
                                          self.thr_blink_high, self.sens_level, frame_dt_ms)

        # The chain runs once and its answer is used twice, exactly as on the phone: the counted code
        # (what the writer, the aggregator and the timeline see) and the printed sentence (what the
        # operator sees) are not the same list. Three branches print but are never counted, and a held
        # alert counts its message as delayed rather than fired (MainActivity.kt:4739, :5145).
        alert, alert_counted, alert_held = self._pick_alert(AlertFacts(
            reliable=reliable, drowsy=drowsy_now, perclos=perclos_val, drift=drift_long,
            look_away=look_away_long, yawn=yawn_confirmed, bpm=bpm, bpm_reliable=bpm_reliable,
            squint=eye.squint_alert, asym=eye.asym_alert, incomplete=eye.incomplete_alert,
            noisy=eye.noisy, break_reminder=break_reminder, confused=confused,
            low_vs_personal=low_vs_personal), sf, now_ms)

        personal_mean = None
        if self.personal_sei_mean is not None:
            personal_mean = int(round(self.personal_sei_mean))

        result = self._result(student_id, student_name, identity_conf, True,
                              self.situation.situation, sei_val, perclos_val)
        result.eye_valid = eye.valid
        result.openness = eye.openness
        result.bpm = bpm if bpm_reliable else None
        result.distance_cm = dist
        result.yaw = yaw_dev
        result.pitch = pitch_dev
        result.alert = alert if (alert_counted and not alert_held) else None
        result.features = feat_map
        result.quality = quality
        result.hud = HudState(
            ear1=eye.ear1, ear2=eye.ear2, openness=eye.openness, sigma=eye.sigma,
            squint_pct=eye.squint_pct, asym_pct=eye.asym_pct,
            incomplete_ratio=eye.incomplete_ratio, eye_valid=eye.valid,
            using_blendshapes=eye.using_blendshapes, noisy=eye.noisy,
            squint_alert=eye.squint_alert, asym_alert=eye.asym_alert,
            incomplete_alert=eye.incomplete_alert, closed=eye.closed,
            drowsy=drowsy_now, blink_total=self._blink_total,
            avg_blink_ms=avg_blink_ms,
            perclos=perclos_val,
            perclos_p70=self.perclos_p70.percent_or_none(now_ms),
            perclos_p80=self.perclos_p80.percent_or_none(now_ms),
            bpm=bpm if bpm_reliable else None,
            distance_cm=dist, distance_scale=self.distance_scale, too_close=too_close,
            yaw=yaw_dev, pitch=pitch_dev, roll=roll_dev,
            iris_available=off_h is not None, gaze_h_dev=gaze_h_dev, gaze_v_dev=gaze_v_dev,
            eyes_open_for_gaze=eyes_open_for_gaze,
            drift_now=drifting, drift_long=drift_long,
            drift_ms=0 if self.drift_start_ms == 0 else now_ms - self.drift_start_ms,
            look_away_long=look_away_long,
            mar=mar, bfr=bfr, yawn_confirmed=yawn_confirmed, furrow_now=furrow_now,
            confused_sustained=confused, yawns_recent=yawns_recent,
            sei=sei_val, average_sei=self.sei.average_sei(), min_sei=self.sei.min_sei,
            fatigue=fatigue, chronic_level=chronic_level,
            quality=quality, quality_note=quality_note,
            # The HUD prints this even while the situation engine holds it back and even for the
            # three branches that are never counted; PersonResult.alert stays None for those, so
            # nothing downstream reads a delayed or informational line as a fired alert.
            alert_message=alert, alert_held=alert_held,
            reliable=reliable if quality is not None else True, personal_sei_mean=personal_mean,
            intervened=self.situation.intervened_episodes,
            suppressed=self.situation.suppressed_episodes,
            session_seconds=max(0, (now_ms - self._monitoring_start_ms) // 1000),
            calibrating=False, calib_progress=self._cal_frames,
            calib_total=self.CALIB_SAMPLES, baseline_ear=self.eye.baseline_ear,
            landmark_count=lm.count,
        )
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

        Returns (yawn_confirmed, furrow_confused, yawns_recent, furrow_now). Both were single frames
        here while
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

        mar_thr = MuscleSensitivity.mouth_threshold(self.sens_level, self.thr_mar)
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
        return yawn_confirmed, confused, len(self._yawn_ts), furrow_now

    def _update_distance_alert(self, dist: Optional[float]) -> bool:
        """The 30-on/32-off distance hysteresis, MainActivity.kt:4139-4144.

        A bare "< 30" on an estimate that still jitters by a centimetre or two flapped the line on
        and off at a desk sitting exactly there, and the phone's whole reason for the gap is that
        each flap is one more counted violation than the student actually earned. It lives in its
        own method because the flap count is the claim: a test has to be able to walk a distance
        sequence through it without driving a whole frame.
        """
        if dist is not None:
            self._distance_alert_active = dist < (
                DISTANCE_RECOVER_CM if self._distance_alert_active else MIN_SAFE_DISTANCE_CM)
        else:
            self._distance_alert_active = False
        return self._distance_alert_active

    def _pick_alert(self, a: AlertFacts, sf: SituationFrame,
                    now_ms: int) -> Tuple[Optional[str], bool, bool]:
        """The phone's priority chain, MainActivity.kt:4704-4736, branch for branch and in order.

        Order is the product: two conditions can be true in the same frame and only the first sentence
        is ever shown, so a chain that shuffles them reports a different cause for the same minute.
        Three differences this replaces: the head-turn line used to outrank the eye-drift line (the
        phone ranks drift first because its evidence is the narrower claim), sitting too close used to
        be a member of the chain (on the phone it is its own permanent line above this column, and
        printing it twice would let one fact outrank everything below it), and a frame with bad signal
        used to alert anyway - now it says "tín hiệu kém" and nothing else, because every other
        sentence in the chain is a conclusion drawn from that same bad signal.

        Returns (code, counted, held). The phone runs this chain twice - once for the sentence at
        :4704 and once for the tally at :4739 - and the two are not the same list: "tín hiệu kém",
        "bối rối" and "kính/chói" print a line but get alertCode 0, because a note about the quality
        of the measurement is not an episode of inattention worth counting. ``counted`` carries that
        split, and a held alert is a delayed one, not a fired one (:4766, :5145).
        """
        if not a.reliable:
            code = "UNRELIABLE"
        elif a.drowsy:
            code = "DROWSY"
        elif a.perclos is not None and a.perclos >= self.thr_perclos:
            code = "PERCLOS_HIGH"
        elif a.drift:
            code = "VISUAL_DRIFT"
        elif a.look_away:
            code = "LOOK_AWAY"
        elif a.yawn:
            code = "YAWN"
        elif a.bpm_reliable and a.bpm is not None and a.bpm < self.thr_blink_low:
            code = "LOW_BLINK"
        elif a.squint:
            code = "SQUINT"
        elif a.asym:
            code = "ASYMMETRY"
        elif a.incomplete:
            code = "INCOMPLETE_BLINK"
        elif a.bpm_reliable and a.bpm is not None and a.bpm > self.thr_blink_high:
            code = "HIGH_BLINK"
        elif a.break_reminder:
            code = "BREAK"
        elif a.confused:
            code = "CONFUSED"
        elif a.low_vs_personal:
            code = "LOW_VS_PERSONAL"
        elif a.noisy:
            code = "NOISY"
        else:
            code = None

        counted = code is not None and code not in self.UNCOUNTED_ALERTS
        # Only the two alerts whose evidence is "the eye looks closed in this frame" can be held
        # (MainActivity.kt:4765). PERCLOS is deliberately not one of them: it is a ratio over a whole
        # minute, so delaying it frame by frame would bend the measurement, not the reading of it.
        eyelid_evidence = code in self.HELD_ALERT_CODES
        # Asked every frame, even a quiet one, because answering "no reason to hold" is what clears
        # the hold timer; skipping it would leave a stale start time and let the next alert through
        # with no delay at all (the phone's own comment at MainActivity.kt:4759-4761).
        held = not self.situation.allow(eyelid_evidence, sf, now_ms)
        return code, counted, held

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
        # MainActivity.kt:3901-3907: the absence is only a break after 20 seconds of it, and only
        # for a calibrated person. Before that the work clock keeps running through the gap, so a
        # student who briefly looks down does not get a free 20 more minutes.
        if self._face_lost_since_ms == 0:
            self._face_lost_since_ms = now_ms
        if self.calibrated and now_ms - self._face_lost_since_ms >= BREAK_DETECT_MS:
            self._work_start_ms = 0

    def _result(self, student_id, student_name, identity_conf, measurable, situation,
                sei_val, perclos_val, calibrated=True) -> PersonResult:
        result = PersonResult(
            track_id=self.track_id, student_id=student_id, student_name=student_name,
            identity_conf=identity_conf, measurable=measurable, calibrated=calibrated,
            sei=sei_val, situation=situation, eye_valid=False, openness=1.0,
            perclos=perclos_val, bpm=None, distance_cm=None, yaw=None, pitch=None,
            alert=None, features={},
        )
        # The phone's HUD has a line for every one of these three states (no face, still learning
        # this person, measuring), so the payload has to exist even when nothing is measured yet.
        # "calibrating" follows the pipeline's own persistent flag, not the per-frame parameter: a
        # person who has never finished calibration must not be shown as complete while they are
        # simply out of frame. Everything left unset stays at its dash-friendly default.
        result.hud = HudState(
            calibrating=not self.calibrated, calib_progress=self._cal_frames,
            calib_total=self.CALIB_SAMPLES, baseline_ear=self.eye.baseline_ear,
            sei=sei_val, perclos=perclos_val,
            session_seconds=(max(0, (self._last_now_ms - self._monitoring_start_ms) // 1000)
                             if self._monitoring_start_ms else 0),
            average_sei=self.sei.average_sei(), min_sei=self.sei.min_sei,
            distance_scale=self.distance_scale, yawns_recent=len(self._yawn_ts),
            blink_total=self._blink_total,
        )
        return result

    def _prune_blinks(self, now_ms: int) -> None:
        cutoff = now_ms - self.BPM_WINDOW_MS
        while self._blink_ts and self._blink_ts[0] < cutoff:
            self._blink_ts.popleft()

    def blinks_since(self, ts_ms: int) -> List[Tuple[int, int]]:
        """Các nhịp chớp mắt (timestamp, duration_ms) MỚI HƠN ts_ms, chỉ đọc.

        TicWatch (theo dõi dấu hiệu giật lặp lại - tài liệu Tic) đọc cùng một nguồn
        với BPM của điện thoại thay vì tự đếm lại: một phép đo, hai người đọc.
        Không thay đổi bất kỳ số liệu parity nào.
        """
        return [(t, d) for t, d in zip(self._blink_ts, self._blink_dur) if t > ts_ms]
