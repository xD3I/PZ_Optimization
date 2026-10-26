#!/usr/bin/env python3
"""Torch shadows loop (2026-10-25): a devCapture run's frames as PNGs, each named by its dev cost mask.

frames.py <run> [--every N] [--scale S] [--out DIR]
The masks come from the console's `pixel light: dev cost mask M epoch_ms=E` lines (devPplAlternate); a frame takes the
mask in force at its epoch. Without them every frame is mask 0.
"""
import argparse, os, re, sys
import numpy as np
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--every", type=int, default=1)
ap.add_argument("--scale", type=float, default=0.5)
ap.add_argument("--out")
a = ap.parse_args()
cap = os.path.join(a.run, "capture")
if not os.path.isdir(cap):
    cap = a.run
lines = open(os.path.join(cap, "index.txt")).read().split("\n")
m = re.match(r"w=(\d+) h=(\d+)( fmt=gray)?", lines[0])
w, h, gray = int(m.group(1)), int(m.group(2)), bool(m.group(3))
eps = [int(x.split()[0]) for x in lines[1:] if x.strip() and x.split()[0].isdigit()]
masks = []
con = os.path.join(a.run, "console.txt")
if os.path.exists(con):
    for line in open(con, errors="replace"):
        mm = re.search(r"dev cost mask (\d+) epoch_ms=(\d+)", line)
        if mm:
            masks.append((int(mm.group(2)), int(mm.group(1))))
masks.sort()
out = a.out or os.path.join(a.run, "frames")
os.makedirs(out, exist_ok=True)
bpp = 1 if gray else 4
path = os.path.join(cap, "frames.gray" if gray else "frames.rgba")
n = os.path.getsize(path) // (w * h * bpp)
with open(path, "rb") as f:
    for i in range(n):
        buf = f.read(w * h * bpp)
        if i % a.every:
            continue
        arr = np.frombuffer(buf, np.uint8).reshape(h, w, bpp)[::-1]
        img = Image.fromarray(arr[:, :, 0] if gray else arr[:, :, :3])
        if a.scale != 1.0:
            img = img.resize((int(w * a.scale), int(h * a.scale)), Image.LANCZOS)
        ep = eps[i] if i < len(eps) else 0
        mk = 0
        for e, k in masks:
            if e <= ep:
                mk = k
        name = "f%03d-m%d.png" % (i, mk)
        img.save(os.path.join(out, name))
        print(name, ep)
