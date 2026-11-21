"""SQLite storage for the FocusChangeAI project.

The file ``focuschange.db`` is not checked in and not created by hand: ``connect()``
opens it (creating it on first use) and ``init_db()`` builds every table with
``CREATE TABLE IF NOT EXISTS``, so running the program is enough for the database to
appear.

The columns mirror what ``edge-server/focusedge/firestore_writer.py`` already sends to
the cloud, so one session measured by the camera reads the same in SQLite and in
Firestore: same names, same meaning, and the same ``source`` tag that keeps a camera
row from overwriting a phone row for the same child.
"""
from __future__ import annotations

import os
import sqlite3
import time
from typing import Iterable, List, Optional, Sequence, Tuple

DB_NAME = "focuschange.db"
DEFAULT_SOURCE = "edge-camera"

SCHEMA = """
CREATE TABLE IF NOT EXISTS students (
    student_id     TEXT PRIMARY KEY,
    name           TEXT NOT NULL,
    seat           TEXT,
    created_at_ms  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    row_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id        TEXT NOT NULL REFERENCES students(student_id),
    session_id        INTEGER NOT NULL,
    source            TEXT NOT NULL,
    ended_at_wall_ms  INTEGER NOT NULL,
    valid_ms          INTEGER NOT NULL,
    face_ratio        REAL NOT NULL,
    valid_ratio       REAL NOT NULL,
    quality_mean      REAL,
    sensitivity       INTEGER,
    alerts_total      INTEGER NOT NULL DEFAULT 0,
    false_alerts      INTEGER NOT NULL DEFAULT 0,
    csv_rows          INTEGER NOT NULL DEFAULT 0,
    avg_fps           REAL NOT NULL DEFAULT 0,
    device            TEXT NOT NULL DEFAULT '',
    app_version       TEXT NOT NULL DEFAULT '',
    eligible          INTEGER NOT NULL,
    ineligible_reason TEXT,
    UNIQUE (student_id, source, session_id)
);

CREATE TABLE IF NOT EXISTS session_features (
    session_row  INTEGER NOT NULL REFERENCES sessions(row_id) ON DELETE CASCADE,
    feature      TEXT NOT NULL,
    mean         REAL NOT NULL,
    std          REAL NOT NULL,
    n            INTEGER NOT NULL,
    PRIMARY KEY (session_row, feature)
);

CREATE TABLE IF NOT EXISTS bands (
    student_id        TEXT NOT NULL REFERENCES students(student_id),
    source            TEXT NOT NULL,
    feature           TEXT NOT NULL,
    typical           REAL NOT NULL,
    min               REAL NOT NULL,
    max               REAL NOT NULL,
    spread            REAL NOT NULL,
    sessions_used     INTEGER NOT NULL,
    band_k            REAL NOT NULL,
    updated_at_wall_ms INTEGER NOT NULL,
    PRIMARY KEY (student_id, source, feature)
);

CREATE TABLE IF NOT EXISTS presence (
    student_id        TEXT NOT NULL REFERENCES students(student_id),
    source            TEXT NOT NULL,
    session_id        INTEGER NOT NULL,
    started_at_wall_ms INTEGER NOT NULL,
    beat_at_wall_ms   INTEGER NOT NULL,
    active_ms         INTEGER NOT NULL,
    valid_ms          INTEGER NOT NULL,
    face_ratio        REAL NOT NULL,
    valid_ratio       REAL NOT NULL,
    alerts_total      INTEGER NOT NULL DEFAULT 0,
    false_alerts      INTEGER NOT NULL DEFAULT 0,
    sei_mean          REAL,
    sei_n             INTEGER,
    state             TEXT NOT NULL DEFAULT '',
    device            TEXT NOT NULL DEFAULT '',
    app_version       TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (student_id, source)
);
"""


def default_path() -> str:
    """The database sits beside the ``database`` package, at the project root."""
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), DB_NAME)


