"""FocusChangeAI edge server - multi-person focus monitoring pipeline.

Five layers, mirroring the architecture agreed for the classroom build:

  1. detection   YOLO + ByteTrack  -> "who is where, stable track id"
  2. identity    ArcFace + roster  -> "which student is this track"
  3. scheduler   ROI budget        -> "who gets measured this frame"
  4. person      MediaPipe + the ported Kotlin engines (EyeEngine, SituationEngine,
                 PersonGuard, habit model, SEI) -> "how focused is this person"
  5. sync        Firestore writer keyed by studentId (sessions / class_live / bands)

The measurement math in ``engines``/``habit``/``sei`` is a faithful port of the
Android app under ``app/src/main/java/com/focuschange/ai`` so a number means the
same thing on both sides.
"""

__version__ = "0.1.0"
