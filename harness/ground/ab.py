#!/usr/bin/env python3
"""Within-run cost of the ground and grass features (2026-10-24): a run with -Dpzopt.devGroundAlternate=<ms> switches the
keys of devGroundAlternateKeys off every second period (on first). The console line
'ground: alternating every P ms from epoch_ms T (on first), keys K [cycle a,b,c]' gives the clock; this pairs each on
period with the mean of the off periods on its two sides (same route stretch, same clocks), from pzopt-overlay.out
(presented frame time, GPU busy ms, game and render thread load), and, with gpuSections=true, the ground.* GPU sections
per half (pzopt-gpusections.out, names suffixed .on / .off by pzopt.Ground.section).

    ab.py [--skip S] [--frac F] <run dir> [<run dir> ...]
        --skip S   seconds dropped after the alternation starts (default 3: the first bakes, program compiles)
        --frac F   share of each period dropped after a switch (default 0.15: the re-bake of the natural ground)

With devGroundCycle=a,b,c the pairs are split by cycle entry (period k: entry (k // 2) % n, on when k is even).
Per period the median is taken (a hitch in one period would swamp a mean); the paired differences give mean +- standard
error and their median. Every report carries the utilization (the objective: a cost that hides under an idle GPU is still
a cost at the cap): GPU busy %, game / render thread load %, fps.
"""
import os
import re
import statistics
import sys
from pathlib import Path

PAT = re.compile(r"ground: alternating every (\d+) ms from epoch_ms (\d+) \(on first\), keys (\S*)(?: cycle (\S+))?")


def load_overlay(run):
    lines = (run / "pzopt-overlay.out").read_text(errors="replace").splitlines()
    hdr = lines[0].split(",")
    ix = {k: hdr.index(k) for k in ("frametime", "gpu_ms", "game_load", "render_load", "gpu_load", "epoch_ms") if k in hdr}
    rows = []
    for l in lines[1:]:
        f = l.split(",")
        try:
            rows.append({k: float(f[i]) for k, i in ix.items()})
        except (ValueError, IndexError):
            continue
    return rows


def route_window(run):
    start = end = None
    p = run / "pzopt-bench.out"
    if p.exists():
        for l in p.read_text(errors="replace").splitlines():
            m = re.search(r"route_start_epoch_ms=(\d+)", l)
            start = int(m.group(1)) if m else start
            m = re.search(r"route_end_epoch_ms=(\d+)", l)
            end = int(m.group(1)) if m else end
    return start, end


def sections(run, P, T, frames_on, frames_off):
    """ground.* GPU sections per half: us per frame (sum of the half's pairs / the half's frames)."""
    p = run / "pzopt-gpusections.out"
    if not p.exists():
        return {}
    tot = {}
    for l in p.read_text(errors="replace").splitlines():
        if l.startswith("#"):
            continue
        f = l.split()
        if len(f) < 3 or not f[1].startswith("ground."):
            continue
        tot[f[1]] = tot.get(f[1], 0) + int(f[2])
    out = {}
    for name, ns in tot.items():
        base, _, half = name.rpartition(".")
        if half not in ("on", "off"):
            base, half = name, "all"
        n = frames_on if half == "on" else frames_off if half == "off" else frames_on + frames_off
        out.setdefault(base, {})[half] = ns / 1000.0 / max(1, n)
    return out


