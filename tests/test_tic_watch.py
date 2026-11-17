"""Kiểm thử lớp theo dõi dấu hiệu Tic (focusedge/tic_watch.py) - tài liệu 1.

Thời gian truyền thẳng bằng now_ms, không sleep. Hai ranh giới phải được khoá bằng test:
  1. Nháy mắt THƯỜNG (rời rạc, hoặc nhắm dài) không bao giờ thành mẫu hình tic.
  2. Đầu ra không bao giờ là chẩn đoán: level chỉ là khuyến nghị, disclaimer luôn có mặt,
     và mốc 4 tuần chỉ kết luận từ lịch sử nhiều ngày (tic_history), không từ một phiên.
"""
import json
import os
import sqlite3
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.teacher_voice import TeacherContextAI as TV
from focusedge.tic_watch import (
    DISCLAIMER, DURATION_MILESTONES, REFERENCES, TicWatch, tic_history,
)

LECTURE = TV.CONTEXT_LECTURE
GROUP = TV.CONTEXT_GROUP_WORK


def burst_blinks(starts, inner_gap=400, dur=200, closer=None):
    """Danh sách (ts, dur) mô phỏng các chùm nháy: mỗi chùm 3 nháy cách nhau inner_gap.

    closer: một nháy rời sau chùm cuối để ĐÓNG chùm đang mở (bộ đếm chỉ chốt chùm khi
    nháy kế tiếp tới quá khoảng cách - đúng cách luồng thật đọc từng nhịp mới).
    """
    out = []
    for s in starts:
        out.extend([(s, dur), (s + inner_gap, dur), (s + 2 * inner_gap, dur)])
    if closer is not None:
        out.append((closer, dur))
    return out


class TestEyePatterns(unittest.TestCase):
    def test_isolated_normal_blinks_are_level_0(self):
        tw = TicWatch()
        v = tw.update(24000, [(1000, 200), (6000, 200), (12000, 200),
                              (18000, 200), (24000, 200)], 0.05, False, LECTURE, True)
        self.assertEqual(v["level"], 0)
        self.assertEqual(v["eye_bursts"], 0)
        self.assertEqual(v["references"], [])  # chưa có gì đáng khuyến nghị thì không hù doạ

    def test_single_burst_is_not_a_pattern(self):
        tw = TicWatch()
        v = tw.update(10000, burst_blinks([1000], closer=10000), 0.05, False, LECTURE, True)
        self.assertEqual(v["eye_bursts"], 1)
        self.assertEqual(v["level"], 0)  # một chùm đơn lẻ chưa phải mẫu hình lặp lại

    def test_regular_train_of_4_bursts_is_level_1(self):
        tw = TicWatch()
        blinks = burst_blinks([1000, 11000, 21000, 31000], closer=41000)
        v = tw.update(41000, blinks, 0.05, False, LECTURE, True)
        self.assertEqual(v["eye_bursts"], 4)
        self.assertTrue(v["eye_pattern"])
        self.assertEqual(v["level"], 1)
        self.assertFalse(v["dense"])
        self.assertIn("theo dõi", v["label"])
        self.assertEqual(len(v["references"]), 4)
        self.assertEqual(tw.last_blink_seen_ms, 41000)

    def test_long_closed_eyes_break_the_cluster(self):
        # Nhắm mắt >= 1 giây là ngủ gật / mỏi mắt, không phải nhịp tíc < 1s của tài liệu 1.
        tw = TicWatch()
        blinks = [(1000, 200), (1400, 200), (1800, 1200),
                  (2200, 200), (2600, 200), (10000, 200)]
        v = tw.update(10000, blinks, 0.05, False, LECTURE, True)
        self.assertEqual(v["eye_bursts"], 0)
        self.assertEqual(v["level"], 0)

    def test_dense_sustained_train_is_level_2(self):
        # 80 chùm, mỗi 4 giây một chùm, kéo dài hơn 5 phút -> YGTSS mức dày đặc.
        tw = TicWatch()
        starts = [1000 + i * 4000 for i in range(80)]
        v = tw.update(321000, burst_blinks(starts, inner_gap=300, closer=321000),
                      0.05, False, LECTURE, True)
        self.assertEqual(v["eye_bursts"], 80)
        self.assertTrue(v["dense"])
        self.assertEqual(v["level"], 2)
        self.assertIn("chuyên khoa", v["label"])

    def test_dense_but_short_is_only_level_1(self):
        # 6 chùm mỗi 4s nhưng mới 20 giây: dày mà chưa kéo dài >= 5 phút -> chưa mức 2.
        tw = TicWatch()
        starts = [1000 + i * 4000 for i in range(6)]
        v = tw.update(25000, burst_blinks(starts, inner_gap=300, closer=25000),
                      0.05, False, LECTURE, True)
        self.assertFalse(v["dense"])
        self.assertTrue(v["eye_pattern"])
        self.assertEqual(v["level"], 1)

    def test_level_rose_fires_once_per_increase(self):
        tw = TicWatch()
        tw.update(41000, burst_blinks([1000, 11000, 21000, 31000], closer=41000),
                  0.05, False, LECTURE, True)
        self.assertTrue(tw.level_rose)
        tw.update(42000, [], 0.05, False, LECTURE, True)
        self.assertFalse(tw.level_rose)  # mức giữ nguyên: không phát lại sự kiện mỗi khung


