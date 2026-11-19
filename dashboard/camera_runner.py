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
            tick_ms = 1000.0 / max(1.0, float((cfg.get("detection") or {}).get("detect_fps", 8)))
            last_tick = 0.0
            last_step = 0.0
            fps = 0.0
            proc = 0.0
            frames = 0
            started = int(time.time() * 1000)
            bucket = 0
            acc: List[Dict] = []
            last_flush = time.monotonic() * 1000
            with self._lock:
                self._info["started_ms"] = started
            while not self._stop.is_set():
                ok, frame = cap.read()
                if not ok:
                    raise RuntimeError("mat khung hinh tu camera (cap.read that bai)")
                h, w = frame.shape[:2]
                if w > width:
                    frame = cv2.resize(frame, (width, max(1, int(h * width / float(w)))))
                now = time.monotonic() * 1000
                if now - last_tick < tick_ms:
                    continue
                last_tick = now
                t0 = time.monotonic() * 1000
                results = pipeline.step(frame)
                step_ms = time.monotonic() * 1000 - t0
                if last_step:
                    dt = t0 - last_step
                    if 0.0 < dt < 5000.0:
                        fps = _ema(fps, 1000.0 / dt)
                last_step = t0
                proc = _ema(proc, step_ms)
                frames += 1
                view = self._draw(frame, pipeline, results)
                ok2, buf = cv2.imencode(".jpg", view, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
                people = self._people(pipeline)
                activity = getattr(pipeline.last_activity, "label", "") or ""
                with self._lock:
                    self._jpeg = buf.tobytes() if ok2 else None
                    self._info = {"people": people, "fps": fps, "processing_ms": proc,
                                  "activity": activity, "started_ms": started, "frames": frames}
                # Trung bình lớp ghi định kỳ vào class_trend:
                # - Ghi cập nhật mỗi 10s trong phút hiện tại để giáo viên thấy số ngay.
                # - Khi chuyển sang phút mới, chốt bucket cũ và bắt đầu bucket mới.
                if results:
                    acc.append(self._means(results))
                now_bucket = int(time.time() * 1000) // 60000 * 60000
                if bucket == 0:
                    bucket = now_bucket
                now_wall = time.monotonic() * 1000
                if now_bucket != bucket:
                    if acc and self._on_minute:
                        try:
                            self._on_minute(bucket, self._average(acc))
                        except Exception:
                            pass
                    bucket = now_bucket
                    acc = []
                    last_flush = now_wall
                elif acc and self._on_minute and (now_wall - last_flush >= 10000.0):
                    try:
                        self._on_minute(bucket, self._average(acc))
                    except Exception:
                        pass
                    last_flush = now_wall
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

    def _people(self, pipeline) -> List[Dict]:
        from focusedge.constants import HabitFeature
        out = []
        for v in pipeline.people():
            r = pipeline.person_result(v.track_id)
            feats = r.features if r is not None else {}
            out.append({
                "track_id": v.track_id,
                "student_id": v.student_id,
                "name": v.student_name or (v.student_id or "guest-%d" % v.track_id),
                "identity_conf": v.identity_conf,
                "measurable": bool(v.measurable),
                "sei": r.sei if r is not None else None,
                "situation": r.situation.name if r is not None else "UNKNOWN",
                "perclos": round(r.perclos, 1) if r is not None and r.perclos is not None else None,
                "distance_cm": round(r.distance_cm, 1) if r is not None and r.distance_cm is not None else None,
                "alert": r.alert if r is not None else None,
                "ear": round(feats.get(HabitFeature.EAR), 3) if feats.get(HabitFeature.EAR) is not None else None,
                "mar": round(feats.get(HabitFeature.MAR), 3) if feats.get(HabitFeature.MAR) is not None else None,
                "pitch": round(r.pitch, 1) if r is not None and r.pitch is not None else None,
                "yaw": round(r.yaw, 1) if r is not None and r.yaw is not None else None,
                "quality": r.quality if r is not None else None,
                # Lớp khuyến nghị sư phạm (tài liệu 1+2+3): đánh giá theo ngữ cảnh khẩu
                # lệnh GV + theo dõi dấu hiệu giật lặp lại. None khi track không tồn tại.
                "context": pipeline.context_view(v.track_id),
                "tic": pipeline.tic_view(v.track_id),
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
        m = {k: (sum(v) / len(v) if v else None) for k, v in vals.items()}
        m["n_students"] = len(results)
        return m

    @staticmethod
    def _average(rows: List[Dict]) -> Dict:
        out = {}
        for key in ("sei", "ear", "mar", "angle"):
            vals = [r[key] for r in rows if r.get(key) is not None]
            out[key] = round(sum(vals) / len(vals), 2) if vals else None
        stu = [r.get("n_students", 0) for r in rows if r.get("n_students") is not None]
        out["n"] = max(stu) if stu else 0
        return out