#!/usr/bin/env python3
"""The voxel lighting on / off pair of a run with devVoxelLightAlternate + devCapture: which captured frames had them on.

    capab.py <run dir> [--out /tmp/vxl-ab.png] [--crop x0,y0,x1,y1 (fractions of the frame)] [--scale 1.0]

Reads <run>/capture (pzopt.FrameCapture) and the alternation's epoch from console.txt ("voxel light: alternating every N ms
from epoch_ms E (on first)"), labels every frame on / off by its epoch (frames within 150 ms of a switch are skipped: the
render thread lags the game thread), and writes the middle off frame and the middle on frame side by side (off left)."""
import argparse
import os
import re
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ppl"))
import capture  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--out", default="/tmp/vxl-ab.png")
    ap.add_argument("--crop", default="0,0,1,1")
    ap.add_argument("--scale", type=float, default=1.0)
    a = ap.parse_args()
    fr, st = capture.load(a.run)
    per = t0 = None
    for line in open(os.path.join(a.run, "console.txt"), errors="replace"):
        m = re.search(r"voxel light: alternating every (\d+) ms from epoch_ms (\d+)", line)
        if m:
            per, t0 = int(m.group(1)), int(m.group(2))
    if per is None:
        sys.exit("no alternation in console.txt")
    on, off = [], []
    for i, s in enumerate(st):
        ph = (s - t0) % per
        if ph < 150 or ph > per - 150:
            continue
        (on if ((s - t0) // per) % 2 == 0 else off).append(i)
    print("frames on", on, "off", off)
    if not on or not off:
        sys.exit("need both")
    h, w = fr.shape[1:3]
    x0, y0, x1, y1 = [float(t) for t in a.crop.split(",")]
    box = (int(w * x0), int(h * y0), int(w * x1), int(h * y1))
    A = Image.fromarray(fr[off[len(off) // 2]]).crop(box)
    B = Image.fromarray(fr[on[len(on) // 2]]).crop(box)
    out = Image.new("RGB", (A.width * 2, A.height))
    out.paste(A, (0, 0))
    out.paste(B, (A.width, 0))
    if a.scale != 1.0:
        out = out.resize((int(out.width * a.scale), int(out.height * a.scale)), Image.LANCZOS)
    out.save(a.out)
    d = np.asarray(B).astype(float) - np.asarray(A).astype(float)
    print("mean change %.2f, |change| > 24: %.2f %% of pixels, wrote %s" % (d.mean(), (np.abs(d).max(2) > 24).mean() * 100, a.out))


if __name__ == "__main__":
    main()
