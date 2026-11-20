#!/usr/bin/env python3
"""FocusChangeAI edge server - classroom entrypoint.

Reads a YAML config, opens a camera/video/RTSP source, and runs the five-layer
pipeline frame by frame. Bound students are stored on this machine: one row per
session in ``data/focuschange.db`` (see ``database/focuschange_db.py``), and the two
live views - the per-student heartbeat and the class board - as ``out/*.json``.
Nothing leaves the box.

Examples:
    python main.py --config config.yaml
    python main.py --config config.yaml --source 0 --show
    python main.py --config config.yaml --source class.mp4 --max-frames 5000
"""
from __future__ import annotations

import argparse
from collections import deque
import os
import sys
import time

# allow "python main.py" from the edge-server dir without installing the package
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from focusedge.classroom import ClassroomPipeline  # noqa: E402
from focusedge.detection import CONTEXT_VI  # noqa: E402
from focusedge.hud import (  # noqa: E402
    DOT_COLOURS, EYE_DRAW, IRIS_FIRST, Line, _C, _SMALL, _TITLE_M, landmark_to_frame_px)
from focusedge.panels import (  # noqa: E402
    FONT_FACTORS, clamp_scroll, fit_factor, panel_layout, panel_people)

_FONTS: dict = {}


def load_config(path: str) -> dict:
    import yaml
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def _sharpness(frame):
    """Laplacian variance: 0.0 for a perfectly flat image, tens for a real scene."""
    import cv2
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def _describe_capture(cap, frame):
    """What the device really gave, as opposed to what was asked for."""
    import cv2
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = float(cap.get(cv2.CAP_PROP_FPS) or 0.0)
    raw = int(cap.get(cv2.CAP_PROP_FOURCC) or 0)
    # Drivers pad this with NULs or control bytes, so keep printable ASCII only - otherwise the
    # line that is supposed to tell the operator which codec is live prints as punctuation.
    fourcc = "".join(c for c in
                     (chr((raw >> (8 * i)) & 0xFF) for i in range(4)) if 32 <= ord(c) < 127) or "?"
    return w, h, fps, fourcc, _sharpness(frame)


def open_source(source, io_cfg=None):
    """Open a webcam/file source, ask for the configured mode, and print the real one.

    A webcam's requested mode and its delivered mode are not the same thing. Measured on this
    project's laptop: asked for MJPG the driver stays in YUY2, 640x480 runs 30 fps while 1280x720
    manages 10, and a lens left pointing at the ceiling opens, reads, reports "success" and produces
    frames no face can ever be found in. Printing what arrived - including a sharpness number - is
    the only way an operator sees that before ten minutes of a session are gone.

    A numeric source also tries its neighbouring indexes: a laptop's built-in camera and a USB
    webcam share the machine and which one gets 0 is not predictable, so "index 0" is a guess.
    """
    import cv2
    io_cfg = io_cfg or {}
    numeric = isinstance(source, int) or (isinstance(source, str) and str(source).isdigit())
    wanted = int(source) if numeric else None
    candidates = ([wanted] + [i for i in range(4) if i != wanted]) if numeric else [source]

    reason = "nothing tried"
    for cand in candidates:
        cap = cv2.VideoCapture(cand if isinstance(cand, int) else str(cand))
        if not cap.isOpened():
            cap.release()
            reason = "%r did not open" % (cand,)
            continue
        fourcc = str(io_cfg.get("fourcc") or "").strip()
        if fourcc:
            cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*fourcc))
        if io_cfg.get("width"):
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, int(io_cfg["width"]))
        if io_cfg.get("height"):
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, int(io_cfg["height"]))
        if io_cfg.get("fps"):
            cap.set(cv2.CAP_PROP_FPS, float(io_cfg["fps"]))
        frame = None
        for _ in range(5):
            ok, frame = cap.read()
            if ok:
                break
        if frame is None or not ok:
            cap.release()
            reason = "%r opened but produced no frame" % (cand,)
            continue
        # A UVC camera sends pure black frames while its exposure settles. Measured on this laptop's
        # C270: the first ~25 reads were flat 0.0 and a real picture only arrived around frame 30.
        # So a flat FIRST frame is normal start-up, not a fault - judging frame 0 would warn the
        # operator on every single run. Discard the black part, then describe what is left.
        warm = 0
        while warm < 90 and _sharpness(frame) < 5.0:
            ok, nxt = cap.read()
            if not ok:
                break
            frame, warm = nxt, warm + 1
        if warm:
            print("camera %r: discarded %d black start-up frames" % (cand, warm))
        w, h, fps, got_fourcc, sharp = _describe_capture(cap, frame)
        ask = "%sx%s/%s@%s" % (io_cfg.get("width") or "?", io_cfg.get("height") or "?",
                               fourcc or "?", io_cfg.get("fps") or "?")
        print("camera %r: delivering %dx%d %s @ %.0f fps (asked %s)" % (cand, w, h, got_fourcc, fps, ask))
        if sharp < 5.0:
            print("  WARNING: still flat after %d frames (sharpness %.1f) - the lens is covered or "
                  "the camera is pointed at a blank surface; nothing will be measured" % (warm + 1, sharp))
        if wanted is not None and cand != wanted:
            print("  NOTE: source %d was configured, but it did not work - using index %d instead"
                  % (wanted, cand))
        return cap
    raise SystemExit("could not open input source %r (%s)" % (source, reason))


def fit_width(frame, target_w: int):
    import cv2
    h, w = frame.shape[:2]
    if target_w and w > target_w:
        scale = target_w / float(w)
        frame = cv2.resize(frame, (target_w, max(1, int(h * scale))))
    return frame


def _font(px: int, bold: bool = False):
    """A Windows font that has Vietnamese glyphs, cached per size.

    cv2.putText can only draw the Hershey ASCII set, so every accented letter the phone HUD uses
    ("Đang hiệu chuẩn", "Khoảng cách") would come out as boxes or missing marks. Pillow with the
    system Segoe UI renders them, which is what makes the two screens read the same.
    """
    from PIL import ImageFont
    key = (px, bold)
    cached = _FONTS.get(key)
    if cached is not None:
        return cached
    names = ("segoeui-bold.ttf", "segoeuisl.ttf", "arialbd.ttf", "segoeui.ttf") if bold \
        else ("segoeui.ttf", "segoeuil.ttf", "arial.ttf")
    for name in names:
        try:
            face = ImageFont.truetype(os.path.join("C:\\Windows\\Fonts", name), px)
        except OSError:
            continue
        _FONTS[key] = face
        return face
    fallback = ImageFont.load_default()
    _FONTS[key] = fallback
    return fallback


