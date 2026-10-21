#!/usr/bin/env python3
"""Stacks of one or more frames from a slowframes dump: drill.py <run> <offset_s>[,<offset_s>...] [--thread MainThread] [--depth 14] [--top 12]
Offsets are seconds after the route start, as slowframes.py --list prints them (the frame starting nearest each)."""
import argparse, collections, sys
from pathlib import Path
ap = argparse.ArgumentParser()
ap.add_argument("run"); ap.add_argument("offsets")
ap.add_argument("--thread", default="MainThread"); ap.add_argument("--depth", type=int, default=14); ap.add_argument("--top", type=int, default=12)
ap.add_argument("--skip", default="java.,jdk.,sun.,org.lwjgl")
a = ap.parse_args()
run = Path(a.run)
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A = int(kv["route_start_epoch_ms"]) * 1_000_000
rows = [list(map(int, l.split())) for l in (run / "pzopt-pacing.out").read_text().splitlines() if l and l[0] != "#"]
off = max(r[0] * 1_000_000 - r[4] for r in rows if r[4])
starts = [r[1] + off for r in rows if r[1]]
dump = Path.home() / ".cache/pzopt-slowframes" / (run.resolve().name + ".dump")
stacks, samples = {}, []
for l in dump.open():
    if l[0] == "s":
        _, i, s = l.rstrip("\n").split(" ", 2); stacks[int(i)] = s
    elif l[0] == "x":
        _, k, th, t, i, w = l.split()
        if th == a.thread and k == "c":
            samples.append((int(t), int(i)))
skip = tuple(a.skip.split(","))
for o in a.offsets.split(","):
    t = A + float(o) * 1e9
    i = min(range(len(starts) - 1), key=lambda j: abs(starts[j] - t))
    s0, s1 = starts[i], starts[i + 1]
    c = collections.Counter()
    n = 0
    for ts, sid in samples:
        if s0 <= ts < s1:
            fr = [f for f in stacks[sid].split(";") if not f.startswith(skip)]
            # cut at GameWindow.frameStep
            for j, f in enumerate(fr):
                if f.endswith("frameStep"):
                    fr = fr[j + 1:]; break
            c[";".join(x.split(".")[-2] + "." + x.split(".")[-1] if "." in x else x for x in fr[-a.depth:])] += 1
            n += 1
    print(f"== +{(s0 - A) / 1e9:.3f}s step {(s1 - s0) / 1e6:.1f} ms, {n} {a.thread} CPU samples")
    for k, v in c.most_common(a.top):
        print(f"  {v:4}  {k}")
