"""Offline teacher commands (guide item 5) and the attention rules they drive.

Pure logic plus one bounded real-engine probe. Run from the edge-server folder:

    python tests/test_offline_voice.py

The cloud path is gone: recognize_google was an API call, not a downloadable library, so the
listener now decodes with Vosk on this machine. The falsifications that matter: no source line
may call Google any more, a noise transcript must not switch the context, and before the teacher
speaks a child looking at a book must NOT be judged against a board expectation.
"""
import os
import sys
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import focusedge.teacher_voice as tv_mod
from focusedge.teacher_voice import TeacherContextAI, StudentAttentionEvaluator, get_teacher_voice
from focusedge.classroom import ClassroomPipeline, TrackRuntime
from focusedge.constants import HabitFeature
from focusedge.person_pipeline import PersonPipeline
from focusedge.roster import Roster

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class _Guest:
    bound_student_id = None
    confidence = "unbound"


def _fresh_voice():
    tv_mod._GLOBAL_TEACHER_VOICE = None
    return get_teacher_voice()


def _pipeline():
    cfg = {"class_id": "lop12a", "class_label": "Lop 12A",
           "person": {"sensitivity": 1, "habit_learn": True},
           "input": {"source": "test-cam"},
           "storage": {"heartbeat_ms": 300000, "class_live_ms": 20000}}
    return ClassroomPipeline(cfg, None, None, Roster(), None, None, None)


def _track(cp, track_id):
    rt = TrackRuntime(track_id=track_id, identity=_Guest(),
                      pipeline=PersonPipeline(track_id, None, 1, 12.0))
    cp._tracks[track_id] = rt
    return rt


def _res(yaw, pitch, mar=0.05, measurable=True):
    return SimpleNamespace(measurable=measurable, yaw=yaw, pitch=pitch,
                           features={HabitFeature.MAR: mar})


# ------------------------------------------------------------------ the cloud is really gone
def test_no_google_call_is_left_in_the_listener():
    src = open(os.path.join(ROOT, "focusedge", "teacher_voice.py"), encoding="utf-8").read()
    # The docstring may NAME the old cloud path to explain why it is gone; a CALL may not survive.
    assert ".recognize_google(" not in src, "the cloud recognizer must not survive as a fallback"
    assert "vosk" in src.lower(), "the offline engine must be the one wired in"


# ------------------------------------------------------------------ the teacher's own commands
def test_the_three_commands_the_teacher_asked_for():
    ai = TeacherContextAI()
    assert ai.detect_context("hoạt động nhóm") == "GROUP_WORK"
    assert ai.detect_context("bắt đầu vào bài học") == "LECTURE"
    assert ai.detect_context("các em ghi bài") == "STUDY_NOTE"


def test_a_spoken_command_claims_the_voice_source():
    ai = _fresh_voice()
    assert ai.note_utterance("các em ghi bài vào vở") == "STUDY_NOTE"
    st = ai.get_status()
    assert st["context_source"] == "voice"
    assert st["last_utterance"] == "các em ghi bài vào vở"
    assert "engine" in st


def test_noise_does_not_switch_the_context():
    ai = TeacherContextAI()
    # 'sau' is what the real vi model produced from pure noise in the latency probe.
    assert ai.detect_context("sau") is None
    assert ai.note_utterance("sau") is None
    assert ai.get_status()["context_source"] == "init"


# ------------------------------------------------------------------ the 10-minute rule
def test_looking_away_ten_minutes_is_the_teachers_off_task_bar():
    ev = StudentAttentionEvaluator()          # default must BE the teacher's rule
    kw = dict(yaw=45, pitch=0, mar=0.05, is_slouching=False,
              context=TeacherContextAI.CONTEXT_LECTURE)
    assert "Dang theo doi" in ev.evaluate(now=0.0, **kw)[0]
    assert "Dang theo doi" in ev.evaluate(now=599.0, **kw)[0]
    label, reason, _ = ev.evaluate(now=600.0, **kw)
    assert "MAT TAP TRUNG" in label
    assert reason == "Không nhìn lên bảng"


def test_writing_with_head_down_is_attentive_during_study_note():
    ev = StudentAttentionEvaluator()
    label, reason, _ = ev.evaluate(yaw=5, pitch=25, mar=0.05, is_slouching=False,
                                   context=TeacherContextAI.CONTEXT_STUDY_NOTE, now=0.0)
    assert label == "TAP TRUNG"
    assert reason == "Đang ghi/học bài"


