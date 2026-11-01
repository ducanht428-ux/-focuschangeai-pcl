"""Per-frame signal quality, ported from the phone.

Sources: computeSignalQuality() in MainActivity.kt (the penalty table) and
measure()/process() in EyeEngine.kt (the luminance sampler). The thresholds live in
constants.SignalQuality, with the same values, because the mean of this score is what
HabitConfig.MIN_QUALITY compares against - a camera-side number built from a different
table would reject good sessions or accept bad ones for reasons nobody can trace.

One deliberate difference, stated instead of hidden:

  The phone docks 50 points below VERY_SMALL_FACE_PX (25 px) and 30 below MIN_FACE_PX
  (40 px), both measured on ITS OWN face box, which a classroom camera does not have.
  Here that whole branch collapses into one boolean - eye span below the operator's
  scheduler floor - and it applies the harsher -50. So a frame the phone would only dock
  30 for is docked 50 here: the camera leg can reject more, never trust more.

Nothing in this module runs before the caller hands it pixels, so the arithmetic stays
testable without numpy, cv2 or a model.
"""
from __future__ import annotations

from typing import Optional, Tuple

from .constants import SignalQuality as SQ

# What process() in EyeEngine.kt returns when it cannot read the plane at all. The
# thresholds are tuned around these numbers, so an unreadable frame is not the same as
# a black one and must not be scored as one.
_UNREADABLE = (128.0, 50.0, 0.0)


def to_gray(frame_bgr):
    """BGR frame to a single-channel plane, the equivalent of the phone's Y plane."""
    import cv2  # lazy, like every other third-party import in this package
    return cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)


def measure_luma(gray, x0: int, y0: int, x1: int, y1: int, step: int) -> Tuple[float, float, float]:
    """Mean, standard deviation and blown-out fraction of a step-sampled region.

    Mirrors measure() in EyeEngine.kt: every step-th row and column of the region, a pixel
    counts as blown out above BRIGHT_PIXEL, population std. An empty region returns the
    same fallback the phone records rather than NaN, because a NaN reaching the penalty
    table would compare false against every threshold and hand out a perfect 100.
    """
    region = gray[max(0, y0):max(0, y1):step, max(0, x0):max(0, x1):step]
    if region.size == 0:
        return _UNREADABLE
    vals = region.astype("float64")
    mean = float(vals.mean())
    std = float(vals.std())
    bright = float((region > SQ.BRIGHT_PIXEL).sum()) / float(region.size)
    return mean, std, bright


def exposure_flags(global_mean: float, global_bright: float,
                   face_mean: float, face_bright: float,
                   has_roi: bool) -> Tuple[bool, bool]:
    """(backlit, overexposed) exactly as line 711-717 of EyeEngine.kt sets them.

    Without a usable ROI the phone has nothing to compare the room against, so it only
    calls a frame overexposed when a much larger share of it is blown out.
    """
    overexposed = bool(
        (face_bright > SQ.ROI_BRIGHT_FRAC) if has_roi
        else (global_bright > SQ.GLOBAL_BRIGHT_FRAC)
    )
    backlit = bool(
        has_roi
        and (global_mean - face_mean) > SQ.BACKLIT_GAP
        and face_mean < SQ.BACKLIT_FACE_MAX
    )
    return backlit, overexposed


def face_light(gray, box, frame_w: int, frame_h: int) -> Optional[Tuple[float, bool, bool]]:
    """The three light facts of one face box: (luma mean, backlit, overexposed).

    Returns None when the box is unusable, which the caller passes on as "no light data"
    instead of inventing a bright, evenly lit room.
    """
    if box is None:
        return None
    x0, y0, x1, y1 = (int(box[0]), int(box[1]), int(box[2]), int(box[3]))
    g_mean, _g_std, g_bright = measure_luma(gray, 0, 0, frame_w, frame_h, SQ.GLOBAL_STEP)
    has_roi = (x1 - x0) >= SQ.MIN_REGION and (y1 - y0) >= SQ.MIN_REGION
    if has_roi:
        f_mean, _f_std, f_bright = measure_luma(gray, x0, y0, x1, y1, SQ.ROI_STEP)
    else:
        # Same fallback as the phone: no face box means the frame IS the region.
        f_mean, f_bright = g_mean, g_bright
    backlit, overexposed = exposure_flags(g_mean, g_bright, f_mean, f_bright, has_roi)
    return f_mean, backlit, overexposed


def signal_quality(luma_mean: float, too_small: bool, yaw_abs: float, pitch_abs: float,
                   iris_ok: bool, backlit: bool = False,
                   overexposed: bool = False) -> int:
    """0-100 confidence in this frame, same deductions in the same order as the phone.

    yaw_abs / pitch_abs are the DEVIATION from this person's neutral pose, smoothed - which is
    what the phone feeds this function despite the parameter being named yawAbs: MainActivity.kt
    passes yawDeg, and yawDeg is yawFilter.filter(yawRawDev) (MainActivity.kt:4148, call at :4195).
    The phone also zeroes both angles until calibration is done, so this is only ever called with
    a settled baseline - person_pipeline.py returns before scoring during calibration.
    """
    q = 100.0

    if luma_mean < SQ.LUMA_VERY_LOW:
        q -= 60.0
    elif luma_mean < SQ.LUMA_LOW:
        q -= 35.0
    elif luma_mean > SQ.LUMA_HIGH:
        q -= 20.0

    if too_small:
        q -= 50.0

    if backlit:
        q -= 15.0
    if overexposed:
        q -= 20.0

    # The phone snapshots "reliable" at this point, before the pose and iris docks
    # (MainActivity.kt:3347). The camera leg has no such flag: measurable in
    # person_pipeline.py already plays that part, so the order is kept for reading only.
    if yaw_abs > SQ.YAW_UNRELIABLE_DEG or pitch_abs > SQ.PITCH_UNRELIABLE_DEG:
        q -= 20.0

    if not iris_ok:
        q -= 10.0

    return int(max(0.0, min(100.0, q)))
