#!/usr/bin/env python3
"""Static-light shadow rig: a devCapture (crop=x:y:w:h, RGBA, bottom-up rows) of a run read back.

usage: capstats.py <run_dir> [--png N[,N...]] [--box name=x,y,w,h ...] [--out dir]
  --png    frames saved as PNG (top-down) into --out (default the scratch dir given, else <run>/capture)
  --box    regions in capture pixels (top-left origin): per-frame mean luma of each, then mean / stdev / max frame-to-frame
           step / ratio stdev against the first box (a shadow that strobes with a fire's flicker shows a ratio that moves)
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--png", default="")
    ap.add_argument("--box", action="append", default=[])
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    cap = os.path.join(a.run, "capture")
    with open(os.path.join(cap, "index.txt")) as f:
        head = f.readline().split()
    w = int(head[0].split("=")[1])
    h = int(head[1].split("=")[1])
    raw = np.memmap(os.path.join(cap, "frames.rgba"), dtype=np.uint8, mode="r")
    n = raw.size // (w * h * 4)
    fr = raw[: n * w * h * 4].reshape(n, h, w, 4)
    print(f"{n} frames {w}x{h}")
    out = a.out or cap
    if a.png:
        for s in a.png.split(","):
            i = int(s)
            if 0 <= i < n:
                p = os.path.join(out, f"{os.path.basename(a.run.rstrip('/'))}-f{i:03d}.png")
                Image.fromarray(np.ascontiguousarray(fr[i, ::-1, :, :3])).save(p)
                print("png", p)
    series = []
    for spec in a.box:
        name, xywh = spec.split("=")
        x, y, bw, bh = (int(v) for v in xywh.split(","))
        vals = []
        for i in range(n):
            img = fr[i, ::-1, :, :3][y:y + bh, x:x + bw].astype(np.float32)
            vals.append(float((0.2126 * img[..., 0] + 0.7152 * img[..., 1] + 0.0722 * img[..., 2]).mean()))
        v = np.array(vals)
        series.append((name, v))
        print(f"{name}: mean {v.mean():.2f} stdev {v.std():.2f} min {v.min():.2f} max {v.max():.2f} max step {np.abs(np.diff(v)).max() if n > 1 else 0:.2f}")
    if len(series) > 1:
        ref = series[0][1]
        for name, v in series[1:]:
            r = v / np.maximum(ref, 1e-3)
            print(f"{name}/{series[0][0]}: ratio mean {r.mean():.3f} stdev {r.std():.3f} max step {np.abs(np.diff(r)).max() if n > 1 else 0:.3f}")


if __name__ == "__main__":
    sys.exit(main())