def mouth_feed(tw, jerk_times, context=LECTURE, yawn=False, open_ms=400):
    """Mỗi nhịp: miệng mở (MAR 0.3) rồi khép (MAR 0.05) sau open_ms."""
    v = None
    for t in jerk_times:
        v = tw.update(t - open_ms, [], 0.3, yawn, context, True)
        v = tw.update(t, [], 0.05, False, context, True)
    return v


class TestMouthPatterns(unittest.TestCase):
    def test_regular_jerks_are_level_1_weak_signal(self):
        tw = TicWatch()
        v = mouth_feed(tw, [1400 + i * 10000 for i in range(6)])
        self.assertEqual(v["mouth_jerks"], 6)
        self.assertTrue(v["mouth_pattern"])
        self.assertEqual(v["level"], 1)
        self.assertTrue(v["mouth_is_weak_signal"])  # không phân biệt được nói thầm: phải nói rõ

    def test_yawn_is_not_a_jerk(self):
        tw = TicWatch()
        v = mouth_feed(tw, [1400 + i * 10000 for i in range(6)], yawn=True)
        self.assertEqual(v["mouth_jerks"], 0)
        self.assertEqual(v["level"], 0)

    def test_group_work_talking_is_not_a_jerk(self):
        tw = TicWatch()
        v = mouth_feed(tw, [1400 + i * 10000 for i in range(6)], context=GROUP)
        self.assertEqual(v["mouth_jerks"], 0)
        self.assertEqual(v["level"], 0)

    def test_slow_open_close_is_not_a_jerk(self):
        # Mở-khép 3 giây là ngáp / nói dài, không phải giật < 1 giây.
        tw = TicWatch()
        v = mouth_feed(tw, [4000 + i * 10000 for i in range(6)], open_ms=3000)
        self.assertEqual(v["mouth_jerks"], 0)

    def test_irregular_jerks_are_not_a_pattern(self):
        # Khoảng cách thất thường (CV > 0.6): giống nói chuyện hơn là tic đều đặn.
        tw = TicWatch()
        ts = [1400]
        for g in (5000, 19000, 5000, 19000, 5000):
            ts.append(ts[-1] + g)
        v = mouth_feed(tw, ts)
        self.assertEqual(v["mouth_jerks"], 6)
        self.assertFalse(v["mouth_pattern"])
        self.assertEqual(v["level"], 0)


class TestHonestyGates(unittest.TestCase):
    def test_unmeasurable_face_observes_nothing(self):
        tw = TicWatch()
        v = tw.update(1000, [(1000, 200), (1400, 200), (1800, 200)], 0.3, False, LECTURE, False)
        self.assertEqual(v["observed_s"], 0.0)
        self.assertEqual(v["eye_bursts"], 0)
        self.assertIsNone(tw.session_summary())  # không quan sát thì không có gì để tổng kết

    def test_observed_time_and_summary(self):
        tw = TicWatch()
        tw.update(1000, [], 0.05, False, LECTURE, True)
        v = tw.update(2000, [], 0.05, False, LECTURE, True)
        self.assertEqual(v["observed_s"], 1.0)
        summ = tw.session_summary()
        self.assertIsNotNone(summ)  # level 0 VẪN ghi: mẫu số của "gần như mỗi ngày"
        self.assertEqual(summ["level"], 0)
        self.assertEqual(summ["observed_s"], 1.0)

    def test_disclaimer_always_present(self):
        tw = TicWatch()
        v = tw.update(1000, [], 0.05, False, LECTURE, True)
        self.assertEqual(v["disclaimer"], DISCLAIMER)
        self.assertIn("KHÔNG phải chẩn đoán", v["disclaimer"])


def _history_conn():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.execute("CREATE TABLE observation_sessions ("
                 "student_id TEXT, source TEXT, started_at_wall_ms INTEGER, notes TEXT)")
    return conn


