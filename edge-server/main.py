#!/usr/bin/env python3
"""FocusChangeAI edge server - classroom entrypoint.

Reads a YAML config, opens a camera/video/RTSP source, and runs the five-layer
pipeline frame by frame. Bound students are written to Firestore keyed by their
studentId (sessions/bands/presence) plus one aggregate class_live document; if no
service-account is configured the same payloads are dumped under ``local_output.dir``.

Examples:
    python main.py --config config.yaml
    python main.py --config config.yaml --source 0 --show
    python main.py --config config.yaml --source class.mp4 --max-frames 5000
"""
from __future__ import annotations

import argparse
import os
import sys
import time

# allow "python main.py" from the edge-server dir without installing the package
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from focusedge.classroom import ClassroomPipeline  # noqa: E402


def load_config(path: str) -> dict:
    import yaml
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def open_source(source):
    import cv2
    # an int (or numeric string) is a webcam index; otherwise it's a file/URL
    if isinstance(source, int) or (isinstance(source, str) and source.isdigit()):
        cap = cv2.VideoCapture(int(source))
    else:
        cap = cv2.VideoCapture(str(source))
    if not cap.isOpened():
        raise SystemExit("could not open input source: %r" % (source,))
    return cap


def fit_width(frame, target_w: int):
    import cv2
    h, w = frame.shape[:2]
    if target_w and w > target_w:
        scale = target_w / float(w)
        frame = cv2.resize(frame, (target_w, max(1, int(h * scale))))
    return frame


def draw_overlay(frame, results):
    import cv2
    for r in results:
        label = "%s %s" % (r.student_name or ("t%d" % r.track_id),
                           "SEI %s" % r.sei if r.sei is not None else r.situation.name)
        color = (0, 200, 0) if (r.sei is None or r.sei >= 60) else (0, 0, 220)
        cv2.putText(frame, label, (12, 30 + 24 * (results.index(r) % 20)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
        if r.alert:
            cv2.putText(frame, "! " + r.alert, (12, 52 + 24 * (results.index(r) % 20)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)
    return frame


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="FocusChangeAI classroom edge server")
    ap.add_argument("--config", default=os.path.join(os.path.dirname(__file__), "config.yaml"))
    ap.add_argument("--source", default=None, help="override input.source (index/file/URL)")
    ap.add_argument("--show", action="store_true", help="open a preview window (needs a display)")
    ap.add_argument("--max-frames", type=int, default=0, help="stop after N frames (0 = run forever)")
    ap.add_argument("--session-minutes", type=float, default=0.0,
                    help="auto-commit and restart sessions after this many minutes")
    args = ap.parse_args(argv)

    cfg = load_config(args.config)
    if args.source is not None:
        cfg.setdefault("input", {})["source"] = args.source

    import cv2  # imported here so the module above stays import-testable

    pipeline = ClassroomPipeline.from_config(cfg)
    cap = open_source(cfg.get("input", {}).get("source", 0))
    process_width = int(cfg.get("input", {}).get("process_width", 1280))
    detect_fps = float(cfg.get("input", {}).get("detect_fps", 12))
    tick_ms = 1000.0 / max(1.0, detect_fps)

    session_reset_ms = int(args.session_minutes * 60_000) if args.session_minutes > 0 else 0
    started_wall = int(time.time() * 1000)

    frame_count = 0
    last_tick = 0.0
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            frame = fit_width(frame, process_width)
            frame_count += 1

            # throttle detection to detect_fps (drop frames between ticks)
            now = time.monotonic() * 1000
            if now - last_tick < tick_ms:
                if args.show:
                    cv2.imshow("FocusChangeAI", frame)
                    if cv2.waitKey(1) & 0xFF == 27:
                        break
                continue
            last_tick = now

            results = pipeline.step(frame)

            if session_reset_ms and int(time.time() * 1000) - started_wall >= session_reset_ms:
                pipeline.shutdown()
                started_wall = int(time.time() * 1000)

            if args.show:
                draw_overlay(frame, results)
                cv2.imshow("FocusChangeAI", frame)
                if cv2.waitKey(1) & 0xFF == 27:
                    break

            if args.max_frames and frame_count >= args.max_frames:
                break
    except KeyboardInterrupt:
        print("\nstopping...")
    finally:
        pipeline.shutdown()
        cap.release()
        if args.show:
            cv2.destroyAllWindows()

    writer = pipeline._writer
    print("frames=%d tracks_active=%d uploaded=%d failed=%d class=%s%s" % (
        frame_count, pipeline.active_tracks, writer.sent, writer.failed,
        pipeline.class_id, " (offline->local)" if not writer.enabled else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
