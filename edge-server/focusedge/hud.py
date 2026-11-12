"""The phone's HUD text, reproduced line for line for the PC preview.

Why this file exists: the phone builds its ~25 text lines inside MainActivity.analyzeFrame and
applyHudText, out of local variables that never leave that function. Nothing on the camera leg could
quote them, so an earlier preview invented its own shorter wording ("Diem tap trung", "Chop") and the
two instruments ended up describing the same measurement with different sentences - which is the one
thing a demo comparing them cannot afford.

Everything here is copied from the phone, including:
  * the line order and the exact Vietnamese strings (MainActivity.kt:1058-1385),
  * the "--" placeholder rules (applyHudText, MainActivity.kt:4950-5101, showSensorLoss :5782),
  * the colour table (seiColorOf/seiLabelOf :2060-2081, chronic :5055, hints :1344-1360),
  * the Locale.US number formats, so "45.2 cm" reads the same on both screens.

A line is printed with "--" when the camera leg genuinely does not measure that quantity. It is never
printed with a number borrowed from a different calculation: see the note on imageDetail, inattention
and the closing threshold in HudState.
"""
from __future__ import annotations

from typing import List, NamedTuple, Optional

from .constants import GAZE_H_DRIFT, GAZE_V_DRIFT, Situation

# ---------------------------------------------------------------------------
# Colours, as the Compose theme renders them on a light surface.
# ---------------------------------------------------------------------------
RGB = tuple


class _C:
    ON_SURFACE: RGB = (28, 27, 31)          # Material3 default text on a light theme
    SURFACE: RGB = (255, 251, 254)          # lightColorScheme background
    GRAY: RGB = (136, 136, 136)             # Color.Gray: SEI with no value yet
    RED: RGB = (255, 0, 0)                  # Color.Red
    SEI_GOOD: RGB = (46, 125, 50)           # 0xFF2E7D32
    SEI_FAIR: RGB = (249, 168, 37)          # 0xFFF9A825
    SEI_MED: RGB = (239, 108, 0)            # 0xFFEF6C00
    PRIMARY: RGB = (102, 80, 164)           # 0xFF6650A4 (Purple40); the phone may show a
                                            # wallpaper-derived tone instead - see Theme.kt
                                            # dynamicLightColorScheme
    ALERT_ORANGE: RGB = (239, 108, 0)       # the alert line's own colour, MainActivity.kt:1138
    HINT_AMBER: RGB = (255, 160, 0)         # personHint, :1347
    HINT_BLUE: RGB = (79, 195, 247)         # faceSizeHint, :1357


class Line(NamedTuple):
    """One HUD row: what to draw, how big, in what colour."""

    text: str
    size: int = 13
    colour: RGB = _C.ON_SURFACE
    bold: bool = False

    @property
    def style(self) -> str:
        """The Compose text style this row was taken from.

        ``size`` is this window's own geometry - main.py:345 rescales every row by the font slider, so
        the pixel number says something about the monitor, not about the phone. The style name is the
        fact that travels: it is what MainActivity.kt applied, and it is what the browser build must
        reproduce with its own CSS. Comparing styles is how two legs can be identical while each keeps
        its own readable size.
        """
        return _SIZE_TO_STYLE.get(self.size, "bodyLarge")


# The inverse of the constants below: a row's size is chosen only by its style, so the style can be
# read back. bodyLarge is the default and owns the size a bare Line() gets.
_SIZE_TO_STYLE = {
    19: "headlineSmall",
    17: "titleLarge",
    14: "titleMedium",
    12: "titleSmall",
    11: "bodySmall",
    13: "bodyLarge",
}

# The two Material3 styles that carry a Medium weight; every other style on this column is Regular.
_MEDIUM_STYLES = frozenset(("titleMedium", "titleSmall"))


# A row whose "text" is this marker is not text at all: it is the slot the phone's SEI sparkline
# occupies. Nothing in this module draws it - the caller replaces the row with the curve.
CHART_ROW = "\x00sei_chart"


