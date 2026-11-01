"""Layer 1 - detection + tracking (YOLO11 + ByteTrack via Ultralytics).

YOLO answers "how many people, where, and what is near them"; ByteTrack gives each
person a stable id across frames. Third-party imports are lazy so the rest of the
package (and its unit tests) import without a GPU or the model weights present.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

# COCO class ids we care about for classroom context.
COCO_PERSON = 0
COCO_CELL_PHONE = 67
COCO_BOOK = 73
COCO_LAPTOP = 63
COCO_BACKPACK = 24

CONTEXT_CLASSES = {
    COCO_CELL_PHONE: "phone",
    COCO_BOOK: "book",
    COCO_LAPTOP: "laptop",
    COCO_BACKPACK: "backpack",
}


@dataclass
class PersonTrack:
    track_id: int
    bbox: tuple            # (x0, y0, x1, y1) in frame pixels
    conf: float
    # Objects whose box center falls inside this person's box, name -> best conf.
    nearby_objects: Dict[str, float] = field(default_factory=dict)

    @property
    def center(self) -> tuple:
        x0, y0, x1, y1 = self.bbox
        return ((x0 + x1) / 2.0, (y0 + y1) / 2.0)

    @property
    def width(self) -> float:
        return self.bbox[2] - self.bbox[0]

    @property
    def height(self) -> float:
        return self.bbox[3] - self.bbox[1]


def assign_objects(persons: List["PersonTrack"], objects: List[tuple]) -> List[tuple]:
    """Record each context object on the person who actually owns it.

    An object belongs to the tightest person box that contains its centre. The first version took
    the FIRST person in YOLO's output order and stopped looking: two desks pressed together means
    one phone whose centre sits inside both boxes, and the student who was listed second never got
    the context while the one listed first took a penalty - and a phone-in-hand alert - for an
    object in somebody else's space. Choosing the smallest containing box is the same tie-break an
    annotator would use: whoever the object occupies most of, owns it.

    Returns the objects no person box contains, for scene-level rules.
    """
    unassigned = []
    for name, box, cf in objects:
        cx = (box[0] + box[2]) / 2.0
        cy = (box[1] + box[3]) / 2.0
        owner = None
        owner_area = 0.0
        for p in persons:
            if p.bbox[0] <= cx <= p.bbox[2] and p.bbox[1] <= cy <= p.bbox[3]:
                area = p.width * p.height
                if owner is None or area < owner_area:
                    owner = p
                    owner_area = area
        if owner is not None:
            if cf > owner.nearby_objects.get(name, 0.0):
                owner.nearby_objects[name] = cf
        else:
            unassigned.append((name, box, cf))
    return unassigned


class Detector:
    """Wraps an Ultralytics YOLO model in tracking mode.

    Usage:
        det = Detector("yolo11m.pt", tracker="bytetrack.yaml")
        tracks, objects = det.run(frame_bgr)   # call every detection tick
    """

    def __init__(self, model: str = "yolo11m.pt", conf: float = 0.35,
                 tracker: str = "bytetrack.yaml", person_class: int = COCO_PERSON,
                 imgsz: int = 1280, device: Optional[str] = None) -> None:
        from ultralytics import YOLO  # lazy
        self._model = YOLO(model)
        self._conf = conf
        self._tracker = tracker
        self._person_class = person_class
        self._imgsz = imgsz
        self._device = device
        # Face boxes are produced separately by the FaceLandmarker/BlazeFace; the
        # detector only yields bodies + context objects.

    def run(self, frame_bgr) -> "tuple[List[PersonTrack], List[tuple]]":
        """Return (person_tracks, other_object_boxes).

        other_object_boxes is a list of (name, bbox, conf) for context objects that
        did not fall inside a person box (kept for scene-level rules).
        """
        results = self._model.track(
            frame_bgr,
            conf=self._conf,
            imgsz=self._imgsz,
            tracker=self._tracker,
            persist=True,
            device=self._device,
            verbose=False,
        )
        r = results[0]
        persons: List[PersonTrack] = []
        objects: List[tuple] = []
        if r.boxes is None or r.boxes.id is None:
            return persons, objects

        ids = r.boxes.id.int().cpu().tolist()
        xyxy = r.boxes.xyxy.cpu().tolist()
        cls = r.boxes.cls.int().cpu().tolist()
        conf = r.boxes.conf.cpu().tolist()

        raw_persons = []
        raw_objects = []
        for tid, box, c, cf in zip(ids, xyxy, cls, conf):
            if c == self._person_class:
                raw_persons.append(PersonTrack(track_id=int(tid), bbox=tuple(box), conf=float(cf)))
            elif c in CONTEXT_CLASSES:
                raw_objects.append((CONTEXT_CLASSES[c], tuple(box), float(cf)))

        # Assign each object to the person whose box contains its center.
        unassigned = assign_objects(raw_persons, raw_objects)
        return raw_persons, unassigned
