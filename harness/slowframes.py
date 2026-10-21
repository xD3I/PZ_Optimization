#!/usr/bin/env python3
"""Names the cause of every slow frame of a run (tail pass 2026-10-13).

    harness/slowframes.py <run> [<run>...] [--budget 8.33] [--list N] [--detail N] [--all]

A frame is slow when its game step (pzopt-pacing.out: step start -> next step start) exceeds --budget. Each slow frame
gets one cause, the largest of these contributions to its excess over the route's median step:

  gc / safepoint      a stop-the-world GC pause, a ZGC allocation stall or a safepoint (gc.log, <run>/jvm.log from
                      --vmarg -Xlog:safepoint=info,gc+phases=info:file=...; asprof jdk.* events) overlapping the step
  render: <label>     the game thread off-CPU in the frame hand-off (waiting for the render thread), labelled with what
                      the render thread ("main") ran in the step (its hottest label beyond its median)
  off-cpu             game-thread time without CPU samples outside the hand-off wait (parked in a join / lock, or
                      descheduled by the OS)
  <phase>/<sub-phase> the game-thread sub-phase (GameThreadProfile's classifier, applied to async-profiler CPU samples
                      of MainThread) whose ms in this frame exceed its median over the fast frames by the most
  diffuse             nothing above explains 35 % of the excess (the whole step uniformly slower)

Sources: pzopt-pacing.out (frames), asprof.jfr (run.sh --asprof event=cpu,interval=1ms[,alloc=...]; dumped once into
~/.cache/pzopt-slowframes/<run>.dump by harness/slowframes/JfrDump.java), pzopt-gtab.out (devFrameSections / devGtAlternate: per-frame
section timers, shown with --detail), pzopt-bakes.out (bakes in the step), gc.log and <run>/jvm.log.
Several runs: one census table, one column per run.
"""
import argparse, collections, datetime, re, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent

ap = argparse.ArgumentParser()
ap.add_argument("runs", nargs="+")
ap.add_argument("--budget", type=float, default=8.33)
ap.add_argument("--list", type=int, default=0, help="print the N worst frames with their contributions")
ap.add_argument("--detail", type=int, default=12, help="sub-phases / labels per cause in the per-run breakdown")
ap.add_argument("--all", action="store_true", help="the whole recording, not the route window")
ap.add_argument("--min-share", type=float, default=0.35)
ap.add_argument("--quiet", action="store_true", help="only the census table")
ap.add_argument("--near", type=int, default=0, help="per-frame mean ms per game-thread sub-phase (and gtab section) in the "
                "frames within 0.5 s after a chunk burst vs the rest: the N largest differences (chunk pass 2026-10-13)")
a = ap.parse_args()


# ---------------------------------------------------------------- classifier (ported from GameThreadProfile.java)
def load_tables():
    src = None
    for p in (ROOT / "src/pzopt/pzopt/GameThreadProfile.java",):
        if p.exists():
            src = p.read_text()
    tabs = {}
    for name in ("PHASES", "CONTAINERS", "LABELS"):
        m = re.search(r"static final String\[\]\[\] " + name + r" = \{(.*?)\n   \};", src, re.S)
        rows = []
        for r in re.finditer(r"\{([^{}]*)\}", m.group(1)):
            parts = [x.strip() for x in r.group(1).split(",")]
            rows.append([None if x == "null" else x.strip('"') for x in parts])
        tabs[name] = rows
    return tabs["PHASES"], tabs["CONTAINERS"], tabs["LABELS"]


PHASES, CONTAINERS, LABELS = load_tables()


def is_game(c):
    return c.startswith(("zombie.", "se.krka.", "pzopt.", "fmod."))


def split(f):
    i = f.rfind(".")
    return (f[:i], f[i + 1:]) if i > 0 else ("", f)


def short(c, m):
    s = c[c.rfind(".") + 1:] + "." + m
    return "pzopt." + s if c.startswith("pzopt.") else s


def matches(c, m, pc, pm):
    return c.startswith(pc) and (pm is None or pm == m)


_cls_cache = {}


