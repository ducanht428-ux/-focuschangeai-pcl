"""Đánh giá tập trung TỪNG EM theo ngữ cảnh khẩu lệnh giáo viên (Fusion Engine).

Tài liệu nguồn:
  - Huong_dan_AI_3_ngu_canh_va_hoat_dong_nhom_MediaPipe.docx (mục I, III, IV)
  - Thiet_ke_AI_nhan_dien_tap_trung_theo_ngu_canh_GV.docx (mục I, II - bảng 5 ngữ cảnh)

Vì sao module này tồn tại riêng thay vì sửa StudentAttentionEvaluator trong teacher_voice.py:
evaluator cũ là bản tham chiếu 3 ngữ cảnh của tài liệu 2, trả về nhãn ASCII cho cửa sổ demo và
đang được test_teacher_voice.py khoá từng chữ. Bản nối vào pipeline thật cần thêm: 5 ngữ cảnh,
ngưỡng cảnh báo RIÊNG từng ngữ cảnh (bảng tài liệu 3), cổng mắt mở / gật đầu / khoảng cách cho
hoạt động nhóm, đếm off-task cộng dồn theo phiên (mốc 10 phút = mất tiết), và trạng thái TRUNG
LẬP khi chưa có khẩu lệnh hoặc mặt không đo được. Giữ hai lớp tách nhau để không phá vỡ hợp
đồng đã kiểm thử của lớp tham chiếu.

Ba nguyên tắc của dự án áp dụng ở đây:

1. KHÔNG CÓ KHẨU LỆNH THÌ KHÔNG PHÁN. context UNKNOWN -> state NEUTRAL, không on/off-task,
   không tích luỹ thời gian. Im lặng của giáo viên không phải là "đang nghe giảng" (xem
   teacher_voice.py: hết hạn quay về UNKNOWN, không quay về LECTURE như bản gốc tài liệu -
   bản gốc đã từng dán nhãn sai mọi phiên trong 3 phút đầu, đo được bằng 2 test activity hỏng).
2. MẶT KHÔNG ĐO ĐƯỢC THÌ KHÔNG PHÁN. measurable False -> NEUTRAL, đồng hồ off-task dừng,
   không tích luỹ. Một khung hình mất mặt không phải là một giây mất tập trung.
3. KHÔNG ĐỤNG VÀO CHUỖI CẢNH BÁO PARITY. SEI, 15 mức cảnh báo, ngưỡng và làm tròn của điện
   thoại giữ nguyên trong person_pipeline.py. Đây là lớp KHUYẾN NGHỊ sư phạm chạy song song,
   sự kiện của nó đi vào notes JSON của phiên và bảng khuyến nghị riêng, không đếm vào
   alerts_total. Góc ở đây là yaw/pitch LỆCH SO VỚI TRUNG TÍNH đã hiệu chuẩn của từng em
   (yaw_dev/pitch_dev của PersonPipeline) - ngưỡng tuyệt đối của tài liệu áp lên độ lệch,
   nên em ngồi lệch camera sẵn không bị phạt oan cả tiết.

Stdout: module không print. Chữ có dấu chỉ nằm trong dict trả cho giao diện.
"""
from __future__ import annotations

import math
from collections import deque
from typing import Deque, Dict, List, Optional, Tuple

from .teacher_voice import TeacherContextAI

