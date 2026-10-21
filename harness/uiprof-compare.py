#!/usr/bin/env python3
"""Per-scene comparison of UI rig runs (ui_script=latency or scenes): the UI's cost from cost runs and the stale share
from devUiTruth runs, side by side (2026-10-14, the UI latency pass, docs/findings-ui-latency-2026-10-14.md).

  uiprof-compare.py --cost stock=RUN --cost prev=RUN --cost new=RUN --truth new=RUN --truth nocap=RUN [--md]

A cost column: the UI's share of the frame (UIManager.update + render, uiprof.py's scene table), UI frames per second and
fps. A truth column: the share of frames on which some top-level element on screen differs from the truth (every element
fresh every frame), for the uiRetained schedule of that run; the first truth run also gives stock's simulated 60 Hz
schedule ("stock*"). RUN is a run dir or a label prefix under harness/runs (newest match).
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent


def run_dir(s):
    p = Path(s)
    if p.is_dir():
        return p
    m = sorted((HERE / "runs").glob(s + "-2*"))
    if not m:
        sys.exit(f"no run for {s}")
    return m[-1]


def uiprof(run):
    with tempfile.NamedTemporaryFile(suffix=".json") as f:
        txt = subprocess.run([sys.executable, str(HERE / "uiprof.py"), str(run), "--all", "--json", f.name],
                             capture_output=True, text=True).stdout
        return json.load(open(f.name)), txt


def stale_table(txt):
    out = {}
    on = False
    for l in txt.splitlines():
        if "stale ret %" in l:
            on = True
            continue
        p = l.split()
        if on and len(p) == 4:
            try:
                out[p[0]] = (float(p[2]), float(p[3]))
            except ValueError:
                pass
    return out


ap = argparse.ArgumentParser()
ap.add_argument("--cost", action="append", default=[])
ap.add_argument("--truth", action="append", default=[])
ap.add_argument("--md", action="store_true", help="a Markdown table")
a = ap.parse_args()
cost = [(k, uiprof(run_dir(v))[0]) for k, v in (x.split("=", 1) for x in a.cost)]
truth = [(k, stale_table(uiprof(run_dir(v))[1])) for k, v in (x.split("=", 1) for x in a.truth)]
scenes = []
for _, d in cost:
    for s in d.get("scenes", {}):
        if s not in scenes:
            scenes.append(s)
for _, t in truth:
    for s in t:
        if s not in scenes and s != "-":
            scenes.append(s)
cols = [f"UI % {k}" for k, _ in cost] + [f"UIfr/s {k}" for k, _ in cost] + [f"fps {k}" for k, _ in cost]
if truth:
    cols += ["stale % stock*"] + [f"stale % {k}" for k, _ in truth]
rows = []
for s in scenes:
    r = [s]
    r += [f"{d['scenes'].get(s, {}).get('ui_pct', float('nan')):.2f}" for _, d in cost]
    r += [f"{d['scenes'].get(s, {}).get('ui_frames_per_s', float('nan')):.0f}" for _, d in cost]
    r += [f"{d['scenes'].get(s, {}).get('fps', float('nan')):.0f}" for _, d in cost]
    if truth:
        r += [f"{truth[0][1].get(s, (float('nan'),) * 2)[1]:.1f}"] + [f"{t.get(s, (float('nan'),) * 2)[0]:.1f}" for _, t in truth]
    rows.append(r)
if a.md:
    print("| scene | " + " | ".join(cols) + " |")
    print("|---" * (len(cols) + 1) + "|")
    for r in rows:
        print("| " + " | ".join(r) + " |")
else:
    w = [max(len(c), 7) for c in cols]
    print(f"{'scene':20s} " + " ".join(f"{c:>{n}s}" for c, n in zip(cols, w)))
    for r in rows:
        print(f"{r[0]:20s} " + " ".join(f"{x:>{n}s}" for x, n in zip(r[1:], w)))
