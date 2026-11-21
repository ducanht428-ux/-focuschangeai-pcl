"""Layer 4 input - MediaPipe FaceLandmarker (Tasks Python API).

Uses the SAME ``face_landmarker.task`` asset the Android app ships, so the 478
landmarks and the 52 blendshapes line up 1:1 with the ported EyeEngine/MainActivity
code. Runs in IMAGE mode on cropped face ROIs chosen by the scheduler, which is how
the pipeline affords K faces instead of the whole class at once.
"""
from __future__ import annotations

from typing import List, Optional

from .constants import LM_COUNT
from .landmarks import Landmarks

# Blendshape names EyeEngine consumes, in the order it expects:
#   [eyeBlinkLeft, eyeBlinkRight, eyeSquintLeft, eyeSquintRight, jawOpen]
_BS_ORDER = ["eyeBlinkLeft", "eyeBlinkRight", "eyeSquintLeft", "eyeSquintRight", "jawOpen"]


class FaceAnalyzer:
    def __init__(self, model_asset_path: str, num_faces: int = 1,
                 blendshapes: bool = True, iris: bool = True) -> None:
        import mediapipe as mp  # lazy
        from mediapipe.tasks.python import BaseOptions
        from mediapipe.tasks.python import vision

        self._mp = mp
        running_mode = vision.RunningMode.IMAGE
        opts = vision.FaceLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=model_asset_path),
            running_mode=running_mode,
            num_faces=num_faces,
            output_face_blendshapes=blendshapes,
            output_facial_transformation_matrixes=False,
        )
        self._landmarker = vision.FaceLandmarker.create_from_options(opts)
        self._want_iris = iris

    def analyze(self, rgb_array) -> "tuple[Optional[Landmarks], Optional[List[float]]]":
        """Run FaceLandmarker on one RGB face crop.

        Returns (Landmarks, blendshape5) or (None, None) when no face is found.
        ``rgb_array`` is an HxWx3 uint8 array (RGB order).
        """
        import numpy as np  # lazy
        mp_image = self._mp.Image(
            image_format=self._mp.ImageFormat.SRGB,
            data=np.ascontiguousarray(rgb_array, dtype=np.uint8),
        )
        res = self._landmarker.detect(mp_image)
        if not res.face_landmarks:
            return None, None
        lms = res.face_landmarks[0]
        flat: List[float] = []
        for p in lms:
            flat.append(p.x)
            flat.append(p.y)
            flat.append(getattr(p, "z", 0.0))
        count = len(lms)
        lm = Landmarks(flat, count)

        bs: Optional[List[float]] = None
        if res.face_blendshapes:
            bs = self._extract_blendshapes(res.face_blendshapes[0])
        return lm, bs

    @staticmethod
    def _extract_blendshapes(categories) -> Optional[List[float]]:
        found = {}
        for c in categories:
            found[c.category_name] = c.score
        blink_l = found.get("eyeBlinkLeft", -1.0)
        blink_r = found.get("eyeBlinkRight", -1.0)
        if blink_l < 0 or blink_r < 0:
            return None
        return [
            blink_l,
            blink_r,
            found.get("eyeSquintLeft", 0.0),
            found.get("eyeSquintRight", 0.0),
            found.get("jawOpen", 0.0),
        ]