def analyze(run, skip_s, frac):
    run = Path(run)
    con = (run / "console.txt").read_text(errors="replace")
    m = PAT.search(con)
    if not m:
        print(f"{run.name}: no ground alternation line (run with --vmarg -Dpzopt.devGroundAlternate=<ms>)")
        return
    P, T, keys, cyc = int(m.group(1)), int(m.group(2)), m.group(3), m.group(4)
    cycle = cyc.split(",") if cyc else [None]
    pz = len(re.findall(r"\[pzopt\]", con))
    start, end = route_window(run)
    rows = load_overlay(run)
    per = {}
    first = None
    for r in rows:
        e = r["epoch_ms"]
        if e < T or (start and e < start) or (end and e > end):
            continue
        first = e if first is None else first
        if e - first < skip_s * 1000 or (e - T) % P < frac * P:
            continue
        per.setdefault(int((e - T) // P), []).append(r)
    if not per:
        print(f"{run.name}: no frames in the alternation window")
        return
    med = {k: {c: statistics.median(x[c] for x in v) for c in v[0]} | {"n": len(v)} for k, v in per.items()}
    print(f"{run.name}: ground keys '{keys}' every {P} ms, {len(per)} periods, skip {skip_s} s + {frac:.0%} of each period, [pzopt] lines {pz}")
    cols = [c for c in ("frametime", "gpu_ms", "game_load", "render_load", "gpu_load") if c in rows[0]]
    for ci, entry in enumerate(cycle):
        d = {c: [] for c in cols}
        ons, offs = [], []
        for k in sorted(med):
            if k % 2 or (k // 2) % len(cycle) != ci or k - 1 not in med or k + 1 not in med:
                continue
            ons.append(med[k])
            offs += [med[k - 1], med[k + 1]]
            for c in cols:
                d[c].append(med[k][c] - (med[k - 1][c] + med[k + 1][c]) / 2)
        if not ons:
            continue
        tag = f" [{entry}]" if entry else ""
        off_ft = statistics.fmean(o["frametime"] for o in offs)
        on_ft = statistics.fmean(o["frametime"] for o in ons)
        se = lambda xs: statistics.stdev(xs) / len(xs) ** 0.5 if len(xs) > 1 else float("nan")
        util = lambda xs: " ".join(f"{c.replace('_load', '')} {statistics.fmean(x[c] for x in xs):.0f}%" for c in ("gpu_load", "game_load", "render_load") if c in xs[0])
        print(f"  {tag or ' all'}: off {off_ft:.3f} ms ({1000 / off_ft:.0f} fps; {util(offs)})  on {on_ft:.3f} ms ({1000 / on_ft:.0f} fps; {util(ons)})  n={len(ons)}")
        for c, unit, scale in (("frametime", "us", 1000), ("gpu_ms", "us", 1000), ("game_load", "%", 1), ("render_load", "%", 1), ("gpu_load", "%", 1)):
            if c not in d or not d[c]:
                continue
            xs = d[c]
            print(f"      {c:12} on - off {statistics.fmean(xs) * scale:+8.1f} +- {se(xs) * scale:6.1f} {unit} (median {statistics.median(xs) * scale:+.1f})")
    n_on = sum(v["n"] for k, v in med.items() if k % 2 == 0)
    n_off = sum(v["n"] for k, v in med.items() if k % 2 == 1)
    sec = sections(run, P, T, n_on, n_off)
    if sec:
        print("  GPU sections (us / frame, whole run per half):")
        for name, h in sorted(sec.items()):
            print("      " + name.ljust(24) + "  ".join(f"{k} {v:7.2f}" for k, v in sorted(h.items())))
    # the run's ground counters (GroundBake.stats / GroundState.stats in the console)
    for l in con.splitlines()[::-1]:
        if "ground bake:" in l:
            print("  " + l[l.index("ground bake:"):][:300])
            break
    for l in con.splitlines()[::-1]:
        if "ground state:" in l and "stamps" in l:
            print("  " + l[l.index("ground state:"):][:300])
            break


def main():
    a = sys.argv[1:]
    skip, frac = 3.0, 0.15
    runs = []
    it = iter(a)
    for x in it:
        if x == "--skip":
            skip = float(next(it))
        elif x == "--frac":
            frac = float(next(it))
        elif x in ("-h", "--help"):
            print(__doc__)
            return
        else:
            runs.append(x)
    if not runs:
        print(__doc__)
        return
    for r in runs:
        if not os.path.isdir(r):
            r2 = Path(__file__).resolve().parents[1] / "runs" / r
            r = str(r2) if r2.is_dir() else r
        analyze(r, skip, frac)


if __name__ == "__main__":
    main()
