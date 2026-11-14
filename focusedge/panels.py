"""Geometry of the one-column-per-student preview (the 2026-11-14 request).

The phone has one holder, so its HUD is one column. The classroom camera has two or three children
in the same frame, and the request was explicit: every person in shot gets their OWN column with the
phone's full line set, not a shared column that swaps whose numbers it shows. This module owns the
arithmetic of that layout - who gets a column, where the columns sit, how small the type may get -
and nothing else. It imports no third-party library, so the layout can be tested on a machine with
no camera, no numpy and no Pillow, the same rule every other focusedge module follows.

Measured constraints it answers (this project's laptop, 1366x768 panel, 728 px of work area):
  * the lens delivers 640x480 and is never upscaled, so the camera band competes with the columns
    for width: three columns of readable type leave the camera ~340-460 px, not 640;
  * the phone's ~38 lines wrap once a column is narrower than ~620 px, so a column is a scrolling
    window of its own text, exactly like the single column on a short screen (main._scroll_view);
  * type below 9 px is not readable at arm's length, so the factor ladder stops at 0.65 and whatever
    still does not fit is reached with W/S, announced on the column itself.
"""
from __future__ import annotations

# Three columns is what 1366 px holds at >=9 px type with the camera still recognisable. A fourth
# column would be ~230 px wide: every sentence wraps three times and the factor ladder bottoms out,
# so the extra person is named on a plate under the camera and reached with 1-9 instead.
MAX_PANELS = 3
MIN_PANEL_W = 280
CAM_MIN_W = 300
CAM_MAX_W = 460
FOOTER_H = 22

# The type ladder, widest first. fit_factor walks it and keeps the first size whose wrapped text
# fits the column whole; scrolling is the fallback, not the default, because a column that hides
# lines behind a key is the complaint this layout was built to answer.
FONT_FACTORS = (1.0, 0.9, 0.8, 0.72, 0.65)


def panel_people(people, focus, max_panels: int = MAX_PANELS):
    """Which people get a column, and how many are left without one.

    Order is the order ``people()`` already guarantees (lowest track id first): a menu that
    reshuffles itself every frame would make "press 2" mean a different child each time. The cap
    keeps the type readable; the operator's chosen head is never the one dropped - if the focus is
    outside the capped slice it takes the last slot, because a key press that selects an invisible
    column reads as a broken key.
    """
    cap = max(1, int(max_panels))
    taken = list(people[:cap])
    ids = [p.track_id for p in taken]
    if focus is not None and focus not in ids:
        hit = next((p for p in people if p.track_id == focus), None)
        if hit is not None:
            if len(taken) >= cap:
                taken[-1] = hit
            else:
                taken.append(hit)
    return taken, max(0, len(people) - len(taken))


def panel_layout(work_w: int, work_h: int, n: int, frame_w: int, frame_h: int, margin: int = 12):
    """Camera band on the left, n equal columns on the right, one machine footer under them.

    Only called with n >= 2: one person keeps the legacy single-column geometry in main.render,
    byte for byte, because that view is the one compared against the phone. The camera shrinks
    toward CAM_MIN_W as columns are added but never below it and never past the lens's own width;
    the columns split whatever width is left equally, so no child's numbers are smaller than another's.
    """
    n = max(2, int(n))
    win_w = max(640, work_w - 8)
    room = win_w - n * MIN_PANEL_W - (n + 1) * margin
    cam_w = min(frame_w, max(CAM_MIN_W, min(CAM_MAX_W, room)))
    cam_h = int(cam_w * frame_h / float(frame_w))
    panel_w = (win_w - cam_w - (n + 1) * margin) // n
    panel_h = max(240, work_h - FOOTER_H - 2 * margin)
    panels = []
    x = margin + cam_w + margin
    for _ in range(n):
        panels.append((x, margin, panel_w, panel_h))
        x += panel_w + margin
    return {"win_w": win_w, "cam": (margin, margin, cam_w, cam_h), "panels": panels,
            "panel_w": panel_w, "panel_h": panel_h, "footer_y": margin + panel_h + 4,
            "margin": margin}


def fit_factor(heights, panel_h: int) -> float:
    """The largest type factor whose wrapped column height fits, or the smallest factor if none does.

    ``heights`` is the measured content height per entry of FONT_FACTORS, in that order. Returning
    the last factor rather than raising is deliberate: a column that scrolls is still a column, and
    a preview that refuses to draw because three children sat down is not.
    """
    for factor, height in zip(FONT_FACTORS, heights):
        if height <= panel_h:
            return factor
    return FONT_FACTORS[-1]


def clamp_scroll(offset, content_h: int, view_h: int) -> int:
    """Keep a column's reading window inside its own text, the single-column rule per column."""
    lowest = max(0, content_h - view_h)
    return max(0, min(int(offset), lowest))