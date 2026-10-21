#!/usr/bin/env python3
"""allocx.py <run> --dump D [--depth 4] [--top 30] [--group]  TLAB-weighted alloc samples in the route window,
threads grouped (pzopt-frame-* -> frame-workers, [tid=..] -> native), per group top stack paths; --cls: by class only."""
import argparse, collections, re
from pathlib import Path
ap = argparse.ArgumentParser()
ap.add_argument("run"); ap.add_argument("--dump"); ap.add_argument("--depth", type=int, default=4); ap.add_argument("--top", type=int, default=30)
ap.add_argument("--groups", default=None); ap.add_argument("--cls", action="store_true"); ap.add_argument("--grep", default=None)
a = ap.parse_args()
run = Path(a.run)
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A = int(kv["route_start_epoch_ms"]) * 1_000_000; B = int(kv["route_end_epoch_ms"]) * 1_000_000
dur = (B - A) / 1e9
def grp(t):
    if t.startswith("pzopt-frame"): return "frame-workers"
    if t.startswith("[tid="): return "native"
    return re.sub(r"[-_#]?\d+$", "*", t)
skip = ("java.", "jdk.", "sun.", "so.", "[", "0x")
def sh(x):
    p = x.split("."); return ".".join(p[-2:]) if len(p) > 1 else x
stacks = {}; byg = collections.Counter(); byp = collections.defaultdict(collections.Counter); bycls = collections.Counter()
for l in open(a.dump):
    if l[0] == "s":
        _, i, s = l.rstrip("\n").split(" ", 2); stacks[int(i)] = s
    elif l[0] == "x":
        _, k, th, t, i, w = l.split()
        if k != "a" or not A <= int(t) <= B: continue
        w = int(w); g = grp(th); byg[g] += w
        fr = stacks[int(i)].split(";")
        cls = fr[-1][7:-1] if fr and fr[-1].startswith("[alloc") else "?"
        bycls[cls] += w
        if a.grep and not re.search(a.grep, stacks[int(i)]): continue
        app = [f for f in fr[:-1] if not f.startswith(skip)]
        key = (cls if not a.cls else "") + "  <  " + " < ".join(sh(x) for x in reversed(app[-a.depth:]))
        byp[g][cls if a.cls else key] += w
tot = sum(byg.values())
print(f"route {dur:.1f} s, total {tot / dur / 1e6:.1f} MB/s")
for g, w in byg.most_common(12): print(f"  {g:24} {w / dur / 1e6:6.1f} MB/s")
print("classes:", ", ".join(f"{c.split('.')[-1]} {w/dur/1e6:.1f}" for c, w in bycls.most_common(25)))
gs = a.groups.split(",") if a.groups else [g for g, _ in byg.most_common(4)]
for g in gs:
    print(f"== {g}")
    for k, w in byp[g].most_common(a.top): print(f"  {w / dur / 1e6:5.2f} {k[:230]}")
