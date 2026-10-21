#!/usr/bin/env python3
"""The dev views of a voxel lighting run with devVoxelLightViewCycle + devCapture, one frame each, in a labelled grid.

    views.py <run dir> [--out /tmp/vxl-views.png] [--crop x0,y0,x1,y1 (fractions)] [--scale 0.5] [--cols 2]

Reads <run>/capture (pzopt.FrameCapture) and the "voxel light: dev view V from epoch_ms E" lines of console.txt, takes the
middle captured frame of each view's first span (frames within 300 ms of a switch skipped) and writes them in a grid."""
import argparse
import os
import re
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ppl"))
import capture  # noqa: E402

NAMES = {0: "voxel lighting", 1: "traced lamps x facing", 2: "sky term", 3: "bounce x4", 4: "validity (green: native)", 5: "facing / 2",
         6: "voxel alone", 7: "split: native left, voxel right", 8: "normals", 9: "blue: cache, red: per pixel", 10: "per pixel (compare with 0)"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--out", default="/tmp/vxl-views.png")
    ap.add_argument("--crop", default="0,0,1,1")
    ap.add_argument("--scale", type=float, default=0.5)
    ap.add_argument("--cols", type=int, default=2)
    a = ap.parse_args()
    fr, st = capture.load(a.run)
    spans = []
    for line in open(os.path.join(a.run, "console.txt"), errors="replace"):
        m = re.search(r"voxel light: dev view (\d+) from epoch_ms (\d+)", line)
        if m:
            spans.append((int(m.group(1)), int(m.group(2))))
    if not spans:
        sys.exit("no dev view lines in console.txt")
    picked = {}
    for k, (v, t0) in enumerate(spans):
        t1 = spans[k + 1][1] if k + 1 < len(spans) else t0 + 10**9
        idx = [i for i, s in enumerate(st) if t0 + 300 <= s <= t1 - 300]
        if idx and v not in picked:
            picked[v] = idx[len(idx) // 2]
    h, w = fr.shape[1:3]
    x0, y0, x1, y1 = [float(t) for t in a.crop.split(",")]
    box = (int(w * x0), int(h * y0), int(w * x1), int(h * y1))
    tiles = []
    for v in sorted(picked):
        im = Image.fromarray(fr[picked[v]]).crop(box)
        im = im.resize((int(im.width * a.scale), int(im.height * a.scale)), Image.LANCZOS)
        d = ImageDraw.Draw(im)
        d.rectangle((0, 0, 330, 22), fill=(0, 0, 0))
        d.text((6, 5), "%d: %s" % (v, NAMES.get(v, "")), fill=(255, 255, 255))
        tiles.append(im)
    if not tiles:
        sys.exit("no frames inside the views' spans")
    cols = min(a.cols, len(tiles))
    rows = (len(tiles) + cols - 1) // cols
    out = Image.new("RGB", (tiles[0].width * cols, tiles[0].height * rows))
    for i, t in enumerate(tiles):
        out.paste(t, ((i % cols) * t.width, (i // cols) * t.height))
    out.save(a.out)
    print("views", sorted(picked), "->", a.out)


if __name__ == "__main__":
    main()
