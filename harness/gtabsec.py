#!/usr/bin/env python3
"""Within-run A/B from pzopt-gtab.out alone (devGtAlternate=N with any period, ABBA or plain; 2026-10-13).

  harness/gtabsec.py <run-dir> [<run-dir> ...] [--all] [--skip S] [--sections a,b,...] [--group 1|2]

pzopt-gtab.out has one row per frame: the phase (on / off) the frame ran with, the game thread's CPU ns, the process CPU
ns, the wall ns, the zombie updates and the ns of every timed section (names from its header). This prints, over the
route window (pzopt-bench.out; --all = every row after --skip seconds), per phase: frames, mean wall ms, mean game-thread
CPU ms, mean process CPU ms (in cores), and the mean ms of each section, plus the on - off differences. The paired line
compares each run of consecutive same-phase frames with the mean of its neighbouring runs of the other phase (a scene that
drifts along the route cancels). Works at the short periods the spinning routes need (230 ms), where gtab.py's 150 ms
switch margin leaves no frames.
"""
import argparse
import os
import statistics


def route_window(run):
    p = os.path.join(run, "pzopt-bench.out")
    if not os.path.exists(p):
        return None
    kv = dict(l.rstrip("\n").split("=", 1) for l in open(p) if "=" in l)
    if "route_start_epoch_ms" in kv and "route_end_epoch_ms" in kv:
        return int(kv["route_start_epoch_ms"]), int(kv["route_end_epoch_ms"])
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--skip", type=float, default=2.0)
    ap.add_argument("--sections", default="")
    ap.add_argument("--group", type=int, default=1, choices=(1, 2), help="which alternation group's phase splits the frames (devGtAlternate2 = 2)")
    a = ap.parse_args()
    for run in a.runs:
        path = os.path.join(run, "pzopt-gtab.out")
        if not os.path.exists(path):
            print(run, ": no pzopt-gtab.out")
            continue
        names = []
        rows = []
        with open(path) as fh:
            for line in fh:
                if line.startswith("#"):
                    if "per section:" in line:
                        tail = line.split("per section:", 1)[1]
                        tail = tail.split("(per frame", 1)[0]
                        names = tail.split()
                    continue
                parts = line.split()
                if len(parts) < 6:
                    continue
                rows.append([int(x) for x in parts])
        if not rows:
            print(run, ": empty")
            continue
        t0 = rows[0][0]
        win = None if a.all else route_window(run)
        lo = max(t0 + a.skip * 1000, win[0] if win else 0)
        hi = win[1] if win else 1 << 62
        rows = [r for r in rows if lo <= r[0] <= hi]
        want = [s for s in a.sections.split(",") if s] or names
        idx = {n: 6 + i for i, n in enumerate(names)}
        ph = {0: [], 1: []}
        bit = a.group - 1
        for r in rows:
            r[1] = (r[1] >> bit) & 1
            ph[r[1]].append(r)
        print(f"== {os.path.basename(run.rstrip('/'))}: {len(rows)} frames in the window (on {len(ph[1])}, off {len(ph[0])})")
        if not ph[0] or not ph[1]:
            continue

        def mean(rs, col, scale=1e6):
            return statistics.fmean(r[col] for r in rs) / scale

        print(f"   {'':24s} {'on':>9s} {'off':>9s} {'on-off':>9s}")
        for label, col in (("wall ms", 4), ("game thread cpu ms", 2), ("process cpu ms", 3)):
            on, off = mean(ph[1], col), mean(ph[0], col)
            print(f"   {label:24s} {on:9.3f} {off:9.3f} {on - off:+9.3f}")
        on, off = mean(ph[1], 5, 1), mean(ph[0], 5, 1)
        print(f"   {'zombie updates':24s} {on:9.0f} {off:9.0f}")
        for n in want:
            if n not in idx:
                continue
            col = idx[n]
            on, off = mean(ph[1], col), mean(ph[0], col)
            if on == 0 and off == 0:
                continue
            print(f"   {n:24s} {on:9.3f} {off:9.3f} {on - off:+9.3f}")
        # paired: runs of consecutive same-phase frames
        segs = []
        for r in rows:
            if segs and segs[-1][0] == r[1]:
                segs[-1][1].append(r)
            else:
                segs.append((r[1], [r]))
        diffs_wall, diffs_gt = [], []
        for i, (p, rs) in enumerate(segs):
            if p != 1 or i == 0 or i == len(segs) - 1:
                continue
            nb = segs[i - 1][1] + segs[i + 1][1]
            diffs_wall.append(mean(rs, 4) - mean(nb, 4))
            diffs_gt.append(mean(rs, 2) - mean(nb, 2))
        if len(diffs_wall) > 2:
            def ci(d):
                return statistics.fmean(d), 1.96 * statistics.stdev(d) / len(d) ** 0.5
            w, wc = ci(diffs_wall)
            g, gc = ci(diffs_gt)
            print(f"   paired ({len(diffs_wall)} on-runs vs neighbours): wall {w:+.3f} +- {wc:.3f} ms, game thread cpu {g:+.3f} +- {gc:.3f} ms")


if __name__ == "__main__":
    main()
