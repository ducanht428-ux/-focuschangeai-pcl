"""Class roster: the studentId -> face-template table identity binds against.

Stored as JSON next to the config so it can be edited by hand and versioned without
a DB. Each entry is one student with an enrolled ArcFace embedding (from
``tools/enroll_roster.py``) and an optional seat label used as a prior.
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

from .identity import cosine


@dataclass
class RosterEntry:
    student_id: str
    name: str
    template: Optional[List[float]] = None   # 512-D ArcFace embedding, None until enrolled
    seat: Optional[str] = None               # "A1" ... optional seating prior


class Roster:
    def __init__(self, entries: Optional[List[RosterEntry]] = None) -> None:
        self._by_id: Dict[str, RosterEntry] = {}
        for e in entries or []:
            self._by_id[e.student_id] = e

    # --- accessors -------------------------------------------------------
    def entries(self) -> List[RosterEntry]:
        return list(self._by_id.values())

    def get(self, student_id: str) -> Optional[RosterEntry]:
        return self._by_id.get(student_id)

    def name_of(self, student_id: str) -> str:
        e = self._by_id.get(student_id)
        return e.name if e else student_id

    def template(self, student_id: str) -> Optional[List[float]]:
        e = self._by_id.get(student_id)
        return e.template if e else None

    def set_template(self, student_id: str, embedding: List[float], name: Optional[str] = None,
                     seat: Optional[str] = None) -> None:
        e = self._by_id.get(student_id)
        if e is None:
            e = RosterEntry(student_id=student_id, name=name or student_id)
            self._by_id[student_id] = e
        e.template = embedding
        if name:
            e.name = name
        if seat:
            e.seat = seat

    def add(self, student_id: str, name: str, seat: Optional[str] = None) -> RosterEntry:
        e = RosterEntry(student_id=student_id, name=name, seat=seat)
        self._by_id[student_id] = e
        return e

    # --- matching --------------------------------------------------------
    def rank(self, embedding: List[float], k: int = 2) -> List[Tuple[str, str, float]]:
        """The ``k`` closest enrolled templates as (student_id, name, cosine), best first.

        ``best_match`` says who is nearest; this says by how much. Inside one class of same-age
        students the nearest template is often the desk next door, and the gap between the two
        scores is the only evidence this machine has that a name is not a guess.
        """
        scored = [(e.student_id, e.name, cosine(embedding, e.template))
                  for e in self._by_id.values() if e.template]
        scored.sort(key=lambda r: -r[2])
        return scored[:max(1, k)]

    def best_match(self, embedding: List[float]) -> Tuple[Optional[str], str, float]:
        """Return (student_id, name, cosine) of the closest enrolled template."""
        top = self.rank(embedding, 1)
        if not top:
            return None, "", 0.0
        return top[0]

    # --- persistence -----------------------------------------------------
    @classmethod
    def load(cls, path: str) -> "Roster":
        if not os.path.exists(path):
            return cls([])
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        entries = [
            RosterEntry(student_id=s["student_id"], name=s.get("name", s["student_id"]),
                        template=s.get("template"), seat=s.get("seat"))
            for s in data.get("students", [])
        ]
        return cls(entries)

    def save(self, path: str) -> None:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        data = {"students": []}
        for e in self._by_id.values():
            data["students"].append({
                "student_id": e.student_id,
                "name": e.name,
                "seat": e.seat,
                "template": e.template,
            })
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        os.replace(tmp, path)
