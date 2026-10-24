#!/usr/bin/env python3
"""In-game frames of a ground-tiling run: a devCapture sequence (<run>/capture) under the ground alternation
(-Dpzopt.devGroundAlternate=P, optionally devGroundCycle) split into its on / off halves.

    ingame.py RUN [--scale S] [--crop x0,y0,x1,y1] [--png DIR]

For each period: the frame 70 % into it (the re-bake of the switch has landed), its label (on / off, the cycle name),
the repeat score (metric.repeat_score at the lattice for screen scale S = zoom-1 px per sprite px x capture scale) of the
frame or crop, and the mean |difference| against the nearest off frame. --png writes the frames (and on - off diffs).
"""
import argparse
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "ppl"))
from capture import load  # noqa: E402
from metric import repeat_score  # noqa: E402


def alternation(run):
    con = open(os.path.join(run, "console.txt"), errors="replace").read()
    m = re.search(r"ground: alternating every (\d+) ms from epoch_ms (\d+).*?(?:cycle (\S+))?$", con, re.M)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), (m.group(3).split(",") if m.group(3) else None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--scale", type=float, default=0.5)
    ap.add_argument("--crop")
    ap.add_argument("--png")
    a = ap.parse_args()
    frames, stamps = load(a.run)
    alt = alternation(a.run)
    if alt is None:
        print("no 'ground: alternating' line in console.txt"); return 1
    period, t0, cycle = alt
    if a.crop:
        x0, y0, x1, y1 = map(int, a.crop.split(","))
        frames = frames[:, y0:y1, x0:x1]
    picks = {}
    for i, ts in enumerate(stamps):
        k = (ts - t0) // period
        f = ((ts - t0) % period) / period
        if k < 0:
            continue
        if k not in picks or abs(f - 0.7) < abs(picks[k][1] - 0.7):
            picks[k] = (i, f)
    offs = {k: v for k, v in picks.items() if k % 2 == 1}
    if a.png:
        os.makedirs(a.png, exist_ok=True)
        from PIL import Image
    for k in sorted(picks):
        i, f = picks[k]
        on = k % 2 == 0
        name = ("on" if on else "off") + ("-" + cycle[(k // 2) % len(cycle)] if cycle else "")
        img = frames[i].astype(np.float32) / 255
        rep, bg = repeat_score(img, scale=a.scale)
        line = f"period {k:2d} {name:14s} frame {i:4d} at {f:.2f}: rep {rep:.3f} bg {bg:.3f}"
        if on and offs:
            ko = min(offs, key=lambda q: abs(q - k))
            d = np.abs(img - frames[offs[ko][0]].astype(np.float32) / 255).mean() * 255
            line += f"  |on - off({ko})| {d:.2f}/255"
            if a.png:
                diff = np.clip(np.abs(img - frames[offs[ko][0]].astype(np.float32) / 255) * 4, 0, 1)
                Image.fromarray((diff * 255).astype(np.uint8)).save(os.path.join(a.png, f"diff-{k:02d}-{name}.png"))
        if a.png:
            Image.fromarray(frames[i]).save(os.path.join(a.png, f"p{k:02d}-{name}.png"))
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
