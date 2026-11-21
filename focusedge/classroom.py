"""The orchestrator - wires all five layers into one classroom run.

This is the only module that knows about every other one. Its job per frame:

  detect (YOLO)  ->  bind identity (ArcFace+roster)  ->  schedule faces (ROI budget)
  ->  measure (MediaPipe + PersonPipeline)  ->  aggregate & store (SQLite + out/)

Third-party imports (cv2/numpy/ultralytics/insightface/mediapipe) are all
lazy: the module imports and its bookkeeping is testable without a GPU or models,
and the heavy objects are only built when ``ClassroomPipeline`` is constructed.
"""
from __future__ import annotations

import math
import os
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Deque, Dict, List, NamedTuple, Optional, Tuple

from .activity import (
    CONTEXT_T, ClassContext, ClassContextEngine, ClassSnapshot, Signals, head_signals, room_objects,
)
from .constants import HabitFeature, Situation
from .context_eval import ContextEvaluator
from .habit import HabitProfile
from .landmarks import focal_px
from .person_pipeline import PersonPipeline, PersonResult
from .quality import face_light, to_gray
from .teacher_voice import TeacherContextAI, get_teacher_voice
from .tic_watch import TicWatch

# Khẩu lệnh giáo viên -> nhãn của activity.py. Chỉ dùng khi CÓ khẩu lệnh thật (xem _update_activity).
# Nhãn giữ đúng chữ của CLASS_CONTEXT_VI rồi thêm nguồn trong ngoặc, để người đọc biết đây là lời
# giáo viên chứ không phải luật viết tay đọc được từ hình ảnh. SEATWORK/Q_AND_A (tài liệu 3) dùng
# nhãn riêng cho lời người đọc; enum vẫn là hạng mục thị giác gần nhất (READING/PRESENTATION).
_VOICE_CONTEXT = {
    TeacherContextAI.CONTEXT_GROUP_WORK: (ClassContext.GROUP_DISCUSSION, "thảo luận nhóm"),
    TeacherContextAI.CONTEXT_STUDY_NOTE: (ClassContext.READING, "đọc hoặc viết / tự học"),
    TeacherContextAI.CONTEXT_LECTURE: (ClassContext.PRESENTATION, "nghe giảng"),
    TeacherContextAI.CONTEXT_SEATWORK: (ClassContext.READING, "làm bài tập / tự học"),
    TeacherContextAI.CONTEXT_Q_AND_A: (ClassContext.PRESENTATION, "hỏi – đáp"),
}

# edge-server/, whichever folder the process was started from.
_PACKAGE_PARENT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def resolve_asset(value: str, root: Optional[str] = None) -> str:
    """Find a model or roster file that config.yaml spells relative to edge-server/.

    The .bat changes into edge-server/ before starting, but "python edge-server/main.py" from the
    repo root does not, and every relative asset path then points at a different place than the one
    the operator read in the config. Candidates, in order: the string as given, the same string
    under edge-server/, and just its file name under edge-server/. The configured string is
    returned when nothing exists, so the failure names what was asked for instead of a guess.
    """
    root = root or _PACKAGE_PARENT
    for candidate in (value, os.path.join(root, value),
                      os.path.join(root, os.path.basename(value))):
        if os.path.exists(candidate):
            return candidate
    return value


def _now_ms() -> int:
    return int(time.monotonic() * 1000)


def _wall_ms() -> int:
    return int(time.time() * 1000)


class DetectionView(NamedTuple):
    """What layer 1 saw on the last frame, kept only so the --show window can draw it.

    step() returns PersonResults, and a result exists only for a face the scheduler chose to
    measure on this tick. That is the right thing for the numbers column and the wrong thing for
    "show me YOLO": with a face budget of 2, four students in frame would draw two boxes and an
    operator would conclude the detector missed two. So the raw layer-1 output travels here
    instead - every box ByteTrack returned, every context object that fell inside no box, and the
    face window each person box was cut into. Measured values never read from it, so nothing in
    this struct can change a stored number.
    """

    persons: tuple = ()                    # PersonTrack: .bbox .track_id .conf .nearby_objects
    objects: tuple = ()                    # (name, bbox, conf) assigned to no person
    face_regions: Tuple[Tuple[int, tuple], ...] = ()   # (track_id, face box) fed to FaceLandmarker
    frame_size: Tuple[int, int] = (0, 0)   # (w, h) the coordinates belong to


class PersonView(NamedTuple):
    """One entry of the head menu: who is in frame, and how sure the machine is about the name.

    Layer 4 is deliberately absent. The preview has to be able to say "em 2/3 · Nam Nguyen · điểm
    khớp 0.61" about a child whose face the scheduler did not pick this tick, and the only honest
    source for that half of the sentence is layer 2.
    """

    track_id: int
    student_id: Optional[str]
    student_name: str
    identity_conf: str
    score: float = 0.0                     # cosine the gates were compared against, 0 before any
    margin: float = 0.0                    # how far that template beats the next-closest student
    samples: int = 0                       # face samples averaged behind the score
    runner_up: str = ""                    # second-closest student_id, "" when there is only one
    gate: float = 0.0                      # bind_hi as configured, so the line can name the bar
    margin_gate: float = 0.0               # min_margin as configured
    measurable: bool = False               # did this face produce numbers recently at all
    seat_hint: str = ""                    # remembered seat occupant's name, "" when no suggestion
    attention_label: str = ""              # teacher-context verdict, "" before any measured frame
    attention_reason: str = ""


