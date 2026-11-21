"""Teacher Voice Context Listener & Multimodal Attention Evaluator.
Integrates speech recognition for teacher verbal commands (LECTURE, STUDY_NOTE, GROUP_WORK)
with MediaPipe Face Mesh & head pose metrics.
"""
from __future__ import annotations

import math
import threading
import time
from typing import Dict, List, Optional, Tuple

try:
    import speech_recognition as sr
    HAS_SR = True
except ImportError:
    sr = None
    HAS_SR = False


class TeacherContextAI:
    """Listens in a background daemon thread to microphone input,
    detecting teacher keywords and switching the classroom activity context.
    Also supports offline fallback, auto-reset timeout, and manual overrides.
    """

    CONTEXT_LECTURE = "LECTURE"         # Nghe giảng (nhìn bảng)
    CONTEXT_STUDY_NOTE = "STUDY_NOTE"   # Ghi bài / làm bài tập (cúi đầu)
    CONTEXT_GROUP_WORK = "GROUP_WORK"   # Thảo luận nhóm (quay mặt + mấp máy môi)

    def __init__(self, auto_reset_seconds: int = 180, language: str = "vi-VN"):
        self.context = self.CONTEXT_LECTURE
        self.last_speech = "Hệ thống sẵn sàng"
        self.last_switch_time = time.time()
        self.auto_reset_seconds = auto_reset_seconds  # Tự quay về LECTURE sau 3-5 phút nếu GV im lặng
        self.running = False
        self.language = language
        self.thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()

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

    def set_context(self, new_ctx: str, reason: str = "manual"):
        """Directly set context (e.g. from UI shortcut or keyboard 1, 2, 3)."""
        if new_ctx in (self.CONTEXT_LECTURE, self.CONTEXT_STUDY_NOTE, self.CONTEXT_GROUP_WORK):
            with self._lock:
                self.context = new_ctx
                self.last_switch_time = time.time()
                self.last_speech = f"[{reason}] -> {new_ctx}"

    def get_status(self) -> Dict[str, any]:
        """Return snapshot of current state."""
        with self._lock:
            # Kiểm tra auto-reset timeout
            now = time.time()
            if self.context != self.CONTEXT_LECTURE and (now - self.last_switch_time > self.auto_reset_seconds):
                self.context = self.CONTEXT_LECTURE
                self.last_speech = "Tự động quay lại Nghe giảng (Hết thời gian hoạt động)"
                self.last_switch_time = now

            return {
                "context": self.context,
                "last_speech": self.last_speech,
                "elapsed_s": round(now - self.last_switch_time, 1),
                "is_running": self.running,
            }

    def _listen_worker(self):
        if not HAS_SR:
            print("[TeacherContextAI] SpeechRecognition không có sẵn, hoạt động ở chế độ phím bấm thủ công.")
            return

        recognizer = sr.Recognizer()
        recognizer.energy_threshold = 300
        recognizer.dynamic_energy_threshold = True

        try:
            mic = sr.Microphone()
        except Exception as e:
            print(f"[TeacherContextAI] Không khởi tạo được micro: {e}. Dùng phím tắt thủ công.")
            return

        print("[TeacherContextAI] Đã kết nối micro giáo viên, luồng lắng nghe khẩu lệnh đã kích hoạt.")

        try:
            with mic as source:
                recognizer.adjust_for_ambient_noise(source, duration=1)
                while self.running:
                    # Kiểm tra auto-reset về LECTURE
                    now = time.time()
                    with self._lock:
                        if self.context != self.CONTEXT_LECTURE and (now - self.last_switch_time > self.auto_reset_seconds):
                            self.context = self.CONTEXT_LECTURE
                            self.last_speech = "Hết thời gian -> Về Nghe giảng"
                            self.last_switch_time = now
                            print("[TeacherContextAI] Hết thời gian hoạt động -> Tự động về LECTURE")

                    try:
                        audio = recognizer.listen(source, timeout=2, phrase_time_limit=5)
                        try:
                            text = recognizer.recognize_google(audio, language=self.language)
                        except sr.RequestError:
                            # Chế độ không có mạng internet
                            continue

                        detected = self.detect_context(text)
                        with self._lock:
                            self.last_speech = text
                            if detected:
                                self.context = detected
                                self.last_switch_time = time.time()
                                print(f"[GV NÓI]: '{text}' ===> CHUYỂN NGỮ CẢNH: {self.context}")

                    except (sr.WaitTimeoutError, sr.UnknownValueError):
                        pass
                    except Exception as err:
                        # Bắt mọi lỗi ngoại lệ để không bao giờ dừng luồng chính
                        time.sleep(0.5)

        except Exception as e:
            print(f"[TeacherContextAI] Ngoại lệ ngoài luồng micro: {e}")

    def start(self):
        if self.running:
            return
        self.running = True
        self.thread = threading.Thread(target=self._listen_worker, daemon=True, name="TeacherVoiceThread")
        self.thread.start()

    def stop(self):
        self.running = False


_GLOBAL_TEACHER_VOICE: Optional[TeacherContextAI] = None


def get_teacher_voice() -> TeacherContextAI:
    """Return singleton TeacherContextAI instance."""
    global _GLOBAL_TEACHER_VOICE
    if _GLOBAL_TEACHER_VOICE is None:
        _GLOBAL_TEACHER_VOICE = TeacherContextAI()
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

    def __init__(self, violation_threshold_s: float = 7.0):
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

        # 1. Kiểm tra tư thế gục mặt / ngủ trước (áp dụng cho mọi ngữ cảnh)
        if is_slouching or pitch < -45.0:
            is_valid = False
            reason = "Gục mặt / Tư thế xấu"

        # 2. Đánh giá theo ngữ cảnh giáo viên đang điều hành
        elif context == TeacherContextAI.CONTEXT_LECTURE:
            # Giảng bài: yêu cầu nhìn thẳng lên bảng
            if abs(yaw) <= 25.0 and -15.0 <= pitch <= 20.0:
                is_valid = True
                reason = "Đang nghe giảng"
            else:
                is_valid = False
                reason = "Không nhìn lên bảng"

        elif context == TeacherContextAI.CONTEXT_STUDY_NOTE:
            # Ghi bài / làm bài tập: cho phép cúi đầu xuống vở
            if abs(yaw) <= 30.0 and -42.0 <= pitch <= -10.0:
                is_valid = True
                reason = "Đang ghi/học bài"
            else:
                is_valid = False
                reason = "Không chép bài / nhìn linh tinh"

        elif context == TeacherContextAI.CONTEXT_GROUP_WORK:
            # Thảo luận nhóm: cập nhật thời điểm mấp máy miệng trao đổi
            if mar > 0.22:
                self.last_talk_time = now

            is_facing_group = (20.0 <= abs(yaw) <= 75.0) or (-40.0 <= pitch <= -12.0)
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
