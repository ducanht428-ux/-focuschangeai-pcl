"""Naming a head by hand, for the machines where recognition cannot answer at all.

Pure logic - no camera, no models, no network. Run from the edge-server folder:

    python tests/test_manual_bind.py

Why this exists was measured on the laptop used for this project: importing onnxruntime segfaults
(exit 139, three runs out of three), so insightface never loads, every track stays a guest, and
_retire refuses a guest's session. The ten tables stay empty for a run that looked perfectly good on
screen. The m key is the way out, and what is checked here is that it really reaches the database -
and that a declared name is stored as declared, never dressed up as a face match.
"""
import json
import os
import sqlite3
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main
from focusedge.classroom import ClassroomPipeline, PersonView, TrackRuntime
from focusedge.hud import _identity_evidence
from focusedge.local_store import LocalStore
from focusedge.person_pipeline import PersonPipeline
from focusedge.roster import Roster, RosterEntry

START_MS = int(time.time() * 1000) - 300_000
END_MS = START_MS + 300_000


class _Guest:
    """What layer 2 holds on a machine with no embedder: nobody, ever."""

    bound_student_id = None
    confidence = "unbound"


def _pipeline(store=None, roster=None, roster_path=None):
    cfg = {"class_id": "lop12a", "class_label": "Lop 12A",
           "person": {"sensitivity": 1, "habit_learn": True},
           "input": {"source": "test-cam"},
           "storage": {"heartbeat_ms": 300000, "class_live_ms": 20000}}
    return ClassroomPipeline(cfg, None, None, roster if roster is not None else Roster(),
                             None, None, store, roster_path=roster_path)


def _track(cp, track_id):
    rt = TrackRuntime(track_id=track_id, identity=_Guest(),
                      pipeline=PersonPipeline(track_id, None, 1, 12.0))
    cp._tracks[track_id] = rt
    return rt


def _store(tmp):
    return LocalStore(db_path=os.path.join(tmp, "focuschange.db"),
                      local_dir=os.path.join(tmp, "out"), class_id="lop12a",
                      class_label="Lop 12A", school_year="2026-2027", app_version="edge-test")


# ------------------------------------------------------------------ the key itself
def test_a_press_names_the_head_and_the_roster_grows_a_slot():
    cp = _pipeline()
    rt = _track(cp, 3)
    assert cp.bind_manual_next(3) == "hs1"
    assert rt.student_id == "hs1"
    assert rt.identity_conf == "manual"
    assert rt.student_name == "Học sinh 1"


def test_two_heads_get_two_names_and_not_one_shared_name():
    # The failure this prevents: both children stored under hs1, which is exactly the "data got
    # mixed" complaint the multi-student suite exists for, arrived at through the keyboard instead.
    cp = _pipeline()
    a, b = _track(cp, 3), _track(cp, 7)
    assert cp.bind_manual_next(3) == "hs1"
    assert cp.bind_manual_next(7) == "hs2"
    assert a.student_id != b.student_id


def test_cycling_walks_the_roster_and_the_last_step_releases():
    cp = _pipeline(roster=Roster([RosterEntry("hs1", "Em Mot"), RosterEntry("hs2", "Em Hai")]))
    rt = _track(cp, 3)
    assert cp.bind_manual_next(3) == "hs1"
    assert cp.bind_manual_next(3) == "hs2"
    assert cp.bind_manual_next(3) is None
    assert rt.student_id is None
    assert rt.identity_conf == "unbound"


def test_a_declared_name_beats_a_recognised_one():
    cp = _pipeline(roster=Roster([RosterEntry("hs1", "Em Mot"), RosterEntry("hs2", "Em Hai")]))
    rt = _track(cp, 3)

    class _Bound:
        bound_student_id = "hs2"
        confidence = "high"

    rt.identity = _Bound()
    cp._sync_binding(rt)
    assert rt.student_id == "hs2" and rt.identity_conf == "high"
    cp.bind_manual_next(3)
    assert rt.student_id == "hs1"
    assert rt.identity_conf == "manual"


def test_the_key_with_nobody_in_frame_says_so_instead_of_crashing():
    cp = _pipeline()
    main._bind_manual({"pipeline": cp, "people": []})
    rt = _track(cp, 3)
    state = {"pipeline": cp, "people": cp.people(), "focus": None}
    main._bind_manual(state)
    assert state["focus"] == 3
    assert state["people"][0].identity_conf == "manual"
    assert state["people"][0].student_id == "hs1"


# ------------------------------------------------------------------ what the screen says
def test_the_screen_says_declared_not_recognised():
    v = PersonView(track_id=3, student_id="hs1", student_name="Học sinh 1",
                   identity_conf="manual")
    s = _identity_evidence(v)
    assert "tự khai" in s
    assert "điểm khớp" not in s


# ------------------------------------------------------------------ what the database keeps
def test_the_named_session_reaches_the_tables():
    tmp = tempfile.mkdtemp()
    store = _store(tmp)
    cp = _pipeline(store=store, roster_path=os.path.join(tmp, "roster.json"))
    rt = _track(cp, 3)
    cp.bind_manual_next(3)
    rt.pipeline.start_session(START_MS)
    cp._retire(rt, END_MS)
    conn = sqlite3.connect(os.path.join(tmp, "focuschange.db"))
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM observation_sessions").fetchone()
    assert row is not None, "a named head must produce a session row"
    assert row["student_id"] == "hs1"
    assert json.loads(row["notes"])["identity"] == "manual"
    st = conn.execute("SELECT * FROM students").fetchone()
    assert st is not None and st["name"] == "Học sinh 1"
    assert store.failed == 0 and store.sent == 1
    conn.close()
    store.close()


def test_a_guest_still_writes_nothing():
    # The falsification: without the key the old behaviour must be exactly as before, so the new
    # route cannot quietly start storing unnamed faces.
    tmp = tempfile.mkdtemp()
    store = _store(tmp)
    cp = _pipeline(store=store)
    rt = _track(cp, 3)
    rt.pipeline.start_session(START_MS)
    cp._retire(rt, END_MS)
    conn = sqlite3.connect(os.path.join(tmp, "focuschange.db"))
    assert conn.execute("SELECT COUNT(*) FROM observation_sessions").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM students").fetchone()[0] == 0
    conn.close()
    store.close()


def test_the_name_survives_a_restart():
    tmp = tempfile.mkdtemp()
    path = os.path.join(tmp, "roster.json")
    cp = _pipeline(roster_path=path)
    _track(cp, 3)
    cp.bind_manual_next(3)
    again = Roster.load(path)
    assert again.get("hs1") is not None
    assert again.name_of("hs1") == "Học sinh 1"


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
