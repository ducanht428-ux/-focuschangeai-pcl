"""Tests that pin the camera leg's cloud record to the phone's cloud record.

Pure logic - no firebase_admin, no network, no camera. Run from the edge-server folder:

    python tests/test_firestore_parity.py

FirebaseSync.kt is the reference: it is what a teacher's screen is built to read. The camera
leg (focusedge/firestore_writer.py) must send the same columns with the same conditions, so one
query answers both instruments. This file reads the Kotlin source, pulls the column names out of
its three payload builders, and compares them against the payloads the Python builder makes for
the same session. Two differences are allowed and named here: "source", and "state" in the
heartbeat. Everything else that drifts fails.

What this does NOT check: that the two machines measure the same child identically. That is
test_parity_thresholds.py. Here the numbers are invented inputs; only the SHAPE of the record is
under test.
"""
import contextlib
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge import constants as K  # noqa: E402
from focusedge.constants import HabitConfig, HabitFeature  # noqa: E402
from focusedge.firestore_writer import (  # noqa: E402
    FirestoreWriter, sanitize, student_doc_key, document_id)
from focusedge.habit import BandOutcome, HabitBaseline, SessionSummary  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP = os.path.join(ROOT, "app", "src", "main", "java", "com", "focuschange", "ai")


def _read(name):
    path = os.path.join(APP, name)
    if not os.path.exists(path):
        raise AssertionError("the phone's source is missing: %s" % path)
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


SYNC = _read("FirebaseSync.kt")
HABIT = _read("HabitModel.kt")


def _fn(src, start, stop):
    i = src.index(start)
    j = src.index(stop, i)
    return src[i:j]


def _columns(body, keys=None):
    """Every out["..."] the Kotlin builder writes, with $key expanded.

    keys defaults to all 12 features; the band record is passed the 8 that carry a band, so the
    comparison is about names, not about how many rows each leg happened to fill.
    """
    keys = keys or _feature_keys()
    out = set()
    for raw in re.findall(r'out\["([^"]+)"\]', body):
        if "$key" in raw:
            for f in keys:
                out.add(raw.replace("$key", f))
        else:
            out.add(raw)
    return out


def _band_keys():
    return [HabitFeature.KEYS[f] for f in HabitFeature.BAND_FEATURES]


def _feature_keys():
    m = re.search(r'val KEYS = arrayOf\(([^)]*)\)', HABIT, re.S)
    assert m, "HabitModel.kt no longer declares the feature keys as an arrayOf"
    return re.findall(r'"([^"]+)"', m.group(1))


def _writer():
    # No key path -> the constructor prints "local only" and never opens a connection. The queue
    # file name is a test-only one so a real pending_upload.jsonl in this folder is left alone.
    # The startup line is swallowed: 20 copies of it would bury the test report.
    with contextlib.redirect_stdout(io.StringIO()):
        return FirestoreWriter(service_account="", pending_file="pending_upload_parity_test.jsonl",
                               local_dir="out_parity_test")


def _summary(**over):
    n = HabitFeature.COUNT
    args = dict(id=1730000000000, valid_ms=120000, face_ratio=0.9876, valid_ratio=0.5432,
                quality=88.0, mean=[1.0] * n, std=[0.5] * n, count=[10] * n,
                quality_count=10, band_in=7, band_below=2, band_above=1, sensitivity_level=2)
    args.update(over)
    return SessionSummary(**args)


def _band_outcomes():
    # All 12 features, so a typo in any column name is caught even though a real session only
    # scores the 8 that have a band.
    return [BandOutcome(feature=f, mean=50.0, min=40.0, max=60.0, samples=100, below=3, above=4)
            for f in range(HabitFeature.COUNT)]


class _Profile:
    """Duck-typed HabitProfile: the writer only asks for baseline() and session_count()."""

    def __init__(self):
        n = HabitFeature.COUNT
        self._base = HabitBaseline([50.0] * n, [4.0] * n,
                                   [f in HabitFeature.BAND_FEATURES for f in range(n)])

    def baseline(self):
        return self._base

    def session_count(self):
        return 5


def _session_payload(reason=None):
    return _writer().build_session_payload(
        "sv-01", "Nguyen Van A", _summary(), _band_outcomes(), 1730000120000, "C270", reason,
        alerts_total=3, false_alerts=0, csv_rows=10, avg_fps=29.7)


def _phone_session_columns():
    return _columns(_fn(SYNC, 'private fun buildPayload(', '\n    }\n'))


