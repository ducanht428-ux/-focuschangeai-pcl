"""Landmark container + face geometry, ported from FocusCore.kt / MainActivity.kt.

A ``Landmarks`` is one flat array ``[x0,y0,z0, x1,y1,z1, ...]`` in normalized image
coordinates (0..1), exactly like the Android side, so the ported engines read it
unchanged. The geometry helpers reproduce the app's head-pose / gaze / distance math.
"""
from __future__ import annotations

import math
from typing import Optional, Tuple

from .constants import (
    CHIN,
    EYE_L_IN,
    EYE_L_OUT,
    EYE_OUTER_WIDTH_CM,
    EYE_R_IN,
    EYE_R_OUT,
    FACE_LEFT_SIDE,
    FACE_RIGHT_SIDE,
    FOREHEAD,
    L_IRIS,
    LM_COUNT,
    R_IRIS,
    REF_DISTANCE_CM,
)


class Landmarks:
    __slots__ = ("data", "count")

    def __init__(self, data, count: int) -> None:
        # Flatten any (x,y,z) iterable-of-iterables into one float list.
        if isinstance(data, (list, tuple)) and data and not isinstance(data[0], (int, float)):
            flat = []
            for p in data:
                flat.append(float(p[0]))
                flat.append(float(p[1]))
                flat.append(float(p[2]) if len(p) > 2 else 0.0)
            data = flat
        self.data = data
        self.count = count

    def x(self, i: int) -> float:
        return self.data[i * 3]

    def y(self, i: int) -> float:
        return self.data[i * 3 + 1]

    def z(self, i: int) -> float:
        return self.data[i * 3 + 2]


EMPTY_LANDMARKS = Landmarks([], 0)


def point_dist_px(lm: Landmarks, a: int, b: int, w: int, h: int) -> float:
    """Pixel distance between two landmarks - the app's pixelDistance()."""
    dx = (lm.x(a) - lm.x(b)) * w
    dy = (lm.y(a) - lm.y(b)) * h
    return math.sqrt(dx * dx + dy * dy)


def eye_corner_span_px(lm: Landmarks, w: int, h: int) -> float:
    """Distance between the two outer eye corners - the app's face-width proxy (faceWidthPx)."""
    return point_dist_px(lm, EYE_R_OUT, EYE_L_OUT, w, h)


def estimate_distance_cm(lm: Landmarks, w: int, h: int, focal_px: float, yaw_abs_deg: float) -> Optional[float]:
    """Raw (pre-scale) distance from the eye-corner span, yaw-compensated (MainActivity)."""
    corner_px = eye_corner_span_px(lm, w, h)
    if corner_px < 1.0:
        return None
    yaw = min(abs(yaw_abs_deg), 72.0)
    cos_y = max(math.cos(math.radians(yaw)), 0.3)
    return EYE_OUTER_WIDTH_CM * focal_px / (corner_px / cos_y)


