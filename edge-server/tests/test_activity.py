"""Tests for the rule-based Classroom Context Engine - no camera, no model, no numpy.

    python tests/test_activity.py

Three families of checks, ported from the browser leg's pc-app/tests/test-context.mjs so both legs are
gated by the same argument, plus a fourth family that only exists here because this leg measures faces
inside a crop rather than inside the whole frame:

  1. classify() over hand-written head records, one per activity, each paired with the frame that looks
     similar and must NOT produce the same label. A rule that only ever fires is worthless.
  2. head_signals() over synthetic meshes built from one rigid head, because the social labels rest on
     a single claim: that the nose offset in the image points the way the face points. If that sign is
     wrong every social label is inverted, and no amount of rule testing would notice.
  3. The hold timer - a label may not move on one frame - and the empty-frame clock.
  4. The rebasing from crop coordinates to frame coordinates, and the HUD rows.

Nothing here quotes an accuracy number for these labels, because none has been measured.
"""
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.activity import (
    BREAK_OBJECTS, CLASS_CONTEXT_VI, CONTEXT_T, ClassContext, ClassContextEngine, HeadSignal,
    PRACTICE_OBJECTS, READING_OBJECTS, Signals, classify, head_signals, room_objects,
)
from focusedge.constants import LM_COUNT, BREAK_DETECT_MS
from focusedge.detection import CONTEXT_VI, PersonTrack, canonical_name
from focusedge.hud import hud_lines
from focusedge.landmarks import Landmarks

FRAME_W, FRAME_H = 640, 480
CROP = 200
T0 = 1000


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def _head(x, y, **kw):
    """The smallest head record the rules read; every field defaults to "pointing at the lens"."""
    fields = dict(span=0.14, yaw_deg=0.0, pitch_deg=0.0, roll_deg=0.0,
                  shift=0.0, dirx=1.0, diry=0.0, objects={})
    fields.update(kw)
    return HeadSignal(x=x, y=y, **fields)


def _sig(heads, persons=None, room=None, gone_ms=0):
    return Signals(heads=tuple(heads),
                   persons=len(heads) if persons is None else persons,
                   room=room or {}, gone_ms=gone_ms)


def _label(sig, T=CONTEXT_T):
    return classify(sig, T).context


def _why(sig, T=CONTEXT_T):
    return classify(sig, T).evidence


def _mk(**over):
    t = dict(CONTEXT_T)
    t.update(over)
    return t


# ---------------------------------------------------------------------------
# 1. The seven labels, from hand-written geometry.
# ---------------------------------------------------------------------------
def test_one_head_facing_the_lens_is_individual_study():
    one = _sig([_head(0.5, 0.5)])
    assert _label(one) == ClassContext.INDIVIDUAL_STUDY
    assert re.search(r"1/1 đầu hướng ống kính.*link 0", _why(one)), _why(one)


def test_bent_head_with_a_book_is_reading_or_writing():
    bent = _sig([_head(0.5, 0.5, pitch_deg=34.0)], room={"book": 0.61})
    assert _label(bent) == ClassContext.READING
    assert re.search(r"1/1 đầu cúi quá 22° \(34°\) · có sách 61%", _why(bent)), _why(bent)
    # Nothing else in the room either: a single bent head has no other explanation available.
    assert _label(_sig([_head(0.5, 0.5, pitch_deg=34.0)])) == ClassContext.READING
    assert "đọc hoặc viết" in CLASS_CONTEXT_VI[ClassContext.READING]


def test_a_bend_just_under_the_floor_is_not_reading():
    # Falsification: DOWN_PITCH_DEG is a gate, not a description of "leaning a bit".
    sig = _sig([_head(0.5, 0.5, pitch_deg=CONTEXT_T["DOWN_PITCH_DEG"] - 1)])
    assert _label(sig) == ClassContext.INDIVIDUAL_STUDY


def test_a_laptop_in_reach_is_practice():
    laptop = {"laptop": 0.72}
    sig = _sig([_head(0.5, 0.5, objects=laptop)], room=laptop)
    assert _label(sig) == ClassContext.PRACTICE
    assert "laptop 72%" in _why(sig), _why(sig)
    assert "thuộc đầu 1" in _why(sig), _why(sig)


