"""Kiểm thử lớp đánh giá tập trung theo ngữ cảnh (focusedge/context_eval.py).

Nguồn tiêu chí: Huong_dan_AI_3_ngu_canh_va_hoat_dong_nhom_MediaPipe.docx (mục III, IV)
và Thiet_ke_AI_nhan_dien_tap_trung_theo_ngu_canh_GV.docx (bảng 5 ngữ cảnh, mục I).
Thời gian truyền thẳng bằng now_ms - không sleep, không phụ thuộc đồng hồ thật.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.context_eval import (
    CRITERIA, STATE_NEUTRAL, STATE_ON_TASK, STATE_WATCH, STATE_OFF_TASK,
    ContextEvaluator,
)
from focusedge.teacher_voice import TeacherContextAI as TV


def feed(ev, t0, t1, context, yaw=0.0, pitch=0.0, mar=0.05, step_ms=1000,
         measurable=True, eyes_open=None, nearest=None):
    """Cho evaluator chạy từ t0 đến t1 (bao gồm t1) với thông số cố định."""
    t = t0
    while t <= t1:
        ev.update(t, context, measurable, yaw, pitch, mar,
                  eyes_open=eyes_open, nearest_cm=nearest)
        t += step_ms
    return ev.view()


class TestLecture(unittest.TestCase):
    def test_frontal_is_on_task(self):
        ev = ContextEvaluator()
        v = ev.update(1000, TV.CONTEXT_LECTURE, True, 5.0, 0.0, 0.05)
        self.assertEqual(v["state"], STATE_ON_TASK)
        self.assertEqual(v["reason"], "Đang nghe giảng")

    def test_look_away_watch_then_off_at_7s(self):
        ev = ContextEvaluator()
        v = feed(ev, 0, 6000, TV.CONTEXT_LECTURE, yaw=45.0)
        self.assertEqual(v["state"], STATE_WATCH)
        v = ev.update(8500, TV.CONTEXT_LECTURE, True, 45.0, 0.0, 0.05)
        self.assertEqual(v["state"], STATE_OFF_TASK)
        self.assertEqual(v["reason"], "Không nhìn lên bảng")
        self.assertEqual(v["episodes"], 1)

    def test_recovery_closes_episode_and_resets_clock(self):
        ev = ContextEvaluator()
        feed(ev, 0, 8000, TV.CONTEXT_LECTURE, yaw=45.0)
        v = ev.update(9000, TV.CONTEXT_LECTURE, True, 0.0, 0.0, 0.05)
        self.assertEqual(v["state"], STATE_ON_TASK)
        self.assertEqual(v["off_s"], 0.0)
        # lệch lại: đồng hồ liên tục bắt đầu lại từ đầu, episode mới
        feed(ev, 10000, 16000, TV.CONTEXT_LECTURE, yaw=45.0)
        v = ev.update(17500, TV.CONTEXT_LECTURE, True, 45.0, 0.0, 0.05)
        self.assertEqual(v["state"], STATE_OFF_TASK)
        self.assertEqual(v["episodes"], 2)


class TestStudyNoteAndSeatwork(unittest.TestCase):
    def test_head_down_is_on_task_not_off_task(self):
        ev = ContextEvaluator()
        v = ev.update(1000, TV.CONTEXT_STUDY_NOTE, True, 5.0, -25.0, 0.05)
        self.assertEqual(v["state"], STATE_ON_TASK)
        self.assertEqual(v["reason"], "Đang ghi/học bài")

    def test_window_is_15s_not_7s(self):
        # 8 giây quay ngang: LECTURE đã báo động, STUDY_NOTE vẫn đang theo dõi.
        ev = ContextEvaluator()
        v = feed(ev, 0, 8000, TV.CONTEXT_STUDY_NOTE, yaw=45.0)
        self.assertEqual(v["state"], STATE_WATCH)
        ev2 = ContextEvaluator()
        v2 = feed(ev2, 0, 8000, TV.CONTEXT_LECTURE, yaw=45.0)
        self.assertEqual(v2["state"], STATE_OFF_TASK)

    def test_seatwork_allows_thinking_looking_up(self):
        ev = ContextEvaluator()
        v = ev.update(1000, TV.CONTEXT_SEATWORK, True, 5.0, 10.0, 0.05)
        self.assertEqual(v["state"], STATE_ON_TASK)
        v = feed(ev, 2000, 22000, TV.CONTEXT_SEATWORK, yaw=60.0)
        self.assertEqual(v["state"], STATE_OFF_TASK)  # window 20s

    def test_q_and_a_frontal_on_task(self):
        ev = ContextEvaluator()
        v = ev.update(1000, TV.CONTEXT_Q_AND_A, True, 10.0, 5.0, 0.05)
        self.assertEqual(v["state"], STATE_ON_TASK)
        self.assertIn("hướng về GV", v["reason"])


class TestSlouchGate(unittest.TestCase):
    def test_slouch_overrides_every_context(self):
        for ctx in (TV.CONTEXT_LECTURE, TV.CONTEXT_STUDY_NOTE, TV.CONTEXT_SEATWORK,
                    TV.CONTEXT_GROUP_WORK, TV.CONTEXT_Q_AND_A):
            ev = ContextEvaluator()
            v = ev.update(1000, ctx, True, 0.0, -50.0, 0.3)
            self.assertEqual(v["reason"], "Gục mặt / tư thế xấu", ctx)
            self.assertNotEqual(v["state"], STATE_ON_TASK, ctx)


class TestGroupWork(unittest.TestCase):
    def test_facing_and_talking_is_on_task(self):
        ev = ContextEvaluator()
        v = ev.update(300000, TV.CONTEXT_GROUP_WORK, True, 35.0, -5.0, 0.3)
        self.assertEqual(v["state"], STATE_ON_TASK)
        self.assertEqual(v["reason"], "Tích cực hoạt động nhóm")
        self.assertTrue(v["talk_recent"])
        self.assertTrue(v["facing_group"])

    def test_talk_window_15s_then_passive_off_at_10s(self):
        ev = ContextEvaluator()
        ev.update(300000, TV.CONTEXT_GROUP_WORK, True, 35.0, -5.0, 0.3)
        # im miệng: 15s sau hết cửa sổ trao đổi, rồi 10s window nữa mới báo động
        v = feed(ev, 301000, 316000, TV.CONTEXT_GROUP_WORK, yaw=35.0, pitch=-5.0)
        self.assertFalse(v["talk_recent"])
        self.assertEqual(v["state"], STATE_WATCH)
        v = feed(ev, 317000, 327000, TV.CONTEXT_GROUP_WORK, yaw=35.0, pitch=-5.0)
        self.assertEqual(v["state"], STATE_OFF_TASK)
        self.assertEqual(v["reason"], "Thụ động / không trao đổi")

    def test_talking_but_not_facing_is_off(self):
        ev = ContextEvaluator()
        v = feed(ev, 300000, 311000, TV.CONTEXT_GROUP_WORK, yaw=0.0, pitch=0.0, mar=0.3)
        self.assertEqual(v["state"], STATE_OFF_TASK)
        self.assertEqual(v["reason"], "Tách nhóm / nhìn chỗ khác")

    def test_closed_eyes_off_even_when_talking(self):
        ev = ContextEvaluator()
        v = ev.update(300000, TV.CONTEXT_GROUP_WORK, True, 35.0, -5.0, 0.3,
                      eyes_open=False)
        self.assertEqual(v["reason"], "Nhắm mắt trong giờ nhóm")
        # không đọc được mắt (None) thì KHÔNG được kết luận nhắm mắt
        v = ev.update(301000, TV.CONTEXT_GROUP_WORK, True, 35.0, -5.0, 0.3,
                      eyes_open=None)
        self.assertEqual(v["state"], STATE_ON_TASK)

    def test_nod_counts_as_interaction_without_talk(self):
        ev = ContextEvaluator()
        # gật nhẹ: pitch nhấp nhô biên 8 độ, >=2 lần đổi chiều trong 4 giây
        for i, p in enumerate([-1.0, -9.0, -1.0, -9.0, -1.0]):
            v = ev.update(300000 + i * 800, TV.CONTEXT_GROUP_WORK, True,
                          35.0, p, 0.05)
        self.assertEqual(v["state"], STATE_ON_TASK)
        self.assertIn("gật đầu tương tác", v["evidence"])

    def test_looking_at_shared_sheet_is_facing(self):
        ev = ContextEvaluator()
        v = ev.update(300000, TV.CONTEXT_GROUP_WORK, True, 0.0, -25.0, 0.3)
        self.assertTrue(v["facing_group"])  # cùng cúi nhìn phiếu chung (-40..-12)
        self.assertEqual(v["state"], STATE_ON_TASK)

    def test_distance_shrink_is_soft_evidence(self):
        ev = ContextEvaluator()
        # baseline học lúc nghe giảng: hai mặt cách nhau 100px
        feed(ev, 0, 20000, TV.CONTEXT_LECTURE, nearest=100.0)
        # giờ nhóm xích lại 80px (<90% baseline) -> tín hiệu mềm, không phải cổng
        v = ev.update(21000, TV.CONTEXT_GROUP_WORK, True, 35.0, -5.0, 0.3,
                      nearest_cm=80.0)
        self.assertTrue(v["group_close"])
        self.assertIn("xích lại gần bạn (tín hiệu mềm)", v["evidence"])
        # không xích gần vẫn on-task nếu đủ tiêu chí cứng
        v = ev.update(22000, TV.CONTEXT_GROUP_WORK, True, 35.0, -5.0, 0.3,
                      nearest_cm=100.0)
        self.assertFalse(v["group_close"])
        self.assertEqual(v["state"], STATE_ON_TASK)


class TestNeutralAndAccumulation(unittest.TestCase):
    def test_unknown_context_is_neutral(self):
        ev = ContextEvaluator()
        v = feed(ev, 0, 30000, TV.CONTEXT_UNKNOWN, yaw=60.0, pitch=-50.0)
        self.assertEqual(v["state"], STATE_NEUTRAL)
        self.assertEqual(v["off_total_s"], 0.0)
        self.assertEqual(v["episodes"], 0)
        self.assertIsNone(ev.session_summary())

    def test_unmeasurable_face_is_neutral_and_stops_clock(self):
        ev = ContextEvaluator()
        feed(ev, 0, 8000, TV.CONTEXT_LECTURE, yaw=45.0)
        self.assertEqual(ev.state, STATE_OFF_TASK)
        total_before = ev.off_task_total_ms
        v = ev.update(9000, TV.CONTEXT_LECTURE, False, None, None, None)
        self.assertEqual(v["state"], STATE_NEUTRAL)
        self.assertEqual(ev.off_task_total_ms, total_before)  # không tích luỹ khi mất mặt
        # đo lại: đồng hồ liên tục bắt đầu lại, không kế thừa 8 giây cũ
        v = ev.update(10000, TV.CONTEXT_LECTURE, True, 45.0, 0.0, 0.05)
        self.assertEqual(v["state"], STATE_WATCH)

    def test_micro_bouts_accumulate_to_severe_at_10_min(self):
        ev = ContextEvaluator()
        # lệch liên tục 10 phút (mỗi nhịp 1s) -> cộng dồn >= 600s = nặng
        feed(ev, 0, 601000, TV.CONTEXT_LECTURE, yaw=45.0, step_ms=1000)
        v = ev.view()
        self.assertGreaterEqual(v["off_total_s"], 600.0)
        self.assertEqual(v["severity"], "nang")

    def test_medium_at_3_min_continuous(self):
        ev = ContextEvaluator()
        v = feed(ev, 0, 120000, TV.CONTEXT_LECTURE, yaw=45.0)
        self.assertEqual(v["severity"], "")
        v = feed(ev, 121000, 181000, TV.CONTEXT_LECTURE, yaw=45.0)
        self.assertEqual(v["severity"], "trung_binh")

    def test_context_switch_keeps_total_resets_continuous(self):
        ev = ContextEvaluator()
        feed(ev, 0, 8000, TV.CONTEXT_LECTURE, yaw=45.0)
        total = ev.off_task_total_ms
        self.assertEqual(len(ev.episodes), 1)
        v = ev.update(9000, TV.CONTEXT_STUDY_NOTE, True, 5.0, -25.0, 0.05)
        self.assertEqual(v["state"], STATE_ON_TASK)
        self.assertEqual(ev.off_task_total_ms, total)
        self.assertEqual(v["off_s"], 0.0)
        summ = ev.session_summary()
        self.assertEqual(summ["contexts_used"], [TV.CONTEXT_LECTURE, TV.CONTEXT_STUDY_NOTE])
        self.assertEqual(summ["episodes"], 1)
        self.assertEqual(len(summ["episode_list"]), 1)
        self.assertGreater(summ["episode_list"][0]["duration_s"], 0.0)

    def test_view_carries_vietnamese_labels(self):
        ev = ContextEvaluator()
        v = ev.update(1000, TV.CONTEXT_GROUP_WORK, True, 35.0, -5.0, 0.3)
        self.assertEqual(v["context_vi"], "thảo luận nhóm")
        self.assertIn("tập trung", v["state_vi"])
        self.assertEqual(v["window_s"], CRITERIA[TV.CONTEXT_GROUP_WORK]["window_s"])


if __name__ == "__main__":
    unittest.main()