# ---------------------------------------------------------------------------
# Bảng quy chiếu ngữ cảnh (tài liệu 3 mục II.2 + tài liệu 2 mục IV.2).
# window_s: thời gian vi phạm LIÊN TỤC trước khi báo mất tập trung, lấy trong
# khoảng tài liệu 3 cho từng ngữ cảnh (LECTURE 5-10s, NOTE 15-20s, SEATWORK
# 15-30s, GROUP 10-15s, QA 5-10s). Tài liệu 2 dùng 7s cho mọi ngữ cảnh; bản
# này phân biệt vì cúi đầu chép bài 8 giây không giống quay ngang 8 giây.
# ---------------------------------------------------------------------------
CRITERIA: Dict[str, Dict] = {
    TeacherContextAI.CONTEXT_LECTURE: {
        "yaw_max": 25.0, "pitch_min": -15.0, "pitch_max": 20.0, "window_s": 7.0,
        "on_reason": "Đang nghe giảng", "off_reason": "Không nhìn lên bảng",
        "vi": "nghe giảng",
    },
    TeacherContextAI.CONTEXT_STUDY_NOTE: {
        "yaw_max": 30.0, "pitch_min": -42.0, "pitch_max": -10.0, "window_s": 15.0,
        "on_reason": "Đang ghi/học bài", "off_reason": "Không chép bài / nhìn linh tinh",
        "vi": "ghi bài",
    },
    TeacherContextAI.CONTEXT_SEATWORK: {
        # Tài liệu 3: cúi nhìn sách/vở HOẶC ngước nhẹ lên suy nghĩ đều hợp lệ.
        "yaw_max": 30.0, "pitch_min": -42.0, "pitch_max": 15.0, "window_s": 20.0,
        "on_reason": "Đang làm bài / suy nghĩ", "off_reason": "Không làm bài / nhìn linh tinh",
        "vi": "làm bài tập",
    },
    TeacherContextAI.CONTEXT_GROUP_WORK: {
        "window_s": 10.0, "vi": "thảo luận nhóm",
        # 4 tiêu chí hình học + thời gian của tài liệu 2 mục III:
        "yaw_min": 20.0, "yaw_max": 75.0,          # 1. quay về phía bạn
        "pitch_group_min": -40.0, "pitch_group_max": -12.0,  # cùng cúi nhìn phiếu chung
        "talk_mar": 0.25, "talk_window_s": 15.0,   # 2. mấp máy môi trong 15s gần nhất
        "nod_window_s": 4.0,                       # 2b. gật đầu tương tác
        "group_close_ratio": 0.90,                 # 3. xích lại gần bạn (tín hiệu mềm)
        # 4. mắt mở, không gục - dùng chung cổng SLOUCH bên dưới.
    },
    TeacherContextAI.CONTEXT_Q_AND_A: {
        # Tài liệu 3: nhìn lên GV/bạn trả lời. Vế "cổ tay giơ cao hơn vai" cần
        # PoseLandmarker - máy dự án không chạy pose (xem ghi chú giới hạn), nên
        # KHÔNG tuyên bố phát hiện giơ tay; chỉ đánh giá hướng đầu/mắt.
        "yaw_max": 25.0, "pitch_min": -15.0, "pitch_max": 20.0, "window_s": 7.0,
        "on_reason": "Đang hướng về GV / bạn trả lời",
        "off_reason": "Cúi gằm tránh né / làm việc riêng",
        "vi": "hỏi – đáp",
    },
}

# Cổng gục mặt áp dụng cho MỌI ngữ cảnh (tài liệu 2: "kiểm tra tư thế gục/ngủ trước tiên").
# Pitch âm sâu hơn -45 độ là gục hẳn xuống bàn, không phải cúi chép bài.
SLOUCH_PITCH_DEG = -45.0

# Mốc đánh giá sư phạm trong tiết 45 phút (tài liệu 2+3 mục I):
#   cộng dồn >= 10 phút  -> mất tập trung NẶNG (mất kết nối với mạch bài)
#   liên tục/cộng dồn >= 3 phút -> TRUNG BÌNH (bỏ lỡ một ý giảng)
SEVERE_TOTAL_S = 600.0
MEDIUM_S = 180.0

# Nhịp micro-distraction 5-10 giây của tài liệu: mỗi lần vượt window là MỘT sự kiện
# (episode), không phải một chuỗi cảnh báo mỗi khung hình.
STATE_NEUTRAL = "NEUTRAL"      # chưa có khẩu lệnh, hoặc mặt không đo được
STATE_ON_TASK = "ON_TASK"      # đúng yêu cầu ngữ cảnh
STATE_WATCH = "WATCH"          # đang lệch nhưng chưa quá window (5-10s theo dõi)
STATE_OFF_TASK = "OFF_TASK"    # lệch liên tục quá window -> mất tập trung