# The three marks the phone prefixes its alert and chronic lines with. Checked on this machine's
# fonts: Segoe UI has no glyph for any of them (U+26A0, U+2139, U+26D4 all missing from its cmap),
# while Segoe UI Symbol has all three - so Pillow would draw an empty box exactly where the warning
# is. Pillow does no automatic fallback, hence drawing these characters with their own face.
_LEAD_SYMBOLS = "\u26a0\u2139\u26d4"


def _symbol_font(px: int, bold: bool = False):
    from PIL import ImageFont
    key = ("sym", px, bold)
    cached = _FONTS.get(key)
    if cached is not None:
        return cached
    for name in ("seguisym.ttf", "segoeui-sb.ttf"):
        try:
            face = ImageFont.truetype(os.path.join("C:\\Windows\\Fonts", name), px)
        except OSError:
            continue
        _FONTS[key] = face
        return face
    face = _font(px, bold)
    _FONTS[key] = face
    return face


def _wrap(draw, text: str, font, max_w: int):
    """Greedy word wrap so a long HUD sentence behaves like a wrapping Compose Text."""
    words = text.split(" ")
    out, line = [], ""
    for word in words:
        trial = word if not line else line + " " + word
        if draw.textlength(trial, font=font) <= max_w or not line:
            line = trial
        else:
            out.append(line)
            line = word
    if line:
        out.append(line)
    return out


def _layout(lines, text_w: int, chart_h: int):
    """Split every HUD row into what to draw and where, one entry per wrapped visual row.

    A leading warning mark keeps its own column: the text after it starts at the same x on every
    wrapped row, which is how the phone's prefixed sentence reads in a wrapping Compose Text.
    """
    from PIL import Image, ImageDraw

    from focusedge.hud import CHART_ROW

    probe = ImageDraw.Draw(Image.new("RGB", (8, 8)))
    rows = []
    for ln in lines:
        if ln.text == CHART_ROW:
            # The phone's sparkline box is 44 dp tall and always there; with fewer than two samples
            # there is no line to draw, so the slot shrinks to a gap rather than moving down.
            rows.append((chart_h if chart_h else 8, 0, "", None, CHART_ROW, None, None))
            continue
        font = _font(ln.size, ln.bold)
        text = ln.text
        sym, sym_font, indent = "", None, 0
        if text and text[0] in _LEAD_SYMBOLS:
            sym, sym_font = text[0], _symbol_font(ln.size, ln.bold)
            indent = int(sym_font.getlength(sym)) + 4
            text = text[1:].lstrip(" ")
        if not text:
            rows.append((6, 0, "", None, "", font, ln.colour))
            continue
        lead = int(font.size * 1.35)
        for row, piece in enumerate(_wrap(probe, text, font, max(40, text_w - indent))):
            rows.append((lead, indent, sym if row == 0 else "", sym_font, piece, font, ln.colour))
    return rows


def _pick_person(results):
    """Whose numbers the column shows. The phone only ever has one student, so the PC has to choose.

    Preference order is the honest one: a person the pipeline is actually measuring and has
    finished calibrating, then any measurable person, then the first track. A wall photograph that
    YOLO boxed as a person never outranks a real face, and if the chosen person is a photograph the
    column says so in the phone's own words instead of showing another person's numbers.

    This is the fallback for a caller with no selection state; a run with two children in shot uses
    _shown_track instead, because "best this frame" recomputed every frame is a column that moves.
    """
    if not results:
        return None
    measuring = [r for r in results if r.measurable and r.calibrated]
    if measuring:
        return max(measuring, key=lambda r: r.quality if r.quality is not None else -1)
    any_face = [r for r in results if r.measurable]
    return any_face[0] if any_face else results[0]


def _shown_track(people, state: dict):
    """Whose numbers the column shows now, and which mode got it there.

    Asking "who looks best this frame" every frame hands the column to whichever child moved
    closer to the lens a second ago. On screen that swap is indistinguishable from a machine that
    mixed the two children up - which is exactly what this is answering - so the shown person is
    remembered: a key wins while that person is in frame, otherwise the column stays on whoever it
    already shows, and only an empty selection or a person who left the frame picks somebody new.
    """
    if not people:
        return None, ""
    ids = [p.track_id for p in people]
    focus = state.get("focus")
    if focus in ids:
        return focus, "chọn bằng phím"
    shown = state.get("shown")
    if shown in ids:
        return shown, "đang theo dõi"
    measurable = [p for p in people if p.measurable]
    named = [p for p in measurable if p.student_id]
    return (named or measurable or people)[0].track_id, "tự động"


def _cycle_focus(people, current, step: int = 1):
    """The track id a key press lands on, walking the menu in the order the menu shows it."""
    if not people:
        return None
    ids = [p.track_id for p in people]
    if current not in ids:
        return ids[0]
    return ids[(ids.index(current) + step) % len(ids)]


def _whom_note(state: dict) -> str:
    """The line naming whose numbers are on the screen, and how that person came to be chosen.

    Numbers with no name over them cannot be read by a second person, and with two children in the
    same frame the name plus "em 2/3" is the whole difference between a switch and a mix-up.
    """
    view = state.get("identity")
    if view is None:
        return "Không có ai trong khung hình"
    people = state.get("people") or []
    ids = [p.track_id for p in people]
    who = view.student_name or "chưa gắn tên"
    if view.student_id:
        who = "%s (%s)" % (view.student_name, view.student_id)
    mode = state.get("shown_mode") or "tự động"
    if len(ids) > 1:
        pos = ids.index(view.track_id) + 1 if view.track_id in ids else 1
        return "Số liệu của em %d/%d · %s · %s (N hoặc 1..9 để đổi em)" % (pos, len(ids), who, mode)
    return "Số liệu của %s · %s" % (who, mode)


def _history_for(histories: dict, track_id: int) -> "deque":
    """This person's own SEI curve, created empty on first use and never shared with another."""
    hist = histories.get(track_id)
    if hist is None:
        hist = histories[track_id] = deque(maxlen=120)
    return hist


