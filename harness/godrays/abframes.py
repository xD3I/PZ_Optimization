#!/usr/bin/env python3
"""A within-run frame-time A/B of god rays: a run with devGodRaysAlternate=N devGodRaysAlternateAll=true switches every
god ray pass on and off every N ms; this splits the in-game overlay's frames (pzopt-overlay.out: frametime, gpu_ms,
epoch_ms) by phase and compares them (frames within 150 ms of a switch skipped: the render thread lags the game thread).

    abframes.py [--skip S] [--door] <run dir> [<run dir>...]   (S: seconds skipped after the alternation starts, default 5;
                --door: the door features' alternation, devGodRaysDoorAlternate, instead of devGodRaysAlternate)

Run-to-run drift (the scene, the clocks, other load) cancels: both halves share the run. The cost is also given as
the mean of paired differences (each on period against the mean of the off periods on either side of it) with its
standard error, and their median (robust to a hitch in one period): a scene that changes along the route (lights coming into view) moves both halves of a pair alike."""
import csv
import math
import os
import re
import statistics
import sys


def main():
    args = sys.argv[1:]
    skip = 5.0
    door = False
    while args and args[0] in ("--skip", "--door"):
        if args[0] == "--skip":
            skip = float(args[1])
            args = args[2:]
        else:
            door = True
            args = args[1:]
    pat = r"god rays: door features alternating every (\d+) ms from epoch_ms (\d+)" if door else r"god rays: alternating every (\d+) ms from epoch_ms (\d+)"
    for run in args:
        per = t0 = None
        for line in open(os.path.join(run, "console.txt"), errors="replace"):
            m = re.search(pat, line)
            if m:
                per, t0 = int(m.group(1)), int(m.group(2))
        if per is None:
            print(run, ": no alternation")
            continue
        halves = {True: [], False: []}
        gpu = {True: [], False: []}
        periods = {}  # period index -> [frametimes]
        pgpu = {}
        with open(os.path.join(run, "pzopt-overlay.out")) as fh:
            for r in csv.DictReader(fh):
                e = int(r["epoch_ms"])
                if e < t0 + skip * 1000:
                    continue
                ph = (e - t0) % per
                if ph < 150 or ph > per - 150:
                    continue
                k = (e - t0) // per
                on = k % 2 == 0
                ft, g = float(r["frametime"]), float(r["gpu_ms"])
                halves[on].append(ft)
                gpu[on].append(g)
                periods.setdefault(k, []).append(ft)
                pgpu.setdefault(k, []).append(g)

        def stats(v):
            v = sorted(v)
            n = len(v)
            return sum(v) / n, v[n // 2], v[int(n * 0.99)] if n else 0

        (m1, p1, q1), (m0, p0, q0) = stats(halves[True]), stats(halves[False])
        g1, g0 = sum(gpu[True]) / len(gpu[True]), sum(gpu[False]) / len(gpu[False])
        print("%s\n  on : %6d frames  mean %.3f ms (%.0f fps)  p50 %.3f  p99 %.3f  gpu %.3f ms"
              % (os.path.basename(run), len(halves[True]), m1, 1000 / m1, p1, q1, g1))
        print("  off: %6d frames  mean %.3f ms (%.0f fps)  p50 %.3f  p99 %.3f  gpu %.3f ms" % (len(halves[False]), m0, 1000 / m0, p0, q0, g0))
        print("  cost: %+.1f us a frame (%+.2f %%), gpu %+.1f us" % ((m1 - m0) * 1000, (m1 / m0 - 1) * 100, (g1 - g0) * 1000))
        # paired: on period k against the off periods k-1 and k+1 (per-period means: the mean frame time is the fps)
        last = max(periods) if periods else 0  # (the last period holds the exit)
        med = {k: statistics.mean(v) for k, v in periods.items() if len(v) >= 20 and k < last}
        gmed = {k: statistics.mean(v) for k, v in pgpu.items() if len(v) >= 20 and k < last}
        d, dg = [], []
        for k in med:
            if k % 2 == 0 and k - 1 in med and k + 1 in med:
                d.append((med[k] - (med[k - 1] + med[k + 1]) / 2) * 1000)
                dg.append((gmed[k] - (gmed[k - 1] + gmed[k + 1]) / 2) * 1000)
        if len(d) >= 3:
            se = statistics.stdev(d) / math.sqrt(len(d))
            print("  paired (%d on periods): %+.1f us +- %.1f (standard error), median %+.1f us, gpu %+.1f us"
                  % (len(d), statistics.mean(d), se, statistics.median(d), statistics.mean(dg)))


if __name__ == "__main__":
    main()
