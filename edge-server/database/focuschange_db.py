"""SQLite schema for locally collected FocusChangeAI sessions."""
from __future__ import annotations

import os
import sqlite3


_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB_PATH = os.path.join(_REPO_ROOT, "data", "focuschange.db")

TABLES = (
    "students",
    "classes",
    "class_memberships",
    "observation_sessions",
    "feature_records",
    "alerts",
    "baseline_profiles",
    "ai_results",
    "review_records",
    "sync_history",
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS students (
    student_id TEXT PRIMARY KEY CHECK (length(trim(student_id)) > 0),
    name TEXT NOT NULL,
    birth_year INTEGER,
    gender TEXT,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS classes (
    class_id TEXT PRIMARY KEY CHECK (length(trim(class_id)) > 0),
    name TEXT NOT NULL,
    school_year TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS class_memberships (
    membership_id INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id TEXT NOT NULL REFERENCES students(student_id),
    class_id TEXT NOT NULL REFERENCES classes(class_id),
    school_year TEXT NOT NULL,
    started_on TEXT NOT NULL,
    ended_on TEXT,
    note TEXT,
    CHECK (ended_on IS NULL OR ended_on > started_on)
);
CREATE UNIQUE INDEX IF NOT EXISTS one_open_membership_per_student
    ON class_memberships(student_id) WHERE ended_on IS NULL;

CREATE TABLE IF NOT EXISTS observation_sessions (
    session_row INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id TEXT NOT NULL REFERENCES students(student_id),
    class_id TEXT NOT NULL REFERENCES classes(class_id),
    source TEXT NOT NULL,
    started_at_wall_ms INTEGER NOT NULL,
    ended_at_wall_ms INTEGER NOT NULL,
    valid_ms INTEGER NOT NULL CHECK (valid_ms >= 0),
    device TEXT NOT NULL,
    app_version TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '{}',
    CHECK (ended_at_wall_ms >= started_at_wall_ms)
);
CREATE INDEX IF NOT EXISTS observation_sessions_student_time
    ON observation_sessions(student_id, started_at_wall_ms);
CREATE UNIQUE INDEX IF NOT EXISTS observation_sessions_source_start
    ON observation_sessions(student_id, source, started_at_wall_ms);

CREATE TABLE IF NOT EXISTS feature_records (
    session_row INTEGER NOT NULL REFERENCES observation_sessions(session_row) ON DELETE CASCADE,
    feature TEXT NOT NULL,
    mean REAL NOT NULL,
    std REAL NOT NULL CHECK (std >= 0),
    n INTEGER NOT NULL CHECK (n > 0),
    extractor TEXT NOT NULL,
    PRIMARY KEY (session_row, feature)
);

CREATE TABLE IF NOT EXISTS alerts (
    alert_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_row INTEGER NOT NULL REFERENCES observation_sessions(session_row) ON DELETE CASCADE,
    student_id TEXT NOT NULL REFERENCES students(student_id),
    kind TEXT NOT NULL,
    message TEXT NOT NULL,
    raised_at_wall_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS alerts_session ON alerts(session_row);

CREATE TABLE IF NOT EXISTS baseline_profiles (
    baseline_id INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id TEXT NOT NULL REFERENCES students(student_id),
    window_start_ms INTEGER NOT NULL,
    window_end_ms INTEGER NOT NULL,
    days INTEGER NOT NULL CHECK (days > 0),
    sessions_used INTEGER NOT NULL CHECK (sessions_used >= 0),
    status TEXT NOT NULL,
    features_json TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_results (
    ai_result_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_row INTEGER NOT NULL REFERENCES observation_sessions(session_row) ON DELETE CASCADE,
    result_json TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS review_records (
    review_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_row INTEGER NOT NULL REFERENCES observation_sessions(session_row) ON DELETE CASCADE,
    reviewer_id TEXT,
    decision TEXT NOT NULL,
    note TEXT,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_history (
    sync_id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at_ms INTEGER NOT NULL,
    completed_at_ms INTEGER,
    status TEXT NOT NULL,
    details TEXT
);
"""


def init_db(conn: sqlite3.Connection) -> None:
    """Create missing tables and indexes without replacing collected records."""
    conn.executescript(_SCHEMA)