def test_a_faint_laptop_box_is_not_practice():
    # Falsification: the confidence floor is shared with the phone's own penalty bar, and a box that
    # survives one pass - a neighbour's desk - must not rename the room.
    weak = {"laptop": 0.3}
    assert _label(_sig([_head(0.5, 0.5, objects=weak)], room=weak)) == ClassContext.INDIVIDUAL_STUDY
    assert CONTEXT_T["OBJ_CONF"] == 0.4


def test_two_heads_turned_at_each_other_are_pair_work():
    pair = _sig([_head(0.35, 0.5, shift=0.21, dirx=1.0), _head(0.62, 0.5, shift=-0.19, dirx=-1.0)])
    assert _label(pair) == ClassContext.PAIR_WORK
    assert re.search(r"cos 1\.00 / 1\.00, cách 0\.27 khung", _why(pair)), _why(pair)


def test_a_pair_needs_both_heads_turned_and_close():
    # Four falsifications, one per way a real room fools this rule.
    one_turned = _sig([_head(0.35, 0.5, shift=0.21, dirx=1.0), _head(0.62, 0.5)])
    assert _label(one_turned) != ClassContext.PAIR_WORK
    away = _sig([_head(0.35, 0.5, shift=-0.21, dirx=-1.0), _head(0.62, 0.5, shift=0.21, dirx=1.0)])
    assert _label(away) != ClassContext.PAIR_WORK
    far = _sig([_head(0.05, 0.5, shift=0.21, dirx=1.0), _head(0.95, 0.5, shift=-0.21, dirx=-1.0)])
    assert _label(far) != ClassContext.PAIR_WORK
    barely = CONTEXT_T["TURN_SHIFT"] - 0.02
    faint = _sig([_head(0.35, 0.5, shift=barely, dirx=1.0), _head(0.62, 0.5, shift=-barely, dirx=-1.0)])
    assert _label(faint) != ClassContext.PAIR_WORK


def test_the_near_head_facing_the_lens_with_the_room_aimed_at_it_is_a_presentation():
    presenter = _head(0.5, 0.3, span=0.30)
    audience = [_head(0.25, 0.66, shift=0.2, dirx=1.0), _head(0.75, 0.66, shift=-0.2, dirx=-1.0)]
    sig = _sig([presenter] + audience)
    assert _label(sig) == ClassContext.PRESENTATION
    assert re.search(r"2/2 đầu còn lại quay về phía em đó", _why(sig)), _why(sig)
    # Falsification: the standing child turned away is a child turned away, not a presenter.
    assert _label(_sig([_head(0.5, 0.3, span=0.30, yaw_deg=48.0)] + audience)) \
        != ClassContext.PRESENTATION
    # Falsification: nobody is nearer than the others, so nothing says "front of the class".
    assert _label(_sig([_head(0.5, 0.3, span=0.15)] + audience)) != ClassContext.PRESENTATION
    assert CONTEXT_T["PRESENTER_SPAN"] == 1.5


def test_three_heads_with_several_aims_are_a_group():
    group = [_head(0.2, 0.5, shift=0.2, dirx=1.0), _head(0.34, 0.52, shift=-0.2, dirx=-1.0),
             _head(0.3, 0.62, shift=0.2, dirx=1.0)]
    assert _label(_sig(group)) == ClassContext.GROUP_DISCUSSION
    assert _label(_sig([_head(0.2, 0.5), _head(0.4, 0.5), _head(0.6, 0.5)])) \
        == ClassContext.INDIVIDUAL_STUDY
    # Deliberate, and worth naming: PAIR_WORK asks for exactly two heads, so two children turning to
    # each other inside a group of three are reported as a group. The label describes the room.
    three_with_a_pair = _sig([_head(0.35, 0.5, shift=0.21, dirx=1.0),
                              _head(0.62, 0.5, shift=-0.21, dirx=-1.0), _head(0.9, 0.5)])
    assert _label(three_with_a_pair) == ClassContext.GROUP_DISCUSSION
    # Falsification: one head turned among three is not yet a discussion.
    assert _label(_sig([_head(0.2, 0.5, shift=0.2, dirx=1.0), _head(0.34, 0.52),
                        _head(0.5, 0.5)])) != ClassContext.GROUP_DISCUSSION


