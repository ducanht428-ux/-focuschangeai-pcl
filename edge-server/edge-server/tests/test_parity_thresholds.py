"""Tests that pin the PC leg's numbers to the phone's numbers.

Pure logic - no numpy, no cv2, no model weights. Run from the edge-server folder:

    python tests/test_parity_thresholds.py

The camera leg and the phone are supposed to be one instrument with two screens. Every case here
is a quantity that both devices compute independently, with the Kotlin literal it has to equal.
These tests do not check that a measurement is right; they check that the two machines cannot
answer the same frame with different numbers, which is the claim the write-up makes.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge import constants as K  # noqa: E402
from focusedge.constants import HabitFeature, MuscleSensitivity, Situation  # noqa: E402
from focusedge.habit import HabitBaseline, HabitProfile  # noqa: E402
from focusedge.hud import ALERT_SENTENCES, hud_lines  # noqa: E402
from focusedge.person_pipeline import (  # noqa: E402
    AlertFacts, HudState, PersonPipeline, PersonResult)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _pipeline(sensitivity=1):
    return PersonPipeline(1, profile=None, sensitivity=sensitivity)


def _pick(p, **facts):
    return p._pick_alert(AlertFacts(**facts), p._current_situation_frame(), 10_000)


class _ReadyProfile:
    """A profile that is ready and fully confident, without building five session summaries.

    _apply_personal_params only asks a profile for baseline() and confidence(), so the blend can be
    tested on its own without the storage layer.
    """

    def __init__(self, means, spreads, confidence):
        n = HabitFeature.COUNT
        filled_means = [means.get(i, 0.0) for i in range(n)]
        filled_spreads = [spreads.get(i, 1.0) for i in range(n)]
        ok = [i in means for i in range(n)]
        self._base = HabitBaseline(filled_means, filled_spreads, ok)
        self._c = confidence

    def baseline(self):
        return self._base

    def confidence(self):
        return self._c


def _texts(result, face_count=1):
    return [ln.text for ln in hud_lines(result, face_count, float("nan"), float("nan"))]


# ---------------------------------------------------------------------------
# 1. The literals
# ---------------------------------------------------------------------------
def test_threshold_constants_equal_the_phone_literals():
    """One number per line, copied from the companion object of MainActivity.kt.

    Four of these were wrong on this machine at once: PERCLOS_ALERT carried 30 where the phone
    carries 15, LOW_BLINK_BPM 12 where it carries 8, HIGH_BLINK_BPM 25 where it carries 30, and
    BPM_RELIABLE_AFTER_MS 20 000 where it carries 15 000. Each one moved a verdict, not a decimal.
    """
    assert K.MIN_SAFE_DISTANCE_CM == 30.0        # MainActivity.kt:240
    assert K.DISTANCE_RECOVER_CM == 32.0         # MainActivity.kt:241
    assert K.BPM_RELIABLE_AFTER_MS == 15000      # MainActivity.kt:269
    assert K.DROWSY_CLOSURE_MS == 1500           # MainActivity.kt:270
    assert K.LOW_BLINK_BPM == 8.0                # MainActivity.kt:271
    assert K.HIGH_BLINK_BPM == 30.0              # MainActivity.kt:272
    assert K.PERCLOS_ALERT == 15.0               # MainActivity.kt:280
    assert K.LOOK_AWAY_YAW_DEG == 18.0           # MainActivity.kt:288
    assert K.LOOK_AWAY_PITCH_DEG == 18.0         # MainActivity.kt:289
    assert K.GAZE_H_DRIFT == 0.16                # MainActivity.kt:305
    assert K.GAZE_V_DRIFT == 0.22                # MainActivity.kt:306
    assert K.MAR_YAWN == 0.55                    # MainActivity.kt:312
    assert K.EYE_MAX_YAW_DEG == 28.0             # MainActivity.kt:177
    assert K.EYE_MAX_PITCH_DEG == 35.0           # MainActivity.kt:178
    assert K.LOW_VS_PERSONAL_MS == 45000         # MainActivity.kt:137
    assert K.LOW_VS_PERSONAL_K == 1.5            # MainActivity.kt:138
    assert K.PERSONAL_SEI_MIN_SPREAD == 8.0      # MainActivity.kt:139
    assert K.BREAK_REMINDER_MS == 20 * 60 * 1000   # MainActivity.kt:354
    assert K.BREAK_REMINDER_SHOW_MS == 30000     # MainActivity.kt:355
    assert K.BREAK_DETECT_MS == 20000            # MainActivity.kt:356


def test_sensitivity_has_three_levers_and_they_are_not_interchangeable():
    """sensTime / sensThr / sensSmooth, MainActivity.kt:3136, :3143, :3151.

    The server had only the first one. A slider that shortens the confirmation timer but leaves the
    head-angle gate and the SEI smoothing alone is a different control from the phone's slider, and
    the operator cannot tell which half of it the machine ignored.
    """
    assert [MuscleSensitivity.time_factor(l) for l in range(4)] == [1.5, 1.0, 0.6, 0.35]
    assert [MuscleSensitivity.sens_thr(l) for l in range(4)] == [1.15, 1.0, 0.85, 0.72]
    assert [MuscleSensitivity.sens_smooth(l) for l in range(4)] == [0.7, 1.0, 1.6, 2.4]
    # Level 4, the highest label the slider offers, has no branch on the phone either: it falls
    # through to 1.0 like everything the when-block does not name.
    assert MuscleSensitivity.sens_thr(4) == 1.0
    assert MuscleSensitivity.sens_smooth(4) == 1.0


def test_the_pipeline_reads_the_sensitivity_of_its_level():
    # Compared against the literals, not against MuscleSensitivity: a test that re-derives the
    # expectation from the same function it is checking passes when that function is broken.
    thr = [1.15, 1.0, 0.85, 0.72]
    smooth = [0.7, 1.0, 1.6, 2.4]
    for level in range(4):
        p = _pipeline(level)
        assert p.sens_thr == thr[level], level
        assert p.sens_smooth == smooth[level], level


def test_unmeasured_thresholds_do_not_stop_the_chain():
    assert _pick(_pipeline(), perclos=None) == (None, False, False)


# ---------------------------------------------------------------------------
# 2. Personalisation
# ---------------------------------------------------------------------------
def test_a_stranger_keeps_the_plain_constants():
    # The phone calls applyPersonalParams with confidence 0 until the profile holds five sessions
    # and ten valid minutes; every mix() then returns the default. Same answer, same gate.
    p = _pipeline()
    assert p.thr_look_yaw == K.LOOK_AWAY_YAW_DEG
    assert p.thr_gaze_h == K.GAZE_H_DRIFT
    assert p.thr_blink_low == K.LOW_BLINK_BPM
    assert p.thr_perclos == K.PERCLOS_ALERT
    assert p.thr_mar == K.MAR_YAWN
    assert p.personal_sei_mean is None
    assert p.personal_sei_spread is None


def test_a_profile_that_is_not_ready_yet_blends_nothing():
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.YAW: 40.0}, {}, confidence=0.0))
    assert p.thr_look_yaw == K.LOOK_AWAY_YAW_DEG


def test_a_ready_profile_blends_by_its_confidence():
    p = _pipeline()
    # |mean| + 2.5 * spread, clamped to 12..30 (HabitModel.kt:418): |10| + 2.5*2 = 15.
    profile = _ReadyProfile({HabitFeature.YAW: -10.0}, {HabitFeature.YAW: 2.0}, confidence=0.5)
    p._apply_personal_params(profile)
    assert p.thr_look_yaw == 18.0 * 0.5 + 15.0 * 0.5

    # The absolute value is what makes a signed feature usable as a gate. Taking the mean as it
    # stands would hand a student who always sits turned to the left a NEGATIVE threshold, which
    # the head is above in every frame. The result is then clamped, so the comparison has to be
    # against the clamp rather than against the arithmetic that reached it.
    neg = _pipeline()
    neg._apply_personal_params(_ReadyProfile({HabitFeature.GAZE_H: -0.2},
                                             {HabitFeature.GAZE_H: 0.04}, 1.0))
    assert neg.thr_gaze_h == 0.30            # 0.2 + 2.5*0.04 = 0.3, and 0.30 is the clamp


def test_the_personal_gates_stay_inside_the_phones_clamps():
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.YAW: 55.0}, {HabitFeature.YAW: 20.0},
                                           confidence=1.0))
    assert p.thr_look_yaw == 30.0
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.PERCLOS: 2.0}, {HabitFeature.PERCLOS: 1.0},
                                           confidence=1.0))
    assert p.thr_perclos == 10.0
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.MAR: 1.0}, {HabitFeature.MAR: 0.5},
                                           confidence=1.0))
    assert p.thr_mar == 0.70


def test_blink_gates_are_two_spreads_around_this_students_own_rate():
    # MainActivity.kt:2496-2499: mean -+ 2 spreads, then coerceIn(4..12) and coerceIn(24..40). The
    # bands do not overlap, so a 26-blink student cannot get a low gate of 22 - the clamp holds it
    # at 12. Pinning the clamped answer is the point: an unclamped gate would make one ordinary
    # fidget count as "blinking too little".
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.BPM: 26.0}, {HabitFeature.BPM: 2.0},
                                           confidence=1.0))
    assert p.thr_blink_low == 12.0           # 26 - 4 = 22, clamped down to the ceiling
    assert p.thr_blink_high == 30.0          # 26 + 4 = 30, which sits inside 24..40
    # A slow blinker lands inside both bands instead.
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.BPM: 10.0}, {HabitFeature.BPM: 1.5},
                                           confidence=1.0))
    assert p.thr_blink_low == 7.0
    assert p.thr_blink_high == 24.0          # 13 clamped up to the floor
    # ...and a very steady or very restless student pushes past them.
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.BPM: 4.0}, {HabitFeature.BPM: 0.0},
                                           confidence=1.0))
    assert p.thr_blink_low == 4.0
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.BPM: 50.0}, {HabitFeature.BPM: 0.0},
                                           confidence=1.0))
    assert p.thr_blink_high == 40.0


def test_the_personal_sei_floor_is_eight_points():
    # MainActivity.kt:139. A student with an extremely flat profile would otherwise get a band so
    # narrow that one ordinary minute reads as "below your usual".
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.SEI: 80.0}, {HabitFeature.SEI: 1.0},
                                           confidence=1.0))
    assert p.personal_sei_mean == 80.0
    assert p.personal_sei_spread == 8.0
    p = _pipeline()
    p._apply_personal_params(_ReadyProfile({HabitFeature.SEI: 80.0}, {HabitFeature.SEI: 20.0},
                                           confidence=1.0))
    assert p.personal_sei_spread == 20.0


def test_a_real_profile_object_with_no_sessions_blends_nothing():
    p = _pipeline()
    p._apply_personal_params(HabitProfile("unknown-student", "Em A"))
    assert p.thr_gaze_v == K.GAZE_V_DRIFT


# ---------------------------------------------------------------------------
# 3. The alert chain
# ---------------------------------------------------------------------------
def test_the_chain_orders_drowsy_before_perclos_before_drift_before_look_away():
    """MainActivity.kt:4705-4734 is a first-match when, so order is the product.

    Two facts can be true in the same frame and only one sentence is ever shown. A chain that
    reorders them reports a different cause for the same minute, and nobody notices because both
    lines are plausible.
    """
    p = _pipeline()
    both = dict(drowsy=True, perclos=90.0, drift=True, look_away=True, yawn=True)
    assert _pick(p, **both)[0] == "DROWSY"
    assert _pick(p, **dict(both, drowsy=False))[0] == "PERCLOS_HIGH"
    assert _pick(p, **dict(both, drowsy=False, perclos=None))[0] == "VISUAL_DRIFT"
    assert _pick(p, **dict(both, drowsy=False, perclos=None, drift=False))[0] == "LOOK_AWAY"
    assert _pick(p, **dict(both, drowsy=False, perclos=None, drift=False,
                           look_away=False))[0] == "YAWN"


def test_a_bad_signal_says_so_and_nothing_else():
    """!reliable is the phone's first branch (MainActivity.kt:4705).

    Every other sentence in the chain is a conclusion drawn from the same unreadable frame, so a
    chain that lets them through is stating five things it cannot support. It also prints without
    being counted: a poor camera is not an episode of inattention.
    """
    p = _pipeline()
    code, counted, _ = _pick(p, reliable=False, drowsy=True, perclos=90.0, drift=True)
    assert code == "UNRELIABLE"
    assert counted is False


def test_three_branches_print_a_line_but_are_never_counted():
    """The phone runs the chain twice (:4704 for the sentence, :4739 for the tally) and the two
    lists differ: these three get alertCode 0. Counting them would inflate the very number the
    judges are shown."""
    p = _pipeline()
    for facts in (dict(reliable=False), dict(confused=True), dict(noisy=True)):
        code, counted, held = _pick(p, **facts)
        assert code is not None
        assert counted is False, code
    for code in ("DROWSY", "PERCLOS_HIGH", "VISUAL_DRIFT", "LOOK_AWAY", "YAWN", "LOW_BLINK",
                 "SQUINT", "ASYMMETRY", "INCOMPLETE_BLINK", "HIGH_BLINK", "BREAK",
                 "LOW_VS_PERSONAL"):
        assert code not in PersonPipeline.UNCOUNTED_ALERTS, code


def test_sitting_too_close_is_not_a_member_of_the_chain():
    # On the phone it is its own permanent line above the column (MainActivity.kt:4139 and the
    # chronic/distance block). Inside the chain it would outrank every eye reading below it.
    assert "TOO_CLOSE" not in ALERT_SENTENCES
    assert "TOO_CLOSE" not in PersonPipeline.UNCOUNTED_ALERTS


def test_only_the_two_eyelid_alerts_can_be_held():
    # MainActivity.kt:4765. PERCLOS is deliberately excluded: it is a ratio over a whole minute, so
    # delaying it frame by frame bends the measurement rather than the reading of it.
    assert PersonPipeline.HELD_ALERT_CODES == ("DROWSY", "SQUINT")


def test_a_held_alert_reaches_the_screen_but_never_the_count():
    p = _pipeline()
    # 10_000 ms is the value _pick passes, so the code is read at a fresh track. The situation layer
    # decides the hold, and this asserts the split the phone makes at :5145 rather than its timing.
    code, counted, held = _pick(p, drowsy=True)
    result = PersonResult(track_id=1, student_id="s", student_name="Em A", identity_conf="yes",
                          measurable=True, calibrated=True, sei=20, situation=Situation.FORWARD,
                          eye_valid=True, openness=0.1, perclos=0.0, bpm=None, distance_cm=None,
                          yaw=None, pitch=None,
                          alert=None if (held or not counted) else code, features={}, quality=None)
    result.hud = HudState(sei=20, alert_message=code, alert_held=held)
    texts = _texts(result)
    assert any("Nhắm mắt quá lâu" in t for t in texts)
    if held:
        assert result.alert is None
        assert any("đang hoãn cảnh báo" in t for t in texts)


def test_every_code_the_chain_can_return_has_a_vietnamese_sentence():
    # alert_sentence() falls back to the raw code, so a branch added without its sentence shows the
    # operator an English identifier - and the fallback is silent.
    p = _pipeline()
    cases = [dict(reliable=False), dict(drowsy=True), dict(perclos=90.0), dict(drift=True),
             dict(look_away=True), dict(yawn=True), dict(bpm=1.0, bpm_reliable=True),
             dict(bpm=99.0, bpm_reliable=True), dict(squint=True), dict(asym=True),
             dict(incomplete=True), dict(break_reminder=True), dict(confused=True),
             dict(low_vs_personal=True), dict(noisy=True)]
    seen = set()
    for facts in cases:
        code = _pick(p, **facts)[0]
        assert code is not None, facts
        seen.add(code)
        assert code in ALERT_SENTENCES, "no sentence for %s" % code
        assert ALERT_SENTENCES[code][0] in "⚠ℹ⏰", code
    assert len(seen) == 15


# ---------------------------------------------------------------------------
# 4. The distance line
# ---------------------------------------------------------------------------
def test_the_distance_warning_switches_on_at_30_and_off_at_32():
    p = _pipeline()
    assert p._update_distance_alert(31.0) is False
    assert p._update_distance_alert(29.0) is True      # crosses the 30 line
    assert p._update_distance_alert(31.0) is True      # still inside the gap: no second flap
    assert p._update_distance_alert(32.5) is False     # recovered
    assert p._update_distance_alert(31.0) is False     # and stays off until 30 again
    # A frame that measures no distance clears it, because the phone clears it with the face.
    assert p._update_distance_alert(29.0) is True
    assert p._update_distance_alert(None) is False


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
