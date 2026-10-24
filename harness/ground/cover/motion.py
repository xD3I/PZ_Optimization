#!/usr/bin/env python3
"""Flicker and shimmer of a moving devCapture sequence (ground cover / glints motion check, 2026-10-24).

    motion.py <run> [<run> ...] [--png DIR] [--worst N] [--skip S]

Each run's capture (<run>/capture/frames.gray or frames.rgba + index.txt, pzopt.FrameCapture) is read; every pair of
consecutive frames is aligned by phase correlation (the camera follows the walking player: the world shifts by whole
pixels between frames), then the aligned overlap is compared:
  jump   pixels whose luma changes by more than 24/255 (a glint or a leaf popping in or out, a re-bake, a seam)
  bright of those, the ones where either frame is near white (>= 200): glint flicker
  mad    mean absolute luma change
A static scene that only pans gives small numbers (sprite edges snapping to pixels); a glint field that shimmers
gives many bright jumps; the A/B is the same route with the feature off. --png writes the worst pairs (prev, cur,
difference) for a look.
"""
import argparse
import os

import numpy as np


def load(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().split("\n")
    head = dict(kv.split("=") for kv in lines[0].split() if "=" in kv)
    w, h = int(head["w"]), int(head["h"])
    stamps = [int(x.split()[0]) for x in lines[1:] if x.strip()]
    gray = os.path.exists(os.path.join(d, "frames.gray"))
    if gray:
        raw = np.memmap(os.path.join(d, "frames.gray"), dtype=np.uint8, mode="r")
        n = min(len(stamps), raw.size // (w * h))
        frames = raw[: n * w * h].reshape(n, h, w)[:, ::-1, :]
    else:
        raw = np.memmap(os.path.join(d, "frames.rgba"), dtype=np.uint8, mode="r")
        n = min(len(stamps), raw.size // (w * h * 4))
        f = raw[: n * w * h * 4].reshape(n, h, w, 4)[:, ::-1, :, :3]
        frames = None
        frames = (f[..., 0] * 0.299 + f[..., 1] * 0.587 + f[..., 2] * 0.114).astype(np.uint8)
    return frames, np.array(stamps[:n])


def shift(a, b):
    """Integer (dy, dx) with b[y, x] ~ a[y - dy, x - dx] (phase correlation on a windowed centre)."""
    fa = np.fft.rfft2(a - a.mean())
    fb = np.fft.rfft2(b - b.mean())
    r = fb * np.conj(fa)
    r /= np.abs(r) + 1e-9
    c = np.fft.irfft2(r, s=a.shape)
    dy, dx = np.unravel_index(np.argmax(c), c.shape)
    if dy > a.shape[0] // 2:
        dy -= a.shape[0]
    if dx > a.shape[1] // 2:
        dx -= a.shape[1]
    return int(dy), int(dx)


def overlap(a, b, dy, dx):
    h, w = a.shape
    ya0, yb0 = max(0, -dy), max(0, dy)
    xa0, xb0 = max(0, -dx), max(0, dx)
    hh, ww = h - abs(dy), w - abs(dx)
    return a[ya0:ya0 + hh, xa0:xa0 + ww], b[yb0:yb0 + hh, xb0:xb0 + ww]


def analyse(run, skip, png, worst):
    frames, stamps = load(run)
    n = len(frames)
    rows = []
    for i in range(max(1, skip), n):
        a = frames[i - 1].astype(np.float32)
        b = frames[i].astype(np.float32)
        dy, dx = shift(a, b)
        oa, ob = overlap(a, b, dy, dx)
        dl = np.abs(ob - oa)
        jump = dl > 24
        bright = jump & ((oa >= 200) | (ob >= 200))
        px = oa.size
        rows.append((i, dy, dx, jump.sum() * 1e6 / px, bright.sum() * 1e6 / px, float(dl.mean())))
    if not rows:
        print(run, "no frame pairs")
        return
    r = np.array([x[3:] for x in rows])
    moving = np.mean([1 if (x[1] or x[2]) else 0 for x in rows])
    dt = np.diff(stamps)
    print("%s: %d frames %dx%d, %.0f ms apart (median), moving %.0f %% of pairs" % (
        os.path.basename(run.rstrip("/")), n, frames.shape[2], frames.shape[1], np.median(dt) if len(dt) else 0, moving * 100))
    for k, name in enumerate(("jump ppm", "bright ppm", "mad")):
        print("  %-10s mean %9.2f  p50 %9.2f  p90 %9.2f  p99 %9.2f  max %9.2f" % (
            name, r[:, k].mean(), np.percentile(r[:, k], 50), np.percentile(r[:, k], 90), np.percentile(r[:, k], 99), r[:, k].max()))
    if png:
        from PIL import Image
        os.makedirs(png, exist_ok=True)
        order = sorted(rows, key=lambda x: -x[4])[:worst]
        for i, dy, dx, j, bj, m in order:
            a = frames[i - 1].astype(np.float32)
            b = frames[i].astype(np.float32)
            oa, ob = overlap(a, b, dy, dx)
            diff = np.clip(np.abs(ob - oa) * 4, 0, 255)
            im = np.concatenate([oa, ob, diff], axis=1).astype(np.uint8)
            Image.fromarray(im).save(os.path.join(png, "%s-%04d.png" % (os.path.basename(run.rstrip("/")), i)))
        print("  worst pairs (frame, shift, bright ppm):", ", ".join("%d (%d,%d) %.0f" % (x[0], x[1], x[2], x[4]) for x in order))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--png")
    ap.add_argument("--worst", type=int, default=4)
    ap.add_argument("--skip", type=int, default=1)
    a = ap.parse_args()
    for run in a.runs:
        analyse(run, a.skip, a.png, a.worst)


if __name__ == "__main__":
    main()