def _ema(prev: float, value: float) -> float:
    """Exponential mean seeded by the first sample, NaN standing in for "no sample yet"."""
    return value if prev != prev else 0.7 * prev + 0.3 * value


def _camera_note(cap, source) -> str:
    """Which device the operator is actually looking at, so a swapped index cannot pass unnoticed."""
    import cv2
    return "cam %s %dx%d @%.0f fps" % (
        source, int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
        float(cap.get(cv2.CAP_PROP_FPS) or 0.0))


def _store_note(store) -> str:
    """Where the numbers go, in the words the operator needs before they need the path.

    The failure half matters more than the success half: a run whose database never opened measures
    a whole lesson, shows every box and number on screen, and stores nothing. Saying "đang lưu"
    while that happens would be the one lie this line could tell.
    """
    if not getattr(store, "enabled", False):
        return "KHÔNG LƯU ĐƯỢC - database (%s)" % (getattr(store, "last_error", "") or "?")
    return "đang lưu %s + %s/" % (os.path.basename(store.db_path), store.local_dir)


def _screen_work_size() -> tuple:
    """Usable screen size in pixels, taskbar excluded.

    Measured on this machine: 1366x768 panel, 1366x728 of work area. Both numbers decide the layout:
    a cv2 window has no scroll bar, and a canvas taller than the screen hides the last parameters,
    while a canvas cv2 is asked to stretch (WINDOW_NORMAL maximised by the operator) comes out soft -
    the whole preview here is only 640 px wide, so any width beyond that is invented pixels.
    SPI_GETWORKAREA is used rather than GetSystemMetrics(49): that index is SM_SAMEDISPLAYFORMAT and
    answers a different question, which is how a first attempt here read "16".
    """
    try:
        import ctypes
        from ctypes import wintypes
        rect = wintypes.RECT()
        if not ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(rect), 0):
            return 1366, 728
        w = int(rect.right - rect.left)
        h = int(rect.bottom - rect.top)
        return (w if w > 480 else 1366), (h if h > 240 else 728)
    except Exception:  # noqa: BLE001 - no display, non-Windows, or blocked call
        return 1366, 728


def _scroll_view(canvas, state: dict):
    """Cut the part of the column the operator asked for, so the window always fits the screen."""
    from PIL import Image, ImageDraw

    win_w, win_h = canvas.width, canvas.height
    view_h = min(win_h, max(320, _screen_work_size()[1] - 44))
    if view_h >= win_h:
        return canvas
    lowest = win_h - view_h
    offset = int(state.get("scroll", 0))
    if offset < 0 or offset > lowest:
        offset = max(0, min(offset, lowest))
        state["scroll"] = offset
    out = canvas.crop((0, offset, win_w, offset + view_h)).convert("RGB")
    if win_h - (offset + view_h) >= 24:
        # A cv2 window shows no scroll bar, so an unreachable remainder looks exactly like an app
        # with no more parameters. Say what is hidden and which key reveals it.
        d = ImageDraw.Draw(out, "RGBA")
        hint = "còn dòng phía dưới · bấm S"
        f = _font(13, True)
        x = win_w - int(d.textlength(hint, font=f)) - 26
        d.rectangle([x - 6, view_h - 24, win_w - 4, view_h - 4], fill=(12, 12, 14, 235))
        d.text((x, view_h - 21), hint, font=f, fill=(255, 205, 0, 255))
    return out


def _bind_manual(state: dict) -> None:
    """m: say who the chosen head is, for the machines where the camera cannot be asked.

    Without a working ArcFace backend every track stays a guest, and _retire refuses to store a
    guest's session - so a run that looked perfectly measured on screen leaves the ten tables empty
    and everything is gone once the live view is overwritten. Who is sitting in the chair is the one
    fact somebody standing at the camera does know, and it is stored as identity_conf "manual" so a
    declared name can never be read back as a face match.
    """
    pipeline = state.get("pipeline")
    people = state.get("people") or []
    if pipeline is None or not people:
        # Said out loud rather than ignored: a key that does nothing looks like a broken key, and the
        # real answer here is "pick a head first".
        print("bind: nobody in frame - press N or 1-9 first")
        return
    ids = [p.track_id for p in people]
    tid = state.get("focus")
    if tid not in ids:
        tid = ids[0]
        state["focus"] = state["shown"] = tid
    sid = pipeline.bind_manual_next(tid)
    # Read back into the menu: the column repaints next frame, and whoever just pressed the key needs
    # the answer now. ASCII only, like the class-context line - a Vietnamese name in roster.json would
    # otherwise be able to stop a measuring run over a log line.
    state["people"] = pipeline.people()
    print("bind: track %d -> %s" % (tid, sid if sid else "released"))


def _handle_key(key: int, state: dict) -> bool:
    """True when the window should close.

    W and S move the reading window. A cv2 window has no scroll bar, so without these the last
    lines of the parameter column are simply unreachable on a short screen. One press moves most of
    a screen rather than 120 px: on this panel 120 px was about an eighth of the hidden part, so
    reaching the bottom took eight presses and read as "scrolling does not work".

    N (or Tab), 1..9 and A belong to a different problem: with two children in one frame the
    operator has to be able to say which one the numbers are about, and change that on purpose
    rather than watch the choice change under them. M answers the question those keys cannot: which
    student that chosen head actually is, when recognition is unavailable and refuses to guess.
    """
    if key == 27:
        return True
    if key in (ord('o'), ord('O')):
        state["overlay"] = not state.get("overlay", False)
        state["scroll"] = 0
        return False
    if key in (ord('y'), ord('Y')):
        state["yolo"] = not state.get("yolo", False)
        return False
    people = state.get("people") or []
    if key in (ord('n'), ord('N'), 9):
        state["focus"] = _cycle_focus(people, state.get("focus", state.get("shown")))
        if state["focus"] is not None:
            state["shown"] = state["focus"]
        return False
    if ord('1') <= key <= ord('9'):
        idx = key - ord('1')
        if idx < len(people):
            state["focus"] = people[idx].track_id
            state["shown"] = people[idx].track_id
        return False
    if key in (ord('a'), ord('A')):
        # Back to the machine's choice - which is still the person already on screen, not whoever
        # happens to look best this frame.
        state["focus"] = None
        return False
    if key in (ord('m'), ord('M')):
        _bind_manual(state)
        return False
    step = max(160, int(_screen_work_size()[1] * 0.55))
    if key in (ord('w'), ord('W')):
        _scroll_by(state, -step)
    elif key in (ord('s'), ord('S')):
        _scroll_by(state, step)
    elif key in (ord('t'), ord('T')):
        _scroll_by(state, None)
    return False


