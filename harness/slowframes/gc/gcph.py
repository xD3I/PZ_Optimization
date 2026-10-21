#!/usr/bin/env python3
"""gcph.py <run>: per GC pause in the route window: pause ms, pre / merge / evac / post ms, ext-root max, scan-heap avg,
copy avg, copied MB, survivors (regions), old added, workers, safepoint time-to-safepoint."""
import re, sys, datetime
from pathlib import Path
for r in sys.argv[1:]:
    r = Path(r); L = (r / "jvm.log").read_text().splitlines()
    kv = dict(l.split("=", 1) for l in (r / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    A = int(kv["route_start_epoch_ms"]) / 1000; B = int(kv["route_end_epoch_ms"]) / 1000
    gc = {}; sp = []
    for l in L:
        m = re.match(r"\[(\S+?)\]\[[\d.]+s\] (.*)", l)
        if not m: continue
        t = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp(); x = m.group(2)
        ms = re.match(r"Safepoint \"(\w+)\".*Reaching safepoint: (\d+) ns.*Total: (\d+) ns", x)
        if ms and A <= t <= B: sp.append((t, ms.group(1), int(ms.group(2)) / 1e6, int(ms.group(3)) / 1e6)); continue
        mg = re.match(r"GC\((\d+)\)\s+(.*)", x)
        if not mg: continue
        d = gc.setdefault(int(mg.group(1)), {"t": t}); y = mg.group(2)
        for k, p in (("pause", r"^Pause (.*?) [\d]+M->.* ([\d.]+)ms$"),):
            mm = re.match(p, y)
            if mm: d["kind"] = mm.group(1).replace("(G1 Evacuation Pause)", "").strip(); d["pause"] = float(mm.group(2)); d["t"] = t
        for k, p in (("pre", r"^Pre Evacuate Collection Set: ([\d.]+)ms"), ("merge", r"^Merge Heap Roots: ([\d.]+)ms"),
                     ("evac", r"^Evacuate Collection Set: ([\d.]+)ms"), ("post", r"^Post Evacuate Collection Set: ([\d.]+)ms"),
                     ("other", r"^Other: ([\d.]+)ms"),
                     ("ext", r"^Ext Root Scanning \(ms\):.*Max: ([\d.]+)"), ("scan", r"^Scan Heap Roots \(ms\):.*Avg: ([\d.]+)"),
                     ("copy", r"^Object Copy \(ms\):.*Avg: ([\d.]+)"), ("copied", r"^Copied Bytes:.*Sum: (\d+)"),
                     ("wk", r"^Object Copy \(ms\):.*Workers: (\d+)"), ("cards", r"^Scanned Cards:.*Sum: (\d+)"),
                     ("surv", r"^Survivor regions: \d+->(\d+)"), ("oldadd", r"^Old regions: (\d+)->(\d+)"), ("eden", r"^Eden regions: (\d+)->")):
            mm = re.match(p, y)
            if mm:
                d[k] = (int(mm.group(2)) - int(mm.group(1))) if k == "oldadd" else float(mm.group(1))
    print(f"== {r.name}")
    print(f"{'t':>5} {'kind':22} {'pause':>5} {'pre':>4} {'merge':>5} {'evac':>5} {'post':>4} {'extMx':>5} {'scan':>4} {'copy':>5} {'MB':>5} {'eden':>4} {'surv':>4} {'old+':>4} {'wk':>3}")
    tot = 0
    for g, d in sorted(gc.items()):
        if "pause" not in d or not A <= d["t"] <= B: continue
        f = lambda k, fmt="{:5.2f}": fmt.format(d[k]) if k in d else "    -"
        tot += d["pause"]
        print(f"{d['t'] - A:5.1f} {d['kind'][:22]:22} {d['pause']:5.2f} {f('pre','{:4.2f}')} {f('merge')} {f('evac')} {f('post','{:4.2f}')} {f('ext')} {f('scan','{:4.2f}')} {f('copy')} "
              f"{d.get('copied', 0) / 1e6:5.1f} {f('eden','{:4.0f}')} {f('surv','{:4.0f}')} {f('oldadd','{:4.0f}')} {f('wk','{:3.0f}')}")
    big = [s for s in sp if s[2] > 0.3]
    print(f"pauses sum {tot:.1f} ms; safepoints in route {len(sp)}, time-to-safepoint > 0.3 ms: {len(big)} " + " ".join(f"{s[1]}:{s[2]:.1f}" for s in big[:12]))
