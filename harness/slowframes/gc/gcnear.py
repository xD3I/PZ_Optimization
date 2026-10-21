#!/usr/bin/env python3
"""gcnear.py <run> [minframe]: around each route GC pause whose 50 ms window holds a frame > minframe (default 16.7):
the pacing rows (game step start sim, render acquire, swap call / return) relative to the pause, in ms."""
import sys, re, datetime
from pathlib import Path
r = Path(sys.argv[1]); mn = float(sys.argv[2]) if len(sys.argv) > 2 else 16.7
kv = dict(l.split("=", 1) for l in (r / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
rows = [list(map(int, l.split())) for l in (r / "pzopt-pacing.out").read_text().splitlines() if l and l[0] != "#"]
off = max(x[0] * 1_000_000 - x[4] for x in rows if x[4])  # epoch_ns - nanoTime (swap return logged at epoch_ms)
L = (r / "pzopt-overlay.out").read_text().splitlines(); h = L[0].split(","); it, ie = h.index("frametime"), h.index("epoch_ms")
fr = [(int(l.split(",")[ie]), float(l.split(",")[it])) for l in L[1:] if l.count(",") >= ie]
log = r / "jvm.log" if (r / "jvm.log").exists() else r / "gc.log"
for l in log.read_text().splitlines():
    m = re.match(r"\[(\S+?)\]\[[\d.]+s\] GC\(\d+\) (Pause \S+(?: \([^)]*\))*) .*?([\d.]+)ms$", l)
    if not m: continue
    te = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() * 1000; d = float(m.group(3)); ts = te - d
    if not A <= te <= B: continue
    w = [x for e, x in fr if ts <= e <= te + 50]
    if not w or max(w) <= mn: continue
    print(f"== pause {m.group(2)[6:40]} {d:.1f} ms at +{(ts - A) / 1000:.2f} s (end = 0); frames in window {' '.join(f'{x:.0f}' for x in w)}")
    for x in rows:
        sw = (x[4] + off) / 1e6 - te
        if -30 <= sw <= 80:
            f = lambda v: f"{(v + off) / 1e6 - te:7.1f}" if v else "      -"
            print(f"   sim {f(x[1])}  acquire {f(x[2])}  swapcall {f(x[3])}  swapret {f(x[4])}")
