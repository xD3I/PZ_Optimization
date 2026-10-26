#!/usr/bin/env python3
"""lua-zombie pass: read a benchmark run (harness/runs/bench-*) and print the numbers of the OnZombieUpdate work.

  harness/luazombie.py <run_dir> [<run_dir> ...] [--skip S]

Per run:
  - the mod Lua profile (results/step-*-luaprof.properties): frames, Lua ms a frame, OnZombieUpdate / OnTick / collide /
    OnAIStateChange / OnPlayerMove ms a frame and calls a frame, the OnZombieUpdate handlers;
  - the benchmark's own frame stats (results/step-*-frames.csv when present: mean, p99, p99.9, spikes);
  - pzopt-luazombie.out (last-pzopt-luazombie.out): per A/B half (ab column) the dispatch ms, replay ms, calls, skipped,
    updates, due, idle a frame;
  - pzopt-gtab.out (last-pzopt-gtab.out) when present: per A/B half the game-thread CPU, process CPU and wall ms a frame,
    p99 / p99.9 of the wall, and the paired per-period difference (on minus the mean of the neighbouring offs).
"""
import glob
import os
import statistics
import sys

EVENTS = ("OnZombieUpdate", "OnTick", "OnCharacterCollide", "OnObjectCollide", "OnAIStateChange", "OnPlayerMove", "LoadGridsquare")


def pct(v, p):
    if not v:
        return float("nan")
    v = sorted(v)
    k = (len(v) - 1) * p
    f = int(k)
    c = min(f + 1, len(v) - 1)
    return v[f] + (v[c] - v[f]) * (k - f)


def luaprof(run):
    for f in sorted(glob.glob(os.path.join(run, "results", "step-*-luaprof.properties"))) or glob.glob(os.path.join(run, "pzopt-luaprof.out")):
        props, events, fns = {}, {}, []
        for line in open(f, errors="replace"):
            if line.startswith("#") or "=" not in line:
                continue
            k, v = line.rstrip("\n").split("=", 1)
            if k.startswith("event."):
                p = v.split("\t")
                events[p[0]] = (float(p[1]), float(p[2]), float(p[3]))
            elif k.startswith("fn."):
                p = v.split("\t")
                if len(p) > 7 and p[4] == "OnZombieUpdate":
                    fns.append((float(p[6]), float(p[7]), p[0], p[1], p[2]))
            else:
                props[k] = v
        print(f"  luaprof {os.path.basename(f)}: frames={props.get('frames')} s={float(props.get('seconds', 0)):.1f} "
              f"lua={float(props.get('lua_ms', 0)):.2f} ms/frame mods={float(props.get('mods_ms', 0)):.2f} p99={float(props.get('p99_frame_ms', 0)):.1f}")
        for e in EVENTS:
            if e in events:
                ms, self_ms, calls = events[e]
                print(f"    {e:<20} {ms:7.3f} ms/frame  {calls:8.1f} calls/frame")
        fns.sort(reverse=True)
        for incl, calls, origin, file, fn in fns[:14]:
            if calls > 0:
                print(f"      {incl:6.3f} ms {calls:7.1f} calls  {origin} {file} {fn}")


STEP_KEYS = ("fps", "mean_ms", "median_ms", "p99_ms", "p999_ms", "max_ms", "stutters", "jitter_ms", "over50", "over100", "low1_avg",
             "game_thread_pct", "render_thread_pct", "cpu_process_pct", "cpu_busiest_core_pct", "gpu_busy_pct", "bound_game_pct",
             "zombies_start", "zombies_end", "lua_ms", "lua_mods_ms", "seconds", "status", "invalid_reason")


def frames(run):
    for f in sorted(glob.glob(os.path.join(run, "results", "step-[0-9][0-9].properties"))):
        props = {}
        for line in open(f, errors="replace"):
            if "=" in line and not line.startswith("#"):
                k, v = line.rstrip("\n").split("=", 1)
                props[k] = v
        out = []
        for k in STEP_KEYS:
            if k in props:
                v = props[k]
                try:
                    v = f"{float(v):.4g}"
                except ValueError:
                    pass
                out.append(f"{k}={v}")
        print(f"  step {os.path.basename(f)} {props.get('scenario', '')}: " + " ".join(out))


def out_file(run, name):
    """benchmark-run.sh copies last-pzopt-*.out, harness/run.sh pzopt-*.out."""
    f = os.path.join(run, "last-" + name)
    return f if os.path.exists(f) else os.path.join(run, name)