# ---------------------------------------------------------------------------
# 1. The session record
# ---------------------------------------------------------------------------
def test_phone_and_camera_use_the_same_feature_key_list():
    assert _feature_keys() == list(HabitFeature.KEYS), \
        "the two KEY lists drifted: %s vs %s" % (_feature_keys(), HabitFeature.KEYS)


def test_every_column_the_phone_sends_for_a_session_is_sent_by_the_camera():
    # The reason is set on purpose: the phone writes ineligible_reason only for a session that
    # has one, so this is the case where the full column set is reachable on both legs.
    missing = sorted(_phone_session_columns() - set(_session_payload("below the 2 minute floor")))
    assert not missing, "the camera record has no %s" % missing


def test_the_camera_session_record_adds_only_the_source_tag():
    extra = sorted(set(_session_payload()) - _phone_session_columns())
    assert extra == ["source"], "unexpected columns on the camera record: %s" % extra


def test_an_ineligible_session_keeps_the_same_columns_as_an_eligible_one():
    ok = _session_payload()
    bad = _session_payload("below the 2 minute floor")
    assert sorted(set(bad) - set(ok)) == ["ineligible_reason"]
    assert "ineligible_reason" in _phone_session_columns()
    assert ok["eligible"] is True and bad["eligible"] is False


def test_a_session_that_scored_no_band_drops_only_the_band_columns():
    edge = set(_writer().build_session_payload(
        "sv-01", "A", _summary(), [], 1, "C270", "below the 2 minute floor",
        alerts_total=0, false_alerts=0, csv_rows=0, avg_fps=0.0).keys())
    phone = _phone_session_columns()
    assert not (phone - edge - set(k for k in phone if k.startswith("band_")))
    assert not any(k.startswith("band_") for k in edge)


def test_the_phone_and_the_camera_learn_a_band_from_the_same_feature_list():
    # Both legs loop the same 8 features when they fill bands/<id>, so the two records can be
    # compared column by column rather than by a set that differs by design.
    assert "for (f in HabitFeature.BAND_FEATURES)" in _fn(
        _read("MainActivity.kt"), 'for (f in HabitFeature.BAND_FEATURES)',
        'list.add(FeatureBand(')
    assert "for f in HabitFeature.BAND_FEATURES" in _fn(
        _writer_source(), "def write_band(", "def write_presence(")


def test_false_alerts_is_written_even_when_it_is_zero():
    # FirebaseSync.kt writes this column unconditionally; a camera session nobody could mark
    # wrong has the true count 0, and 0 must appear rather than a hole in the record.
    assert _session_payload()["false_alerts"] == 0
    assert '"false_alerts": false_alerts' in _writer_source()


# ---------------------------------------------------------------------------
# 2. The band record
# ---------------------------------------------------------------------------
def test_every_column_the_phone_sends_for_a_band_is_sent_by_the_camera():
    phone = _columns(_fn(SYNC, 'private fun buildBandPayload(', '\n    }\n'), keys=_band_keys())
    missing = sorted(phone - set(_band_payload().keys()))
    assert not missing, "the camera band record has no %s" % missing


def test_the_camera_band_record_adds_nothing():
    phone = _columns(_fn(SYNC, 'private fun buildBandPayload(', '\n    }\n'), keys=_band_keys())
    extra = sorted(set(_band_payload().keys()) - phone)
    assert extra == [], "the band record grew columns the phone does not send: %s" % extra


def test_the_band_record_carries_the_spread_it_was_built_from():
    edge = _band_payload()
    for f in HabitFeature.BAND_FEATURES:
        key = HabitFeature.KEYS[f]
        assert edge["band_spread_%s" % key] == 4.0


# ---------------------------------------------------------------------------
# 3. The presence heartbeat
# ---------------------------------------------------------------------------
def test_every_column_the_phone_sends_for_a_beat_is_sent_by_the_camera():
    phone = _columns(_fn(SYNC, 'private fun buildHeartbeatPayload(', '\n    }\n'))
    edge = set(_writer().build_heartbeat_payload(
        "sv-01", "A", _summary(), 1730000000000, 1730000060000, 60000, 3, "C270",
        "learning", 0).keys())
    missing = sorted(phone - edge)
    assert not missing, "the camera beat has no %s" % missing


