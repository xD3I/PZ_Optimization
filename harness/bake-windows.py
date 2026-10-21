#!/usr/bin/env python3
"""Where the frames that bake many chunk levels spend their extra time (async-profiler wall samples, 2026-10-12).

    harness/bake-windows.py <run> [--heavy 12] [--light 3] [--depth 6] [--top 30] [--thread main|MainThread|both]

Needs <run>/asprof.jfr (run.sh --asprof event=cpu,interval=2ms,wall=2ms) and the bake timing rows of pzopt-bakes.out
(pzopt.BakeLog). Each game step of the route window is classed by the levels it baked (heavy >= --heavy, light <= --light);
the render thread's submit window (acquire -> swap call) and the game thread's step window of those frames are handed to
harness/jfr-windows.java, which folds the samples per class: the stacks that grow in the heavy frames are the cost of a burst.
"""
import argparse, bisect, os, subprocess, tempfile
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--heavy", type=int, default=12)
ap.add_argument("--light", type=int, default=3)
ap.add_argument("--depth", type=int, default=6)
ap.add_argument("--top", type=int, default=30)
ap.add_argument("--thread", default="both")
ap.add_argument("--inside-bakes", action="store_true", help="render thread inside each baked level's markers (slow levels > 2 ms vs the rest)")
ap.add_argument("--diff", action="store_true", help="print the anchor frames' ms per window by class and their difference instead of the raw folds")
ap.add_argument("--interval-ms", type=float, default=2.0, help="the asprof wall interval")
ap.add_argument("--by-hold", action="store_true", help="devBakeHoldFrames run: heavy = the frames of bake blocks, light = the frames of hold blocks")
a = ap.parse_args()
run = Path(a.run)
kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
A, B = int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
rows, off = [], None
for l in (run / "pzopt-pacing.out").read_text().splitlines():
    if l.startswith("#"):
        continue
    p = list(map(int, l.split()))
    o = p[0] * 1_000_000 - p[4]
    off = o if off is None or o > off else off
    rows.append(p)
t = [list(map(int, l.split()[1:])) for l in (run / "pzopt-bakes.out").read_text().splitlines() if l.startswith("t ")]
tns = [r[0] for r in t]
win = {"main": [], "MainThread": []}
counts = {"heavy": 0, "light": 0}
for i in range(1, len(rows) - 2):
    if not (A <= rows[i][0] <= B):
        continue
    s1, s2 = rows[i + 1][1], rows[i + 2][1]
    j = bisect.bisect_left(tns, s1)
    if j >= len(t) or t[j][0] >= s2:
        continue
    lv = t[j][5]
    tag = "heavy" if lv >= a.heavy else ("light" if lv <= a.light else None)
    if a.by_hold:
        h = t[j][21] if len(t[j]) > 21 else -1
        tag = "heavy" if h == 0 else ("light" if h == 1 else None)  # devBakeHoldFrames: bake blocks vs hold blocks
    if not tag:
        continue
    counts[tag] += 1
    e, sim, acq, sc, sr, w, gd, _ = rows[i]
    win["main"].append((tag, acq + off, sc + off))
    win["MainThread"].append((tag, sim + off, rows[i + 1][1] + off))
if a.inside_bakes:
    # the render thread between each baked level's begin / end markers (rb rows with their start ns): levels over 2 ms vs the rest
    win["main"] = []
    for l in (run / "pzopt-bakes.out").read_text().splitlines():
        if l.startswith("rb "):
            f = list(map(int, l.split()[1:]))
            if len(f) >= 7:
                win["main"].append(("slowlevel" if f[1] > 2_000_000 else "level", f[6] + off, f[6] + f[1] + off))
        elif l.startswith("ra "):
            f = list(map(int, l.split()[1:]))  # startNs ns kind: AO jobs after the bakes (kinds 1, 4) over 2 ms
            if f[2] in (1, 4) and f[1] > 2_000_000:
                win["main"].append(("slowaojob", f[0] + off, f[0] + f[1] + off))
    threads_override = ["main"]
print(f"== {run.name}: {counts['heavy']} heavy frames (>= {a.heavy} levels), {counts['light']} light (<= {a.light})")
here = Path(__file__).resolve().parent
threads = ["main"] if a.inside_bakes else (["main", "MainThread"] if a.thread == "both" else [a.thread])
for th in threads:
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        for tag, s, e in win[th]:
            f.write(f"{tag} {s} {e}\n")
    print(f"\n#### {'render thread (main)' if th == 'main' else 'game thread (MainThread)'}: {'submit' if th == 'main' else 'step'} windows")
    r = subprocess.run(["java", str(here / "jfr-windows.java"), str(run / "asprof.jfr"), f.name, th, "wall", str(a.depth), str(a.top)],
                       capture_output=True, text=True)
    os.unlink(f.name)
    if not a.diff:
        print(r.stdout + r.stderr[-2000:])
        continue
    # --diff: every anchor frame's ms per window in each class (share x samples per window x 2 ms) and the difference
    import re
    cls, sec, out = None, None, {}
    for l in r.stdout.splitlines():
        m = re.match(r"== (\w+): (\d+) windows, (\d+) samples", l.strip())
        if m:
            cls = m.group(1)
            out[cls] = {"w": int(m.group(2)), "s": int(m.group(3)), "a": {}}
            continue
        if "frames anywhere" in l:
            sec = "a"
            continue
        if "stacks (leaf" in l:
            sec = "s"
            continue
        m = re.match(r"\s+([\d.]+)%\s+(.*)", l)
        if m and cls and sec == "a":
            out[cls]["a"][m.group(2).strip()] = float(m.group(1))
    names = sorted(out)
    if len(names) < 2:
        print(r.stdout + r.stderr[-2000:])
        continue
    h, lt = out.get("heavy", out[names[0]]), out.get("light", out[names[1]])
    mh, ml = h["s"] / max(1, h["w"]) * a.interval_ms, lt["s"] / max(1, lt["w"]) * a.interval_ms
    print(f"  ms per window: heavy {mh:.2f} ({h['w']} windows), light {ml:.2f} ({lt['w']}); anchors by difference:")
    rows = []
    for k in set(h["a"]) | set(lt["a"]):
        x, y = h["a"].get(k, 0) / 100 * mh, lt["a"].get(k, 0) / 100 * ml
        rows.append((x - y, k, x, y))
    for d, k, x, y in sorted(rows, reverse=True)[:a.top]:
        print(f"  {d:+6.2f}  heavy {x:5.2f}  light {y:5.2f}  {k[:100]}")
