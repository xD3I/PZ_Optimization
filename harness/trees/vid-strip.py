#!/usr/bin/env python3
"""A strip of consecutive frames of a capture round frame index I, 1:1 crop, brightened for a dark scene (2026-10-25).

Usage: vid-strip.py VIDEO BLOCKS.npz OUT.jpg I [--before 2] [--after 3] [--crop W:H:X:Y] [--gain 2.2] [--scale 0.5]
"""
import argparse
import glob
import os
import subprocess
import tempfile

import numpy as np
from PIL import Image


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("npz")
    ap.add_argument("out")
    ap.add_argument("frames", help="index, or a comma list of indices (then --before/--after apply to each)")
    ap.add_argument("--before", type=int, default=2)
    ap.add_argument("--after", type=int, default=3)
    ap.add_argument("--crop", default="1280:720:1920:720")
    ap.add_argument("--gain", type=float, default=2.2)
    ap.add_argument("--scale", type=float, default=0.5)
    a = ap.parse_args()
    pts = np.load(a.npz)["pts"]
    rows = []
    for fi in (int(v) for v in a.frames.split(",")):
        i0, i1 = max(0, fi - a.before), min(len(pts) - 1, fi + a.after)
        t0, t1 = pts[i0] - 0.001, pts[i1] + 0.001
        cw, ch, cx, cy = (int(v) for v in a.crop.split(":"))
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["ffmpeg", "-v", "error", "-copyts", "-ss", str(max(0.0, 2.0 * int(t0 / 2.0) - 0.01)), "-c:v", "libdav1d",
                            "-i", a.video, "-vf", f"select='between(t,{t0:.6f},{t1:.6f})',crop={cw}:{ch}:{cx}:{cy}",
                            "-fps_mode", "passthrough", os.path.join(d, "f%03d.png")], check=True)
            fs = sorted(glob.glob(os.path.join(d, "f*.png")))
            ims = [np.clip(np.asarray(Image.open(f).convert("RGB")).astype(np.float32) * a.gain, 0, 255).astype(np.uint8) for f in fs]
        row = np.hstack([np.pad(im, ((0, 0), (0, 4), (0, 0)), constant_values=255) for im in ims])
        rows.append(row)
    w = max(r.shape[1] for r in rows)
    g = np.vstack([np.pad(r, ((0, 6), (0, w - r.shape[1]), (0, 0))) for r in rows])
    im = Image.fromarray(g)
    im.resize((int(im.width * a.scale), int(im.height * a.scale))).save(a.out, quality=88)
    print(a.out, im.size)


if __name__ == "__main__":
    main()
