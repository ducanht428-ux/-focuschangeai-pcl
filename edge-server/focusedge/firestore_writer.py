"""Layer 5 - cloud sync, keyed by studentId.

Mirrors FirebaseSync.kt where it matters - the field names, the "not measured is a
missing column, never a 0" rule, the on-disk retry queue - but it must NOT mirror the
document keys. The phone writes sessions/<sanitize(id)>-<n>, presence/<id> and
bands/<id> for the same child, and set() replaces a whole document, so a camera run
would silently erase the week of phone data the habit profile is built from, and the
teacher's screen would alternate between two instruments with nothing to say which one
produced the number on screen.

One profile per student, one row per instrument. Everything the camera writes stays in
the SAME collection, so a profile is still read by studentId, but under a key carrying
"-cam-":
  * sessions/<id>-cam-<n>   never collides with the phone's <id>-<n>
  * bands/<id>-cam          sits beside the phone's band for the same child
  * presence/<id>-cam       sits beside the phone's live row

The namespacing lives in the KEY and nowhere else. Inside the three documents every
column is the phone's column: same names, same set, same conditions, same rounding, so a
teacher's query reads one instrument or the other with no special case. The only extra
column is "source", and the only camera-only table is class_live, which the phone never
had a way to fill.
"""
from __future__ import annotations

import json
import math
import os
import threading
from typing import Dict, List, Optional

from .constants import HabitConfig, HabitFeature
from .habit import HabitProfile, SessionSummary


def sanitize(student_id: str) -> str:
    out = []
    for ch in student_id:
        out.append(ch if (ch.isalnum() or ch == "-" or ch == "_") else "-")
    return "".join(out)


# Tagged into the document name rather than the payload because two instruments measuring
# the same child are two rows of evidence, not one row that last writer wins. "cam" cannot
# collide with a session index: the phone's key is <id>-<digits>, this is <id>-cam-<digits>.
SOURCE_TAG = "cam"


def document_id(student_id: str, session_id: int) -> str:
    return sanitize(student_id) + "-" + SOURCE_TAG + "-" + str(session_id)


def student_doc_key(student_id: str) -> str:
    """Document name for the one-row-per-student tables, camera-namespaced."""
    return sanitize(student_id) + "-" + SOURCE_TAG


COLLECTION = "sessions"
PRESENCE_COLLECTION = "presence"
BAND_COLLECTION = "bands"
CLASS_LIVE_COLLECTION = "class_live"
SCHEMA = 1


