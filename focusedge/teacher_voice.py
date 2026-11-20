"""Teacher Voice Context Listener & Multimodal Attention Evaluator.
Recognizes teacher verbal commands (LECTURE, STUDY_NOTE, GROUP_WORK) OFFLINE with Vosk
(vosk-model-small-vn-0.4, Apache 2.0) and evaluates head pose / mouth movement against them.
No audio ever leaves this machine: the old recognize_google path was a cloud API call, not a
downloadable library, so it was removed rather than kept as a fallback.
"""
from __future__ import annotations

import json
import math
import os
import threading
import time
from typing import Dict, List, Optional, Tuple

try:
    import speech_recognition as sr
    HAS_SR = True
except ImportError:
    sr = None
    HAS_SR = False


def _say(msg: str) -> None:
    # The Windows console code page (cp1252) cannot encode Vietnamese diacritics, and a raise
    # here kills the listener thread while the dashboard still claims it is running - measured
    # live on the project laptop. Degrade the log line, never the thread.
    try:
        print(msg)
    except UnicodeEncodeError:
        print(msg.encode("ascii", "replace").decode("ascii"))


class TeacherContextAI:
    """Listens in a background daemon thread to microphone input,
    detecting teacher keywords and switching the classroom activity context.
    Also supports offline fallback, auto-reset timeout, and manual overrides.
    """

    CONTEXT_LECTURE = "LECTURE"         # Nghe giảng (nhìn bảng)
    CONTEXT_STUDY_NOTE = "STUDY_NOTE"   # Ghi bài / làm bài tập (cúi đầu)
    CONTEXT_GROUP_WORK = "GROUP_WORK"   # Thảo luận nhóm (quay mặt + mấp máy môi)

    def __init__(self, auto_reset_seconds: int = 180, language: str = "vi-VN",
                 model_path: str = "models/vosk-model-small-vn-0.4"):
        self.context = self.CONTEXT_LECTURE
        self.last_speech = "Hệ thống sẵn sàng"
        self.last_switch_time = time.time()
        self.auto_reset_seconds = auto_reset_seconds  # Tự quay về LECTURE sau 3-5 phút nếu GV im lặng
        self.running = False
        self.language = language
        self.model_path = model_path
        # Which recognizer is actually behind the listen button: "init" before start, then
        # "vosk-vi-offline" when the model loaded, or "manual" when speech is unavailable.
        # The dashboard prints this so a silent mic is never mistaken for a listening one.
        self.engine = "init"
        self.thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
        # Where the current context came from: "init" (nobody spoke or clicked yet), "voice",
        # "manual" (dashboard API / shortcut) or "auto" (silence timeout). The classroom label
        # may only claim teacher speech when a real utterance produced the switch.
        self.context_source = "init"
        self.last_utterance: Optional[str] = None

        # Từ điển khẩu lệnh quy chuẩn
        self.rules: Dict[str, List[str]] = {
            self.CONTEXT_GROUP_WORK: [
                "bắt đầu thảo luận",  # Câu chỉ định
                "thảo luận",
                "hoạt động nhóm",
                "nhóm đôi",
                "trao đổi với bạn",
                "quay sang bạn",
                "làm việc nhóm",
            ],
            self.CONTEXT_LECTURE: [
                "bắt đầu vào bài học",  # Câu chỉ định
                "bắt đầu bài học",
                "vào bài mới",
                "cả lớp chú ý",       # Câu chỉ định
                "hết giờ thảo luận",  # Câu chỉ định
                "nhìn lên bảng",
                "nghe giảng",
                "cô giảng",
                "thầy giảng",
                "hết giờ",
                "quan sát",
                "chú ý",
            ],
            self.CONTEXT_STUDY_NOTE: [
                "ghi bài",
                "chép bài",
                "viết vào vở",
                "làm bài tập",
                "mở sách",
                "ghi chép",
                "chép định nghĩa",
            ],
        }

    def detect_context(self, text: str) -> Optional[str]:
        """Classify Vietnamese text into a context using keyword matching.
        Sorts keywords by length descending so specific commands like
        'hết giờ thảo luận' or 'bắt đầu thảo luận' match before generic 'thảo luận'.
        """
        if not text:
            return None
        text_lower = text.lower().strip()

        # Thu thập toàn bộ (keyword, context) và sắp xếp theo độ dài giảm dần
        all_rules = []
        for ctx, keywords in self.rules.items():
            for kw in keywords:
                all_rules.append((kw, ctx))
        all_rules.sort(key=lambda item: len(item[0]), reverse=True)

        for kw, ctx in all_rules:
            if kw in text_lower:
                return ctx
        return None

    def note_utterance(self, text: str) -> Optional[str]:
        # The single entry point for recognised speech, so the worker and the tests drive the
        # same path: remember the sentence, switch context only when a keyword matched.
        if not text:
            return None
        detected = self.detect_context(text)
        with self._lock:
            self.last_utterance = text
            self.last_speech = text
            if detected:
                self.context = detected
                self.context_source = "voice"
                self.last_switch_time = time.time()
        return detected

    def set_context(self, new_ctx: str, reason: str = "manual"):
        """Directly set context (e.g. from UI shortcut or keyboard 1, 2, 3)."""
        if new_ctx in (self.CONTEXT_LECTURE, self.CONTEXT_STUDY_NOTE, self.CONTEXT_GROUP_WORK):
            with self._lock:
                self.context = new_ctx
                self.context_source = "manual"
                self.last_switch_time = time.time()
                self.last_speech = f"[{reason}] -> {new_ctx}"

    def get_status(self) -> Dict[str, any]:
        """Return snapshot of current state."""
        with self._lock:
            # Kiểm tra auto-reset timeout
            now = time.time()
            if self.context != self.CONTEXT_LECTURE and (now - self.last_switch_time > self.auto_reset_seconds):
                self.context = self.CONTEXT_LECTURE
                self.context_source = "auto"
                self.last_speech = "Tự động quay lại Nghe giảng (Hết thời gian hoạt động)"
                self.last_switch_time = now

            return {
                "context": self.context,
                "context_source": self.context_source,
                "last_utterance": self.last_utterance,
                "last_speech": self.last_speech,
                "elapsed_s": round(now - self.last_switch_time, 1),
                # A dead worker must not read as "listening": the flag follows the thread,
                # not the intention recorded by start().
                "is_running": bool(self.running and (self.thread is None or self.thread.is_alive())),
                "engine": self.engine,
            }

    def _listen_worker(self):
        if not HAS_SR:
            _say("[TeacherContextAI] SpeechRecognition không có sẵn, hoạt động ở chế độ phím bấm thủ công.")
            self.engine = "manual"
            return

        try:
            from vosk import Model, KaldiRecognizer, SetLogLevel
        except ImportError:
            _say("[TeacherContextAI] Chưa cài vosk (pip install vosk) - chỉ dùng phím/API chọn tay.")
            self.engine = "manual"
            return

        from .classroom import resolve_asset
        path = resolve_asset(self.model_path)
        if not os.path.isdir(path):
            _say("[TeacherContextAI] Không thấy model tiếng Việt tại '%s'. Tải vosk-model-small-vn-0.4.zip "
                  "(32 MB, Apache 2.0) từ alphacephei.com/vosk/models rồi giải nén vào models/. "
                  "Chưa có model thì chỉ dùng chọn tay." % path)
            self.engine = "manual"
            return

        SetLogLevel(-1)
        try:
            model = Model(path)
        except Exception as e:
            _say(f"[TeacherContextAI] Không nạp được model Vosk: {e}. Dùng chọn tay.")
            self.engine = "manual"
            return
        kaldi = KaldiRecognizer(model, 16000)

        recognizer = sr.Recognizer()
        recognizer.energy_threshold = 300
        recognizer.dynamic_energy_threshold = True

        try:
            mic = sr.Microphone(sample_rate=16000)
        except Exception as e:
            _say(f"[TeacherContextAI] Không khởi tạo được micro: {e}. Dùng phím tắt thủ công.")
            self.engine = "manual"
            return

        self.engine = "vosk-vi-offline"
        _say("[TeacherContextAI] Đã kết nối micro giáo viên, nghe khẩu lệnh OFFLINE bằng Vosk (vi).")

        try:
            with mic as source:
                recognizer.adjust_for_ambient_noise(source, duration=1)
                while self.running:
                    # Kiểm tra auto-reset về LECTURE
                    now = time.time()
                    with self._lock:
                        if self.context != self.CONTEXT_LECTURE and (now - self.last_switch_time > self.auto_reset_seconds):
                            self.context = self.CONTEXT_LECTURE
                            self.context_source = "auto"
                            self.last_speech = "Hết thời gian -> Về Nghe giảng"
                            self.last_switch_time = now
                            _say("[TeacherContextAI] Hết thời gian hoạt động -> Tự động về LECTURE")

                    try:
                        audio = recognizer.listen(source, timeout=2, phrase_time_limit=5)
                    except sr.WaitTimeoutError:
                        continue
                    except Exception:
                        # Bắt mọi lỗi ngoại lệ để không bao giờ dừng luồng chính
                        time.sleep(0.5)
                        continue

                    # Offline decode, no network call anywhere in this path. Measured on the
                    # project laptop (i5-5200U): model load 0.95 s, decode RTF ~1.18, so a 5 s
                    # phrase costs ~6 s of CPU and speech spoken DURING decode is missed - the
                    # teacher repeats the command, the mic loop is not a dictation transcript.
                    raw = audio.get_raw_data(convert_rate=16000, convert_width=2)
                    texts = []
                    for i in range(0, len(raw), 8000):
                        if kaldi.AcceptWaveform(raw[i:i + 8000]):
                            t = json.loads(kaldi.Result()).get("text", "")
                            if t:
                                texts.append(t)
                    t = json.loads(kaldi.FinalResult()).get("text", "")
                    if t:
                        texts.append(t)
                    for t in texts:
                        detected = self.note_utterance(t)
                        if detected:
                            _say(f"[GV NÓI]: '{t}' ===> CHUYỂN NGỮ CẢNH: {detected}")

        except Exception as e:
            _say(f"[TeacherContextAI] Ngoại lệ ngoài luồng micro: {e}")
            self.engine = "manual"

    def start(self):
        # A worker that exited (no mic, no model, crashed) leaves running=True behind it;
        # without the liveness check the button could never start a second attempt.
        if self.running and self.thread is not None and self.thread.is_alive():
            return
        self.running = True
        self.thread = threading.Thread(target=self._listen_worker, daemon=True, name="TeacherVoiceThread")
        self.thread.start()

    def stop(self):
        self.running = False