def classify(stack):
    """stack: root-first 'Class.method;...' -> (phase/sub, hot)."""
    r = _cls_cache.get(stack)
    if r:
        return r
    fr = [split(f) for f in stack.split(";")]
    phase = label = child = None
    in_frame = False
    for i, (c, m) in enumerate(fr):
        if not in_frame:
            if c == "zombie.GameWindow" and m == "frameStep":
                in_frame = True
            continue
        if phase is None:
            for pc, pm, pl in PHASES:
                if matches(c, m, pc, pm):
                    phase = pl
                    break
        for lc, lm, ll in LABELS:
            if matches(c, m, lc, lm):
                label = ll
                break
        for cc, cm in CONTAINERS:
            if matches(c, m, cc, cm):
                child = None
                for c2, m2 in fr[i + 1:]:
                    if is_game(c2):
                        child = short(c2, m2)
                        break
                label = None
                break
    hot = "?"
    for c, m in reversed(fr):
        if is_game(c):
            hot = short(c, m)
            break
    if not in_frame:
        phase = "outside frame"
    elif phase is None:
        phase = "frame other"
    sub = label or child or phase
    r = (phase + "/" + sub, hot)
    _cls_cache[stack] = r
    return r


_rcache = {}


def render_label(stack):
    """The render thread's sample: its innermost game / LWJGL frame, plus a driver / native marker for the leaf."""
    r = _rcache.get(stack)
    if r:
        return r
    fr = stack.split(";")
    lab = None
    for f in reversed(fr):
        c, m = split(f)
        if is_game(c):
            lab = short(c, m)
            break
    if lab is None:
        lab = fr[-1][:60]
    for f in reversed(fr):  # the GL entry point it is in, when there is one
        c, m = split(f)
        if c.startswith("org.lwjgl") and (m.startswith("gl") or m.startswith("ngl") or m.startswith("nglfw") or m.startswith("glfw")):
            lab += " > " + m
            break
    leaf = fr[-1]
    if "nvidia" in leaf.lower() or leaf.startswith(("lib", "_nv", "0x")) and "." not in leaf:
        lab += " [driver]"
    _rcache[stack] = lab
    return lab


# ---------------------------------------------------------------- readers
def kvfile(p):
    return dict(l.split("=", 1) for l in p.read_text().splitlines() if "=" in l) if p.exists() else {}


def iso_ms(s):
    return datetime.datetime.fromisoformat(s).timestamp() * 1000.0


def jvm_events(run):
    """(start_epoch_ns, end_epoch_ns, kind) of stop-the-world events from gc.log / jvm.log."""
    ev = []
    for name in ("gc.log", "jvm.log"):
        p = run / name
        if not p.exists():
            continue
        for l in p.read_text(errors="replace").splitlines():
            m = re.match(r"\[([^\]]+)\]\[[\d.]+s\](?:\[[^\]]*\])* (.*)", l)
            if not m:
                continue
            try:
                t = iso_ms(m.group(1))
            except ValueError:
                continue
            msg = m.group(2)
            d = None
            kind = None
            mm = re.search(r"Allocation Stall \((\S+)\) ([\d.]+)ms", msg)
            if mm:
                d, kind = float(mm.group(2)), "alloc stall " + mm.group(1)
            mm = mm or re.search(r"Safepoint \"(\w+)\".*Total: (\d+) ns", msg)
            if mm and kind is None:
                d, kind = int(mm.group(2)) / 1e6, "safepoint " + mm.group(1)
            if kind is None:
                mm = re.search(r"(Pause [\w ()]+?) [\d.]+[MG].*?([\d.]+)ms$", msg) or re.search(r"(Pause [\w ]+) ([\d.]+)ms", msg)
                if mm:
                    d, kind = float(mm.group(2)), mm.group(1).strip()
            if kind and d is not None and d > 0:
                ev.append(((t - d) * 1e6, t * 1e6, kind))
    return ev


def gc_cycles(run):
    """Concurrent ZGC / G1 cycles (start, end ns) from gc.log: 'GC(n) ... ' start line and its end line."""
    p = run / "gc.log"
    if not p.exists():
        return []
    open_, cyc = {}, []
    for l in p.read_text(errors="replace").splitlines():
        m = re.match(r"\[([^\]]+)\]\[[\d.]+s\] GC\((\d+)\) (.*)", l)
        if not m:
            continue
        t = iso_ms(m.group(1)) * 1e6
        n, msg = m.group(2), m.group(3)
        if re.search(r"\d+M\(\d+%\)->\d+M", msg) or re.search(r"\) [\d.]+s$", msg):
            if n in open_:
                cyc.append((open_.pop(n), t, msg.split("(")[0].strip()))
        elif n not in open_:
            open_[n] = t
    return cyc