def head_pose_deg(lm: Landmarks, w: int, h: int) -> Optional[Tuple[float, float, float]]:
    """yaw / pitch / roll in degrees: a port of computeHeadPose() (MainActivity.kt:3273).

    Same three vectors as the phone. The horizontal one joins the two sides of the face plane
    (454 - 234), the vertical one joins forehead to chin (10 - 152), both taken in pixels and
    including MediaPipe's z, and their cross product is the direction the face is pointing.
    Yaw and pitch are read off that normal, roll off the cheek line. Convention as in the app:
    yaw positive = turned toward the subject's left, pitch positive = bending down.

    Replaces a two-dimensional guess (nose offset across the eye line, multiplied by 2.2 to look
    like degrees). It agreed with the phone in sign but not in scale, which meant the thresholds
    in constants.py - the 28 degree eye gate, the 18 degree look-away gate, the furrow window -
    were each enforced against a different ruler on the two devices, so a head turn could alert
    on one and pass on the other.

    Returns None when the plane degenerates. That is an unmeasurable frame, not a frontal head:
    the phone keeps its previous angles in that case, but a classroom camera must not carry an
    old pose into a new frame, because the next alert would be judged by geometry nobody saw.
    """
    if lm.count <= FACE_LEFT_SIDE:
        return None
    wf = float(w)
    hf = float(h)

    rx = (lm.x(FACE_LEFT_SIDE) - lm.x(FACE_RIGHT_SIDE)) * wf
    ry = (lm.y(FACE_LEFT_SIDE) - lm.y(FACE_RIGHT_SIDE)) * hf
    rz = (lm.z(FACE_LEFT_SIDE) - lm.z(FACE_RIGHT_SIDE)) * wf

    ux = (lm.x(FOREHEAD) - lm.x(CHIN)) * wf
    uy = (lm.y(FOREHEAD) - lm.y(CHIN)) * hf
    uz = (lm.z(FOREHEAD) - lm.z(CHIN)) * wf

    nx = ry * uz - rz * uy
    ny = rz * ux - rx * uz
    nz = rx * uy - ry * ux

    length = math.sqrt(nx * nx + ny * ny + nz * nz)
    if length < 1e-6:
        return None

    fx = nx / length
    fy = ny / length
    fz = nz / length

    yaw = math.degrees(math.atan2(fx, -fz))
    pitch = math.degrees(math.atan2(fy, math.sqrt(fx * fx + fz * fz)))
    roll = math.degrees(math.atan2(ry, max(abs(rx), 0.0001)))
    return yaw, pitch, roll


def gaze_offsets(lm: Landmarks, w: int, h: int) -> "tuple[Optional[float], Optional[float]]":
    """Horizontal / vertical iris offset within each eye, normalized by eye width.

    Returns (off_h, off_v) averaged over both eyes, or (None, None) when the iris
    landmarks are not present (FaceMesh without refine_landmarks).
    """
    if lm.count < LM_COUNT:
        return None, None
    offs_h = []
    offs_v = []
    rcx, rcy = _iris_center(lm, R_IRIS, w, h)
    lcx, lcy = _iris_center(lm, L_IRIS, w, h)
    if rcx is None or lcx is None:
        return None, None
    for (icx, icy, out_i, in_i) in ((rcx, rcy, EYE_R_OUT, EYE_R_IN), (lcx, lcy, EYE_L_IN, EYE_L_OUT)):
        cx = (lm.x(out_i) + lm.x(in_i)) / 2.0 * w
        cy = (lm.y(out_i) + lm.y(in_i)) / 2.0 * h
        ew = max(1.0, abs(lm.x(out_i) - lm.x(in_i)) * w)
        eh = max(1.0, _eye_height(out_i, in_i, lm, w, h))
        offs_h.append((icx - cx) / ew)
        offs_v.append((icy - cy) / eh)
    off_h = sum(offs_h) / len(offs_h)
    off_v = sum(offs_v) / len(offs_v)
    return off_h, off_v


def _iris_center(lm: Landmarks, ring, w: int, h: int):
    if lm.count < max(ring) + 1:
        return None, None
    xs = [lm.x(i) for i in ring]
    ys = [lm.y(i) for i in ring]
    return sum(xs) / len(xs) * w, sum(ys) / len(ys) * h


def _eye_height(out_i: int, in_i: int, lm: Landmarks, w: int, h: int) -> float:
    # vertical extent approximated from eyelid landmarks 159/145 (right) or 386/374 (left)
    up = 159 if out_i == EYE_R_OUT else 386
    low = 145 if out_i == EYE_R_OUT else 374
    return abs(lm.y(up) - lm.y(low)) * h


def focal_px(frame_w: int, horizontal_fov_deg: float = 60.0) -> float:
    """Pinhole focal length in pixels from a nominal horizontal FOV (app uses ~60 deg)."""
    return frame_w / (2.0 * math.tan(math.radians(horizontal_fov_deg / 2.0)))
