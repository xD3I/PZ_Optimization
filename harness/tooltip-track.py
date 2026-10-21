#!/usr/bin/env python3
"""repro-ui (2026-10-16): find tooltip boxes in every frame of a recording window and flag frames where a tooltip is drawn
somewhere it was neither the frame before nor the frame after (the "flickers back and forth" report).

A tooltip box = a horizontal light border line (ISToolTip's drawRectBorder) of 120..1400 px that is not part of the static
picture (the per-pixel median of the window's frames). Per frame: the box's top-left (x, y) and width. Out-of-order frame:
both neighbours have the box, the neighbours agree within 6 px but this frame is > 6 px away from both.

  tooltip-track.py <recording.mp4> <t0> <t1> <out.txt> [crop w:h:x:y]
"""
import subprocess
import sys

import numpy as np

src, t0, t1, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
crop = sys.argv[5] if len(sys.argv) > 5 else "5120:2160:0:0"
cw, ch, cx, cy = map(int, crop.split(":"))
W, H = cw // 2, ch // 2
def dec(extra):
    cmd = ["ffmpeg", "-v", "error", "-ss", str(t0), "-t", str(t1 - t0), "-i", src, "-vf",
           f"{extra}crop={crop},scale={W}:{H}:flags=area,format=gray", "-f", "rawvideo", "-"]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE)


sp = dec("fps=2,").stdout.read()
k = len(sp) // (W * H)
static = np.median(np.frombuffer(sp[: k * W * H], np.uint8).reshape(k, H, W), axis=0).astype(np.int16)
pipe = dec("").stdout


def boxes(g):
    # a box = two light horizontal border lines (ISToolTip's drawRectBorder) with the same x span, 20..250 half-res px apart
    up = np.roll(g, 2, axis=0); dn = np.roll(g, -2, axis=0)
    line = (g - np.maximum(up, dn) > 6)
    runs = {}
    for y in np.where(line.sum(axis=1) >= 80)[0]:
        if y < 3 or y > H - 3:
            continue
        d = np.diff(np.concatenate(([0], line[y].view(np.int8), [0])))
        st = np.where(d == 1)[0]; en = np.where(d == -1)[0]
        for a0, b0 in zip(st, en):
            if 80 <= b0 - a0 <= 520:
                runs.setdefault(int(y), []).append((int(a0), int(b0)))
    best = None
    ys = sorted(runs)
    for i, y1 in enumerate(ys):
        for (a1, b1) in runs[y1]:
            for y2 in ys[i + 1:]:
                if y2 - y1 > 250:
                    break
                if y2 - y1 < 20:
                    continue
                for (a2, b2) in runs[y2]:
                    if abs(a1 - a2) <= 2 and abs(b1 - b2) <= 2:
                        # the static picture has no box there (setting rows have no closed boxes this wide)
                        if np.abs(g[y1, a1:b1] - static[y1, a1:b1]).mean() > 4:
                            cand = (cx + 2 * a1, cy + 2 * y1, 2 * (b1 - a1), 2 * (y2 - y1))
                            if best is None or cand[2] * cand[3] > best[2] * best[3]:
                                best = cand
    return best


track = []
while True:
    buf = pipe.read(W * H)
    if len(buf) < W * H:
        break
    track.append(boxes(np.frombuffer(buf, np.uint8).reshape(H, W).astype(np.int16)))
n = len(track)
flags = []
with open(out, "w") as fo:
    for i, b in enumerate(track):
        fo.write("%d %.3f %s\n" % (i, t0 + i / 60.0, "-" if b is None else "%d %d %d %d" % b))
    for i in range(1, n - 1):
        p, c, q = track[i - 1], track[i], track[i + 1]
        if p and c and q:
            dpq = abs(p[0] - q[0]) + abs(p[1] - q[1])
            dev = abs(c[0] - (p[0] + q[0]) / 2) + abs(c[1] - (p[1] + q[1]) / 2)
            if dev > 10 + 0.25 * dpq:  # off the line between its neighbours: drawn somewhere else for one frame
                flags.append((i, t0 + i / 60.0, p, c, q))
    fo.write("# frames=%d with_box=%d out_of_order=%d\n" % (n, sum(1 for b in track if b), len(flags)))
    for f in flags:
        fo.write("# FLICKER frame %d t=%.3f prev=%s this=%s next=%s\n" % f)
print("frames=%d with_box=%d out_of_order=%d" % (n, sum(1 for b in track if b), len(flags)))
for f in flags[:40]:
    print("FLICKER frame %d t=%.3f prev=%s this=%s next=%s" % f)
