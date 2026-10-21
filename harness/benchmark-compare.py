#!/usr/bin/env python3
"""Compare built-in benchmark runs (harness/benchmark-run.sh output dirs, docs/plan-builtin-benchmark.md).

usage: benchmark-compare.py <run dir or label prefix>... [--key fps] [--json out]

Per run, scenario and config: the passes' values of --key (default fps) in plan order, mean, coefficient of
variation, 95 % CI half-width (Student t), and the means of the 1 % low, p99, stutters, zombies at the start, the
background CPU; the first measured pass against the rest (cold vs warm process). Pass status other than ok is shown.
"""
import glob
import json
import math
import os
import sys

T975 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228]


def props(path):
    d = {}
    with open(path) as f:
        for line in f:
            if line.startswith("#") or "=" not in line:
                continue
            k, v = line.rstrip("\n").split("=", 1)
            d[k] = v.replace("\\=", "=").replace("\\:", ":")
    return d


def num(d, k):
    try:
        return float(d.get(k, "nan"))
    except ValueError:
        return float("nan")


def stats(v):
    v = [x for x in v if not math.isnan(x)]
    n = len(v)
    if n == 0:
        return n, float("nan"), float("nan"), float("nan")
    m = sum(v) / n
    if n < 2:
        return n, m, float("nan"), float("nan")
    sd = math.sqrt(sum((x - m) ** 2 for x in v) / (n - 1))
    t = T975[min(n - 2, len(T975) - 1)] if n - 1 <= 10 else 1.96
    return n, m, 100 * sd / m if m else float("nan"), t * sd / math.sqrt(n)


def resolve(arg):
    if os.path.isdir(arg):
        return [arg]
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs")
    return sorted(glob.glob(os.path.join(here, "bench-" + arg + "*")))


def main():
    args = sys.argv[1:]
    key = "fps"
    out = None
    if "--key" in args:
        i = args.index("--key")
        key = args[i + 1]
        del args[i:i + 2]
    if "--json" in args:
        i = args.index("--json")
        out = args[i + 1]
        del args[i:i + 2]
    rows = []
    for a in args:
        for run in resolve(a):
            steps = sorted(glob.glob(os.path.join(run, "results", "step-*.properties")))
            groups = {}
            for s in steps:
                p = props(s)
                if p.get("config") == "W":
                    continue
                groups.setdefault((p.get("scenario"), p.get("config")), []).append(p)
            for (sc, cfg), ps in groups.items():
                ok = [p for p in ps if p.get("status") == "ok"]
                vals = [num(p, key) for p in ok]
                n, m, cv, ci = stats(vals)
                low = stats([num(p, "low1_avg") for p in ok])[1]
                p99 = stats([num(p, "p99_ms") for p in ok])[1]
                stut = stats([num(p, "stutters") for p in ok])[1]
                zs = stats([num(p, "zombies_start") for p in ok])
                bg = stats([num(p, "cpu_background_pct") for p in ok])[1]
                first_vs_rest = float("nan")
                if len(vals) >= 3:
                    rest = sum(vals[1:]) / (len(vals) - 1)
                    first_vs_rest = 100 * (vals[0] - rest) / rest if rest else float("nan")
                bad = [f"{p.get('pass')}:{p.get('status')}" for p in ps if p.get("status") != "ok"]
                rows.append({"run": os.path.basename(run), "scenario": sc, "config": cfg, "n": n, "values": vals, "mean": m, "cv_pct": cv,
                             "ci95": ci, "low1": low, "p99": p99, "stutters": stut, "zombies_start": zs[1], "zombies_cv_pct": zs[2],
                             "background_pct": bg, "first_vs_rest_pct": first_vs_rest, "bad": bad})
    print(f"{'run':44} {'scenario':14} cfg  n  {key + ' per pass':38} {'mean':>8} {'CV%':>5} {'+-95':>6} {'1%low':>6} {'p99':>6} {'stut':>5} {'zomb':>6} {'zCV%':>5} {'bg%':>4} {'1st%':>6}")
    for r in rows:
        vals = " ".join(f"{v:.1f}" for v in r["values"])
        print(f"{r['run'][:44]:44} {r['scenario'][:14]:14} {r['config']:3} {r['n']:2}  {vals[:38]:38} {r['mean']:8.1f} {r['cv_pct']:5.1f} {r['ci95']:6.1f} {r['low1']:6.1f} {r['p99']:6.2f} {r['stutters']:5.0f} {r['zombies_start']:6.0f} {r['zombies_cv_pct']:5.1f} {r['background_pct']:4.0f} {r['first_vs_rest_pct']:6.1f}"
              + (f"  not ok: {' '.join(r['bad'])}" if r["bad"] else ""))
    if out:
        with open(out, "w") as f:
            json.dump(rows, f, indent=1)


if __name__ == "__main__":
    main()
