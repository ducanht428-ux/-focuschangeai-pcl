"""Test suite for Teacher Voice Context & Multimodal Attention Evaluator."""
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.teacher_voice import (
    TeacherContextAI,
    StudentAttentionEvaluator,
    calculate_mar,
)


class TestTeacherVoice(unittest.TestCase):

    def setUp(self):
        self.ai = TeacherContextAI(auto_reset_seconds=2)
        self.evaluator = StudentAttentionEvaluator(violation_threshold_s=7.0)

    def test_keywords_group_work(self):
        # Test câu yêu cầu của người dùng
        self.assertEqual(self.ai.detect_context("bắt đầu thảo luận"), "GROUP_WORK")
        self.assertEqual(self.ai.detect_context("cả lớp bắt đầu thảo luận nhóm"), "GROUP_WORK")
        self.assertEqual(self.ai.detect_context("các em thảo luận nhóm đôi"), "GROUP_WORK")
        self.assertEqual(self.ai.detect_context("trao đổi với bạn bên cạnh"), "GROUP_WORK")

    def test_keywords_lecture(self):
        # Test câu yêu cầu của người dùng
        self.assertEqual(self.ai.detect_context("cả lớp chú ý"), "LECTURE")
        self.assertEqual(self.ai.detect_context("hết giờ thảo luận"), "LECTURE")
        self.assertEqual(self.ai.detect_context("nhìn lên bảng nào"), "LECTURE")
        self.assertEqual(self.ai.detect_context("nghe giảng nhé"), "LECTURE")

    def test_keywords_study_note(self):
        self.assertEqual(self.ai.detect_context("các em ghi bài vào vở"), "STUDY_NOTE")
        self.assertEqual(self.ai.detect_context("chép bài đi"), "STUDY_NOTE")
        self.assertEqual(self.ai.detect_context("mở sách làm bài tập"), "STUDY_NOTE")

    def test_auto_reset_timeout(self):
        self.ai.set_context(TeacherContextAI.CONTEXT_GROUP_WORK)
        status = self.ai.get_status()
        self.assertEqual(status["context"], "GROUP_WORK")

        # Sau 2.2 giây (lớn hơn auto_reset_seconds=2), phải tự về LECTURE
        time.sleep(2.2)
        status = self.ai.get_status()
        self.assertEqual(status["context"], "LECTURE")

    def test_evaluator_lecture_context(self):
        # Trong LECTURE: nhìn bảng (yaw=0, pitch=0) là TẬP TRUNG
        status, reason, _ = self.evaluator.evaluate(yaw=5, pitch=0, mar=0.05, is_slouching=False,
                                                    context=TeacherContextAI.CONTEXT_LECTURE, now=100.0)
        self.assertEqual(status, "TAP TRUNG")
        self.assertEqual(reason, "Đang nghe giảng")

        # Nhìn sang bên (yaw=45) trong < 7s -> Đang theo dõi (chưa báo động giả)
        status, _, _ = self.evaluator.evaluate(yaw=45, pitch=0, mar=0.05, is_slouching=False,
                                               context=TeacherContextAI.CONTEXT_LECTURE, now=101.0)
        self.assertIn("Dang theo doi", status)

        # Nhìn sang bên liên tục >= 7s -> Báo MẤT TẬP TRUNG
        status, reason, _ = self.evaluator.evaluate(yaw=45, pitch=0, mar=0.05, is_slouching=False,
                                                    context=TeacherContextAI.CONTEXT_LECTURE, now=108.5)
        self.assertIn("MAT TAP TRUNG", status)
        self.assertEqual(reason, "Không nhìn lên bảng")

    def test_evaluator_study_note_context(self):
        # Trong STUDY_NOTE: cúi đầu viết bài (pitch=-25, yaw=5) là TẬP TRUNG (không bị phạt)
        status, reason, _ = self.evaluator.evaluate(yaw=5, pitch=-25, mar=0.05, is_slouching=False,
                                                    context=TeacherContextAI.CONTEXT_STUDY_NOTE, now=200.0)
        self.assertEqual(status, "TAP TRUNG")
        self.assertEqual(reason, "Đang ghi/học bài")

    def test_evaluator_group_work_context(self):
        # Trong GROUP_WORK: quay sang bạn (yaw=35) và có nói chuyện (mar=0.25) -> TẬP TRUNG
        status, reason, _ = self.evaluator.evaluate(yaw=35, pitch=-15, mar=0.25, is_slouching=False,
                                                    context=TeacherContextAI.CONTEXT_GROUP_WORK, now=300.0)
        self.assertEqual(status, "TAP TRUNG")
        self.assertEqual(reason, "Tích cực hoạt động nhóm")

    def test_calculate_mar(self):
        # Ngậm miệng: khoảng cách môi bé
        mar_closed = calculate_mar(top_y=10.0, bottom_y=11.0, left_x=5.0, right_x=25.0)
        self.assertAlmostEqual(mar_closed, 1.0 / 20.0, places=3)

        # Mở miệng nói: khoảng cách môi lớn
        mar_open = calculate_mar(top_y=10.0, bottom_y=16.0, left_x=5.0, right_x=25.0)
        self.assertAlmostEqual(mar_open, 6.0 / 20.0, places=3)
        self.assertTrue(mar_open > 0.22)


if __name__ == "__main__":
    unittest.main()