def read_lz(run, skip):
    f = out_file(run, "pzopt-luazombie.out")
    if not os.path.exists(f):
        return [], ""
    rows, head = [], ""
    for line in open(f, errors="replace"):
        if line.startswith("#"):
            head = line.strip()
            continue
        p = line.split()
        if len(p) >= 14:
            r = [float(x) for x in p[:22]]
            pad = [1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]  # scale near_calls hot_calls alpha_n alpha_chg alpha_rev draw_rev stale_ms
            rows.append(r + pad[len(r) - 14:])
    if rows:
        t0 = rows[0][0]
        rows = [r for r in rows if r[0] - t0 >= skip * 1000]
    return rows, head


def variants_of(head):
    i = head.find("variants=")
    if i < 0:
        return []
    v = head[i + 9:].split("/")[0].strip()
    return v.split(";") if v else []


def horde_epochs(run, skip):
    """epoch_ms of the gtab rows in the measured window (the horde's frames), or None without pzopt-gtab.out."""
    f = out_file(run, "pzopt-gtab.out")
    if not os.path.exists(f):
        return None
    rows = []
    for line in open(f, errors="replace"):
        if line.startswith("#"):
            continue
        p = line.split()
        if len(p) >= 6:
            rows.append((int(float(p[0])), float(p[5])))
    if not rows:
        return None
    t0 = rows[0][0]
    rows = [r for r in rows if r[0] - t0 >= skip * 1000]
    zs = sorted(r[1] for r in rows)
    floor = zs[int(len(zs) * 0.9)] * 0.3 if zs else 0  # the loading's many fast frames hold few zombies
    return set(r[0] for r in rows if r[1] >= floor)


def luazombie(run, skip):
    rows, head = read_lz(run, skip)
    ep = horde_epochs(run, skip)
    if ep:
        rows = [r for r in rows if int(r[0]) in ep or int(r[0]) - 1 in ep or int(r[0]) + 1 in ep]
    if not rows:
        return
    names = variants_of(head)
    print(f"  luazombie.out: {len(rows)} frames  {head[head.find('('):]}")
    for ab in sorted(set(int(r[1]) for r in rows), reverse=not names):
        rs = [r for r in rows if int(r[1]) == ab]
        m = lambda i: statistics.mean(r[i] for r in rs)
        tag = f"variant {ab} ({names[ab]})" if names and ab < len(names) else f"ab={ab}"
        print(f"    {tag} n={len(rs)} dispatch={m(2) / 1e6:.3f} ms replay={m(3) / 1e6:.3f} ms replay_events={m(4):.0f} calls={m(5):.0f} "
              f"skipped={m(6):.0f} stag={m(7) / 1e6:.3f} ms updates={m(8):.0f} due={m(9):.0f} idle={m(10):.0f} batch={m(11) / 1e6:.3f} ms "
              f"collide={m(12):.0f} dup={m(13):.0f} scale={m(14):.2f} near_calls={m(15):.0f} hot_calls={m(16):.0f} "
              f"| median dispatch={statistics.median(r[2] for r in rs) / 1e6:.3f} p95={pct([r[2] for r in rs], .95) / 1e6:.3f} ms")
        st = [r[21] for r in rs if r[21] > 0]
        if st:
            print(f"      stale_ms (mean interval of a deferred handler on a zombie): mean {statistics.mean(st):.0f} median {statistics.median(st):.0f}")
        an = sum(r[17] for r in rs)
        if an > 0:
            print(f"      alpha (devLuaZombieAlpha): {an:.0f} zombie-frames, target changes {sum(r[18] for r in rs) / an * 1000:.2f} / 1000, "
                  f"target reversals {sum(r[19] for r in rs) / an * 1000:.3f} / 1000, drawn reversals {sum(r[20] for r in rs) / an * 1000:.3f} / 1000")


def frametimes(run, skip):
    """Per variant / A/B half: the frame step from consecutive pzopt-luazombie.out rows (one per frame, epoch_ms at the frame
    start), over the horde frames (zombie updates >= 0.3 x their p90; the loading and the menus between passes drop out)."""
    rows, head = read_lz(run, skip)
    if len(rows) < 10:
        return
    names = variants_of(head)
    us = sorted(r[8] for r in rows)
    floor = us[int(len(us) * 0.9)] * 0.3
    per = {}
    for a, b in zip(rows, rows[1:]):
        d = b[0] - a[0]
        if b[8] < floor or d <= 0 or d > 2000:
            continue
        per.setdefault(int(b[1]), []).append(d)
    for g in sorted(per, reverse=not names):
        w = per[g]
        tag = f"variant {g} ({names[g]})" if names and g < len(names) else f"ab={g}"
        print(f"    frames {tag} n={len(w)} mean={statistics.mean(w):.1f} median={statistics.median(w):.1f} p99={pct(w, .99):.1f} "
              f"p99.9={pct(w, .999):.1f} max={max(w):.0f} ms  over50={sum(1 for x in w if x > 50)} over100={sum(1 for x in w if x > 100)}")