class FirestoreWriter:
    def __init__(self, service_account: str = "", pending_file: str = "pending_upload.jsonl",
                 pending_max: int = 300, local_dir: str = "out", app_version: str = "edge-0.1.0"):
        self._db = None
        self._lock = threading.Lock()
        self._pending: List[dict] = []
        self._pending_file = pending_file
        self._pending_max = pending_max
        self._local_dir = local_dir
        self.app_version = app_version
        self.sent = 0
        self.failed = 0
        self.last_error = ""
        self._load_pending()
        if service_account and os.path.exists(service_account):
            self._connect(service_account)
        # Say which mode was chosen, in as many words. Without this, a wrong key path and a
        # deliberately-offline run look identical from the outside, and the operator spends the
        # test staring at a document that never appears. The path only - never the key contents.
        if not service_account:
            print("sync: no key configured -> local only, documents land in %s/" % self._local_dir)
        elif not os.path.exists(service_account):
            print("sync: key file %r not found -> local only, documents land in %s/"
                  % (service_account, self._local_dir))
        elif self._db is not None:
            print("sync: connected to Firestore using %r" % service_account)
        else:
            print("sync: key %r found but connect failed (%s) -> local only"
                  % (service_account, self.last_error))

    # ------------------------------------------------------------------
    @property
    def enabled(self) -> bool:
        return self._db is not None

    def _connect(self, service_account: str) -> None:
        try:
            import firebase_admin
            from firebase_admin import credentials, firestore
            if not firebase_admin._apps:
                cred = credentials.Certificate(service_account)
                firebase_admin.initialize_app(cred)
            self._db = firestore.client()
        except Exception as e:  # boundary with an external SDK
            self._db = None
            self.last_error = type(e).__name__

    # ------------------------------------------------------------------
    def write_session(self, student_id: str, student_name: str, s: SessionSummary,
                      band_outcomes, ended_at_wall_ms: int, device: str,
                      ineligible_reason: Optional[str], alerts_total: int,
                      csv_rows: int, avg_fps: float,
                      false_alerts: int = 0) -> None:
        payload = self.build_session_payload(
            student_id, student_name, s, band_outcomes, ended_at_wall_ms, device,
            ineligible_reason, alerts_total, false_alerts, csv_rows, avg_fps)
        self._set(f"{COLLECTION}/{document_id(student_id, s.id)}", payload, kind="session")

    def build_session_payload(self, student_id, student_name, s: SessionSummary,
                              band_outcomes, ended_at_wall_ms, device, ineligible_reason,
                              alerts_total, false_alerts, csv_rows, avg_fps) -> Dict:
        out: Dict = {
            "schema": SCHEMA,
            "student_id": student_id,
            "student_name": student_name,
            "session_id": s.id,
            "ended_at_wall_ms": ended_at_wall_ms,
            "valid_ms": s.valid_ms,
            "face_ratio": _round4(s.face_ratio),
            "valid_ratio": _round4(s.valid_ratio),
            "alerts_total": alerts_total,
            "csv_rows": csv_rows,
            "avg_fps": _round2(avg_fps),
            "device": device,
            "app_version": self.app_version,
            "source": "edge-camera",
            "eligible": ineligible_reason is None,
            # Always written, like FirebaseSync.kt:502. The column counts taps on "Canh bao sai",
            # and a session where nobody tapped it has the true count 0 - the phone writes that 0
            # too. What tells the two instruments apart is "source" and the "-cam-" in the key,
            # not a missing column.
            "false_alerts": false_alerts,
        }
        if s.quality_count > 0:
            out["quality_mean"] = _round2(s.quality)
        if ineligible_reason is not None:
            out["ineligible_reason"] = ineligible_reason
        if s.sensitivity_level >= 0:
            out["sensitivity"] = s.sensitivity_level
        for f in range(HabitFeature.COUNT):
            n = s.count[f]
            if n > 0:
                key = HabitFeature.KEYS[f]
                out[f"mean_{key}"] = _round2(s.mean[f])
                out[f"std_{key}"] = _round2(s.std[f])
                out[f"n_{key}"] = n
        if band_outcomes:
            out["band_k"] = float(HabitConfig.BAND_K)
            for b in band_outcomes:
                key = HabitFeature.KEYS[b.feature]
                out[f"band_min_{key}"] = _round2(b.min)
                out[f"band_max_{key}"] = _round2(b.max)
                out[f"band_typical_{key}"] = _round2(b.mean)
                out[f"band_frames_{key}"] = b.samples
                out[f"band_below_{key}"] = b.below
                out[f"band_above_{key}"] = b.above
        return out

    def write_band(self, student_id: str, student_name: str, profile: HabitProfile,
                   updated_at_wall_ms: int, device: str) -> None:
        base = profile.baseline()
        # (feature, band) pairs, because ToleranceBand holds only the three numbers and the
        # column names are built from the feature: a bare band list loses which row is whose.
        bands = []
        for f in HabitFeature.BAND_FEATURES:
            b = base.band(f)
            if b is None:
                continue
            bands.append((f, b))
        if not bands:
            return
        out: Dict = {
            "schema_b": SCHEMA,
            "student_id": student_id,
            "student_name": student_name,
            "sessions_used": profile.session_count(),
            "updated_at_wall_ms": updated_at_wall_ms,
            "band_k": float(HabitConfig.BAND_K),
            "device": device,
            "app_version": self.app_version,
        }
        for f, b in bands:
            key = HabitFeature.KEYS[f]
            out[f"band_typical_{key}"] = _round2(b.mean)
            out[f"band_min_{key}"] = _round2(b.min)
            out[f"band_max_{key}"] = _round2(b.max)
            # The width the band was built from, so a reader can see why two children with the
            # same typical value have different ranges. FirebaseSync.kt:428 has always sent it.
            out[f"band_spread_{key}"] = _round2(base.spread(f))
        self._set(f"{BAND_COLLECTION}/{student_doc_key(student_id)}", out, kind="band")

    def write_presence(self, student_id: str, student_name: str, s: SessionSummary,
                       started_at_wall_ms: int, beat_at_wall_ms: int, active_ms: int,
                       alerts_total: int, device: str, state: str,
                       false_alerts: int = 0) -> None:
        payload = self.build_heartbeat_payload(
            student_id, student_name, s, started_at_wall_ms, beat_at_wall_ms, active_ms,
            alerts_total, device, state, false_alerts)
        self._set(f"{PRESENCE_COLLECTION}/{student_doc_key(student_id)}", payload,
                  kind="presence", queue_on_fail=False)

    def build_heartbeat_payload(self, student_id, student_name, s: SessionSummary,
                                started_at_wall_ms, beat_at_wall_ms, active_ms,
                                alerts_total, device, state, false_alerts) -> Dict:
        """Same columns as buildHeartbeatPayload in FirebaseSync.kt:436-484, beat by beat.

        The camera writes its own row (presence/<id>-cam) rather than merging into the phone's,
        so it can use the phone's column names and a plain set() - no prefix, no half-overwritten
        document. "state" is the one column the phone has no word for, because a phone measures one
        fixed student and never has to say which situation it is looking at.
        """
        out: Dict = {
            "schema_h": SCHEMA,
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
            "source": "edge-camera",
            "state": state,
        }
        if s.quality_count > 0:
            out["quality_mean"] = _round2(s.quality)
        sei_n = s.count[HabitFeature.SEI]
        if sei_n > 0:
            out["sei_mean"] = _round2(s.mean[HabitFeature.SEI])
            out["sei_n"] = sei_n
        if s.sensitivity_level >= 0:
            out["sensitivity"] = s.sensitivity_level
        if s.band_samples > 0:
            out["band_k"] = float(HabitConfig.BAND_K)
            out["band_frames"] = s.band_samples
            out["band_below"] = s.band_below
            out["band_above"] = s.band_above
        return out

    def write_class_live(self, class_id: str, students: Dict[str, Dict],
                         alerts: List[Dict], updated_at_wall_ms: int) -> None:
        payload = {
            "schema_l": SCHEMA,
            "class_id": class_id,
            "updated_at_wall_ms": updated_at_wall_ms,
            "students": students,
            "alerts": alerts[-50:],  # keep the tail, not the whole history
        }
        self._set(f"{CLASS_LIVE_COLLECTION}/{sanitize(class_id)}", payload, kind="class_live",
                  queue_on_fail=False)

    # ------------------------------------------------------------------
    def _set(self, doc_path: str, payload: Dict, kind: str, queue_on_fail: bool = True) -> None:
        with self._lock:
            if self._db is None:
                self._dump_local(doc_path, payload)
                return
        try:
            self._db.document(doc_path).set(payload)
            self.sent += 1
        except Exception as e:  # boundary with an external SDK
            self.failed += 1
            self.last_error = type(e).__name__
            if queue_on_fail:
                self._enqueue(doc_path, payload)

    def _enqueue(self, doc_path: str, payload: Dict) -> None:
        rec = {"doc": doc_path, "payload": payload}
        with self._lock:
            # replace an older queued payload for the SAME document
            self._pending = [p for p in self._pending if p["doc"] != doc_path]
            self._pending.append(rec)
            dropped = 0
            while len(self._pending) > self._pending_max:
                self._pending.pop(0)
                dropped += 1
            self._write_queue_locked()

    def _load_pending(self) -> None:
        if not os.path.exists(self._pending_file):
            return
        try:
            with open(self._pending_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line:
                        self._pending.append(json.loads(line))
        except Exception:
            self._pending = []

    def _write_queue_locked(self) -> None:
        tmp = self._pending_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            for rec in self._pending:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        os.replace(tmp, self._pending_file)

    def resend_pending(self) -> "tuple[int, int]":
        if self._db is None:
            return 0, len(self._pending)
        accepted = refused = 0
        with self._lock:
            batch = list(self._pending)
        for rec in batch:
            try:
                self._db.document(rec["doc"]).set(rec["payload"])
                accepted += 1
                with self._lock:
                    self._pending = [p for p in self._pending if p is not rec]
                    self._write_queue_locked()
            except Exception as e:
                refused += 1
                self.last_error = type(e).__name__
        return accepted, refused

    def _dump_local(self, doc_path: str, payload: Dict) -> None:
        os.makedirs(self._local_dir, exist_ok=True)
        safe = doc_path.replace("/", "__")
        path = os.path.join(self._local_dir, safe + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)


def _round2(v: float) -> float:
    return _round_to(v, 100.0)


def _round4(v: float) -> float:
    return _round_to(v, 10000.0)


def _round_to(v: float, scale: float) -> float:
    """FirebaseSync.kt:944 - (v * scale).roundToInt() / scale, and 0.0 when not finite.

    Written out instead of using round() because Python's round() is banker's rounding: it sends
    0.125 down to 0.12 where Kotlin sends it up to 0.13. Two instruments that measure the same
    child must not disagree in the last digit by accident.
    """
    try:
        if not math.isfinite(v):
            return 0.0
    except TypeError:
        return 0.0
    return math.floor(v * scale + 0.5) / scale