def test_the_camera_beat_adds_only_the_source_tag_and_the_situation():
    phone = _columns(_fn(SYNC, 'private fun buildHeartbeatPayload(', '\n    }\n'))
    edge = set(_writer().build_heartbeat_payload(
        "sv-01", "A", _summary(), 1730000000000, 1730000060000, 60000, 3, "C270",
        "learning", 0).keys())
    assert sorted(edge - phone) == ["source", "state"]


def test_a_beat_that_measured_nothing_omits_the_same_columns_the_phone_omits():
    empty = _summary(quality_count=0, count=[0] * HabitFeature.COUNT, sensitivity_level=-1,
                     band_in=0, band_below=0, band_above=0)
    edge = _writer().build_heartbeat_payload(
        "sv-01", "A", empty, 1, 2, 0, 0, "C270", "unknown", 0)
    for gone in ("quality_mean", "sei_mean", "sei_n", "sensitivity",
                 "band_k", "band_frames", "band_below", "band_above"):
        assert gone not in edge, "%s was written for a beat that measured nothing" % gone
    # The phone drops the same four by guarding the same three conditions.
    body = _fn(SYNC, 'private fun buildHeartbeatPayload(', '\n    }\n')
    assert "if (s.qualityCount > 0)" in body and "if (s.sensitivityLevel >= 0)" in body
    assert "if (seiN > 0)" in body and "if (r.bandFrames > 0)" in body


def test_a_beat_is_never_queued_for_resend_on_either_side():
    # A beat means "measured now"; replaying one an hour later tells the teacher the opposite.
    phone = _fn(SYNC, 'fun uploadHeartbeat(', 'fun uploadBand(')
    assert "enqueue(" not in phone
    src = _writer_source()
    assert "queue_on_fail=False" in _fn(src, "def write_presence(", "def write_class_live(")


def test_the_session_id_is_a_start_timestamp_on_both_legs():
    # FirebaseSync.kt:50 says sessionId and startedAtWallMs are the same value by construction,
    # because the phone opens a session with HabitSession(System.currentTimeMillis()). A counter
    # that restarts at 1 on every run reuses document names, so set() would replace the previous
    # run's record for the same child - silently, with nothing on screen saying a week got shorter.
    assert "HabitSession(System.currentTimeMillis())" in _read("MainActivity.kt")
    src = _classroom_source()
    assert "rt.pipeline.start_session(wall_ms)" in src
    assert "_session_counter" not in src, "the counter that reused session ids is back"


def test_a_beat_reports_the_real_start_of_its_session():
    # started_at_wall_ms must be the moment the session opened, not now minus the measured time:
    # the two differ whenever frames were skipped, and the phone writes the true start.
    src = _classroom_source()
    beat = _fn(src, "def _write_heartbeats(", "def shutdown(")
    assert "started_at_wall_ms=rt.session_started_wall_ms" in beat
    assert "wall_ms - active_ms" not in src
    assert "rt.session_started_wall_ms = wall_ms" in src


def test_a_beat_built_from_a_real_accumulator_has_the_phone_columns():
    # _write_heartbeats calls session.to_summary() mid-session, so the object under the payload is
    # a live HabitSession, not a fixture. One round trip here proves the property names the writer
    # reads (band_samples, quality_count, sensitivity_level) really exist on it.
    from focusedge.habit import HabitSession
    hs = HabitSession(1730000000000)
    hs.sensitivity_level = 1
    hs.tick(1730000000000, face_visible=True, valid=True)
    hs.tick(1730000060000, face_visible=True, valid=True)
    base = _Profile().baseline()
    hs.set_bands(base, list(HabitFeature.BAND_FEATURES))
    for _ in range(5):
        hs.observe(HabitFeature.SEI, 62.0)
        hs.observe_band(HabitFeature.SEI, 62.0)
        hs.add_quality(90.0)
    edge = _writer().build_heartbeat_payload(
        "sv-01", "A", hs.to_summary(), hs.id, 1730000060000, hs.active_ms, 1, "C270",
        "learning", 0)
    assert edge["session_id"] == hs.id and edge["started_at_wall_ms"] == hs.id
    assert edge["sei_mean"] == 62.0 and edge["sei_n"] == 5
    assert edge["quality_mean"] == 90.0 and edge["sensitivity"] == 1
    assert edge["band_frames"] == 5 and edge["band_k"] == float(HabitConfig.BAND_K)
    assert edge["active_ms"] == 60000
    phone = _columns(_fn(SYNC, 'private fun buildHeartbeatPayload(', '\n    }\n'))
    assert not (phone - set(edge))


