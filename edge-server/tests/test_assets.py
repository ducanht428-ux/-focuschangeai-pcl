"""Tests for asset paths: the camera leg must start from any folder, not only edge-server/.

Run from the edge-server folder:

    python tests/test_assets.py

Why these exist: config.yaml spells the eye model and the roster ("roster.json") relative to this
folder. The double-click launcher cds here, but "python edge-server/main.py" from the repo root does
not, and the same config then points at files that are not there - MediaPipe fails deep inside its own
loader with a path nobody typed. Each case below is one of those layouts.

The eye-model spelling is READ FROM config.yaml, not hardcoded here. It used to be hardcoded as
"../app/src/main/assets/face_landmarker.task", which only exists inside the full FocusChangeAIApp
tree; on a standalone clone of this leg the test failed for a path the config no longer used. The
config now names the copy tracked at the repo root, and this test checks whatever the config says.
"""
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.classroom import resolve_asset  # noqa: E402

EDGE_SERVER = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _configured_eye_model() -> str:
    """The face_landmarker_task value from config.yaml, without importing PyYAML.

    These tests run on a machine with no third-party packages installed (house style: lazy imports),
    so one key is read by scanning lines instead of parsing the whole document. Comments and quotes
    are stripped the same way yaml would for a plain scalar.
    """
    path = os.path.join(EDGE_SERVER, "config.yaml")
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("face_landmarker_task:"):
                value = line.split(":", 1)[1].split("#", 1)[0].strip()
                return value.strip("\"'")
    raise AssertionError("config.yaml has no face_landmarker_task key")


EYE_MODEL_CONFIG_VALUE = _configured_eye_model()


def test_the_configured_eye_model_is_found_even_from_another_folder():
    here = os.getcwd()
    elsewhere = tempfile.mkdtemp(prefix="fcai-cwd-")
    try:
        os.chdir(elsewhere)
        resolved = resolve_asset(EYE_MODEL_CONFIG_VALUE)
        assert os.path.exists(resolved), (
            "the eye model was not found from CWD=%r; resolved to %r" % (elsewhere, resolved))
        assert os.path.samefile(resolved, os.path.join(EDGE_SERVER, EYE_MODEL_CONFIG_VALUE)), (
            "resolved to %r, which is not the file the repo holds" % (resolved,))
    finally:
        os.chdir(here)
        shutil.rmtree(elsewhere, ignore_errors=True)


def test_a_model_dropped_inside_edge_server_is_found_too():
    """The hand-over ZIP layout: the .task sits next to main.py, config still points into app/.

    Run from a folder where the configured "../app/..." spelling resolves to nothing, so the only
    candidate left is the copy inside the package folder. That is exactly the machine-B layout where
    somebody put the model next to main.py instead of carrying the whole repo tree.
    """
    root = tempfile.mkdtemp(prefix="fcai-assets-")
    elsewhere = tempfile.mkdtemp(prefix="fcai-cwd-")
    here = os.getcwd()
    try:
        os.chdir(elsewhere)
        name = os.path.basename(EYE_MODEL_CONFIG_VALUE)
        with open(os.path.join(root, name), "wb") as f:
            f.write(b"not a real model")
        assert not os.path.exists(EYE_MODEL_CONFIG_VALUE), (
            "the fixture is useless if the configured path happens to exist from this CWD")
        resolved = resolve_asset(EYE_MODEL_CONFIG_VALUE, root=root)
        assert resolved == os.path.join(root, name), (
            "expected the copy inside the package folder, got %r" % (resolved,))
    finally:
        os.chdir(here)
        shutil.rmtree(root, ignore_errors=True)
        shutil.rmtree(elsewhere, ignore_errors=True)


def test_an_unfound_weight_name_is_left_bare_so_it_can_still_download():
    """A fresh clone has no weights at all. Returning the bare name is what lets ultralytics fetch
    it; returning a fabricated edge-server/... path would turn a working download into a stall."""
    assert resolve_asset("yolo11x-does-not-exist.pt") == "yolo11x-does-not-exist.pt", (
        "a missing weight must be passed through unchanged, not pointed at a folder it is not in")


def test_a_path_that_already_exists_is_returned_unchanged():
    real = os.path.join(EDGE_SERVER, "config.yaml")
    assert resolve_asset(real) == real, "an absolute path must be used exactly as given"


def test_a_missing_asset_is_returned_as_asked_for_not_invented():
    """No candidate exists: the caller must still see the name the operator wrote, and nothing raises."""
    wanted = "this/file/does/not/exist.task"
    assert resolve_asset(wanted, root=EDGE_SERVER) == wanted, (
        "silently pointing at a different file would hide the real mistake")


def test_the_run_names_the_missing_model_instead_of_staying_silent():
    """classroom.from_config must print the eye-model path it settled on when nothing exists."""
    src = open(os.path.join(EDGE_SERVER, "focusedge", "classroom.py"), encoding="utf-8").read()
    assert "eye model NOT FOUND" in src, (
        "a run without the eye model measures nothing, and the operator was not told")
    assert "model_asset_path=eye_model" in src, (
        "FaceAnalyzer must be handed the RESOLVED path, not the raw config string")
    assert 'roster_path = resolve_asset(cfg.get("roster"' in src, (
        "the roster is relative to edge-server/ for the same reason the model is")


def test_the_yolo_weights_go_through_the_same_resolver():
    """A bare weight name is the worst case, because ultralytics DOWNLOADS what it cannot find.

    Observed while writing these tests: run from the repo root, the detector silently fetched a
    second 5.4 MB yolo11n.pt into that root instead of using the one in edge-server/. On a machine
    without internet the same line is not a download but a stall, so the name must be resolved first.
    """
    src = open(os.path.join(EDGE_SERVER, "focusedge", "classroom.py"), encoding="utf-8").read()
    assert "model=resolve_asset(det_cfg.get(\"model\"" in src, (
        "Detector was handed the raw weight name again, which lets ultralytics download a duplicate")


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


def load_tests(loader, tests, pattern):
    import unittest
    suite = unittest.TestSuite()
    for k, v in sorted(globals().items()):
        if k.startswith("test_") and callable(v):
            suite.addTest(unittest.FunctionTestCase(v))
    return suite


if __name__ == "__main__":
    sys.exit(main())
