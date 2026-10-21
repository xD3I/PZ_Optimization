#!/usr/bin/env python3
"""Chunk-texture bake cost per frame from an instrumented run (pzopt.BakeLog timing rows in pzopt-bakes.out, 2026-10-12).

    harness/bake-times.py <run> [<run> ...] [--all]

For the route window (pzopt-bench.out route_start/end_epoch_ms; --all = the whole run) and every game step
(pzopt-pacing.out sim_ns, joined as frame-causes.py joins the bake census):
  bake ms      game-thread time of the levels baked in that frame (renderOneLevel of a dirty level, start to end)
  max level    the longest single level bake of the frame
  pre / post   the passes around the chunk loop that scale with the bakes (scheduler plan, bake preparation, occlusion,
               chunk lighting; AO flush, relief flush, pixel-light lattice)
  rt ms        render-thread submission of the frame's bakes (time between each baked level's begin / end markers)
  step         the game step; "extra" = step minus the median step of frames that baked nothing
"""
import argparse, bisect, collections, statistics
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("runs", nargs="+")
ap.add_argument("--all", action="store_true", help="whole run instead of the route window")
ap.add_argument("--block", type=int, default=8, help="devBakeHoldFrames block length of the run")
a = ap.parse_args()

PH = ["start", "info", "floor", "objects", "rest"]
KINDS = ["create", "object", "cutaway", "trees", "redraw", "lighting", "other"]
PRE = ["sched", "prepare", "occlusion", "lighting"]
POST = ["ao", "relief", "ppl"]


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q / 100 * len(v)))] if v else float("nan")


def stats(v):
    return f"p50 {pct(v,50):6.2f}  p90 {pct(v,90):6.2f}  p99 {pct(v,99):6.2f}  max {max(v) if v else float('nan'):6.2f}"