def test_nobody_facing_the_lens_and_snacks_on_the_desk_is_a_break():
    away = [_head(0.3, 0.5, yaw_deg=62.0, shift=-0.3, dirx=-1.0),
            _head(0.6, 0.5, yaw_deg=70.0, shift=0.3, dirx=1.0)]
    room = {"cup": 0.77, "banana": 0.55, "backpack": 0.6}
    sig = _sig(away, room=room)
    assert _label(sig) == ClassContext.BREAK
    assert re.search(r"cup 77%.*banana 55%", _why(sig)), _why(sig)
    # Falsification: turned heads alone are not a break; the desk has to say so too.
    assert _label(_sig(away)) == ClassContext.UNKNOWN
    # Falsification: one child still facing the lens during a snack is work, not a break.
    assert _label(_sig([_head(0.3, 0.5), away[1]], room=room)) != ClassContext.BREAK


def test_an_empty_frame_is_a_break_only_past_the_phones_own_gap():
    assert _label(_sig([], persons=2, gone_ms=25000)) == ClassContext.BREAK
    assert re.search(r"mặt vắng 25 s \(ngưỡng 20 s\), còn 2 hộp người",
                     _why(_sig([], persons=2, gone_ms=25000)))
    assert BREAK_DETECT_MS == 20000
    # None, not UNKNOWN: a person box whose mesh dropped for two seconds is missing data, and a frame
    # with no information must not rename the room. The engine's half of this seam is tested below.
    short = _sig([], persons=2, gone_ms=5000)
    assert _label(short) is None
    assert re.search(r"2 hộp người nhưng chưa có mặt nào trong 5\.0 s", _why(short)), _why(short)
    assert _label(_sig([], persons=0, gone_ms=40000)) == ClassContext.UNKNOWN
    assert classify(None).context == ClassContext.UNKNOWN


def test_the_object_sets_are_the_ones_the_detector_can_report():
    # Falsification with teeth: a name layer 1 never emits makes its rule dead code, silently. The
    # browser leg's list and this leg's whitelist grew up separately, so this is the check that stops
    # BREAK from being a label that can only fire on the projector.
    assert "laptop" in PRACTICE_OBJECTS and "apple" not in PRACTICE_OBJECTS
    assert "backpack" in BREAK_OBJECTS and "bottle" in BREAK_OBJECTS
    assert ",".join(READING_OBJECTS) == "book"
    missing = [n for n in set(PRACTICE_OBJECTS) | set(READING_OBJECTS) | set(BREAK_OBJECTS)
               if canonical_name(n) not in CONTEXT_VI]
    assert not missing, "detector cannot name these: %s" % missing
    assert canonical_name("cell phone") == "phone"


# ---------------------------------------------------------------------------
# 2. The geometry layer, on meshes built from one rigid head.
# ---------------------------------------------------------------------------
def _mesh(cx=0.5, cy=0.5, span=0.22, yaw=0.0, pitch=0.0, crop=CROP):
    """One rigid head, placed in the frame and returned as (landmarks, face_bbox).

    Built in PIXELS first, then normalised the way FaceLandmarker hands a crop back. The order matters
    and was learned on the browser leg's first run of this suite: head_pose_deg multiplies x and z by
    the region width but y by its height, so a model written directly in normalised units hands that
    function two vectors that are orthogonal in pixels and skewed in the mixed space - a 25 degree
    pitch alone came back as 23 degrees of yaw.
    """
    a, b = math.radians(yaw), math.radians(pitch)
    X = (math.cos(a), 0.0, math.sin(a))            # 234 -> 454
    Y = (0.0, -math.cos(b), -math.sin(b))          # chin -> forehead, tipped by pitch
    Z = (math.sin(a), math.cos(a) * math.sin(b), -math.cos(a) * math.cos(b))
    unit = span * FRAME_W / 2.0                    # one unit = half the eye span, in pixels

    def at(vx, vy, vz):
        px = cx * FRAME_W + (X[0] * vx + Y[0] * vy + Z[0] * vz) * unit
        py = cy * FRAME_H + (X[1] * vx + Y[1] * vy + Z[1] * vz) * unit
        pz = (X[2] * vx + Y[2] * vy + Z[2] * vz) * unit
        bx0 = cx * FRAME_W - crop / 2.0
        by0 = cy * FRAME_H - crop / 2.0
        return ((px - bx0) / crop, (py - by0) / crop, pz / crop)

    data = [0.0] * (LM_COUNT * 3)

    def put(idx, p):
        data[idx * 3] = p[0]
        data[idx * 3 + 1] = p[1]
        data[idx * 3 + 2] = p[2]

    origin = at(0, 0, 0)
    for i in range(LM_COUNT):
        put(i, origin)
    e_a, e_b = at(-1, 0, 0), at(1, 0, 0)
    for i in (33, 160, 158, 133, 153, 144):
        put(i, e_a)
    for i in (362, 385, 387, 263, 373, 380):
        put(i, e_b)
    put(1, at(0, -0.05, 0.54))                     # the nose sits out along the face normal
    put(10, at(0, 1.2, 0))                         # forehead
    put(152, at(0, -1.2, 0))                       # chin
    put(234, at(-1.3, 0, 0))
    put(454, at(1.3, 0, 0))
    box = (int(cx * FRAME_W - crop / 2.0), int(cy * FRAME_H - crop / 2.0),
           int(cx * FRAME_W + crop / 2.0), int(cy * FRAME_H + crop / 2.0))
    return Landmarks(data, LM_COUNT), box


