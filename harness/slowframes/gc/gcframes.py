#!/usr/bin/env python3
"""gcframes.py <run>...: each GC pause in the route window (jvm.log or gc.log; log time = pause end) against the presented
frames (overlay) whose present time falls in [pause start, pause end + W ms] (W = 50: the frame queue delays presentation):
the worst frame there, the excess over the cap interval (8.33 ms) summed, and how many GC windows hold a frame > 9 / > 16.7 ms.
Baseline: the same window statistic at random (non-GC) times."""
import sys, re, datetime, random
from pathlib import Path
W = 50.0
CAP = 1000 / 120
for r in sys.argv[1:]:
    r = Path(r)
    kv = dict(l.split("=", 1) for l in (r / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    L = (r / "pzopt-overlay.out").read_text().splitlines(); h = L[0].split(",")
    it, ie = h.index("frametime"), h.index("epoch_ms")
    fr = []
    for l in L[1:]:
        p = l.split(",")
        try: fr.append((int(p[ie]), float(p[it])))
        except (ValueError, IndexError): pass
    log = r / "jvm.log" if (r / "jvm.log").exists() else r / "gc.log"
    ps = []
    for l in log.read_text().splitlines():
        m = re.match(r"\[(\S+?)\]\[[\d.]+s\] GC\(\d+\) (Pause \S+(?: \([^)]*\))*) .*?([\d.]+)ms$", l)
        if not m: continue
        t = datetime.datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() * 1000
        if A <= t <= B: ps.append((t - float(m.group(3)), t, float(m.group(3)), m.group(2).replace("Pause ", "")))
    def win(a, b):
        f = [x for e, x in fr if a <= e <= b]
        return (max(f) if f else 0, sum(max(0, x - CAP) for x in f))
    con = (r / "console.txt").read_text(errors="replace") if (r / "console.txt").exists() else ""
    sched = [int(x) for x in re.findall(r"gc slack: collect at (\d+) ms", con)]
    res = [win(a, b + W) + (d, k, any(a - 5 <= c <= b + 30 for c in sched)) for a, b, d, k in ps]
    random.seed(1); base = []
    gcw = [(a, b + W) for a, b, d, k in ps]
    while len(base) < 400:
        t = random.uniform(A, B - 60)
        if any(not (t + 60 < a or t > b) for a, b in gcw): continue
        base.append(win(t, t + 60))
    n = len(res)
    print(f"{r.name[:34]:34} pauses {n}, max {max([x[2] for x in res] or [0]):.1f} ms | windows with a frame >9: {sum(1 for x in res if x[0] > 9)} >16.7: {sum(1 for x in res if x[0] > 16.7)}, excess {sum(x[1] for x in res):.0f} ms"
          f" | random windows: >9 {100*sum(1 for x in base if x[0] > 9)/len(base):.0f} %, >16.7 {100*sum(1 for x in base if x[0] > 16.7)/len(base):.0f} %, excess/window {sum(x[1] for x in base)/len(base):.1f} ms")
    if sched:
        for lab, sub in (("scheduled", [x for x in res if x[4]]), ("natural", [x for x in res if not x[4] and x[3] != "Cleanup" and x[2] > 0.5])):
            n = max(1, len(sub))
            print(f"   {lab:9} {len(sub):2} pauses, mean {sum(x[2] for x in sub) / n:.1f} ms | windows >9: {sum(1 for x in sub if x[0] > 9)} >16.7: {sum(1 for x in sub if x[0] > 16.7)}, excess {sum(x[1] for x in sub):.0f} ms ({sum(x[1] for x in sub) / n:.1f} / pause)")
    if len(sys.argv) == 2:
        print("   " + " ".join(f"{d:.0f}->{m:.0f}{'*' if sc else ''}" for m, e, d, k, sc in res))
