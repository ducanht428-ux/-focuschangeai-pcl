"""Layer 1 - detection + tracking (YOLO11 + ByteTrack via Ultralytics).

YOLO answers "how many people, where, and what is near them"; ByteTrack gives each
person a stable id across frames. Third-party imports are lazy so the rest of the
package (and its unit tests) import without a GPU or the model weights present.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

# COCO class id for the only box this project treats as a subject rather than as context.
COCO_PERSON = 0

# The object vocabulary this leg names on screen. Same forty entries as CONTEXT_VI in the browser leg's
# js/detect.mjs, so "trong khung: chai nước 0.62" means the same desk item on both legs. Furniture is
# deliberately absent: chair and dining table are in every frame of a classroom and would out-number the
# desk items they are supposed to be background for.
#
# This list is not decoration. activity.py's break and practice rules read exactly these names, and
# while the detector whitelisted four objects the BREAK label could never fire from room evidence here
# even though it fires on the web leg. Widening it costs no inference: ultralytics scores all eighty
# COCO classes on every pass, this is a filter over boxes that already exist.
#
# Parity, stated rather than glossed: the only rule that can move a SCORE reads one name, 'phone'
# (person_pipeline.phone_context_penalty), and that name has always been here - so the two legs still
# cannot disagree about attention, only about labels.
CONTEXT_VI = {
    "phone": "điện thoại", "book": "sách", "laptop": "laptop", "backpack": "ba lô",
    "bottle": "chai nước", "cup": "cốc nước", "scissors": "cái kéo", "handbag": "túi xách",
    "clock": "đồng hồ", "teddy bear": "gấu bông", "mouse": "chuột máy tính",
    "keyboard": "bàn phím", "remote": "điều khiển", "umbrella": "cái ô",
    "sports ball": "quả bóng", "tv": "tivi", "kite": "con diều", "frisbee": "đĩa ném",
    "skateboard": "ván trượt", "tennis racket": "cái vợt", "baseball bat": "gậy bóng chày",
    "baseball glove": "găng bóng chày", "wine glass": "ly nước", "fork": "cái nĩa",
    "knife": "con dao", "spoon": "cái thìa", "bowl": "cái bát", "banana": "quả chuối",
    "apple": "quả táo", "sandwich": "bánh sandwich", "orange": "quả cam",
    "broccoli": "bông cải", "carrot": "củ cà rốt", "hot dog": "bánh mì hot dog",
    "pizza": "bánh pizza", "donut": "bánh donut", "cake": "bánh ngọt", "tie": "cà vạt",
    "potted plant": "chậu cây", "vase": "lọ hoa",
}

# ultralytics spells the class "cell phone"; every other file in this project, and the web leg, call it
# "phone". The canonical key is what nearby_objects holds, so the penalty rule never has to know.
CONTEXT_ALIASES = {"cell phone": "phone"}


def canonical_name(name: str) -> str:
    """A model's class label spelled the way this project's rules spell it."""
    return CONTEXT_ALIASES.get(name, name)



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
        #
        # The object ids are read out of the weights' OWN label table instead of being typed in here,
        # because the number depends on which table a checkpoint carries: the 80-name COCO set puts
        # "book" at 73 while the 91-name variant puts it at 75, and a hand-typed dict would quietly
        # name a sink "book" on the other one. Whatever the model calls a class, the name is what the
        # rules match, so this is the one place a class number is trusted.
        names = dict(getattr(self._model, "names", {}) or {})
        self._context_ids = {i: canonical_name(str(n)) for i, n in names.items()
                             if canonical_name(str(n)) in CONTEXT_VI}
        # A person-shaped box is the subject of every rule downstream, so a weights file whose label
        # table does not call class ``person_class`` "person" would make this leg measure nothing at all.
        # Failing here, at load, names the mismatch; letting it pass would show up as an empty room.
        if names and canonical_name(str(names.get(self._person_class, ""))) != "person":
            raise ValueError("model %r has no 'person' at class id %d (it is %r) - set "
                             "detection.person_class to the id this checkpoint uses"
                             % (model, self._person_class, names.get(self._person_class)))


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
            elif c in self._context_ids:
                raw_objects.append((self._context_ids[c], tuple(box), float(cf)))

        # Assign each object to the person whose box contains its center.
        unassigned = assign_objects(raw_persons, raw_objects)
        return raw_persons, unassigned