def _signals(meshes, owners=()):
    """The head records a frame of meshes produces, with each head's own object map attached."""
    nearby = list(owners) + [{}] * (len(meshes) - len(owners))
    return head_signals([(lm, box, n) for (lm, box), n in zip(meshes, nearby)], FRAME_W, FRAME_H)


def test_a_flat_mesh_reads_frontal_with_no_direction():
    flat = _signals([_mesh()])[0]
    assert abs(flat.yaw_deg) < 1.0, flat
    assert abs(flat.pitch_deg) < 1.0, flat
    assert abs(flat.shift) < CONTEXT_T["TURN_SHIFT"], flat
    assert abs(flat.span - 0.22) < 0.001, flat.span


def test_a_turned_mesh_recovers_the_angle_and_points_the_way_it_turned():
    turned = _signals([_mesh(yaw=30.0)])[0]
    assert abs(turned.yaw_deg - 30.0) <= 2.0, turned.yaw_deg
    assert turned.shift > CONTEXT_T["TURN_SHIFT"]
    # QUY TẮC AIM: the sign of the nose offset, not the sign of yaw, decides which way the head points.
    assert turned.dirx > 0.9, turned
    assert _signals([_mesh(yaw=-30.0)])[0].dirx < -0.9


def test_a_bent_mesh_recovers_pitch_and_invents_no_direction():
    bent = _signals([_mesh(pitch=25.0)])[0]
    assert abs(bent.pitch_deg - 25.0) <= 2.0, bent.pitch_deg
    assert abs(bent.shift) < CONTEXT_T["TURN_SHIFT"], bent.shift


def test_two_meshes_built_as_a_pair_read_as_a_pair():
    # The same pair as the hand-written record, but from meshes: if the aim claim above is wrong, this
    # is the check that goes red, because a real left-turn / real right-turn pair must be PAIR_WORK.
    heads = _signals([_mesh(cx=0.36, yaw=24.0), _mesh(cx=0.62, yaw=-24.0)])
    assert _label(_sig(heads)) == ClassContext.PAIR_WORK
    crossed = _signals([_mesh(cx=0.36, yaw=-24.0), _mesh(cx=0.62, yaw=24.0)])
    assert _label(_sig(crossed)) != ClassContext.PAIR_WORK


def test_a_head_inherits_the_objects_of_the_box_that_contains_it():
    # The crop-to-frame rebasing is this leg's own risk: the browser leg measures the whole frame, so
    # a head's x is already comparable with another head's. Here two heads from two different crops are
    # only comparable because head_signals put them back through their boxes.
    left, right = _mesh(cx=0.3), _mesh(cx=0.7)
    heads = head_signals([(left[0], left[1], {"laptop": 0.7}),
                          (right[0], right[1], {"book": 0.66})], FRAME_W, FRAME_H)
    assert len(heads) == 2
    assert heads[0].objects.get("laptop", 0) > 0.6 and "book" not in heads[0].objects
    assert heads[1].objects.get("book", 0) > 0.6 and "laptop" not in heads[1].objects
    assert heads[0].x < heads[1].x, [h.x for h in heads]
    assert abs(heads[1].x - 0.7) < 0.05, heads[1].x


