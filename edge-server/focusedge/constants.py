"""Shared constants, ported 1:1 from the Android app so a value means the same thing.

Sources:
  HabitFeature / HabitConfig  <- HabitModel.kt
  Situation                   <- SituationEngine.kt
  EyeEngine landmark indices  <- EyeEngine.kt
  SEI weights + thresholds    <- MainActivity.kt (companion object)
"""
from __future__ import annotations

from enum import IntEnum


# ---------------------------------------------------------------------------
# The 12 learned features (HabitFeature in HabitModel.kt). Index order is the
# contract with the CSV header and the Firestore columns - never reorder.
# ---------------------------------------------------------------------------
class HabitFeature:
    BPM = 0
    BLINK_MS = 1
    DIST = 2
    YAW = 3
    PITCH = 4
    GAZE_H = 5
    GAZE_V = 6
    MAR = 7
    BFR = 8
    EAR = 9
    PERCLOS = 10
    SEI = 11
    COUNT = 12

    # Per-observation clipping (winsorize) so one noisy frame cannot drag a profile off.
    LO = [0.0, 40.0, 10.0, -60.0, -60.0, -0.5, -0.6, 0.0, 0.1, 0.0, 0.0, 0.0]
    HI = [60.0, 1000.0, 150.0, 60.0, 60.0, 0.5, 0.6, 1.2, 1.5, 0.6, 100.0, 100.0]
    MIN_SPREAD = [2.0, 20.0, 3.0, 2.0, 2.0, 0.02, 0.03, 0.02, 0.01, 0.01, 1.5, 3.0]

    KEYS = [
        "blink_bpm", "avg_blink_ms", "distance_cm", "yaw_deg", "pitch_deg", "gaze_h",
        "gaze_v", "mar", "bfr", "ear", "perclos_p60", "sei",
    ]

    # The eight features checked two-sided on every frame.
    BAND_FEATURES = [BPM, YAW, PITCH, GAZE_H, GAZE_V, MAR, PERCLOS, SEI]


# ---------------------------------------------------------------------------
# Habit learning bars (HabitConfig in HabitModel.kt).
# ---------------------------------------------------------------------------
class HabitConfig:
    MIN_SESSION_MS = 2 * 60 * 1000
    MIN_FACE_RATIO = 0.70
    MIN_VALID_RATIO = 0.50
    MIN_QUALITY = 60.0
    MIN_SESSION_SEI = 40.0
    MIN_SESSIONS = 5
    MIN_TOTAL_MS = 10 * 60 * 1000
    MAX_SESSIONS = 40
    MIN_FRAMES_PER_FEATURE = 30
    RECENCY_DECAY = 0.9
    MAX_WEIGHT_MINUTES = 30.0
    DAY_MS = 86_400_000
    MAX_FRAME_GAP_MS = 700
    BAND_K = 2.5


# ---------------------------------------------------------------------------
# Per-frame signal quality (computeSignalQuality in MainActivity.kt and the
# luminance sampler measure()/process() in EyeEngine.kt).
#
# These numbers gate habit learning: HabitConfig.MIN_QUALITY above compares against
# the mean of exactly this score, so a session built from frames that fail here is
# dropped by habit.py instead of being learned from.
# ---------------------------------------------------------------------------
class SignalQuality:
    LUMA_VERY_LOW = 35.0      # MainActivity.kt: mean luminance of the face region
    LUMA_LOW = 60.0
    LUMA_HIGH = 220.0

    # EyeEngine.kt: measure() samples every Nth row/column, and counts a pixel as blown out
    # above 245. Same steps on both sides so the two instruments see the same brightness.
    GLOBAL_STEP = 8
    ROI_STEP = 4
    MIN_REGION = 64           # EyeEngine.kt: a smaller ROI than this is not measured at all
    BRIGHT_PIXEL = 245
    ROI_BRIGHT_FRAC = 0.18
    GLOBAL_BRIGHT_FRAC = 0.30
    BACKLIT_GAP = 45.0        # EyeEngine.kt: globalMean - faceMean that means "window behind"
    BACKLIT_FACE_MAX = 95.0

    # MainActivity.kt: the pose beyond which eye landmarks stop being believable.
    YAW_UNRELIABLE_DEG = 35.0
    PITCH_UNRELIABLE_DEG = 30.0


