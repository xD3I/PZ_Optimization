#!/usr/bin/env python3
# (copied from ground-tiling's harness/ground-tiling/bakecost.py into ground-common, 2026-10-24: the shared no-op probes use it too)
"""Bake cost of the ground patches: the GPU time of every chunk-texture bake (the "bake" pairs of pzopt-gpusections.out,
run with --prop gpuSections=true and instrument) split by the ground alternation's halves (devGroundAlternate; each
switch re-bakes the natural ground on screen, so both halves bake the same chunk levels), plus the bake count per half.

    bakecost.py RUN [--skip 0.15]

Needs the console's 'ground(?: tiling)?: clock nano= epoch_ms=' anchor and 'ground: alternating every P ms from epoch_ms T'.
"""
import argparse
import re
import statistics
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--skip", type=float, default=0.0, help="fraction of each period to skip at its start")
    a = ap.parse_args()
    run = Path(a.run)
    con = (run / "console.txt").read_text(errors="replace")
    m = re.search(r"ground(?: tiling)?: clock nano=(\d+) epoch_ms=(\d+)", con)
    alt = re.search(r"ground: alternating every (\d+) ms from epoch_ms (\d+).*?(?: cycle (\S+))?$", con, re.M)
    if not m or not alt:
        print("missing clock anchor or alternation line"); return 1
    nano0, ep0 = int(m.group(1)), int(m.group(2))
    P, T = int(alt.group(1)), int(alt.group(2))
    cyc = alt.group(3).split(",") if alt.group(3) else None
    halves = {}
    for l in (run / "pzopt-gpusections.out").read_text(errors="replace").splitlines():
        if l.startswith("#"):
            continue
        f = l.split()
        if len(f) < 3 or f[1] != "bake":
            continue
        ep = ep0 + (int(f[0]) - nano0) / 1e6
        if ep < T:
            continue
        k = int((ep - T) // P)
        if ((ep - T) % P) / P < a.skip:
            continue
        name = ("on" if k % 2 == 0 else "off") + ("-" + cyc[(k // 2) % len(cyc)] if cyc and k % 2 == 0 else "")
        halves.setdefault(name, []).append(int(f[2]) / 1000.0)
    for name in sorted(halves):
        v = halves[name]
        print(f"{name:16s} bakes {len(v):6d}  GPU us/bake mean {statistics.mean(v):7.1f}  median {statistics.median(v):7.1f}  p90 {sorted(v)[int(0.9 * (len(v) - 1))]:7.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