_GLOBAL_TEACHER_VOICE: Optional[TeacherContextAI] = None


def get_teacher_voice(cfg: Optional[dict] = None) -> TeacherContextAI:
    """Return singleton TeacherContextAI instance, optionally seeded from config.yaml's
    teacher_voice section by whichever caller gets here first (dashboard API or pipeline)."""
    global _GLOBAL_TEACHER_VOICE
    if _GLOBAL_TEACHER_VOICE is None:
        c = cfg or {}
        _GLOBAL_TEACHER_VOICE = TeacherContextAI(
            auto_reset_seconds=int(c.get("auto_reset_seconds", 180)),
            model_path=str(c.get("model_path", "models/vosk-model-small-vn-0.4")))
    return _GLOBAL_TEACHER_VOICE


def calculate_mar(top_y: float, bottom_y: float, left_x: float, right_x: float,
                  top_x: float = 0.0, bottom_x: float = 0.0, left_y: float = 0.0, right_y: float = 0.0) -> float:
    """Calculate Mouth Aspect Ratio (MAR) from 4 lip landmarks.
    Standard MediaPipe: top (13), bottom (14), left corner (78), right corner (308).
    """
    vertical = math.hypot(top_x - bottom_x, top_y - bottom_y)
    horizontal = math.hypot(left_x - right_x, left_y - right_y)
    if horizontal <= 1e-6:
        return 0.0
    return vertical / horizontal


