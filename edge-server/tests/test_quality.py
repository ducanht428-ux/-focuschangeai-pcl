"""Tests for the ported signal-quality number and the box it is measured on.

Pure logic only: no numpy, no cv2, no model weights, no GPU. Run it with

    python tests/test_quality.py

from the edge-server folder, or with pytest if it happens to be installed. This is
deliberate - the whole point is that a broken quality number can be caught on a laptop
that has never downloaded YOLO.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.classroom import ClassroomPipeline
from focusedge.constants import HabitFeature
from focusedge.habit import HabitProfile, HabitSession
from focusedge.quality import exposure_flags, signal_quality


def _good(**kw):
    """A frame with nothing wrong with it, then whatever the case wants to break."""
    args = dict(luma_mean=120.0, too_small=False, yaw_abs=0.0, pitch_abs=0.0, iris_ok=True)
    args.update(kw)
    return signal_quality(**args)


def test_clean_frame_is_100():
    assert _good() == 100


def test_dark_and_blown_out_frames_are_penalised():
    assert _good(luma_mean=20.0) == 40      # -60, LUMA_VERY_LOW
    assert _good(luma_mean=50.0) == 65      # -35, LUMA_LOW
    assert _good(luma_mean=235.0) == 80     # -20, LUMA_HIGH


def test_small_face_is_penalised_harder_than_the_phone_would():
    # The phone would dock 30 for a face between 25 and 40 px; the camera leg docks 50.
    # Documented in quality.py: it may be stricter than the phone, never more trusting.
    assert _good(too_small=True) == 50


def test_pose_and_iris_docks_and_the_floor():
    assert _good(yaw_abs=40.0) == 80
    assert _good(pitch_abs=35.0) == 80
    assert _good(iris_ok=False) == 90
    # Everything wrong at once must not go below zero.
    assert _good(luma_mean=10.0, too_small=True, yaw_abs=60.0, iris_ok=False,
                 backlit=True, overexposed=True) == 0


def test_exposure_flags_match_the_phone_predicates():
    # with a face box: overexposed from the ROI bright fraction alone
    assert exposure_flags(120.0, 0.05, 100.0, 0.20, True) == (False, True)
    assert exposure_flags(120.0, 0.05, 100.0, 0.15, True) == (False, False)
    # no face box: only a much larger blown-out share counts
    assert exposure_flags(120.0, 0.35, 120.0, 0.20, False) == (False, True)
    assert exposure_flags(120.0, 0.25, 120.0, 0.25, False) == (False, False)
    # backlit needs the room to be brighter than the face AND the face to be dark
    assert exposure_flags(180.0, 0.0, 90.0, 0.0, True) == (True, False)    # gap 90, face < 95
    assert exposure_flags(180.0, 0.0, 100.0, 0.0, True) == (False, False)  # face not dark enough
    assert exposure_flags(180.0, 0.0, 90.0, 0.0, False) == (False, False)  # no box, no claim


def test_session_without_quality_samples_is_rejected_and_with_them_is_not():
    """The exact bug this file exists for.

    habit.py compares the session's mean quality against HabitConfig.MIN_QUALITY, and a session
    that never recorded one sits at 0.0 - so before quality.py existed, every camera session was
    ineligible and no band could ever be learned, whatever the camera actually saw.
    """
    def run(minutes=3.0, with_quality=True):
        hs = HabitSession(1)
        t = 0
        step = 500
        while t < int(minutes * 60_000):
            hs.tick(t, True, True)
            hs.observe(HabitFeature.SEI, 70.0)
            if with_quality:
                hs.add_quality(80.0)
            t += step
        return hs.to_summary()

    profile = HabitProfile("stu-1", "Test")
    broken = run(with_quality=False)
    fixed = run(with_quality=True)

    assert broken.quality == 0.0
    assert profile.ineligible_reason(broken) is not None
    assert fixed.quality_count > 0
    assert profile.ineligible_reason(fixed) is None


def test_face_region_rejects_a_face_cut_by_the_frame_edge():
    # Constructible with no models at all: every heavy object is injected.
    cp = ClassroomPipeline({}, None, None, None, None, None, None)
    w, h = 1000, 1000

    # Fully inside the frame.
    assert cp._face_region((100, 100, 300, 500), w, h) is not None

    # 4.5% of the wanted square falls off the right edge: still a whole face, keep it.
    assert cp._face_region((800, 100, 1000, 500), w, h) is not None

    # 27% off: the head is cut, and the eye ratio plus the distance would both be read
    # from a box the camera never actually captured.
    assert cp._face_region((900, 100, 1000, 500), w, h) is None


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
