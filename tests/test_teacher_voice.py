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

    def test_keywords_seatwork(self):
        # Tài liệu 3 tách SEATWORK khỏi STUDY_NOTE: làm bài cá nhân / suy nghĩ.
        self.assertEqual(self.ai.detect_context("các em tự làm bài nhé"), "SEATWORK")
        self.assertEqual(self.ai.detect_context("cả lớp tự học"), "SEATWORK")
        self.assertEqual(self.ai.detect_context("bây giờ làm bài một mình"), "SEATWORK")
        self.assertEqual(self.ai.detect_context("đây là bài tập cá nhân"), "SEATWORK")
        self.assertEqual(self.ai.detect_context("các em suy nghĩ rồi viết ra"), "SEATWORK")

    def test_keywords_q_and_a(self):
        self.assertEqual(self.ai.detect_context("ai trả lời câu hỏi này"), "Q_AND_A")
        self.assertEqual(self.ai.detect_context("em nào giơ tay"), "Q_AND_A")
        self.assertEqual(self.ai.detect_context("mời em phát biểu"), "Q_AND_A")
        self.assertEqual(self.ai.detect_context("cô mời một bạn đọc bài"), "Q_AND_A")
        self.assertEqual(self.ai.detect_context("bây giờ hỏi đáp"), "Q_AND_A")

    def test_five_contexts_and_manual_accepts_new_ones(self):
        self.assertEqual(len(TeacherContextAI.CONTEXTS), 5)
        for ctx in ("SEATWORK", "Q_AND_A"):
            self.ai.set_context(ctx, reason="dashboard_api")
            self.assertEqual(self.ai.get_status()["context"], ctx)
            self.assertTrue(self.ai.get_status()["has_command"])

    def test_keyword_overlap_resolved_longest_first(self):
        # "làm bài tập" (STUDY_NOTE, tài liệu 2 + test khoá) dài hơn "tự làm" (SEATWORK):
        # câu chứa cả hai phải về STUDY_NOTE, không nhập nhằng.
        self.assertEqual(self.ai.detect_context("các em tự làm bài tập"), "STUDY_NOTE")
        # "hết giờ thảo luận" là lệnh VỀ nghe giảng, không phải lệnh thảo luận.
        self.assertEqual(self.ai.detect_context("hết giờ thảo luận"), "LECTURE")

    def test_auto_reset_timeout(self):
        self.ai.set_context(TeacherContextAI.CONTEXT_GROUP_WORK)
        status = self.ai.get_status()
        self.assertEqual(status["context"], "GROUP_WORK")

        # Sau 2.2 giây (lớn hơn auto_reset_seconds=2) phải tự về UNKNOWN, KHÔNG phải LECTURE.
        # Bản đầu tiên quay về LECTURE, tức là tự tuyên bố "đang nghe giảng" khi giáo viên đã im lặng
        # 3 phút - và classroom.py đọc trạng thái khởi động đó thành khẩu lệnh thật, dán nhãn sai lên
        # mọi phiên (2 test activity hỏng). Im lặng không phải là một hoạt động.
        time.sleep(2.2)
        status = self.ai.get_status()
        self.assertEqual(status["context"], "UNKNOWN")
        self.assertFalse(status["has_command"])

    def test_starts_unknown_and_claims_no_command(self):
        fresh = TeacherContextAI()
        status = fresh.get_status()
        self.assertEqual(status["context"], "UNKNOWN")
        self.assertFalse(status["has_command"])
        self.assertIsNone(status["source"])
        self.assertEqual(status["last_speech"], "")
        self.assertFalse(status["is_running"])

    def test_feed_text_marks_speech_and_keeps_the_heard_sentence(self):
        self.assertEqual(self.ai.feed_text("các em ghi bài vào vở"), "STUDY_NOTE")
        status = self.ai.get_status()
        self.assertEqual(status["context"], "STUDY_NOTE")
        self.assertEqual(status["source"], "speech")
        self.assertTrue(status["has_command"])
        self.assertEqual(status["last_speech"], "các em ghi bài vào vở")
        # Một câu không phải khẩu lệnh: ghi lại câu nghe được nhưng không đổi ngữ cảnh.
        self.assertIsNone(self.ai.feed_text("trời hôm nay đẹp"))
        self.assertEqual(self.ai.get_status()["context"], "STUDY_NOTE")

    def test_manual_choice_is_not_labelled_as_speech(self):
        self.ai.set_context(TeacherContextAI.CONTEXT_LECTURE, reason="dashboard_api")
        status = self.ai.get_status()
        self.assertEqual(status["source"], "manual")
        self.assertEqual(status["context"], "LECTURE")
        # Trả quyền quyết định về cho luật viết tay.
        self.ai.set_context(TeacherContextAI.CONTEXT_UNKNOWN)
        self.assertFalse(self.ai.get_status()["has_command"])

    def test_status_carries_the_privacy_warning(self):
        status = self.ai.get_status()
        # Nhận dạng dùng Google nên audio rời máy: giao diện phải nói điều đó trước khi bật micro.
        self.assertTrue(status["sends_audio_offsite"])
        self.assertIn("Google", status["privacy_note"])
        self.assertIn("mic_available", status)

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