class StudentAttentionEvaluator:
    """Evaluates student engagement based on head pose (yaw, pitch),
    mouth movement (MAR), posture, and the teacher's current speech context.
    Implements a sliding window / elapsed timer to avoid false alarms.
    """

    # The teacher's own rule for this project: a head looking elsewhere for 10 minutes counts
    # as off-task. Shorter windows are available per instance (the tests drive 7 s to exercise
    # the timer without waiting), and config.yaml's teacher_voice.off_task_seconds overrides it.
    def __init__(self, violation_threshold_s: float = 600.0):
        self.violation_threshold_s = violation_threshold_s
        self.off_task_start: Optional[float] = None
        self.last_talk_time: float = time.time()

    def evaluate(self, yaw: float, pitch: float, mar: float, is_slouching: bool,
                 context: str, now: Optional[float] = None) -> Tuple[str, str, Tuple[int, int, int]]:
        """Evaluate a single student's status.
        Returns:
            (status_label, reason, color_rgb)
            - status_label: "TAP TRUNG" or "MAT TAP TRUNG (Xs)" or "TAP TRUNG (Dang theo doi...)"
            - reason: Human-readable explanation
            - color_rgb: (0, 200, 0) green, (0, 200, 200) yellow, (0, 0, 255) red
        """
        if now is None:
            now = time.time()

        is_valid = False
        reason = ""

        # Pose convention is the one landmarks.py shares with the Android and web legs:
        # pitch POSITIVE = bending the head down, negative = looking up.

        # 1. Kiểm tra tư thế gục mặt / ngủ trước (áp dụng cho mọi ngữ cảnh)
        if is_slouching or pitch > 45.0:
            is_valid = False
            reason = "Gục mặt / Tư thế xấu"

        # 2. Đánh giá theo ngữ cảnh giáo viên đang điều hành
        elif context == TeacherContextAI.CONTEXT_LECTURE:
            # Giảng bài: yêu cầu nhìn thẳng lên bảng
            if abs(yaw) <= 25.0 and -20.0 <= pitch <= 15.0:
                is_valid = True
                reason = "Đang nghe giảng"
            else:
                is_valid = False
                reason = "Không nhìn lên bảng"

        elif context == TeacherContextAI.CONTEXT_STUDY_NOTE:
            # Ghi bài / làm bài tập: cho phép cúi đầu xuống vở
            if abs(yaw) <= 30.0 and 10.0 <= pitch <= 42.0:
                is_valid = True
                reason = "Đang ghi/học bài"
            else:
                is_valid = False
                reason = "Không chép bài / nhìn linh tinh"

        elif context == TeacherContextAI.CONTEXT_GROUP_WORK:
            # Thảo luận nhóm: cập nhật thời điểm mấp máy miệng trao đổi
            if mar > 0.22:
                self.last_talk_time = now

            is_facing_group = (20.0 <= abs(yaw) <= 75.0) or (12.0 <= pitch <= 40.0)
            is_interacting = (now - self.last_talk_time) <= 15.0

            if is_facing_group and is_interacting:
                is_valid = True
                reason = "Tích cực hoạt động nhóm"
            elif not is_facing_group:
                is_valid = False
                reason = "Tách nhóm / Nhìn chỗ khác"
            else:
                is_valid = False
                reason = "Thụ động / Không trao đổi"
        else:
            is_valid = True
            reason = "Bình thường"

        # 3. Bộ đếm thời gian chống báo động giả (chỉ báo mất tập trung nếu vi phạm quá 7s)
        if is_valid:
            self.off_task_start = None
            return ("TAP TRUNG", reason, (0, 200, 0))
        else:
            if self.off_task_start is None:
                self.off_task_start = now
            elapsed = now - self.off_task_start
            if elapsed >= self.violation_threshold_s:
                return (f"MAT TAP TRUNG ({int(elapsed)}s)", reason, (0, 0, 255))
            else:
                return ("TAP TRUNG (Dang theo doi...)", reason, (0, 200, 200))
