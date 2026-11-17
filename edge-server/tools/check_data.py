"""Check whether measured sessions really land in ``data/focuschange.db``.

Two modes, both camera-free and model-free (pure stdlib, like the tests):

    python tools/check_data.py           report what the default database holds right now
    python tools/check_data.py --demo    prove the write path end to end, then clean up

Report mode never creates anything: a missing file is the answer, not an error.

Demo mode writes ONE clearly labelled fake session (student_id ``hs_demo``) through the real
``LocalStore``, using the same default path a camera run uses (``storage.db: ""`` in config.yaml
resolves to the schema's own ``DB_PATH``). It reads every row back, prints them, and then deletes
them again - sessions first, so the schema's ON DELETE CASCADE removes the features and alerts
with them. What is left is an initialized, EMPTY database: the exact file a real run will fill.
The live-view folder is a temporary directory, so ``out/`` is never touched, and the demo student
is removed from students/classes/memberships/baselines too, so no later reader can mistake the
check for a measured lesson.

Stdout is ASCII only, the house rule; the alert sentences in the database are the phone's
Vietnamese and are therefore counted, not printed.
"""
import json
import os
import sqlite3
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.constants import HabitFeature  # noqa: E402
from focusedge.habit import BandOutcome, HabitProfile, SessionSummary  # noqa: E402
from focusedge.local_store import LocalStore, resolve_db_path  # noqa: E402

DEMO_ID = "hs_demo"
DEMO_NAME = "DEMO - kiem tra du lieu, khong phai hoc sinh that"
DEMO_CLASS = "lop12a"


def _schema():
    import database.focuschange_db as schema
    return schema


def _counts(conn):
    return {t: conn.execute('SELECT COUNT(*) FROM "%s"' % t).fetchone()[0]
            for t in _schema().TABLES}


def _print_counts(conn, label):
    counts = _counts(conn)
    print("%s: %s" % (label, ", ".join("%s=%d" % (t, n) for t, n in counts.items())))
    return counts


def report():
    path = resolve_db_path("")
    print("default database path (storage.db = \"\"): %s" % path)
    if not os.path.exists(path):
        print("status: FILE DOES NOT EXIST - no camera run has stored anything on this machine yet")
        return 0
    # Read-only on purpose: a check must not create or repair the file it is checking.
    conn = sqlite3.connect("file:%s?mode=ro" % path.replace("\\", "/"), uri=True)
    conn.row_factory = sqlite3.Row
    _print_counts(conn, "tables")
    rows = conn.execute(
        "SELECT s.student_id, st.name, s.source, s.started_at_wall_ms, s.valid_ms"
        " FROM observation_sessions AS s JOIN students AS st USING (student_id)"
        " ORDER BY s.session_row DESC LIMIT 10").fetchall()
    if not rows:
        print("sessions: none stored")
    for r in rows:
        print("  %-12s %-24s %-12s %s %6.1f min" % (
            r["student_id"], (r["name"] or "")[:24], r["source"],
            time.strftime("%Y-%m-%d %H:%M", time.localtime(r["started_at_wall_ms"] / 1000.0)),
            (r["valid_ms"] or 0) / 60000.0))
    conn.close()
    return 0


def _summary(sid, sei_mean=70.0):
    """The same shape tests/test_local_store.py writes: a 5 minute session, 60 SEI frames."""
    c = HabitFeature.COUNT
    mean, std, count = [0.0] * c, [0.0] * c, [0] * c
    mean[HabitFeature.SEI], std[HabitFeature.SEI], count[HabitFeature.SEI] = sei_mean, 8.0, 60
    mean[HabitFeature.EAR], std[HabitFeature.EAR], count[HabitFeature.EAR] = 0.25, 0.03, 40
    return SessionSummary(id=sid, valid_ms=240_000, face_ratio=0.91, valid_ratio=0.78,
                          quality=82.0, mean=mean, std=std, count=count, quality_count=40,
                          band_in=10, band_below=1, band_above=2, sensitivity_level=1)


