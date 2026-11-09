"""Tests that a measured session really lands in focuschange.db.

Pure logic - no camera, no models, no network. Run from the edge-server folder:

    python tests/test_sqlite_mirror.py

classroom.py mirrors every cloud write into SQLite through three private methods.
This file drives those three methods on a bare ClassroomPipeline (no detector, no
embedder: the mirror must not depend on any of the heavy layers) and reads the rows
back out of a throwaway database file, so a regression here means a session a teacher
will never find in the database.

What this does NOT check: that the cloud write still happens. That is
test_firestore_parity.py. Here only the local copy is under test.
"""
import os
import sys
import tempfile
import types

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from database import database as DB  # noqa: E402
from focusedge.classroom import ClassroomPipeline  # noqa: E402
from focusedge.constants import HabitFeature  # noqa: E402
from focusedge.habit import HabitProfile, SessionSummary  # noqa: E402

FAILS = []


def check(name, got, want=True):
    ok = (got == want) if not callable(want) else want(got)
    if not ok:
        FAILS.append(name)
    print(("PASS " if ok else "FAIL ") + name + "  -> " + repr(got))


def summary(sid, sei_mean=70.0, n=60):
    c = HabitFeature.COUNT
    mean = [0.0] * c
    std = [0.0] * c
    count = [0] * c
    mean[HabitFeature.SEI] = sei_mean
    std[HabitFeature.SEI] = 8.0
    count[HabitFeature.SEI] = n
    return SessionSummary(id=sid, valid_ms=240000, face_ratio=0.91, valid_ratio=0.78,
                          quality=82.0, mean=mean, std=std, count=count, quality_count=40,
                          band_in=10, band_below=1, band_above=2, sensitivity_level=1)


def bare_pipeline(db_path):
    pipe = ClassroomPipeline.__new__(ClassroomPipeline)
    pipe._device = "test-cam"
    pipe._writer = types.SimpleNamespace(app_version="edge-test")
    pipe._sqlite = DB
    pipe._store = DB.connect(db_path)
    pipe._store_warned = False
    return pipe


def main():
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    os.remove(path)  # connect() must be the thing that creates it
    pipe = bare_pipeline(path)
    conn = pipe._store

    check("connect created the file", os.path.exists(path))
    check("all five tables exist", sorted(DB.table_names(conn)),
          ["bands", "presence", "session_features", "sessions", "students"])

    s = summary(7)
    pipe._mirror_session("hs1", "Le Van A", s, 1762600000000, 21.0, None, 3)
    stu = conn.execute("SELECT * FROM students").fetchall()
    check("one student row", len(stu), 1)
    check("student name kept", stu[0]["name"], "Le Van A")
    ses = conn.execute("SELECT * FROM sessions").fetchall()
    check("one session row", len(ses), 1)
    check("session is eligible", ses[0]["eligible"], 1)
    check("alerts carried over", ses[0]["alerts_total"], 3)
    check("avg_fps carried over", ses[0]["avg_fps"], 21.0)
    check("device carried over", ses[0]["device"], "test-cam")
    check("app version carried over", ses[0]["app_version"], "edge-test")
    feat = conn.execute("SELECT * FROM session_features").fetchall()
    check("only measured features stored", [f["feature"] for f in feat], ["sei"])
    check("feature mean stored", feat[0]["mean"], 70.0)
    check("feature n stored", feat[0]["n"], 60)

    # A rewritten session replaces its row and its features instead of doubling them.
    pipe._mirror_session("hs1", "Le Van A", summary(7, sei_mean=72.0), 1762600060000, 20.0,
                         None, 4)
    check("rewrite keeps one session row",
          conn.execute("SELECT COUNT(*) c FROM sessions").fetchone()["c"], 1)
    check("rewrite keeps one feature row",
          conn.execute("SELECT COUNT(*) c FROM session_features").fetchone()["c"], 1)
    check("rewrite carries the new mean",
          conn.execute("SELECT mean FROM session_features").fetchone()["mean"], 72.0)
    check("students not duplicated",
          conn.execute("SELECT COUNT(*) c FROM students").fetchone()["c"], 1)

    # An ineligible session says why, in the same words the cloud row uses.
    pipe._mirror_session("hs2", "Tran B", summary(1), 1762600000000, 21.0, "too short", 0)
    row = conn.execute("SELECT * FROM sessions WHERE student_id = 'hs2'").fetchone()
    check("ineligible flag set", row["eligible"], 0)
    check("ineligible reason kept", row["ineligible_reason"], "too short")

    profile = HabitProfile("hs1", "Le Van A")
    for i in range(3):
        profile.commit(summary(10 + i, sei_mean=70.0 + i))
    pipe._mirror_bands("hs1", "Le Van A", profile, 1762600000000)
    band = conn.execute("SELECT * FROM bands").fetchall()
    check("one band row per band feature with data", [b["feature"] for b in band], ["sei"])
    check("band typical near the session means", abs(band[0]["typical"] - 71.0) < 2.0)
    check("band min below typical", band[0]["min"] < band[0]["typical"], True)
    check("band max above typical", band[0]["max"] > band[0]["typical"], True)

    pipe._mirror_presence("hs1", "Le Van A", s, 1762599000000, 1762600000000, 250000, 3,
                          "working")
    pre = conn.execute("SELECT * FROM presence").fetchall()
    check("one presence row", len(pre), 1)
    check("presence state kept", pre[0]["state"], "working")
    check("presence sei mean kept", pre[0]["sei_mean"], 70.0)
    pipe._mirror_presence("hs1", "Le Van A", s, 1762599000000, 1762600300000, 260000, 5,
                          "looking away")
    check("heartbeat replaces, not appends",
          conn.execute("SELECT COUNT(*) c FROM presence").fetchone()["c"], 1)
    check("heartbeat carries the new beat",
          conn.execute("SELECT beat_at_wall_ms FROM presence").fetchone()["beat_at_wall_ms"],
          1762600300000)

    # A pipeline with no store at all must stay silent, not crash.
    dead = bare_pipeline(path)
    dead._store.close()  # the connection was only made to be thrown away
    dead._store = None
    dead._mirror_session("hs3", "C", summary(1), 1, 1.0, None, 0)
    check("no store means no write and no crash",
          conn.execute("SELECT COUNT(*) c FROM students WHERE student_id='hs3'")
          .fetchone()["c"], 0)

    # A store that dies mid-run must warn once and let the run continue.
    broken = bare_pipeline(path)
    broken._store.close()
    broken._mirror_session("hs4", "D", summary(1), 1, 1.0, None, 0)
    check("closed store warns once", broken._store_warned, True)
    broken._mirror_session("hs4", "D", summary(2), 2, 1.0, None, 0)
    check("second failure stays quiet", broken._store_warned, True)

    conn.close()
    os.remove(path)
    if FAILS:
        print("\n%d FAILURES: %s" % (len(FAILS), FAILS))
        sys.exit(1)
    print("\nALL PASS")


if __name__ == "__main__":
    main()
