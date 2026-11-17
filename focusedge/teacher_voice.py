"""Teacher Voice Context Listener & Multimodal Attention Evaluator.

Integrates speech recognition for teacher verbal commands (LECTURE, STUDY_NOTE, GROUP_WORK,
SEATWORK, Q_AND_A) with MediaPipe Face Mesh & head pose metrics. Việc ĐÁNH GIÁ từng em theo
ngữ cảnh nằm ở context_eval.py (nối vào pipeline); StudentAttentionEvaluator dưới đây là bản
tham chiếu 3 ngữ cảnh của tài liệu 2, giữ nguyên hợp đồng đã kiểm thử.

Hai nguyên tắc của dự án áp dụng cho module này:

1. KHÔNG SUY DIỄN KHI CHƯA NGHE. Trạng thái khởi động là UNKNOWN, không phải LECTURE. Bản đầu tiên
   mở mắt ở LECTURE với last_speech "Hệ thống sẵn sàng", và classroom.py đọc đó thành một khẩu lệnh
   thật: mọi phiên bị dán nhãn "nghe giảng (giọng nói GV)" trong 3 phút đầu dù micro chưa cài và
   không ai nói (đo được: tests/test_activity.py 27 pass / 2 fail). Im lặng không phải là bài giảng.
   Hết thời gian khẩu lệnh cũng quay về UNKNOWN, không quay về LECTURE.
2. MICRO LÀ CHỦ ĐỘNG BẬT, VÀ PHẢI NÓI RÕ AUDIO ĐI ĐÂU. Nhận dạng dùng recognize_google, tức là đoạn
   ghi âm được gửi ra máy chủ Google - trái với phần còn lại của dự án (offline, không gì rời máy).
   Vì vậy luồng nghe mặc định TẮT, chỉ chạy khi người dùng bấm bật, và get_status() luôn kèm
   "sends_audio_offsite": True cùng câu cảnh báo để giao diện in ra trước khi bật.

Stdout của module này ASCII-only (code page console không encode được tiếng Việt có dấu); chữ có dấu
chỉ nằm trong JSON trả cho giao diện, giống phần còn của focusedge.
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


# Câu cảnh báo riêng tư, một chỗ để server / giao diện / HUD cùng đọc một lời.
PRIVACY_NOTE = ("Nhận dạng khẩu lệnh dùng Google Speech: đoạn ghi âm từ micro được gửi ra internet. "
                "Phần đo lường còn lại vẫn offline. Chỉ bật khi giáo viên đồng ý.")
MISSING_DEPS_NOTE = ("Chưa cài SpeechRecognition / PyAudio nên không nghe được micro. "
                     "Vẫn chọn ngữ cảnh bằng tay được.")


class TeacherContextAI:
    """Listens in a background daemon thread to microphone input,
    detecting teacher keywords and switching the classroom activity context.
    Also supports offline fallback, auto-reset timeout, and manual overrides.
    """

    CONTEXT_UNKNOWN = "UNKNOWN"         # Chưa nghe khẩu lệnh nào - không được coi là một hoạt động
    CONTEXT_LECTURE = "LECTURE"         # Nghe giảng (nhìn bảng)
    CONTEXT_STUDY_NOTE = "STUDY_NOTE"   # Ghi bài / chép bài (cúi đầu xuống vở)
    CONTEXT_GROUP_WORK = "GROUP_WORK"   # Thảo luận nhóm (quay mặt + mấp máy môi)
    CONTEXT_SEATWORK = "SEATWORK"       # Làm bài tập / suy nghĩ cá nhân (tài liệu 3)
    CONTEXT_Q_AND_A = "Q_AND_A"         # Hỏi - đáp, giơ tay phát biểu (tài liệu 3)

    CONTEXTS = (CONTEXT_LECTURE, CONTEXT_STUDY_NOTE, CONTEXT_GROUP_WORK,
                CONTEXT_SEATWORK, CONTEXT_Q_AND_A)

    def __init__(self, auto_reset_seconds: int = 180, language: str = "vi-VN"):
        self.context = self.CONTEXT_UNKNOWN
        self.last_speech = ""
        self.source: Optional[str] = None   # "speech" = nghe được, "manual" = người dùng chọn
        self.last_switch_time = time.time()
        self.auto_reset_seconds = auto_reset_seconds  # Hết thời gian -> UNKNOWN, không phải LECTURE
        self.running = False
        self.language = language
        self.thread: Optional[threading.Thread] = None
        self.last_error = ""
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
                "cô giải thích",      # Tài liệu 3, bảng ngữ cảnh LECTURE
                "thầy giải thích",
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
            # Tài liệu 3 tách SEATWORK (làm bài tập / suy nghĩ) khỏi NOTE_TAKING (ghi chép).
            # Hai tài liệu MÂU THUẪN ở "làm bài tập" / "mở sách": tài liệu 2 xếp chúng vào
            # STUDY_NOTE và test_teacher_voice đã khoá điều đó ("mở sách làm bài tập" ->
            # STUDY_NOTE). Giữ nguyên STUDY_NOTE cho hai từ này; SEATWORK nhận các từ còn
            # lại của tài liệu 3. Nhờ khớp dài-nhất-trước, "tự làm bài tập" vẫn về STUDY_NOTE.
            self.CONTEXT_SEATWORK: [
                "làm bài một mình",
                "bài tập cá nhân",
                "tự làm",
                "tự học",
                "suy nghĩ",
            ],
            self.CONTEXT_Q_AND_A: [
                "trả lời câu hỏi",
                "ai trả lời",
                "giơ tay",
                "phát biểu",
                "mời một bạn",
                "mời em",
                "hỏi đáp",
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
        """Directly set context (e.g. from UI shortcut or keyboard 1, 2, 3).

        UNKNOWN cũng nhận: đó là cách người dùng trả quyền quyết định về cho luật viết tay của
        activity.py. Mỗi lần đặt là một khẳng định CÓ chủ ngữ, nên source được ghi lại - classroom.py
        dựa vào đó để nói "GV khẩu lệnh" hay "GV chọn", không gọi một nút bấm là giọng nói.
        """
        if new_ctx not in self.CONTEXTS and new_ctx != self.CONTEXT_UNKNOWN:
            return
        with self._lock:
            self.context = new_ctx
            self.last_switch_time = time.time()
            if new_ctx == self.CONTEXT_UNKNOWN:
                self.source = None
                self.last_speech = ""
            else:
                self.source = "manual" if reason != "speech" else "speech"
                # Không bịa một câu nói khi người dùng bấm nút: last_speech chỉ chứa lời nghe được
                # (feed_text đã ghi, giữ nguyên làm bằng chứng). Bản cũ ghi "[dashboard_api] ->
                # GROUP_WORK" và giao diện in nó như "câu gần nhất" - mã nội bộ, không phải lời GV.
                if self.source == "manual":
                    self.last_speech = ""

    def feed_text(self, text: str) -> Optional[str]:
        """Một câu nghe được (hoặc gõ vào để thử): phân loại rồi đặt ngữ cảnh.

        Tách khỏi luồng micro để kiểm thử được mà không cần phần cứng, và để chỗ khác (giao diện,
        công cụ dòng lệnh) dùng chung một đường vào. Trả về ngữ cảnh nhận được, None nếu câu không
        phải khẩu lệnh - khi đó last_speech vẫn ghi lại câu nghe được cho người xem đối chiếu.
        """
        detected = self.detect_context(text)
        with self._lock:
            self.last_speech = text or ""
        if detected:
            self.set_context(detected, reason="speech")
        return detected

    def _expire_locked(self, now: float) -> None:
        """Hết thời gian khẩu lệnh -> UNKNOWN. Gọi khi đang giữ _lock.

        Bản cũ quay về LECTURE, tức là tự tuyên bố "đang nghe giảng" sau khi giáo viên im lặng 3 phút.
        Không có bằng chứng thì không có nhãn: UNKNOWN để activity.py đọc phòng bằng luật của nó.
        """
        if self.context != self.CONTEXT_UNKNOWN and (now - self.last_switch_time > self.auto_reset_seconds):
            self.context = self.CONTEXT_UNKNOWN
            self.source = None
            self.last_speech = ""
            self.last_switch_time = now

    def get_status(self) -> Dict[str, any]:
        """Return snapshot of current state."""
        with self._lock:
            now = time.time()
            self._expire_locked(now)
            return {
                "context": self.context,
                "last_speech": self.last_speech,
                "elapsed_s": round(now - self.last_switch_time, 1),
                "is_running": self.running,
                # Có khẩu lệnh thật hay không - classroom.py chỉ ghi đè nhãn khi cờ này True.
                "has_command": self.context != self.CONTEXT_UNKNOWN,
                "source": self.source,
                "mic_available": HAS_SR,
                "sends_audio_offsite": True,
                "privacy_note": PRIVACY_NOTE,
                "last_error": self.last_error or ("" if HAS_SR else MISSING_DEPS_NOTE),
                "auto_reset_seconds": self.auto_reset_seconds,
            }

    def _listen_worker(self):
        if not HAS_SR:
            # Không có thư viện thì không có luồng nghe. running phải về False để giao diện không
            # hiện "đang nghe" trong khi không có gì đang nghe.
            with self._lock:
                self.running = False
                self.last_error = MISSING_DEPS_NOTE
            print("[teacher-voice] SpeechRecognition/PyAudio missing - manual context only")
            return

        recognizer = sr.Recognizer()
        recognizer.energy_threshold = 300
        recognizer.dynamic_energy_threshold = True

        try:
            mic = sr.Microphone()
        except Exception as e:
            with self._lock:
                self.running = False
                self.last_error = "Không khởi tạo được micro: %s" % e
            print("[teacher-voice] no microphone (%s) - manual context only" % type(e).__name__)
            return

        print("[teacher-voice] mic open, listening for teacher commands (audio goes to Google)")

        try:
            with mic as source:
                recognizer.adjust_for_ambient_noise(source, duration=1)
                while self.running:
                    now = time.time()
                    with self._lock:
                        self._expire_locked(now)

                    try:
                        audio = recognizer.listen(source, timeout=2, phrase_time_limit=5)
                        try:
                            text = recognizer.recognize_google(audio, language=self.language)
                        except sr.RequestError:
                            # Không có mạng / Google từ chối: ghi lại một lần để giao diện nói thật,
                            # rồi nghe tiếp chứ không dừng luồng.
                            with self._lock:
                                self.last_error = ("Không gọi được nhận dạng giọng nói (cần internet). "
                                                   "Khẩu lệnh vẫn chọn bằng tay được.")
                            continue

                        detected = self.feed_text(text)
                        if detected:
                            print("[teacher-voice] command -> %s" % detected)
                        with self._lock:
                            self.last_error = ""

                    except (sr.WaitTimeoutError, sr.UnknownValueError):
                        pass
                    except Exception as err:
                        # Bắt mọi lỗi ngoại lệ để không bao giờ dừng luồng chính
                        with self._lock:
                            self.last_error = "Lỗi luồng nghe: %s" % err
                        time.sleep(0.5)

        except Exception as e:
            with self._lock:
                self.running = False
                self.last_error = "Ngoại lệ ngoài luồng micro: %s" % e
            print("[teacher-voice] mic loop ended (%s)" % type(e).__name__)

    def start(self):
        """Bật luồng nghe. Chỉ chạy khi được gọi rõ ràng (nút trên giao diện / API listen)."""
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
