"""Luồng camera nền cho dashboard: chạy ĐÚNG lõi đo hiện có, phát khung hình chỉ trong bộ nhớ.

Vì sao không gọi main.py: main.py vẽ cửa sổ OpenCV riêng và giữ vòng lặp phím của nó; dashboard
cần cùng lõi đo nhưng không cần cửa sổ thứ hai. ClassroomPipeline.step() là ranh giới sạch nhất
(năm tầng, tự ghi SQLite, tự chốt phiên khi track chết) nên luồng này chỉ việc mở camera, gọi
step, rồi vẽ hộp lên khung hình để phát MJPEG cho trình duyệt.

Quyền riêng tư (đặc tả mục 2): khung hình KHÔNG BAO GIỜ chạm đĩa. JPEG nằm trong một ô nhớ,
ghi đè mỗi khung; mất kết nối là mất khung, đúng nghĩa "xử lý xong là bỏ". Nút "Chụp ảnh" của
giao diện là chụp MÀN HÌNH phía trình duyệt, không phải trích khung hình mặt từ luồng này.

Khi thiếu thư viện hoặc thiếu model, start() trả lỗi liệt kê đúng thứ thiếu thay vì ném
exception vào luồng nền: màn hình Camera phải nói được vì sao chưa chạy, giống cách main.py in
"eye model NOT FOUND" cho người vận hành cửa sổ.
"""
from __future__ import annotations

import importlib.util
import math
import threading
import time
from typing import Callable, Dict, List, Optional

# Bốn thư viện lõi đo cần có mặt. yaml nằm đây vì config.yaml là đầu vào của from_config.
REQUIRED_MODULES = ("cv2", "mediapipe", "ultralytics", "yaml")


def missing_deps() -> List[str]:
    return [m for m in REQUIRED_MODULES if importlib.util.find_spec(m) is None]


def _ema(old: float, new: float, alpha: float = 0.2) -> float:
    return new if old <= 0 else old * (1 - alpha) + new * alpha


