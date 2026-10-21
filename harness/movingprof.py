#!/usr/bin/env python3
"""devMovingProf run: the moving pass split per category (pzopt-movingprof.out, pzopt.MovingProf).

movingprof.py <run> [--secs 2,10,20,21,24] [--top 25] [--per-second] [--all]
  Per category: render-thread ms a frame (every frame), GPU ms a frame (the sampled frames), commands a frame, sprite
  indices a frame, over the chosen route seconds (default: the Louisville horde seconds) and over the rest of the route.
  --per-second: the pass's render-thread / GPU ms per route second and its top three categories.
  --all: every frame of the file (no route window)."""
import argparse, collections, statistics
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--secs", default="2,10,20,21,24")
ap.add_argument("--top", type=int, default=25)
ap.add_argument("--per-second", action="store_true")
ap.add_argument("--all", action="store_true")
ap.add_argument("--file", default="pzopt-movingprof.out")
ap.add_argument("--mode", type=int, default=None, help="only frames of this devHordeModes mode")
a = ap.parse_args()
run = Path(a.run)

A = B = None
off = 0.0
if not a.all:
    kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    offs = []
    for l in (run / "pzopt-pacing.out").read_text().splitlines():
        if l.startswith("#"):
            continue
        p = l.split()
        try:
            offs.append(int(p[0]) * 1_000_000 - int(p[2]))
        except Exception:
            pass
    off = statistics.median(offs)

names = {}
frames = []  # (sec, gpu?, passNs, passGpu, {cat: (cpu, n, units, gpu)})
for l in (run / a.file).read_text().splitlines():
    if l.startswith("# cat "):
        _, _, i, n = l.split(" ", 3)
        names[int(i)] = n
        continue
    if l.startswith("#") or not l.strip():
        continue
    p = l.split()
    nano = int(p[0])
    if a.all:
        sec = 0
    else:
        ep = (nano + off) / 1e6
        if not (A <= ep <= B):
            continue
        sec = int((ep - A) // 1000)
    cats = {}
    mode = 0
    rest = p[4:]
    if rest and rest[0].startswith("m"):
        mode = int(rest[0][1:]); rest = rest[1:]
    for f in rest:
        v = f.split(":")
        c = int(v[0])
        cats[c] = (int(v[1]), int(v[2]), int(v[3]), int(v[4]) if len(v) > 4 else None)
    frames.append((sec, p[1] == "gpu", int(p[2]), int(p[3]), cats, mode))

if a.mode is not None:
    frames = [f for f in frames if f[5] == a.mode]
if not frames:
    raise SystemExit("no frames in the window")
hs = set(int(x) for x in a.secs.split(",")) if a.secs else set()


def table(sel, title):
    if not sel:
        return
    g = [f for f in sel if f[1]]
    pg = [f[3] for f in sel if not f[1] and f[3] >= 0]
    print(f"\n== {title}: {len(sel)} frames ({len(g)} with GPU stamps); pass render-thread {statistics.mean(f[2] for f in sel)/1e6:.2f} ms/f,"
          f" pass GPU {statistics.mean(f[3] for f in g)/1e6 if g else float('nan'):.2f} ms/f (stamped frames),"
          f" {statistics.mean(pg)/1e6 if pg else float('nan'):.2f} ms/f (two-stamp frames, median {statistics.median(pg)/1e6 if pg else float('nan'):.2f})")
    cpu = collections.Counter(); n = collections.Counter(); u = collections.Counter(); gpu = collections.Counter()
    for f in sel:
        for c, (cp, k, un, gp) in f[4].items():
            cpu[c] += cp; n[c] += k; u[c] += un
    for f in g:
        for c, (cp, k, un, gp) in f[4].items():
            gpu[c] += gp or 0
    keys = set(cpu) | set(gpu)
    order = sorted(keys, key=lambda c: -(cpu[c] / len(sel) + (gpu[c] / len(g) if g else 0)))
    print(f"{'category':42} {'RT ms/f':>8} {'GPU ms/f':>8} {'n/f':>7} {'us/cmd':>7} {'idx/f':>8}")
    for c in order[:a.top]:
        nf = n[c] / len(sel)
        print(f"{names.get(c, str(c))[:42]:42} {cpu[c]/len(sel)/1e6:8.3f} {(gpu[c]/len(g)/1e6 if g else float('nan')):8.3f} {nf:7.1f}"
              f" {(cpu[c]/n[c]/1e3 if n[c] else 0):7.2f} {u[c]/len(sel):8.0f}")


modes = sorted(set(f[5] for f in frames))
if len(modes) > 1:
    print("per devHordeModes mode (two-stamp frames): pass render-thread / GPU ms a frame, mean and median")
    for grp, title in ((lambda f: f[0] in hs, "horde secs"), (lambda f: f[0] not in hs, "other secs")):
        for m in modes:
            L = [f for f in frames if f[5] == m and grp(f) and not f[1] and f[3] >= 0]
            if L:
                print(f"  {title:10} m{m:<3d} n{len(L):5d}  RT {statistics.mean(f[2] for f in L)/1e6:6.2f} / {statistics.median(f[2] for f in L)/1e6:6.2f}"
                      f"  GPU {statistics.mean(f[3] for f in L)/1e6:6.2f} / {statistics.median(f[3] for f in L)/1e6:6.2f}")
if a.all:
    table(frames, "all frames")
else:
    table([f for f in frames if f[0] in hs], f"route seconds {sorted(hs)}")
    table([f for f in frames if f[0] not in hs], "other route seconds")
if a.per_second:
    print("\nsec frames RT_ms GPU_ms | top render-thread categories (ms/f)")
    by = collections.defaultdict(list)
    for f in frames:
        by[f[0]].append(f)
    for s in sorted(by):
        L = by[s]; g = [f for f in L if f[1]]
        tot = collections.Counter()
        for f in L:
            for c, v in f[4].items():
                tot[c] += v[0]
        top = " ".join(f"{names.get(c, c)}={t/len(L)/1e6:.2f}" for c, t in tot.most_common(3))
        print(f"{s:3d} {len(L):5d} {statistics.mean(f[2] for f in L)/1e6:6.2f} {statistics.mean(f[3] for f in g)/1e6 if g else float('nan'):6.2f} | {top}")