# What the preview paints on top of the camera. The phone's Canvas draws nothing but dots here
# (MainActivity.kt:2019-2052): iris landmarks cyan r=3, the two eye rings yellow r=4, everything
# else green r=2. Iris landmarks start at LM_RIGHT_IRIS = 468, and EYE_MASK is the union of
# RIGHT_EYE and LEFT_EYE (MainActivity.kt, landmark index lists above the drawing code).
IRIS_FIRST = 468
EYE_DRAW = frozenset((33, 160, 158, 133, 153, 144, 362, 385, 387, 263, 373, 380))
DOT_COLOURS = {  # Color.Green / Yellow / Cyan, in the BGR order cv2 wants
    "iris": ((255, 255, 0), 3),
    "eye": ((0, 255, 255), 4),
    "mesh": ((0, 255, 0), 2),
}


# Type.kt overrides only titleLarge (22 sp); the rest are Material3 defaults, mapped onto
# preview pixels at a size that stays readable on a 640-wide window.
_HEADLINE = 19   # headlineSmall  (24 sp) - "FocusChangeAI"
_TITLE_L = 17    # titleLarge     (22 sp) - the SEI header
_TITLE_M = 14    # titleMedium    (16 sp) - "Hoc sinh:", "So khuon mat:", blink count
_SMALL = 11      # bodySmall      (12 sp)
_TITLE_S = 12    # titleSmall     (14 sp) - chronic / anomaly / too-close / alert lines


def _num(v: Optional[float], fmt: str = "%.1f") -> str:
    """Format a number the way the phone does, or print its dash when there is none."""
    if v is None:
        return "--"
    try:
        if v != v:  # NaN
            return "--"
    except TypeError:
        return "--"
    return fmt % v


# ---------------------------------------------------------------------------
# The three lookup tables (MainActivity.kt:2060-2081, describeSituation :2837)
# ---------------------------------------------------------------------------
def sei_label(sei: Optional[int]) -> str:
    if sei is None or sei < 0:
        return "--"
    if sei >= 80:
        return "Tốt"
    if sei >= 60:
        return "Khá"
    if sei >= 40:
        return "Trung bình"
    return "Thấp"


def sei_colour(sei: Optional[int]) -> RGB:
    if sei is None or sei < 0:
        return _C.GRAY
    if sei >= 80:
        return _C.SEI_GOOD
    if sei >= 60:
        return _C.SEI_FAIR
    if sei >= 40:
        return _C.SEI_MED
    return _C.RED


def fatigue_label(index: int) -> str:
    if index < 25:
        return "Tỉnh táo"
    if index < 50:
        return "Hơi mệt"
    if index < 75:
        return "Mệt"
    return "Rất mệt"


SITUATION_VI = {
    Situation.FORWARD: "Ngồi thẳng, nhìn màn hình",
    Situation.DOWN: "Cúi xuống (viết hoặc đọc)",
    Situation.TILT: "Nghiêng đầu",
    Situation.TURNED: "Quay sang bên",
    Situation.DOZING: "Gật đầu, ít cử động",
    Situation.UNKNOWN: "--",
}