def _scroll_by(state: dict, delta) -> None:
    """W/S/T move the reading window of whichever column the operator is on.

    With one person in frame that is the whole canvas, exactly as before. With a column per person
    each column keeps its own offset: scrolling the focused child must not move the neighbour's
    text, or the columns stop being independent instruments. None means "back to the top".
    """
    if len(state.get("people") or []) >= 2:
        scrolls = state.setdefault("panel_scroll", {})
        tid = state.get("shown")
        if delta is None:
            scrolls[tid] = 0
        else:
            scrolls[tid] = max(0, int(scrolls.get(tid, 0)) + delta)
    elif delta is None:
        state["scroll"] = 0
    else:
        state["scroll"] = max(0, int(state.get("scroll", 0)) + delta)


def _scaled(ln: Line, factor: float) -> Line:
    return Line(ln.text, max(9, int(round(ln.size * factor))), ln.colour, ln.bold)


def _fit_rows(lines, text_w: int, panel_h: int, factor: float, chart_h: int):
    """Take the phone's rows in order until the panel is full, and count what had to be left out.

    The count is returned because a preview that quietly drops lines is how a judge reads a missing
    number as a zero. Whatever fits is whatever there is, and the panel says so on its last line.
    """
    rows, used, taken = [], 0, 0
    for ln in lines:
        one = _layout([_scaled(ln, factor)], text_w, chart_h)
        height = sum(row[0] for row in one)
        if used + height > panel_h:
            break
        rows.extend(one)
        used += height
        taken += 1
    return rows, len(lines) - taken


def _render_overlay(frame, person, lines, state: dict, view=None):
    """The phone's numbers written large ON the picture, for a booth where people stand back.

    Column mode is the one that must match the phone, and it stays the default. This mode keeps the
    phone's wording, colours and order but puts them over the frame at 1.35x the size. A 640x480
    picture cannot hold all ~26 rows at that size, so it shows as many as fit and names the rest -
    the alternative is a font small enough to fit everything, which is the thing being complained
    about. The picture is never upscaled: the panel is drawn over the lens's own pixels.
    """
    import numpy as np
    from PIL import Image, ImageDraw

    from focusedge.hud import CHART_ROW

    fh, fw = frame.shape[:2]
    margin = 10
    canvas = Image.new("RGB", (fw, fh), _C.SURFACE)
    _blit_camera(canvas, frame, 0, 0, fw, fh,
                 person.landmarks if person is not None and person.measurable else None,
                 person.face_bbox if person is not None else None, view)

    panel_w = int(fw * 0.62)
    # The same size rule the column uses: the sparkline slot only exists from the second sample on.
    # Reserving it against an empty history is how _draw_sei_chart is handed a list to index into.
    scale = 1.35
    history = list(state.get("sei_history", ()))
    chart_h = int(52 * scale) if len(history) >= 2 else 0
    rows, hidden = _fit_rows(lines, panel_w - 2 * margin, fh - 2 * margin, scale, chart_h)
    d = ImageDraw.Draw(canvas, "RGBA")
    # The phone's surface colour, not a dark scrim. Every text colour in hud.py was chosen against
    # a light Material surface (ON_SURFACE is near-black, and green/amber/red carry meaning), so a
    # black panel over the video renders the whole column invisible - the first version here did
    # exactly that. Same surface as the phone also keeps the two screens readable the same way.
    d.rectangle([0, 0, panel_w, fh], fill=_C.SURFACE + (222,))

    y = margin
    for lead, indent, sym, sym_font, piece, font, colour in rows:
        if piece == CHART_ROW:
            if chart_h:
                _draw_sei_chart(d, margin, y, panel_w - 2 * margin, chart_h - 8, history)
        else:
            if sym:
                d.text((margin, y), sym, font=sym_font, fill=colour + (255,))
            if piece:
                d.text((margin + indent, y), piece, font=font, fill=colour + (255,))
        y += lead
    if hidden:
        d.text((margin, fh - margin - 15), "còn %d dòng nữa · bấm O" % hidden,
               font=_font(12, True), fill=(150, 60, 0, 255))
    return np.ascontiguousarray(np.array(canvas)[:, :, ::-1])