def test_a_head_without_objects_and_a_mesh_too_short_are_skipped():
    lone = _mesh()
    assert not head_signals([(lone[0], lone[1], None)], FRAME_W, FRAME_H)[0].objects
    assert len(head_signals([(lone[0], None, {})], FRAME_W, FRAME_H)) == 0
    assert len(_signals([_mesh(span=0.01)])) == 0
    # The guard is the largest eye-ring index (263). head_pose_deg wants landmark 454 and answers None
    # for a short set, but a truncated mesh is not a head the rules should vote on at all.
    short = Landmarks([0.5, 0.5, 0.0] * 263, 263)
    assert len(head_signals([(short, (0, 0, 200, 200), {})], FRAME_W, FRAME_H)) == 0


def test_room_objects_keeps_the_best_confidence_per_name():
    owner = PersonTrack(track_id=1, bbox=(0, 0, 10, 10), conf=0.8,
                        nearby_objects={"cup": 0.5})
    room = room_objects([owner], [("cup", (0, 0, 5, 5), 0.81), ("book", (0, 0, 5, 5), 0.44)])
    assert room["cup"] == 0.81, room
    assert room["book"] > 0.4, room
    assert room_objects(None, None) == {}


# ---------------------------------------------------------------------------
# 3. The hold: a label may not move on one frame.
# ---------------------------------------------------------------------------
def test_the_first_real_label_lands_at_once_and_the_second_waits_for_the_hold():
    single = _sig([_head(0.5, 0.5)])
    pair = _sig([_head(0.35, 0.5, shift=0.21, dirx=1.0), _head(0.62, 0.5, shift=-0.19, dirx=-1.0)])
    eng = ClassContextEngine(_mk())
    s0 = eng.update(single, T0)
    assert eng.context == ClassContext.INDIVIDUAL_STUDY
    assert eng.switches == 0, "the first label is not a switch"
    s0 = eng.update(pair, T0 + 100)
    assert eng.context == ClassContext.INDIVIDUAL_STUDY
    assert "giữ" in s0.evidence, s0.evidence
    assert "làm việc cặp" in s0.evidence and "học cá nhân" in s0.evidence, s0.evidence
    eng.update(pair, T0 + CONTEXT_T["HOLD_MS"] - 1)
    assert eng.context == ClassContext.INDIVIDUAL_STUDY
    eng.update(pair, T0 + CONTEXT_T["HOLD_MS"] + 100)
    assert eng.context == ClassContext.PAIR_WORK
    assert eng.switches == 1
    eng.update(single, T0 + CONTEXT_T["HOLD_MS"] + 200)
    assert eng.context == ClassContext.PAIR_WORK, "back to the old label needs its own hold too"
    snap = eng.snapshot(T0 + 9000)
    for f in ("context", "raw", "label", "evidence", "switches", "held_ms"):
        assert hasattr(snap, f), f


def test_an_interrupted_proposal_restarts_the_clock():
    single = _sig([_head(0.5, 0.5)])
    pair = _sig([_head(0.35, 0.5, shift=0.21, dirx=1.0), _head(0.62, 0.5, shift=-0.19, dirx=-1.0)])
    eng = ClassContextEngine(_mk())
    eng.update(single, T0)
    eng.update(pair, T0 + 100)
    eng.update(single, T0 + 900)
    eng.update(pair, T0 + 1000)
    eng.update(pair, T0 + 1500)
    assert eng.context == ClassContext.INDIVIDUAL_STUDY, "1500 ms from the RESTART, not from T0+100"
    eng.update(pair, T0 + 2600)
    assert eng.context == ClassContext.PAIR_WORK


def test_a_dropped_mesh_does_not_spend_somebody_elses_clock():
    single = _sig([_head(0.5, 0.5)])
    pair = _sig([_head(0.35, 0.5, shift=0.21, dirx=1.0), _head(0.62, 0.5, shift=-0.19, dirx=-1.0)])
    empty = _sig([], persons=2)
    eng = ClassContextEngine(_mk())
    eng.update(single, T0)
    eng.update(pair, T0 + 100)                 # proposal starts here
    snap = eng.update(empty, T0 + 900)         # mesh drops for a moment
    assert eng.raw is None
    assert eng.context == ClassContext.INDIVIDUAL_STUDY
    assert re.search(r"khung này không có mặt nào để đọc .*giữ nhãn trước", snap.evidence), \
        snap.evidence
    eng.update(pair, T0 + 1000)                # and restarts here, not at T0+100
    eng.update(pair, T0 + 2400)
    assert eng.context == ClassContext.INDIVIDUAL_STUDY
    eng.update(pair, T0 + 2500)
    assert eng.context == ClassContext.PAIR_WORK


