#!/usr/bin/env python3
"""Draw the per-student column preview with FABRICATED students - no camera, no models.

    python tools/preview_panels.py --people 3 --out out/preview_panels.png

Why this exists: the column-per-person layout is the part of the 2026-11-14 request a judge sees
first, and this laptop cannot run the camera leg yet (eye model and heavy deps missing). The numbers
below are invented on purpose and never touch the database or the out/*.json live views - the only
file written is the PNG named by --out. What is REAL is everything downstream of the numbers:
hud_lines, the type ladder, the wrapping, the per-column scrolling and the camera band, i.e. exactly
what the operator will see once the camera leg runs. Compare --people 1 against the old single
column: that view must stay byte-identical to the phone-parity layout.
"""
from __future__ import annotations

import argparse
import math
import os
import sys
from collections import deque
from types import SimpleNamespace as NS

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _student(i):
    from focusedge.constants import Situation
    from focusedge.person_pipeline import HudState, PersonResult

    sei = (72, 45, 88, 61)[i % 4]
    hud = HudState(ear1=0.27, ear2=0.26, openness=0.82, sigma=0.02, eye_valid=True,
                   using_blendshapes=True, blink_total=38 + i * 3, avg_blink_ms=220.0,
                   perclos=11.0 + i * 2, perclos_p70=9.0 + i, perclos_p80=7.0 + i,
                   bpm=14.0 + i, distance_cm=46.0 + i * 3, distance_scale=1.0,
                   yaw=3.0 + i, pitch=4.0, roll=1.0, iris_available=True,
                   gaze_h_dev=0.05, gaze_v_dev=0.04, eyes_open_for_gaze=True,
                   mar=0.21, bfr=0.95, yawns_recent=i % 2, sei=sei, average_sei=sei - 4,
                   fatigue=18 + i * 9, chronic_level=1 if i == 1 else 0,
                   quality=82 - i * 7, quality_note="Tốt", reliable=True,
                   alert_message="LOOK_AWAY" if i == 1 else None,
                   session_seconds=312 + i * 40, landmark_count=478)
    names = [("hs1", "Le Van A"), ("hs2", "Tran Thi B"), ("hs3", "Pham Van C"), (None, "")]
    sid, name = names[i % 4]
    result = PersonResult(track_id=i + 1, student_id=sid, student_name=name,
                          identity_conf="manual" if sid else "unbound",
                          measurable=True, calibrated=True, sei=sei,
                          situation=Situation.DOWN if i == 1 else Situation.FORWARD,
                          eye_valid=True, openness=0.82, perclos=hud.perclos, bpm=hud.bpm,
                          distance_cm=hud.distance_cm, yaw=hud.yaw, pitch=hud.pitch,
                          alert="LOOK_AWAY" if i == 1 else None, quality=hud.quality, hud=hud)
    view = NS(track_id=i + 1, student_id=sid, student_name=name,
              identity_conf=result.identity_conf, measurable=True, score=0.61, margin=0.08,
              samples=24, runner_up="hs2", gate=0.52, margin_gate=0.05)
    hist = deque((sei + 12 * math.sin(t / 9.0 + i) for t in range(60)), maxlen=120)
    return result, view, hist


def main() -> int:
    ap = argparse.ArgumentParser(description="fabricated multi-column preview")
    ap.add_argument("--people", type=int, default=3)
    ap.add_argument("--out", default=os.path.join("out", "preview_panels.png"))
    args = ap.parse_args()

    import numpy as np
    from PIL import Image

    import main as edge_main

    frame = np.empty((480, 640, 3), np.uint8)
    frame[:] = np.linspace(50, 110, 480, dtype=np.uint8)[:, None, None]
    results, people, histories = [], [], {}
    for i in range(max(1, args.people)):
        r, v, h = _student(i)
        results.append(r)
        people.append(v)
        histories[r.track_id] = h
    state = {"class_label": "Lớp 12A", "measurable": len(results), "tracks": len(results),
             "det_rate": 7.0, "store_note": "đang lưu focuschange.db + out/",
             "camera_note": "cam 0 640x480 @30 fps", "processing_ms": 145.0, "fps": 7.0,
             "sei_history": histories[1], "histories": histories, "sample_ms": {},
             "people": people, "shown": 1, "focus": None, "shown_mode": "tự động",
             "identity": people[0], "person": results[0], "pipeline": None,
             "scroll": 0, "overlay": False, "yolo": False, "boxes": None,
             "panel_scroll": {}, "panel_factor": {}, "max_panels": 3, "activity": None}
    arr = edge_main.render(frame, results, state)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    Image.fromarray(arr[:, :, ::-1]).save(args.out)
    print("preview written: %s (%dx%d, %d fabricated people)" % (
        os.path.abspath(args.out), arr.shape[1], arr.shape[0], len(results)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())