# ---------------------------------------------------------------------------
# Situations (SituationEngine.kt). csvCode is the on-disk column value.
# ---------------------------------------------------------------------------
class Situation(IntEnum):
    FORWARD = 0
    DOWN = 1
    TILT = 2
    TURNED = 3
    DOZING = 4
    UNKNOWN = 5


# ---------------------------------------------------------------------------
# Face-mesh landmark indices (EyeEngine.kt + MainActivity.kt).
# ---------------------------------------------------------------------------
EYE_R_UP = [161, 160, 159, 158, 157]
EYE_R_LOW = [163, 144, 145, 153, 154]
EYE_L_UP = [388, 387, 386, 385, 384]
EYE_L_LOW = [390, 373, 374, 380, 381]
EYE_R_OUT = 33
EYE_R_IN = 133
EYE_L_IN = 362
EYE_L_OUT = 263
# Iris rings (for gaze offset + distance cross-check).
R_IRIS = list(range(468, 473))   # right iris: 468 center, 469-472 around
L_IRIS = list(range(473, 478))   # left iris:  473 center, 474-477 around
FOREHEAD = 10
CHIN = 152
# Brow / mouth points, with the same indices the app names (MainActivity.kt:227-232). BFR is the
# spacing of the two INNER BROW corners, MAR the lip-to-lip distance over the mouth width.
BROW_INNER_R = 55
BROW_INNER_L = 285
MOUTH_UP = 13
MOUTH_LOW = 14
MOUTH_R = 78
MOUTH_L = 308
# The two sides of the face plane, used as the horizontal vector by computeHeadPose
# (MainActivity.kt:197-198, LM_FACE_RIGHT / LM_FACE_LEFT).
FACE_RIGHT_SIDE = 234
FACE_LEFT_SIDE = 454
LM_COUNT = 478                   # 468 face + 10 iris landmarks

# ---------------------------------------------------------------------------
# SEI weights + component thresholds (MainActivity.kt companion).
# ---------------------------------------------------------------------------
SEI_SMOOTHING = 0.12
FATIGUE_SMOOTHING = 0.10
W_POSTURE = 0.20
W_DISTANCE = 0.15
W_GAZE = 0.20
W_EYES = 0.20
W_BPM = 0.10
W_EXPRESSION = 0.15
SEI_WEIGHTS = [W_POSTURE, W_DISTANCE, W_GAZE, W_EYES, W_BPM, W_EXPRESSION]

LOOK_AWAY_YAW_DEG = 18.0
LOOK_AWAY_PITCH_DEG = 18.0
LOOK_AWAY_MS = 2000
HEAD_FRONTAL_YAW_DEG = 20.0
GAZE_H_DRIFT = 0.16
GAZE_V_DRIFT = 0.22
VISUAL_DRIFT_MS = 3000
MAR_YAWN = 0.55
JAW_OPEN_YAWN = 0.55
# Micro-expression durations (MainActivity.kt:310-321). A single frame of wide mouth or low BFR is
# not a yawn or a frown on either device, so the server needs the same sustain windows the phone
# uses before it can claim the same expression score.
BFR_FURROW_RATIO = 0.88
BFR_SUSTAIN_MS = 2000
YAWN_MIN_MS = 1200
YAWN_WINDOW_MS = 5 * 60 * 1000
# Must stay equal to DROWSY_CLOSURE_MS in MainActivity.kt (1_500L). The server was written at 800,
# which fires the drowsy alert almost twice as early as the phone does; sessions recorded by the two
# devices then cannot be compared at all, and this file's whole purpose is to say "the same
# measurement the Android app produces".
DROWSY_CLOSURE_MS = 1500
# Eye-validity gate, mirroring EYE_MAX_YAW_DEG / EYE_MAX_PITCH_DEG in MainActivity.kt. Both compare
# the deviation from this person's calibration baseline, which is exactly what the phone feeds the eye
# engine (yawDeg there is poseYawAbs - baselineYawDeg, smoothed). The server had 35 degrees where the
# phone has 28, i.e. it keeps measuring eyes through a head turn wide enough to make the lids read as
# closed - the same false "eyes closed" reading on a head turn that the phone had to fix.
EYE_MAX_YAW_DEG = 28.0
EYE_MAX_PITCH_DEG = 35.0
MIN_SAFE_DISTANCE_CM = 30.0
LOW_BLINK_BPM = 12.0
HIGH_BLINK_BPM = 25.0
PERCLOS_ALERT = 30.0
BPM_RELIABLE_AFTER_MS = 20000