class CameraRunner:
    """Một luồng camera duy nhất cho cả dashboard (bậc standard: 1 camera, đặc tả mục 4)."""

    def __init__(self, on_minute: Optional[Callable[[int, Dict], None]] = None):
        self._lock = threading.Lock()
        self._thread: Optional[threading.Thread] = None
        self._pipeline = None   # ClassroomPipeline đang chạy, để API gắn tên gọi bind_manual_name
        self._stop = threading.Event()
        self._jpeg: Optional[bytes] = None
        self._info: Dict = {"people": [], "fps": 0.0, "processing_ms": 0.0,
                            "activity": "", "started_ms": 0, "frames": 0}
        # Tuỳ chọn hiển thị áp dụng ngay không cần khởi động lại: Y hộp, O số liệu đè, G tăng sáng.
        self.options = {"boxes": True, "overlay": False, "brighten": False}
        self.error = ""
        self._on_minute = on_minute

    # ------------------------------------------------------------------ trạng thái
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def status(self) -> Dict:
        with self._lock:
            info = dict(self._info)
        return {"running": self.running(), "error": self.error,
                "people": len(info["people"]), "fps": round(info["fps"], 1),
                "processing_ms": round(info["processing_ms"], 1),
                "started_ms": info["started_ms"], "frames": info["frames"],
                "options": dict(self.options)}

    def snapshot(self):
        """(jpeg bytes, info) của khung gần nhất - handler MJPEG và /api/live cùng đọc ô này."""
        with self._lock:
            return self._jpeg, dict(self._info)

    # ------------------------------------------------------------------ điều khiển
    def start(self, cfg: Dict) -> Dict:
        if self.running():
            return {"ok": True, "already": True}
        miss = missing_deps()
        if miss:
            self.error = "thieu thu vien: %s" % ", ".join(miss)
            return {"ok": False, "error": self.error,
                    "hint": "cai dat: python -m pip install " + " ".join(miss)}
        from focusedge.classroom import resolve_asset
        model = resolve_asset(cfg.get("face_landmarker_task", "face_landmarker.task"))
        import os
        if not os.path.exists(model):
            self.error = "thieu model: %s" % model
            return {"ok": False, "error": self.error,
                    "hint": "copy face_landmarker.task vao thu muc du an"}
        self._stop.clear()
        self.error = ""
        self._thread = threading.Thread(target=self._run, args=(cfg,), daemon=True,
                                        name="dashboard-camera")
        self._thread.start()
        return {"ok": True}

    def stop(self) -> Dict:
        self._stop.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=10)
        self._thread = None
        with self._lock:
            self._jpeg = None
            self._info = {"people": [], "fps": 0.0, "processing_ms": 0.0,
                          "activity": "", "started_ms": 0, "frames": 0}
        return {"ok": True}

    # ------------------------------------------------------------------ gắn tên (nút "Gắn tên" trên thẻ người)
    def bind(self, track_id: int, name: str = "") -> Dict:
        """Khai báo tên cho một track đang trong khung hình - cùng thẩm quyền phím M của main.py.

        Gọi từ luồng HTTP trong khi luồng camera đang step(): các phép gán bên trong
        bind_manual_name là nguyên tử dưới GIL, tệ nhất một khung hình đọc nhầm trạng thái
        rồi khung sau tự sửa - giống cách main.py gọi bind giữa hai lần step.
        """
        pipeline = self._pipeline
        if pipeline is None or not self.running():
            return {"ok": False, "error": "camera chưa chạy - bật camera trước khi gắn tên"}
        sid = pipeline.bind_manual_name(track_id, name)
        if sid is None:
            return {"ok": False, "error": "không thấy track %d trong khung hình" % track_id}
        return {"ok": True, "student_id": sid, "name": pipeline.name_of(sid)}

    def unbind(self, track_id: int) -> Dict:
        pipeline = self._pipeline
        if pipeline is None or not self.running():
            return {"ok": False, "error": "camera chưa chạy"}
        if not pipeline.unbind_manual(track_id):
            return {"ok": False, "error": "không thấy track %d trong khung hình" % track_id}
        return {"ok": True}

    # ------------------------------------------------------------------ luồng nền
    def _run(self, cfg: Dict) -> None:
        import cv2
        from focusedge.classroom import ClassroomPipeline
        from focusedge.constants import HabitFeature

        pipeline = None
        cap = None
        try:
            pipeline = ClassroomPipeline.from_config(cfg)
            self._pipeline = pipeline
            source = (cfg.get("input") or {}).get("source", 0)
            cap = cv2.VideoCapture(source if isinstance(source, int) else str(source))
            if not cap.isOpened():
                raise RuntimeError("khong mo duoc camera %r" % (source,))
            # Webcam trả ~30 khung đen đầu tiên khi mới mở (main.open_source cũng bỏ chúng);
            # không bỏ thì hiệu chuẩn và đo bám vào ảnh đen.
            for _ in range(30):
                cap.read()
            width = int((cfg.get("input") or {}).get("process_width", 640))
            cap.set(cv2.CAP_PROP_FPS, 30)

            latest_results: List = []
            ai_frame = None
            ai_lock = threading.Lock()
            ai_event = threading.Event()
            ai_proc = 0.0
            acc: List[Dict] = []
            bucket = 0

            def ai_worker():
                nonlocal latest_results, ai_proc, acc, bucket
                while not self._stop.is_set():
                    ai_event.wait(timeout=0.2)
                    ai_event.clear()
                    if self._stop.is_set():
                        break
                    with ai_lock:
                        if ai_frame is None:
                            continue
                        f_copy = ai_frame.copy()
                    t0 = time.monotonic() * 1000
                    try:
                        res = pipeline.step(f_copy)
                        step_ms = time.monotonic() * 1000 - t0
                        ai_proc = _ema(ai_proc, step_ms)
                        latest_results = res
                        if res:
                            acc.append(self._means(res))
                        now_bucket = int(time.time() * 1000) // 60000 * 60000
                        if now_bucket != bucket:
                            if bucket and acc and self._on_minute:
                                self._on_minute(bucket, self._average(acc))
                            bucket = now_bucket
                            acc = []
                    except Exception:
                        pass

            ai_thread = threading.Thread(target=ai_worker, daemon=True, name="camera-ai-worker")
            ai_thread.start()

            fps = 0.0
            last_t = 0.0
            frames = 0
            started = int(time.time() * 1000)
            with self._lock:
                self._info["started_ms"] = started

            while not self._stop.is_set():
                ok, frame = cap.read()
                if not ok:
                    raise RuntimeError("mat khung hinh tu camera (cap.read that bai)")
                h, w = frame.shape[:2]
                if w > width:
                    frame = cv2.resize(frame, (width, max(1, int(h * width / float(w)))))

                t_now = time.monotonic() * 1000
                if last_t:
                    dt = t_now - last_t
                    if 0.0 < dt < 5000.0:
                        fps = _ema(fps, 1000.0 / dt)
                last_t = t_now
                frames += 1

                with ai_lock:
                    ai_frame = frame
                ai_event.set()

                view = self._draw(frame, pipeline, latest_results)
                ok2, buf = cv2.imencode(".jpg", view, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
                people = self._people(pipeline, ai_proc, fps)
                activity = getattr(pipeline.last_activity, "label", "") or ""
                top_hud = []
                try:
                    from focusedge.hud import hud_lines
                    primary_r = latest_results[0] if latest_results else None
                    raw_hud = hud_lines(primary_r, len(people), ai_proc, fps, activity=pipeline.last_activity)
                    top_hud = [{"text": l.text, "size": l.size, "colour": list(l.colour), "bold": l.bold} for l in raw_hud]
                except Exception:
                    pass

                recent_alerts = list(getattr(pipeline, "_alerts", []))[-10:]
                with self._lock:
                    self._jpeg = buf.tobytes() if ok2 else None
                    self._info = {"people": people, "fps": fps, "processing_ms": ai_proc,
                                  "activity": activity, "started_ms": started, "frames": frames,
                                  "hud_lines": top_hud, "recent_alerts": recent_alerts}

            ai_event.set()
            ai_thread.join(timeout=1.0)
        except Exception as e:  # ranh giới với camera và lõi đo: lỗi phải hiện lên màn hình
            self.error = "%s: %s" % (type(e).__name__, e)
        finally:
            if bucket and acc and self._on_minute:
                try:
                    self._on_minute(bucket, self._average(acc))
                except Exception:
                    pass
            self._pipeline = None
            if pipeline is not None:
                try:
                    pipeline.shutdown()   # chốt mọi phiên đang mở vào SQLite, đúng luật lõi
                except Exception:
                    pass
            if cap is not None:
                cap.release()
            with self._lock:
                self._jpeg = None
                self._info["people"] = []

    # ------------------------------------------------------------------ vẽ và đóng gói
    def _draw(self, frame, pipeline, results):
        import cv2
        view = frame
        if self.options.get("brighten"):
            view = cv2.convertScaleAbs(view, alpha=1.25, beta=20)
        if not self.options.get("boxes"):
            return view
        view = view.copy()
        boxes = pipeline.last_boxes
        by_id = {r.track_id: r for r in results}
        for person in boxes.persons:
            x0, y0, x1, y1 = [int(v) for v in person.bbox]
            cv2.rectangle(view, (x0, y0), (x1, y1), (0, 200, 0), 2)
            rt_result = by_id.get(person.track_id) or pipeline.person_result(person.track_id)
            label = (rt_result.student_id if rt_result and rt_result.student_id
                     else "guest-%d" % person.track_id)
            cv2.putText(view, label, (x0, max(14, y0 - 6)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 200, 0), 2)
            if self.options.get("overlay") and rt_result is not None and rt_result.sei is not None:
                cv2.putText(view, "SEI %d" % rt_result.sei, (x0, min(view.shape[0] - 6, y1 + 16)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 220, 220), 2)
                # Trạng thái theo ngữ cảnh GV: ASCII (cv2.putText không vẽ được dấu tiếng Việt).
                ctx = pipeline.context_view(person.track_id) or {}
                st = ctx.get("state", "NEUTRAL")
                if st == "ON_TASK":
                    cv2.putText(view, "ON-TASK", (x0, min(view.shape[0] - 6, y1 + 34)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 200, 0), 2)
                elif st == "WATCH":
                    cv2.putText(view, "WATCH %ds" % int(ctx.get("off_s", 0)),
                                (x0, min(view.shape[0] - 6, y1 + 34)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 220, 220), 2)
                elif st == "OFF_TASK":
                    cv2.putText(view, "OFF-TASK %ds" % int(ctx.get("off_s", 0)),
                                (x0, min(view.shape[0] - 6, y1 + 34)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 2)
        for tid, region in boxes.face_regions:
            if region is None:
                # _face_region trả None khi cửa sổ mặt bị mép khung hình ăn quá nhiều;
                # không bỏ qua thì unpack None đánh chết luồng camera chỉ vì một người ngồi lệch.
                continue
            x0, y0, x1, y1 = [int(v) for v in region]
            cv2.rectangle(view, (x0, y0), (x1, y1), (0, 220, 220), 1)
        return view

    def _people(self, pipeline, processing_ms: float = 0.0, fps: float = 0.0) -> List[Dict]:
        from focusedge.constants import HabitFeature
        from focusedge.hud import hud_lines
        from focusedge.person_pipeline import HudState
        out = []
        people_views = list(pipeline.people())
        face_count = len(people_views)
        for v in people_views:
            r = pipeline.person_result(v.track_id)
            feats = r.features if r is not None else {}
            if r is not None:
                if r.hud is None:
                    r.hud = HudState()
                ear_val = feats.get(HabitFeature.EAR)
                if ear_val is not None and (r.hud.ear1 is None or math.isnan(r.hud.ear1)):
                    r.hud.ear1 = float(ear_val)
                    r.hud.ear2 = float(ear_val)
                mar_val = feats.get(HabitFeature.MAR)
                if mar_val is not None and (r.hud.mar is None or math.isnan(r.hud.mar)):
                    r.hud.mar = float(mar_val)
                    if r.hud.bfr is None or math.isnan(r.hud.bfr):
                        r.hud.bfr = 0.0
                if r.pitch is not None and r.hud.pitch is None:
                    r.hud.pitch = r.pitch
                if r.yaw is not None and r.hud.yaw is None:
                    r.hud.yaw = r.yaw
                if r.distance_cm is not None and r.hud.distance_cm is None:
                    r.hud.distance_cm = r.distance_cm
                if r.sei is not None and r.hud.sei is None:
                    r.hud.sei = r.sei
                if r.perclos is not None and r.hud.perclos is None:
                    r.hud.perclos = r.perclos
                if r.quality is not None and r.hud.quality is None:
                    r.hud.quality = r.quality

            p_hud = []
            try:
                raw = hud_lines(r, face_count, processing_ms, fps, activity=pipeline.last_activity)
                p_hud = [{"text": l.text, "size": l.size, "colour": list(l.colour), "bold": l.bold} for l in raw]
            except Exception:
                pass

            out.append({
                "track_id": v.track_id,
                "student_id": v.student_id,
                "name": v.student_name or (v.student_id or "guest-%d" % v.track_id),
                "identity_conf": v.identity_conf,
                "seat_hint": v.seat_hint,
                "attention_label": v.attention_label,
                "attention_reason": v.attention_reason,
                "measurable": bool(v.measurable),
                "sei": r.sei if r is not None else None,
                "situation": getattr(r.situation, "name", str(r.situation)) if (r is not None and r.situation is not None) else "UNKNOWN",
                "perclos": round(r.perclos, 1) if r is not None and r.perclos is not None else None,
                "distance_cm": round(r.distance_cm, 1) if r is not None and r.distance_cm is not None else None,
                "alert": r.alert if r is not None else None,
                "ear": (round(feats[HabitFeature.EAR], 3)
                        if feats.get(HabitFeature.EAR) is not None
                        and math.isfinite(feats[HabitFeature.EAR]) else None),
                "mar": (round(feats[HabitFeature.MAR], 3)
                        if feats.get(HabitFeature.MAR) is not None
                        and math.isfinite(feats[HabitFeature.MAR]) else None),
                "pitch": round(r.pitch, 1) if r is not None and r.pitch is not None else None,
                "yaw": round(r.yaw, 1) if r is not None and r.yaw is not None else None,
                "quality": r.quality if r is not None else None,
                # Lớp khuyến nghị sư phạm (tài liệu 1+2+3): đánh giá theo ngữ cảnh khẩu
                # lệnh GV + theo dõi dấu hiệu giật lặp lại. None khi track không tồn tại.
                "context": pipeline.context_view(v.track_id),
                "tic": pipeline.tic_view(v.track_id),
                "hud_lines": p_hud,
            })
        return out

    @staticmethod
    def _means(results) -> Dict:
        from focusedge.constants import HabitFeature
        vals = {"sei": [], "ear": [], "mar": [], "angle": []}
        for r in results:
            if r.sei is not None:
                vals["sei"].append(float(r.sei))
            ear = r.features.get(HabitFeature.EAR)
            mar = r.features.get(HabitFeature.MAR)
            if ear is not None:
                vals["ear"].append(float(ear))
            if mar is not None:
                vals["mar"].append(float(mar))
            angle = r.pitch if r.pitch is not None else r.yaw
            if angle is not None:
                vals["angle"].append(abs(float(angle)))
        means = {k: (sum(v) / len(v) if v else None) for k, v in vals.items()}
        means["n"] = len(results)
        return means

    @staticmethod
    def _average(rows: List[Dict]) -> Dict:
        out = {}
        for key in ("sei", "ear", "mar", "angle"):
            vals = [r[key] for r in rows if r.get(key) is not None]
            out[key] = sum(vals) / len(vals) if vals else None
        # n_students is the average number of measured heads per frame in this minute.
        # Counting the accumulated rows instead would store the frame sample count under a
        # column the chart labels as children, which is a different quantity entirely.
        ns = [r["n"] for r in rows if r.get("n") is not None]
        out["n"] = int(round(sum(ns) / len(ns))) if ns else 0
        return out