# ------------------------------------------------------------------ pipeline wiring
def test_before_any_command_a_book_reader_is_not_judged_against_the_board():
    _fresh_voice()
    cp = _pipeline()
    rt = _track(cp, 3)
    cp._evaluate_attention(rt, _res(yaw=0, pitch=25), 100_000)
    assert rt.attention_label == "TAP TRUNG"
    assert rt.attention_reason == "Bình thường"


def test_a_manual_context_reaches_the_per_track_verdict():
    _fresh_voice().set_context(TeacherContextAI.CONTEXT_STUDY_NOTE)
    cp = _pipeline()
    rt = _track(cp, 3)
    cp._evaluate_attention(rt, _res(yaw=5, pitch=25), 100_000)
    assert rt.attention_label == "TAP TRUNG"
    assert rt.attention_reason == "Đang ghi/học bài"
    views = {v.track_id: v for v in cp.people()}
    assert views[3].attention_label == "TAP TRUNG"


def test_an_unmeasurable_or_poseless_frame_claims_nothing():
    _fresh_voice().set_context(TeacherContextAI.CONTEXT_LECTURE)
    cp = _pipeline()
    rt = _track(cp, 3)
    cp._evaluate_attention(rt, _res(yaw=0, pitch=0, measurable=False), 100_000)
    assert rt.attention_label == ""
    cp._evaluate_attention(rt, _res(yaw=None, pitch=None), 100_000)
    assert rt.attention_label == ""


def test_the_off_task_window_is_configurable():
    _fresh_voice().set_context(TeacherContextAI.CONTEXT_LECTURE)
    cfg = {"class_id": "lop12a", "teacher_voice": {"off_task_seconds": 7},
           "person": {}, "input": {}, "storage": {}}
    cp = ClassroomPipeline(cfg, None, None, Roster(), None, None, None)
    rt = _track(cp, 3)
    cp._evaluate_attention(rt, _res(yaw=45, pitch=0), 100_000)
    assert "Dang theo doi" in rt.attention_label
    cp._evaluate_attention(rt, _res(yaw=45, pitch=0), 107_000)
    assert "MAT TAP TRUNG" in rt.attention_label


# ------------------------------------------------------------------ the real offline engine
def test_log_lines_survive_a_cp1252_console():
    # Measured live: the worker thread died on print('Đã kết nối...') because the Windows
    # console is cp1252, while the dashboard kept claiming "đang nghe". The log must degrade,
    # never the thread.
    import io
    from focusedge.teacher_voice import _say
    buf = io.TextIOWrapper(io.BytesIO(), encoding="cp1252")
    old = sys.stdout
    sys.stdout = buf
    try:
        _say("[TeacherContextAI] Đã kết nối micro giáo viên, nghe khẩu lệnh OFFLINE bằng Vosk (vi).")
        _say("[GV NÓI]: 'các em ghi bài' ===> CHUYỂN NGỮ CẢNH: STUDY_NOTE")
    finally:
        sys.stdout = old


def test_a_dead_worker_is_not_reported_as_listening():
    import threading
    ai = TeacherContextAI()
    ai.running = True
    ai.thread = threading.Thread(target=lambda: None)   # created, never started = dead
    assert ai.get_status()["is_running"] is False
    ai.running = False
    assert ai.get_status()["is_running"] is False


def test_the_vosk_engine_decodes_without_a_network():
    try:
        from vosk import Model, KaldiRecognizer, SetLogLevel
    except ImportError:
        print("skip: vosk not installed (pip install vosk)")
        return
    path = os.path.join(ROOT, "models", "vosk-model-small-vn-0.4")
    if not os.path.isdir(path):
        print("skip: vi model not downloaded (see config.yaml teacher_voice)")
        return
    import json
    import numpy as np
    SetLogLevel(-1)
    rec = KaldiRecognizer(Model(path), 16000)
    silence = np.zeros(16000, dtype=np.int16).tobytes()
    for i in range(0, len(silence), 8000):
        rec.AcceptWaveform(silence[i:i + 8000])
    text = json.loads(rec.FinalResult()).get("text", "")
    assert isinstance(text, str)
    ai = TeacherContextAI()
    assert ai.note_utterance(text) is None, "silence must not switch the context"


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