def _render_panels(frame, results, state: dict, people, person) -> "np.ndarray":
    """One full phone column per person in frame - the 2026-11-14 request, drawn.

    Every column IS the single-person column: same hud_lines, same order, same colours, its own SEI
    sparkline from its own history. Nothing is summarised away to make room; when a column's wrapped
    text is taller than the screen it scrolls inside its own rectangle (W/S on the focused column)
    and says how many lines are below, because a quietly dropped line is how a judge reads a missing
    number as a zero. The focused column carries a purple border so "whose numbers am I scrolling"
    is never a guess, and the machine's own run line sits once in the footer instead of once per
    child - it describes the box, not the student.
    """
    import numpy as np
    from PIL import Image, ImageDraw

    from focusedge.hud import CHART_ROW, hud_lines

    taken, extra = panel_people(people, state.get("focus") or state.get("shown"),
                                state.get("max_panels", 3))
    fh, fw = frame.shape[:2]
    work_w, work_h = _screen_work_size()
    geo = panel_layout(work_w, work_h, len(taken), fw, fh)
    margin = geo["margin"]
    canvas = Image.new("RGB", (geo["win_w"], work_h), _C.SURFACE)
    d = ImageDraw.Draw(canvas, "RGBA")
    cam_x, cam_y, cam_w, cam_h = geo["cam"]
    _blit_camera(canvas, frame, cam_y, cam_x, cam_w, cam_h,
                 person.landmarks if person is not None and person.measurable else None,
                 person.face_bbox if person is not None else None,
                 state.get("boxes") if state.get("yolo") else None)
    if extra:
        _plate(d, cam_x, cam_y + cam_h + 6,
               "+%d người nữa trong khung · phím 1-9 để chọn" % extra,
               (235, 235, 235), size=12, bold=False, max_x=cam_x + cam_w)

    by_id = {r.track_id: r for r in results}
    pipeline = state.get("pipeline")
    scrolls = state.setdefault("panel_scroll", {})
    factors = state.setdefault("panel_factor", {})
    now = time.monotonic()
    shown = state.get("shown")
    for i, view in enumerate(taken):
        tid = view.track_id
        x, y, w, h = geo["panels"][i]
        r = by_id.get(tid)
        if r is None and pipeline is not None:
            # Their own last numbers, never a neighbour's fresh ones - the single-column rule.
            r = pipeline.person_result(tid)
        lines = list(hud_lines(r, 1 if (r is not None and r.measurable) else 0,
                               state.get("processing_ms", float("nan")),
                               state.get("fps", float("nan")),
                               identity=view, activity=state.get("activity")))
        who = view.student_name or "chưa gắn tên"
        if view.student_id:
            who = "%s (%s)" % (view.student_name, view.student_id)
        head = "Em %d/%d · %s" % (i + 1, len(people), who)
        if tid == shown:
            head += " · %s" % (state.get("shown_mode") or "tự động")
        lines.insert(1, Line(head, _TITLE_M, _C.PRIMARY, True))
        history = list((state.get("histories") or {}).get(tid, ()))
        chart_h = 52 if len(history) >= 2 else 0
        text_w = w - 16

        # The type ladder is measured, not guessed, but re-measuring five wraps per column per frame
        # is arithmetic a 2-core box does not need at 30 fps: the answer is cached per column width
        # and refreshed every 1.5 s, slower than any child moves between chairs.
        key = (tid, w)
        cached = factors.get(key)
        if cached is None or now - cached[0] > 1.5:
            heights = [sum(row[0] for row in _layout([_scaled(ln, f) for ln in lines],
                                                    text_w, chart_h))
                       for f in FONT_FACTORS]
            factor = fit_factor(heights, h)
            factors[key] = (now, factor)
        else:
            factor = cached[1]
        rows = _layout([_scaled(ln, factor) for ln in lines], text_w, chart_h)
        content_h = sum(row[0] for row in rows)
        offset = clamp_scroll(scrolls.get(tid, 0), content_h, h)
        scrolls[tid] = offset

        border = _C.PRIMARY + (255,) if tid == shown else (214, 208, 220, 255)
        d.rectangle([x - 3, y - 3, x + w + 3, y + h + 3], outline=border, width=2)
        py, hidden = y - offset, 0
        for lead, indent, sym, sym_font, piece, font, colour in rows:
            if py >= y + h:
                hidden += 1
            elif py >= y and py + lead <= y + h:
                if piece == CHART_ROW:
                    if chart_h:
                        _draw_sei_chart(d, x + 8, py, text_w, chart_h - 8, history)
                else:
                    if sym:
                        d.text((x + 8, py), sym, font=sym_font, fill=colour + (255,))
                    if piece:
                        d.text((x + 8 + indent, py), piece, font=font, fill=colour + (255,))
            py += lead
        if content_h > h:
            hint = ("còn %d dòng dưới · S" % hidden) if hidden else "W: về đầu cột"
            _plate(d, x + 4, y + h - 22, hint, (255, 205, 0), size=12, max_x=x + w)
    d.text((margin, geo["footer_y"]), _run_state(state), font=_font(12), fill=_C.GRAY + (255,))
    return np.ascontiguousarray(np.array(canvas)[:, :, ::-1])


