"""Theo dõi DẤU HIỆU giật lặp lại kiểu Tic (tài liệu 1) - KHÔNG chẩn đoán.

Tài liệu nguồn: Tai_lieu_dau_hieu_va_thong_so_benh_Tic.docx (DSM-5, YGTSS, BV Nhi Trung ương).

Ranh giới y khoa mà module này bắt buộc phải giữ:

* Tài liệu 1 nói rõ: không có con số "đo đếm" cứng nào kết luận được rối loạn tíc; chẩn đoán
  dựa trên tính chất động tác + tần suất trong ngày + tổng thời gian kéo dài, và do chuyên khoa
  thực hiện. Camera chỉ nhìn thấy MỘT phiên vài chục phút, không thấy "gần như mỗi ngày trong
  4 tuần". Vì vậy đầu ra ở đây là KHUYẾN NGHỊ THEO DÕI / ĐI KHÁM, không bao giờ là tên bệnh.
* Nháy mắt thường cũng nhanh (<1s) và cũng lặp lại. Tín hiệu phân biệt là MẪU HÌNH: chùm
  >=3 nháy liên tiếp cách nhau <=1.5s, các chùm lặp lại đều đặn mỗi 5-15 giây trong nhiều
  phút (mức trung bình-nặng của tài liệu), hoặc dày đặc vài giây một lần kéo dài (YGTSS 4-5).
* Giật miệng khó phân biệt với nói chuyện thầm bằng một camera. Chỉ đếm khi KHÔNG phải giờ
  thảo luận nhóm, không phải ngáp (ngáp đã có engine riêng), và chuỗi phải ĐỀU (>=5 nhịp,
  khoảng cách trung vị 4-20s, hệ số biến thiên thấp). Đây là tín hiệu YẾU - giao diện phải
  nói rõ như vậy.
* Mốc thời gian DSM-5 (<4 tuần / 4 tuần-1 năm / >1 năm) chỉ đọc được từ LỊCH SỬ nhiều phiên
  trong SQLite: tic_history() đếm số NGÀY có mẫu hình trên tổng số ngày quan sát. Kéo dài
  trên 4 tuần và gần như mỗi ngày -> khuyến nghị đi khám chuyên khoa. Vẫn không chẩn đoán.

Không print, không import bên thứ ba. Sự kiện Tic đi vào notes JSON của phiên và luồng
khuyến nghị riêng của classroom.py - KHÔNG đếm vào alerts_total của chuỗi parity điện thoại.
"""
from __future__ import annotations

import math
import time
from collections import deque
from typing import Deque, Dict, List, Optional, Tuple

from .teacher_voice import TeacherContextAI

# --- hằng số mẫu hình, chú thích nguồn theo tài liệu 1 -----------------------
BLINK_MAX_MS = 1000        # mục I: một nhịp tíc đơn giản thường dưới 1 giây
BURST_GAP_MS = 1500        # các nháy cách nhau <=1.5s thuộc về MỘT chùm
BURST_MIN_BLINKS = 3       # >=3 nháy nhanh liên tiếp = một chùm (nháy thường rời rạc)
TRAIN_WINDOW_MS = 600000   # xét mẫu hình trong 10 phút gần nhất
TRAIN_MIN_BURSTS = 4       # >=4 chùm lặp lại mới thành mẫu hình
TRAIN_GAP_MIN_MS = 4000    # mục I: trung bình-nặng "cứ 5-15 giây lại giật 1 cái"
TRAIN_GAP_MAX_MS = 20000
DENSE_GAP_MS = 5000        # YGTSS mức 4: "mỗi vài giây"
DENSE_MIN_BURSTS = 6
DENSE_SUSTAIN_MS = 300000  # dày đặc kéo dài >=5 phút trong phiên -> khuyến nghị mạnh

MOUTH_MAR_HI = 0.25        # miệng mở (cùng ngưỡng nói chuyện của tài liệu 2)
MOUTH_MAR_LO = 0.15        # miệng khép lại
MOUTH_MAX_MS = 1000        # giật miệng: mở-khép dưới 1 giây (ngáp dài hơn nhiều)
MOUTH_MIN_JERKS = 5
MOUTH_GAP_MIN_MS = 4000
MOUTH_GAP_MAX_MS = 20000
MOUTH_MAX_CV = 0.60        # chuỗi phải ĐỀU mới tính (nói chuyện thất thường hơn)

