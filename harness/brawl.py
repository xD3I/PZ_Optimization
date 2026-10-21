#!/usr/bin/env python3
"""Zombie behaviour, key off vs key on, from `brawl=N` runs (pzopt.BrawlRig, 2026-10-13).

  harness/brawl.py --off <run> [<run> ...] --on <run> [<run> ...] [--no-jev] [--json f]

Per run (console `harness: brawl t=` lines + the `brawl=` line of pzopt-bench.out): hits the player took, health they cost,
seconds with a hit, the zombies' mean distance to the player over time (how fast the horde closes in), how many stood
within 4 tiles, and the share of zombie-seconds per action state (idle, walktoward, pathfind, attack, lunge, thump,
onground, hitreaction...). The off runs' spread is the noise: each on-run metric is printed beside the off range, and a
metric outside [min - 25 % of the off range, max + 25 %] of the off runs (and of a 10 % relative band) is flagged. Jev then
answers whether the on runs show the same zombie behaviour as the off runs within that spread (text only, numbers in).
"""
import argparse
import json
import re
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

LINE = re.compile(r"harness: brawl t=(\d+) alive=(\d+) dead=(\d+) target=(\d+) dist_mean=([\d.]+) dist_median=([\d.]+) within1\.5=(\d+) within4=(\d+) within12=(\d+) hits=(\d+) damage=([\d.]+) states=(\S*)")


def load(run):
    run = Path(run)
    rows = []
    for l in (run / "console.txt").read_text(errors="replace").splitlines():
        m = LINE.search(l)
        if m:
            g = m.groups()
            states = {}
            for kv in g[11].split(","):
                if ":" in kv:
                    k, v = kv.rsplit(":", 1)
                    states[k] = int(v)
            rows.append({"t": int(g[0]), "alive": int(g[1]), "dead": int(g[2]), "target": int(g[3]), "dist_mean": float(g[4]),
                         "dist_median": float(g[5]), "within1.5": int(g[6]), "within4": int(g[7]), "within12": int(g[8]),
                         "hits": int(g[9]), "damage": float(g[10]), "states": states})
    summ = ""
    bench = run / "pzopt-bench.out"
    if bench.exists():
        for l in bench.read_text(errors="replace").splitlines():
            if l.startswith("brawl="):
                summ = l
    return rows, summ


def metrics(rows):
    if not rows:
        return {}
    n = len(rows)
    tot_states = {}
    for r in rows:
        for k, v in r["states"].items():
            tot_states[k] = tot_states.get(k, 0) + v
    zs = sum(tot_states.values()) or 1
    m = {
        "seconds": n,
        "hits_per_s": sum(r["hits"] for r in rows) / n,
        "damage_per_s": sum(r["damage"] for r in rows) / n,
        "seconds_with_hit_share": sum(1 for r in rows if r["hits"] > 0) / n,
        "targeting_mean": statistics.fmean(r["target"] for r in rows),
        "within4_mean": statistics.fmean(r["within4"] for r in rows),
        "within12_mean": statistics.fmean(r["within12"] for r in rows),
        "dist_mean_t5": next((r["dist_mean"] for r in rows if r["t"] >= 5), None),
        "dist_mean_t10": next((r["dist_mean"] for r in rows if r["t"] >= 10), None),
        "dist_mean_t20": next((r["dist_mean"] for r in rows if r["t"] >= 20), None),
        "dist_mean_last": rows[-1]["dist_mean"],
        "dead_last": rows[-1]["dead"],
    }
    for k, v in tot_states.items():
        m["state_share_" + k] = v / zs
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--off", nargs="+", required=True)
    ap.add_argument("--on", nargs="+", required=True)
    ap.add_argument("--no-jev", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
    off = {r: metrics(load(r)[0]) for r in a.off}
    on = {r: metrics(load(r)[0]) for r in a.on}
    keys = sorted({k for m in list(off.values()) + list(on.values()) for k in m})
    flagged = []
    print(f"{'metric':34s} {'off runs':>30s}   on runs")
    for k in keys:
        ov = [m.get(k) for m in off.values() if m.get(k) is not None]
        nv = [m.get(k) for m in on.values() if m.get(k) is not None]
        if not ov and not nv:
            continue
        lo, hi = (min(ov), max(ov)) if ov else (0.0, 0.0)
        span = hi - lo
        band_lo = lo - 0.25 * span - 0.10 * abs(lo) - (0.02 if k.startswith("state_share") else 0.0)
        band_hi = hi + 0.25 * span + 0.10 * abs(hi) + (0.02 if k.startswith("state_share") else 0.0)
        out = [v for v in nv if v < band_lo or v > band_hi]
        if out:
            flagged.append(k)
        fmtv = lambda v: f"{v:8.3f}" if isinstance(v, float) else f"{v:8}"
        print(f"{k:34s} {' '.join(fmtv(v) for v in ov):>30s}   {' '.join(fmtv(v) for v in nv)}{'   <-- outside the off spread' if out else ''}")
    result = {"off": off, "on": on, "flagged": flagged}
    if not a.no_jev:
        from typesafe_client import ask, noul, choice, fmt
        state = {"what": "Zombie behaviour in a scripted scene: N hostile zombies spawned 4-30 tiles round a standing player who "
                         "makes a noise every 4 s; per run the hits the player took, zombies' distance to the player over "
                         "time and the share of zombie-seconds per action state. 'off' runs are the reference game "
                         "(several runs give the run-to-run spread, zombies' random rolls differ every run); 'on' runs "
                         "have a performance change that runs the zombies' update on worker threads.",
                 "off_runs": off, "on_runs": on, "metrics_outside_off_spread": flagged}
        ans = ask(state, {
            "same": noul("Do the 'on' runs show the same zombie behaviour as the 'off' runs, i.e. every difference is "
                         "within the spread between the 'off' runs themselves or explained by random rolls?"),
            "kind": choice("Which describes the 'on' runs against the 'off' runs?",
                           {"same": "same behaviour within the run-to-run spread",
                            "more_aggressive": "zombies reach / hit the player clearly more",
                            "less_aggressive": "zombies reach / hit the player clearly less, or stand idle more",
                            "broken": "states or counts that the reference never shows (stuck, frozen, mass deaths)"})})
        print("jev:\n  " + fmt(ans).replace("\n", "\n  "))
        result["jev"] = ans
    if a.json:
        Path(a.json).write_text(json.dumps(result, indent=1))


if __name__ == "__main__":
    main()
