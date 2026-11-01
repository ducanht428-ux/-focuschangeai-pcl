"""Layer 2 - identity (ArcFace embeddings + roster matching).

Turns a transient ByteTrack id into a durable studentId from the class roster, and
keeps re-verifying so an ID switch (two students swapping seats, an occlusion) is
caught and the track demoted back to a guest rather than silently writing person A's
measurements under person B's name.
"""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Deque, List, Optional

if TYPE_CHECKING:
    # roster imports cosine from here, so keep this edge type-only to avoid a cycle
    from .roster import Roster


def cosine(a, b) -> float:
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))


class FaceEmbedder:
    """Wraps insightface's ArcFace recognition model (lazy import)."""

    def __init__(self, pack: str = "buffalo_l", providers: Optional[List[str]] = None) -> None:
        import insightface  # lazy
        from insightface.app import FaceAnalysis
        self._app = FaceAnalysis(name=pack, providers=providers or ["CUDAExecutionProvider",
                                                                     "CPUExecutionProvider"])
        # detection=False: bodies/faces come from YOLO + MediaPipe; we only need the
        # recognition embedding, but insightface still needs a aligned face box.
        self._app.prepare(ctx_id=0, det_size=(320, 320))

    def embed(self, face_crop_bgr, bbox) -> Optional[List[float]]:
        """Return a 512-D embedding for the face in ``face_crop_bgr`` or None."""
        import numpy as np  # lazy
        faces = self._app.get(np.asarray(face_crop_bgr))
        if not faces:
            return None
        # Take the largest detected face in the crop.
        best = max(faces, key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]))
        emb = best.embedding
        return [float(v) for v in emb]


@dataclass
class IdentityState:
    """Per-track identity memory."""

    bound_student_id: Optional[str] = None
    bound_name: str = ""
    confidence: str = "unbound"          # unbound | medium | high | guest
    embed_window: Deque = field(default_factory=lambda: deque(maxlen=40))
    mismatch_since_ms: int = 0

    def avg_embedding(self) -> Optional[List[float]]:
        if not self.embed_window:
            return None
        n = len(self.embed_window[0])
        acc = [0.0] * n
        for e in self.embed_window:
            for i in range(n):
                acc[i] += e[i]
        return [v / len(self.embed_window) for v in acc]


class IdentityResolver:
    def __init__(self, embedder: FaceEmbedder, roster: Roster,
                 bind_hi: float = 0.45, bind_lo: float = 0.30,
                 embed_window: int = 40, unbind_hold_ms: int = 10000) -> None:
        self._embedder = embedder
        self._roster = roster
        self._bind_hi = bind_hi
        self._bind_lo = bind_lo
        self._unbind_hold_ms = unbind_hold_ms
        self._window = embed_window

    def new_state(self) -> IdentityState:
        return IdentityState(embed_window=deque(maxlen=self._window))

    def observe(self, state: IdentityState, face_crop, bbox, now_ms: int) -> IdentityState:
        """Feed one face crop for a track; update its binding in place."""
        emb = self._embedder.embed(face_crop, bbox)
        if emb is None:
            return state
        state.embed_window.append(emb)

        # Only judge identity once we have a few clean samples.
        if len(state.embed_window) < 5:
            return state

        avg = state.avg_embedding()
        match_id, match_name, score = self._roster.best_match(avg)

        if state.bound_student_id is None:
            # Not bound yet: bind only above the high gate.
            if score >= self._bind_hi and match_id is not None:
                state.bound_student_id = match_id
                state.bound_name = match_name
                state.confidence = "high"
                state.mismatch_since_ms = 0
            elif score >= self._bind_lo and match_id is not None:
                state.confidence = "medium"
            else:
                state.confidence = "guest"
        else:
            # Already bound: re-verify against the bound student's own template.
            own = self._roster.template(state.bound_student_id)
            own_score = cosine(avg, own) if own else score
            if own_score >= self._bind_lo:
                state.confidence = "high" if own_score >= self._bind_hi else "medium"
                state.mismatch_since_ms = 0
            else:
                if state.mismatch_since_ms == 0:
                    state.mismatch_since_ms = now_ms
                if now_ms - state.mismatch_since_ms >= self._unbind_hold_ms:
                    # Persistent mismatch -> this is no longer the bound person.
                    self._unbind(state)
        return state

    def _unbind(self, state: IdentityState) -> None:
        state.bound_student_id = None
        state.bound_name = ""
        state.confidence = "guest"
        state.mismatch_since_ms = 0
        state.embed_window.clear()
