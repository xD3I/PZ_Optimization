#!/usr/bin/env python3
"""Render-thread profile of an asprof run (GL API cost pass, 2026-11-17).

usage: rtprof.py <run> [--thread main] [--top 40] [--match REGEX]...

Reads <run>/asprof.jfr (run.sh --asprof event=cpu,interval=1ms,threads) over the route window (pzopt-bench.out) through
jfrconv, keeps the samples of one thread (the render thread is "main"), and prints
  - ms per frame (samples x interval / frames from pzopt-overlay.out),
  - self time by leaf frame and by shared object (driver = libgallium / libGLX / libGL...),
  - inclusive time of every frame matching --match (default: the GL entry points LWJGL calls, grouped by name),
so the share of the GL API in the render thread's time can be read without perf.
"""
import argparse
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from flamegraph import route_window  # noqa: E402

JFRCONV = os.path.expanduser("~/.local/share/async-profiler/async-profiler-4.1-linux-x64/bin/jfrconv")


def frames_in_window(run, win):
    p = Path(run) / "pzopt-overlay.out"
    if not p.exists():
        return None
    n = 0
    for line in p.read_text(errors="replace").splitlines():
        parts = line.split(",")
        if not parts or not parts[-1].strip().isdigit():
            continue
        t = int(parts[-1])
        if win[0] <= t <= win[1]:
            n += 1
    return n or None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--thread", default="main")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--interval-ms", type=float, default=1.0)
    ap.add_argument("--match", action="append", default=[])
    ap.add_argument("--callers", action="append", default=[], help="GL function name: print who calls it")
    a = ap.parse_args()
    win = route_window(a.run)
    if not win:
        sys.exit("no route window in pzopt-bench.out")
    cache = Path(a.run) / f".asprof-collapsed-{win[0]}-{win[1]}.txt"
    if not cache.exists():
        subprocess.run([JFRCONV, "--cpu", "-t", "--from", str(win[0]), "--to", str(win[1]), "-o", "collapsed",
                        str(Path(a.run) / "asprof.jfr"), str(cache)], check=True, capture_output=True)
    stacks = Counter()
    for line in cache.read_text(errors="replace").splitlines():
        st, _, n = line.rpartition(" ")
        fr = st.split(";")
        if n.isdigit() and fr[0].startswith("[" + a.thread + " "):
            stacks[tuple(fr[1:])] += int(n)
    total = sum(stacks.values())
    frames = frames_in_window(a.run, win)
    per = (a.interval_ms / frames) if frames else None

    def fmt(c):
        s = f"{100.0 * c / total:5.1f}%"
        if per:
            s += f" {c * per:6.3f} ms/frame"
        return s

    secs = (win[1] - win[0]) / 1000.0
    print(f"{a.run}: thread {a.thread}, {total} samples over {secs:.1f} s"
          + (f", {frames} frames, {total * per:.3f} ms/frame" if per else ""))

    leaf = Counter()
    lib = Counter()
    for st, c in stacks.items():
        f = st[-1] if st else "?"
        leaf[f] += c
        if f.endswith("_[j]") or f.endswith("_[i]") or f.endswith("_[0]") or "." in f.split("(")[0] and "/" in f:
            lib["java"] += c
        elif f.startswith("[") or f.startswith("__") or f.startswith("entry_") or "_[k]" in f:
            lib["kernel"] += c
        else:
            lib["native"] += c
    print("\nself time by leaf:")
    for f, c in leaf.most_common(a.top):
        print(f"  {fmt(c)}  {f}")

    pats = a.match or [r"^org/lwjgl/opengl/(GL\d+C?|ARB\w+|EXT\w+)\.(n?gl\w+)"]
    rx = [re.compile(p) for p in pats]
    incl = Counter()
    for st, c in stacks.items():
        seen = set()
        for f in st:
            for r in rx:
                m = r.search(f)
                if m:
                    key = m.group(m.lastindex or 0)
                    key = re.sub(r"^n(gl)", r"\1", key)
                    if key not in seen:
                        seen.add(key)
                        incl[key] += c
    print("\ninclusive time of matching frames:")
    s = 0
    for k, c in incl.most_common(a.top):
        print(f"  {fmt(c)}  {k}")
        s += c
    print(f"  (sum {fmt(s)}; nested matches count once per name)")

    for name in a.callers:
        rc = re.compile(r"\.n?" + re.escape(name) + r"_")
        callers = Counter()
        for st, c in stacks.items():
            for i in range(len(st) - 1, -1, -1):
                if rc.search(st[i]):
                    chain = [f.rsplit("_[", 1)[0].rsplit("/", 1)[-1] for f in st[max(0, i - 6):i] if "lwjgl" not in f]
                    callers[" < ".join(reversed(chain[-3:]))] += c
                    break
        print(f"\ncallers of {name}:")
        for k, c in callers.most_common(6):
            print(f"  {fmt(c)}  {k}")


if __name__ == "__main__":
    main()
