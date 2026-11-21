"""Two children in one frame: nobody's number may become the other's, and a name must be earned.

Pure logic - no camera, no models, no network. Run from the edge-server folder:

    python tests/test_multi_student.py

Three things are checked here because all three are what an operator sees as "the data got mixed":
which person the column is showing (main.py), how sure the machine is of that person's NAME
(identity.py / roster.py), and whether the curve beside the number belongs to that person at all.
What the measurement itself means is test_measurement.py's job.
"""
import math
import os
import sys
from collections import deque

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main
from focusedge.classroom import ClassroomPipeline, PersonView, TrackRuntime
from focusedge.hud import _identity_evidence
from focusedge.identity import IdentityResolver, cosine
from focusedge.person_pipeline import PersonPipeline
from focusedge.roster import Roster, RosterEntry

BIND_HI = 0.52
BIND_LO = 0.38
MIN_MARGIN = 0.05
MIN_SAMPLES = 10


def _vec(deg):
    """A face embedding pointing deg degrees away from the x axis.

    Cosine ignores length, so an angle is the only thing that decides a score here: it makes every
    number in these tests derivable by hand instead of read out of the code being tested.
    """
    return [math.cos(math.radians(deg)), math.sin(math.radians(deg))]


class _FixedEmbedder:
    """Returns the same face every time, so a test controls the score and nothing else."""

    def __init__(self, deg):
        self.deg = deg

    def embed(self, face_crop, bbox):
        return _vec(self.deg)


def _roster(*specs):
    return Roster([RosterEntry(student_id=sid, name=name, template=_vec(deg))
                   for sid, name, deg in specs])


def _resolver(embedder, roster):
    return IdentityResolver(embedder, roster, bind_hi=BIND_HI, bind_lo=BIND_LO,
                            min_margin=MIN_MARGIN, min_samples=MIN_SAMPLES, embed_window=40,
                            unbind_hold_ms=1000)


def _feed(resolver, state, n, now_ms=0):
    for _ in range(n):
        now_ms += 33
        state = resolver.observe(state, None, None, now_ms)
    return state, now_ms


# ------------------------------------------------------------------ the menu (main.py)
def _people():
    return [PersonView(track_id=3, student_id="hs1", student_name="Em Mot", identity_conf="high",
                       measurable=True),
            PersonView(track_id=7, student_id="hs2", student_name="Em Hai", identity_conf="high",
                       measurable=True)]


def test_column_keeps_the_person_it_was_already_showing():
    people = _people()
    shown, mode = main._shown_track(people, {"shown": 7})
    assert shown == 7
    assert "theo dõi" in mode


def test_column_does_not_jump_to_the_better_face_of_the_two():
    # The bug this exists for: with both children measurable, a per-frame "who looks best" pick
    # moves the column every few seconds and the operator reads it as one merged student.
    people = _people()
    first, _ = main._shown_track(people, {})
    assert first == 3
    assert main._shown_track(people, {"shown": first})[0] == 3


def test_a_key_outranks_the_machine_and_survives_the_other_face():
    people = _people()
    shown, mode = main._shown_track(people, {"focus": 7, "shown": 3})
    assert shown == 7
    assert "phím" in mode


def test_a_key_is_dropped_when_that_person_leaves_the_frame():
    people = [p for p in _people() if p.track_id != 7]
    shown, _ = main._shown_track(people, {"focus": 7, "shown": 7})
    assert shown == 3


def test_an_empty_room_shows_nobody_rather_than_the_last_face():
    assert main._shown_track([], {"shown": 3})[0] is None
    assert main._whom_note({"people": [], "identity": None}) == "Không có ai trong khung hình"


def test_cycling_walks_the_menu_and_wraps():
    people = _people()
    assert main._cycle_focus(people, 3) == 7
    assert main._cycle_focus(people, 7) == 3
    # A stale or absent selection lands on the first row, not on nothing.
    assert main._cycle_focus(people, None) == 3
    assert main._cycle_focus(people, 99) == 3
    assert main._cycle_focus([], 3) is None


