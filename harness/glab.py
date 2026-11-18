#!/usr/bin/env python3
"""The GL 4.5 pass's in-run A/B (2026-11-17, pzopt.GlModern, docs/findings-gl-modern-2026-11-17.md).

usage: glab.py <run>... [--skip S]

Reads pzopt-gpusections.out (`begin_cpu_ns name gpu_ns cpu_ns`) of a run with
`--prop gpuSections=true --prop instrument=true --prop devGlAlternate=<ms>`: the sections `composite` and `vispoly` carry
`.on` / `.off` by the frame's half. Per section and half: count, GPU us and render-thread us (time between the markers'
execution on the render thread) as mean / median, and on - off with a bootstrap 95 % interval of the mean difference.
Also the whole-frame cost per half from pzopt-overlay.out (frametime, gpu) when the console names the alternation start.
"""
import argparse
import random
import re
import statistics as st
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from flamegraph import route_window  # noqa: E402


def boot(a, b, n=2000):
    r = random.Random(1)
    d = []
    for _ in range(n):
        d.append(st.fmean(r.choices(a, k=len(a))) - st.fmean(r.choices(b, k=len(b))))
    d.sort()
    return d[int(0.025 * n)], d[int(0.975 * n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--skip", type=float, default=2.0, help="seconds skipped at the route start")
    a = ap.parse_args()
    for run in a.runs:
        win = route_window(run)
        t0 = None
        m = re.search(r"gl modern: alternating .* every (\d+) ms from epoch_ms (\d+)",
                      (Path(run) / "console.txt").read_text(errors="replace"))
        period = int(m.group(1)) if m else None
        if m:
            t0 = int(m.group(2))
        vals = defaultdict(lambda: ([], []))  # name -> (gpu us, cpu us)
        p = Path(run) / "pzopt-gpusections.out"
        lines = p.read_text(errors="replace").splitlines() if p.exists() else []
        # begin_cpu_ns is System.nanoTime: map onto the route window through the share of the file (no epoch in the rows);
        # the route window is enforced by skipping the first / last rows outside it when an epoch column exists.
        for ln in lines:
            f = ln.split()
            if len(f) < 4 or not f[0].lstrip("-").isdigit():
                continue
            name = f[1]
            if not (name.endswith(".on") or name.endswith(".off")):
                continue
            g, c = vals[name]
            g.append(int(f[2]) / 1000.0)
            c.append(int(f[3]) / 1000.0)
        print(f"{run}: period {period} ms")
        bases = sorted({n.rsplit(".", 1)[0] for n in vals})
        for b in bases:
            on, off = vals.get(b + ".on"), vals.get(b + ".off")
            if not on or not off or not on[0] or not off[0]:
                continue
            for i, what in ((0, "GPU"), (1, "render thread")):
                x, y = on[i], off[i]
                lo, hi = boot(x, y)
                print(f"  {b:12s} {what:13s} on n={len(x):5d} mean {st.fmean(x):7.1f} med {st.median(x):7.1f} us | off n={len(y):5d} "
                      f"mean {st.fmean(y):7.1f} med {st.median(y):7.1f} us | on-off {st.fmean(x) - st.fmean(y):+7.1f} us [{lo:+.1f}, {hi:+.1f}]")
        # whole frames by half from the overlay log
        ov = Path(run) / "pzopt-overlay.out"
        if t0 and period and ov.exists() and win:
            rows = ov.read_text(errors="replace").splitlines()
            hdr = rows[0].split(",") if rows else []
            ift = hdr.index("frametime") if "frametime" in hdr else None
            igpu = next((i for i, h in enumerate(hdr) if h.strip() in ("gpu_ms", "gpu_time")), None)
            halves = {True: ([], []), False: ([], [])}
            for r in rows[1:]:
                f = r.split(",")
                if not f[-1].strip().isdigit():
                    continue
                t = int(f[-1])
                if t < win[0] + a.skip * 1000 or t > win[1]:
                    continue
                per = (t - t0) // period
                if (t - t0) % period < 60:  # a frame may straddle the switch
                    continue
                on = ((per + 1) >> 1 & 1) == 0
                try:
                    halves[on][0].append(float(f[ift]))
                    if igpu is not None:
                        halves[on][1].append(float(f[igpu]))
                except (TypeError, ValueError, IndexError):
                    pass
            for k, nm in ((0, "frame ms"), (1, "gpu ms")):
                x, y = halves[True][k], halves[False][k]
                if x and y:
                    lo, hi = boot(x, y)
                    print(f"  whole frame  {nm:13s} on n={len(x):5d} mean {st.fmean(x):7.3f} | off n={len(y):5d} mean {st.fmean(y):7.3f} | "
                          f"on-off {st.fmean(x) - st.fmean(y):+.3f} [{lo:+.3f}, {hi:+.3f}]")


if __name__ == "__main__":
    main()