def render(frame, results, state: dict) -> "np.ndarray":
    """The phone's content, arranged for a wide screen: camera on the left, column on the right.

    This is deliberately NOT an overlay on the picture. The phone keeps its text in a scrolling
    column and the camera in a Box (MainActivity.kt:1051-2053); an earlier version of this preview
    wrote the numbers on top of the student's face and boxed the head, so the two screens could not
    be read side by side.

    Stacking them - preview on top, numbers below - was tried on this panel and does not fit. The
    column is ~26 lines tall whatever its width, because the phone's sentences are short and never
    wrap, so the text needs ~500 px of height on its own; 500 plus a 480 px preview is 1004 px on a
    728 px screen. Measured with the stacked layout, the preview was squeezed to 240x180 and the
    last parameters still fell off the bottom. Side by side, the preview runs at the 640x480 the
    lens actually delivers - no resampling, so no blur - and the whole column is on screen at once.
    """
    import numpy as np
    from PIL import Image, ImageDraw

    from focusedge.hud import CHART_ROW, hud_lines

    person = state.get("person")
    if person is None:
        person = _pick_person(results)
    people = state.get("people") or []
    if (not state.get("overlay") and len(people) >= 2
            and int(state.get("max_panels", 3)) >= 2):
        return _render_panels(frame, results, state, people, person)
    lines = list(hud_lines(person, len(results),
                           state.get("processing_ms", float("nan")),
                           state.get("fps", float("nan")),
                           identity=state.get("identity"),
                           activity=state.get("activity")))
    # Under the headline, not at the bottom: the question this line answers is "are these the
    # numbers of the child I am looking at", and it has to be answered before the SEI is read.
    lines.insert(1, Line(_whom_note(state), _TITLE_M, _C.PRIMARY, True))
    lines.append(Line(_run_state(state), _SMALL, _C.GRAY))
    history = list(state.get("sei_history", ()))
    view = state.get("boxes") if state.get("yolo") else None

    if state.get("overlay"):
        return _render_overlay(frame, person, lines, state, view)

    fh, fw = frame.shape[:2]
    work_w = _screen_work_size()[0]
    margin = 12
    # Never upscale: past 640 px wide every displayed pixel would be invented by the interpolator,
    # and invented pixels are the softness the operator sees.
    cam_w = min(fw, max(320, work_w // 2 - 2 * margin))
    cam_h = int(cam_w * fh / float(fw))
    text_x = margin + cam_w + margin
    win_w = min(work_w - 8, text_x + 620 + margin)
    text_w = max(240, win_w - text_x - margin)

    chart_h = 52 if len(history) >= 2 else 0

    # measure pass: wrap, then add up, so the window is only as tall as the text really is
    rows = _layout(lines, text_w, chart_h)
    text_h = sum(row[0] for row in rows)

    canvas = Image.new("RGB", (win_w, 2 * margin + max(cam_h, text_h)), _C.SURFACE)
    d = ImageDraw.Draw(canvas, "RGBA")
    _blit_camera(canvas, frame, margin, margin, cam_w, cam_h,
                 person.landmarks if person is not None and person.measurable else None,
                 person.face_bbox if person is not None else None, view)

    y = margin
    for lead, indent, sym, sym_font, piece, font, colour in rows:
        if piece == CHART_ROW:
            if chart_h:
                _draw_sei_chart(d, text_x, y, text_w, chart_h - 8, history)
        else:
            if sym:
                d.text((text_x, y), sym, font=sym_font, fill=colour + (255,))
            if piece:
                d.text((text_x + indent, y), piece, font=font, fill=colour + (255,))
        y += lead

    canvas = _scroll_view(canvas, state)
    return np.ascontiguousarray(np.array(canvas)[:, :, ::-1])


def _band_mapping(fw: int, fh: int, box_w: int, box_h: int):
    """The zoom-and-centre-crop PreviewView FILL_CENTER does, as three numbers.

    MainActivity.kt:2020 - scale = max(box_w/fw, box_h/fh), then the cut is centred. Kept apart
    from the drawing so both the dots and a YOLO box go through the same arithmetic: two copies of
    this formula are how a landmark and a box edge end up on different pixels.
    """
    scale = max(box_w / float(fw), box_h / float(fh))
    sw, sh = int(round(fw * scale)), int(round(fh * scale))
    return scale, (sw - box_w) // 2, (sh - box_h) // 2


def _blit_camera(canvas, frame, top: int, left: int, box_w: int, box_h: int, landmarks=None,
                 face_bbox=None, view=None):
    """Paste the camera into its band the way PreviewView FILL_CENTER does it.

    Same zoom-and-centre-crop, and the same dots only: the phone draws no face box, so neither
    does this. Doing the arithmetic in one place keeps a dot at landmark i exactly where the
    phone's Canvas puts dot i.

    face_bbox matters more than it looks. The phone runs FaceLandmarker on the WHOLE frame, so its
    landmark x/y are already frame-normalised. The server runs it on the CROP cut from a person
    box (classroom.py:260), so the same 0..1 numbers are relative to that crop. Plotting them
    straight onto the frame compresses a 478-point face into the top-left corner of the picture;
    they have to be re-based into frame pixels first. Without a bbox the dots are skipped rather
    than drawn at the wrong place.

    ``view`` is the YOLO overlay (see _draw_yolo): the raw layer-1 boxes for the same frame. It is
    drawn through the identical zoom-and-centre arithmetic so a box edge lands on the same pixel
    as the landmark next to it.
    """
    import cv2
    import numpy as np
    from PIL import Image

    fh, fw = frame.shape[:2]
    scale, cut_x, cut_y = _band_mapping(fw, fh, box_w, box_h)
    sw, sh = int(round(fw * scale)), int(round(fh * scale))
    # INTER_AREA when shrinking: it averages the pixels it drops, so a 640-wide lens at 445 px stays
    # crisp. INTER_LINEAR (the default) only samples, which is what makes a downscaled face mushy.
    interp = cv2.INTER_AREA if scale < 1.0 else cv2.INTER_LINEAR
    crop = np.ascontiguousarray(cv2.resize(frame, (sw, sh), interpolation=interp)[
        cut_y:cut_y + box_h, cut_x:cut_x + box_w])

    if landmarks is not None and landmarks.count:
        for i in range(landmarks.count):
            px = landmark_to_frame_px(landmarks, i, face_bbox, fw, fh)
            if px is None:
                break
            if i >= IRIS_FIRST:
                colour, radius = DOT_COLOURS["iris"]
            elif i in EYE_DRAW:
                colour, radius = DOT_COLOURS["eye"]
            else:
                colour, radius = DOT_COLOURS["mesh"]
            cx = int(px[0] * scale) - cut_x
            cy = int(px[1] * scale) - cut_y
            cv2.circle(crop, (cx, cy), radius, colour, -1)

    canvas.paste(Image.fromarray(crop[:, :, ::-1]), (left, top))
    # The boxes are in pixels of the frame that was detected, not of the frame being drawn: the
    # throttle between detections means the two can differ. A box from a differently sized frame
    # would sit across the picture rather than on the face, so it is dropped, not rescaled.
    if view is not None and view.persons and view.frame_size == (fw, fh):
        _draw_yolo(canvas, view, top, left, box_w, box_h, scale, cut_x, cut_y)


# Layer 1 in RGB for Pillow. hud.py's DOT_COLOURS are the BGR order cv2 wants, so they cannot be
# reused here - a green box drawn from those constants comes out cyan.
_YOLO_PERSON = (0, 190, 80)
_YOLO_FACE = (255, 205, 0)
_YOLO_OBJECT = (255, 130, 40)
_YOLO_PLATE = (12, 12, 14, 235)
# The names layer 1 reports, spelled the way the operator reads them. Imported rather than retyped:
# this table used to be a four-entry copy of detection's whitelist, so a box the detector named was
# plates on screen and an object nobody could say the name of.
_OBJ_VI = CONTEXT_VI


def _plate(d, x: int, y: int, text: str, colour: tuple, size: int = 13, bold: bool = True,
           right: bool = False, max_x: int = None) -> int:
    """Write a label on a dark plate, because the classroom wall behind it is a light colour.

    max_x pulls the plate back inside the band when the text would run off its right edge - a
    label that is half clipped says less than no label at all.
    """
    font = _font(size, bold)
    w = int(d.textlength(text, font=font))
    if right:
        x -= w + 8
    if max_x is not None and x + w + 8 > max_x:
        x = max(0, max_x - w - 8)
    d.rectangle([x, y, x + w + 8, y + size + 8], fill=_YOLO_PLATE)
    d.text((x + 4, y + 3), text, font=font, fill=colour + (255,))
    return size + 10


def _draw_yolo(canvas, view, top: int, left: int, box_w: int, box_h: int,
               scale: float, cut_x: int, cut_y: int) -> None:
    """Put YOLO's own output on the picture: every person box, its track id and confidence, the
    face window cut out of it, and any context object that belongs to nobody.

    Opt-in (Y). The default screen draws no boxes at all, because it has to read like the phone,
    and the phone's Canvas paints only dots - so this is the one view where the operator can check
    what the detector is doing rather than what the measurement concluded. Two facts worth keeping
    apart on screen: a person box is YOLO thinking something is shaped like a person, and the
    yellow window is what FaceLandmarker was actually given. A box with no window is a head the
    frame edge cut off, which is why the distance and eye numbers are missing for that track.

    Everything is drawn onto a layer the size of the band and composited once. Pillow has no clip
    rectangle, so drawing straight onto the canvas lets a box that leaves the frame run on into the
    numbers column - which a first version here did, and which makes the column unreadable.
    """
    from PIL import Image, ImageDraw

    layer = Image.new("RGBA", (box_w, box_h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer, "RGBA")
    regions = dict(view.face_regions)

    def xy(x, y):
        return int(round(x * scale)) - cut_x, int(round(y * scale)) - cut_y

    def row_y(y):
        """Keep a label plate inside the band, top and bottom."""
        return max(0, min(y, box_h - 24))

    for p in view.persons:
        x0, y0 = xy(p.bbox[0], p.bbox[1])
        x1, y1 = xy(p.bbox[2], p.bbox[3])
        d.rectangle([x0, y0, x1, y1], outline=_YOLO_PERSON + (255,), width=2)
        _plate(d, x0, row_y(y0 - 24), "#%d  conf %.2f" % (p.track_id, p.conf), _YOLO_PERSON,
               max_x=box_w)
        box = regions.get(p.track_id)
        if box is None:
            _plate(d, x0 + 3, row_y(y1 + 2),
                   "không có vùng mặt: đầu bị cắt ra khỏi khung", _YOLO_FACE, size=12, bold=False,
                   max_x=box_w)
            continue
        fx0, fy0 = xy(box[0], box[1])
        fx1, fy1 = xy(box[2], box[3])
        d.rectangle([fx0, fy0, fx1, fy1], outline=_YOLO_FACE + (255,), width=2)
        _plate(d, fx0, row_y(fy1 + 2),
               "vùng mặt %d×%d px" % (fx1 - fx0, fy1 - fy0), _YOLO_FACE, size=12, bold=False,
               max_x=box_w)
        for name, cf in sorted(p.nearby_objects.items(), key=lambda kv: -kv[1])[:2]:
            _plate(d, x0 + 3, row_y(y0 + 26),
                   "trong khung: %s %.2f" % (_OBJ_VI.get(name, name), cf),
                   _YOLO_OBJECT, size=12, bold=False, max_x=box_w)

    for name, box, cf in view.objects:
        ox0, oy0 = xy(box[0], box[1])
        ox1, oy1 = xy(box[2], box[3])
        d.rectangle([ox0, oy0, ox1, oy1], outline=_YOLO_OBJECT + (255,), width=2)
        _plate(d, ox0, row_y(oy0 - 22),
               "%s %.2f · không thuộc ai" % (_OBJ_VI.get(name, name), cf),
               _YOLO_OBJECT, size=12, bold=False, max_x=box_w)

    _plate(d, box_w - 3, box_h - 24,
           "xanh = hộp YOLO (%d) · vàng = vùng mặt · cam = đồ vật (%d)"
           % (len(view.persons), len(view.objects)), (235, 235, 235), size=12, bold=False,
           right=True)
    canvas.paste(layer, (left, top), layer)


def _draw_sei_chart(d, x, y, w, h, history):
    """The 44 dp sparkline under the SEI header (MainActivity.kt:1077-1109)."""
    from focusedge.hud import sei_colour

    d.line([x, y + h * (1 - 60 / 100.0), x + w, y + h * (1 - 60 / 100.0)],
           fill=(158, 158, 158), width=1)
    step_x = w / float(max(1, len(history) - 1))
    colour = sei_colour(history[-1]) + (255,)
    points = [(x + i * step_x, y + h * (1 - value / 100.0)) for i, value in enumerate(history)]
    for a, b in zip(points, points[1:]):
        d.line([a, b], fill=colour, width=3)


def _run_state(state: dict) -> str:
    """The one line the phone has no equivalent for: how this machine is actually running.

    Two counts, because they are not the same fact. YOLO drew ``tracks`` person-shaped boxes and the
    pipeline measured ``measurable`` of them; printing only the big number would let a wall
    photograph be counted as a student on a screen a teacher reads.

    The overlay modes state themselves rather than only their key, so a screen showing boxes is
    never mistaken for the plain measurement view: with the boxes on, the line reports what layer 1
    found; with them off, it says which key turns them on.
    """
    keys = []
    boxes = state.get("boxes")
    if state.get("yolo"):
        keys.append("hộp YOLO: %d người · %d đồ vật" % (
            len(boxes.persons) if boxes else 0, len(boxes.objects) if boxes else 0))
    else:
        keys.append("bấm Y: hộp YOLO")
    if state.get("overlay"):
        keys.append("số đè lên ảnh, bấm O để về cột")
    tail = " · " + " · ".join(keys)
    return ("%s · %d người đo được · %d khung người · %.1f lượt đo/giây · %s · %s%s · "
            "ESC để tắt" % (
                state["class_label"], state["measurable"], state["tracks"], state["det_rate"],
                state["store_note"],
                state["camera_note"], tail))


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
    io_cfg = cfg.get("input", {})
    cap = open_source(io_cfg.get("source", 0), io_cfg)
    process_width = int(io_cfg.get("process_width", 1280))
    detect_fps = float(io_cfg.get("detect_fps", 12))
    # measure_fps paces pipeline.step(); with detection.yolo_every > 1 the intermediate
    # steps only run FaceLandmarker on cached boxes, so the eye cadence can exceed the
    # YOLO budget. 0/absent = old behavior (one step per detection tick).
    measure_fps = float(io_cfg.get("measure_fps", 0)) or detect_fps
    tick_ms = 1000.0 / max(1.0, measure_fps)

    session_reset_ms = int(args.session_minutes * 60_000) if args.session_minutes > 0 else 0
    started_wall = int(time.time() * 1000)

    frame_count = 0
    last_tick = 0.0
    # The window is rebuilt on every delivered frame from the numbers of the last measured one;
    # measuring at detect_fps but painting at camera rate is exactly how the phone HUD behaves.
    last_results = []
    det_count, det_window_ms, det_rate = 0, 0.0, 0.0

    # The values behind the phone's recognition-time line and its SEI sparkline. The curve is one
    # sample per second and 120 deep (MainActivity.kt HISTORY_SIZE) and it belongs to ONE student,
    # so there is a curve per track rather than one shared curve: with two children in frame the
    # shared version was cleared the moment the column moved, which made the second child look like
    # the first one with a blank history.
    processing_ms = float("nan")
    fps = float("nan")
    histories: dict = {}
    sample_ms: dict = {}
    last_step_ms = 0.0
    state = {"class_label": getattr(pipeline, "class_label", pipeline.class_id),
             "measurable": 0, "tracks": 0, "det_rate": 0.0,
             "store_note": _store_note(pipeline._writer),
             "camera_note": _camera_note(cap, io_cfg.get("source", 0)),
             "processing_ms": processing_ms, "fps": fps,
             "sei_history": deque(maxlen=120), "histories": histories, "sample_ms": sample_ms,
             # Who the column is showing right now. "shown" is the machine's sticky choice, "focus"
             # is the operator's, and a key press only ever changes the second one.
             "people": [], "shown": None, "focus": None, "shown_mode": "",
             "identity": None, "person": None,
             # The m key has to reach the pipeline: naming a head by hand is a decision about the
             # measurement, not about the view, so it belongs there rather than in a screen variable.
             "pipeline": pipeline,
             "scroll": 0, "overlay": False,
             # One reading window and one cached type factor per column, keyed by track id.
             "panel_scroll": {}, "panel_factor": {},
             "max_panels": int((cfg.get("display") or {}).get("max_panels", 3)),
             # Boxes on by default: the request was to see YOLO working on the main screen, not in a
             # separate debug window. Y turns them off, and that is the view that matches the phone.
             "yolo": True, "boxes": None}

    if args.show:
        # WINDOW_AUTOSIZE, not WINDOW_NORMAL. The canvas is already built to the screen, so there is
        # nothing to gain from resizing - and a maximised cv2 window resamples the whole canvas,
        # turning a 640 px lens picture and crisp 13 px text into the soft 2x-stretched image an
        # operator sees when they double-click the title bar.
        cv2.namedWindow("FocusChangeAI", cv2.WINDOW_AUTOSIZE)
        # ASCII only, like the .bat launcher: the title bar is drawn by Windows through a narrow
        # code page, so any accented word arrives as mojibake. Everything inside the window goes
        # through Pillow and keeps its accents fine - it is only this OS-drawn string that cannot.
        cv2.setWindowTitle("FocusChangeAI",
                           "FocusChangeAI - one column per person, Y boxes, O overlay, W/S scroll, "
                           "N or 1-9 head, A auto, M name, ESC quit")

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
                    cv2.imshow("FocusChangeAI", render(frame, last_results, state))
                    if _handle_key(cv2.waitKey(1) & 0xFF, state):
                        break
                continue
            last_tick = now

            step_started = now
            results = pipeline.step(frame)
            last_results = results
            state["boxes"] = pipeline.last_boxes
            state["activity"] = pipeline.last_activity
            step_ms = time.monotonic() * 1000 - step_started
            if last_step_ms:
                dt = step_started - last_step_ms
                if 0.0 < dt < 5000.0:
                    fps = _ema(fps, 1000.0 / dt)
            last_step_ms = step_started
            processing_ms = _ema(processing_ms, step_ms)
            state["processing_ms"], state["fps"] = processing_ms, fps

            det_count += 1
            det_window_ms += tick_ms
            if det_window_ms >= 1000.0:
                det_rate = 1000.0 * det_count / det_window_ms
                det_count, det_window_ms = 0, 0.0
                state["det_rate"] = det_rate

            if session_reset_ms and int(time.time() * 1000) - started_wall >= session_reset_ms:
                pipeline.shutdown()
                started_wall = int(time.time() * 1000)

            if args.show:
                people = pipeline.people()
                shown, mode = _shown_track(people, state)
                state["people"], state["shown"], state["shown_mode"] = people, shown, mode
                state["identity"] = next((p for p in people if p.track_id == shown), None)
                person = next((r for r in results if r.track_id == shown), None)
                if person is None:
                    # The scheduler measures a few faces per tick, so the chosen child is not
                    # always in this frame's results. Their own last numbers, never somebody
                    # else's fresh ones.
                    person = pipeline.person_result(shown)
                state["person"] = person

                # Every measured person keeps their own curve, sampled once a second, whether or
                # not the column is on them - so pressing N shows a real history at once instead of
                # starting to collect one from now.
                for r in results:
                    if (r.calibrated and r.sei is not None
                            and step_started - sample_ms.get(r.track_id, 0.0) >= 1000.0):
                        sample_ms[r.track_id] = step_started
                        _history_for(histories, r.track_id).append(r.sei)
                alive = {p.track_id for p in people}
                for dead in [t for t in histories if t not in alive]:
                    # A retired track is a finished session; its curve stays in the database and
                    # has no business holding memory for the rest of the school year.
                    del histories[dead]
                    sample_ms.pop(dead, None)
                    state["panel_scroll"].pop(dead, None)
                    for k in [k for k in state["panel_factor"] if k[0] == dead]:
                        del state["panel_factor"][k]
                state["sei_history"] = (_history_for(histories, shown) if shown is not None
                                         else deque(maxlen=120))
                state["measurable"] = pipeline.measurable_tracks
                state["tracks"] = pipeline.active_tracks
                cv2.imshow("FocusChangeAI", render(frame, results, state))
                if _handle_key(cv2.waitKey(1) & 0xFF, state):
                    break

            if args.max_frames and frame_count >= args.max_frames:
                break
    except KeyboardInterrupt:
        print("\nstopping...")
    finally:
        # Asked before shutdown(), because shutdown() closes the database and LocalStore.enabled is
        # "_conn is not None" - read afterwards it is false for every run on earth, and a healthy run
        # that stored six sessions would end by telling the operator "database NOT open".
        db_was_open = pipeline._writer.enabled
        pipeline.shutdown()
        cap.release()
        if args.show:
            cv2.destroyAllWindows()

    store = pipeline._writer
    print("frames=%d tracks_active=%d stored=%d failed=%d class=%s%s" % (
        frame_count, pipeline.active_tracks, store.sent, store.failed,
        pipeline.class_id, "" if db_was_open else " (database NOT open - nothing stored)"))
    # The run used to end by pointing at a Firestore console; with nothing leaving the box the
    # evidence has to appear here, or an operator cannot tell "nothing eligible" from "nothing
    # written".
    print(store.report())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