STATE_VI = {
    STATE_NEUTRAL: "Chưa đánh giá theo ngữ cảnh",
    STATE_ON_TASK: "Đang tập trung theo ngữ cảnh",
    STATE_WATCH: "Đang theo dõi (lệch nhẹ)",
    STATE_OFF_TASK: "Mất tập trung theo ngữ cảnh",
}

# Khoảng cách hai mắt/giữa các mặt chỉ tin được tương đối trên một camera: tiêu cự
# suy từ FOV 60 độ, hộp YOLO co giãn theo tư thế. Vì vậy tiêu chí 3 (xích lại gần
# nhóm) là TÍN HIỆU MỀM ghi vào bằng chứng, không phải cổng cứng quyết định on/off.
DIST_BASELINE_ALPHA = 0.05


def _finite(v: Optional[float], default: float = 0.0) -> float:
    try:
        return float(v) if v is not None and math.isfinite(float(v)) else default
    except (TypeError, ValueError):
        return default


class ContextEvaluator:
    """Một bộ đánh giá cho MỘT em, sống suốt phiên đo của track đó.

    update() gọi mỗi khung hình đo được (hoặc mỗi khung hình mất mặt để dừng đồng hồ).
    view() trả dict cho giao diện; session_summary() trả phần ghi vào notes JSON.
    """

    def __init__(self) -> None:
        self.state = STATE_NEUTRAL
        self.context = TeacherContextAI.CONTEXT_UNKNOWN
        self.reason = ""
        self.evidence: List[str] = []
        self.window_s = 0.0
        # đồng hồ vi phạm liên tục
        self._off_start_ms = 0
        self._last_update_ms = 0
        # tích luỹ theo phiên (chỉ khi CÓ khẩu lệnh và đo được)
        self.off_task_total_ms = 0
        self.episodes: List[Dict] = []
        self._episode_open: Optional[Dict] = None
        self.contexts_used: List[str] = []
        # nhóm: cửa sổ trượt nói chuyện + gật đầu + khoảng cách
        self._last_talk_ms = 0
        self._pitch_hist: Deque[Tuple[int, float]] = deque(maxlen=64)
        self._nod_until_ms = 0
        self._dist_baseline_cm: Optional[float] = None
        self.group_close = False
        self.talk_recent = False
        self.facing_group = False
        self.severity = ""

    # ------------------------------------------------------------------
    def _nod_recent(self, now_ms: int, pitch: float) -> bool:
        """Gật đầu tương tác: pitch nhấp nhô nhẹ (biên 3-20 độ, >= 2 lần đổi chiều
        trong 4 giây). Tài liệu 2 tiêu chí 2: gật đầu được tính là trao đổi kể cả
        khi miệng không mấp máy (em nghe bạn rồi gật)."""
        self._pitch_hist.append((now_ms, pitch))
        while self._pitch_hist and now_ms - self._pitch_hist[0][0] > 4000:
            self._pitch_hist.popleft()
        if len(self._pitch_hist) < 4:
            return now_ms < self._nod_until_ms
        vals = [p for _, p in self._pitch_hist]
        amp = max(vals) - min(vals)
        if not (3.0 <= amp <= 20.0):
            return now_ms < self._nod_until_ms
        reversals = 0
        prev_sign = 0
        for a, b in zip(vals, vals[1:]):
            d = b - a
            if abs(d) < 1.0:
                continue
            sign = 1 if d > 0 else -1
            if prev_sign and sign != prev_sign:
                reversals += 1
            prev_sign = sign
        if reversals >= 2:
            self._nod_until_ms = now_ms + 4000
        return now_ms < self._nod_until_ms

    def _geometry(self, ctx: str, yaw: float, pitch: float, mar: float,
                  eyes_open: Optional[bool], now_ms: int) -> Tuple[bool, str, List[str]]:
        """Trả (on_task, reason, evidence) theo bảng quy chiếu. Cổng gục trước tiên."""
        if pitch < SLOUCH_PITCH_DEG:
            return False, "Gục mặt / tư thế xấu", []
        c = CRITERIA.get(ctx)
        if c is None:
            return True, "", []
        if ctx == TeacherContextAI.CONTEXT_GROUP_WORK:
            ev: List[str] = []
            if mar > c["talk_mar"]:
                self._last_talk_ms = now_ms
            self.talk_recent = (now_ms - self._last_talk_ms) <= c["talk_window_s"] * 1000.0
            nod = self._nod_recent(now_ms, pitch)
            self.facing_group = (c["yaw_min"] <= abs(yaw) <= c["yaw_max"]
                                 or c["pitch_group_min"] <= pitch <= c["pitch_group_max"])
            if self.talk_recent:
                ev.append("có trao đổi miệng trong 15s")
            if nod:
                ev.append("gật đầu tương tác")
            if self.group_close:
                ev.append("xích lại gần bạn (tín hiệu mềm)")
            interacting = self.talk_recent or nod
            # Tiêu chí 4: mắt mở. eyes_open None = không đọc được mắt (góc đầu quá
            # nghiêng cho EyeEngine) -> KHÔNG được kết luận là nhắm mắt; bỏ qua cổng.
            if eyes_open is False:
                return False, "Nhắm mắt trong giờ nhóm", ev
            if self.facing_group and interacting:
                return True, "Tích cực hoạt động nhóm", ev
            if not self.facing_group:
                return False, "Tách nhóm / nhìn chỗ khác", ev
            return False, "Thụ động / không trao đổi", ev
        on = abs(yaw) <= c["yaw_max"] and c["pitch_min"] <= pitch <= c["pitch_max"]
        return on, (c["on_reason"] if on else c["off_reason"]), []

    # ------------------------------------------------------------------
    def update(self, now_ms: int, context: str, measurable: bool,
               yaw: Optional[float], pitch: Optional[float], mar: Optional[float],
               eyes_open: Optional[bool] = None,
               nearest_cm: Optional[float] = None) -> Dict:
        """Một nhịp đánh giá. yaw/pitch là ĐỘ LỆCH so với trung tính đã hiệu chuẩn.

        nearest_cm: khoảng cách tới mặt gần nhất trong khung (tiêu chí 3 của nhóm).
        Baseline khoảng cách học chậm chỉ khi KHÔNG phải giờ nhóm, để "xích lại gần"
        so với lúc ngồi nghe giảng đúng như tài liệu mô tả.
        """
        yaw = _finite(yaw)
        pitch = _finite(pitch)
        mar = _finite(mar)
        dt_ms = 0
        if self._last_update_ms and now_ms > self._last_update_ms:
            dt_ms = min(5000, now_ms - self._last_update_ms)  # mất mặt lâu không tính dồn
        self._last_update_ms = now_ms

        # khoảng cách: baseline mềm + tín hiệu xích gần
        if nearest_cm is not None and nearest_cm > 0:
            if context != TeacherContextAI.CONTEXT_GROUP_WORK:
                if self._dist_baseline_cm is None:
                    self._dist_baseline_cm = float(nearest_cm)
                else:
                    self._dist_baseline_cm += DIST_BASELINE_ALPHA * (nearest_cm - self._dist_baseline_cm)
            self.group_close = (self._dist_baseline_cm is not None
                                and nearest_cm < self._dist_baseline_cm * CRITERIA[
                                    TeacherContextAI.CONTEXT_GROUP_WORK]["group_close_ratio"])
        else:
            self.group_close = False

        has_command = context in CRITERIA
        if not has_command or not measurable:
            # Trung lập: dừng đồng hồ, đóng episode nếu đang mở, KHÔNG tích luỹ.
            self._close_episode(now_ms)
            self._off_start_ms = 0
            self.state = STATE_NEUTRAL
            self.context = context if has_command else TeacherContextAI.CONTEXT_UNKNOWN
            self.reason = "" if has_command else "Chưa có khẩu lệnh GV"
            if has_command and not measurable:
                self.reason = "Mặt không đo được - chưa đánh giá"
            self.window_s = CRITERIA[context]["window_s"] if has_command else 0.0
            self.severity = self._severity()
            return self.view()

        if context != self.context:
            # Đổi ngữ cảnh: đóng episode cũ, reset đồng hồ liên tục; tổng tích luỹ
            # giữ nguyên (đó là tổng của cả phiên, không của một ngữ cảnh).
            self._close_episode(now_ms)
            self._off_start_ms = 0
            if context not in self.contexts_used:
                self.contexts_used.append(context)
        self.context = context
        self.window_s = CRITERIA[context]["window_s"]

        on, reason, ev = self._geometry(context, yaw, pitch, mar, eyes_open, now_ms)
        self.reason = reason
        self.evidence = ev
        if on:
            self._close_episode(now_ms)
            self._off_start_ms = 0
            self.state = STATE_ON_TASK
        else:
            if self._off_start_ms == 0:
                self._off_start_ms = now_ms
            # Tích luỹ MỌI nhịp lệch (kể cả nhịp ngắn chưa quá window): tài liệu định
            # nghĩa mất tập trung nặng là TỔNG thời gian lệch "liên tục hoặc cộng dồn"
            # >= 10 phút, các nhịp 5-10 giây rời rạc vẫn góp vào tổng đó.
            self.off_task_total_ms += dt_ms
            elapsed_s = (now_ms - self._off_start_ms) / 1000.0
            if elapsed_s >= self.window_s:
                self.state = STATE_OFF_TASK
                if self._episode_open is None:
                    self._episode_open = {"context": context, "reason": reason,
                                          "start_ms": self._off_start_ms, "end_ms": now_ms}
                    self.episodes.append(self._episode_open)
                else:
                    self._episode_open["end_ms"] = now_ms
                    self._episode_open["reason"] = reason
            else:
                self.state = STATE_WATCH
        self.severity = self._severity()
        return self.view()

    def _close_episode(self, now_ms: int) -> None:
        if self._episode_open is not None:
            self._episode_open["end_ms"] = now_ms
            self._episode_open = None

    def _severity(self) -> str:
        total_s = self.off_task_total_ms / 1000.0
        cont_s = ((self._last_update_ms - self._off_start_ms) / 1000.0
                  if self._off_start_ms else 0.0)
        if total_s >= SEVERE_TOTAL_S:
            return "nang"        # >= 10 phút cộng dồn: mất kết nối với mạch bài
        if cont_s >= MEDIUM_S or total_s >= MEDIUM_S:
            return "trung_binh"  # 3-5 phút: bỏ lỡ một ý giảng
        return ""

    # ------------------------------------------------------------------
    def view(self) -> Dict:
        """Dict cho giao diện (thẻ từng em / class_live JSON)."""
        off_s = ((self._last_update_ms - self._off_start_ms) / 1000.0
                 if self._off_start_ms and self.state in (STATE_WATCH, STATE_OFF_TASK) else 0.0)
        return {
            "context": self.context,
            "context_vi": CRITERIA.get(self.context, {}).get("vi", ""),
            "state": self.state,
            "state_vi": STATE_VI[self.state],
            "reason": self.reason,
            "evidence": list(self.evidence),
            "off_s": round(off_s, 1),
            "off_total_s": round(self.off_task_total_ms / 1000.0, 1),
            "window_s": self.window_s,
            "severity": self.severity,
            "episodes": len(self.episodes),
            "talk_recent": self.talk_recent,
            "facing_group": self.facing_group,
            "group_close": self.group_close,
        }

    def session_summary(self) -> Optional[Dict]:
        """Phần ghi vào observation_sessions.notes khi chốt phiên.

        None khi phiên không có khẩu lệnh nào - không có gì để tổng kết thì không
        bịa một tổng kết rỗng. off-task chỉ được đếm trong lúc CÓ lệnh và đo được.
        """
        if not self.contexts_used:
            return None
        self._close_episode(self._last_update_ms)
        return {
            "off_task_s": round(self.off_task_total_ms / 1000.0, 1),
            "episodes": len(self.episodes),
            "severity": self._severity(),
            "contexts_used": list(self.contexts_used),
            "episode_list": [
                {"context": e["context"], "reason": e["reason"],
                 "start_ms": e["start_ms"], "end_ms": e["end_ms"],
                 "duration_s": round((e["end_ms"] - e["start_ms"]) / 1000.0, 1)}
                for e in self.episodes
            ],
        }