# The pipeline records these codes; the phone renders each of them with this sentence
# (MainActivity.kt:4704-4736). TOO_CLOSE is deliberately absent: on the phone a face closer than
# 30 cm is its own always-visible line above the parameter column, not a member of the alert chain,
# and reproducing it in both places would print one fact twice.
ALERT_SENTENCES = {
    # Copied from MainActivity.kt:4705-4735 word for word, in the phone's own branch order. Every
    # line the phone can print has to be reachable from here: a missing key falls through to
    # alert_sentence's `code` default, which shows the operator an English identifier instead of a
    # sentence - the one failure mode a parity HUD must not have.
    "UNRELIABLE": "⚠ Tín hiệu kém (%s) - kết quả có thể không chính xác.",
    "DROWSY": "⚠ Nhắm mắt quá lâu - bạn có thể đang buồn ngủ!",
    "PERCLOS_HIGH": "⚠ Dấu hiệu mệt mỏi (PERCLOS cao) - hãy nghỉ ngơi.",
    "VISUAL_DRIFT": "⚠ Visual Drift: ánh mắt rời màn hình quá 3 giây.",
    "LOOK_AWAY": "⚠ Đầu bạn đang quay lệch khỏi màn hình.",
    "YAWN": "⚠ Bạn đang ngáp - dấu hiệu mệt mỏi.",
    "LOW_BLINK": "⚠ Chớp mắt quá ít - hãy chớp mắt thường xuyên hơn.",
    "SQUINT": ("⚠ Mắt khép hoặc nheo một phần kéo dài - có thể chói, mỏi hoặc khó chịu. "
               "Hãy nghỉ mắt và chỉnh độ sáng."),
    "ASYMMETRY": ("ℹ Hai mắt mở không đều trong thời gian dài - hãy nghỉ mắt; nếu hay lặp lại, "
                  "hãy báo người lớn hoặc giáo viên."),
    "INCOMPLETE_BLINK": "ℹ Chớp mắt không hoàn toàn - mắt dễ khô, hãy chớp mắt chậm và đầy đủ.",
    "HIGH_BLINK": ("ℹ Chớp mắt rất nhiều - mắt có thể đang khô hoặc bị kích ứng, "
                   "hãy nghỉ mắt."),
    "BREAK": "⏰ Đã 20 phút: hãy nhìn xa 6 m trong 20 giây (quy tắc 20-20-20).",
    "CONFUSED": "ℹ Bạn có vẻ đang căng thẳng hoặc bối rối với nội dung.",
    "LOW_VS_PERSONAL": "ℹ Mức tập trung đang thấp hơn thường ngày của bạn (thường khoảng %s).",
    "NOISY": ("ℹ Kính phản chiếu hoặc ánh sáng chói đang gây nhiễu - hãy nghiêng màn hình, "
              "giảm chói hoặc tăng ánh sáng đều."),
}

# The two branches that carry a value inside the sentence, handled by name in alert_sentence rather
# than by a generic % substitution: feeding one placeholder the arguments of the other is how a
# quality note ends up printed where the phone would have printed this student's usual SEI.


def alert_sentence(code: Optional[str], personal_sei_mean: Optional[int],
                   quality_note: Optional[str] = None) -> str:
    """The one Vietnamese line the phone shows for this code, or "" when it shows nothing.

    ``alertCode`` and the printed sentence are two separate ``when`` blocks on the phone
    (MainActivity.kt:4704 and :4739) and they are not the same list: "tín hiệu kém", "bối rối" and
    the glare note print a line but count as code 0. A caller that wants the count reads
    PersonResult.alert; a caller that wants the screen reads this.
    """
    if not code:
        return ""
    if code == "LOW_VS_PERSONAL":
        return ALERT_SENTENCES[code] % _num(personal_sei_mean, "%d")
    if code == "UNRELIABLE":
        return ALERT_SENTENCES[code] % (quality_note or "--")
    return ALERT_SENTENCES.get(code, code)


# ---------------------------------------------------------------------------
# The individual status sentences
# ---------------------------------------------------------------------------
def eye_status(h) -> str:
    """currentEyeStatus, MainActivity.kt:4361-4367, same five branches in the same order."""
    if h is None:
        return "Không phát hiện khuôn mặt"
    if h.drowsy:
        return "Nhắm lâu - có thể buồn ngủ"
    if h.closed:
        return "Đang nhắm"
    if not h.eye_valid:
        return "Tạm bỏ qua (góc nghiêng lớn hoặc tín hiệu kém)"
    if h.squint_alert:
        return "Mắt khép hoặc nheo một phần kéo dài"
    return "Đang mở hoặc chuyển trạng thái"


def eye_detail(h) -> str:
    """eyeDetail, MainActivity.kt:4316-4329."""
    if h is None or not h.eye_valid:
        return "Góc nghiêng lớn hoặc tín hiệu kém: tạm bỏ qua phân tích mắt"
    return ("độ mở %.2f | nhiễu %.2f | nheo %.0f%% | lệch 2 mắt %.0f%% | %s%s" % (
        h.openness, h.sigma, h.squint_pct, h.asym_pct,
        "AI blendshape + hình học" if h.using_blendshapes
        else "hình học (chưa bật blendshape)",
        " | kính/chói gây nhiễu" if h.noisy else ""))


