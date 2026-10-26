#!/usr/bin/env python3
"""Torch shadows loop (2026-10-26): frame time per dev cost mask of a devPplAlternate run, from pzopt-overlay.out.

maskft.py <run> [--skip-ms 150]
Each frame takes the mask in force at its epoch (console `pixel light: dev cost mask M epoch_ms=E`); the first --skip-ms
after every flip are left out (the flip's own frame, program variants compiling). Prints per mask: frames, mean, p50, p99
of the presented frame time and the overlay's gpu_ms (0 where the platform has no GPU timer, e.g. macOS).
For machines without GPU timer queries (the Mac) this is the cost comparison.
"""
import argparse, csv, os, re
import numpy as np

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--skip-ms", type=float, default=150.0)
a = ap.parse_args()
flips = []
for line in open(os.path.join(a.run, "console.txt"), errors="replace"):
    m = re.search(r"dev cost mask (\d+) epoch_ms=(\d+)", line)
    if m:
        flips.append((int(m.group(2)), int(m.group(1))))
flips.sort()
if not flips:
    raise SystemExit("no dev cost mask lines (not a devPplAlternate run)")
rows = list(csv.DictReader(open(os.path.join(a.run, "pzopt-overlay.out"))))
per = {}
for r in rows:
    try:
        e = float(r["epoch_ms"]); ft = float(r["frametime"]); g = float(r.get("gpu_ms") or 0)
    except (KeyError, ValueError):
        continue
    k = None
    for i, (fe, mask) in enumerate(flips):
        if fe <= e:
            k = i
        else:
            break
    if k is None or e - flips[k][0] < a.skip_ms:
        continue
    per.setdefault(flips[k][1], []).append((ft, g))
for mask in sorted(per):
    v = np.array(per[mask])
    print(f"mask {mask:>9}: {len(v):5d} frames  frametime mean {v[:,0].mean():6.2f} p50 {np.percentile(v[:,0],50):6.2f} p99 {np.percentile(v[:,0],99):6.2f} ms  gpu_ms mean {v[:,1].mean():6.2f}")
