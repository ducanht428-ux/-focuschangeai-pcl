"""Tests for the measurement layer: head pose, object ownership, distance scale,
and the two micro-expression durations.

Pure logic again - no numpy, no cv2, no model weights. Run from the edge-server folder:

    python tests/test_measurement.py

Each case is a defect the camera leg had against the phone, so the assertions are written
from the Android side's rule (file and line quoted), not from whatever this code does.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.constants import (
    BROW_INNER_L,
    BROW_INNER_R,
    CHIN,
    FACE_LEFT_SIDE,
    FACE_RIGHT_SIDE,
    FOREHEAD,
    EYE_L_OUT,
    EYE_R_OUT,
    LM_COUNT,
    MOUTH_L,
    MOUTH_LOW,
    MOUTH_R,
    MOUTH_UP,
    MuscleSensitivity,
)
from focusedge.detection import PersonTrack, assign_objects
from focusedge.landmarks import Landmarks, estimate_distance_cm, focal_px, head_pose_deg
from focusedge.person_pipeline import PersonPipeline

def _face(spots=None):
    """A 478-point landmark set with only the named indices given coordinates.

    spots maps index -> (x, y, z) in normalized coordinates, the same flat [x,y,z,...] layout
    the Android side and face_landmarker.py both produce. Indices the tests care about are the
    four computeHeadPose reads: 234 and 454 for the cheeks, 10 and 152 for forehead and chin.
    """
    data = [0.0] * (LM_COUNT * 3)
    for idx, (x, y, z) in (spots or {}).items():
        data[idx * 3] = x
        data[idx * 3 + 1] = y
        data[idx * 3 + 2] = z
    return Landmarks(data, LM_COUNT)


def _pose_lms(left_z=0.0, right_z=0.0, chin_z=0.0, forehead_z=0.0, tilt=0.0):
    """A flat frontal face: cheeks level at y 0.5, forehead above chin, both z from the caller."""
    return _face({
        FACE_RIGHT_SIDE: (0.30, 0.50 - tilt, right_z),
        FACE_LEFT_SIDE: (0.70, 0.50 + tilt, left_z),
        FOREHEAD: (0.50, 0.20, forehead_z),
        CHIN: (0.50, 0.80, chin_z),
    })


def test_frontal_face_is_zero_degrees():
    # computeHeadPose on a face plane parallel to the image: normal straight at the camera.
    yaw, pitch, roll = head_pose_deg(_pose_lms(), 200, 200)
    assert abs(yaw) < 1e-6, yaw
    assert abs(pitch) < 1e-6, pitch
    assert abs(roll) < 1e-6, roll


def test_turned_head_yaw_matches_the_unnormalised_cross_product():
    # Left cheek pushed back in z (a head turned away on that side). The angle is checked against
    # atan2 of the raw cross product, computed here by hand, so it tests the port's sign handling
    # rather than re-running the same arithmetic.
    # r = (40, 0, -20), u = (0, -60, 0) at w = h = 100 -> n = (-1200, 0, -2400).
    lm = _pose_lms(left_z=-0.2)
    yaw, pitch, roll = head_pose_deg(lm, 100, 100)
    assert abs(yaw - math.degrees(math.atan2(-1200.0, 2400.0))) < 1e-9, yaw
    assert yaw < 0.0
    assert abs(pitch) < 1e-6, pitch


def test_tilted_head_roll_sign():
    # roll = atan2(ry, |rx|) (MainActivity.kt:3298): the cheek line dropping, not the face turning.
    lm = _pose_lms(tilt=0.05)
    _yaw, _pitch, roll = head_pose_deg(lm, 100, 100)
    assert abs(roll - math.degrees(math.atan2(10.0, 40.0))) < 1e-9, roll


def test_degenerate_face_plane_is_not_measured():
    # Every point at the origin: the two vectors are zero, so there is no face normal and no angle.
    # The old code would still have returned numbers; reporting them as a frontal head is the exact
    # failure mode that makes a garbage frame look like a calm student.
    assert head_pose_deg(_face(), 200, 200) is None
    # A landmark set that does not reach index 454 has no cheek points at all.
    short = Landmarks([0.5, 0.5, 0.0] * 100, 100)
    assert head_pose_deg(short, 200, 200) is None


def test_distance_uses_the_frame_ruler_not_the_crop_ruler():
    """A face box is a pixel window of the frame, so it shares the frame's focal length.

    The bug: focal_px(w) with w = crop width (~200) instead of the frame (~1280) reported every
    desk 6.4x closer, and the error changed with the size of the YOLO box around the head.
    """
    lm = _face({
        EYE_R_OUT: (0.45, 0.5, 0.0),
        EYE_L_OUT: (0.55, 0.5, 0.0),
    })
    by_crop = estimate_distance_cm(lm, 200, 200, focal_px(200), 0.0)
    by_frame = estimate_distance_cm(lm, 200, 200, focal_px(1280), 0.0)
    assert abs(by_frame / by_crop - 1280.0 / 200.0) < 1e-9
    # 1280 px across a 60 deg field of view is the only ruler the 60 deg assumption is true for.
    assert abs(focal_px(1280) / focal_px(200) - 6.4) < 1e-9


def _eyes(spots):
    """Add the two outer eye corners a face-width ratio needs, at x 0.25 / 0.75, y 0.4."""
    s = dict(spots)
    s[EYE_R_OUT] = (0.25, 0.40, 0.0)
    s[EYE_L_OUT] = (0.75, 0.40, 0.0)
    return s


def test_mar_is_lip_gap_over_mouth_width():
    """pixelDistance(13, 14) / pixelDistance(78, 308), exactly MainActivity.kt:4186-4190.

    The server used to return the jawOpen blendshape whenever blendshapes exist, and the mouth
    height over the EYE span when they did not. Neither is the phone's MAR: the first is the
    phone's second yawn clause, the second is divided by the wrong landmark pair, so HabitFeature
    MAR meant a third thing here and the 0.55 yawn trigger was compared against it.
    """
    p = _pipeline()
    lm = _face(_eyes({
        MOUTH_R: (0.30, 0.50, 0.0),
        MOUTH_L: (0.70, 0.50, 0.0),
        MOUTH_UP: (0.50, 0.45, 0.0),
        MOUTH_LOW: (0.50, 0.55, 0.0),
    }))
    # width 0.4 * 200 px = 80, gap 0.1 * 200 px = 20 -> 0.25
    assert abs(p._mar(lm, 200, 200) - 0.25) < 1e-9
    # A collapsed mouth box must report a shut mouth (the phone's < 1 px guard), not divide by it:
    # 0.0 keeps the yawn clock reset instead of starting a burst from a 0.6 wide sliver.
    shut = _face({MOUTH_R: (0.5, 0.5, 0.0), MOUTH_L: (0.5, 0.5, 0.0),
                  MOUTH_UP: (0.5, 0.2, 0.0), MOUTH_LOW: (0.5, 0.8, 0.0)})
    assert p._mar(shut, 200, 200) == 0.0


def test_bfr_is_inner_brow_spacing_over_face_width():
    """pixelDistance(55, 285) / faceWidthPx, i.e. brow WIDTH over eye span (MainActivity.kt:4182, :4020).

    The old server reading was the average vertical brow-to-lower-lid gap over eye span (66/107 and
    300/336). Both drop when a student frowns, but they are not the same number, and BFR_FURROW_RATIO
    0.88 - a ceiling measured on the phone's ratio - was being applied to the other one.
    """
    p = _pipeline()
    lm = _face(_eyes({
        BROW_INNER_R: (0.42, 0.30, 0.0),
        BROW_INNER_L: (0.58, 0.30, 0.0),
    }))
    # brow 0.16 * 200 = 32 px over eye span 0.5 * 200 = 100 px -> 0.32
    assert abs(p._bfr(lm, 200, 200) - 0.32) < 1e-9
    # A vertical drop of both brows changes nothing, because only the spacing between the two inner
    # corners is read. This fixture also leaves the four landmarks the old formula used (66, 107,
    # 300, 336) at zero: read through that formula this face would score about 1.0, not 0.32.
    moved = _face(_eyes({
        BROW_INNER_R: (0.42, 0.40, 0.0),
        BROW_INNER_L: (0.58, 0.40, 0.0),
    }))
    assert abs(p._bfr(moved, 200, 200) - 0.32) < 1e-9


def _track(tid, box):
    return PersonTrack(track_id=tid, bbox=box, conf=0.9)


def test_object_belongs_to_the_tightest_person_box():
    # Two overlapping students, a phone whose centre is inside both. The first version took whoever
    # YOLO listed first and stopped looking, so the wrong student carried the phone-in-hand context.
    near = _track(1, (0, 0, 100, 300))            # 30 000 px2, listed first
    wide = _track(2, (0, 0, 400, 300))            # 120 000 px2, the neighbour's whole row
    objects = [("phone", (40, 100, 60, 140), 0.7)]
    assign_objects([wide, near], objects)
    assert near.nearby_objects.get("phone") == 0.7
    assert "phone" not in wide.nearby_objects


def test_object_outside_every_person_stays_unassigned():
    a = _track(1, (0, 0, 100, 100))
    left = assign_objects([a], [("book", (500, 500, 560, 560), 0.6)])
    assert left == [("book", (500, 500, 560, 560), 0.6)]
    assert "book" not in a.nearby_objects


def _pipeline(level=1):
    p = PersonPipeline(7, None, level)
    p.baseline_bfr = 0.2
    return p


def test_a_single_wide_mouth_frame_is_not_a_yawn():
    """YAWN_MIN_MS = 1200 (MainActivity.kt:320) has to be held before it counts.

    Before this port the server alerted on one frame, which is also the frame a talking student
    produces.
    """
    p = _pipeline()
    confirmed, confused, recent, furrow = p._update_micro_expressions(
        0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0, 1000)
    assert (confirmed, confused, recent, furrow) == (False, False, 0, False)
    confirmed = p._update_micro_expressions(0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0, 1600)[0]
    assert confirmed is False          # 600 ms in, still under 1200
    confirmed = p._update_micro_expressions(0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0, 2300)[0]
    assert confirmed is True           # 1300 ms held


def test_a_long_yawn_is_counted_once_and_a_break_starts_a_new_burst():
    p = _pipeline()
    args = (0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0)
    p._update_micro_expressions(*args, 1000)
    p._update_micro_expressions(*args, 2300)
    assert p._update_micro_expressions(*args, 2400)[2] == 1   # same burst, not a second yawn
    assert p._update_micro_expressions(*args, 3600)[2] == 1   # still one continuous burst
    p._update_micro_expressions(0.1, [0, 0, 0, 0, 0.0], 0.2, 0.0, 3700)   # mouth closes
    assert p._update_micro_expressions(*args, 3800)[0] is False  # new burst must re-fill the time
    assert p._update_micro_expressions(*args, 5100)[0] is True
    assert p._update_micro_expressions(*args, 5100)[2] == 2


def test_sensitivity_shortens_the_yawn_window_the_way_it_does_on_the_phone():
    # sensTime() level 3 = 0.35, so 1200 ms becomes 420 ms. Level 0 (1.5) wants 1800 ms.
    # Timestamps start at 1000, never 0: 0 is the "burst not started" sentinel in both engines.
    fast = _pipeline(3)
    assert fast.sens_time == 0.35
    assert fast._update_micro_expressions(0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0, 1000)[0] is False
    assert fast._update_micro_expressions(0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0, 1500)[0] is True
    slow = _pipeline(0)
    assert slow.sens_time == 1.5
    slow._update_micro_expressions(0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0, 1000)
    assert slow._update_micro_expressions(0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0, 2500)[0] is False
    assert slow._update_micro_expressions(0.9, [0, 0, 0, 0, 0.1], 0.2, 0.0, 2900)[0] is True


def test_sustained_frown_needs_a_frontal_face_and_two_seconds():
    # BFR_FURROW_RATIO 0.88 of this person's baseline brow fill, HEAD_FRONTAL_YAW_DEG 20 wide,
    # BFR_SUSTAIN_MS 2000 (MainActivity.kt:310-311, 4454-4466).
    p = _pipeline()
    lowered = 0.2 * 0.87          # just under the 0.88 trigger
    assert p._update_micro_expressions(0.1, None, lowered, 0.0, 1000)[1] is False
    assert p._update_micro_expressions(0.1, None, lowered, 0.0, 2900)[1] is False
    assert p._update_micro_expressions(0.1, None, lowered, 0.0, 3100)[1] is True
    # The same brow drop while the head is turned 30 degrees is not a frown: brow geometry reads
    # lower when the face is tipped, which is why the phone gates it by yaw.
    q = _pipeline()
    for t in (1000, 2000, 3100, 4200):
        assert q._update_micro_expressions(0.1, None, lowered, 30.0, t)[1] is False
    # A brow at or above the trigger never counts, however long it lasts.
    r = _pipeline()
    for t in (1000, 5000):
        assert r._update_micro_expressions(0.1, None, 0.2 * 0.88, 0.0, t)[1] is False


def test_furrow_sensitivity_moves_the_two_knobs_in_opposite_directions():
    # FocusCore.kt:481 and :487. The trigger is a ceiling, so a higher level RAISES it; the yaw
    # window is an acceptance window, so a higher level WIDENS it.
    assert MuscleSensitivity.furrow_ratio(1, 0.88) == 0.88
    assert abs(MuscleSensitivity.furrow_ratio(3, 0.88) - (1.0 - 0.12 * 0.70)) < 1e-9
    assert abs(MuscleSensitivity.furrow_ratio(0, 0.88) - (1.0 - 0.12 * 1.15)) < 1e-9
    assert MuscleSensitivity.furrow_yaw_window(1, 20.0) == 20.0
    assert abs(MuscleSensitivity.furrow_yaw_window(3, 20.0) - 20.0 / 0.70) < 1e-9
    assert abs(MuscleSensitivity.furrow_yaw_window(0, 20.0) - 20.0 / 1.15) < 1e-9
    # Level 0 is the least sensitive: a stricter brow drop and a narrower frontal window.
    assert MuscleSensitivity.furrow_ratio(0, 0.88) < 0.88 < MuscleSensitivity.furrow_ratio(3, 0.88)
    assert MuscleSensitivity.furrow_yaw_window(0, 20.0) < MuscleSensitivity.furrow_yaw_window(3, 20.0)


def main():
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failed = 0
    for fn in tests:
        try:
            fn()
            print("ok   %s" % fn.__name__)
        except Exception as exc:  # noqa: BLE001 - a test runner should report, not raise
            failed += 1
            print("FAIL %s: %s" % (fn.__name__, exc))
    print("%d passed, %d failed" % (len(tests) - failed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