def gaze_status(h) -> str:
    """gazeStatus, MainActivity.kt:4415-4433."""
    if h is None:
        return "Không có dữ liệu mống mắt"
    if not h.iris_available:
        return "Không có dữ liệu mống mắt"
    if not h.reliable:
        return "Tín hiệu kém, tạm bỏ qua"
    if not h.eyes_open_for_gaze:
        return "Mắt đang nhắm"
    horizontal = abs(h.gaze_h_dev) / GAZE_H_DRIFT >= abs(h.gaze_v_dev) / GAZE_V_DRIFT
    if horizontal:
        direction = "trái" if h.gaze_h_dev > 0 else "phải"
    else:
        direction = "xuống" if h.gaze_v_dev > 0 else "lên"
    if h.drift_long:
        return "Visual Drift: lệch sang %s %.1f giây" % (direction, h.drift_ms / 1000.0)
    if h.drift_now:
        return "Đang liếc sang %s" % direction
    return "Nhìn vào màn hình"


def expression_status(h) -> str:
    """expressionStatus, MainActivity.kt:4491-4496."""
    if h is None:
        return "Đang hiệu chuẩn"
    if h.yawn_confirmed:
        return "Ngáp (dấu hiệu mệt mỏi)"
    if h.confused_sustained:
        return "Nhíu mày kéo dài (bối rối / căng thẳng)"
    if h.furrow_now:
        return "Hơi nhíu mày"
    return "Bình thường"


def situation_status(h, situation: Situation) -> str:
    """situationStatus, MainActivity.kt:4775-4780: the tally is only appended once it is non-zero."""
    text = SITUATION_VI.get(situation, "--")
    if h is None or h.intervened == 0:
        return text
    return "%s · đã hoãn %d lần, bỏ hẳn %d lần" % (text, h.intervened, h.suppressed)


def head_pose_text(h) -> str:
    """headPoseText, MainActivity.kt:5000-5010. All three angles or nothing."""
    if h is None or h.yaw is None or h.pitch is None or h.roll is None:
        return "--"
    return "Yaw %+.1f° (+ = trái) | Pitch %+.1f° (+ = cúi) | Roll %+.1f°" % (
        h.yaw, h.pitch, h.roll)


def calibration_text(h) -> str:
    """calibrationLabelFrom, MainActivity.kt:5175-5192.

    The phone's second number is its closing threshold in EAR units; this engine decides closure on
    a normalised openness ratio instead, so there is no same-quantity value to print and the slot
    keeps the dash rather than borrowing a number that means something else.
    """
    if h is None:
        return "--"
    if not h.calibrating:
        return "Hoàn tất (EAR cơ sở %s, ngưỡng nhắm --)" % _num(h.baseline_ear, "%.3f")
    hint = ""
    if h.quality is not None and h.quality < 50:
        hint = " - %s" % h.quality_note
    return "Đang hiệu chuẩn %d/%d - hãy nhìn thẳng, mở mắt tự nhiên%s" % (
        h.calib_progress, h.calib_total, hint)


