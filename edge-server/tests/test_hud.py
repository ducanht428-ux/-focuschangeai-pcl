"""Tests for the display layer: the PC preview must not disagree with the phone's wording.

Pure logic - no numpy, no cv2, no model weights. Run from the edge-server folder:

    python tests/test_hud.py

Each case is a way the two screens could drift apart while measuring the same frame: a line
invented instead of copied, a number borrowed from a different calculation, or the printed quality
score disagreeing with the recorded one.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.constants import Situation  # noqa: E402
from focusedge.hud import CHART_ROW, hud_lines, landmark_to_frame_px, situation_status  # noqa: E402
from focusedge.landmarks import Landmarks  # noqa: E402
from focusedge.person_pipeline import HudState, PersonPipeline  # noqa: E402
from focusedge.quality import signal_quality, signal_quality_detail  # noqa: E402


class _Stub:
    """The four PersonResult fields the HUD reads, without building a measured frame."""

    def __init__(self, hud, calibrated=True, alert=None, name=""):
        self.hud = hud
        self.calibrated = calibrated
        self.alert = alert
        self.student_name = name
        self.situation = Situation.FORWARD


def _texts(result, face_count=1):
    return [ln.text for ln in hud_lines(result, face_count, float("nan"), float("nan"))]


def test_the_column_opens_with_the_phones_title_and_face_line():
    texts = _texts(None, 0)
    assert texts[0] == "FocusChangeAI"                      # MainActivity.kt:1058
    assert texts[1] == "Chưa phát hiện khuôn mặt."
    assert "SEI 2.0: -- / 100" in texts                      # no value yet, not a zero
    with_face = _texts(_Stub(HudState(sei=72)), 1)
    assert with_face[1] == "Đã phát hiện khuôn mặt."
    assert "SEI 2.0: 72 / 100 (Khá)" in with_face            # seiLabelOf, MainActivity.kt:2067


def test_unmeasured_lines_keep_the_phones_dash_instead_of_a_borrowed_number():
    """Four slots the phone fills from stages this leg does not have.

    A dash is the phone's own answer for "not measured". Putting another quantity in those slots
    would make the two screens look comparable when they are not.
    """
    texts = _texts(_Stub(HudState(sei=72)))
    assert "Xử lý ảnh: --" in texts           # no brightness-enhancer stage on the camera leg
    assert "Mất tập trung: --" in texts       # no inattention accumulator here
    assert "Ghi CSV: --" in texts             # this leg writes documents, not frames.csv
    assert "ngưỡng nhắm --)" in [t for t in texts if t.startswith("Hiệu chuẩn:")][0]
    # _texts passes NaN for both clock values, which is the state of a window before its first
    # measured step. The phone never prints "nan" anywhere.
    assert "Thời gian nhận diện: -- ms (-- FPS)" in texts


def test_calibration_countdown_is_the_phones_sixty_frames():
    # CALIBRATION_FRAMES = 60 (MainActivity.kt:258). A shorter countdown would finish calibrating
    # on frames the phone still calls unsettled, and the printed x/N would be a second instrument.
    assert PersonPipeline.CALIB_SAMPLES == 60
    h = HudState(calibrating=True, calib_progress=17, calib_total=60, quality=40,
                 quality_note="Anh sang yeu")
    line = [t for t in _texts(_Stub(h, calibrated=False)) if t.startswith("Hiệu chuẩn:")][0]
    assert "Đang hiệu chuẩn 17/60" in line
    assert line.endswith("- Anh sang yeu")                   # the low-quality hint the phone adds


def test_the_sparkline_slot_is_between_the_sei_header_and_the_chronic_line():
    """MainActivity.kt:1071-1109 puts the 44 dp curve directly under the SEI header.

    The renderer draws whatever row carries CHART_ROW, so the order is owned here: if the marker
    moved, the PC screen would show the curve at the foot of the column and the two would stop
    being readable line by line.
    """
    texts = _texts(_Stub(HudState(sei=72, chronic_level=2)))
    header = next(i for i, t in enumerate(texts) if t.startswith("SEI 2.0:"))
    assert texts[header + 1] == CHART_ROW
    assert texts[header + 2].startswith("⛔")


def test_alert_sentence_is_the_one_the_phone_shows_for_that_code():
    # The sentence comes from the HUD's own alert_message, not from PersonResult.alert: three of the
    # phone's branches print a line but count as alertCode 0 (MainActivity.kt:4740, :4752, :4754),
    # so reading the counted code would leave those three lines off the screen entirely.
    texts = _texts(_Stub(HudState(sei=30, alert_message="DROWSY")))
    assert "⚠ Nhắm mắt quá lâu - bạn có thể đang buồn ngủ!" in texts    # MainActivity.kt:4708
    texts = _texts(_Stub(HudState(sei=30, personal_sei_mean=71, alert_message="LOW_VS_PERSONAL")))
    assert any("thường khoảng 71" in t for t in texts)
    texts = _texts(_Stub(HudState(sei=30, quality_note="Mờ", alert_message="UNRELIABLE")))
    assert any("Tín hiệu kém (Mờ)" in t for t in texts)


def test_a_held_alert_names_the_situation_that_is_holding_it():
    """MainActivity.kt:5074-5078: "situation - đang hoãn cảnh báo: message", never a blank line."""
    texts = _texts(_Stub(HudState(sei=30, alert_message="DROWSY", alert_held=True)))
    held = [t for t in texts if "đang hoãn cảnh báo" in t]
    assert len(held) == 1
    assert held[0].startswith(situation_status(HudState(sei=30), Situation.FORWARD))
    assert held[0].endswith("Nhắm mắt quá lâu - bạn có thể đang buồn ngủ!")
    # Not held: the same frame shows the plain sentence with no prefix.
    plain = _texts(_Stub(HudState(sei=30, alert_message="DROWSY")))
    assert not any("đang hoãn" in t for t in plain)


def test_quality_score_printed_is_the_score_recorded():
    """One penalty table, so the HUD can never disagree with the number written to Firestore."""
    for args in [((200.0, False, 5.0, 5.0, True), {}),
                 ((30.0, False, 5.0, 5.0, True), {}),
                 ((20.0, True, 40.0, 35.0, False), {}),
                 ((100.0, False, 40.0, 5.0, False), {"backlit": True}),
                 ((230.0, False, 5.0, 5.0, True), {"overexposed": True})]:
        score, note, reliable = signal_quality_detail(*args[0], **args[1])
        assert score == signal_quality(*args[0], **args[1])
        assert isinstance(note, str) and note
        assert isinstance(reliable, bool)
    # Snapshotted before the pose and iris docks (MainActivity.kt:3347): a 78-point frame with a
    # turned head is still "reliable", and the gaze line must not then say the signal is poor.
    assert signal_quality_detail(100.0, False, 40.0, 35.0, False)[2] is True


def test_crop_landmarks_are_rebased_into_frame_pixels_not_frame_fractions():
    # The phone measures the whole frame, this leg measures a crop, and both hand the renderer a
    # 0..1 number. A dot must land inside the face box, not at the same fraction of the frame.
    lm = Landmarks([(0.0, 0.0), (1.0, 1.0), (0.5, 0.5)], 3)
    box = (100, 50, 300, 250)
    assert landmark_to_frame_px(lm, 0, box, 640, 480) == (100.0, 50.0)
    assert landmark_to_frame_px(lm, 1, box, 640, 480) == (300.0, 250.0)
    assert landmark_to_frame_px(lm, 2, box, 640, 480) == (200.0, 150.0)
    # The bug this locks: reading the crop fraction as a frame fraction instead.
    assert landmark_to_frame_px(lm, 2, box, 640, 480) != (0.5 * 640, 0.5 * 480)
    # No box, or a degenerate one, means no dot - never a dot in the wrong place.
    assert landmark_to_frame_px(lm, 0, None, 640, 480) is None
    assert landmark_to_frame_px(lm, 0, (100, 50, 100, 250), 640, 480) is None


def test_the_yolo_box_uses_the_same_band_arithmetic_as_the_dots():
    # The camera band is a zoom-and-centre-crop of the frame, so a box in frame pixels has to be
    # mapped through exactly the numbers the dots use. Two copies of that formula is how a green
    # box ends up beside a face instead of around it.
    from main import _band_mapping

    assert _band_mapping(640, 480, 640, 480) == (1.0, 0, 0)
    # Half size: the zoom is uniform, so nothing is cut.
    assert _band_mapping(640, 480, 320, 240) == (0.5, 0, 0)
    # A band narrower than the picture's aspect: height drives the zoom and the sides are cut
    # equally, which is the FILL_CENTER behaviour the phone's PreviewView shows.
    scale, cut_x, cut_y = _band_mapping(640, 480, 300, 480)
    assert (scale, cut_x, cut_y) == (1.0, 170, 0)
    # A frame point therefore lands inside the band, not outside it.
    assert 0 <= 400 * scale - cut_x < 300


def main():
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
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