def test_a_long_absence_proposes_a_break_and_switches_after_the_hold():
    single = _sig([_head(0.5, 0.5)])
    empty = _sig([], persons=2)
    eng = ClassContextEngine(_mk())
    eng.update(single, T0)
    for t in range(T0 + 200, T0 + 21001, 100):
        eng.update(empty, t)
    assert eng.raw == ClassContext.BREAK
    assert eng.context == ClassContext.INDIVIDUAL_STUDY, "the hold still has to run"
    snap = None
    for t in range(T0 + 21100, T0 + 22701, 100):
        snap = eng.update(empty, t)
    assert eng.context == ClassContext.BREAK
    assert re.search(r"mặt vắng 2[0-9] s \(ngưỡng 20 s\)", snap.evidence), snap.evidence


def test_reset_clears_the_label_the_clock_and_the_evidence():
    eng = ClassContextEngine(_mk())
    eng.update(_sig([_head(0.5, 0.5)]), T0)
    eng.update(_sig([], persons=2), T0 + 5000)
    eng.reset()
    assert eng.context == ClassContext.UNKNOWN
    assert eng.empty_since_ms == 0
    assert eng.evidence == "chưa có khung nào"
    assert eng.switches == 0 and eng.samples == 0


# ---------------------------------------------------------------------------
# 4. What the column prints.
# ---------------------------------------------------------------------------
class _Snap:
    """The two fields the HUD reads; a snapshot from the engine carries the same names."""

    def __init__(self, label, evidence):
        self.label = label
        self.evidence = evidence


def _texts(result=None, face_count=1, activity=None):
    return [ln.text for ln in hud_lines(result, face_count, float("nan"), float("nan"),
                                        activity=activity)]


def test_the_column_prints_the_room_label_and_its_evidence():
    snap = _Snap(CLASS_CONTEXT_VI[ClassContext.GROUP_DISCUSSION],
                 "3 đầu, 2 đầu quay sang bạn gần (link 2, cos 0.88)")
    texts = _texts(face_count=3, activity=snap)
    assert "Hoạt động lớp: thảo luận nhóm" in texts
    assert "3 đầu, 2 đầu quay sang bạn gần" in "\n".join(texts)
    # CÂU THỪA NHẬN LUẬT VIẾT TAY: these rules were hand-written and no labelled classroom video has
    # ever been scored against them, so the row that shows a label also says that. A test, because the
    # sentence is the kind of thing a tidy-up edit drops.
    assert "luật viết tay, chưa kiểm định bằng dữ liệu có nhãn" in "\n".join(texts)


def test_the_activity_row_sits_directly_under_the_situation_row():
    snap = _Snap(CLASS_CONTEXT_VI[ClassContext.PAIR_WORK], "2 đầu quay vào nhau (cos 1.00 / 1.00)")
    texts = _texts(face_count=2, activity=snap)
    i = texts.index([t for t in texts if t.startswith("Tình huống:")][0])
    assert texts[i + 1].startswith("Hoạt động lớp: làm việc cặp"), texts[i:i + 3]


def test_a_caller_with_no_context_layer_still_prints_the_row_and_no_second_line():
    plain = _texts(face_count=3)
    assert "Hoạt động lớp: --" in plain
    assert "luật viết tay" not in "\n".join(plain)
    with_activity = _texts(face_count=3, activity=_Snap("học cá nhân", "1/1 đầu hướng ống kính"))
    # One row, not two: the label row is always there, and the evidence line only joins when there is
    # evidence to show.
    assert len(with_activity) - len(plain) == 1, (len(with_activity), len(plain))


# ---------------------------------------------------------------------------
# 5. The wiring, i.e. the part only classroom.py can get wrong.
# ---------------------------------------------------------------------------
def _bare_pipeline():
    from focusedge.classroom import ClassroomPipeline
    cfg = {"class_id": "lop12a", "person": {"sensitivity": 1}, "scheduler": {"min_eye_span_px": 12}}
    return ClassroomPipeline(cfg, None, None, None, None, None, None)