def demo():
    path = resolve_db_path("")
    print("demo target (the real default path): %s" % path)
    tmp = tempfile.mkdtemp(prefix="focuschange_demo_out_")
    store = LocalStore(db_path="", local_dir=tmp, class_id=DEMO_CLASS, class_label="Lop 12A",
                       school_year="2026-2027", app_version="edge-demo-check")
    if not store.enabled:
        print("DEMO FAILED: database did not open (%s)" % store.last_error)
        return 1

    start = int(time.time() * 1000) - 300_000
    end = start + 300_000
    store.write_session(
        student_id=DEMO_ID, student_name=DEMO_NAME, s=_summary(start),
        band_outcomes=[BandOutcome(feature=HabitFeature.SEI, mean=70.0, min=60.0, max=85.0,
                                   samples=13, below=1, above=2)],
        ended_at_wall_ms=end, device="demo-check", ineligible_reason=None, alerts_total=2,
        csv_rows=60, avg_fps=20.5,
        alerts=[{"code": "DROWSY", "wall_ms": start + 60_000},
                {"code": "LOW_BLINK", "wall_ms": start + 120_000}],
        identity="manual")
    profile = HabitProfile(DEMO_ID, DEMO_NAME)
    for i in range(3):
        profile.commit(_summary(start - i * 600_000, sei_mean=70.0 + i))
    store.write_band(DEMO_ID, DEMO_NAME, profile, end, "demo-check")
    print("writer: sent=%d failed=%d" % (store.sent, store.failed))
    store.close()
    if store.failed:
        print("DEMO FAILED: the writer refused a row (%s)" % store.last_error)
        return 1

    # ---- read every row back, through a connection with the same FK rule the writer uses
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    _print_counts(conn, "after demo write")
    ses = conn.execute("SELECT * FROM observation_sessions WHERE student_id=?",
                       (DEMO_ID,)).fetchone()
    if ses is None:
        print("DEMO FAILED: no session row for %s" % DEMO_ID)
        conn.close()
        return 1
    print("session row: student=%s class=%s source=%s device=%s version=%s valid_ms=%d" % (
        ses["student_id"], ses["class_id"], ses["source"], ses["device"],
        ses["app_version"], ses["valid_ms"]))
    print("session notes: %s" % json.dumps(json.loads(ses["notes"]), sort_keys=True))
    for f in conn.execute("SELECT * FROM feature_records WHERE session_row=? ORDER BY feature",
                          (ses["session_row"],)):
        print("feature: %-4s mean=%.2f std=%.2f n=%d extractor=%s" % (
            f["feature"], f["mean"], f["std"], f["n"], f["extractor"]))
    for a in conn.execute("SELECT * FROM alerts WHERE session_row=? ORDER BY alert_id",
                          (ses["session_row"],)):
        # message is the phone's Vietnamese sentence - counted and timed, not printed (ASCII rule)
        print("alert: kind=%s at=%d message_len=%d" % (
            a["kind"], a["raised_at_wall_ms"], len(a["message"] or "")))
    base = conn.execute("SELECT * FROM baseline_profiles WHERE student_id=?",
                        (DEMO_ID,)).fetchone()
    if base is not None:
        print("baseline: status=%s sessions_used=%d days=%d features=%s" % (
            base["status"], base["sessions_used"], base["days"],
            json.dumps(json.loads(base["features_json"])["features"], sort_keys=True)))

    # ---- remove the demo, children first via the schema's own cascade
    conn.execute("DELETE FROM observation_sessions WHERE student_id=?", (DEMO_ID,))
    conn.execute("DELETE FROM baseline_profiles WHERE student_id=?", (DEMO_ID,))
    conn.execute("DELETE FROM class_memberships WHERE student_id=?", (DEMO_ID,))
    conn.execute("DELETE FROM students WHERE student_id=?", (DEMO_ID,))
    # the class row only if nothing else points at it - a real run's class must survive this check
    conn.execute(
        "DELETE FROM classes WHERE class_id=?"
        " AND NOT EXISTS (SELECT 1 FROM class_memberships WHERE class_id=?)"
        " AND NOT EXISTS (SELECT 1 FROM observation_sessions WHERE class_id=?)",
        (DEMO_CLASS, DEMO_CLASS, DEMO_CLASS))
    conn.commit()
    counts = _print_counts(conn, "after demo cleanup")
    tables = sorted(r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
    conn.close()
    if any(counts.values()):
        print("DEMO FAILED: demo rows left behind")
        return 1
    print("demo rows removed; %d tables remain, all empty" % len(tables))
    print("left in place: initialized EMPTY database at %s (%d bytes)" % (
        path, os.path.getsize(path)))
    print("out/ was not touched (live views went to %s)" % tmp)
    return 0


if __name__ == "__main__":
    sys.exit(demo() if "--demo" in sys.argv[1:] else report())