def connect(db_path: Optional[str] = None) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path or default_path())
    conn.execute("PRAGMA foreign_keys = ON")
    conn.row_factory = sqlite3.Row
    init_db(conn)
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
    conn.commit()


def table_names(conn: sqlite3.Connection) -> List[str]:
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
        " ORDER BY name").fetchall()
    return [r["name"] for r in rows]


# ---------------------------------------------------------------------------
def upsert_student(conn: sqlite3.Connection, student_id: str, name: str,
                   seat: Optional[str] = None, now_ms: Optional[int] = None) -> None:
    now = int(now_ms if now_ms is not None else time.time() * 1000)
    conn.execute(
        "INSERT INTO students (student_id, name, seat, created_at_ms) VALUES (?, ?, ?, ?)"
        " ON CONFLICT(student_id) DO UPDATE SET name = excluded.name, seat = excluded.seat",
        (student_id, name, seat, now))
    conn.commit()


def save_session(conn: sqlite3.Connection, student_id: str, student_name: str, session_id: int,
                 ended_at_wall_ms: int, valid_ms: int, face_ratio: float, valid_ratio: float,
                 source: str = DEFAULT_SOURCE, quality_mean: Optional[float] = None,
                 sensitivity: Optional[int] = None, alerts_total: int = 0, false_alerts: int = 0,
                 csv_rows: int = 0, avg_fps: float = 0.0, device: str = "", app_version: str = "",
                 ineligible_reason: Optional[str] = None,
                 features: Optional[Sequence[Tuple[str, float, float, int]]] = None) -> int:
    """Write one session row plus its per-feature rows; returns the session row id.

    ``features`` holds ``(feature_key, mean, std, n)`` tuples, one per measured habit
    feature, and only for features with n > 0 - a feature nobody measured stays absent
    instead of being stored as a zero.
    """
    upsert_student(conn, student_id, student_name)
    cur = conn.execute(
        "INSERT INTO sessions (student_id, session_id, source, ended_at_wall_ms, valid_ms,"
        " face_ratio, valid_ratio, quality_mean, sensitivity, alerts_total, false_alerts,"
        " csv_rows, avg_fps, device, app_version, eligible, ineligible_reason)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        " ON CONFLICT(student_id, source, session_id) DO UPDATE SET"
        " ended_at_wall_ms = excluded.ended_at_wall_ms, valid_ms = excluded.valid_ms,"
        " face_ratio = excluded.face_ratio, valid_ratio = excluded.valid_ratio,"
        " quality_mean = excluded.quality_mean, sensitivity = excluded.sensitivity,"
        " alerts_total = excluded.alerts_total, false_alerts = excluded.false_alerts,"
        " csv_rows = excluded.csv_rows, avg_fps = excluded.avg_fps,"
        " device = excluded.device, app_version = excluded.app_version,"
        " eligible = excluded.eligible, ineligible_reason = excluded.ineligible_reason",
        (student_id, session_id, source, ended_at_wall_ms, valid_ms, face_ratio, valid_ratio,
         quality_mean, sensitivity, alerts_total, false_alerts, csv_rows, avg_fps, device,
         app_version, 1 if ineligible_reason is None else 0, ineligible_reason))
    row_id = cur.lastrowid
    if features:
        conn.execute("DELETE FROM session_features WHERE session_row = ?", (row_id,))
        conn.executemany(
            "INSERT OR REPLACE INTO session_features (session_row, feature, mean, std, n)"
            " VALUES (?, ?, ?, ?, ?)",
            [(row_id, key, mean, std, n) for key, mean, std, n in features])
    conn.commit()
    return row_id


