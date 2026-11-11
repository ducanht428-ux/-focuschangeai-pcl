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
    # What the matcher saw, not just what it decided. Without these the only answer a guest gets is
    # the word "guest", and an operator cannot tell a dim room from a wrong roster from a broken
    # camera. score is the number the config's gates are compared against.
    score: float = 0.0                   # cosine of the averaged face against its own gate
    margin: float = 0.0                  # how far that template beats the next-closest student
    samples: int = 0                     # embeddings averaged behind score
    runner_up: str = ""                  # second-closest student_id, "" when there is only one

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
    # The gates below decide when a face becomes a name. They were 0.45 / 0.30, and 0.30 was enough
    # to bind: in a room of same-age students that is the band where the child at the next desk
    # matches, and the first student to walk in frame would then own the other one's rows.
    # bind_hi is now the only gate that can create a binding, min_margin is a second gate the raw
    # score cannot pass alone, and bind_lo only decides whether an existing binding is kept.
    def __init__(self, embedder: FaceEmbedder, roster: Roster,
                 bind_hi: float = 0.52, bind_lo: float = 0.38, min_margin: float = 0.05,
                 min_samples: int = 10,
                 embed_window: int = 40, unbind_hold_ms: int = 10000) -> None:
        self._embedder = embedder
        self._roster = roster
        self._bind_hi = bind_hi
        self._bind_lo = bind_lo
        self._min_margin = min_margin
        self._min_samples = max(1, int(min_samples))
        self._unbind_hold_ms = unbind_hold_ms
        self._window = embed_window

    def new_state(self) -> IdentityState:
        return IdentityState(embed_window=deque(maxlen=self._window))

    # Read out by the preview, so the screen can print the bar a face was measured against instead
    # of only the verdict. The numbers live in one place: config.yaml decides them, this reads them
    # back, and nothing re-types them into a sentence.
    @property
    def bind_hi(self) -> float:
        return self._bind_hi

    @property
    def min_margin(self) -> float:
        return self._min_margin

    @property
    def min_samples(self) -> int:
        return self._min_samples

    def observe(self, state: IdentityState, face_crop, bbox, now_ms: int) -> IdentityState:
        """Feed one face crop for a track; update its binding in place."""
        emb = self._embedder.embed(face_crop, bbox)
        if emb is None:
            return state
        state.embed_window.append(emb)
        state.samples = len(state.embed_window)

        # Only judge identity once a few clean samples exist. Ten, not five: the averaged embedding
        # of five frames of a turning head is a blur that sits between two children's templates,
        # and the blur was exactly what passed the old 0.45 gate.
        if state.samples < self._min_samples:
            return state

        avg = state.avg_embedding()
        # Three deep: a bound student can be third, and the number the operator is shown is the gap
        # to the best OTHER student, which needs the rest of the list to exist.
        ranked = self._roster.rank(avg, 3)
        if not ranked:
            state.confidence = "guest"
            state.score, state.margin, state.runner_up = 0.0, 0.0, ""
            return state

        if state.bound_student_id is None:
            match_id, match_name, score = ranked[0]
            margin = score - ranked[1][2] if len(ranked) > 1 else 1.0
            state.score, state.margin = score, margin
            state.runner_up = ranked[1][0] if len(ranked) > 1 else ""
            if score >= self._bind_hi and margin >= self._min_margin and match_id is not None:
                state.bound_student_id = match_id
                state.bound_name = match_name
                state.confidence = "high"
                state.mismatch_since_ms = 0
            elif score >= self._bind_lo and match_id is not None:
                # Named as a possibility, never as a key: this branch cannot bind on its own.
                state.confidence = "medium"
            else:
                state.confidence = "guest"
        else:
            # Already bound: re-verify against the bound student's own template.
            own = self._roster.template(state.bound_student_id)
            own_score = cosine(avg, own) if own else ranked[0][2]
            rival = [(s, sid) for sid, _, s in ranked if sid != state.bound_student_id]
            margin = own_score - max(rival)[0] if rival else 1.0
            state.score, state.margin = own_score, margin
            state.runner_up = max(rival)[1] if rival else ""
            if own_score >= self._bind_lo and margin >= self._min_margin:
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
        state.samples = 0
