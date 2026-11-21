"""Layer 5 - local storage: one SQLite file, the ten tables of ``database/focuschange_db.py``.

This module replaces ``firestore_writer.py``. The four method names it is called by
(``write_session``, ``write_band``, ``write_presence``, ``write_class_live``) are unchanged, so the
orchestrator's wiring did not have to move with the destination of the data. What changed is where
a measurement ends up and, with it, two rules the cloud version had to obey and no longer does:

  * no document keys. The cloud needed ``<id>-cam-<n>`` so a camera run could not ``set()`` over a
    week of phone rows for the same child. Here the instrument is a COLUMN
    (``observation_sessions.source``), so the id is the student's own and one child has one row per
    measured session no matter which device measured it.
  * no retry queue. ``pending_upload.jsonl`` existed because a network write can be refused. A disk
    write either lands or it does not, and the run keeps the frames it already measured; the queue
    is gone along with the service-account key and the ``firebase_admin`` import.

Where the ten tables have no column for a number the cloud sent, the number is not dropped and not
forced into a column that would misrepresent it:

  * per-session aggregates (face/valid ratio, quality, sensitivity, fps, alert counts) go into
    ``observation_sessions.notes`` as one JSON object. They describe how the session was measured,
    which is what ``notes`` is for. ``feature_records`` keeps the mean/std/n contract - a ratio has
    no meaningful std here, so inventing 0.0 would claim zero variance.
  * the learned bands go into ``baseline_profiles.features_json``, one object per feature.
  * the live rows (``presence`` heartbeat and ``class_live``) have NO table in that schema. They are
    a picture of the last few seconds, not a record, and widening a schema someone wrote from a
    specification document is not this module's call. They are dumped as ``out/*.json`` instead -
    same content as before, same folder the operator already looks in, just nobody reading it over
    the network any more.

``alerts`` does have a table, and unlike the cloud it gets the real rows: the writer used to send a
count only, so "which alert, when" existed nowhere but a 200-item ring buffer on screen.
"""
from __future__ import annotations

import json
import math
import os
import sqlite3
import threading
import time
from typing import Dict, List, Optional, Sequence, Tuple

from .constants import HabitConfig, HabitFeature
from .habit import HabitProfile, SessionSummary
from .hud import alert_sentence

# Which instrument filled a row. The column is the phone's own, and it is the only place the two
# instruments are told apart now that the document key no longer has to carry the difference.
SOURCE = "edge-camera"

# ``classes.school_year`` is NOT NULL and is part of the key a membership points at, so it has to
# come from somewhere the operator can read. config.yaml has no such line, and a run in June and a
# run in September would otherwise land in two different school years with nothing saying which.
DEFAULT_SCHOOL_YEAR = "2026-2027"

# Written into class_memberships.note so the camera can tell its own rows from a register a person
# maintains. Without it, "close the previous membership" would edit somebody else's record.
OWNED = SOURCE

COLLECTION_DIR = "out"


def _repo_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def default_db_path() -> str:
    """``<repo>/data/focuschange.db`` - the path ``database/focuschange_db.py`` chose for itself.

    Read from that module rather than spelled out again, so there is one answer to "which file does
    the camera write" and it cannot drift from the schema's own idea of where it lives. It is a
    ``Path`` there, so it comes back as a string: everything else in this module is a path string.
    """
    return str(_db().DB_PATH)


def resolve_db_path(value: str = "") -> str:
    """Anchor a configured database path at the repo root, never at the current folder.

    ``Chay-Cam.bat`` changes into edge-server/ before starting, but a run from the repo root does
    not, and then "../data/focuschange.db" would mean two different files on the same machine - the
    failure mode ``resolve_asset`` already fixes for the model weights. Absolute paths are taken as
    written, so a test or a second disk can point the store somewhere else on purpose.
    """
    if not value:
        return default_db_path()
    if os.path.isabs(value):
        return value
    return os.path.normpath(os.path.join(_repo_root(), value))