def test_the_pipeline_updates_the_room_label_and_paces_it_like_the_web_leg():
    cp = _bare_pipeline()
    heads = [_mesh(cx=0.36, yaw=24.0), _mesh(cx=0.62, yaw=-24.0)]
    measured = [(heads[0][0], heads[0][1], {}), (heads[1][0], heads[1][1], {})]
    cp._update_activity(measured, [PersonTrack(1, (0, 0, 300, 480), 0.8),
                                   PersonTrack(2, (300, 0, 640, 480), 0.8)], [],
                        FRAME_W, FRAME_H, T0)
    assert cp.last_activity is not None
    assert cp.last_activity.label == CLASS_CONTEXT_VI[ClassContext.PAIR_WORK], cp.last_activity
    assert cp.activity.samples == 1

    # A second sample inside SAMPLE_MS is dropped on purpose: the rules are arithmetic over every head
    # pair, and this box measures 8 detection passes a second (config.yaml detect_fps).
    cp._update_activity(measured, [], [], FRAME_W, FRAME_H, T0 + 40)
    assert cp.activity.samples == 1, "the sample gate must hold"
    cp._update_activity(measured, [], [], FRAME_W, FRAME_H, T0 + 120)
    assert cp.activity.samples == 2


def test_a_pipeline_that_measured_nothing_still_counts_the_boxes_it_saw():
    cp = _bare_pipeline()
    one = _mesh()
    cp._update_activity([(one[0], one[1], {})], [PersonTrack(1, (0, 0, 300, 480), 0.8)], [],
                        FRAME_W, FRAME_H, T0)
    assert cp.last_activity.context == ClassContext.INDIVIDUAL_STUDY
    # Nobody measurable, but three bodies in shot: too short a gap to be a break, so no vote at all.
    cp._update_activity([], [PersonTrack(i, (0, 0, 10, 10), 0.8) for i in (1, 2, 3)], [],
                        FRAME_W, FRAME_H, T0 + 120)
    assert cp.last_activity.raw is None
    assert "chưa có mặt nào" in cp.last_activity.evidence, cp.last_activity.evidence


# ---------------------------------------------------------------------------
# Khẩu lệnh giáo viên (focusedge/teacher_voice.py) nối vào _update_activity.
# Năm test này là hàng rào chống lại đúng cái hỏng đã đo được: teacher_voice từng khởi động ở LECTURE
# với last_speech "Hệ thống sẵn sàng", và classroom.py coi đó là khẩu lệnh thật -> mọi phiên bị dán
# nhãn "nghe giảng (giọng nói GV)" trong 3 phút đầu dù không có micro. Nhãn phải do luật viết tay đọc
# từ hình ảnh, trừ khi CÓ một khẩu lệnh thật và khung đó CÓ mặt để đọc.
# ---------------------------------------------------------------------------
def _voice():
    from focusedge.teacher_voice import TeacherContextAI, get_teacher_voice
    tv = get_teacher_voice()
    tv.set_context(TeacherContextAI.CONTEXT_UNKNOWN)
    return tv


def _one_head_pipeline():
    cp = _bare_pipeline()
    one = _mesh()
    cp._update_activity([(one[0], one[1], {})], [PersonTrack(1, (0, 0, 300, 480), 0.8)], [],
                        FRAME_W, FRAME_H, T0)
    return cp


def test_without_a_teacher_command_the_label_is_the_rules_own():
    tv = _voice()
    try:
        cp = _one_head_pipeline()
        assert cp.last_activity.context == ClassContext.INDIVIDUAL_STUDY, cp.last_activity
        assert cp.last_activity.label == CLASS_CONTEXT_VI[ClassContext.INDIVIDUAL_STUDY]
        assert "khẩu lệnh" not in cp.last_activity.label and "GV chọn" not in cp.last_activity.label
    finally:
        tv.set_context(tv.CONTEXT_UNKNOWN)


def test_a_heard_command_switches_the_label_and_says_where_it_came_from():
    tv = _voice()
    try:
        assert tv.feed_text("cả lớp bắt đầu thảo luận nhóm") == tv.CONTEXT_GROUP_WORK
        cp = _one_head_pipeline()
        assert cp.last_activity.context == ClassContext.GROUP_DISCUSSION, cp.last_activity
        assert "khẩu lệnh GV" in cp.last_activity.label, cp.last_activity.label
        assert "bắt đầu thảo luận" in cp.last_activity.evidence, cp.last_activity.evidence
    finally:
        tv.set_context(tv.CONTEXT_UNKNOWN)