@dataclass
class TrackRuntime:
    """Everything the orchestrator remembers about one ByteTrack id."""

    track_id: int
    identity: object                       # IdentityState (duck-typed to avoid import)
    pipeline: PersonPipeline
    student_id: Optional[str] = None
    student_name: str = ""
    identity_conf: str = "unbound"
    # The operator's own answer to "who is this", set with the m key. Kept beside, not instead of,
    # the recognition state: releasing the manual binding has to leave the ArcFace verdict intact.
    manual_student_id: Optional[str] = None
    last_seen_ms: int = 0
    last_measured_ms: int = 0
    eye_span_px: float = 0.0               # 0 -> bootstrap candidate until first measured
    last_result: Optional[PersonResult] = None
    nearby: Dict[str, float] = field(default_factory=dict)
    # Counted here instead of read back out of the alert log: that log is a 200-item ring,
    # so a busy classroom would quietly lose the earliest alerts of a session and the number
    # stored would be short without anything on screen saying so. ``alerts`` is the same list
    # kept per session rather than per run, so the alerts table gets one row per alert and the
    # count in the session's notes can never disagree with the rows beside it.
    alert_count: int = 0
    alerts: List[Dict] = field(default_factory=list)
    measured_frames: int = 0
    # Per-track attention verdict against the teacher's spoken context (teacher_voice.py).
    # Display only: it never opens an alert or changes a stored number.
    attention_eval: object = None
    attention_label: str = ""
    attention_reason: str = ""
    # Wall clock of the moment this session started. The phone derives it from its session id,
    # which IS the start timestamp; here the id is a per-student counter, so the start time is
    # remembered separately rather than recomputed from the measured time.
    session_started_wall_ms: int = 0
    # Lớp khuyến nghị sư phạm chạy SONG SONG với chuỗi parity (không đụng SEI/alerts_total):
    # đánh giá tập trung theo khẩu lệnh GV (context_eval.py, tài liệu 2+3) và theo dõi dấu
    # hiệu giật lặp lại kiểu Tic (tic_watch.py, tài liệu 1). Mỗi track một bộ, sống theo phiên.
    context_eval: ContextEvaluator = field(default_factory=ContextEvaluator)
    tic: TicWatch = field(default_factory=TicWatch)