def _db():
    """Import ``database.focuschange_db`` whatever folder the process was started from.

    The schema module sits at the repo root, one level above edge-server/, and that root is only on
    ``sys.path`` when main.py happens to be run from there. classroom.py used to do this insert for
    the 5-table mirror; with the mirror folded into this module the path work belongs here.
    """
    import sys
    repo_root = _repo_root()
    if repo_root not in sys.path:
        sys.path.insert(0, repo_root)
    import database.focuschange_db as schema
    return schema


def _day_of(wall_ms: int) -> str:
    return time.strftime("%Y-%m-%d", time.localtime(wall_ms / 1000.0))


class LocalStore:
    """Writes measurements into the ten-table database and dumps the live views to ``out/``.

    A disk that refuses the file must cost the run its record, never its measurement: every public
    method is guarded, the first failure prints and later ones only move the ``failed`` counter.
    The pipeline keeps measuring either way.
    """

    def __init__(self, db_path: str = "", local_dir: str = COLLECTION_DIR,
                 class_id: str = "class", class_label: str = "",
                 school_year: str = DEFAULT_SCHOOL_YEAR,
                 app_version: str = "edge-0.1.0"):
        self.class_id = class_id
        self.class_label = class_label or class_id
        self.school_year = school_year or DEFAULT_SCHOOL_YEAR
        self.app_version = app_version
        self._local_dir = local_dir
        self._lock = threading.Lock()
        self._conn: Optional[sqlite3.Connection] = None
        self.db_path = resolve_db_path(db_path)
        self.sent = 0          # sessions/baselines/alerts actually landed in the database
        self.failed = 0
        self.last_error = ""
        self._warned = False
        self._open()

    # ------------------------------------------------------------------
    def _open(self) -> None:
        try:
            _db()  # makes the repo root importable; raises before sqlite is touched if not
            # sqlite3 creates the FILE but not the FOLDER, and the default path is
            # <repo>/data/focuschange.db - a folder that is not in git because it holds nothing but
            # measured data. Without this the first run on a fresh clone would say the database
            # could not be opened, which is true and useless.
            parent = os.path.dirname(self.db_path)
            if parent:
                os.makedirs(parent, exist_ok=True)
            self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
            self._conn.row_factory = sqlite3.Row
            self._conn.execute("PRAGMA foreign_keys = ON")
            _db().init_db(self._conn)
            print("database: %s (%d bang)" % (self.db_path, len(_db().TABLES)))
        except Exception as e:  # boundary with the filesystem and with the schema module
            self._conn = None
            self.last_error = type(e).__name__
            print("database NOT OPEN (%s: %s) - measurements run, nothing is stored"
                  % (type(e).__name__, e))

    @property
    def enabled(self) -> bool:
        return self._conn is not None

    @property
    def local_dir(self) -> str:
        """Folder holding the two live views - named so the status line can say it out loud."""
        return self._local_dir

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                try:
                    self._conn.close()
                except Exception:
                    pass
                self._conn = None

    def _fail(self, what: str, e: Exception) -> None:
        self.failed += 1
        self.last_error = type(e).__name__
        if not self._warned:
            self._warned = True
            print("database write refused (%s): %s: %s - later failures only count, "
                  "the run keeps measuring" % (what, type(e).__name__, e))

    # ==================================================================
    # sessions
    # ==================================================================
    def write_session(self, student_id: str, student_name: str, s: SessionSummary,
                      band_outcomes, ended_at_wall_ms: int, device: str,
                      ineligible_reason: Optional[str], alerts_total: int,
                      csv_rows: int, avg_fps: float, false_alerts: int = 0,
                      alerts: Sequence[Dict] = (),
                      identity: Optional[str] = None,
                      context_summary: Optional[Dict] = None,
                      tic_summary: Optional[Dict] = None) -> None:
        """One measured session: its row, its per-feature statistics, its alerts.

        ``s.id`` IS the wall clock of the session start (``start_session(wall_ms)`` in
        person_pipeline, the phone's rule), so ``started_at_wall_ms`` is read from it rather than
        carried as a second copy of the same fact that could disagree.

        ``context_summary`` / ``tic_summary`` là tổng kết lớp KHUYẾN NGHỊ sư phạm (tài liệu
        1+2+3), không phải số liệu parity: chúng đi vào notes JSON dưới khoá riêng
        (context_eval / tic_watch), không đụng alerts_total hay bất kỳ cột nào của điện thoại.
        """
        if self._conn is None:
            return
        try:
            with self._lock:
                self._ensure_student(student_id, student_name)
                self._ensure_membership(student_id, ended_at_wall_ms, _day_of(s.id))
                self._replace_session(student_id, s.id)
                row = self._insert_session(
                    student_id, s, ended_at_wall_ms, device, ineligible_reason, alerts_total,
                    false_alerts, csv_rows, avg_fps, band_outcomes, identity,
                    context_summary, tic_summary)
                self._insert_features(row, s)
                self._insert_alerts(row, student_id, alerts)
                self._conn.commit()
            self.sent += 1
        except Exception as e:  # boundary with the database
            self._rollback()
            self._fail("session %s/%s" % (student_id, s.id), e)

    def _rollback(self) -> None:
        # One session is one transaction (its student, its row, its features, its alerts). Partial
        # rows would be worse than none: a feature_records entry without its session, or an alert
        # that belongs to no run.
        try:
            self._conn.rollback()
        except Exception:
            pass

    def _ensure_student(self, student_id: str, student_name: str) -> None:
        # INSERT OR IGNORE, not an upsert of the name: ``students`` also carries birth_year and
        # gender, filled in by whoever maintains the roster. Re-writing the row on every measured
        # session would flatten whatever else lives in it.
        self._conn.execute(
            "INSERT OR IGNORE INTO students (student_id, name, created_at_ms) VALUES (?, ?, ?)",
            (student_id, student_name or student_id, int(time.time() * 1000)))

    def _ensure_class(self) -> None:
        # A class row exists once per class_id (that is its primary key), so a school-year change
        # does not move it: updating ``school_year`` in place is refused by the schema as soon as
        # any membership points at the old year. Left alone rather than fought.
        self._conn.execute(
            "INSERT OR IGNORE INTO classes (class_id, name, school_year, created_at_ms)"
            " VALUES (?, ?, ?, ?)",
            (self.class_id, self.class_label, self.school_year, int(time.time() * 1000)))

    def _ensure_membership(self, student_id: str, now_wall_ms: int, started_on: str) -> None:
        """Record that this instrument is watching this child in this class.

        The membership table is a school's enrolment register, and the camera is not the registrar:
        it only knows the days it had the child in frame. So a row is opened with ``note`` set to
        ``edge-camera`` and only a row the camera itself opened is ever closed. A human-maintained
        open membership is left exactly as it is - overwriting it would let a camera index decide
        which class a child belongs to, and the overlap trigger would reject the row anyway.

        Best effort in every case: a membership that cannot open must not cost the session its
        measurement.
        """
        self._ensure_class()
        rows = self._conn.execute(
            "SELECT membership_id, class_id, school_year, started_on, note FROM class_memberships"
            " WHERE student_id = ? AND ended_on IS NULL", (student_id,)).fetchall()
        for r in rows:
            if r["class_id"] == self.class_id and r["school_year"] == self.school_year:
                return                      # already recorded here
        today = _day_of(now_wall_ms)
        for r in rows:
            if r["note"] != OWNED:
                return                      # a person owns this child's register; do not fight it
            if r["started_on"] >= today:
                return                      # opened and moved the same day: [start, start) is not
            self._conn.execute(
                "UPDATE class_memberships SET ended_on = ? WHERE membership_id = ?",
                (today, r["membership_id"]))
            break
        self._conn.execute(
            "INSERT INTO class_memberships (student_id, class_id, school_year, started_on, note)"
            " VALUES (?, ?, ?, ?, ?)",
            (student_id, self.class_id, self.school_year, started_on, OWNED))

    def _insert_session(self, student_id: str, s: SessionSummary, ended_at_wall_ms: int,
                        device: str, ineligible_reason: Optional[str], alerts_total: int,
                        false_alerts: int, csv_rows: int, avg_fps: float,
                        band_outcomes, identity: Optional[str] = None,
                        context_summary: Optional[Dict] = None,
                        tic_summary: Optional[Dict] = None) -> int:
        meta: Dict = {
            "face_ratio": _round4(s.face_ratio),
            "valid_ratio": _round4(s.valid_ratio),
            "alerts_total": alerts_total,
            "false_alerts": false_alerts,
            "csv_rows": csv_rows,
            "avg_fps": _round2(avg_fps),
            # The same verdict the cloud column "eligible" carried, in words a person can read
            # without knowing that a missing reason means eligible.
            "eligible": ineligible_reason is None,
        }
        # "manual" means a human said this head belongs to that id, with no face compared to prove it.
        # Without this the declared rows and the recognised rows sit in one table indistinguishable.
        if identity:
            meta["identity"] = identity
        if ineligible_reason is not None:
            meta["ineligible_reason"] = ineligible_reason
        if s.quality_count > 0:
            meta["quality_mean"] = _round2(s.quality)
        if s.sensitivity_level >= 0:
            meta["sensitivity"] = s.sensitivity_level
        if s.band_samples > 0:
            meta["band_k"] = float(HabitConfig.BAND_K)
            meta["band_frames"] = s.band_samples
            meta["band_below"] = s.band_below
            meta["band_above"] = s.band_above
        # Lớp khuyến nghị sư phạm (tài liệu 1+2+3) - khoá riêng trong notes, không phải
        # cột parity. tic_watch kể cả level 0: ngày quan sát không mẫu hình là mẫu số
        # của tỉ lệ "gần như mỗi ngày" khi tic_history() đọc dọc trên 4 tuần.
        if context_summary is not None:
            meta["context_eval"] = context_summary
        if tic_summary is not None:
            meta["tic_watch"] = tic_summary
        for b in band_outcomes or ():
            key = HabitFeature.KEYS[b.feature]
            meta["band_" + key] = {"min": _round2(b.min), "max": _round2(b.max),
                                   "typical": _round2(b.mean), "frames": b.samples,
                                   "below": b.below, "above": b.above}
        cur = self._conn.execute(
            "INSERT INTO observation_sessions (student_id, class_id, source, started_at_wall_ms,"
            " ended_at_wall_ms, valid_ms, device, app_version, notes)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (student_id, self.class_id, SOURCE, s.id, ended_at_wall_ms, s.valid_ms,
             device, self.app_version, json.dumps(meta, ensure_ascii=False)))
        return int(cur.lastrowid)

    def _replace_session(self, student_id: str, session_id: int) -> None:
        """Delete a previous row for the same measured session, children and all.

        The cloud used ``set()``, so a second commit of one session replaced its record instead of
        doubling it; there is no unique key here to hang that on, because ``session_row`` is the
        primary key and ``started_at_wall_ms`` is free. The old path did nothing, so the same rule
        is applied by hand - and the cascade the schema declares on feature_records, ai_results and
        alerts does the child cleanup, which is why it is declared there.
        """
        old = self._conn.execute(
            "SELECT session_row FROM observation_sessions"
            " WHERE student_id = ? AND source = ? AND started_at_wall_ms = ?",
            (student_id, SOURCE, session_id)).fetchone()
        if old is not None:
            self._conn.execute("DELETE FROM observation_sessions WHERE session_row = ?",
                               (old["session_row"],))

    def _insert_features(self, session_row: int, s: SessionSummary) -> None:
        # Only features with at least one observation: a number nobody measured is an absent row,
        # not a zero - and here the schema enforces it, because feature_records CHECKs n > 0.
        rows = []
        for f in range(HabitFeature.COUNT):
            n = s.count[f]
            if n > 0:
                rows.append((session_row, HabitFeature.KEYS[f], _round2(s.mean[f]),
                             _round2(s.std[f]), n, "mediapipe"))
        if rows:
            self._conn.executemany(
                "INSERT OR REPLACE INTO feature_records (session_row, feature, mean, std, n,"
                " extractor) VALUES (?, ?, ?, ?, ?, ?)", rows)

    def write_instant_alert(self, student_id: str, student_name: str, code: str,
                            wall_ms: int) -> None:
        """Ghi ngay cảnh báo vào SQLite ngay trong lúc quay (không chờ phiên kết thúc)."""
        if not self._conn:
            return
        msg = alert_sentence(code, None, None) or code
        try:
            self._conn.execute(
                "INSERT INTO alerts (session_row, student_id, kind, message, raised_at_wall_ms, seen_at_ms)"
                " VALUES (0, ?, ?, ?, ?, NULL)",
                (student_id, code, msg, int(wall_ms)))
            self._conn.commit()
        except Exception:
            pass

    def _insert_alerts(self, session_row: int, student_id: str,
                       alerts: Sequence[Dict]) -> None:
        for a in alerts:
            code = str(a.get("code") or "")
            if not code:
                continue
            wall_ms = int(a.get("wall_ms") or 0)
            cur = self._conn.execute(
                "UPDATE alerts SET session_row = ? WHERE session_row = 0 AND student_id = ? AND raised_at_wall_ms = ?",
                (session_row, student_id, wall_ms))
            if cur.rowcount == 0:
                self._conn.execute(
                    "INSERT INTO alerts (session_row, student_id, kind, message, raised_at_wall_ms, seen_at_ms)"
                    " VALUES (?, ?, ?, ?, ?, NULL)",
                    (session_row, student_id, code,
                     alert_sentence(code, None, None) or code, wall_ms))

    # ==================================================================
    # learned bands
    # ==================================================================
    def write_band(self, student_id: str, student_name: str, profile: HabitProfile,
                   updated_at_wall_ms: int, device: str) -> None:
        """Append one baseline snapshot: the bands the profile has learned as of this session.

        ``baseline_profiles`` has no unique key - it is a history, one row per recomputation, which
        is what lets a later reader see how a child's typical value moved. The window is the span of
        the sessions the profile actually learned from.
        """
        if self._conn is None:
            return
        base = profile.baseline()
        features: Dict[str, Dict] = {}
        for f in HabitFeature.BAND_FEATURES:
            b = base.band(f)
            if b is None:
                continue
            features[HabitFeature.KEYS[f]] = {
                "typical": _round2(b.mean), "min": _round2(b.min), "max": _round2(b.max),
                "spread": _round2(base.spread(f) or 0.0),
            }
        if not features:
            return
        starts = [s.id for s in profile.sessions] or [updated_at_wall_ms]
        window_start = min(starts)
        days = max(1, int(math.ceil((updated_at_wall_ms - window_start) / 86_400_000.0)))
        try:
            with self._lock:
                self._ensure_student(student_id, student_name)
                self._conn.execute(
                    "INSERT INTO baseline_profiles (student_id, window_start_ms, window_end_ms,"
                    " days, sessions_used, status, features_json, created_at_ms)"
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (student_id, window_start, updated_at_wall_ms, days, profile.session_count(),
                     "ready" if profile.is_ready() else "building",
                     json.dumps({"band_k": float(HabitConfig.BAND_K), "source": SOURCE,
                                 "device": device, "features": features}, ensure_ascii=False),
                     updated_at_wall_ms))
                self._conn.commit()
            self.sent += 1
        except Exception as e:  # boundary with the database
            self._rollback()
            self._fail("baseline %s" % student_id, e)

    # ==================================================================
    # live views - no table for them, so they stay files
    # ==================================================================
    def write_presence(self, student_id: str, student_name: str, s: SessionSummary,
                       started_at_wall_ms: int, beat_at_wall_ms: int, active_ms: int,
                       alerts_total: int, device: str, state: str,
                       false_alerts: int = 0) -> None:
        self._dump("presence_%s.json" % student_id, {
            "student_id": student_id,
            "student_name": student_name,
            "session_id": s.id,
            "started_at_wall_ms": started_at_wall_ms,
            "beat_at_wall_ms": beat_at_wall_ms,
            "active_ms": active_ms,
            "valid_ms": s.valid_ms,
            "face_ratio": _round4(s.face_ratio),
            "valid_ratio": _round4(s.valid_ratio),
            "alerts_total": alerts_total,
            "false_alerts": false_alerts,
            "device": device,
            "app_version": self.app_version,
            "source": SOURCE,
            "state": state,
        })

    def write_class_live(self, class_id: str, students: Dict[str, Dict],
                         alerts: List[Dict], updated_at_wall_ms: int,
                         advisories: Sequence[Dict] = ()) -> None:
        self._dump("class_live_%s.json" % class_id, {
            "class_id": class_id,
            "class_label": self.class_label,
            "school_year": self.school_year,
            "updated_at_wall_ms": updated_at_wall_ms,
            "students": students,
            "alerts": alerts[-50:],  # keep the tail, not the whole history
            # Khuyến nghị sư phạm (off-task theo ngữ cảnh / dấu hiệu Tic) - tách khỏi
            # alerts của chuỗi parity điện thoại.
            "advisories": list(advisories)[-50:],
        })

    def _dump(self, name: str, payload: Dict) -> None:
        try:
            os.makedirs(self._local_dir, exist_ok=True)
            tmp = os.path.join(self._local_dir, name + ".tmp")
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(payload, f, ensure_ascii=False, indent=2)
            os.replace(tmp, os.path.join(self._local_dir, name))
        except Exception as e:  # boundary with the filesystem
            self.failed += 1
            self.last_error = type(e).__name__

    # ==================================================================
    # reading back
    # ==================================================================
    def report(self, limit: int = 10) -> str:
        """Plain-text summary of what this database holds, for the operator's console.

        Opens its own read-only look rather than reusing the run's connection, because the useful
        moment to ask is after the run has closed it. Missing file is reported, never created.
        """
        if not os.path.exists(self.db_path):
            return "no database at %s yet - nothing was stored this run" % self.db_path
        try:
            conn = sqlite3.connect(self.db_path)
            conn.row_factory = sqlite3.Row
            counts = ", ".join("%s=%s" % (t, conn.execute(
                'SELECT COUNT(*) FROM "%s"' % t).fetchone()[0]) for t in _db().TABLES)
            rows = conn.execute(
                "SELECT s.student_id, st.name, s.started_at_wall_ms, s.valid_ms"
                " FROM observation_sessions AS s JOIN students AS st USING (student_id)"
                " ORDER BY s.session_row DESC LIMIT ?", (int(limit),)).fetchall()
            conn.close()
        except Exception as e:
            return "database %s unreadable: %s: %s" % (self.db_path, type(e).__name__, e)
        out = ["database: %s" % self.db_path, "  " + counts]
        for row in rows:
            out.append("  %-12s %-18s %s %6.1f min" % (
                row["student_id"], (row["name"] or "")[:18],
                time.strftime("%m-%d %H:%M", time.localtime(row["started_at_wall_ms"] / 1000.0)),
                (row["valid_ms"] or 0) / 60000.0))
        if not rows:
            out.append("  khong co buoi do nao duoc luu")
        return "\n".join(out)


def _round2(v: float) -> float:
    return _round_to(v, 100.0)


def _round4(v: float) -> float:
    return _round_to(v, 10000.0)


def _round_to(v: float, scale: float) -> float:
    """Round half away from zero, the phone's rule (FirebaseSync.kt:944), not Python's.

    Kept from the cloud writer because the numbers stored here are compared with the phone's and
    the web leg's, and ``round()`` is banker's rounding: it sends 0.125 down to 0.12 where the phone
    sends it up to 0.13. Three instruments must not disagree in the last digit by accident.
    """
    try:
        if not math.isfinite(v):
            return 0.0
    except TypeError:
        return 0.0
    return math.floor(v * scale + 0.5) / scale
