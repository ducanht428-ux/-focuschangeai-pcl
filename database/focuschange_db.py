"""Local observation database with stable student IDs and dated class history."""
from __future__ import annotations

import sqlite3
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = PROJECT_DIR / "data"
DB_PATH = DATA_DIR / "focuschange.db"

TABLES = (
    "classes",
    "students",
    "class_memberships",
    "observation_sessions",
    "baseline_profiles",
    "feature_records",
    "ai_results",
    "alerts",
    "review_records",
    "sync_history",
)

SCHEMA = """
CREATE TABLE IF NOT EXISTS classes (
    class_id       TEXT PRIMARY KEY NOT NULL CHECK (length(trim(class_id)) > 0),
    name           TEXT NOT NULL CHECK (length(trim(name)) > 0),
    school_year    TEXT NOT NULL CHECK (length(trim(school_year)) > 0),
    grade          TEXT,
    created_at_ms  INTEGER NOT NULL,
    UNIQUE (class_id, school_year)
);

CREATE TABLE IF NOT EXISTS students (
    student_id     TEXT PRIMARY KEY NOT NULL CHECK (length(trim(student_id)) > 0),
    name           TEXT NOT NULL CHECK (length(trim(name)) > 0),
    birth_year     INTEGER,
    gender         TEXT,
    notes          TEXT,
    created_at_ms  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS class_memberships (
    membership_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id     TEXT NOT NULL REFERENCES students(student_id),
    class_id       TEXT NOT NULL,
    school_year    TEXT NOT NULL,
    started_on     TEXT NOT NULL CHECK (
        length(started_on) = 10 AND date(started_on, '+0 days') IS started_on),
    ended_on       TEXT CHECK (ended_on IS NULL OR (
        length(ended_on) = 10 AND date(ended_on, '+0 days') IS ended_on
        AND ended_on > started_on)),
    note           TEXT,
    FOREIGN KEY (class_id, school_year) REFERENCES classes(class_id, school_year),
    UNIQUE (student_id, started_on)
);

-- Membership periods are [started_on, ended_on), allowing transfer on the end date.
CREATE TRIGGER IF NOT EXISTS membership_no_overlap_insert
BEFORE INSERT ON class_memberships
WHEN EXISTS (
    SELECT 1 FROM class_memberships AS m
    WHERE m.student_id = NEW.student_id
      AND (NEW.ended_on IS NULL OR m.started_on < NEW.ended_on)
      AND (m.ended_on IS NULL OR NEW.started_on < m.ended_on)
)
BEGIN
    SELECT RAISE(ABORT, 'overlapping class membership');
END;

CREATE TRIGGER IF NOT EXISTS membership_no_overlap_update
BEFORE UPDATE ON class_memberships
WHEN EXISTS (
    SELECT 1 FROM class_memberships AS m
    WHERE m.student_id = NEW.student_id AND m.membership_id != OLD.membership_id
      AND (NEW.ended_on IS NULL OR m.started_on < NEW.ended_on)
      AND (m.ended_on IS NULL OR NEW.started_on < m.ended_on)
)
BEGIN
    SELECT RAISE(ABORT, 'overlapping class membership');
END;

CREATE TABLE IF NOT EXISTS observation_sessions (
    session_row        INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id         TEXT NOT NULL REFERENCES students(student_id),
    class_id           TEXT REFERENCES classes(class_id),
    source             TEXT NOT NULL DEFAULT 'edge-camera',
    started_at_wall_ms INTEGER,
    ended_at_wall_ms   INTEGER NOT NULL,
    valid_ms           INTEGER NOT NULL DEFAULT 0 CHECK (valid_ms >= 0),
    device             TEXT NOT NULL DEFAULT '',
    app_version        TEXT NOT NULL DEFAULT '',
    notes              TEXT,
    CHECK (started_at_wall_ms IS NULL OR ended_at_wall_ms >= started_at_wall_ms),
    UNIQUE (session_row, student_id)
);

CREATE TABLE IF NOT EXISTS baseline_profiles (
    baseline_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id      TEXT NOT NULL REFERENCES students(student_id),
    window_start_ms INTEGER NOT NULL,
    window_end_ms   INTEGER NOT NULL CHECK (window_end_ms >= window_start_ms),
    days            INTEGER NOT NULL DEFAULT 14 CHECK (days > 0),
    sessions_used   INTEGER NOT NULL DEFAULT 0 CHECK (sessions_used >= 0),
    status          TEXT NOT NULL DEFAULT 'building',
    features_json   TEXT NOT NULL DEFAULT '{}' CHECK (
        CASE WHEN json_valid(features_json) THEN json_type(features_json) = 'object' ELSE 0 END),
    created_at_ms   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS feature_records (
    feature_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    session_row  INTEGER NOT NULL REFERENCES observation_sessions(session_row) ON DELETE CASCADE,
    feature      TEXT NOT NULL CHECK (length(trim(feature)) > 0),
    mean         REAL NOT NULL,
    std          REAL NOT NULL CHECK (std >= 0),
    n            INTEGER NOT NULL CHECK (n > 0),
    extractor    TEXT NOT NULL DEFAULT 'mediapipe',
    UNIQUE (session_row, extractor, feature)
);

CREATE TABLE IF NOT EXISTS ai_results (
    result_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    session_row    INTEGER NOT NULL REFERENCES observation_sessions(session_row) ON DELETE CASCADE,
    sei_mean       REAL CHECK (sei_mean IS NULL OR sei_mean BETWEEN 0 AND 100),
    label          TEXT,
    explanation    TEXT,
    model_version  TEXT NOT NULL DEFAULT '',
    created_at_ms  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS alerts (
    alert_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_row       INTEGER NOT NULL,
    student_id        TEXT NOT NULL,
    kind              TEXT NOT NULL,
    message           TEXT NOT NULL,
    raised_at_wall_ms INTEGER NOT NULL,
    status            TEXT NOT NULL DEFAULT 'new',
    FOREIGN KEY (session_row, student_id)
        REFERENCES observation_sessions(session_row, student_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS review_records (
    review_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_id       INTEGER NOT NULL REFERENCES alerts(alert_id) ON DELETE CASCADE,
    reviewer       TEXT NOT NULL CHECK (length(trim(reviewer)) > 0),
    verdict        TEXT NOT NULL,
    comment        TEXT,
    reviewed_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_history (
    sync_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    direction     TEXT NOT NULL,
    target        TEXT NOT NULL DEFAULT '',
    status        TEXT NOT NULL,
    rows_synced   INTEGER NOT NULL DEFAULT 0 CHECK (rows_synced >= 0),
    started_at_ms INTEGER NOT NULL,
    detail        TEXT
);

CREATE INDEX IF NOT EXISTS sessions_by_student
    ON observation_sessions(student_id, ended_at_wall_ms);
CREATE INDEX IF NOT EXISTS baselines_by_student
    ON baseline_profiles(student_id, window_end_ms);
CREATE INDEX IF NOT EXISTS results_by_session ON ai_results(session_row);
CREATE INDEX IF NOT EXISTS alerts_by_session ON alerts(session_row, student_id);
CREATE INDEX IF NOT EXISTS reviews_by_alert ON review_records(alert_id);
"""


def get_connection(db_path: Path | str | None = None) -> sqlite3.Connection:
    target = DB_PATH if db_path is None else db_path
    if str(target) != ":memory:":
        Path(target).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(target)
    try:
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        init_db(conn)
    except Exception:
        conn.close()
        raise
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def table_names(conn: sqlite3.Connection) -> list[str]:
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
        " ORDER BY name").fetchall()
    return [r["name"] for r in rows]


def now_ms() -> int:
    return int(time.time() * 1000)


if __name__ == "__main__":
    conn = get_connection()
    try:
        print("KET NOI SQLITE THANH CONG!")
        print("Duong dan database:")
        print(DB_PATH)
        for name in TABLES:
            count = conn.execute('SELECT COUNT(*) FROM "' + name + '"').fetchone()[0]
            print("  %-22s %d rows" % (name, count))
        if set(table_names(conn)) != set(TABLES):
            raise SystemExit("Unexpected database tables")
        if conn.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise SystemExit("Database integrity check failed")
        if conn.execute("PRAGMA foreign_key_check").fetchall():
            raise SystemExit("Database foreign key check failed")
        print("10 BANG OK - INTEGRITY OK - FOREIGN KEYS OK")
    finally:
        conn.close()
