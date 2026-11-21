"""Tests for the one-column-per-student preview geometry (the 2026-11-14 request).

Run from the project folder:

    python tests/test_panels.py

Pure arithmetic - no camera, no numpy, no Pillow, the same rule every focusedge module follows.
What these pin: every child in frame gets a column of their own up to the readable cap, the chosen
head is never the dropped one, the columns tile the screen with equal widths (one child's type must
not be smaller than another's), the camera is never upscaled, and a column that cannot fit scrolls
inside itself instead of silently losing lines. The numbers 1366x728 and 640x480 are this project's
measured panel work area and the C270's delivered mode, not guesses.
"""
import os
import sys
from types import SimpleNamespace as NS

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.panels import (  # noqa: E402
    CAM_MAX_W, CAM_MIN_W, FONT_FACTORS, MAX_PANELS, clamp_scroll, fit_factor, panel_layout,
    panel_people)

WORK_W, WORK_H = 1366, 728
FRAME_W, FRAME_H = 640, 480


def _people(n):
    return [NS(track_id=i + 1) for i in range(n)]


def test_one_person_keeps_the_legacy_single_column():
    taken, extra = panel_people(_people(1), None)
    assert len(taken) == 1 and extra == 0, "one child must still get the phone-identical view"


def test_every_person_in_frame_gets_a_column_up_to_the_cap():
    taken, extra = panel_people(_people(3), None)
    assert [p.track_id for p in taken] == [1, 2, 3] and extra == 0
    taken, extra = panel_people(_people(5), None)
    assert len(taken) == MAX_PANELS and extra == 2, (
        "the cap exists to keep type readable; the rest are named under the camera")


def test_the_chosen_head_is_never_the_dropped_one():
    taken, extra = panel_people(_people(5), 5)
    assert 5 in [p.track_id for p in taken] and extra == 2, (
        "a key press that selects an invisible column reads as a broken key")


def test_columns_tile_the_screen_without_gaps_or_overlap():
    for n in (2, 3):
        geo = panel_layout(WORK_W, WORK_H, n, FRAME_W, FRAME_H)
        rects = geo["panels"]
        assert len(rects) == n
        assert len({w for _, _, w, _ in rects}) == 1, "equal widths, equal type size"
        x = geo["cam"][0] + geo["cam"][2] + geo["margin"]
        for px, py, w, h in rects:
            assert (px, py) == (x, geo["margin"]), "columns start where the camera band ends"
            assert h == geo["panel_h"] and h > 240
            x += w + geo["margin"]
        assert x - geo["margin"] <= geo["win_w"], "no column may run off the screen"
        assert geo["footer_y"] + 16 <= WORK_H, "the machine footer must stay on screen"


def test_the_camera_is_never_upscaled_and_never_starved():
    for n in (2, 3, 4):
        cam = panel_layout(WORK_W, WORK_H, n, FRAME_W, FRAME_H)["cam"]
        assert CAM_MIN_W <= cam[2] <= min(CAM_MAX_W, FRAME_W)
        assert cam[3] == int(cam[2] * FRAME_H / float(FRAME_W)), "keep the lens's own aspect"


def test_a_narrow_screen_still_gives_every_column_room():
    geo = panel_layout(1024, 700, 3, FRAME_W, FRAME_H)
    assert geo["panel_w"] >= 200, "the camera floor wins, but columns stay usable"
    assert geo["panels"][-1][0] + geo["panel_w"] <= geo["win_w"]


def test_the_type_ladder_only_shrinks_and_stops_readable():
    assert list(FONT_FACTORS) == sorted(FONT_FACTORS, reverse=True)
    assert FONT_FACTORS[-1] >= 0.65, "below this the type is not readable at arm's length"
    assert fit_factor([900, 800, 700, 600, 500], 680) == 0.72
    assert fit_factor([900, 800, 700, 690, 680], 680) == 0.65
    assert fit_factor([900] * 5, 680) == FONT_FACTORS[-1], (
        "a column that scrolls is still a column; refusing to draw is not")


def test_a_column_scroll_stays_inside_its_own_text():
    assert clamp_scroll(-5, 900, 680) == 0
    assert clamp_scroll(5000, 900, 680) == 220
    assert clamp_scroll(50, 600, 680) == 0, "content shorter than the window: nothing to scroll"


def test_scroll_keys_move_only_the_focused_column():
    import main
    state = {"people": _people(2), "shown": 2, "panel_scroll": {1: 40, 2: 0}, "scroll": 0}
    main._scroll_by(state, 160)
    assert state["panel_scroll"] == {1: 40, 2: 160} and state["scroll"] == 0, (
        "scrolling one child must not move the neighbour's text")
    main._scroll_by(state, None)
    assert state["panel_scroll"] == {1: 40, 2: 0}
    single = {"people": _people(1), "shown": 1, "scroll": 10}
    main._scroll_by(single, 160)
    assert single["scroll"] == 170, "one person keeps the legacy whole-canvas scroll"


def main() -> int:
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