def save_bands(conn: sqlite3.Connection, student_id: str, student_name: str,
               bands: Sequence[Tuple[str, float, float, float, float]], sessions_used: int,
               band_k: float, updated_at_wall_ms: int, source: str = DEFAULT_SOURCE) -> None:
    """One row per (student, source, feature); ``bands`` holds
    ``(feature, typical, min, max, spread)``."""
    upsert_student(conn, student_id, student_name)
    conn.executemany(
        "INSERT INTO bands (student_id, source, feature, typical, min, max, spread,"
        " sessions_used, band_k, updated_at_wall_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        " ON CONFLICT(student_id, source, feature) DO UPDATE SET"
        " typical = excluded.typical, min = excluded.min, max = excluded.max,"
        " spread = excluded.spread, sessions_used = excluded.sessions_used,"
        " band_k = excluded.band_k, updated_at_wall_ms = excluded.updated_at_wall_ms",
        [(student_id, source, key, typical, lo, hi, spread, sessions_used, band_k,
          updated_at_wall_ms) for key, typical, lo, hi, spread in bands])
    conn.commit()


def save_presence(conn: sqlite3.Connection, student_id: str, student_name: str, session_id: int,
                  started_at_wall_ms: int, beat_at_wall_ms: int, active_ms: int, valid_ms: int,
                  face_ratio: float, valid_ratio: float, alerts_total: int = 0,
                  false_alerts: int = 0, sei_mean: Optional[float] = None,
                  sei_n: Optional[int] = None, state: str = "", device: str = "",
                  app_version: str = "", source: str = DEFAULT_SOURCE) -> None:
    """One live row per (student, source), replaced on every heartbeat."""
    upsert_student(conn, student_id, student_name)
    conn.execute(
        "INSERT INTO presence (student_id, source, session_id, started_at_wall_ms,"
        " beat_at_wall_ms, active_ms, valid_ms, face_ratio, valid_ratio, alerts_total,"
        " false_alerts, sei_mean, sei_n, state, device, app_version)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        " ON CONFLICT(student_id, source) DO UPDATE SET"
        " session_id = excluded.session_id, started_at_wall_ms = excluded.started_at_wall_ms,"
        " beat_at_wall_ms = excluded.beat_at_wall_ms, active_ms = excluded.active_ms,"
        " valid_ms = excluded.valid_ms, face_ratio = excluded.face_ratio,"
        " valid_ratio = excluded.valid_ratio, alerts_total = excluded.alerts_total,"
        " false_alerts = excluded.false_alerts, sei_mean = excluded.sei_mean,"
        " sei_n = excluded.sei_n, state = excluded.state, device = excluded.device,"
        " app_version = excluded.app_version",
        (student_id, source, session_id, started_at_wall_ms, beat_at_wall_ms, active_ms,
         valid_ms, face_ratio, valid_ratio, alerts_total, false_alerts, sei_mean, sei_n,
         state, device, app_version))
    conn.commit()


# ---------------------------------------------------------------------------
def list_students(conn: sqlite3.Connection) -> List[sqlite3.Row]:
    return conn.execute("SELECT * FROM students ORDER BY student_id").fetchall()


def sessions_of(conn: sqlite3.Connection, student_id: str) -> List[sqlite3.Row]:
    return conn.execute(
        "SELECT * FROM sessions WHERE student_id = ? ORDER BY ended_at_wall_ms",
        (student_id,)).fetchall()


def features_of(conn: sqlite3.Connection, session_row: int) -> List[sqlite3.Row]:
    return conn.execute(
        "SELECT * FROM session_features WHERE session_row = ? ORDER BY feature",
        (session_row,)).fetchall()


def _fmt_ms(ms: Optional[int]) -> str:
    if not ms:
        return "-"
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ms / 1000.0))


def _minutes(ms: Optional[int]) -> str:
    return "%.1f min" % ((ms or 0) / 60000.0)