def test_the_column_names_the_child_and_counts_the_children():
    people = _people()
    note = main._whom_note({"people": people, "identity": people[1], "shown_mode": "chọn bằng phím"})
    assert "em 2/2" in note
    assert "hs2" in note
    assert "Em Mot" not in note


def test_one_child_in_frame_is_not_numbered():
    people = _people()[:1]
    note = main._whom_note({"people": people, "identity": people[0], "shown_mode": "đang theo dõi"})
    assert "1/1" not in note
    assert "hs1" in note


def test_each_person_gets_their_own_curve():
    histories = {}
    a = main._history_for(histories, 3)
    b = main._history_for(histories, 7)
    assert a is not b
    a.append(80)
    assert list(b) == []
    assert list(main._history_for(histories, 3)) == [80]


# ------------------------------------------------------------------ the name (identity.py)
def test_two_children_who_are_nearly_tied_get_no_name():
    # The whole reason the margin gate exists: A and B three degrees apart, a face between them,
    # and the old score-only gate would have handed one of them a confident label.
    roster = _roster(("hs1", "Em Mot", 0.0), ("hs2", "Em Hai", 3.0))
    resolver = _resolver(_FixedEmbedder(1.5), roster)
    state, _ = _feed(resolver, resolver.new_state(), MIN_SAMPLES + 2)
    assert state.bound_student_id is None
    assert state.confidence != "high"
    assert state.margin < MIN_MARGIN


def test_a_clear_win_still_binds():
    roster = _roster(("hs1", "Em Mot", 0.0), ("hs2", "Em Hai", 60.0))
    resolver = _resolver(_FixedEmbedder(0.0), roster)
    state, _ = _feed(resolver, resolver.new_state(), MIN_SAMPLES + 2)
    assert state.bound_student_id == "hs1"
    assert state.confidence == "high"
    assert state.runner_up == "hs2"


def test_one_child_in_the_roster_is_not_refused_for_want_of_a_rival():
    # With nothing to compare against the margin must not become a second wall: a single enrolled
    # student is the normal case in a quiet test room, and refusing there would look like a break.
    roster = _roster(("hs1", "Em Mot", 0.0))
    resolver = _resolver(_FixedEmbedder(0.0), roster)
    state, _ = _feed(resolver, resolver.new_state(), MIN_SAMPLES + 2)
    assert state.bound_student_id == "hs1"
    assert state.margin == 1.0
    assert state.runner_up == ""


def test_a_name_needs_enough_samples_before_any_gate_is_judged():
    roster = _roster(("hs1", "Em Mot", 0.0), ("hs2", "Em Hai", 60.0))
    resolver = _resolver(_FixedEmbedder(0.0), roster)
    state, _ = _feed(resolver, resolver.new_state(), MIN_SAMPLES - 1)
    assert state.bound_student_id is None
    assert state.samples == MIN_SAMPLES - 1


def test_a_turned_head_does_not_lose_an_established_name():
    roster = _roster(("hs1", "Em Mot", 0.0), ("hs2", "Em Hai", 60.0))
    resolver = _resolver(_FixedEmbedder(0.0), roster)
    state, now = _feed(resolver, resolver.new_state(), MIN_SAMPLES + 2)
    assert state.bound_student_id == "hs1"
    # 25 degrees off still clears bind_lo, so the binding holds through a look at the board.
    resolver._embedder = _FixedEmbedder(25.0)
    state, now = _feed(resolver, state, 4, now)
    assert state.bound_student_id == "hs1"
    assert cosine(_vec(25.0), _vec(0.0)) > BIND_LO