def test_a_clicked_context_is_not_reported_as_a_voice_command():
    tv = _voice()
    try:
        tv.set_context(tv.CONTEXT_STUDY_NOTE, reason="dashboard_api")
        cp = _one_head_pipeline()
        assert cp.last_activity.context == ClassContext.READING, cp.last_activity
        assert "GV chọn" in cp.last_activity.label, cp.last_activity.label
        assert "khẩu lệnh" not in cp.last_activity.label
    finally:
        tv.set_context(tv.CONTEXT_UNKNOWN)


def test_an_expired_command_hands_the_label_back_to_the_rules():
    import time
    tv = _voice()
    try:
        tv.feed_text("các em thảo luận nhóm đôi")
        # Đẩy mốc đặt khẩu lệnh về quá khứ thay vì ngủ: cùng một đường hết hạn mà get_status() dùng.
        tv.last_switch_time = time.time() - (tv.auto_reset_seconds + 5)
        assert tv.get_status()["has_command"] is False
        assert tv.get_status()["context"] == tv.CONTEXT_UNKNOWN
        cp = _one_head_pipeline()
        assert cp.last_activity.context == ClassContext.INDIVIDUAL_STUDY, cp.last_activity
    finally:
        tv.set_context(tv.CONTEXT_UNKNOWN)


def test_a_command_does_not_invent_activity_when_no_face_was_measured():
    tv = _voice()
    try:
        tv.feed_text("bắt đầu thảo luận")
        cp = _bare_pipeline()
        cp._update_activity([], [PersonTrack(i, (0, 0, 10, 10), 0.8) for i in (1, 2, 3)], [],
                            FRAME_W, FRAME_H, T0)
        assert cp.last_activity.raw is None
        assert "chưa có mặt nào" in cp.last_activity.evidence, cp.last_activity.evidence
        assert "khẩu lệnh" not in cp.last_activity.label
    finally:
        tv.set_context(tv.CONTEXT_UNKNOWN)


def test_a_command_does_not_invent_activity_in_an_empty_room():
    tv = _voice()
    try:
        tv.feed_text("bắt đầu thảo luận")
        cp = _bare_pipeline()
        cp._update_activity([], [], [], FRAME_W, FRAME_H, T0)
        assert cp.last_activity.raw == ClassContext.UNKNOWN, cp.last_activity
        assert cp.last_activity.label == CLASS_CONTEXT_VI[ClassContext.UNKNOWN]
        assert "khẩu lệnh" not in cp.last_activity.label
    finally:
        tv.set_context(tv.CONTEXT_UNKNOWN)


def test_a_command_still_labels_a_room_whose_heads_look_away():
    tv = _voice()
    try:
        tv.feed_text("cả lớp chú ý nhìn lên bảng")
        cp = _bare_pipeline()
        # Một đầu quay hẳn 60°: không hướng ống kính, không cúi, không quay sang bạn nào -> luật
        # viết tay bó tay (UNKNOWN). Đúng lúc nghe giảng cả lớp nhìn lên bảng, nên lời giáo viên
        # là bằng chứng thật và được phép đặt nhãn, miễn trong khung CÓ người.
        away = _mesh(yaw=60.0)
        cp._update_activity([(away[0], away[1], {})], [PersonTrack(1, (0, 0, 300, 480), 0.8)], [],
                            FRAME_W, FRAME_H, T0)
        assert cp.last_activity.raw == ClassContext.UNKNOWN, cp.last_activity
        assert cp.last_activity.context == ClassContext.PRESENTATION, cp.last_activity
        assert "khẩu lệnh GV" in cp.last_activity.label
    finally:
        tv.set_context(tv.CONTEXT_UNKNOWN)


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


def load_tests(loader, tests, pattern):
    import unittest
    suite = unittest.TestSuite()
    for k, v in sorted(globals().items()):
        if k.startswith("test_") and callable(v):
            suite.addTest(unittest.FunctionTestCase(v))
    return suite


if __name__ == "__main__":
    sys.exit(main())