SEI_TOO_CLOSE_PENALTY = 15.0
SEI_CHRONIC_PENALTY = 20.0
SEI_WATCH_PENALTY = 8.0
SEI_ANOMALY_CAP = 40.0

# PERCLOS openness cutoffs (MainActivity).
PERCLOS_P70_OPEN = 0.30
PERCLOS_P80_OPEN = 0.20

# Distance geometry: real-world eye-corner span used as the reference width.
# 8.9, not 9.0: EYE_OUTER_WIDTH_CM in MainActivity.kt:237 ("outer-cornus to outer-cornus, not the
# 6.3 IPD"). This number divides straight into every reported distance, so a rounded-up value makes
# the camera leg read about 1% further away than the phone does for the same face.
EYE_OUTER_WIDTH_CM = 8.9
REF_DISTANCE_CM = 50.0


# ---------------------------------------------------------------------------
# MuscleSensitivity (FocusCore.kt) - the level 0..3 control.
# ---------------------------------------------------------------------------
class MuscleSensitivity:
    BLINK_LOAD_FLOOR_MS = 150.0
    BLINK_LOAD_RAMP_MS = 250.0

    @staticmethod
    def factor(level: int) -> float:
        return {0: 1.15, 2: 0.85, 3: 0.70}.get(level, 1.0)

    @staticmethod
    def time_factor(level: int) -> float:
        """sensTime() in MainActivity.kt: how much the slider stretches an alert timer.

        Only the alert branches take this. The SEI eye-score penalty deliberately does NOT,
        on the phone and here - "eyes shut 1,5 s" means the same thing whatever the slider says.
        """
        return {0: 1.5, 2: 0.6, 3: 0.35}.get(level, 1.0)

    @classmethod
    def mouth_threshold(cls, level: int, base: float) -> float:
        return base * cls.factor(level)

    @classmethod
    def furrow_ratio(cls, level: int, base: float) -> float:
        """furrowRatio() in FocusCore.kt:481. The base is a ceiling (a furrow is BFR BELOW it),
        so a higher level has to RAISE the number: 0.88 becomes 0.90."""
        return 1.0 - (1.0 - base) * cls.factor(level)

    @classmethod
    def furrow_yaw_window(cls, level: int, base_deg: float) -> float:
        """furrowYawWindow() in FocusCore.kt:487: an acceptance window, not a trigger, so it
        takes the inverse - level 2 widens it, level 0 narrows it."""
        return base_deg / cls.factor(level)

    @classmethod
    def blink_load_floor_ms(cls, level: int) -> float:
        return cls.BLINK_LOAD_FLOOR_MS * cls.factor(level)

    @classmethod
    def blink_load_ramp_ms(cls, level: int) -> float:
        return cls.BLINK_LOAD_RAMP_MS * cls.factor(level)

    @classmethod
    def blink_load(cls, level: int, avg_blink_ms: float) -> float:
        lo = cls.blink_load_floor_ms(level)
        ramp = cls.blink_load_ramp_ms(level)
        return _clamp((avg_blink_ms - lo) / ramp, 0.0, 1.0)


def _clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def clamp01(v: float) -> float:
    """clamp01 in the Kotlin app: NaN-safe enough for scoring (NaN -> 0)."""
    if v != v:  # NaN
        return 0.0
    return max(0.0, min(1.0, v))