def report(db_path: Optional[str] = None) -> None:
    """Print what is inside the database. Reads only - never writes a row.

    The other way in is a SQLite browser, but that is another program to install; this
    needs nothing but the Python already on the machine.
    """
    path = db_path or default_path()
    if not os.path.exists(path):
        print("no database yet at %s" % path)
        print("it appears by itself the first time the program records a session")
        return
    conn = connect(path)
    print("database file: %s  (%.1f KB)" % (path, os.path.getsize(path) / 1024.0))
    for t in table_names(conn):
        n = conn.execute("SELECT COUNT(*) FROM " + t).fetchone()[0]
        print("  %-17s %d rows" % (t, n))
    students = list_students(conn)
    if not students:
        print("\nempty - no measured session has been written into it yet.")
        conn.close()
        return
    for st in students:
        seat = ("  seat " + st["seat"]) if st["seat"] else ""
        print("\n%s - %s%s" % (st["student_id"], st["name"], seat))
        for s in sessions_of(conn, st["student_id"]):
            verdict = "eligible" if s["eligible"] else "NOT eligible: " + str(s["ineligible_reason"])
            print("  session %s  ended %s  %s  face %.0f%%  alerts %d  fps %.1f  %s  [%s]"
                  % (s["session_id"], _fmt_ms(s["ended_at_wall_ms"]), _minutes(s["valid_ms"]),
                     100.0 * s["face_ratio"], s["alerts_total"], s["avg_fps"], verdict,
                     s["source"]))
            for f in features_of(conn, s["row_id"]):
                print("      %-12s mean %8.2f   std %7.2f   n %d"
                      % (f["feature"], f["mean"], f["std"], f["n"]))
        bands = conn.execute(
            "SELECT * FROM bands WHERE student_id = ? ORDER BY feature",
            (st["student_id"],)).fetchall()
        if bands:
            print("  learned bands (%d sessions, k=%.1f):" % (bands[0]["sessions_used"],
                                                               bands[0]["band_k"]))
            for b in bands:
                print("      %-12s typical %8.2f   from %8.2f to %8.2f"
                      % (b["feature"], b["typical"], b["min"], b["max"]))
        live = conn.execute(
            "SELECT * FROM presence WHERE student_id = ? ORDER BY source",
            (st["student_id"],)).fetchall()
        for p in live:
            print("  last beat %s  %s  %s  sei %s  [%s]"
                  % (_fmt_ms(p["beat_at_wall_ms"]), _minutes(p["active_ms"]),
                     p["state"] or "-", "-" if p["sei_mean"] is None
                     else "%.1f (n=%d)" % (p["sei_mean"], p["sei_n"] or 0), p["source"]))
    conn.close()


def _selftest(db_path: str) -> None:
    conn = connect(db_path)
    print("database file:", db_path)
    print("tables:", ", ".join(table_names(conn)))
    upsert_student(conn, "hs1", "Hoc sinh 1", seat="A1")
    save_session(conn, "hs1", "Hoc sinh 1", 7, 1_762_000_000_000, 240_000, 0.91, 0.78,
                 quality_mean=82.5, alerts_total=3, false_alerts=1, csv_rows=1200,
                 avg_fps=21.4, device="pc-cam", app_version="edge-0.1.0",
                 features=[("blink_bpm", 14.2, 2.1, 96), ("sei", 74.0, 8.5, 96)])
    save_bands(conn, "hs1", "Hoc sinh 1", [("sei", 74.0, 61.0, 88.0, 9.0)], 5, 1.5,
               1_762_000_000_000)
    save_presence(conn, "hs1", "Hoc sinh 1", 7, 1_761_999_000_000, 1_762_000_000_000,
                  250_000, 240_000, 0.91, 0.78, alerts_total=3, sei_mean=74.0, sei_n=96,
                  state="working")
    rows = sessions_of(conn, "hs1")
    print("students:", len(list_students(conn)), "sessions:", len(rows),
          "features of first session:", len(features_of(conn, rows[0]["row_id"])))
    conn.close()


if __name__ == "__main__":
    import sys
    # No argument prints what is inside, and changes nothing. --selftest is the write test:
    # it invents a student named hs1, so point it at a throwaway file, never at the real one.
    rest = [a for a in sys.argv[1:] if a != "--selftest"]
    where = rest[0] if rest else default_path()
    if "--selftest" in sys.argv:
        _selftest(where)
    else:
        report(where)