def load_dump(run, A, B):
    jfr = run / "asprof.jfr"
    if not jfr.exists() and (run / "pzopt.jfr").exists():
        jfr = run / "pzopt.jfr"  # run.sh --jfr: JFR's own samplers (ExecutionSample, NativeMethodSample)
    cache = Path.home() / ".cache/pzopt-slowframes"
    cache.mkdir(parents=True, exist_ok=True)
    dump = cache / (run.resolve().name + ".dump")
    if not jfr.exists():
        return None
    if not dump.exists() or dump.stat().st_mtime < jfr.stat().st_mtime:
        subprocess.run(["java", str(HERE / "slowframes/JfrDump.java"), str(jfr), str(dump), "MainThread,main"], check=True)
    stacks, samples, jev = {}, [], []
    for l in dump.open():
        if l[0] == "s":
            _, i, s = l.rstrip("\n").split(" ", 2)
            stacks[int(i)] = s
        elif l[0] == "x":
            _, k, th, t, i, w = l.split()
            t = int(t)
            if A <= t <= B:
                samples.append((t, k, th, int(i), int(w)))
        elif l[0] == "g":
            _, k, t, d, name = l.split()
            t = int(t)
            jev.append((t, t + int(d) * 1000, k + " " + name))
    samples.sort()
    return stacks, samples, jev


def load_gtab(run):
    p = run / "pzopt-gtab.out"
    if not p.exists():
        return None, []
    hdr, rows = [], []
    for l in p.read_text().splitlines():
        if l.startswith("#"):
            m = re.search(r"ns per section: (.*?)  \(", l)
            if m:
                hdr = m.group(1).split()
            continue
        f = l.split()
        if len(f) > 6:
            rows.append(list(map(int, f)))
    return hdr, rows


# ---------------------------------------------------------------- per run
def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, int(q / 100 * len(v)))] if v else float("nan")