for runarg in a.runs:
    run = Path(runarg)
    kv = dict(l.split("=", 1) for l in (run / "pzopt-bench.out").read_text().splitlines() if "=" in l)
    A, B = int(kv.get("route_start_epoch_ms", 0)), int(kv.get("route_end_epoch_ms", 1 << 62))
    prow = []
    for l in (run / "pzopt-pacing.out").read_text().splitlines():
        if l.startswith("#"):
            continue
        p = list(map(int, l.split()))
        prow.append(p)
    steps = [r[1] for r in prow]
    t = []  # (ns, frameNo, fields)
    bt = collections.defaultdict(list)  # t-row index -> bt rows
    rb = collections.defaultdict(list)  # frameNo -> render ns per level
    ra = []
    cur = -1
    for l in (run / "pzopt-bakes.out").read_text().splitlines():
        if l.startswith("t "):
            f = list(map(int, l.split()[1:]))
            t.append(f)
            cur = len(t) - 1
        elif l.startswith("bt ") and cur >= 0:
            bt[cur].append(list(map(int, l.split()[1:])))
        elif l.startswith("rb "):
            f = l.split()
            rb[int(f[1])].append(int(f[2]))
        elif l.startswith("ra "):
            f = list(map(int, l.split()[1:]))
            ra.append(f)  # startNs ns kind (chunk AO jobs; kinds 1 / 4 run after the bakes, outside the bake markers)
    if not t:
        print(f"== {run.name}: no timing rows (build without bake timing?)")
        continue
    tns = [r[0] for r in t]
    ra.sort()
    rans = [x[0] for x in ra]
    frames = []
    for i in range(0, len(prow) - 2):
        e = prow[i][0]
        if not a.all and not (A <= e <= B):
            continue
        s1, s2 = prow[i + 1][1], prow[i + 2][1]
        j = bisect.bisect_left(tns, s1)
        if j >= len(t) or t[j][0] >= s2:
            continue
        r = t[j]
        step = (prow[i + 1][1] - prow[i][1]) / 1e6
        # t: ns frameNo loopNs bakeNs bakeMaxNs levels cleanNs pre0..3 post0..2 kind0..6
        frameNo, loop, bake, bmax, lv, clean = r[1], r[2] / 1e6, r[3] / 1e6, r[4] / 1e6, r[5], r[6] / 1e6
        pre = [x / 1e6 for x in r[7:11]]
        post = [x / 1e6 for x in r[11:14]]
        kinds = [x / 1e6 for x in r[14:21]]
        rt = [x / 1e6 for x in rb.get(frameNo, [])]
        ph = [0.0] * 5
        for b in bt.get(j, []):
            for k in range(5):
                ph[k] += b[6 + k] / 1e6
        hold = r[21] if len(r) > 21 else -1
        e, sim, acq, sc = prow[i][0], prow[i][1], prow[i][2], prow[i][3]
        lo, hi = bisect.bisect_left(rans, acq), bisect.bisect_right(rans, sc)
        aoOut = sum(x[1] for x in ra[lo:hi] if x[2] in (1, 4)) / 1e6  # AO jobs after the bakes in this frame's render submit
        aoAll = sum(x[1] for x in ra[lo:hi]) / 1e6
        frames.append(dict(fn=frameNo, aoOut=aoOut, aoAll=aoAll, step=step, loop=loop, bake=bake, bmax=bmax, lv=lv, clean=clean, pre=pre, post=post, kinds=kinds,
                           rt=sum(rt), rtmax=max(rt) if rt else 0.0, ph=ph, hold=hold, submit=(sc - acq - prow[i][5]) / 1e6,
                           iv=(prow[i][4] - prow[i - 1][4]) / 1e6 if i > 0 else 0.0))
    n = len(frames)
    if not n:
        print(f"== {run.name}: no frames in the window")
        continue
    zero = [f["step"] for f in frames if f["lv"] == 0]
    base = statistics.median(zero) if zero else float("nan")
    print(f"== {run.name}: {n} frames{' (whole run)' if a.all else ' in the route window'}, {sum(f['lv'] for f in frames)} levels baked")
    print(f"  game step      {stats([f['step'] for f in frames])}")
    print(f"  bake ms/frame  {stats([f['bake'] for f in frames])}   (game thread, baked levels)")
    print(f"  bake+pre+post  {stats([f['bake'] + sum(f['pre']) + sum(f['post']) for f in frames])}")
    print(f"  max level ms   {stats([f['bmax'] for f in frames])}")
    print(f"  chunk loop     {stats([f['loop'] for f in frames])}   (clean levels {statistics.mean(f['clean'] for f in frames):.2f} ms avg)")
    print(f"  rt bake ms     {stats([f['rt'] for f in frames])}   (render thread, max level {max(f['rtmax'] for f in frames):.2f})")
    if ra:
        print(f"  rt AO jobs     {stats([f['aoAll'] for f in frames])}   (render thread, all chunk AO jobs; after the bakes: p99 {pct([f['aoOut'] for f in frames], 99):.2f})")
        print(f"  rt bake + AO   {stats([f['rt'] + f['aoOut'] for f in frames])}   (render thread, bake markers + AO jobs after them)")
    print(f"  bake total     {stats([f['bake'] + f['post'][0] + f['post'][1] + f['rt'] + f['aoOut'] for f in frames])}   (game-thread bakes + AO / relief flush + render-thread bakes + AO after them)")
    tot = lambda key, k: sum(f[key][k] for f in frames) / n
    print("  per frame avg  pre: " + " ".join(f"{PRE[k]}={tot('pre', k):.2f}" for k in range(4))
          + "  post: " + " ".join(f"{POST[k]}={tot('post', k):.2f}" for k in range(3)))
    print("  bake phases    " + " ".join(f"{PH[k]}={tot('ph', k):.2f}" for k in range(5))
          + "  kinds: " + " ".join(f"{KINDS[k]}={tot('kinds', k):.2f}" for k in range(7) if tot('kinds', k) > 0.005))
    print(f"  steps of frames with 0 levels: median {base:.2f} ms ({len(zero)} frames)")
    print("  levels   frames  step   extra  bake  pre+post  rt   (ms, means)")
    buckets = [(0, 0), (1, 3), (4, 7), (8, 11), (12, 19), (20, 29), (30, 10 ** 6)]
    for lo, hi in buckets:
        fs = [f for f in frames if lo <= f["lv"] <= hi]
        if not fs:
            continue
        m = lambda g: statistics.mean(g(f) for f in fs)
        lab = f"{lo}" if lo == hi else (f"{lo}-{hi}" if hi < 10 ** 6 else f"{lo}+")
        print(f"  {lab:>6} {len(fs):8} {m(lambda f: f['step']):6.2f} {m(lambda f: f['step']) - base:+6.2f} {m(lambda f: f['bake']):5.2f}"
              f" {m(lambda f: sum(f['pre']) + sum(f['post'])):8.2f} {m(lambda f: f['rt']):5.2f}")
    if any(f["hold"] >= 0 for f in frames):
        # devBakeHoldFrames: blocks of frames that bake (0) and that hold every bake but the must class (1), same scene
        print("  devBakeHoldFrames blocks (bake = 0, hold = 1):")
        for h in (0, 1):
            fs = [f for f in frames if f["hold"] == h]
            if fs:
                m = lambda g: statistics.mean(g(f) for f in fs)
                print(f"    hold={h} frames {len(fs):4}  levels {m(lambda f: f['lv']):5.1f}  step {m(lambda f: f['step']):6.2f} (p90 {pct([f['step'] for f in fs], 90):6.2f})"
                      f"  interval {m(lambda f: f['iv']):6.2f}  render submit {m(lambda f: f['submit']):6.2f}  bake {m(lambda f: f['bake']):5.2f}  rt bake {m(lambda f: f['rt']):5.2f}")
        # paired: each bake block against the hold blocks right before / after it (same stretch of the route)
        blocks = collections.defaultdict(list)
        for f in frames:
            if f["hold"] >= 0:
                blocks[f["fn"] // a.block].append(f)
        d = collections.defaultdict(list)
        for b, fs in blocks.items():
            if fs[0]["hold"] != 0:
                continue
            nb = [x for k in (b - 1, b + 1) for x in blocks.get(k, []) if x["hold"] == 1]
            if not nb:
                continue
            for key in ("step", "iv", "submit", "lv"):
                d[key].append(statistics.mean(x[key] for x in fs) - statistics.mean(x[key] for x in nb))
        if d["step"]:
            se = lambda v: statistics.stdev(v) / len(v) ** 0.5 if len(v) > 1 else float("nan")
            print(f"    paired ({len(d['step'])} bake blocks vs their neighbouring hold blocks): step {statistics.mean(d['step']):+.2f} +- {se(d['step']):.2f} ms,"
                  f" interval {statistics.mean(d['iv']):+.2f} +- {se(d['iv']):.2f}, render submit {statistics.mean(d['submit']):+.2f} +- {se(d['submit']):.2f},"
                  f" levels {statistics.mean(d['lv']):+.1f}")
            for pos in range(a.block):
                x = [f["step"] for f in frames if f["hold"] == 0 and f["fn"] % a.block == pos]
                y = [f["step"] for f in frames if f["hold"] == 1 and f["fn"] % a.block == pos]
                if x and y:
                    print(f"      frame {pos} of the block: bake {statistics.mean(x):6.2f}  hold {statistics.mean(y):6.2f}  ({statistics.mean(x) - statistics.mean(y):+.2f})")
    print("  bake ms  frames  step   extra")
    for lo, hi in [(0, 0.001), (0.001, 2), (2, 5), (5, 10), (10, 15), (15, 1e9)]:
        fs = [f for f in frames if lo <= f["bake"] < hi]
        if fs:
            st = statistics.mean(f["step"] for f in fs)
            print(f"  {lo:>4}-{hi if hi < 1e9 else 'inf':<5} {len(fs):6} {st:6.2f} {st - base:+6.2f}")
