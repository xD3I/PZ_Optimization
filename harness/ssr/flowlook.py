#!/usr/bin/env python3
"""reflectionsFlow look rig (2026-10-24): a devCapture run with `--prop devSsrFlowAlternate=P` (the reflections follow
the water's flow, then sit still as on 2026-09-26, every P ms) split by the console's 'ssr flow: alternating every P ms
from epoch_ms T0' clock.

    flowlook.py <run> --box x0,y0,x1,y1 [--out DIR] [--gif] [--skip 0.4] [--flow-deg A]

--box: the water the reflection lies on, in capture pixels. Per state (on / off), over the frames past the first
--skip of each period:
- wobble: mean per-pixel temporal std of the luminance in the box (how much the reflection moves and breaks);
- change: mean |frame-to-frame luminance change| (motion at the capture rate);
- axis: the dominant direction of the motion, from the structure tensor of the temporal change's spatial gradients
  (degrees, screen x right / y up; the motion runs across the gradient of the change, so axis = gradient axis + 90),
  with its coherence (0 isotropic .. 1 one direction). --flow-deg prints the angle to the water's flow axis.
Writes <out>/flow-on-<n>.png / flow-off-<n>.png (box crops, 2x nearest) and with --gif <out>/flow-onoff.gif (the box,
on beside off, the frames of one on period and the next off period at the capture rate)."""
import argparse
import math
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ppl"))
from capture import load, lum  # noqa: E402


def phases(run, stamps, skip):
    con = open(os.path.join(run, "console.txt"), errors="replace").read()
    m = re.search(r"ssr flow: alternating every (\d+) ms from epoch_ms (\d+)", con)
    if not m:
        sys.exit("no 'ssr flow: alternating' line in console.txt")
    p, t0 = int(m.group(1)), int(m.group(2))
    dt = stamps - t0
    period = dt // p
    ok = (dt % p) >= skip * p
    return period, ok


def axis(stacks):
    """dominant motion axis of (n, h, w) luminance stacks: structure tensor of the frame-to-frame change's gradient"""
    jxx = jyy = jxy = 0.0
    for stack in stacks:
        d = np.diff(stack, axis=0)
        gy, gx = np.gradient(d, axis=(1, 2))
        gy = -gy  # screen y up
        jxx, jyy, jxy = jxx + (gx * gx).sum(), jyy + (gy * gy).sum(), jxy + (gx * gy).sum()
    ang = 0.5 * math.degrees(math.atan2(2 * jxy, jxx - jyy))  # gradient axis
    coh = math.sqrt((jxx - jyy) ** 2 + 4 * jxy * jxy) / max(jxx + jyy, 1e-9)
    return (ang + 90.0 + 90.0) % 180.0 - 90.0, coh


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--box", required=True)
    ap.add_argument("--out")
    ap.add_argument("--gif", action="store_true")
    ap.add_argument("--skip", type=float, default=0.4)
    ap.add_argument("--flow-deg", type=float)
    a = ap.parse_args()
    x0, y0, x1, y1 = map(int, a.box.split(","))
    frames, stamps = load(a.run)
    frames = frames[:, y0:y1, x0:x1]
    period, ok = phases(a.run, stamps, a.skip)
    L = lum(frames.astype(np.float32))
    res = {}
    for name, par in (("on", 0), ("off", 1)):
        wob, chg, runs = [], [], []
        for k in sorted(set(period[(period % 2 == par) & ok].tolist())):
            sel = np.where((period == k) & ok)[0]
            if len(sel) < 6:
                continue
            s = L[sel]
            wob.append(s.std(axis=0).mean())
            chg.append(np.abs(np.diff(s, axis=0)).mean())
            runs.append(s)
        if not runs:
            print(name, "no whole period")
            continue
        ang, coh = axis(runs)
        res[name] = (np.mean(wob), np.mean(chg), ang, coh, sum(len(r) for r in runs))
        extra = ""
        if a.flow_deg is not None:
            diff = abs((ang - a.flow_deg + 90.0) % 180.0 - 90.0)
            extra = f" ({diff:.0f} deg off the flow axis {a.flow_deg:.0f})"
        print(f"{name:3s}: {res[name][4]} frames, wobble {res[name][0]:.2f}, change/frame {res[name][1]:.2f}, motion axis {ang:+.0f} deg "
              f"coherence {coh:.2f}{extra}")
    if "on" in res and "off" in res:
        print(f"on/off: wobble x{res['on'][0] / max(res['off'][0], 1e-6):.2f}, change x{res['on'][1] / max(res['off'][1], 1e-6):.2f}")
    if a.out:
        from PIL import Image
        os.makedirs(a.out, exist_ok=True)
        for name, par in (("on", 0), ("off", 1)):
            sel = np.where((period % 2 == par) & ok)[0]
            for j, i in enumerate(sel[len(sel) // 2:len(sel) // 2 + 3]):
                im = Image.fromarray(frames[i])
                im.resize((im.width * 2, im.height * 2), Image.NEAREST).save(os.path.join(a.out, f"flow-{name}-{j}.png"))
        if a.gif:
            ks = sorted(set(period[ok].tolist()))
            kon = next(k for k in ks if k % 2 == 0 and k + 1 in ks)
            on, off = np.where((period == kon) & ok)[0], np.where((period == kon + 1) & ok)[0]
            n = min(len(on), len(off))
            h, w = frames.shape[1:3]
            seq = []
            for i in range(n):
                c = np.zeros((h, 2 * w + 8, 3), np.uint8)
                c[:, :w] = frames[on[i]]
                c[:, w + 8:] = frames[off[i]]
                seq.append(Image.fromarray(c).quantize(colors=128, method=Image.Quantize.MEDIANCUT))
            fps = 1000.0 / max(np.diff(stamps).mean(), 1)
            seq[0].save(os.path.join(a.out, "flow-onoff.gif"), save_all=True, append_images=seq[1:], duration=int(1000 / fps), loop=0)
            print(f"gif: {n} frames, left = flow on, right = flow off -> {os.path.join(a.out, 'flow-onoff.gif')}")


if __name__ == "__main__":
    main()
