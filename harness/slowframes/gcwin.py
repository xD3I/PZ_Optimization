#!/usr/bin/env python3
"""tail2: the G1/ZGC pauses inside each run's route window: gcwin.py <run>... (gc.log + pzopt-bench.out)"""
import sys, re, datetime
from pathlib import Path
for r in sys.argv[1:]:
    r = Path(r)
    kv = dict(l.split("=", 1) for l in (r / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    a = int(kv["route_start_epoch_ms"]) / 1000.0
    secs = float(kv.get("route_seconds", 25) or 25)
    out = []
    for l in (r / "gc.log").read_text().splitlines():
        m = re.match(r"\[(\S+?)\]\[[\d.]+s\] GC\(\d+\) (Pause \S+(?: \([^)]*\))*) .*?([\d.]+)ms$", l)
        if not m:
            continue
        t = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp()
        if a <= t <= a + secs:
            kind = "Young" if "Young" in m.group(2) else m.group(2).split()[1]
            out.append((t - a, kind, float(m.group(3))))
    ms = [x[2] for x in out]
    print(f"{r.name[:34]:34} {len(out):2} pauses, sum {sum(ms):6.1f} ms, max {max(ms) if ms else 0:5.1f}, >16.7: {sum(1 for x in ms if x > 16.7)}  |  "
          + " ".join(f"{t:.1f}s:{d:.0f}" for t, k, d in out))
