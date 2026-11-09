"""The orchestrator - wires all five layers into one classroom run.

This is the only module that knows about every other one. Its job per frame:

  detect (YOLO)  ->  bind identity (ArcFace+roster)  ->  schedule faces (ROI budget)
  ->  measure (MediaPipe + PersonPipeline)  ->  aggregate & sync (Firestore)

Third-party imports (cv2/numpy/ultralytics/insightface/mediapipe/firebase) are all
lazy: the module imports and its bookkeeping is testable without a GPU or models,
and the heavy objects are only built when ``ClassroomPipeline`` is constructed.
"""
from __future__ import annotations

import os
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Deque, Dict, List, NamedTuple, Optional, Tuple

from .constants import HabitConfig, HabitFeature, Situation
from .habit import HabitProfile
from .landmarks import focal_px
from .person_pipeline import PersonPipeline, PersonResult
from .quality import face_light, to_gray

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
    this struct can change a number that goes to Firestore.
    """

    persons: tuple = ()                    # PersonTrack: .bbox .track_id .conf .nearby_objects
    objects: tuple = ()                    # (name, bbox, conf) assigned to no person
    face_regions: Tuple[Tuple[int, tuple], ...] = ()   # (track_id, face box) fed to FaceLandmarker
    frame_size: Tuple[int, int] = (0, 0)   # (w, h) the coordinates belong to


@dataclass
class TrackRuntime:
    """Everything the orchestrator remembers about one ByteTrack id."""

    track_id: int
    identity: object                       # IdentityState (duck-typed to avoid import)
    pipeline: PersonPipeline
    student_id: Optional[str] = None
    student_name: str = ""
    identity_conf: str = "unbound"
    last_seen_ms: int = 0
    last_measured_ms: int = 0
    eye_span_px: float = 0.0               # 0 -> bootstrap candidate until first measured
    last_result: Optional[PersonResult] = None
    nearby: Dict[str, float] = field(default_factory=dict)
    # Counted here instead of read back out of the alert log: that log is a 200-item ring,
    # so a busy classroom would quietly lose the earliest alerts of a session and the number
    # written to Firestore would be short without anything on screen saying so.
    alert_count: int = 0
    measured_frames: int = 0
    # Wall clock of the moment this session started. The phone derives it from its session id,
    # which IS the start timestamp; here the id is a per-student counter, so the start time is
    # remembered separately rather than recomputed from the measured time.
    session_started_wall_ms: int = 0


class ClassroomPipeline:
    # head region of the person box fed to FaceLandmarker (faces live near the top)
    FACE_TOP_FRACTION = 0.55
    # Fraction of that region allowed to fall outside the frame before the face is skipped.
    # Tuned for a tripod camera at the front of a room, where a body touching the edge means the
    # head is cut too; a phone held by the student is always framed whole, so it has no equivalent.
    CLIP_MAX_FRACTION = 0.25

    def __init__(self, cfg: dict, detector, embedder, roster, analyzer, scheduler,
                 writer, profiles: Optional[Dict[str, HabitProfile]] = None) -> None:
        self._cfg = cfg
        self._detector = detector
        self._embedder = embedder          # may be None (identity disabled)
        self._roster = roster
        self._analyzer = analyzer
        self._scheduler = scheduler
        self._writer = writer
        self._profiles: Dict[str, HabitProfile] = profiles or {}

        det = cfg.get("detection", {})
        ident = cfg.get("identity", {})
        sched = cfg.get("scheduler", {})
        person = cfg.get("person", {})
        sync = cfg.get("sync", {})

        self.class_id = cfg.get("class_id", "class")
        # What the operator and the judges read on the preview. class_id stays the document key and
        # is an ASCII slug, so the two are deliberately different strings.
        self.class_label = cfg.get("class_label") or self.class_id
        self._track_ttl_ms = int(det.get("track_ttl_ms", 15000))
        self._sensitivity = int(person.get("sensitivity", 1))
        self._habit_learn = bool(person.get("habit_learn", True))
        self._min_span = float(sched.get("min_eye_span_px", 12))
        self._reverify_ms = int(ident.get("reverify_ms", 30000))
        self._identity_enabled = embedder is not None

        self._class_live_ms = int(sync.get("class_live_ms", 20000))
        self._heartbeat_ms = int(sync.get("heartbeat_ms", 300000))
        self._device = cfg.get("input", {}).get("source", "edge")
        # from_config() installs a real IdentityResolver; a directly built pipeline
        # (tests) keeps identity off.
        self._resolver = None

        self._tracks: Dict[int, TrackRuntime] = {}
        self._alerts: Deque[Dict] = deque(maxlen=200)
        # Preview-only: the last frame's raw layer-1 output. Overwritten every step().
        self.last_boxes = DetectionView()

        self._last_class_live_ms = 0
        self._last_heartbeat_ms = 0
        self._last_step_ms = 0
        self._frame_dt_ms = 33.0

        # SQLite mirror of everything the cloud writer receives, kept beside it rather than
        # inside it: a disk that refuses the file must cost the run its local copy, never its
        # measurement, so both the import and the connect live under this guard and the writer
        # path above cannot see them fail.
        self._sqlite = None
        self._store = None
        self._store_warned = False
        try:
            import sys as _sys
            _repo_root = os.path.dirname(_PACKAGE_PARENT)
            if _repo_root not in _sys.path:
                _sys.path.insert(0, _repo_root)
            from database import database as _sqlite
            self._sqlite = _sqlite
            self._store = _sqlite.connect()
            print("sqlite mirror: %s" % _sqlite.default_path())
        except Exception as e:  # boundary with the filesystem
            print("sqlite mirror disabled: %s: %s" % (type(e).__name__, e))

    # ------------------------------------------------------------------
    # construction helper kept here so main.py stays thin
    # ------------------------------------------------------------------
    @classmethod
    def from_config(cls, cfg: dict) -> "ClassroomPipeline":
        from .detection import Detector
        from .face_landmarker import FaceAnalyzer
        from .firestore_writer import FirestoreWriter
        from .identity import FaceEmbedder, IdentityResolver
        from .roster import Roster
        from .scheduler import Scheduler

        det_cfg = cfg.get("detection", {})
        ident_cfg = cfg.get("identity", {})
        sched_cfg = cfg.get("scheduler", {})
        sync_cfg = cfg.get("sync", {})
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
        )
        # Identity is optional: without insightface/roster templates the pipeline
        # still measures bodies, it just keeps everyone as a guest.
        embedder = None
        try:
            embedder = FaceEmbedder(pack=ident_cfg.get("recognition_pack", "buffalo_l"))
        except Exception as e:  # boundary with an external SDK
            # Printed rather than swallowed: with no embedder every track stays a guest, which
            # means a run that looks fine on screen writes no sessions document at all. The
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
        writer = FirestoreWriter(
            service_account=sync_cfg.get("service_account", ""),
            pending_file=sync_cfg.get("pending_file", "pending_upload.jsonl"),
            pending_max=int(sync_cfg.get("pending_max", 300)),
            local_dir=io_cfg.get("dir", "out"),
        )
        # stash the resolver so step() can reach it without another import
        obj = cls(cfg, detector, embedder, roster, analyzer, scheduler, writer)
        if embedder is not None:
            obj._resolver = IdentityResolver(
                embedder, roster,
                bind_hi=float(ident_cfg.get("bind_hi", 0.45)),
                bind_lo=float(ident_cfg.get("bind_lo", 0.30)),
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
        persons, scene_objects = self._detector.run(frame_bgr)
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
            results.append(res)
            if res.alert:
                self._register_alert(rt, res.alert, wall_ms)

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
    def _sync_binding(self, rt: TrackRuntime) -> None:
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

    def _register_alert(self, rt: TrackRuntime, code: str, wall_ms: int) -> None:
        rt.alert_count += 1
        self._alerts.append({
            "student_id": rt.student_id or ("guest-%d" % rt.track_id),
            "student_name": rt.student_name,
            "track_id": rt.track_id,
            "code": code,
            "wall_ms": wall_ms,
        })

    # ------------------------------------------------------------------
    def _retire(self, rt: TrackRuntime, wall_ms: int) -> None:
        """Track died: close its session and (for bound students) commit + upload."""
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
        # Computed once so the cloud row and the SQLite row carry the same verdict.
        ineligible = profile.ineligible_reason(summary)
        self._writer.write_session(
            student_id=rt.student_id, student_name=rt.student_name, s=summary,
            band_outcomes=s.band_outcomes(), ended_at_wall_ms=wall_ms, device=str(self._device),
            ineligible_reason=ineligible,
            alerts_total=rt.alert_count,
            # 0, the same number the phone writes for a session in which nobody pressed
            # "Canh bao sai". The camera has no such button, so that count really is zero here;
            # "source" and the "-cam-" in the key are what tell the two instruments apart.
            false_alerts=0,
            csv_rows=summary.count[HabitFeature.SEI],
            avg_fps=avg_fps,
        )
        self._mirror_session(rt.student_id, rt.student_name, summary, wall_ms, avg_fps,
                             ineligible, rt.alert_count)
        if self._habit_learn:
            self._writer.write_band(rt.student_id, rt.student_name, profile, wall_ms, str(self._device))
            self._mirror_bands(rt.student_id, rt.student_name, profile, wall_ms)

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
            students[key] = entry
        self._writer.write_class_live(self.class_id, students, list(self._alerts), wall_ms)

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
            self._mirror_presence(rt.student_id, rt.student_name,
                                  rt.pipeline.session.to_summary(),
                                  rt.session_started_wall_ms, wall_ms,
                                  rt.pipeline.session.active_ms, rt.alert_count, state)

    # ------------------------------------------------------------------
    # SQLite mirror - the same rows the cloud writer gets, written beside it
    # ------------------------------------------------------------------
    def _mirror(self, fn, **kwargs) -> None:
        if self._store is None:
            return
        try:
            fn(self._store, **kwargs)
        except Exception as e:  # boundary with the filesystem
            if not self._store_warned:
                self._store_warned = True
                print("sqlite mirror write failed, cloud copy unaffected: %s: %s"
                      % (type(e).__name__, e))

    def _mirror_session(self, student_id: str, student_name: str, s, wall_ms: int,
                        avg_fps: float, ineligible_reason, alerts_total: int) -> None:
        # Only features with at least one observation, the same rule the cloud payload uses:
        # a feature nobody measured stays an absent row instead of a zero.
        features = [(HabitFeature.KEYS[f], s.mean[f], s.std[f], s.count[f])
                    for f in range(HabitFeature.COUNT) if s.count[f] > 0]
        self._mirror(self._sqlite.save_session,
                     student_id=student_id, student_name=student_name, session_id=s.id,
                     ended_at_wall_ms=wall_ms, valid_ms=s.valid_ms,
                     face_ratio=s.face_ratio, valid_ratio=s.valid_ratio,
                     quality_mean=s.quality if s.quality_count > 0 else None,
                     sensitivity=s.sensitivity_level if s.sensitivity_level >= 0 else None,
                     alerts_total=alerts_total, false_alerts=0,
                     csv_rows=s.count[HabitFeature.SEI], avg_fps=avg_fps,
                     device=str(self._device), app_version=self._writer.app_version,
                     ineligible_reason=ineligible_reason, features=features)

    def _mirror_bands(self, student_id: str, student_name: str, profile, wall_ms: int) -> None:
        base = profile.baseline()
        bands = []
        for f in HabitFeature.BAND_FEATURES:
            b = base.band(f)
            if b is None:
                continue
            bands.append((HabitFeature.KEYS[f], b.mean, b.min, b.max, base.spread(f)))
        if not bands:
            return
        self._mirror(self._sqlite.save_bands, student_id=student_id,
                     student_name=student_name, bands=bands,
                     sessions_used=profile.session_count(),
                     band_k=float(HabitConfig.BAND_K), updated_at_wall_ms=wall_ms)

    def _mirror_presence(self, student_id: str, student_name: str, s, started_ms: int,
                         beat_ms: int, active_ms: int, alerts_total: int, state: str) -> None:
        sei_n = s.count[HabitFeature.SEI]
        self._mirror(self._sqlite.save_presence, student_id=student_id,
                     student_name=student_name, session_id=s.id,
                     started_at_wall_ms=started_ms, beat_at_wall_ms=beat_ms,
                     active_ms=active_ms, valid_ms=s.valid_ms,
                     face_ratio=s.face_ratio, valid_ratio=s.valid_ratio,
                     alerts_total=alerts_total, false_alerts=0,
                     sei_mean=s.mean[HabitFeature.SEI] if sei_n > 0 else None,
                     sei_n=sei_n if sei_n > 0 else None, state=state,
                     device=str(self._device), app_version=self._writer.app_version)

    # ------------------------------------------------------------------
    def shutdown(self, wall_ms: Optional[int] = None) -> None:
        """Commit every still-open session (called on Ctrl-C / stream end)."""
        wall_ms = _wall_ms() if wall_ms is None else wall_ms
        for rt in list(self._tracks.values()):
            self._retire(rt, wall_ms)
        self._tracks.clear()
        # one last live snapshot so the teacher screen reflects the stop
        self._write_class_live(wall_ms)
        if self._store is not None:
            self._store.close()
            self._store = None

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