# Mốc DSM-5 (mục I bảng tóm tắt) - thông tin tham khảo, KHÔNG phải ngưỡng chẩn đoán của app.
DURATION_MILESTONES = [
    {"range": "Dưới 4 tuần", "meaning": "Co giật cơ sinh lý tạm thời - thường do thiếu ngủ, mỏi mắt, căng thẳng; có thể tự hết khi nghỉ ngơi."},
    {"range": "Từ 4 tuần đến dưới 1 năm", "meaning": "Khoảng theo dõi rối loạn tíc nhất thời (Provisional Tic Disorder) - cần theo dõi sát tần suất và yếu tố kích phát."},
    {"range": "Trên 1 năm, gần như mỗi ngày", "meaning": "Khoảng rối loạn tíc mạn tính / Hội chứng Tourette - chỉ chuyên khoa mới kết luận được."},
]

DISCLAIMER = ("Đây KHÔNG phải chẩn đoán y khoa. Camera chỉ ghi nhận mẫu hình cử động lặp lại; "
              "chẩn đoán rối loạn tíc thuộc về chuyên khoa Thần kinh / Tâm thần nhi. Số liệu "
              "chỉ để hỗ trợ giáo viên và phụ huynh theo dõi, không dùng làm căn cứ kỷ luật.")

REFERENCES = [
    {"title": "DSM-5: Tourette's disorder and other tic disorders (PMC3038221)",
     "url": "https://pmc.ncbi.nlm.nih.gov/articles/PMC3038221/"},
    {"title": "Bảng phân loại rối loạn tíc theo DSM-5 (PMC6690237)",
     "url": "https://pmc.ncbi.nlm.nih.gov/articles/PMC6690237/"},
    {"title": "Thang mức độ nghiêm trọng tíc toàn cầu Yale YGTSS (PMC10687404)",
     "url": "https://pmc.ncbi.nlm.nih.gov/articles/PMC10687404/"},
    {"title": "BV Nhi Trung ương: Hiểu đúng về rối loạn Tic",
     "url": "https://benhviennhitrunguong.gov.vn/hieu-dung-cham-tot-hieu-dung-ve-roi-loan-tic-dau-hieu-nguyen-nhan-va-khi-nao-can-can-thiep.html"},
]

LEVEL_LABEL = {
    0: "",
    1: "Dấu hiệu giật lặp lại - nên theo dõi",
    2: "Dấu hiệu giật lặp lại dày đặc / kéo dài - nên trao đổi với phụ huynh và cân nhắc khám chuyên khoa",
}


def _median(vals: List[float]) -> float:
    s = sorted(vals)
    n = len(s)
    if n == 0:
        return 0.0
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def _cv(vals: List[float]) -> float:
    """Hệ số biến thiên = std/mean: chuỗi đều đặn (tic) thấp, thất thường (nói chuyện) cao."""
    if len(vals) < 2:
        return 0.0
    m = sum(vals) / len(vals)
    if m <= 0:
        return 0.0
    var = sum((v - m) ** 2 for v in vals) / len(vals)
    return math.sqrt(var) / m


def _gaps(ts: List[int]) -> List[float]:
    return [float(b - a) for a, b in zip(ts, ts[1:])]