def analyse(run):
    kv = kvfile(run / "pzopt-bench.out")
    A = int(kv["route_start_epoch_ms"]) * 1_000_000
    B = int(kv["route_end_epoch_ms"]) * 1_000_000
    rows = [list(map(int, l.split())) for l in (run / "pzopt-pacing.out").read_text().splitlines() if l and l[0] != "#"]
    # nanoTime -> epoch ns: epoch_ms is stamped at the swap return (floor to the ms), so the largest e*1e6 - swap_ret is the
    # offset to within 1 ms
    off = max(r[0] * 1_000_000 - r[4] for r in rows if r[4])
    # a frozen frame counter anywhere in the run (a hang): the longest gap between presented frames
    gaps = [(rows[i + 1][0] - rows[i][0], rows[i][0]) for i in range(len(rows) - 1)]
    if gaps:
        g_, at_ = max(gaps)
        if g_ > 5000:
            print(f"   WARNING {run.name}: no frame for {g_ / 1000:.1f} s at +{(at_ - A / 1e6) / 1000:.1f} s from the route start (a hang or a "
                  f"loading screen); harness/slowframes/hangwatch.py catches it live")
    if a.all:
        A, B = rows[0][1] + off, rows[-1][1] + off
    frames = []  # (start_ns_epoch, end_ns_epoch, step_ms, acq, swapcall, swapret, wait, gpu_done)
    for i in range(len(rows) - 1):
        r, n = rows[i], rows[i + 1]
        if not r[1] or not n[1]:
            continue
        s, e = r[1] + off, n[1] + off
        if s < A or e > B:
            continue
        frames.append(dict(s=s, e=e, step=(n[1] - r[1]) / 1e6, render=(r[3] - r[2] - r[5]) / 1e6 if r[3] else 0.0,
                           swap=(r[4] - r[3]) / 1e6 if r[4] else 0.0, gpu=(r[6] - r[3]) / 1e6 if r[6] else 0.0, bakes=0,
                           creates=0, raw=r))
    if not frames:
        print(f"{run.name}: no frames in the window", file=sys.stderr)
        return None
    steps = [f["step"] for f in frames]
    med = pct(steps, 50)
    # bakes (frame-end ns on the nanoTime clock)
    bp = run / "pzopt-bakes.out"
    if bp.exists():
        bk = []
        for l in bp.open():
            if not l[0].isdigit():
                continue
            p = l.split()
            bk.append((int(p[0]) + off, sum(map(int, p[1:])), int(p[1])))
        bk.sort()
        j = 0
        for f in frames:
            while j < len(bk) and bk[j][0] < f["s"]:
                j += 1
            k = j
            while k < len(bk) and bk[k][0] < f["e"]:
                f["bakes"] += bk[k][1]
                f["creates"] += bk[k][2]
                k += 1
    # stop-the-world events
    stw = jvm_events(run)
    cyc = gc_cycles(run)
    # chunk publications (pzopt-chunks.out, microseconds since Stats init; pzopt-frames.out's anchors give that clock's epoch)
    chunks = []
    try:
        anc = next(l.split() for l in (run / "pzopt-frames.out").open() if l.startswith("# anchor"))
        t0us = int(anc[2]) - int(anc[3])
        for l in (run / "pzopt-chunks.out").read_text().splitlines()[1:]:
            if not l or l[0] == "#":
                continue
            p_ = l.split("\t")
            chunks.append((t0us + int(p_[0]) + sum(int(x) for x in p_[7:12])) * 1000)
        chunks.sort()
    except (StopIteration, OSError, ValueError, IndexError):
        pass
    # other processes' CPU (sysmon: machine cpu_pct of all logical CPUs minus the game's own), per 0.5 s sample
    ext = []
    try:
        import os
        ncpu = os.cpu_count() or 16
        L = (run / "sysmon.csv").read_text().splitlines()
        h = L[0].split(",")
        for l in L[1:]:
            d_ = dict(zip(h, l.split(",")))
            if not d_.get("cpu_pct") or not d_.get("game_cpu_pct"):
                continue
            ext.append((int(d_["epoch_ms"]) * 1_000_000, float(d_["cpu_pct"]) * ncpu / 100 - float(d_["game_cpu_pct"]) / 100))
    except (OSError, KeyError, ValueError, IndexError):
        pass
    import bisect
    ext_t = [e_[0] for e_ in ext]
    for f in frames:
        j_ = bisect.bisect_left(ext_t, f["e"])
        f["ext"] = ext[j_][1] if j_ < len(ext) else 0.0
        f["chunks"] = bisect.bisect_left(chunks, f["e"]) - bisect.bisect_left(chunks, f["s"]) if chunks else 0
        f["incyc"] = any(c0 <= f["e"] and c1 >= f["s"] for c0, c1, _ in cyc)
    d = load_dump(run, A - 50_000_000, B + 50_000_000)
    interval = None
    if d:
        stacks, samples, jev = d
        stw += [x for x in jev if not x[2].startswith("GarbageCollection")]
        mt = [t for t, k, th, i, w in samples if k == "c" and th == "MainThread"]
        gaps = sorted(b - a_ for a_, b in zip(mt, mt[1:]) if 0 < b - a_ < 20_000_000)
        interval = pct(gaps, 25) / 1e6 if gaps else 1.0
        # bin samples into frames (game thread by step window; render thread by the same window)
        j = 0
        for f in frames:
            f["gt"] = collections.Counter()
            f["rt"] = collections.Counter()
            f["rtn"] = collections.Counter()
            f["al"] = collections.Counter()
            f["gt_n"] = 0
            f["handoff"] = 0
            while j < len(samples) and samples[j][0] < f["s"]:
                j += 1
            k = j
            while k < len(samples) and samples[k][0] < f["e"]:
                t, kind, th, i, w = samples[k]
                st = stacks[i]
                if th == "MainThread" and kind == "c":
                    sub, hot = classify(st)
                    f["gt"][sub] += 1
                    f["gt_n"] += 1
                    if "frame hand-off" in sub:
                        f["handoff"] += 1
                elif th == "main" and kind == "c":
                    f["rt"][render_label(st)] += 1
                elif th == "main" and kind == "n":
                    f["rtn"][render_label(st)] += 1
                elif kind == "a":
                    f["al"][classify(st)[0] if th == "MainThread" else "render:" + render_label(st)] += w
                k += 1
    # gtab rows: matched by the next step start (GtAb.frame runs in Pacing.stepStart)
    ghdr, grows = load_gtab(run)
    if grows:
        gi = 0
        for f in frames:
            e_ms = f["e"] / 1e6
            while gi < len(grows) and grows[gi][0] < e_ms - 1.5:
                gi += 1
            if gi < len(grows) and abs(grows[gi][0] - e_ms) <= 1.5 and abs(grows[gi][4] / 1e6 - f["step"]) < 0.6:
                f["gtab"] = grows[gi]
    fast = [f for f in frames if f["step"] <= med]
    base_gt = collections.Counter()
    base_rt = collections.Counter()
    if d:
        for f in fast:
            base_gt.update(f["gt"])
            base_rt.update(f["rt"])
        nfast = max(1, len(fast))
        base_gt = {k: v / nfast * interval for k, v in base_gt.items()}
        base_rt = {k: v / nfast * interval for k, v in base_rt.items()}
        base_cpu = sum(f["gt_n"] for f in fast) / nfast * interval
        base_off = sum(f["step"] for f in fast) / nfast - base_cpu
        base_hand = sum(f["handoff"] for f in fast) / nfast * interval
    # gtab per-frame columns: medians over the fast frames (ns sections, the OS counters)
    COUNTS = ("zombie_updates", "pu_zombies", "pu_moved", "pu_collided", "bake_t0", "bake_t1", "bake_t2", "bake_t3",
              "bake_offered", "minflt", "majflt", "rtGenTex", "newTex")
    CONTAINER_SECTIONS = ("logic", "renderInternal", "cellRender", "postupdate", "tl_pass")  # hold other sections
    gcol = {n: i for i, n in enumerate(ghdr)} if ghdr else {}
    gbase = {}
    gfast = [f["gtab"] for f in fast if "gtab" in f]
    if gfast and gcol:
        for n_, i_ in gcol.items():
            gbase[n_] = pct([g[6 + i_] for g in gfast if 6 + i_ < len(g)], 50) / 1e6
        gbase["offcpu"] = pct([(g[4] - g[2]) for g in gfast], 50) / 1e6
    contrib_sum = collections.Counter()
    census = collections.Counter()
    census16 = collections.Counter()
    excess_ms = collections.Counter()
    detail = collections.defaultdict(collections.Counter)
    slow = []
    for f in frames:
        if f["step"] <= a.budget:
            continue
        x = f["step"] - med
        contrib = {}
        ov = 0.0
        kinds = collections.Counter()
        for s0, s1, k in stw:
            o = min(s1, f["e"]) - max(s0, f["s"])
            if o > 0:
                ov += o / 1e6
                kinds[k.split()[0] + " " + (k.split()[1] if len(k.split()) > 1 else "")] += o / 1e6
        if ov > 0:
            contrib["gc/safepoint"] = ov
        sub_excess = {}
        if d:
            cpu = f["gt_n"] * interval
            off_cpu = max(0.0, f["step"] - cpu - base_off - ov)
            hand = f["handoff"] * interval - base_hand
            # the hand-off wait spins / parks in pushFrameDown: CPU samples there and off-CPU time both count as waiting
            # for the render thread when the hand-off itself shows samples, or when the render thread was busy beyond
            # its median in this window
            rt_ex = {k: v * interval - base_rt.get(k, 0.0) for k, v in f["rt"].items()}
            rt_top = max(rt_ex, key=rt_ex.get) if rt_ex else "?"
            render_wait = max(0.0, hand) + (off_cpu if f["handoff"] > 0 else 0.0)
            if render_wait > 0:
                contrib["render: " + rt_top] = render_wait
            if f["handoff"] == 0 and off_cpu > 0:
                contrib["off-cpu"] = off_cpu
            for k, v in f["gt"].items():
                if "frame hand-off" in k:
                    continue
                e_ = v * interval - base_gt.get(k, 0.0)
                if e_ > 0:
                    sub_excess[k] = e_
            for k, v in sub_excess.items():
                contrib[k] = v
        g = f.get("gtab")
        if g and gbase:
            v = lambda n_: g[6 + gcol[n_]] if n_ in gcol and 6 + gcol[n_] < len(g) else 0
            if "runq_ns" in gcol:
                rq = v("runq_ns") / 1e6 - gbase["runq_ns"]
                if rq > 0:
                    contrib["os: run-queue wait" + (" (other processes busy)" if f.get("ext", 0.0) > 2.0 else "")] = rq
                    # the run-queue wait is part of the off-CPU time: take it out of the off-CPU contribution
                    if "off-cpu" in contrib:
                        contrib["off-cpu"] = max(0.0, contrib["off-cpu"] - rq)
                if v("majflt") > 0:
                    kinds["major faults"] += v("majflt")
            if "handoff" in gcol:
                # measured waits replace the sampled estimates: the hand-off (the render thread is behind), the worker
                # batch joins, and whatever else the game thread spent off-CPU (locks, parks, the OS)
                rt_lab = None
                if d:
                    rt_ex = {k: vv * interval - base_rt.get(k, 0.0) for k, vv in f["rt"].items()}
                    rt_lab = max(rt_ex, key=rt_ex.get) if rt_ex else None
                for k_ in [k_ for k_ in contrib if k_.startswith("render: ") or k_ == "off-cpu"]:
                    del contrib[k_]
                ho = v("handoff") / 1e6 - gbase["handoff"]
                if ho > 0:
                    contrib["render: " + (rt_lab or "hand-off wait")] = ho
                    if v("rtGenTex") > 1_000_000:
                        kinds["glGenTextures > 1 ms in the frame"] += 1
                bw = v("batchWait") / 1e6 - gbase["batchWait"]
                if bw > 0:
                    contrib["wait: frame-worker batch join"] = bw
                oc = ((g[4] - g[2]) - v("handoff") - v("batchWait")) / 1e6 - (gbase["offcpu"] - gbase["handoff"] - gbase["batchWait"])
                oc -= max(0.0, contrib.get("os: run-queue wait", 0.0))
                oc -= ov  # a stop-the-world pause is off-CPU time too: it counts as gc/safepoint
                if oc > 0:
                    contrib["off-cpu (other)"] = oc
            if not d:
                oc = (g[4] - g[2]) / 1e6 - gbase["offcpu"] - max(0.0, contrib.get("os: run-queue wait", 0.0))
                if oc > 0:
                    contrib["off-cpu"] = oc
                for n_ in gcol:
                    if n_ in COUNTS or n_ in CONTAINER_SECTIONS or n_ in ("runq_ns", "handoff", "batchWait", "rtGenTex", "newTex"):
                        continue
                    e_ = v(n_) / 1e6 - gbase.get(n_, 0.0)
                    if e_ > 0:
                        contrib["section " + n_] = e_
        if not contrib:
            cause = "unattributed (no samples)"
        else:
            cause = max(contrib, key=contrib.get)
            if contrib[cause] < a.min_share * x:
                cause = "diffuse"
        # coarse family for the census table
        fam = cause
        if cause.startswith("render: "):
            fam = "render thread"
        census[fam] += 1
        if f["step"] > 16.7:
            census16[fam] += 1
        excess_ms[fam] += x
        detail[fam][cause if fam != cause else (max(sub_excess, key=sub_excess.get) if cause == "diffuse" and sub_excess else cause)] += 1
        for k in kinds:
            if fam == "gc/safepoint" or k == "major faults" or k.startswith("glGen"):
                detail[fam]["  " + k] += 1
        slow.append((f["step"], f, contrib, cause))
        for k_, v_ in contrib.items():
            contrib_sum[k_] += v_
    # results
    n = len(frames)
    gt_med = None
    res = dict(name=run.name, n=n, med=med, p90=pct(steps, 90), p99=pct(steps, 99), p999=pct(steps, 99.9),
               mx=max(steps), slow=sum(1 for s in steps if s > a.budget), slow16=sum(1 for s in steps if s > 16.7),
               census=census, census16=census16, excess=excess_ms, contrib=contrib_sum, detail=detail, interval=interval,
               cycles=sum(1 for c0, c1, _ in cyc if c1 > A and c0 < B), stw=sum(1 for s0, s1, k in stw if A <= s0 <= B))
    if not a.quiet:
        print(f"== {run.name}: {n} frames, game step p50 {med:.2f} p90 {res['p90']:.2f} p99 {res['p99']:.2f} "
              f"p99.9 {res['p999']:.2f} max {res['mx']:.1f} ms; {res['slow']} > {a.budget} ms, {res['slow16']} > 16.7 ms"
              + (f"; CPU samples every {interval:.2f} ms" if interval else "; no asprof.jfr: GC / off-CPU only"))
        print(f"   gc.log: {res['cycles']} collector cycles overlap the window, {res['stw']} stop-the-world / stall / safepoint events in it")
        exf = [f for f in frames if f.get("ext", 0.0) > 2.0]
        if exf:
            print(f"   other processes used > 2 cores during {len(exf)} frames ({100 * len(exf) / n:.0f} %), slow among them "
                  f"{100 * sum(1 for f in exf if f['step'] > a.budget) / len(exf):.0f} %: treat that window as machine noise")
        inc = [f for f in frames if f["incyc"]]
        if inc:
            print(f"   frames inside a concurrent GC cycle: {len(inc)} ({100 * len(inc) / n:.0f} %), slow among them "
                  f"{100 * sum(1 for f in inc if f['step'] > a.budget) / len(inc):.0f} % vs {100 * sum(1 for f in frames if not f['incyc'] and f['step'] > a.budget) / max(1, n - len(inc)):.0f} % outside")
        if chunks:
            # slow-frame share in the 1.5 s after each chunk-row arrival vs elsewhere
            rows_ = []
            for t in (c for c in chunks if A <= c <= B):
                if not rows_ or t - rows_[-1] > 300_000_000:
                    rows_.append(t)
            near = [f for f in frames if any(0 <= f["s"] - r <= 500_000_000 for r in rows_)]
            print(f"   chunk arrivals: {len(chunks)} chunks in {len(rows_)} bursts; frames within 0.5 s after a burst: {len(near)} "
                  f"({100 * sum(1 for f in near if f['step'] > a.budget) / max(1, len(near)):.0f} % slow) vs the rest "
                  f"({100 * sum(1 for f in frames if f not in near and f['step'] > a.budget) / max(1, n - len(near)):.0f} % slow)")
            if a.near and near:
                nearid = set(id(f) for f in near)
                rest = [f for f in frames if id(f) not in nearid]
                ms = lambda fs: sum(f["step"] for f in fs) / max(1, len(fs))
                cn_, cr_ = collections.Counter(), collections.Counter()
                for st_, f_, ct_, cause_ in slow:
                    (cn_ if id(f_) in nearid else cr_)[cause_.split(":")[0] if cause_.startswith("render:") else cause_] += 1
                print("   slow-frame causes near a burst (per 1000 near frames) vs the rest (per 1000 other frames):")
                for k_, v_ in (cn_ + cr_).most_common(a.near):
                    print(f"       {k_:56} {1000 * cn_[k_] / len(near):6.1f} vs {1000 * cr_[k_] / max(1, len(rest)):6.1f}")
                print(f"   near-burst step mean {ms(near):.2f} ms vs rest {ms(rest):.2f} ms (+{ms(near) - ms(rest):.2f})")
                if d:
                    def mean_gt(fs):
                        c = collections.Counter()
                        for f in fs:
                            for k, v in f["gt"].items():
                                c[k] += v * interval
                        return {k: v / max(1, len(fs)) for k, v in c.items()}
                    mn, mr = mean_gt(near), mean_gt(rest)
                    diff = sorted(((mn.get(k, 0) - mr.get(k, 0), k) for k in set(mn) | set(mr)), reverse=True)
                    print(f"   near-burst minus rest, game-thread CPU ms a frame (asprof): total "
                          f"{sum(mn.values()) - sum(mr.values()):+.2f}")
                    for dv, k in diff[:a.near]:
                        print(f"       {k:56} {mn.get(k, 0):6.3f} vs {mr.get(k, 0):6.3f}  {dv:+.3f}")
                    print(f"       ... most negative: " + ", ".join(f"{k} {dv:+.3f}" for dv, k in diff[-3:]))
                gn = [f["gtab"] for f in near if "gtab" in f]
                gr = [f["gtab"] for f in rest if "gtab" in f]
                if gn and gr and gcol:
                    rowsd = []
                    for n_, i_ in gcol.items():
                        mn_ = sum(g[6 + i_] for g in gn if 6 + i_ < len(g)) / len(gn)
                        mr_ = sum(g[6 + i_] for g in gr if 6 + i_ < len(g)) / len(gr)
                        sc = 1 if n_ in ("minflt", "majflt", "rtGenTex", "newTex", "bake_t0", "bake_t1", "bake_t2", "bake_t3",
                                         "bake_offered", "pu_zombies", "pu_moved", "pu_collided", "z_flight_n") else 1e6
                        rowsd.append(((mn_ - mr_) / sc, n_, mn_ / sc, mr_ / sc))
                    rowsd.sort(reverse=True)
                    print("   near-burst minus rest, gtab columns (ms a frame, counts as counts):")
                    for dv, n_, x, y in rowsd[:a.near]:
                        print(f"       {n_:24} {x:8.3f} vs {y:8.3f}  {dv:+.3f}")
        if gcol and "runq_ns" in gcol and any("gtab" in f for f in frames):
            gs = [f for f in frames if "gtab" in f]
            rq = [f["gtab"][6 + gcol["runq_ns"]] / 1e6 for f in gs]
            sl = [f for f in gs if f["step"] > a.budget]
            print(f"   game thread run-queue wait: mean {sum(rq) / len(rq):.2f} ms a frame, p99 {pct(rq, 99):.2f}; in slow frames mean "
                  f"{sum(f['gtab'][6 + gcol['runq_ns']] for f in sl) / max(1, len(sl)) / 1e6:.2f}; major faults {sum(f['gtab'][6 + gcol['majflt']] for f in gs)}, "
                  f"minor faults {sum(f['gtab'][6 + gcol['minflt']] for f in gs)}")
        tot = max(1, res["slow"])
        for fam, c in census.most_common():
            print(f"   {fam:46} {c:5} frames ({100 * c / tot:4.1f} %), {census16[fam]:4} > 16.7 ms, excess {excess_ms[fam]:7.0f} ms")
            for k, v in detail[fam].most_common(a.detail):
                if k != fam:
                    print(f"       {k:60} {v:5}")
        gs = [f for f in frames if "gtab" in f]
        if len(gs) > 100 and gcol:
            # which timed sections carry the frame-to-frame variance: cov(section, step) / var(step), all frames and the
            # frames up to 30 ms (the p90 region, the spikes left out)
            def shares(fs):
                st = [f["step"] for f in fs]
                m = sum(st) / len(st)
                var = sum((x - m) ** 2 for x in st) / len(st) or 1.0
                out = []
                for n_, i_ in gcol.items():
                    if n_ in COUNTS or n_ in CONTAINER_SECTIONS:
                        continue
                    col = [f["gtab"][6 + i_] / 1e6 for f in fs]
                    mc = sum(col) / len(col)
                    out.append((sum((a_ - mc) * (b_ - m) for a_, b_ in zip(col, st)) / len(st) / var, n_))
                return sorted(out, reverse=True)
            print("   share of the step's variance (cov / var): all frames " + ", ".join(f"{n_} {100 * v_:.0f}%" for v_, n_ in shares(gs)[:8]))
            low = [f for f in gs if f["step"] <= 30]
            print("   frames <= 30 ms:                         " + ", ".join(f"{n_} {100 * v_:.0f}%" for v_, n_ in shares(low)[:8]))
        tot_ex = sum(f["step"] - med for _, f, _, _ in slow)
        print(f"   excess over the median in all slow frames: {tot_ex:.0f} ms; summed contributions (a frame counts in several):")
        for k_, v_ in contrib_sum.most_common(a.detail * 2):
            print(f"       {k_:60} {v_:7.0f} ms ({100 * v_ / max(1.0, tot_ex):4.1f} %)")
        if a.list:
            print(f"   worst {a.list} frames:")
            for step, f, contrib, cause in sorted(slow, key=lambda x: -x[0])[: a.list]:
                top = ", ".join(f"{k} {v:.1f}" for k, v in sorted(contrib.items(), key=lambda kv: -kv[1])[:4])
                extra = (f" EXT {f['ext']:.1f} cores" if f.get("ext", 0) > 2 else "") + f" render {f['render']:.1f} swap {f['swap']:.1f} gpu {f['gpu']:.1f} bakes {f['bakes']} creates {f['creates']} chunks {f['chunks']}" + (" gc-cycle" if f["incyc"] else "")
                if "gtab" in f and "runq_ns" in gcol:
                    gg = f["gtab"]
                    extra += f" runq {gg[6 + gcol['runq_ns']] / 1e6:.1f} majflt {gg[6 + gcol['majflt']]} offcpu {(gg[4] - gg[2]) / 1e6:.1f}"
                    if "newTex" in gcol:
                        extra += f" newTex {gg[6 + gcol['newTex']]}"
                if "gtab" in f and ghdr:
                    g = f["gtab"][6:]
                    secs = sorted(((g[i] / 1e6, ghdr[i]) for i in range(min(len(g), len(ghdr))) if not ghdr[i].startswith(("bake_", "pu_z", "pu_m", "pu_c"))), reverse=True)[:4]
                    extra += " | " + " ".join(f"{nm} {v:.1f}" for v, nm in secs)
                print(f"     +{(f['s'] - A) / 1e9:6.2f}s {step:6.1f} ms -> {cause}: {top} |{extra}")
                if f.get("rtn"):
                    print("         render thread in native: " + ", ".join(f"{k} {v}" for k, v in f["rtn"].most_common(4)))
            if d:
                al = collections.Counter()
                for step, f, contrib, cause in slow:
                    al.update(f["al"])
                if al:
                    print("   allocation in slow frames (sampled bytes by sub-phase):")
                    for k, v in al.most_common(8):
                        print(f"       {k:60} {v / 1e6:9.1f} MB")
    return res


