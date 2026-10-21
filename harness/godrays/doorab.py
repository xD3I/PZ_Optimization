#!/usr/bin/env python3
"""God rays through a door the harness toggles (find=sundoor, pzopt.DoorRig), from a devCapture sequence.

    doorab.py <run> [--out DIR] [--box x0,y0,x1,y1] [--gain 6] [--settle MS]

The frames are split by the door's state (the "harness: door toggled open|closed epoch_ms=" lines; frames within --settle
ms after a toggle are the transition, kept apart) and, with devGodRaysDoorAlternate (else devGodRaysAlternate), by that half (on / off). Writes:
  closed.png / open.png           the mean frame of each door state (god rays on frames when alternating);
  contrib-open.png, -closed.png   with the alternation: mean(on) - mean(off) of that door state as a heat picture over the
                                  dimmed off frame (red brighter, blue darker, x gain): what the god rays add;
  trans-<k>.png                   the frames of the first opening's transition, one by one (the shaft coming in);
and prints, for the box (capture pixels; default the whole frame), the mean god ray brightening per door state and per
transition frame (ms after the toggle): the latency and the ramp of the shaft.
"""
import argparse
import os
import re
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ppl"))
import capture  # noqa: E402


def events(run):
    toggles, per, t0 = [], None, None
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        m = re.search(r"harness: door toggled (open|closed) epoch_ms=(\d+)", line)
        if m:
            toggles.append((int(m.group(2)), m.group(1) == "open"))
        m = re.search(r"god rays: (door features )?alternating every (\d+) ms from epoch_ms (\d+)", line)
        if m and (per is None or m.group(1)):
            per, t0 = int(m.group(2)), int(m.group(3))  # (the door features' alternation wins over the god rays')
    return toggles, per, t0


def heat(d, base, gain):
    img = np.repeat(base[..., None], 3, 2)
    pos, neg = np.clip(d * gain, 0, 255), np.clip(-d * gain, 0, 255)
    img[..., 0] += pos
    img[..., 2] += neg
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--out", default="/tmp/grdoor")
    ap.add_argument("--box")
    ap.add_argument("--gain", type=float, default=6.0)
    ap.add_argument("--settle", type=int, default=600)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    fr, st = capture.load(a.run)
    toggles, per, t0 = events(a.run)
    if not toggles:
        sys.exit("no door toggles in console.txt")
    h, w = fr.shape[1:3]
    x0, y0, x1, y1 = map(int, a.box.split(",")) if a.box else (0, 0, w, h)

    def state(s):
        last = None
        for t, op in toggles:
            if t <= s:
                last = (t, op)
        if last is None:
            return None, None, False  # before the first toggle: closed
        return last[1], s - last[0], True

    def half(s):
        if per is None:
            return True
        ph = (s - t0) % per
        if ph < 120 or ph > per - 120:
            return None
        return ((s - t0) // per) % 2 == 0

    groups = {}
    for i, s in enumerate(st):
        op, since, any_ = state(s)
        if not any_:
            continue  # before the first toggle: the scene still settling
        if since < a.settle:
            continue
        hv = half(s)
        if hv is None:
            continue
        groups.setdefault((op, hv), []).append(i)
    mean = lambda idx: sum(fr[i].astype(np.float32) for i in idx) / len(idx)
    for op in (False, True):
        name = "open" if op else "closed"
        on = groups.get((op, True), [])
        if on:
            Image.fromarray(mean(on).astype(np.uint8)).save(os.path.join(a.out, f"{name}.png"))
        off = groups.get((op, False), [])
        if per is not None and on and off:
            mo, mf = mean(on), mean(off)
            d = (mo - mf).mean(2)
            heat(d, mf.mean(2) * 0.45, a.gain).save(os.path.join(a.out, f"contrib-{name}.png"))
            b = d[y0:y1, x0:x1]
            print(f"door {name}: {len(on)} on / {len(off)} off frames; god rays in the box: mean {b.mean():+.2f}, "
                  f"brightened >6: {(b > 6).mean() * 100:.1f} %, max {b.max():.0f}")
        elif on:
            print(f"door {name}: {len(on)} frames (no alternation)")
    if per is None and groups.get((False, True)) and groups.get((True, True)):
        d = (mean(groups[(True, True)]) - mean(groups[(False, True)])).mean(2)
        heat(d, mean(groups[(False, True)]).mean(2) * 0.45, a.gain).save(os.path.join(a.out, "open-minus-closed.png"))
        b = d[y0:y1, x0:x1]
        print(f"open - closed in the box: mean {b.mean():+.2f}, brightened >6: {(b > 6).mean() * 100:.1f} %")
    # the first opening's transition, frame by frame against the last closed frame before it
    for t, op in toggles:
        if not op:
            continue
        before = [i for i, s in enumerate(st) if s < t]
        after = [i for i, s in enumerate(st) if t <= s < t + 1500]
        if not before or not after:
            continue
        ref = fr[before[-1]].astype(np.float32)
        print(f"opening at {t}: frames after the toggle (box mean change against the last closed frame):")
        for k, i in enumerate(after):
            d = (fr[i].astype(np.float32) - ref).mean(2)[y0:y1, x0:x1]
            print(f"  +{st[i] - t:5d} ms  {d.mean():+6.2f}  brightened >6: {(d > 6).mean() * 100:5.1f} %")
            if k < 12:
                Image.fromarray(fr[i]).crop((x0, y0, x1, y1)).save(os.path.join(a.out, f"trans-{k:02d}.png"))
        break


if __name__ == "__main__":
    main()