def _insert_day(conn, day_index, level, sid="hs1", source="edge-camera", base=None):
    if base is None:
        base = int(time.mktime((2026, 10, 1, 12, 0, 0, 0, 0, -1)) * 1000)
    conn.execute("INSERT INTO observation_sessions VALUES (?, ?, ?, ?)",
                 (sid, source, base + day_index * 86400000,
                  json.dumps({"tic_watch": {"level": level}})))


class TestTicHistory(unittest.TestCase):
    def test_empty_student_returns_safe_defaults(self):
        out = tic_history(_history_conn(), "")
        self.assertEqual(out["level"], 0)
        self.assertFalse(out["recommend_exam"])
        self.assertEqual(out["disclaimer"], DISCLAIMER)
        self.assertEqual(len(out["references"]), 4)
        self.assertEqual(len(out["milestones"]), 3)

    def test_four_weeks_near_daily_recommends_exam(self):
        conn = _history_conn()
        for i in range(30):  # 30 ngày quan sát, 15 ngày đầu có mẫu hình (tỉ lệ 0.5)
            _insert_day(conn, i, 1 if i < 15 else 0)
        out = tic_history(conn, "hs1")
        self.assertEqual(out["days_observed"], 30)
        self.assertEqual(out["days_with_pattern"], 15)
        self.assertEqual(out["span_days"], 30)
        self.assertTrue(out["recommend_exam"])
        self.assertEqual(out["level"], 2)
        self.assertIn("4 tuần", out["note"])
        self.assertIn("không chẩn đoán", out["note"].lower())

    def test_short_span_never_recommends_exam(self):
        conn = _history_conn()
        for i in range(10):  # ngày nào cũng có mẫu hình nhưng mới 10 ngày
            _insert_day(conn, i, 1)
        out = tic_history(conn, "hs1")
        self.assertFalse(out["recommend_exam"])
        self.assertEqual(out["level"], 1)
        self.assertEqual(out["span_days"], 10)

    def test_sparse_pattern_over_four_weeks_is_only_level_1(self):
        conn = _history_conn()
        for i in range(30):  # 30 ngày nhưng chỉ 5 ngày có mẫu hình (< 10, tỉ lệ < 0.5)
            _insert_day(conn, i, 1 if i % 6 == 0 else 0)
        out = tic_history(conn, "hs1")
        self.assertFalse(out["recommend_exam"])
        self.assertEqual(out["level"], 1)

    def test_level_zero_days_are_the_denominator(self):
        conn = _history_conn()
        for i in range(30):
            _insert_day(conn, i, 1 if i < 15 else 0)
        # Nếu các ngày level 0 KHÔNG được ghi thì tỉ lệ là 15/15 = 1.0 và mọi chuỗi
        # ngắn cũng thành "gần như mỗi ngày". Ở đây phải là 15/30.
        out = tic_history(conn, "hs1")
        self.assertEqual(out["days_observed"], 30)
        self.assertEqual(len(out["days"]), 30)

    def test_all_clean_history_is_level_0(self):
        conn = _history_conn()
        for i in range(5):
            _insert_day(conn, i, 0)
        out = tic_history(conn, "hs1")
        self.assertEqual(out["level"], 0)
        self.assertEqual(out["sessions_observed"], 5)
        self.assertEqual(out["days_with_pattern"], 0)
        self.assertEqual(out["note"], "")

    def test_sessions_without_tic_watch_are_skipped(self):
        conn = _history_conn()
        base = int(time.mktime((2026, 10, 1, 12, 0, 0, 0, 0, -1)) * 1000)
        conn.execute("INSERT INTO observation_sessions VALUES (?, ?, ?, ?)",
                     ("hs1", "edge-camera", base, json.dumps({"face_ratio": 0.9})))
        out = tic_history(conn, "hs1")
        self.assertEqual(out["sessions_observed"], 0)
        self.assertEqual(out["level"], 0)

    def test_other_instrument_rows_are_ignored(self):
        conn = _history_conn()
        for i in range(30):
            _insert_day(conn, i, 1, source="phone")
        out = tic_history(conn, "hs1")
        self.assertEqual(out["sessions_observed"], 0)

    def test_missing_table_degrades_gracefully(self):
        conn = sqlite3.connect(":memory:")
        conn.row_factory = sqlite3.Row
        out = tic_history(conn, "hs1")
        self.assertEqual(out["level"], 0)
        self.assertEqual(out["disclaimer"], DISCLAIMER)


if __name__ == "__main__":
    unittest.main()