class TicWatch:
    """Một bộ theo dõi cho MỘT em. update() mỗi khung hình đo được của track."""

    def __init__(self) -> None:
        # mắt: chùm nháy
        self._cluster: List[Tuple[int, int]] = []   # (ts, dur_ms) của chùm đang mở
        self._last_blink_ts = 0
        self._last_blink_seen_ms = 0
        self._bursts: Deque[int] = deque(maxlen=256)  # ts bắt đầu mỗi chùm
        # miệng: máy trạng thái mở-khép
        self._mouth_open_ms = 0
        self._mouth_was_yawn = False
        self._jerks: Deque[int] = deque(maxlen=256)
        # tổng kết
        self.observed_ms = 0
        self._last_update_ms = 0
        self.level = 0
        self.eye_bursts = 0
        self.mouth_jerks = 0
        self.dense = False
        self.eye_pattern = False
        self.mouth_pattern = False

    # ------------------------------------------------------------------
    def _prune(self, now_ms: int) -> None:
        while self._bursts and now_ms - self._bursts[0] > TRAIN_WINDOW_MS:
            self._bursts.popleft()
        while self._jerks and now_ms - self._jerks[0] > TRAIN_WINDOW_MS:
            self._jerks.popleft()

    def _close_cluster(self) -> None:
        if len(self._cluster) >= BURST_MIN_BLINKS:
            self._bursts.append(self._cluster[0][0])
        self._cluster = []

    def _feed_blinks(self, blinks: List[Tuple[int, int]]) -> None:
        for ts, dur in blinks:
            if ts <= self._last_blink_seen_ms:
                continue
            self._last_blink_seen_ms = ts
            if dur >= BLINK_MAX_MS:
                # nhắm mắt dài (ngủ gật/mỏi) không phải nhịp tíc đơn giản <1s
                self._close_cluster()
                continue
            if self._cluster and ts - self._last_blink_ts > BURST_GAP_MS:
                self._close_cluster()
            self._cluster.append((ts, dur))
            self._last_blink_ts = ts
        # chùm đang mở chưa đóng: chỉ đóng khi đã quá khoảng cách (nhánh trên xử lý
        # khi nháy kế tiếp tới); không đóng ở đây để chuỗi liên tục còn nối được.

    def _feed_mouth(self, now_ms: int, mar: float, yawn_confirmed: bool, context: str) -> None:
        if context == TeacherContextAI.CONTEXT_GROUP_WORK:
            # giờ thảo luận: miệng mở là NÓI CHUYỆN, không phải tín hiệu giật.
            self._mouth_open_ms = 0
            self._mouth_was_yawn = False
            return
        if mar > MOUTH_MAR_HI:
            if self._mouth_open_ms == 0:
                self._mouth_open_ms = now_ms
                self._mouth_was_yawn = False
            if yawn_confirmed:
                self._mouth_was_yawn = True
        elif mar < MOUTH_MAR_LO and self._mouth_open_ms:
            dur = now_ms - self._mouth_open_ms
            if dur < MOUTH_MAX_MS and not self._mouth_was_yawn:
                self._jerks.append(now_ms)
            self._mouth_open_ms = 0
            self._mouth_was_yawn = False

    def _patterns(self, now_ms: int) -> None:
        self._prune(now_ms)
        bursts = list(self._bursts)
        self.eye_bursts = len(bursts)
        self.eye_pattern = False
        self.dense = False
        if len(bursts) >= TRAIN_MIN_BURSTS:
            g = _gaps(bursts)
            med = _median(g)
            if TRAIN_GAP_MIN_MS <= med <= TRAIN_GAP_MAX_MS:
                self.eye_pattern = True
            if (len(bursts) >= DENSE_MIN_BURSTS and med <= DENSE_GAP_MS
                    and bursts[-1] - bursts[0] >= DENSE_SUSTAIN_MS):
                self.dense = True
        jerks = list(self._jerks)
        self.mouth_jerks = len(jerks)
        self.mouth_pattern = False
        if len(jerks) >= MOUTH_MIN_JERKS:
            g = _gaps(jerks)
            med = _median(g)
            if MOUTH_GAP_MIN_MS <= med <= MOUTH_GAP_MAX_MS and _cv(g) <= MOUTH_MAX_CV:
                self.mouth_pattern = True
        prev = self.level
        self.level = 2 if self.dense else (1 if (self.eye_pattern or self.mouth_pattern) else 0)
        self._level_rose = self.level > prev

    # ------------------------------------------------------------------
    def update(self, now_ms: int, blinks: List[Tuple[int, int]], mar: Optional[float],
               yawn_confirmed: bool, context: str, measurable: bool) -> Dict:
        dt = 0
        if self._last_update_ms and now_ms > self._last_update_ms:
            dt = min(5000, now_ms - self._last_update_ms)
        self._last_update_ms = now_ms
        if measurable:
            self.observed_ms += dt
            self._feed_blinks(blinks)
            self._feed_mouth(now_ms, float(mar) if mar is not None and math.isfinite(mar) else 0.0,
                             yawn_confirmed, context)
        self._patterns(now_ms)
        return self.view()

    @property
    def level_rose(self) -> bool:
        """True ở nhịp mức khuyến nghị TĂNG - classroom dùng để phát một sự kiện, không phát lại mỗi khung."""
        return getattr(self, "_level_rose", False)

    @property
    def last_blink_seen_ms(self) -> int:
        """Mốc nháy mắt đã xử lý - classroom đọc để chỉ chuyển tiếp nhịp MỚI từ PersonPipeline."""
        return self._last_blink_seen_ms

    def view(self) -> Dict:
        return {
            "level": self.level,
            "label": LEVEL_LABEL[self.level],
            "eye_bursts": self.eye_bursts,
            "mouth_jerks": self.mouth_jerks,
            "eye_pattern": self.eye_pattern,
            "mouth_pattern": self.mouth_pattern,
            "mouth_is_weak_signal": True,
            "dense": self.dense,
            "observed_s": round(self.observed_ms / 1000.0, 1),
            "disclaimer": DISCLAIMER,
            "references": REFERENCES if self.level >= 1 else [],
        }

    def session_summary(self) -> Optional[Dict]:
        """Ghi vào observation_sessions.notes để tic_history() đọc lại theo nhiều tuần.

        Luôn ghi khi có quan sát (kể cả level 0): ngày quan sát mà không có mẫu hình
        là mẫu số của tỉ lệ "gần như mỗi ngày" - thiếu nó thì không phân biệt được
        'theo dõi 4 tuần đều đặn' với 'thỉnh thoảng mới đo'.
        """
        if self.observed_ms <= 0:
            return None
        return {
            "level": self.level,
            "eye_bursts": self.eye_bursts,
            "mouth_jerks": self.mouth_jerks,
            "dense": self.dense,
            "observed_s": round(self.observed_ms / 1000.0, 1),
        }