# ---------------------------------------------------------------------------
# 4. Numbers, names and keys
# ---------------------------------------------------------------------------
def test_band_k_comes_from_the_shared_constant_not_from_a_literal():
    assert float(K.HabitConfig.BAND_K) == float(
        re.search(r'const val BAND_K = ([\d.]+)f', HABIT).group(1))
    src = _writer_source()
    assert 'band_k"] = 2.5' not in src, "band_k is a typed-in literal again"
    w = _writer()
    payloads = [_band_payload(),
                w.build_session_payload(
                    "sv", "A", _summary(), _band_outcomes(), 1, "C270", None,
                    alerts_total=0, false_alerts=0, csv_rows=0, avg_fps=0.0),
                w.build_heartbeat_payload(
                    "sv", "A", _summary(), 1, 2, 3, 0, "C270", "learning", 0)]
    for p in payloads:
        assert p["band_k"] == float(HabitConfig.BAND_K), p["band_k"]


def test_rounding_is_kotlin_half_up_not_python_bankers():
    # 0.125 and 0.625 are exactly representable, so they isolate the tie rule: Python's round()
    # sends both to the even neighbour (0.12, 0.62), Kotlin's roundToInt sends both up (0.13, 0.63).
    from focusedge.firestore_writer import _round2
    assert round(0.125, 2) == 0.12, "python no longer uses bankers rounding"
    for v, want in ((0.125, 0.13), (0.625, 0.63), (-0.125, -0.12), (0.0, 0.0), (1.5, 1.5)):
        assert _round2(v) == want, "round2(%s) = %s, Kotlin gives %s" % (v, _round2(v), want)


def test_a_number_that_is_not_a_number_rounds_to_zero_like_the_phone():
    from focusedge.firestore_writer import _round2, _round4
    for bad in (float("nan"), float("inf"), float("-inf"), None):
        assert _round2(bad) == 0.0 and _round4(bad) == 0.0


def test_the_document_keys_can_never_overwrite_a_phone_document():
    # This is the one place the record is deliberately NOT identical: same collection, same
    # columns, different row. set() replaces a whole document, so an un-namespaced camera key
    # would erase the phone's week of sessions that the habit profile is built from.
    for sid in ("sv-01", "sv 01", "a.b/c"):
        phone_session = sanitize(sid) + "-" + str(1730000000000)
        assert phone_session != document_id(sid, 1730000000000)
        assert document_id(sid, 1).startswith(sanitize(sid) + "-cam-")
        assert student_doc_key(sid) == sanitize(sid) + "-cam"
        assert student_doc_key(sid) != sanitize(sid)


def test_sanitize_matches_the_kotlin_rule():
    # Kotlin keeps a letter, a digit, '-' and '_'; everything else becomes '-'. Letters of any
    # script count, so a Vietnamese name keeps its diacritics on both legs - if only one side
    # stripped them, the same child would be two documents and the teacher would see one.
    kotlin = _fn(SYNC, 'private fun sanitize(', '\n    }\n')
    assert "isLetterOrDigit() || ch == '-' || ch == '_'" in kotlin
    assert sanitize("Tran Van 11A2") == "Tran-Van-11A2"
    assert sanitize("a.b*c") == "a-b-c"
    assert sanitize("SV/2026#1") == "SV-2026-1"
    assert sanitize("nguyen_dang") == "nguyen_dang"
    assert sanitize("hs-01") == "hs-01"
    assert sanitize("Le Anh") == "Le-Anh"


def test_schema_number_is_the_same_on_both_legs():
    from focusedge import firestore_writer as fw
    assert int(re.search(r'private const val SCHEMA = (\d+)', SYNC).group(1)) == fw.SCHEMA


# ---------------------------------------------------------------------------
# write_band has no standalone builder, so the payload is read out of the write instead.
# ---------------------------------------------------------------------------
def _band_payload():
    """The payload write_band would send, without touching a network or a disk."""
    w = _writer()
    seen = {}

    def fake_set(doc_path, payload, kind, queue_on_fail=True):
        seen["doc"] = doc_path
        seen["payload"] = payload

    w._set = fake_set
    w.write_band("sv-01", "A", _Profile(), 1730000000000, "C270")
    assert seen.get("doc") == "bands/sv-01-cam", seen.get("doc")
    return seen["payload"]


def _writer_source():
    return _read_py("firestore_writer.py")


def _classroom_source():
    return _read_py("classroom.py")


def _read_py(name):
    with open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "focusedge", name), encoding="utf-8") as f:
        return f.read()


def main():
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
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
