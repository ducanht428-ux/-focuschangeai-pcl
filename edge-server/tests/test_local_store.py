"""Tests that a measured session really lands in the ten-table database.

Pure logic - no camera, no models, no network, no firebase. Run from the edge-server folder:

    python tests/test_local_store.py

LocalStore is layer 5 on its own: it takes the same four calls the orchestrator makes and turns
them into rows of ``database/focuschange_db.py`` plus two JSON files for the live views. Driving it
directly (rather than through a ClassroomPipeline) is what makes the test honest about that split -
nothing here can pass because a detector or an embedder happened to be present.

What this does NOT check: that the numbers themselves are right. That is test_measurement.py and
test_parity_thresholds.py. Here the question is only whether a measurement that exists on screen
also exists in storage.
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
from focusedge.hud import ALERT_SENTENCES  # noqa: E402
from focusedge.local_store import LocalStore, _round2, resolve_db_path  # noqa: E402

FAILS = []

# A session that started and ended on the same day the test runs, so the membership date is
# predictable without freezing a clock: started_at_wall_ms IS the session id.
START_MS = int(time.time() * 1000) - 300_000
END_MS = START_MS + 300_000


def check(name, got, want=True):
    ok = (got == want) if not callable(want) else want(got)
    if not ok:
        FAILS.append(name)
    print(("PASS " if ok else "FAIL ") + name + "  -> " + repr(got))


def summary(sid=START_MS, sei_mean=70.0, n=60, valid_ms=240_000):
    c = HabitFeature.COUNT
    mean, std, count = [0.0] * c, [0.0] * c, [0] * c
    mean[HabitFeature.SEI] = sei_mean
    std[HabitFeature.SEI] = 8.0
    count[HabitFeature.SEI] = n
    mean[HabitFeature.EAR] = 0.25
    std[HabitFeature.EAR] = 0.03
    count[HabitFeature.EAR] = 40
    return SessionSummary(id=sid, valid_ms=valid_ms, face_ratio=0.91, valid_ratio=0.78,
                          quality=82.0, mean=mean, std=std, count=count, quality_count=40,
                          band_in=10, band_below=1, band_above=2, sensitivity_level=1)


BANDS = [BandOutcome(feature=HabitFeature.SEI, mean=70.0, min=60.0, max=85.0,
                     samples=13, below=1, above=2)]
ALERTS = [{"code": "DROWSY", "wall_ms": START_MS + 60_000},
          {"code": "LOW_BLINK", "wall_ms": START_MS + 120_000}]


def write_session(store, student_id="hs1", name="Le Van A", **kw):
    store.write_session(student_id=student_id, student_name=name, s=kw.pop("s", summary()),
                        band_outcomes=kw.pop("band_outcomes", BANDS),
                        ended_at_wall_ms=kw.pop("ended_at_wall_ms", END_MS),
                        device="test-cam", ineligible_reason=kw.pop("ineligible_reason", None),
                        alerts_total=kw.pop("alerts_total", 2), csv_rows=60, avg_fps=20.5,
                        alerts=kw.pop("alerts", ALERTS), **kw)


def main():
    tmp = tempfile.mkdtemp()
    db = os.path.join(tmp, "focuschange.db")
    out = os.path.join(tmp, "out")
    store = LocalStore(db_path=db, local_dir=out, class_id="lop12a",
                       class_label="Lop 12A", school_year="2026-2027", app_version="edge-test")
    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row

    check("database file created", os.path.exists(db))
    check("enabled once open", store.enabled)
    check("ten tables, as the schema declares them",
          sorted(r[0] for r in conn.execute(
              "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")),
          ["ai_results", "alerts", "baseline_profiles", "class_memberships", "classes",
           "feature_records", "observation_sessions", "review_records", "students",
           "sync_history"])

    # ------------------------------------------------------------------ session
    write_session(store)
    check("one student row", conn.execute("SELECT COUNT(*) c FROM students").fetchone()["c"], 1)
    check("student name stored",
          conn.execute("SELECT name FROM students").fetchone()["name"], "Le Van A")
    cls = conn.execute("SELECT * FROM classes").fetchone()
    check("class stored with the configured school year",
          (cls["class_id"], cls["name"], cls["school_year"]), ("lop12a", "Lop 12A", "2026-2027"))
    mem = conn.execute("SELECT * FROM class_memberships").fetchone()
    check("membership opened from the session's own day", mem["started_on"],
          time.strftime("%Y-%m-%d", time.localtime(START_MS / 1000.0)))
    check("membership still open", mem["ended_on"], None)

    ses = conn.execute("SELECT * FROM observation_sessions").fetchone()
    check("session keyed to the child", ses["student_id"], "hs1")
    check("session keyed to the class", ses["class_id"], "lop12a")
    check("instrument named in a column, not in a document key", ses["source"], "edge-camera")
    check("start time read from the session id", ses["started_at_wall_ms"], START_MS)
    check("end time stored", ses["ended_at_wall_ms"], END_MS)
    check("valid time stored", ses["valid_ms"], 240_000)
    check("device and version stored", (ses["device"], ses["app_version"]),
          ("test-cam", "edge-test"))

    meta = json.loads(ses["notes"])
    check("face ratio kept in notes", meta["face_ratio"], 0.91)
    check("valid ratio kept in notes", meta["valid_ratio"], 0.78)
    check("quality kept in notes", meta["quality_mean"], 82.0)
    check("sensitivity kept in notes", meta["sensitivity"], 1)
    check("alert count kept in notes", meta["alerts_total"], 2)
    check("false alerts are a real zero, not an absent column", meta["false_alerts"], 0)
    check("fps rounded to two decimals", meta["avg_fps"], 20.5)
    check("eligible verdict kept", meta["eligible"], True)
    check("no ineligible reason when eligible", "ineligible_reason" not in meta)
    check("band outcome carried per feature",
          meta["band_sei"], {"min": 60.0, "max": 85.0, "typical": 70.0, "frames": 13,
                             "below": 1, "above": 2})

    feats = conn.execute("SELECT * FROM feature_records ORDER BY feature").fetchall()
    check("only the measured features are stored", [f["feature"] for f in feats],
          [HabitFeature.KEYS[HabitFeature.EAR], HabitFeature.KEYS[HabitFeature.SEI]])
    check("mean and std and n stored",
          [(f["mean"], f["std"], f["n"], f["extractor"]) for f in feats],
          [(0.25, 0.03, 40, "mediapipe"), (70.0, 8.0, 60, "mediapipe")])

    rows = conn.execute("SELECT * FROM alerts ORDER BY alert_id").fetchall()
    check("every alert of the session became a row", len(rows), 2)
    check("alert kinds kept", [a["kind"] for a in rows], ["DROWSY", "LOW_BLINK"])
    check("alert times kept", [a["raised_at_wall_ms"] for a in rows],
          [START_MS + 60_000, START_MS + 120_000])
    check("alert message is the phone's own sentence",
          [a["message"] for a in rows]
          == [ALERT_SENTENCES["DROWSY"], ALERT_SENTENCES["LOW_BLINK"]])
    check("alert belongs to the session that produced it",
          set(a["session_row"] for a in rows), {ses["session_row"]})

    # ------------------------------------------------------------------ replace
    write_session(store, s=summary(sei_mean=72.0), alerts=ALERTS[:1], alerts_total=1)
    check("a second commit of one session replaces it, not doubles it",
          conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"], 1)
    check("replacing a session replaces its features",
          conn.execute("SELECT mean FROM feature_records WHERE feature='sei'")
          .fetchone()["mean"], 72.0)
    check("replacing a session replaces its alerts",
          conn.execute("SELECT COUNT(*) c FROM alerts").fetchone()["c"], 1)
    check("replacing a session leaves one student",
          conn.execute("SELECT COUNT(*) c FROM students").fetchone()["c"], 1)
    check("replacing a session does not reopen membership history",
          conn.execute("SELECT COUNT(*) c FROM class_memberships").fetchone()["c"], 1)

    # ------------------------------------------------------------------ ineligible
    # An ineligible session still has to be stored - "we watched and it did not qualify" is a
    # finding, and the habit profile needs it to say why the band did not move.
    SHORT_START = int(time.time() * 1000) - 60_000
    write_session(store, student_id="hs2", name="Tran B", ended_at_wall_ms=SHORT_START + 60_000,
                  s=summary(sid=SHORT_START, valid_ms=30_000),
                  ineligible_reason="chua du 2 phut hop le", alerts_total=0, alerts=[])
    r2 = conn.execute("SELECT * FROM observation_sessions WHERE student_id='hs2'").fetchone()
    m2 = json.loads(r2["notes"])
    check("ineligible session still gets its row", r2 is not None)
    check("ineligible verdict in notes", (m2["eligible"], m2["ineligible_reason"]),
          (False, "chua du 2 phut hop le"))
    check("a session with no alerts writes no alert rows",
          conn.execute("SELECT COUNT(*) c FROM alerts WHERE student_id='hs2'").fetchone()["c"], 0)

    # ------------------------------------------------------------------ child moved class
    # Two ways a membership row can exist: the camera opened it, or a person did. Only the first
    # is the camera's to close - a class index must not overrule the school's register.
    conn.executemany("INSERT INTO students (student_id, name, created_at_ms) VALUES (?, ?, ?)",
                     [("hs3", "Ba Nguyen", 1000), ("hs4", "Bon Pham", 1000)])
    conn.executemany(
        "INSERT INTO class_memberships (student_id, class_id, school_year, started_on, note)"
        " VALUES (?, ?, ?, ?, ?)",
        [("hs3", "lop12a", "2026-2027", "2026-09-01", "edge-camera"),
         ("hs4", "lop12a", "2026-2027", "2026-09-01", "giao vien ghi")])
    conn.commit()
    store2 = LocalStore(db_path=db, local_dir=out, class_id="lop12b", class_label="Lop 12B",
                        school_year="2026-2027", app_version="edge-test")
    sessions_before = conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"]
    write_session(store2, student_id="hs3", name="Ba Nguyen", s=summary(sid=END_MS))
    write_session(store2, student_id="hs4", name="Bon Pham", s=summary(sid=END_MS + 1),
                  ended_at_wall_ms=END_MS + 1)
    open3 = conn.execute("SELECT * FROM class_memberships WHERE student_id='hs3'"
                         " AND ended_on IS NULL").fetchall()
    check("a camera-opened membership moves", len(open3), 1)
    check("the open membership is the new class", open3[0]["class_id"], "lop12b")
    check("the old period was closed, not deleted",
          conn.execute("SELECT ended_on FROM class_memberships WHERE student_id='hs3'"
                       " AND class_id='lop12a'").fetchone()["ended_on"],
          time.strftime("%Y-%m-%d"))
    check("the closed period kept its real start",
          conn.execute("SELECT started_on FROM class_memberships WHERE student_id='hs3'"
                       " AND class_id='lop12a'").fetchone()["started_on"], "2026-09-01")
    human = conn.execute("SELECT * FROM class_memberships WHERE student_id='hs4'").fetchall()
    check("a person's membership row is left alone", len(human), 1)
    check("it is still open and still in the old class",
          (human[0]["ended_on"], human[0]["class_id"]), (None, "lop12a"))
    check("both moves still stored their measurement",
          conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"],
          sessions_before + 2)
    check("the new class got its own row",
          conn.execute("SELECT COUNT(*) c FROM classes").fetchone()["c"], 2)

    # ------------------------------------------------------------------ baselines
    profile = HabitProfile("hs1", "Le Van A")
    for i in range(3):
        profile.commit(summary(sid=START_MS - i * 600_000, sei_mean=70.0 + i))
    store.write_band("hs1", "Le Van A", profile, END_MS, "test-cam")
    base = conn.execute("SELECT * FROM baseline_profiles").fetchone()
    check("baseline window spans the learned sessions",
          (base["window_start_ms"], base["window_end_ms"]), (START_MS - 1200_000, END_MS))
    check("baseline counts the sessions it learned from", base["sessions_used"], 3)
    check("three sessions do not clear the learning bars yet", profile.is_ready(), False)
    check("so the baseline says building", base["status"], "building")
    bj = json.loads(base["features_json"])
    check("baseline json is an object of features", sorted(bj.keys()),
          ["band_k", "device", "features", "source"])
    check("baseline carries the sei band",
          sorted(bj["features"].keys()), [HabitFeature.KEYS[HabitFeature.SEI]])
    sei_band = bj["features"][HabitFeature.KEYS[HabitFeature.SEI]]
    check("band typical sits inside the session means",
          69.0 <= sei_band["typical"] <= 72.0)
    check("band min is below typical and max above",
          sei_band["min"] < sei_band["typical"] < sei_band["max"])
    check("band spread recorded", sei_band["spread"] > 0)
    empty = HabitProfile("hs9", "Khong co du lieu")
    store.write_band("hs9", "Khong co du lieu", empty, END_MS, "test-cam")
    check("a profile with nothing learned writes no baseline",
          conn.execute("SELECT COUNT(*) c FROM baseline_profiles").fetchone()["c"], 1)

    # ------------------------------------------------------------------ live views
    store.write_presence("hs1", "Le Van A", summary(), START_MS, END_MS, 250_000, 2,
                         "test-cam", "working")
    check("heartbeat file written", os.path.exists(os.path.join(out, "presence_hs1.json")))
    hb = json.load(open(os.path.join(out, "presence_hs1.json"), encoding="utf-8"))
    check("heartbeat keeps the phone's column names",
          (hb["state"], hb["active_ms"], hb["alerts_total"], hb["source"]),
          ("working", 250_000, 2, "edge-camera"))
    store.write_class_live("lop12a", {"hs1": {"name": "Le Van A", "sei": 71}},
                           [{"code": "DROWSY"}], END_MS)
    live = json.load(open(os.path.join(out, "class_live_lop12a.json"), encoding="utf-8"))
    check("class board names the class and the year",
          (live["class_id"], live["class_label"], live["school_year"]),
          ("lop12a", "Lop 12A", "2026-2027"))
    check("class board lists the student", list(live["students"].keys()), ["hs1"])
    before = conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"]
    store.write_presence("hs1", "Le Van A", summary(), START_MS, END_MS, 250_000, 2,
                         "test-cam", "looking_away")
    check("a heartbeat is not a measured session",
          conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"], before)
    check("heartbeat replaced rather than appended",
          len([f for f in os.listdir(out) if f.startswith("presence_")]), 1)

    # ------------------------------------------------------------------ failure paths
    # A closed store writes nothing and raises nothing: the run is over, there is no measurement
    # left to lose.
    sent_before, failed_before = store.sent, store.failed
    store.close()
    check("closed store reports itself disabled", store.enabled, False)
    write_session(store)
    check("writing to a closed store is a silent no-op",
          (store.sent, store.failed), (sent_before, failed_before))

    # A store that opens fine and then refuses a write is the case the warning exists for: it has
    # to say so once, count every refusal, leave the run going, and above all leave no half session
    # behind. An empty student id is refused by the schema's own CHECK, so this drives the real
    # path rather than a mocked exception.
    store3 = LocalStore(db_path=db, local_dir=out, class_id="lop12a", app_version="edge-test")
    rows_before = conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"]
    wrote, fails_before = store3.sent, store3.failed
    for i in (1, 2):
        write_session(store3, student_id="", name="Khong co ma hoc sinh",
                      s=summary(sid=END_MS + i))
    check("a refused write is counted, not swallowed", store3.failed, fails_before + 2)
    check("a refused write stores nothing", store3.sent, wrote)
    check("a refused session leaves no half-written row",
          conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"],
          rows_before)
    check("a refused session leaves no orphan alerts",
          conn.execute("SELECT COUNT(*) c FROM alerts WHERE student_id=''").fetchone()["c"], 0)
    store3.close()

    # An unreadable path must not stop a run: the constructor reports and stays disabled. A folder
    # that does not exist yet is NOT such a path - the store makes it, because data/ is not in git.
    (fd, notadir) = tempfile.mkstemp(dir=tmp)
    os.close(fd)
    broken = LocalStore(db_path=os.path.join(notadir, "x.db"), local_dir=out)
    check("a database that cannot open leaves the run going", broken.enabled, False)
    check("a database that cannot open says why", broken.last_error != "")
    broken.write_session(student_id="hs1", student_name="A", s=summary(), band_outcomes=[],
                         ended_at_wall_ms=END_MS, device="d", ineligible_reason=None,
                         alerts_total=0, csv_rows=0, avg_fps=0.0)
    check("writing to a store that never opened raises nothing", broken.failed, 0)
    check("a missing data folder is created rather than complained about",
          LocalStore(db_path=os.path.join(tmp, "brand", "new", "db.sqlite"),
                     local_dir=out).enabled, True)
    os.remove(notadir)

    # A disk that refuses the live view must not be mistaken for a refused measurement.
    (fd, blocker) = tempfile.mkstemp(dir=tmp)
    os.close(fd)
    blocked = LocalStore(db_path=os.path.join(tmp, "second.db"), local_dir=out,
                         class_id="lop12a")
    blocked._local_dir = blocker          # a file cannot hold a folder
    blocked.write_class_live("lop12a", {}, [], END_MS)
    check("a refused live view counts, without raising", blocked.failed, 1)
    check("a refused live view still leaves the database open", blocked.enabled)
    blocked.close()
    os.remove(blocker)

    # ------------------------------------------------------------------ path and rounding rules
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    check("a relative db path is anchored at the repo root, not the current folder",
          resolve_db_path(os.path.join("data", "focuschange.db")),
          os.path.normpath(os.path.join(repo, "data", "focuschange.db")))
    check("an absolute db path is taken as written", resolve_db_path(db), db)
    check("an empty db path falls back to the schema's own file",
          resolve_db_path(""), os.path.normpath(str(_schema_db_path())))
    check("half-up rounding, the phone's rule", _round2(0.125), 0.13)
    check("banker's rounding would have said 0.12", _round2(0.125) != round(0.125, 2))
    check("a non-finite value stores 0.0, never NaN", _round2(float("nan")), 0.0)

    # ------------------------------------------------------------------ the orchestrator's own calls
    # Everything above calls LocalStore by hand. classroom.py calls it from _retire and
    # _write_heartbeats, and a renamed keyword there is invisible until a real session ends - by
    # which time the run is over. This section makes those two calls for real, on a pipeline with no
    # detector and no camera, so the wiring is checked the same day the writer changed.
    store4 = LocalStore(db_path=db, local_dir=out, class_id="lop12c",
                        class_label="Lop 12C", school_year="2026-2027", app_version="edge-test")
    wired = _wired_pipeline(store4)
    rt = _runtime(11, "hs5", "Nam Nguyen")
    wired._tracks = {11: rt}
    rt.pipeline.start_session(START_MS)
    wired._register_alert(rt, "DROWSY", START_MS + 1000)
    wired._register_alert(rt, "LOOK_AWAY", START_MS + 2000)
    wired._retire(rt, END_MS)
    row5 = conn.execute("SELECT * FROM observation_sessions WHERE student_id='hs5'").fetchone()
    check("classroom._retire reaches the database", row5 is not None)
    check("the alert rows came from _register_alert",
          conn.execute("SELECT COUNT(*) c FROM alerts WHERE student_id='hs5'").fetchone()["c"], 2)
    check("_retire reports its own alert count",
          json.loads(row5["notes"])["alerts_total"], 2)
    check("a session measured on a bare pipeline is a real, short, ineligible session",
          json.loads(row5["notes"])["eligible"], False)
    rt2 = _runtime(12, "hs6", "Lan Tran")
    wired._tracks = {12: rt2}
    rt2.pipeline.start_session(START_MS)
    beats_before = len(os.listdir(out))
    wired._write_heartbeats(END_MS)
    check("classroom._write_heartbeats reaches the live folder", len(os.listdir(out)) > beats_before)
    check("the heartbeat file is the student's own",
          os.path.exists(os.path.join(out, "presence_hs6.json")))
    check("the orchestrator's calls were accepted, not refused", store4.failed, 0)
    store4.close()

    conn.close()
    print("\n" + store2.report())
    if FAILS:
        print("\n%d FAILURES: %s" % (len(FAILS), FAILS))
        sys.exit(1)
    print("\nALL PASS")


def _schema_db_path():
    import os as _os
    import sys as _sys
    root = _os.path.dirname(_os.path.dirname(_os.path.dirname(_os.path.abspath(__file__))))
    if root not in _sys.path:
        _sys.path.insert(0, root)
    from database import focuschange_db
    return focuschange_db.DB_PATH


def _runtime(track_id, student_id, name):
    """A track the orchestrator would hold after layer 2 bound it, with nothing measured.

    PersonPipeline is real because _retire and _write_heartbeats both read it; the camera layers
    stay absent, so a session that never got a frame is what reaches the writer.
    """
    from focusedge.person_pipeline import PersonPipeline
    from focusedge.classroom import TrackRuntime

    class _Guest:
        bound_student_id = None
        confidence = "unbound"

    rt = TrackRuntime(track_id=track_id, identity=_Guest(),
                      pipeline=PersonPipeline(track_id, None, 1, 12.0),
                      student_id=student_id, student_name=name, identity_conf="high")
    return rt


def _wired_pipeline(store):
    """ClassroomPipeline wired to the real LocalStore and to nothing else.

    detector, embedder, roster, analyzer and scheduler are None because the three methods called
    below touch none of them: _retire reads the track runtime and the writer, _write_heartbeats
    likewise, _register_alert neither. That is the point - if one of them ever grows a dependency
    on layer 1, this raises instead of writing a session that the camera path never produces.
    """
    from focusedge.classroom import ClassroomPipeline

    class _Roster:
        def name_of(self, student_id):
            return {"hs5": "Nam Nguyen", "hs6": "Lan Tran"}.get(student_id, student_id)

    cfg = {"class_id": "lop12c", "class_label": "Lop 12C",
           "person": {"sensitivity": 1, "habit_learn": True},
           "input": {"source": "test-cam"},
           "storage": {"heartbeat_ms": 300000, "class_live_ms": 20000}}
    return ClassroomPipeline(cfg, None, None, _Roster(), None, None, store)


if __name__ == "__main__":
    main()