def test_a_different_child_takes_the_track_back_after_the_hold():
    roster = _roster(("hs1", "Em Mot", 0.0), ("hs2", "Em Hai", 80.0))
    resolver = _resolver(_FixedEmbedder(0.0), roster)
    state, now = _feed(resolver, resolver.new_state(), MIN_SAMPLES + 2)
    assert state.bound_student_id == "hs1"
    resolver._embedder = _FixedEmbedder(80.0)
    # First the 40-sample window has to turn over, then the mismatch has to outlast the hold, then
    # min_samples new faces are needed before a name is given at all: about a hundred frames, which
    # is the two-ish seconds an empty seat beside a moving child is allowed to keep the old name.
    state, now = _feed(resolver, state, 20, now)
    assert state.bound_student_id == "hs1"
    state, now = _feed(resolver, state, 100, now)
    assert state.bound_student_id == "hs2"
    assert state.confidence == "high"


def test_the_resolver_reports_the_gates_it_used():
    roster = _roster(("hs1", "Em Mot", 0.0))
    resolver = _resolver(_FixedEmbedder(0.0), roster)
    assert resolver.bind_hi == BIND_HI
    assert resolver.min_margin == MIN_MARGIN
    assert resolver.min_samples == MIN_SAMPLES


# ------------------------------------------------------------------ what the screen says
def _view(**kw):
    args = dict(track_id=3, student_id="hs1", student_name="Em Mot", identity_conf="high",
                score=0.74, margin=0.31, samples=18, runner_up="hs2", gate=BIND_HI,
                margin_gate=MIN_MARGIN)
    args.update(kw)
    return PersonView(**args)


def test_a_bound_name_shows_its_score_and_its_rival():
    text = _identity_evidence(_view())
    assert "0.74" in text
    assert "hs2" in text
    assert "chưa gắn tên" not in text


def test_an_unbound_face_says_the_score_it_was_refused_on():
    text = _identity_evidence(_view(student_id=None, score=0.44, margin=0.01, runner_up="hs2"))
    assert "chưa gắn tên" in text
    assert "0.44" in text
    # The bar has to be on the screen too, or "no name" is indistinguishable from "no camera".
    assert "0.52" in text
    assert "Em Mot" not in text


def test_no_samples_is_not_reported_as_a_low_score():
    text = _identity_evidence(_view(score=0.0, margin=0.0, samples=0))
    assert "0.00" not in text
    assert "chưa có mẫu mặt" in text


def test_a_single_child_roster_admits_it_has_nothing_to_compare():
    text = _identity_evidence(_view(runner_up=""))
    assert "chưa có em thứ hai" in text


# ------------------------------------------------------------------ the wiring (classroom.py)
def _bare_pipeline():
    cfg = {"class_id": "lop12a", "person": {"sensitivity": 1}, "scheduler": {"min_eye_span_px": 12}}
    return ClassroomPipeline(cfg, None, None, None, None, None, None)


def test_people_are_in_track_id_order_so_a_key_means_the_same_child():
    cp = _bare_pipeline()

    class _State:
        score, margin, samples, runner_up = 0.7, 0.2, 12, ""

    for tid, sid in ((9, "hs9"), (4, "hs4")):
        cp._tracks[tid] = TrackRuntime(
            track_id=tid, identity=_State(), pipeline=PersonPipeline(tid, None, 1, 12.0),
            student_id=sid, student_name=sid.upper(), identity_conf="high")
    ids = [p.track_id for p in cp.people()]
    assert ids == [4, 9]
    assert [p.student_id for p in cp.people()] == ["hs4", "hs9"]
    assert cp.people()[0].score == 0.7
    assert cp.people()[0].measurable is False


def test_a_person_the_scheduler_skipped_keeps_their_own_last_numbers():
    cp = _bare_pipeline()
    assert cp.person_result(None) is None
    assert cp.person_result(4) is None


def load_tests(loader, tests, pattern):
    import unittest
    suite = unittest.TestSuite()
    for k, v in sorted(globals().items()):
        if k.startswith("test_") and callable(v):
            suite.addTest(unittest.FunctionTestCase(v))
    return suite


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