class ClassroomPipeline:
    # head region of the person box fed to FaceLandmarker (faces live near the top)
    FACE_TOP_FRACTION = 0.65
    # Fraction of that region allowed to fall outside the frame before the face is skipped.
    # 0.25 was tuned for a tripod 5m away; for a laptop webcam 50-80cm away the face fills
    # the frame much more and often touches the top/side edges. 0.50 lets those frames through.
    CLIP_MAX_FRACTION = 0.50
    # Seat memory: a manually named head that leaves the frame remembers its seat (normalized
    # face-center) for this long, and a new guest head appearing within this radius is OFFERED
    # that name on the first m press. Offered, never auto-bound: two children swapping seats
    # must not silently swap their data.
    SEAT_RADIUS = 0.10
    SEAT_TTL_MS = 600_000

    def __init__(self, cfg: dict, detector, embedder, roster, analyzer, scheduler,
                 writer, profiles: Optional[Dict[str, HabitProfile]] = None,
                 roster_path: Optional[str] = None) -> None:
        self._cfg = cfg
        self._detector = detector
        self._embedder = embedder          # may be None (identity disabled)
        self._roster = roster
        # Where the roster was loaded from, so a name typed at the keyboard by the operator can be
        # written back. Tests hand in a Roster object and no path, and then it stays unsaved.
        self._roster_path = roster_path if isinstance(roster_path, str) else None
        self._analyzer = analyzer
        self._scheduler = scheduler
        self._writer = writer
        self._profiles: Dict[str, HabitProfile] = profiles or {}

        det = cfg.get("detection", {})
        ident = cfg.get("identity", {})
        sched = cfg.get("scheduler", {})
        person = cfg.get("person", {})
        storage = cfg.get("storage", {})

        self.class_id = cfg.get("class_id", "class")
        # What the operator and the judges read on the preview. class_id stays the stored key and
        # is an ASCII slug, so the two are deliberately different strings.
        self.class_label = cfg.get("class_label") or self.class_id
        self._track_ttl_ms = int(det.get("track_ttl_ms", 15000))
        self._sensitivity = int(person.get("sensitivity", 1))
        self._habit_learn = bool(person.get("habit_learn", True))
        self._min_span = float(sched.get("min_eye_span_px", 12))
        self._reverify_ms = int(ident.get("reverify_ms", 30000))
        self._identity_enabled = embedder is not None
        # Teacher's rule: 10 minutes of looking elsewhere before a head counts as off-task.
        self._off_task_s = float(cfg.get("teacher_voice", {}).get("off_task_seconds", 600))

        # Layer 5 used to be two copies of every number - the cloud writer and a 5-table mirror
        # beside it. There is one copy now: the writer IS the database, so a disk that refuses the
        # file is reported by the writer's own counter and nothing here has to guard it twice.
        self._class_live_ms = int(storage.get("class_live_ms", 20000))
        self._heartbeat_ms = int(storage.get("heartbeat_ms", 300000))
        self._device = cfg.get("input", {}).get("source", "edge")
        # from_config() installs a real IdentityResolver; a directly built pipeline
        # (tests) keeps identity off.
        self._resolver = None

        self._tracks: Dict[int, TrackRuntime] = {}
        self._seat_memory: Dict[str, Dict] = {}
        self._last_wh = (0, 0)
        self._alerts: Deque[Dict] = deque(maxlen=200)
        # Khuyến nghị (off-task theo ngữ cảnh, dấu hiệu Tic) - tách khỏi _alerts của parity:
        # không đếm vào alerts_total, không ghi vào bảng alerts, đi vào notes JSON + class_live.
        self._advisories: Deque[Dict] = deque(maxlen=200)
        # Preview-only: the last frame's raw layer-1 output. Overwritten every step().
        self.last_boxes = DetectionView()

        # What the room is doing, one label for the whole class rather than one per head. Kept here
        # because only step() has the meshes, the boxes and the objects of one and the same frame, and
        # a room label built from two different frames is a claim nobody can check.
        self.activity = ClassContextEngine()
        self.last_activity: Optional[ClassSnapshot] = None
        self._activity_at_ms = 0
        self._activity_sample_ms = int(CONTEXT_T["SAMPLE_MS"])

        self._last_class_live_ms = 0
        self._last_heartbeat_ms = 0
        self._last_step_ms = 0
        self._frame_dt_ms = 33.0

        # YOLO stride: 1 = detect on every step (the old behavior). N > 1 reuses the previous
        # YOLO boxes on the intermediate steps and only runs FaceLandmarker there, so blink
        # sampling can run faster than one ~145 ms detection pass per frame allows. The boxes
        # are at most N-1 frames stale; the alert windows downstream are seconds long.
        self._yolo_every = max(1, int(det.get("yolo_every", 1)))
        self._step_count = 0
        self._cached_persons = []
        self._cached_objects = []
        self._cached_wh = (0, 0)
        self.last_yolo_ran = True

    # ------------------------------------------------------------------
    # construction helper kept here so main.py stays thin
    # ------------------------------------------------------------------
    @classmethod
    def from_config(cls, cfg: dict) -> "ClassroomPipeline":
        from .detection import Detector
        from .face_landmarker import FaceAnalyzer
        from .identity import FaceEmbedder, IdentityResolver
        from .local_store import LocalStore
        from .roster import Roster
        from .scheduler import Scheduler

        det_cfg = cfg.get("detection", {})
        ident_cfg = cfg.get("identity", {})
        sched_cfg = cfg.get("scheduler", {})
        storage_cfg = cfg.get("storage", {})
        io_cfg = cfg.get("local_output", {})

        # The weights name is resolved the same way the eye model is. It matters more than it looks:
        # ultralytics happily DOWNLOADS a model it cannot find, so running from the repo root with the
        # weights sitting in edge-server/ silently fetched a second 5.4 MB copy into the wrong folder
        # instead of failing - and a machine with no internet then waits instead of measuring.
        detector = Detector(
            model=resolve_asset(det_cfg.get("model", "yolo11m.pt")),
            conf=float(det_cfg.get("conf", 0.35)),
            tracker=det_cfg.get("tracker", "bytetrack.yaml"),
            person_class=int(det_cfg.get("person_class", 0)),
            imgsz=int(det_cfg.get("imgsz") or cfg.get("input", {}).get("process_width", 1280)),
            device=det_cfg.get("device"),
            min_person_height_px=int(det_cfg.get("min_person_height_px", 100)),
        )
        # Identity is optional: without insightface/roster templates the pipeline
        # still measures bodies, it just keeps everyone as a guest.
        embedder = None
        try:
            embedder = FaceEmbedder(pack=ident_cfg.get("recognition_pack", "buffalo_l"))
        except Exception as e:  # boundary with an external SDK
            # Printed rather than swallowed: with no embedder every track stays a guest, which
            # means a run that looks fine on screen writes no session rows at all. The
            # reason has to appear on the same screen the operator is watching.
            print("identity off (%s: %s) - every track stays a guest" % (type(e).__name__, e))

        roster_path = resolve_asset(cfg.get("roster", "roster.json"))
        roster = Roster.load(roster_path) if isinstance(roster_path, str) else roster_path
        if embedder is not None and not roster.entries():
            # The other half of the same silence: insightface loaded, but there is nobody to
            # match against. Enroll with tools/enroll_roster.py before expecting student rows.
            print("roster %r has no enrolled students - nobody can be bound" % (roster_path,))

        eye_model = resolve_asset(cfg.get("face_landmarker_task", "face_landmarker.task"))
        if not os.path.exists(eye_model):
            # Printed, not raised past: without the model no face is ever measured, and an operator
            # watching boxes appear would blame the camera. Naming the path tried is the whole fix.
            print("eye model NOT FOUND at %r - config.yaml face_landmarker_task is relative to "
                  "edge-server/; copy face_landmarker.task there or fix the path" % (eye_model,))
        analyzer = FaceAnalyzer(
            model_asset_path=eye_model,
            num_faces=1,
        )
        scheduler = Scheduler(
            max_active_faces=int(sched_cfg.get("max_active_faces", 10)),
            min_eye_span_px=float(sched_cfg.get("min_eye_span_px", 12)),
            starve_ms=int(sched_cfg.get("starve_ms", 1500)),
            hot_multiplier=float(sched_cfg.get("hot_multiplier", 3)),
        )
        writer = LocalStore(
            db_path=storage_cfg.get("db", ""),
            local_dir=io_cfg.get("dir", "out"),
            class_id=cfg.get("class_id", "class"),
            class_label=cfg.get("class_label") or cfg.get("class_id", "class"),
            school_year=storage_cfg.get("school_year", ""),
        )
        # stash the resolver so step() can reach it without another import
        obj = cls(cfg, detector, embedder, roster, analyzer, scheduler, writer,
                  roster_path=roster_path if isinstance(roster_path, str) else None)
        if embedder is not None:
            obj._resolver = IdentityResolver(
                embedder, roster,
                bind_hi=float(ident_cfg.get("bind_hi", 0.52)),
                bind_lo=float(ident_cfg.get("bind_lo", 0.38)),
                min_margin=float(ident_cfg.get("min_margin", 0.05)),
                min_samples=int(ident_cfg.get("min_samples", 10)),
                embed_window=int(ident_cfg.get("embed_window", 40)),
                unbind_hold_ms=int(ident_cfg.get("unbind_hold_ms", 10000)),
            )
        else:
            obj._resolver = None
        return obj

    # ------------------------------------------------------------------
    def new_identity_state(self):
        if self._resolver is not None:
            return self._resolver.new_state()
        # A stand-in when identity is off: no binding ever happens.
        from .identity import IdentityState
        return IdentityState()

    def _profile_for(self, student_id: str) -> HabitProfile:
        p = self._profiles.get(student_id)
        if p is None:
            p = HabitProfile(student_id, self._roster.name_of(student_id))
            self._profiles[student_id] = p
        return p

    # ------------------------------------------------------------------
    def step(self, frame_bgr, now_ms: Optional[int] = None,
             wall_ms: Optional[int] = None) -> List[PersonResult]:
        """Process one BGR frame through all five layers. Returns per-person results."""
        now_ms = _now_ms() if now_ms is None else now_ms
        wall_ms = _wall_ms() if wall_ms is None else wall_ms
        self._frame_dt_ms = max(1.0, min(500.0, now_ms - self._last_step_ms)) if self._last_step_ms else 33.0
        self._last_step_ms = now_ms

        h, w = frame_bgr.shape[:2]
        self._last_wh = (w, h)
        self._step_count += 1
        # An empty cache means an empty room: keep detecting every frame so a child who
        # walks in is found at once, and a resolution change invalidates pixel boxes.
        run_yolo = (self._yolo_every <= 1
                    or self._step_count % self._yolo_every == 1
                    or not self._cached_persons
                    or self._cached_wh != (w, h))
        if run_yolo:
            persons, scene_objects = self._detector.run(frame_bgr)
            self._cached_persons, self._cached_objects = persons, scene_objects
            self._cached_wh = (w, h)
        else:
            persons, scene_objects = self._cached_persons, self._cached_objects
        self.last_yolo_ran = run_yolo
        self.last_boxes = DetectionView(persons=tuple(persons), objects=tuple(scene_objects),
                                        frame_size=(w, h))

        seen: Dict[int, bool] = {p.track_id: True for p in persons}
        results: List[PersonResult] = []

        # -- update tracks: identity + face candidates --------------------
        candidates = []
        regions: Dict[int, tuple] = {}
        for p in persons:
            rt = self._tracks.get(p.track_id)
            if rt is None:
                rt = TrackRuntime(
                    track_id=p.track_id,
                    identity=self.new_identity_state(),
                    pipeline=PersonPipeline(p.track_id, None, self._sensitivity,
                                            self._min_span),
                    last_seen_ms=now_ms,
                    # bootstrap so a brand-new face is selected once to get a span
                    eye_span_px=self._min_span,
                )
                self._tracks[p.track_id] = rt
            rt.last_seen_ms = now_ms
            rt.nearby = p.nearby_objects

            face_box = self._face_region(p.bbox, w, h)
            regions[p.track_id] = face_box
            crop = self._crop(frame_bgr, face_box)

            # identity (throttled internally by the resolver window)
            if self._resolver is not None and crop is not None:
                self._resolver.observe(rt.identity, crop, face_box, now_ms)
            self._sync_binding(rt)

            from .scheduler import FaceCandidate
            candidates.append(FaceCandidate(
                track_id=p.track_id, face_bbox=face_box,
                eye_span_px=rt.eye_span_px, last_measured_ms=rt.last_measured_ms,
                is_hot=self._is_hot(rt),
            ))

        # -- schedule + measure the chosen faces --------------------------
        self.last_boxes = self.last_boxes._replace(face_regions=tuple(regions.items()))
        chosen = self._scheduler.select(candidates, now_ms)
        by_track = {rt.track_id: rt for rt in self._tracks.values()}
        gray = None  # converted at most once per frame, and only if a face actually got measured
        # One focal length for the whole frame: face crops are pixel windows of this same image, so
        # they share its scale. Nominal 60 deg horizontal field of view, like the phone.
        frame_focal = focal_px(w)
        measured = []
        for cand in chosen:
            rt = by_track.get(cand.track_id)
            if rt is None:
                continue
            crop = self._crop(frame_bgr, cand.face_bbox)
            light = None
            if crop is not None:
                if gray is None:
                    gray = to_gray(frame_bgr)
                light = face_light(gray, cand.face_bbox, w, h)
            rgb = crop[:, :, ::-1] if crop is not None else None
            lm, bs = (None, None)
            if rgb is not None:
                lm, bs = self._analyzer.analyze(rgb)
                if lm is not None and lm.count:
                    from .landmarks import eye_corner_span_px
                    cw, ch = self._box_size(cand.face_bbox)
                    rt.eye_span_px = eye_corner_span_px(lm, cw, ch)
            res = rt.pipeline.process(
                lm, bs, self._box_size(cand.face_bbox)[0], self._box_size(cand.face_bbox)[1],
                now_ms, self._frame_dt_ms, rt.student_id, rt.student_name,
                rt.identity_conf, rt.nearby, light=light, focal=frame_focal,
            )
            rt.last_measured_ms = now_ms
            rt.measured_frames += 1
            # The preview draws this frame, so the pixels the numbers came from ride along with
            # them: box + landmarks are in this frame's coordinates and stay valid only here.
            res.face_bbox = cand.face_bbox
            res.landmarks = lm
            res.calibration_progress = rt.pipeline.calibration_progress
            rt.last_result = res
            self._evaluate_attention(rt, res, wall_ms)
            results.append(res)
            measured.append((res.landmarks, res.face_bbox, rt.nearby))
            if res.alert:
                self._register_alert(rt, res.alert, wall_ms)
            elif rt.attention_label and rt.attention_label.startswith("MAT TAP TRUNG"):
                self._register_alert(rt, "LOOK_AWAY", wall_ms)

        # -- Lớp khuyến nghị sư phạm: đánh giá từng em THEO NGỮ CẢNH khẩu lệnh GV -----
        # (context_eval.py - tài liệu 2+3) và theo dõi dấu hiệu giật lặp lại kiểu Tic
        # (tic_watch.py - tài liệu 1). Chạy sau khi đo, đọc lại chính số liệu khung này;
        # không sửa SEI, không đụng chuỗi cảnh báo parity. Không có khẩu lệnh hoặc mặt
        # không đo được -> NEUTRAL, đồng hồ dừng (hai nguyên tắc của teacher_voice).
        tv_ctx = get_teacher_voice().get_status().get("context", "UNKNOWN")
        centers = {}
        for res in results:
            if res.measurable and res.face_bbox:
                x0, y0, x1, y1 = res.face_bbox
                centers[res.track_id] = ((x0 + x1) / 2.0, (y0 + y1) / 2.0)
        measured_ids = set()
        for res in results:
            rt = self._tracks.get(res.track_id)
            if rt is None:
                continue
            measured_ids.add(res.track_id)
            meas = res.measurable and res.yaw is not None and res.pitch is not None
            nearest = None
            c = centers.get(res.track_id)
            if c is not None and len(centers) > 1:
                # Khoảng cách PIXEL tới mặt gần nhất: tiêu chí 3 chỉ so TỈ LỆ xích lại
                # gần baseline nên px hay cm không đổi kết luận (cùng một camera, cùng
                # tiêu cự khung hình). Tín hiệu MỀM, không phải cổng cứng.
                nearest = min(math.hypot(c[0] - o[0], c[1] - o[1])
                              for tid, o in centers.items() if tid != res.track_id)
            # Mắt chỉ kết luận mở/nhắm khi EyeEngine đọc được; góc đầu quá nghiêng cho
            # eye gate thì None = không biết, KHÔNG được coi là nhắm mắt (context_eval).
            eyes_open = (res.openness >= 0.5) if res.eye_valid else None
            mar = res.hud.mar if res.hud is not None else None
            yawn = bool(res.hud.yawn_confirmed) if res.hud is not None else False
            eps_before = len(rt.context_eval.episodes)
            rt.context_eval.update(now_ms, tv_ctx, meas, res.yaw, res.pitch, mar,
                                   eyes_open=eyes_open, nearest_cm=nearest)
            if len(rt.context_eval.episodes) > eps_before:
                ep = rt.context_eval.episodes[-1]
                self._register_advisory(rt, "CONTEXT_OFF_TASK", wall_ms,
                                        context=ep["context"], reason=ep["reason"])
            rt.tic.update(now_ms, rt.pipeline.blinks_since(rt.tic.last_blink_seen_ms),
                          mar, yawn, tv_ctx, res.measurable)
            if rt.tic.level_rose:
                self._register_advisory(rt, "TIC_WATCH", wall_ms, level=rt.tic.level,
                                        reason=rt.tic.view()["label"])
        # Track không được đo nhịp này: chỉ dừng đồng hồ khi mặt THỰC SỰ không đo được
        # hoặc đã lâu không đo. Scheduler bỏ qua một nhịp (đông mặt) không phải là mất
        # mặt - reset nhầm sẽ khiến đồng hồ 7 giây không bao giờ đầy trong lớp đông.
        for rt in self._tracks.values():
            if rt.track_id in measured_ids:
                continue
            r = rt.last_result
            absent = (r is None or not r.measurable
                      or now_ms - rt.last_measured_ms > 2000)
            if absent:
                rt.context_eval.update(now_ms, tv_ctx, False, None, None, None)
                rt.tic.update(now_ms, [], None, False, tv_ctx, False)

        # -- what the room as a whole is doing -----------------------------
        self._update_activity(measured, persons, scene_objects, w, h, now_ms)

        # -- start sessions for freshly bound, measurable people ----------
        for rt in self._tracks.values():
            if rt.student_id and rt.pipeline.session is None and rt.last_result and rt.last_result.measurable:
                # The id IS the wall clock of the start, the phone's rule (HabitSession is keyed
                # the same way). A per-process counter restarted at 1 on every run, so a second
                # run of the same child wrote sessions/<id>-cam-1 again and set() replaced the
                # first run's record - and the saved habit profile replaced its stored session too.
                rt.pipeline.start_session(wall_ms)
                rt.session_started_wall_ms = wall_ms

        # -- retire dead tracks -------------------------------------------
        for tid in list(self._tracks.keys()):
            rt = self._tracks[tid]
            if not seen.get(tid) and now_ms - rt.last_seen_ms >= self._track_ttl_ms:
                self._retire(rt, wall_ms)
                del self._tracks[tid]

        # -- periodic aggregate writes ------------------------------------
        if now_ms - self._last_class_live_ms >= self._class_live_ms:
            self._write_class_live(wall_ms)
            self._last_class_live_ms = now_ms
        if now_ms - self._last_heartbeat_ms >= self._heartbeat_ms:
            self._write_heartbeats(wall_ms)
            self._last_heartbeat_ms = now_ms

        return results

    # ------------------------------------------------------------------
    def _update_activity(self, measured, persons, scene_objects, w: int, h: int,
                         now_ms: int) -> None:
        """One Classroom Context Engine sample, paced by ``CONTEXT_T["SAMPLE_MS"]``.

        Purely a reading of numbers this frame already produced: no model, no extra pass. It is paced
        anyway because the geometry is per head and the pair/group rules compare every head with every
        other, which on a 2-core box is arithmetic that does not have to run at frame rate. The web leg
        paces the same way, so both screens update on the same rhythm.

        Only heads with a mesh FROM THIS FRAME are fed. A track the scheduler did not pick this tick is
        left out rather than represented by its previous tick's landmarks: see the note in activity.py.
        """
        if self._activity_at_ms and now_ms - self._activity_at_ms < self._activity_sample_ms:
            return
        self._activity_at_ms = now_ms
        snap = self.activity.update(Signals(
            heads=head_signals(measured, w, h),
            persons=len(persons),
            room=room_objects(persons, scene_objects),
        ), now_ms)

        # Teacher voice commands act as a context switch, but the label may only claim speech
        # when a real utterance (or an explicit manual pick) produced the switch. At boot and
        # after the silence timeout the vision-based label stays in charge.
        try:
            from .teacher_voice import get_teacher_voice, TeacherContextAI
            tv = get_teacher_voice(self._cfg.get("teacher_voice"))
            tv_status = tv.get_status()
            tv_ctx = tv_status["context"]
            src = tv_status.get("context_source", "init")
            if src in ("voice", "manual"):
                tag = "giọng nói GV" if src == "voice" else "GV chọn tay"
                evidence = ("GV khẩu lệnh: %s" % tv_status["last_utterance"]) if src == "voice" \
                    else ("GV chọn: %s" % tv_status["last_speech"])
                if tv_ctx == TeacherContextAI.CONTEXT_GROUP_WORK:
                    snap = snap._replace(
                        context=int(ClassContext.GROUP_DISCUSSION),
                        label="thảo luận nhóm (%s)" % tag,
                        evidence=evidence
                    )
                elif tv_ctx == TeacherContextAI.CONTEXT_STUDY_NOTE:
                    snap = snap._replace(
                        context=int(ClassContext.READING),
                        label="đọc hoặc viết / tự học (%s)" % tag,
                        evidence=evidence
                    )
                elif tv_ctx == TeacherContextAI.CONTEXT_LECTURE:
                    snap = snap._replace(
                        context=int(ClassContext.PRESENTATION),
                        label="nghe giảng (%s)" % tag,
                        evidence=evidence
                    )
        except Exception:
            pass

        if self.last_activity is None or snap.context != self.last_activity.context:
            # One line per switch, never per frame: the label is the slow quantity here, and a stdout
            # that prints it 8 times a second would bury the alerts the operator is watching for.
            # ASCII only, on purpose - this console's code page cannot encode the Vietnamese label and
            # raising inside step() would stop a measuring run over a log line. The screen owns the
            # readable sentence; this line is for a run started without --show.
            prev = self.last_activity.context if self.last_activity is not None else None
            print("class-context: %s -> %s" % (
                ClassContext(prev).name if prev is not None else "-",
                ClassContext(snap.context).name))
        self.last_activity = snap

    # ------------------------------------------------------------------
    def _sync_binding(self, rt: TrackRuntime) -> None:
        if rt.manual_student_id is not None:
            # The operator said who this is, and that outranks a machine which cannot say. Stored as
            # identity_conf "manual" (see _retire, which carries it into the row's notes) so a table
            # of measurements can always tell a declared name from a recognised one.
            sid = rt.manual_student_id
            rt.student_id = sid
            rt.student_name = self._roster.name_of(sid)
            rt.identity_conf = "manual"
            rt.pipeline.profile = self._profile_for(sid)
            if rt.pipeline._baseline is None:
                rt.pipeline._baseline = rt.pipeline.profile.baseline()
            return
        st = rt.identity
        sid = getattr(st, "bound_student_id", None)
        conf = getattr(st, "confidence", "unbound")
        if sid is not None and conf in ("high", "medium"):
            if rt.student_id != sid:
                rt.student_id = sid
                rt.pipeline.profile = self._profile_for(sid)
                if rt.pipeline._baseline is None:
                    rt.pipeline._baseline = rt.pipeline.profile.baseline()
            rt.student_name = self._roster.name_of(sid)
            rt.identity_conf = conf
        else:
            # unbound / guest: keep the track measurable but never keyed to a student
            rt.student_id = None
            rt.student_name = "guest-%d" % rt.track_id
            rt.identity_conf = conf or "guest"

    # ------------------------------------------------------------------
    # naming a head by hand, for the runs where recognition cannot answer at all
    # ------------------------------------------------------------------
    def seat_suggestion(self, track_id: int, now_ms: Optional[int] = None) -> Optional[str]:
        """Whose remembered seat this guest head is sitting in, or None.

        A suggestion for the operator, never a binding: the m key (or the dashboard button)
        still has to be pressed. Seats are normalized face centers, so the hint survives a
        resolution change; the radius is a tenth of the frame, tighter than one desk apart.
        """
        rt = self._tracks.get(track_id)
        if rt is None or rt.manual_student_id is not None:
            return None
        if rt.last_result is None or rt.last_result.face_bbox is None:
            return None
        w, h = self._last_wh
        if not w or not h:
            return None
        now_ms = _wall_ms() if now_ms is None else now_ms
        x0, y0, x1, y1 = rt.last_result.face_bbox
        cx, cy = (x0 + x1) / 2.0 / w, (y0 + y1) / 2.0 / h
        taken = set(t.manual_student_id for tid, t in self._tracks.items() if tid != track_id)
        best = None
        for sid, m in self._seat_memory.items():
            if sid in taken or now_ms - m["at_ms"] > self.SEAT_TTL_MS:
                continue
            d = math.hypot(cx - m["cx"], cy - m["cy"])
            if d <= self.SEAT_RADIUS and (best is None or d < best[0]):
                best = (d, sid)
        return best[1] if best else None

    def bind_manual_next(self, track_id: int) -> Optional[str]:
        """The m key: give the chosen head a name the operator is stating, not a match.

        Recognition is not always available. On the laptop used for this project the ArcFace backend
        crashes the process the moment it is imported, so nothing is ever bound, and a run of any
        length leaves the ten tables empty because a session row is keyed by student_id. The one
        thing somebody standing at the camera does know is who is sitting in front of it.

        The first press takes the first roster slot no other head has claimed; later presses walk
        the rest, and the step past the last one releases the track back to the machine's verdict.
        """
        rt = self._tracks.get(track_id)
        if rt is None:
            return None
        ids = [e.student_id for e in self._roster.entries()]
        if not ids:
            ids = [self._add_manual_slot(1)]
        current = rt.manual_student_id
        if current is None:
            taken = set(t.manual_student_id for tid, t in self._tracks.items() if tid != track_id)
            free = [s for s in ids if s not in taken]
            # A head in a remembered seat takes that student first: one press re-binds the
            # child who left and came back, instead of walking the roster from slot one.
            sug = self.seat_suggestion(track_id)
            if sug is not None and sug in free:
                nxt = sug
            else:
                nxt = free[0] if free else self._add_manual_slot(len(ids) + 1)
        elif current in ids:
            i = ids.index(current)
            nxt = ids[i + 1] if i + 1 < len(ids) else None
        else:
            nxt = None
        rt.manual_student_id = nxt
        self._sync_binding(rt)
        return rt.student_id

    def bind_manual_name(self, track_id: int, name: str) -> Optional[str]:
        """The dashboard's "Gắn tên" button: the m key's authority, with a readable name.

        The cv2 window can only walk hs1/hs2 slots because it has no text field; the web
        person card does, so the operator types who this is in one step. Same rule as the
        first m press: take the first roster slot no other head has claimed, create hsN
        when the roster is empty. An already-bound track keeps its slot - sessions stored
        under that id stay that child's sessions - and only takes the new name.
        identity_conf stays "manual": a stated name, never a machine match.
        """
        rt = self._tracks.get(track_id)
        if rt is None:
            return None
        sid = rt.manual_student_id
        if sid is None:
            ids = [e.student_id for e in self._roster.entries()]
            taken = set(t.manual_student_id for tid, t in self._tracks.items() if tid != track_id)
            free = [s for s in ids if s not in taken]
            sid = free[0] if free else self._add_manual_slot(len(ids) + 1)
            rt.manual_student_id = sid
        name = (name or "").strip()
        if name:
            entry = self._roster.get(sid)
            if entry is None:
                self._roster.add(sid, name)
            else:
                entry.name = name
            self._save_roster()
        self._sync_binding(rt)
        return rt.student_id

    def unbind_manual(self, track_id: int) -> bool:
        """Release a stated name back to the machine's verdict (the m key's step past the last).

        The track becomes a guest again: still measurable on screen, but no new session is
        keyed to it, exactly like a head the machine never recognised.
        """
        rt = self._tracks.get(track_id)
        if rt is None:
            return False
        rt.manual_student_id = None
        self._sync_binding(rt)
        return True

    def name_of(self, student_id: str) -> str:
        return self._roster.name_of(student_id)

    def _add_manual_slot(self, index: int) -> str:
        """hs1, hs2 ...: the numbering the phone and the web leg both start their roster with.

        A cv2 window has no text field, so a name has to be derivable from the slot alone. Real names
        go into roster.json by hand afterwards - the id never changes, so the sessions already stored
        under hs1 stay that child's sessions when the readable name appears.
        """
        sid = "hs%d" % index
        while self._roster.get(sid) is not None:
            index += 1
            sid = "hs%d" % index
        self._roster.add(sid, "Học sinh %d" % index)
        self._save_roster()
        return sid

    def _save_roster(self) -> None:
        if not self._roster_path:
            return
        try:
            self._roster.save(self._roster_path)
        except Exception as e:  # the roster is a convenience; a measurement never is
            print("roster NOT saved (%s: %s) - this run keeps the name, a restart loses it"
                  % (type(e).__name__, e))

    def _is_hot(self, rt: TrackRuntime) -> bool:
        r = rt.last_result
        if r is None:
            return False
        if r.situation == Situation.DOZING:
            return True
        if r.sei is not None and r.sei < 50:
            return True
        if r.alert:
            return True
        return False

    def _evaluate_attention(self, rt: TrackRuntime, res, wall_ms: int) -> None:
        """Judge this head against the teacher's spoken context, for display only.

        The context counts only when a real utterance or a manual pick produced it - the same
        honesty rule as the room label. Before anyone speaks, a child reading a book must not
        be told they are "not looking at the board": with no command there is no expectation,
        so the evaluator's neutral branch runs instead.
        """
        if not res.measurable or res.yaw is None or res.pitch is None:
            return
        from .teacher_voice import StudentAttentionEvaluator, get_teacher_voice
        if rt.attention_eval is None:
            rt.attention_eval = StudentAttentionEvaluator(violation_threshold_s=self._off_task_s)
        st = get_teacher_voice(self._cfg.get("teacher_voice")).get_status()
        ctx = st["context"] if st.get("context_source") in ("voice", "manual") else ""
        mar = float(res.features.get(HabitFeature.MAR, 0.0) or 0.0)
        label, reason, _ = rt.attention_eval.evaluate(
            float(res.yaw), float(res.pitch), mar, False, ctx, now=wall_ms / 1000.0)
        rt.attention_label, rt.attention_reason = label, reason

    def _register_alert(self, rt: TrackRuntime, code: str, wall_ms: int) -> None:
        # Cảnh báo tối đa 5 phút 1 lần cho 1 học sinh để chống spam chuông/thông báo
        last_alert_time = getattr(rt, "_last_alert_wall_ms", 0)
        cooldown_ms = int((self._cfg.get("alerts") or {}).get("cooldown_seconds", 300)) * 1000
        if wall_ms - last_alert_time < cooldown_ms:
            return
        rt._last_alert_wall_ms = wall_ms

        rt.alert_count += 1
        rt.alerts.append({"code": code, "wall_ms": wall_ms})
        alert_item = {
            "student_id": rt.student_id or ("guest-%d" % rt.track_id),
            "student_name": rt.student_name,
            "track_id": rt.track_id,
            "code": code,
            "wall_ms": wall_ms,
        }
        self._alerts.append(alert_item)
        # Ghi ngay tức thì vào SQLite database bảng alerts để giao diện nhận được cảnh báo ngay trong lúc quay
        if hasattr(self._writer, "write_instant_alert"):
            try:
                self._writer.write_instant_alert(
                    student_id=alert_item["student_id"],
                    student_name=rt.student_name or alert_item["student_id"],
                    code=code,
                    wall_ms=wall_ms,
                )
            except Exception:
                pass

    def _register_advisory(self, rt: TrackRuntime, kind: str, wall_ms: int, **fields) -> None:
        """Một sự kiện KHUYẾN NGHỊ (off-task theo ngữ cảnh, mức dấu hiệu Tic tăng).

        Không phải cảnh báo parity: không đếm vào alert_count, không ghi bảng alerts.
        Đi vào luồng advisories của class_live JSON và (qua session_summary) notes JSON.
        """
        self._advisories.append({
            "kind": kind,
            "student_id": rt.student_id or ("guest-%d" % rt.track_id),
            "student_name": rt.student_name,
            "track_id": rt.track_id,
            "wall_ms": wall_ms,
            **fields,
        })

    # ------------------------------------------------------------------
    def _retire(self, rt: TrackRuntime, wall_ms: int) -> None:
        """Track died: close its session and (for bound students) commit + upload."""
        if (rt.student_id and rt.identity_conf == "manual"
                and rt.last_result is not None and rt.last_result.face_bbox is not None
                and self._last_wh != (0, 0)):
            w, h = self._last_wh
            x0, y0, x1, y1 = rt.last_result.face_bbox
            self._seat_memory[rt.student_id] = {
                "cx": (x0 + x1) / 2.0 / w, "cy": (y0 + y1) / 2.0 / h,
                "name": rt.student_name, "at_ms": wall_ms,
            }
        s = rt.pipeline.commit_session()
        if s is None or rt.student_id is None:
            return
        summary = s.to_summary()
        profile = rt.pipeline.profile or self._profile_for(rt.student_id)
        profile.commit(summary)
        # Measured frames divided by the session's own active time: the previous number was the
        # gap of whichever frame happened to come last, so one stutter at the end of a 40 minute
        # session reported the whole session as running at 3 fps.
        avg_fps = 1000.0 * rt.measured_frames / float(max(1, s.active_ms))
        # Computed once so the session row and the learned band row carry the same verdict.
        ineligible = profile.ineligible_reason(summary)
        self._writer.write_session(
            student_id=rt.student_id, student_name=rt.student_name, s=summary,
            band_outcomes=s.band_outcomes(), ended_at_wall_ms=wall_ms, device=str(self._device),
            ineligible_reason=ineligible,
            alerts_total=rt.alert_count,
            # 0, the same number the phone writes for a session in which nobody pressed
            # "Canh bao sai". The camera has no such button, so that count really is zero here;
            # what tells the two instruments apart is the "source" column, nothing else.
            false_alerts=0,
            csv_rows=summary.count[HabitFeature.SEI],
            avg_fps=avg_fps,
            alerts=list(rt.alerts),
            # high/medium (earned from a face) or manual (declared on the keyboard) or unbound. In
            # the row's notes because the two are not the same kind of measurement and a table has to
            # be able to say which names were recognised and which somebody stated.
            identity=rt.identity_conf,
            # Tổng kết lớp khuyến nghị (không phải parity): off-task theo ngữ cảnh của phiên
            # và mẫu hình dấu hiệu Tic. tic_summary luôn có khi đã quan sát được mặt - ngày
            # level 0 là mẫu số của tỉ lệ "gần như mỗi ngày" khi đọc dọc 4 tuần (tic_history).
            context_summary=rt.context_eval.session_summary(),
            tic_summary=rt.tic.session_summary(),
        )
        if self._habit_learn:
            self._writer.write_band(rt.student_id, rt.student_name, profile, wall_ms, str(self._device))

    def _write_class_live(self, wall_ms: int) -> None:
        students: Dict[str, Dict] = {}
        for rt in self._tracks.values():
            r = rt.last_result
            key = rt.student_id or ("guest-%d" % rt.track_id)
            entry: Dict = {
                "name": rt.student_name or key,
                "identity_conf": rt.identity_conf,
                "track_id": rt.track_id,
                "measurable": bool(r and r.measurable),
            }
            if r is not None:
                if r.sei is not None:
                    entry["sei"] = r.sei
                entry["situation"] = int(r.situation)
                if r.perclos is not None:
                    entry["perclos"] = round(r.perclos, 1)
                if r.distance_cm is not None:
                    entry["distance_cm"] = round(r.distance_cm, 1)
                if r.alert:
                    entry["alert"] = r.alert
                if r.quality is not None:
                    entry["quality"] = r.quality
            # Lớp khuyến nghị sư phạm (tài liệu 1+2+3) đi cùng thẻ người trong class_live.
            entry["context"] = rt.context_eval.view()
            entry["tic"] = rt.tic.view()
            students[key] = entry
        self._writer.write_class_live(self.class_id, students, list(self._alerts), wall_ms,
                                      advisories=list(self._advisories))

    def _write_heartbeats(self, wall_ms: int) -> None:
        for rt in self._tracks.values():
            if rt.student_id is None or rt.pipeline.session is None:
                continue
            r = rt.pipeline.last_result
            state = r.situation.name.lower() if r else "unknown"
            self._writer.write_presence(
                student_id=rt.student_id, student_name=rt.student_name,
                s=rt.pipeline.session.to_summary(),
                started_at_wall_ms=rt.session_started_wall_ms, beat_at_wall_ms=wall_ms,
                active_ms=rt.pipeline.session.active_ms,
                alerts_total=rt.alert_count, device=str(self._device), state=state,
            )

    # ------------------------------------------------------------------
    def shutdown(self, wall_ms: Optional[int] = None) -> None:
        """Commit every still-open session (called on Ctrl-C / stream end)."""
        wall_ms = _wall_ms() if wall_ms is None else wall_ms
        for rt in list(self._tracks.values()):
            self._retire(rt, wall_ms)
        self._tracks.clear()
        # one last live snapshot so the teacher screen reflects the stop
        self._write_class_live(wall_ms)
        self._writer.close()

    # --- geometry helpers ----------------------------------------------
    def _face_region(self, bbox, w: int, h: int):
        x0, y0, x1, y1 = bbox
        ph = y1 - y0
        pw = x1 - x0
        # heads live near the top; bias the crop up and square-ish
        face_h = ph * self.FACE_TOP_FRACTION
        cy = y0 + face_h * 0.5
        cx = (x0 + x1) * 0.5
        size = max(pw * 0.7, face_h)
        fx0 = max(0, int(cx - size / 2))
        fy0 = max(0, int(cy - size / 2))
        fx1 = min(w, int(cx + size / 2))
        fy1 = min(h, int(cy + size / 2))
        if fx1 - fx0 < 8 or fy1 - fy0 < 8:
            return None
        # How much of the wanted square the frame edge ate. A student half out of frame still
        # produces a box that looks normal here, and both the eye ratio and the distance come out
        # of that box's width - so a clipped face is reported as a closer face with sharper eyes
        # than were ever captured. Refusing the box is the honest answer: the next frame where
        # this person is fully in view is measured normally.
        kept = float(min(fx1 - fx0, fy1 - fy0))
        if size > 0.0 and kept < size * (1.0 - self.CLIP_MAX_FRACTION):
            return None
        return (fx0, fy0, fx1, fy1)

    @staticmethod
    def _box_size(box):
        if box is None:
            return (0, 0)
        return (int(box[2] - box[0]), int(box[3] - box[1]))

    @staticmethod
    def _crop(frame, box):
        if box is None:
            return None
        x0, y0, x1, y1 = box
        crop = frame[y0:y1, x0:x1]
        if crop.size == 0:
            return None
        return crop

    # ------------------------------------------------------------------
    @property
    def active_tracks(self) -> int:
        return len(self._tracks)

    def people(self) -> List[PersonView]:
        """Who is in frame and how sure the machine is of each name, lowest track id first.

        Ordered by id and not by quality because the preview keys a row of this list: a menu that
        reshuffles itself every frame would make "press 2" mean a different child each time.
        """
        rows: List[PersonView] = []
        gate = float(getattr(self._resolver, "bind_hi", 0.0) or 0.0)
        margin_gate = float(getattr(self._resolver, "min_margin", 0.0) or 0.0)
        for rt in sorted(self._tracks.values(), key=lambda r: r.track_id):
            st = rt.identity
            hint_id = self.seat_suggestion(rt.track_id) if rt.student_id is None else None
            hint = self._seat_memory[hint_id]["name"] if hint_id else ""
            rows.append(PersonView(
                track_id=rt.track_id, student_id=rt.student_id, student_name=rt.student_name,
                identity_conf=rt.identity_conf,
                score=float(getattr(st, "score", 0.0) or 0.0),
                margin=float(getattr(st, "margin", 0.0) or 0.0),
                samples=int(getattr(st, "samples", 0) or 0),
                runner_up=str(getattr(st, "runner_up", "") or ""),
                gate=gate, margin_gate=margin_gate,
                measurable=bool(rt.last_result is not None and rt.last_result.measurable),
                seat_hint=hint,
                attention_label=rt.attention_label,
                attention_reason=rt.attention_reason))
        return rows

    def person_result(self, track_id: Optional[int]):
        """The newest PersonResult for one track, even if the scheduler skipped that face this tick.

        Returning nothing here is what makes the column fall back to whichever face WAS measured,
        and that silent swap is the behaviour the operator cannot tell apart from mixed-up numbers.
        A stale row of the same child is honest; a fresh row of the other child is not.
        """
        if track_id is None:
            return None
        rt = self._tracks.get(track_id)
        return rt.last_result if rt is not None else None

    @property
    def measurable_tracks(self) -> int:
        """Head count the machine can actually defend: a track that produced numbers recently.

        YOLO boxes anything shaped like a person, and in this project's own room that includes the
        photographs taped to the wall. Counting those as students would put a number on screen that
        no measurement supports, so the preview prints this next to the raw box count.
        """
        now_ms = _now_ms()
        return sum(1 for rt in self._tracks.values()
                   if rt.last_result is not None and rt.last_result.measurable
                   and now_ms - rt.last_measured_ms <= self._track_ttl_ms // 3)

    @property
    def profiles(self) -> Dict[str, HabitProfile]:
        return self._profiles

    @property
    def recent_alerts(self) -> List[Dict]:
        return list(self._alerts)

    # --- lớp khuyến nghị sư phạm cho dashboard (camera_runner._people đọc các view này) ---
    def context_view(self, track_id: int) -> Optional[Dict]:
        rt = self._tracks.get(track_id)
        return rt.context_eval.view() if rt is not None else None

    def tic_view(self, track_id: int) -> Optional[Dict]:
        rt = self._tracks.get(track_id)
        return rt.tic.view() if rt is not None else None

    @property
    def advisories(self) -> List[Dict]:
        return list(self._advisories)
