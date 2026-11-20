"""Seat memory: a named head that leaves remembers its seat, and the next head there is OFFERED
that name on the first m press - never bound silently.

Pure logic - no camera, no models, no network. Run from the edge-server folder:

    python tests/test_seat_memory.py

The pain this answers (guide item 1): a student leaves and comes back, ByteTrack gives them a new
id, and the operator has to walk the roster from slot one again - or worse, binds the wrong child.
The falsifications below matter as much as the happy path: a far seat, a taken seat and an expired
seat must all fall back to the old behaviour, and no code path may bind without a press.
"""
import os
import sys
import time
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.classroom import ClassroomPipeline, TrackRuntime
from focusedge.person_pipeline import PersonPipeline
from focusedge.roster import Roster, RosterEntry

W, H = 640, 480
SEAT_A = (100, 50, 200, 150)      # center (150, 100) -> normalized (0.234, 0.208)
NEAR_A = (110, 60, 210, 160)      # center (160, 110), well inside SEAT_RADIUS
FAR = (450, 300, 550, 400)        # center (500, 350), another desk entirely


class _Guest:
    bound_student_id = None
    confidence = "unbound"


def _pipeline():
    cfg = {"class_id": "lop12a", "class_label": "Lop 12A",
           "person": {"sensitivity": 1, "habit_learn": True},
           "input": {"source": "test-cam"},
           "storage": {"heartbeat_ms": 300000, "class_live_ms": 20000}}
    roster = Roster([RosterEntry("hs1", "Em Mot"), RosterEntry("hs2", "Em Hai")])
    cp = ClassroomPipeline(cfg, None, None, roster, None, None, None)
    cp._last_wh = (W, H)
    return cp


def _track(cp, track_id, bbox):
    rt = TrackRuntime(track_id=track_id, identity=_Guest(),
                      pipeline=PersonPipeline(track_id, None, 1, 12.0))
    rt.last_result = SimpleNamespace(face_bbox=bbox, measurable=False)
    cp._tracks[track_id] = rt
    return rt


def _retired_hs2(cp, wall_ms=None):
    """hs2 sat at SEAT_A, was named by hand, then left the frame."""
    rt = _track(cp, 3, SEAT_A)
    cp.bind_manual_next(3)          # hs1
    cp.bind_manual_next(3)          # hs2
    cp._retire(rt, wall_ms if wall_ms is not None else int(time.time() * 1000))
    del cp._tracks[3]
    return rt


def test_retire_records_the_seat_of_a_manual_binding():
    cp = _pipeline()
    _retired_hs2(cp)
    m = cp._seat_memory.get("hs2")
    assert m is not None, "a manually named head must leave its seat behind"
    assert abs(m["cx"] - 150.0 / W) < 1e-9 and abs(m["cy"] - 100.0 / H) < 1e-9
    assert m["name"] == "Em Hai"


def test_a_guest_retire_records_nothing():
    cp = _pipeline()
    rt = _track(cp, 3, SEAT_A)
    cp._retire(rt, int(time.time() * 1000))
    assert cp._seat_memory == {}, "a guest has no name to remember"


def test_nearby_guest_is_offered_the_old_name_and_one_press_rebinds():
    cp = _pipeline()
    _retired_hs2(cp)
    rt = _track(cp, 9, NEAR_A)
    assert cp.seat_suggestion(9) == "hs2"
    # The falsification baked into the happy path: without seat memory the first press would
    # take hs1 (first free slot). One press must land on hs2 instead.
    assert cp.bind_manual_next(9) == "hs2"
    assert rt.identity_conf == "manual"


def test_a_far_seat_falls_back_to_first_free_slot():
    cp = _pipeline()
    _retired_hs2(cp)
    _track(cp, 9, FAR)
    assert cp.seat_suggestion(9) is None
    assert cp.bind_manual_next(9) == "hs1"


def test_a_taken_seat_is_not_offered_again():
    cp = _pipeline()
    _retired_hs2(cp)
    _track(cp, 9, NEAR_A)
    assert cp.bind_manual_next(9) == "hs2"
    _track(cp, 10, NEAR_A)          # second head at the same desk
    assert cp.seat_suggestion(10) is None
    assert cp.bind_manual_next(10) == "hs1"


def test_an_expired_seat_is_forgotten():
    cp = _pipeline()
    old = int(time.time() * 1000) - cp.SEAT_TTL_MS - 1
    _retired_hs2(cp, wall_ms=old)
    _track(cp, 9, NEAR_A)
    assert cp.seat_suggestion(9) is None


def test_nothing_binds_without_a_press():
    cp = _pipeline()
    _retired_hs2(cp)
    rt = _track(cp, 9, NEAR_A)
    assert rt.student_id is None, "a suggestion must never become a binding by itself"
    assert rt.manual_student_id is None
    views = {v.track_id: v for v in cp.people()}
    assert views[9].seat_hint == "Em Hai"
    assert views[9].student_id is None


def test_a_bound_head_gets_no_hint():
    cp = _pipeline()
    _retired_hs2(cp)
    rt = _track(cp, 9, NEAR_A)
    cp.bind_manual_next(9)
    assert cp.seat_suggestion(9) is None
    views = {v.track_id: v for v in cp.people()}
    assert views[9].seat_hint == ""


def test_the_nearest_seat_wins_when_two_are_in_radius():
    cp = _pipeline()
    now = int(time.time() * 1000)
    cp._seat_memory["hs1"] = {"cx": 150.0 / W, "cy": 100.0 / H, "name": "Em Mot", "at_ms": now}
    cp._seat_memory["hs2"] = {"cx": 162.0 / W, "cy": 112.0 / H, "name": "Em Hai", "at_ms": now}
    _track(cp, 9, NEAR_A)           # center (160, 110): hs2's seat is closer
    assert cp.seat_suggestion(9) == "hs2"


if __name__ == "__main__":
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
    sys.exit(1 if failed else 0)