def gtab(run, skip):
    f = out_file(run, "pzopt-gtab.out")
    if not os.path.exists(f):
        return
    rows = []
    for line in open(f, errors="replace"):
        if line.startswith("#"):
            continue
        p = line.split()
        if len(p) >= 6:
            rows.append([float(p[0]), int(p[1]), float(p[2]), float(p[3]), float(p[4]), float(p[5])])
    if not rows:
        return
    t0 = rows[0][0]
    rows = [r for r in rows if r[0] - t0 >= skip * 1000]
    # the measured window: frames with the horde (the loading and the menu between passes have few zombie updates)
    zs = sorted(r[5] for r in rows)
    if zs:
        floor = zs[int(len(zs) * 0.9)] * 0.3
        rows = [r for r in rows if r[5] >= floor]
    lz, head = read_lz(run, 0)
    names = variants_of(head)
    if names and lz:
        # the variant of each gtab row: the luazombie row written in the same frame start (same epoch_ms, give or take 1)
        j = 0
        for r in rows:
            while j < len(lz) - 1 and lz[j][0] < r[0] - 1:
                j += 1
            r[1] = int(lz[j][1]) if abs(lz[j][0] - r[0]) <= 2 else -1
        groups = sorted(set(r[1] for r in rows if r[1] >= 0))
        key = lambda r: r[1]
    else:
        groups = [1, 0]
        key = lambda r: r[1] & 1
    for g in groups:
        rs = [r for r in rows if key(r) == g]
        if not rs:
            continue
        w = [r[4] / 1e6 for r in rs]
        tag = f"variant {g} ({names[g]})" if names and g < len(names) else f"ab={g}"
        print(f"    gtab {tag} n={len(rs)} game_thread_cpu={statistics.mean(r[2] for r in rs) / 1e6:.2f} ms process_cpu={statistics.mean(r[3] for r in rs) / 1e6:.2f} ms "
              f"wall mean={statistics.mean(w):.2f} p99={pct(w, .99):.2f} p99.9={pct(w, .999):.2f} max={max(w):.1f} zombies={statistics.mean(r[5] for r in rs):.0f}")
    if names:
        return
    # paired: consecutive runs of the same phase are periods
    periods = []
    cur = None
    for r in rows:
        ph = r[1] & 1
        if cur is None or cur[0] != ph:
            cur = [ph, []]
            periods.append(cur)
        cur[1].append(r)
    diffs = []
    for i in range(1, len(periods) - 1):
        if periods[i][0] == 1 and periods[i - 1][0] == 0 and periods[i + 1][0] == 0:
            on = statistics.mean(r[2] for r in periods[i][1])
            off = (statistics.mean(r[2] for r in periods[i - 1][1]) + statistics.mean(r[2] for r in periods[i + 1][1])) / 2
            diffs.append((on - off) / 1e6)
    if len(diffs) > 2:
        print(f"    gtab paired game-thread cpu on-off: {statistics.mean(diffs):+.3f} ms (sd {statistics.stdev(diffs):.3f}, n={len(diffs)})")


def errors(run):
    """Console errors of each pass (ERROR lines, Lua stack traces, exceptions), by message (numbers stripped)."""
    import re
    for f in sorted(glob.glob(os.path.join(run, "results", "step-*-console.out"))) or [os.path.join(run, "console.txt")]:
        c = {}
        for line in open(f, errors="replace"):
            if "ERROR" in line or "STACK TRACE" in line or "Exception" in line or "attempted index" in line:
                m = re.sub(r"[0-9]+", "#", line.split(">", 1)[-1].strip())[:140]
                c[m] = c.get(m, 0) + 1
        n = sum(c.values())
        print(f"  console {os.path.basename(f)}: {n} error lines, {len(c)} distinct")
        for m, k in sorted(c.items(), key=lambda x: -x[1])[:6]:
            print(f"    {k:5d} {m}")


def main():
    args = sys.argv[1:]
    skip = 0.0
    if "--errors" in args:
        args.remove("--errors")
    if "--skip" in args:
        i = args.index("--skip")
        skip = float(args[i + 1])
        del args[i:i + 2]
    for run in args:
        print(run)
        luaprof(run)
        frames(run)
        luazombie(run, skip)
        frametimes(run, skip)
        gtab(run, skip)
        if "--errors" in sys.argv:
            errors(run)


if __name__ == "__main__":
    main()
