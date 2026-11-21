"""YOLO stride: eye measurement must outrun the detection budget (guide item 2).

Pure logic - a counting fake detector and analyzer, the real scheduler, no camera, no
models. Run from the edge-server folder:

    python tests/test_yolo_stride.py

Why this exists: one yolo11n pass costs ~145 ms on the i5-5200U, so a loop that detects
on every step measures eyes at ~5-7 fps and a 100-400 ms blink can fall between two
measured frames. With detection.yolo_every = 2 the intermediate steps reuse the last
person boxes and only run FaceLandmarker. What is checked here is the contract of that
reuse: YOLO runs on the right steps ONLY, the face analyzer still runs on EVERY step,
track ids survive the cached frames, and an empty room or a resolution change always
forces a fresh detection.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np

from focusedge.classroom import ClassroomPipeline
from focusedge.detection import PersonTrack
from focusedge.roster import Roster
from focusedge.scheduler import Scheduler

T0 = 1000


class _CountingDetector:
    def __init__(self, persons=None):
        self.calls = 0
        self._persons = persons if persons is not None else [PersonTrack(1, (100, 50, 300, 450), 0.9)]

    def run(self, frame):
        self.calls += 1
        return list(self._persons), []


class _CountingAnalyzer:
    def __init__(self):
        self.calls = 0

    def analyze(self, rgb):
        self.calls += 1
        return None, None   # no face found: the stride contract does not depend on landmarks


def _pipeline(yolo_every=None, detector=None):
    cfg = {"class_id": "lop12a", "person": {"sensitivity": 1},
           "scheduler": {"min_eye_span_px": 12},
           "storage": {"class_live_ms": 20000, "heartbeat_ms": 300000}}
    if yolo_every is not None:
        cfg["detection"] = {"yolo_every": yolo_every}
    det = detector if detector is not None else _CountingDetector()
    ana = _CountingAnalyzer()
    sched = Scheduler(max_active_faces=10, min_eye_span_px=12.0, starve_ms=1500, hot_multiplier=3.0)
    cp = ClassroomPipeline(cfg, det, None, Roster(), ana, sched, None)
    return cp, det, ana


def _frame(w=640, h=480):
    return np.zeros((h, w, 3), dtype=np.uint8)


def _steps(cp, n, w=640, h=480):
    out = []
    for i in range(n):
        out.append(cp.step(_frame(w, h), now_ms=T0 + i * 80, wall_ms=T0 + i * 80))
    return out


def test_stride_two_runs_yolo_on_half_the_steps_but_measures_every_step():
    cp, det, ana = _pipeline(yolo_every=2)
    results = _steps(cp, 4)
    assert det.calls == 2, det.calls          # steps 1 and 3
    assert ana.calls == 4, ana.calls          # every step measured the face
    assert all(len(r) == 1 for r in results)  # the cached box kept the track measurable
    assert set(cp._tracks.keys()) == {1}      # same track id across cached frames


def test_default_is_the_old_one_yolo_per_step():
    cp, det, ana = _pipeline()                # no yolo_every in cfg at all
    _steps(cp, 3)
    assert det.calls == 3, det.calls
    assert ana.calls == 3, ana.calls


def test_stride_one_is_also_every_step():
    cp, det, _ = _pipeline(yolo_every=1)
    _steps(cp, 3)
    assert det.calls == 3, det.calls


def test_an_empty_room_never_skips_detection():
    # Nobody cached means nobody to measure: skipping YOLO here would delay finding a
    # child who walks in, for no saving at all (there is no face work to do instead).
    cp, det, ana = _pipeline(yolo_every=2, detector=_CountingDetector(persons=[]))
    _steps(cp, 3)
    assert det.calls == 3, det.calls
    assert ana.calls == 0, ana.calls


def test_a_resolution_change_forces_a_fresh_detection():
    cp, det, _ = _pipeline(yolo_every=2)
    cp.step(_frame(640, 480), now_ms=T0, wall_ms=T0)          # YOLO (step 1)
    cp.step(_frame(320, 240), now_ms=T0 + 80, wall_ms=T0 + 80)  # would skip, but size changed
    assert det.calls == 2, det.calls
    assert cp._cached_wh == (320, 240)


def test_the_flag_says_which_steps_really_ran_yolo():
    cp, _, _ = _pipeline(yolo_every=2)
    flags = []
    for i in range(4):
        cp.step(_frame(), now_ms=T0 + i * 80, wall_ms=T0 + i * 80)
        flags.append(cp.last_yolo_ran)
    assert flags == [True, False, True, False], flags


def test_cached_boxes_stay_the_boxes_the_face_was_measured_on():
    cp, det, _ = _pipeline(yolo_every=2)
    r1 = cp.step(_frame(), now_ms=T0, wall_ms=T0)[0]
    r2 = cp.step(_frame(), now_ms=T0 + 80, wall_ms=T0 + 80)[0]
    assert det.calls == 1
    assert r2.face_bbox == r1.face_bbox  # the skip step measured inside the SAME cached region


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