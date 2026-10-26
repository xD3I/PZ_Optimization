#!/usr/bin/env python3
"""Frame tails of chunk arrival in a benchmark run (lua-stream, 2026-10-25).

Reads a harness/runs/bench-* dir (or its results/): per pass the game-step csv pzopt.LuaStream writes
(<step>-luastream.csv: step_ms, LoadGridsquare / LoadChunk ms, hand-off work ms, queue), the pass's step properties
(fps, p99, utilization) and its mod Lua profile (<step>-luaprof.properties: ms a frame by event).

Per pass it prints: game-step mean / median / p99 / p99.9 / max, spikes (steps over 2x the median), the spikes the
LoadGridsquare + LoadChunk time made (without that time the step would not have been a spike), the worst steps with
their event share, utilization (process / busiest core / GPU), and the profiler's Lua ms a frame of the stream events.

Usage: harness/luastream.py <run> [<run> ...] [--worst N] [--handlers N]   (a benchmark-run.sh run, or a harness/run.sh run with
pzopt-luastream.out / pzopt-chunkready.out / pzopt-luahandlers.out: the route window)
   (--handlers: the top N rows of <step>-luahandlers.csv)
"""
import csv
import glob
import os
import re
import statistics
import sys


def pct(v, p):
    if not v:
        return float("nan")
    s = sorted(v)
    k = (len(s) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def props(path):
    d = {}
    if os.path.isfile(path):
        for line in open(path, encoding="utf-8", errors="replace"):
            if "=" in line and not line.startswith("#"):
                k, v = line.rstrip("\n").split("=", 1)
                d[k] = v
    return d


def one(csvp, worst, ext=".csv"):
    base = csvp[:-len("-luastream" + ext)]
    rows = list(csv.DictReader(open(csvp)))
    if len(rows) < 10:
        print(f"  {os.path.basename(csvp)}: {len(rows)} rows")
        return
    rows = rows[5:]  # the first steps of the route window carry the world-ready burst's tail
    st = [float(r["step_ms"]) for r in rows]
    lgs = [float(r["loadgridsquare_ms"]) for r in rows]
    lc = [float(r["loadchunk_ms"]) for r in rows]
    work = [float(r["stream_work_ms"]) for r in rows]
    q = [int(r["queued"]) for r in rows]
    med = statistics.median(st)
    spikes = [i for i, v in enumerate(st) if v > 2 * med]
    caused = [i for i in spikes if st[i] - (lgs[i] + lc[i]) <= 2 * med]
    byw = [i for i in spikes if st[i] - max(work[i], lgs[i] + lc[i]) <= 2 * med]  # the time-sliced hand-off's whole work (Lua or not)
    over = lambda ms: sum(1 for v in st if v > ms)
    key = rows[0].get("key_on", "?")
    n = len(st)
    print(f"  {os.path.basename(base)}  steps {n}  key_on {key}")
    print(f"    step ms: mean {statistics.fmean(st):.2f}  median {med:.2f}  p99 {pct(st, 99):.2f}  p99.9 {pct(st, 99.9):.2f}  max {max(st):.2f}"
          f"  | >2x median {len(spikes)} ({100.0 * len(spikes) / n:.2f} %), of them by LoadGridsquare+LoadChunk {len(caused)}"
          f" (by the hand-off's whole work {len(byw)})  | >33 ms {over(33.3)}  >50 ms {over(50)}  >100 ms {over(100)}")
    if "max_step_ms" in rows[0]:
        ms = [float(r["max_step_ms"]) for r in rows]
        ph = {}
        for i in spikes:
            if work[i] > 0:
                ph[rows[i]["max_step_phase"]] = ph.get(rows[i]["max_step_phase"], 0) + 1
        longest = sorted(range(n), key=lambda i: -ms[i])[:5]
        print(f"    longest single hand-off step: p99 {pct(ms, 99):.2f}  max {max(ms):.2f} ms ("
              + ", ".join(f"{ms[i]:.1f} {rows[i]['max_step_phase']}" for i in longest) + ")  | phase of the longest step in spike steps: "
              + (", ".join(f"{k} {v}" for k, v in sorted(ph.items(), key=lambda kv: -kv[1])) or "-"))
    late = [i for i in range(n) if float(rows[i]["t_ms"]) > SETTLE * 1000]
    if late and len(late) < n:
        s2 = [st[i] for i in late]
        m2 = statistics.median(s2)
        sp2 = [i for i in late if st[i] > 2 * m2]
        c2 = [i for i in sp2 if st[i] - (lgs[i] + lc[i]) <= 2 * m2]
        vp2 = [int(rows[i].get("visible_pending", 0) or 0) for i in late]
        print(f"    after {SETTLE:.0f} s ({len(late)} steps): mean {statistics.fmean(s2):.2f}  median {m2:.2f}  p99 {pct(s2, 99):.2f}  p99.9 {pct(s2, 99.9):.2f}  max {max(s2):.2f}"
              f"  | >2x median {len(sp2)}, by LGS+LC {len(c2)}  | queue mean {statistics.fmean(q[i] for i in late):.1f}  on-screen not ready mean {statistics.fmean(vp2):.2f}")
    tot = sum(st)
    print(f"    stream ms/step: LoadGridsquare mean {statistics.fmean(lgs):.3f} p99 {pct(lgs, 99):.2f} max {max(lgs):.2f}"
          f"  LoadChunk mean {statistics.fmean(lc):.3f} max {max(lc):.2f}  hand-off work mean {statistics.fmean(work):.3f} max {max(work):.2f}"
          f"  | share of time {100.0 * (sum(lgs) + sum(lc)) / tot:.1f} %  | queue mean {statistics.fmean(q):.1f} max {max(q)}"
          f"  | LGS calls/step {statistics.fmean(int(r['lgs_calls']) for r in rows):.1f}")
    if "lgs_skips" in rows[0]:
        sk = [int(r["lgs_skips"]) for r in rows]
        extra = f"  LGS handler calls skipped/step {statistics.fmean(sk):.1f}"
        if "budget_ms" in rows[0]:
            bud = [float(r["budget_ms"]) for r in rows]
            dem = [float(r["demand_ms"]) for r in rows]
            extra += f"  | budget mean {statistics.fmean(bud):.2f} ms  keep-up demand mean {statistics.fmean(dem):.2f} ms"
        if "visible_pending" in rows[0]:
            vp = [int(r["visible_pending"]) for r in rows]
            extra += f"  | on-screen chunks not ready: mean {statistics.fmean(vp):.2f}  max {max(vp)}  steps with any {100.0 * sum(1 for v in vp if v > 0) / n:.1f} %"
        print("    " + extra.strip())
    rp = base + "-chunkready" + ext
    if os.path.isfile(rp):
        rr = list(csv.DictReader(open(rp)))
        for tag, sel in (("all", rr), (f"after {SETTLE:.0f} s", [r for r in rr if float(r["t_ms"]) > SETTLE * 1000])):
            lat = [float(r["ready_ms"]) for r in sel]
            if lat:
                print(f"    chunk hand-off wait (queued -> ready to draw), {tag}, {len(lat)} chunks: p50 {pct(lat, 50):.0f} ms  p90 {pct(lat, 90):.0f}  p99 {pct(lat, 99):.0f}  max {max(lat):.0f}"
                      f"  | >1 s {sum(1 for v in lat if v > 1000)}  >3 s {sum(1 for v in lat if v > 3000)}")
    hp = base + "-luahandlers" + ext
    if os.path.isfile(hp) and HANDLERS:
        hr = list(csv.DictReader(open(hp), delimiter="\t"))
        tot = sum(float(r["ms"]) for r in hr) or 1.0
        calls = sum(int(r["calls"]) for r in hr)
        skips = sum(int(r["skips"]) for r in hr)
        mism = sum(int(r["mismatches"]) for r in hr)
        print(f"    handlers {len(hr)}: {tot:.0f} ms run, {calls} calls, {skips} skipped ({100.0 * skips / max(1, calls + skips):.1f} %), {mism} checker mismatches")
        for r in hr[:HANDLERS]:
            rec = int(r["recorded"])
            print(f"      {float(r['ms']):8.1f} ms {int(r['calls']):7d} calls {float(r['mean_us']):6.2f} us  skips {int(r['skips']):7d}  rec {rec:6d} eff {int(r['effects']):6d}"
                  f" unc {int(r['uncovered']):6d} sigs {int(r['learned_sigs']):5d} mism {int(r['mismatches'])} {'NEVER ' if r['never'] == 'true' else ''}{r['handler']}  | {r['top_reasons'][:150]}")
    top = sorted(range(n), key=lambda i: -st[i])[:worst]
    print("    worst steps (ms: step / LGS / LC / work, queue): " + "  ".join(f"{st[i]:.0f}/{lgs[i]:.0f}/{lc[i]:.0f}/{work[i]:.0f},q{q[i]}" for i in top))
    # the step properties (fps, tails, utilization) and the Lua profile of the same pass
    m = re.match(r"(.*/)?(step-\d+)-", base + "-")
    stepp = props(os.path.join(os.path.dirname(base), (m.group(2) if m else "x") + ".properties"))
    if stepp:
        g = lambda k: stepp.get(k, "nan")
        print(f"    pass: fps {g('fps')}  median {g('median_ms')}  p99 {g('p99_ms')}  p99.9 {g('p999_ms')}  max {g('max_ms')}  jitter {g('jitter_ms')}"
              f"  | cpu process {g('cpu_process_pct')} % busiest core {g('cpu_busiest_core_pct')} %  gpu {g('gpu_busy_pct')} %"
              f"  | lua {g('lua_ms')} ms mods {g('lua_mods_ms')} ms")
    lp = props(base + "-luaprof.properties")
    ev = {}
    for k, v in lp.items():
        if k.startswith("event."):
            f = v.split("\t")
            ev[f[0]] = (float(f[1]), float(f[3]) if len(f) > 3 and f[3] else 0.0)
    if ev:
        want = ["LoadGridsquare", "LoadChunk", "OnTick", "OnZombieUpdate", "OnFillContainer", "Lua call"]
        print("    lua profile ms/frame (calls/frame): " + "  ".join(f"{e} {ev[e][0]:.2f} ({ev[e][1]:.0f})" for e in want if e in ev)
              + f"  | frames {lp.get('frames', '?')}")


HANDLERS = 0
SETTLE = 12.0


def main():
    global HANDLERS, SETTLE
    args = sys.argv[1:]
    worst = 8
    if "--settle" in args:
        i = args.index("--settle")
        SETTLE = float(args[i + 1])
        del args[i:i + 2]
    if "--handlers" in args:
        i = args.index("--handlers")
        HANDLERS = int(args[i + 1])
        del args[i:i + 2]
    if "--worst" in args:
        i = args.index("--worst")
        worst = int(args[i + 1])
        del args[i:i + 2]
    for run in args:
        h = os.path.join(run, "pzopt-luastream.out")
        if os.path.exists(h):  # a harness/run.sh run: the route window only (recorded from route start), no settle cut
            if "--settle" not in sys.argv:
                SETTLE = 0.0
            print(f"{run}: harness route")
            one(h, worst, ".out")
            continue
        res = run if os.path.basename(run.rstrip("/")) == "results" else os.path.join(run, "results")
        files = sorted(glob.glob(os.path.join(res, "step-*-luastream.csv")))
        print(f"{run}: {len(files)} passes")
        for f in files:
            one(f, worst)


if __name__ == "__main__":
    main()