# ---------------------------------------------------------------------------
# The whole column
# ---------------------------------------------------------------------------
def hud_lines(result, face_count: int, processing_ms: float, fps: float, identity=None,
              activity=None) -> List[Line]:
    """Every text line the phone shows between its title and its camera preview.

    ``result`` is the PersonResult the pipeline produced for the person being shown (or None when
    the frame holds nobody); the blink count and the calibration progress ride inside its HudState,
    because both belong to one person, not to the window.

    ``identity`` is that person's PersonView from layer 2, and it is this leg's own addition: the
    phone has one holder and one face, so it never has to admit how close a name call was. Here the
    line carries the score and the gap to the next-closest student, which is how an operator can
    tell "the roster has no such child" from "the room is too dark to be sure".

    ``activity`` is the room-level ClassSnapshot from focusedge.activity, also not a phone line: the
    phone measures one head and has nothing to say about what the class is doing. When the caller has
    no context layer the row keeps its place with a dash, because a column that silently loses a line
    looks like a column that changed.
    """
    h = result.hud if result is not None else None
    calibrated = bool(result is not None and result.calibrated)
    out: List[Line] = []
    add = out.append

    add(Line("FocusChangeAI", _HEADLINE, _C.ON_SURFACE, True))
    if face_count > 0:
        add(Line("Đã phát hiện khuôn mặt."))
    else:
        add(Line("Chưa phát hiện khuôn mặt."))

    sei = h.sei if h is not None else None
    if sei is None:
        add(Line("SEI 2.0: -- / 100", _TITLE_L, _C.GRAY, True))
    else:
        add(Line("SEI 2.0: %d / 100 (%s)" % (sei, sei_label(sei)), _TITLE_L,
                 sei_colour(sei), True))
    # Not text: the phone's 44 dp SEI sparkline sits in this exact slot, between the header and the
    # chronic line (MainActivity.kt:1071-1109). The renderer owns the drawing, hud_lines owns the
    # order, so the curve cannot drift to the bottom of the column the way a caller-side insert did.
    add(Line(CHART_ROW))

    # ---- lines the phone shows above the parameter column, in its order ----
    if h is not None and h.chronic_level == 2:
        add(Line("⛔ CẢNH BÁO MẤT TẬP TRUNG KÉO DÀI - hãy nghỉ ngơi hoặc tập trung lại.",
                 _TITLE_S, _C.RED))
    elif h is not None and h.chronic_level == 1:
        add(Line("⚠ Cần chú ý: các lần mất tập trung đang tăng lên.", _TITLE_S, _C.SEI_MED))

    if h is not None and h.too_close:
        add(Line("⚠ Cảnh báo: bạn đang ngồi quá gần màn hình (< 30 cm)!", _TITLE_S, _C.RED))

    # Computed once: the phone reads the same situation string for its situation line below and
    # for the prefix of a held alert (MainActivity.kt:5074), and two calls could disagree.
    sit_text = situation_status(h, result.situation if result is not None else Situation.UNKNOWN)

    sentence = alert_sentence(h.alert_message if h else None,
                              h.personal_sei_mean if h else None,
                              h.quality_note if h else None)
    if sentence and h is not None and h.alert_held:
        # A held alert says what is holding it instead of showing nothing: an operator who sees a
        # shut eye and no line cannot tell "delayed by the context layer" from "broken".
        sentence = "%s - đang hoãn cảnh báo: %s" % (sit_text, sentence)
    if sentence:
        add(Line(sentence, _TITLE_S, _C.ALERT_ORANGE))

    add(Line(""))
    name = (result.student_name or "") if result is not None else ""
    add(Line("Học sinh: %s" % (name or "chưa gắn tên"), _TITLE_M))
    if h is None or not calibrated:
        add(Line("Đang nạp hồ sơ học sinh...", _SMALL))
    else:
        add(Line(_habit_status(h), _SMALL))
    add(Line("Máy này đang gắn với: %s" % (name or "chưa gắn tên"), _SMALL))
    if identity is not None:
        add(Line(_identity_evidence(identity), _SMALL))

    add(Line("Số khuôn mặt: %d" % face_count, _TITLE_M))
    add(Line("Số điểm mốc: %d" % (h.landmark_count if h else 0)))
    add(Line("Số lần chớp mắt: %d" % (h.blink_total if h else 0), _TITLE_M, _C.PRIMARY))
    add(Line("Nhịp chớp mắt (BPM): %s" % (_num(h.bpm if h else None))))

    add(Line("EAR mắt 1: %s" % (_num(h.ear1 if h else None, "%.3f"))))
    add(Line("EAR mắt 2: %s" % (_num(h.ear2 if h else None, "%.3f"))))
    add(Line("Trạng thái mắt: %s" % eye_status(h)))
    if h is None:
        add(Line("Phân tích mắt: --"))
    else:
        add(Line("Phân tích mắt: %s" % eye_detail(h)))
    # The phone's image-processing line describes its brightness enhancer stage. This leg feeds raw
    # frames to the same landmark model with no enhancer, so there is no such stage to describe.
    add(Line("Xử lý ảnh: --"))

    dist_colour = _C.RED if (h is not None and h.too_close) else _C.ON_SURFACE
    add(Line("Khoảng cách: %s (hệ số %s của hồ sơ này)" % (
        _num(h.distance_cm if h else None),
        "x%.2f" % h.distance_scale if h else "--"), 13, dist_colour))

    add(Line("Tư thế đầu: %s" % head_pose_text(h)))
    add(Line("Hướng nhìn: %s" % gaze_status(h)))
    add(Line("Tình huống: %s" % sit_text))
    # The room-level label, directly under the per-head situation, because the two answers belong
    # together: "this child is turned sideways" and "the room is doing pair work" are the same geometry
    # read at two scales. The sentence after the evidence is not decoration - these rules were written
    # by hand and no labelled classroom video has ever been scored against them, so the number column
    # has to say that in the same breath as the label. tests/test_activity.py pins the wording.
    add(Line("Hoạt động lớp: %s" % (activity.label if activity is not None else "--"),
             _TITLE_M, _C.PRIMARY if activity is not None else _C.GRAY))
    if activity is not None:
        add(Line("%s · luật viết tay, chưa kiểm định bằng dữ liệu có nhãn" % activity.evidence,
                 _SMALL, _C.GRAY))
    add(Line("Vi biểu cảm: %s" % expression_status(h)))
    if h is not None and h.mar == h.mar and h.bfr == h.bfr:
        add(Line("Chỉ số: BFR %.2f | MAR %.2f | Ngáp (5 phút): %d" % (
            h.bfr, h.mar, h.yawns_recent)))
    else:
        add(Line("Chỉ số: --"))
    # No inattention accumulator or violation score exists on the camera leg yet, and a dash is the
    # phone's own answer for that (inattentionText stays "--" while the window is too short).
    add(Line("Mất tập trung: --"))

    add(Line("Mệt mỏi (tỉ lệ nhắm mắt): %s" % (_num(h.perclos if h else None))))
    add(Line("PERCLOS P70/P80: %s / %s" % (_num(h.perclos_p70 if h else None),
                                            _num(h.perclos_p80 if h else None))))
    # The phone prints its CSV logger state here. This leg writes summary documents, not a
    # per-frame CSV, so the line keeps its place with the phone's "not measured" dash.
    add(Line("Ghi CSV: --", _SMALL))

    if h is None or h.fatigue is None:
        add(Line("Chỉ số mệt mỏi: --"))
    else:
        blink_part = "" if h.avg_blink_ms is None else " | Chớp TB %.0f ms" % h.avg_blink_ms
        add(Line("Chỉ số mệt mỏi: %d / 100 (%s)%s" % (
            h.fatigue, fatigue_label(h.fatigue), blink_part)))

    if h is None or h.quality is None:
        add(Line("Chất lượng tín hiệu: --"))
    else:
        add(Line("Chất lượng tín hiệu: %d / 100 (%s)" % (h.quality, h.quality_note)))

    add(Line("Hiệu chuẩn: %s" % calibration_text(h)))

    if h is not None and calibrated:
        add(Line("Phiên làm việc: %02d:%02d | SEI trung bình: %s" % (
            h.session_seconds // 60, h.session_seconds % 60,
            _num(h.average_sei, "%d"))))
    else:
        add(Line("Phiên làm việc: --"))

    # Both numbers come from the caller's own clock, so before the first measured step they are not
    # numbers yet - and the phone's answer for "not yet" is the dash, never "nan".
    add(Line("Thời gian nhận diện: %s ms (%s FPS)" % (_num(processing_ms), _num(fps, "%.0f"))))
    add(Line(PRIVACY_NOTE, _SMALL))
    # Weight is not a caller's opinion: Material3 fixes it per text style, and the phone's own
    # headlineSmall and titleLarge are Regular while titleMedium and titleSmall are Medium. Deriving
    # it here is what stops one leg from bolding a line the phone never bolded.
    return [l._replace(bold=l.style in _MEDIUM_STYLES) for l in out]


