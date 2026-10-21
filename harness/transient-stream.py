#!/usr/bin/env python3
"""repro-ui (2026-10-16): streaming one-frame transient detector (flicker.py's A -> B -> A test with maxk 1, three frames in
memory instead of the whole stretch; flicker.py on a 165 fps capture got the queue worker OOM-killed).

Per frame f: pixels where |f - f-1| >= T and |f - f+1| >= T but |f-1 - f+1| < T/2 (something drawn for exactly one frame,
or missing for one frame). Prints the frames with the most such pixels and the bounding box of them.

  transient-stream.py <video> <t0> <t1> <fps> <crop w:h:x:y> [--thresh 24] [--top 25] [--scale 2]
"""
import argparse
import subprocess

import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("video"); ap.add_argument("t0", type=float); ap.add_argument("t1", type=float)
ap.add_argument("fps", type=float); ap.add_argument("crop")
ap.add_argument("--thresh", type=int, default=24); ap.add_argument("--top", type=int, default=25)
ap.add_argument("--scale", type=int, default=2)
a = ap.parse_args()
cw, ch, cx, cy = map(int, a.crop.split(":"))
W, H = cw // a.scale, ch // a.scale
cmd = ["ffmpeg", "-v", "error", "-hwaccel", "cuda", "-ss", str(a.t0), "-t", str(a.t1 - a.t0), "-i", a.video, "-vf",
       f"crop={a.crop},scale={W}:{H}:flags=area,format=gray", "-fps_mode", "passthrough", "-f", "rawvideo", "-"]
p = subprocess.Popen(cmd, stdout=subprocess.PIPE)


def nxt():
    b = p.stdout.read(W * H)
    return None if len(b) < W * H else np.frombuffer(b, np.uint8).reshape(H, W).astype(np.int16)


prev, cur, nx = nxt(), nxt(), nxt()
i = 1
res = []
T = a.thresh
while nx is not None:
    m = (np.abs(cur - prev) >= T) & (np.abs(cur - nx) >= T) & (np.abs(prev - nx) < T // 2)
    n = int(m.sum())
    if n:
        ys, xs = np.nonzero(m)
        res.append((n, i, cx + a.scale * xs.min(), cy + a.scale * ys.min(), cx + a.scale * xs.max(), cy + a.scale * ys.max()))
    prev, cur, nx = cur, nx, nxt()
    i += 1
print(f"frames={i + 1} with_transients={len(res)} total_px={sum(r[0] for r in res)}")
for n, f, x0, y0, x1, y1 in sorted(res, reverse=True)[: a.top]:
    print(f"frame {f} t={a.t0 + f / a.fps:.3f} px={n} box={x0},{y0}-{x1},{y1}")