results = [r for r in (analyse(Path(x)) for x in a.runs) if r]
if len(results) > 1:
    fams = sorted({k for r in results for k in r["census"]}, key=lambda k: -sum(r["census"][k] for r in results))
    w = 16
    print("\n== slow-frame census (frames with game step > %.2f ms)" % a.budget)
    print(f"   {'':40}" + "".join(f"{r['name'][:w - 1]:>{w}}" for r in results))
    for lab, key in (("p50 / p90 ms", None),):
        print(f"   {'p50 / p90':40}" + "".join(f"{r['med']:>7.2f} /{r['p90']:>6.2f}" for r in results))
        print(f"   {'p99 / p99.9':40}" + "".join(f"{r['p99']:>7.1f} /{r['p999']:>6.1f}" for r in results))
        print(f"   {'max':40}" + "".join(f"{r['mx']:>{w}.1f}" for r in results))
        print(f"   {'frames / slow / > 16.7':40}" + "".join(f"{str(r['n']) + '/' + str(r['slow']) + '/' + str(r['slow16']):>{w}}" for r in results))
    for k in fams[:a.detail * 2]:
        print(f"   {k[:40]:40}" + "".join(f"{r['census'][k]:>{w}}" for r in results))
    print("   -- summed contributions in slow frames, ms (a frame counts in several)")
    keys = sorted({k for r in results for k in r["contrib"]}, key=lambda k: -max(r["contrib"][k] for r in results))
    for k in keys[:a.detail * 2]:
        print(f"   {k[:40]:40}" + "".join(f"{r['contrib'][k]:>{w}.0f}" for r in results))
