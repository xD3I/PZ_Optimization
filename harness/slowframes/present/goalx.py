#!/usr/bin/env python3
"""The goal-120 numbers of runs from the in-game overlay log (presented frames, route window):
mean fps, share of frames within 9.0 ms, p50 / p90 / p99 / p99.9 / max, frames > 16.7 / > 33 ms, and the other processes' CPU.

    harness/slowframes/goal.py <run>...
"""
import sys
from pathlib import Path


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q / 100 * len(v)))] if v else float("nan")


print(f"{'run':34} {'fps':>6} {'<=9ms':>6} {'p50':>6} {'p90':>6} {'p99':>6} {'p99.9':>6} {'max':>6} {'>16.7':>6} {'>33':>4} {'ext':>5}")
for r in sys.argv[1:]:
    run = Path(r)
    kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    lines = (run / "pzopt-overlay.out").read_text().splitlines()
    h = lines[0].split(",")
    it, ie = h.index("frametime"), h.index("epoch_ms")
    ft = []
    for l in lines[1:]:
        p = l.split(",")
        try:
            e, f = int(p[ie]), float(p[it])
        except (ValueError, IndexError):
            continue
        if A <= e <= B:
            ft.append(f)
    ext = []
    try:
        L = (run / "sysmon.csv").read_text().splitlines()
        hh = L[0].split(",")
        for l in L[1:]:
            d = dict(zip(hh, l.split(",")))
            if d.get("cpu_pct") and d.get("game_cpu_pct") and A <= int(d["epoch_ms"]) <= B:
                ext.append(float(d["cpu_pct"]) * 16 / 100 - float(d["game_cpu_pct"]) / 100)
    except (OSError, KeyError, ValueError):
        pass
    n = len(ft)
    fps = 1000.0 * n / sum(ft) if ft else 0
    print(f"{run.name[:34]:34} {fps:7.2f} {100 * sum(1 for f in ft if f <= 9.0) / max(1, n):6.2f}% {pct(ft, 50):6.2f} {pct(ft, 90):6.2f} "
          f"{pct(ft, 99):7.3f} {pct(ft, 99.9):7.3f} {max(ft) if ft else 0:7.3f} {sum(1 for f in ft if f > 16.7):6} {sum(1 for f in ft if f > 33):4} "
          f"{sum(ext) / len(ext) if ext else float('nan'):5.2f}")