# ---------------------------------------------------------------------------
# Đọc dọc nhiều phiên: mốc 4 tuần của DSM-5 chỉ tồn tại trong lịch sử, không
# tồn tại trong một phiên. Hàm này chạy phía dashboard (conn tới cùng file SQLite).
# ---------------------------------------------------------------------------
def tic_history(conn, student_id: str) -> Dict:
    """Tổng hợp dấu hiệu tic của một em qua các phiên đã lưu (notes JSON).

    Khuyến nghị đi khám (level 2) khi mẫu hình xuất hiện GẦN NHƯ MỖI NGÀY
    (>=50% số ngày có quan sát, tối thiểu 10 ngày) và KÉO DÀI TRÊN 4 TUẦN
    (>=28 ngày giữa phiên đầu và phiên cuối có quan sát) - đúng hai điều kiện
    tài liệu 1 nhấn mạnh. Dưới các mốc đó: level 1 nếu từng có mẫu hình, else 0.
    """
    import datetime
    import json
    out = {
        "student_id": student_id, "level": 0, "label": "",
        "sessions_observed": 0, "days_observed": 0, "days_with_pattern": 0,
        "span_days": 0, "recommend_exam": False,
        "note": "", "disclaimer": DISCLAIMER, "references": REFERENCES,
        "milestones": DURATION_MILESTONES, "days": [],
    }
    if not student_id:
        return out
    try:
        rows = conn.execute(
            "SELECT started_at_wall_ms, notes FROM observation_sessions"
            " WHERE student_id = ? AND source = 'edge-camera' ORDER BY started_at_wall_ms",
            (student_id,)).fetchall()
    except Exception:
        return out
    observed_days = set()
    pattern_days = set()
    day_level: Dict[str, int] = {}
    first_ms = last_ms = None
    for r in rows:
        try:
            meta = json.loads(r["notes"] or "{}")
        except (ValueError, TypeError):
            continue
        tw = meta.get("tic_watch")
        if not isinstance(tw, dict):
            continue
        ms = int(r["started_at_wall_ms"] or 0)
        day = time.strftime("%Y-%m-%d", time.localtime(ms / 1000.0))
        observed_days.add(day)
        first_ms = ms if first_ms is None else min(first_ms, ms)
        last_ms = ms if last_ms is None else max(last_ms, ms)
        lv = int(tw.get("level") or 0)
        day_level[day] = max(day_level.get(day, 0), lv)
        if lv >= 1:
            pattern_days.add(day)
        out["sessions_observed"] += 1
    out["days_observed"] = len(observed_days)
    out["days_with_pattern"] = len(pattern_days)
    if first_ms is not None and last_ms is not None:
        d0 = datetime.date.fromtimestamp(first_ms / 1000.0)
        d1 = datetime.date.fromtimestamp(last_ms / 1000.0)
        out["span_days"] = (d1 - d0).days + 1
    out["days"] = [{"day": d, "level": day_level.get(d, 0)} for d in sorted(observed_days)]
    ratio = (len(pattern_days) / len(observed_days)) if observed_days else 0.0
    recommend = (out["span_days"] >= 28 and len(pattern_days) >= 10 and ratio >= 0.5)
    out["recommend_exam"] = recommend
    out["level"] = 2 if recommend else (1 if pattern_days else 0)
    out["label"] = LEVEL_LABEL[out["level"]]
    if recommend:
        out["note"] = ("Mẫu hình giật lặp lại xuất hiện gần như mỗi ngày và kéo dài trên 4 tuần. "
                       "Theo tài liệu DSM-5, đây là lúc nên đưa em tới chuyên khoa Thần kinh hoặc "
                       "Tâm thần nhi để được đánh giá trực tiếp. Camera không chẩn đoán.")
    elif pattern_days:
        out["note"] = ("Đã ghi nhận mẫu hình giật lặp lại ở %d ngày quan sát. Tiếp tục theo dõi; "
                       "nếu kéo dài trên 4 tuần hoặc dày lên khi căng thẳng/mệt mỏi, nên trao đổi "
                       "với phụ huynh và cân nhắc đi khám." % len(pattern_days))
    return out