#!/usr/bin/env python3
"""Enroll (or refresh) a student's ArcFace template in the class roster.

The edge server binds a transient camera track to a durable studentId by matching
an averaged ArcFace embedding against the templates this tool writes. Run it once
per student, in good light, facing the camera.

Usage:
    # capture live from webcam (press SPACE to sample a frame, ENTER to finish)
    python tools/enroll_roster.py --roster roster.json --id sv001 --name "Nguyen Van A" --capture 20

    # or build the template from saved photos
    python tools/enroll_roster.py --roster roster.json --id sv001 --name "Nguyen Van A" \
        --images photos/sv001/*.jpg

The template is the mean embedding of the accepted samples; matching is cosine
similarity (see focusedge/identity.py).
"""
from __future__ import annotations

import argparse
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from focusedge.identity import FaceEmbedder  # noqa: E402
from focusedge.roster import Roster  # noqa: E402


def _largest_embedding(embedder, bgr):
    return embedder.embed(bgr, (0, 0, bgr.shape[1], bgr.shape[0]))


def from_images(embedder, paths):
    embs = []
    for p in paths:
        import cv2
        img = cv2.imread(p)
        if img is None:
            print("  skip (unreadable): %s" % p)
            continue
        e = _largest_embedding(embedder, img)
        if e is None:
            print("  no face: %s" % p)
            continue
        embs.append(e)
        print("  ok: %s" % p)
    return embs


def from_camera(embedder, target, camera):
    import cv2
    cap = cv2.VideoCapture(camera)
    if not cap.isOpened():
        raise SystemExit("cannot open camera %r" % camera)
    embs = []
    print("Press SPACE to sample a face, ENTER to stop (%d needed)." % target)
    while len(embs) < target:
        ok, frame = cap.read()
        if not ok:
            break
        cv2.imshow("enroll %s (%d/%d)" % (frame_label, len(embs), target), frame)
        key = cv2.waitKey(1) & 0xFF
        if key == 13:  # ENTER
            break
        if key == 32:  # SPACE
            e = _largest_embedding(embedder, frame)
            if e is None:
                print("  no face detected, try again")
            else:
                embs.append(e)
                print("  sampled %d/%d" % (len(embs), target))
    cap.release()
    cv2.destroyAllWindows()
    return embs


frame_label = "roster"


def main(argv=None) -> int:
    global frame_label
    ap = argparse.ArgumentParser(description="Enroll a student's face template")
    ap.add_argument("--roster", default="roster.json")
    ap.add_argument("--id", required=True, help="studentId (the key stored rows are found by)")
    ap.add_argument("--name", default=None)
    ap.add_argument("--seat", default=None)
    ap.add_argument("--pack", default="buffalo_l")
    ap.add_argument("--images", nargs="*", default=None, help="glob(s) of saved photos")
    ap.add_argument("--capture", type=int, default=0, help="samples to grab from webcam")
    ap.add_argument("--camera", type=int, default=0)
    args = ap.parse_args(argv)

    frame_label = args.id
    embedder = FaceEmbedder(pack=args.pack)

    embs: list = []
    if args.images:
        paths: list = []
        for pattern in args.images:
            paths.extend(sorted(glob.glob(pattern)))
        embs = from_images(embedder, paths)
    elif args.capture > 0:
        embs = from_camera(embedder, args.capture, args.camera)
    else:
        print("nothing to do: pass --images ... or --capture N")
        return 2

    if not embs:
        print("no usable face samples; roster NOT changed")
        return 1

    template = [sum(e[i] for e in embs) / len(embs) for i in range(len(embs[0]))]

    roster = Roster.load(args.roster)
    roster.set_template(args.id, template, name=args.name, seat=args.seat)
    roster.save(args.roster)
    print("enrolled %s (%d samples) -> %s" % (args.id, len(embs), args.roster))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