# The phone's privacy line (MainActivity.kt) still mentions Firebase, because on the phone that is
# a switch the student can see. This machine has no such switch any more, so the sentence is its
# own: copying the phone's line here would claim a cloud path the camera leg does not have.
PRIVACY_NOTE = (
    "Mọi phép đo chạy trên máy này; chương trình không lưu ảnh, không lưu video. "
    "Kết quả chỉ ghi vào data/focuschange.db và các tệp JSON trong thư mục out/ trên cùng máy. "
    "Không có bất kỳ dữ liệu nào rời khỏi máy quay."
)


def _habit_status(h) -> str:
    """The phone's habitStatus line for the case this machine is actually in.

    With an empty roster nobody is bound, so there is no per-student profile to report and the
    sentence says so in the phone's own words; the "Hồ sơ cá nhân: N phiên ..." branch belongs to a
    profile this leg cannot have, so it is not printed with invented numbers.
    """
    return "Đang học thói quen: hồ sơ chưa gắn tên nên chưa có phiên nào được lưu."


def _identity_evidence(v) -> str:
    """The number a name was decided on, or the reason there is no name yet.

    Written from the PersonView rather than from the verdict string, so the screen cannot say
    "chưa đủ chắc" about a face that scored above the bar and vice versa. The gap to the
    next-closest classmate is the part that matters in a classroom: two scores that differ by
    nothing is the machine guessing, and a negative gap says so out loud.

    A name declared with the m key has no number behind it at all, and it says so first: without
    that line a table full of typed-in names reads exactly like a table full of recognised ones.
    """
    if v.identity_conf == "manual":
        return ("Nhận diện: tự khai - người vận hành gán tên bằng phím, không có khuôn mặt nào "
                "được so với danh sách lớp")
    if not v.samples:
        return "Nhận diện: chưa có mẫu mặt nào để so với danh sách lớp"
    if not v.score:
        return "Nhận diện: chưa so được với danh sách lớp (%d mẫu mặt)" % v.samples
    if v.student_id:
        if not v.runner_up:
            return ("Nhận diện: điểm khớp %.2f · %d mẫu mặt · danh sách lớp chưa có em thứ hai để so"
                    % (v.score, v.samples))
        return ("Nhận diện: điểm khớp %.2f · chênh với %s %.2f · %d mẫu mặt"
                % (v.score, v.runner_up, v.margin, v.samples))
    need = []
    if v.gate:
        need.append("cần ≥ %.2f" % v.gate)
    if v.margin_gate:
        need.append("chênh ≥ %.2f" % v.margin_gate)
    return ("Nhận diện: chưa gắn tên, điểm cao nhất %.2f (%s) · %d mẫu mặt"
            % (v.score, " và ".join(need) if need else "chưa đạt", v.samples))


def landmark_to_frame_px(lm, i: int, face_bbox, frame_w: int, frame_h: int):
    """Put dot i of a CROP-measured face where the phone would put dot i of a FRAME-measured face.

    The phone runs FaceLandmarker on the whole camera frame, so its landmark x/y are already
    normalised against the frame and MainActivity.kt:2030 can multiply them by the view size
    directly. This leg runs it on the crop cut from a person box (classroom.py:260), so the very
    same 0..1 numbers are normalised against that crop instead. Plotting them as if they were
    frame-normalised squeezes a 478-point face into the top-left of the picture - the mesh then
    looks like noise over the shoulder, and a judge reading the dots concludes the tracker is
    broken. Re-basing through face_bbox is what makes the two screens agree.

    Returns None when there is no bbox: a dot at a wrong place is worse than no dot.
    """
    if lm is None or face_bbox is None:
        return None
    bx0 = float(face_bbox[0])
    by0 = float(face_bbox[1])
    bw = float(face_bbox[2]) - bx0
    bh = float(face_bbox[3]) - by0
    if bw <= 0.0 or bh <= 0.0:
        return None
    return bx0 + lm.x(i) * bw, by0 + lm.y(i) * bh
