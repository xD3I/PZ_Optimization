#!/usr/bin/env python3
"""How much of the game thread does zombie work, wherever the profiler files it (2026-10-12).

  harness/zombieshare.py <run-dir> [<run-dir> ...] [--source auto|asprof|stacks] [--detail N] [--leaves N]

Reads the game thread's samples over the route window: the MainThread of `asprof.jfr` (a `run.sh --asprof
event=cpu,interval=...` run: unbiased, 200+ Hz; converted with jfrconv, cached next to the run) or, without one,
`pzopt-stacks.out` (the in-game 25 Hz sampler, safepoint-biased: it locates regions, not lines).

Every stack is put in one bucket by its OUTERMOST telling frame (root -> leaf): the first entity frame
(IsoZombie / IsoPlayer / IsoAnimal / BaseVehicle) decides whose work it is; before one is met, a zombie container
frame (the postupdate batches, the separation / update batches, ZombieGroup, sceneCullZombies, the characters draw,
the zombie vocals, the population manager) files it. Stacks under the player (its LOS loop walks every zombie) or a
vehicle stay theirs. The "listed" total is the goal's metric: zombie update + postupdate + groups + render; the
"adjacent" lines (population spawn / reuse, the scheduler's per-object classify) are printed beside it.

Prints per bucket the share of all game-thread samples and ms per frame (mean frame from pzopt-bench.out), then
the top frames under each zombie bucket (--detail) and the hottest leaves of zombie work (--leaves).
"""
import argparse
import os
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from flamegraph import read_stacks, route_window  # noqa: E402

JFRCONV = os.path.expanduser("~/.local/share/async-profiler/async-profiler-4.1-linux-x64/bin/jfrconv")


def norm(f):
    f = f.rsplit("_[", 1)[0]
    if "/" in f and "." in f:
        f = f.rsplit("/", 1)[1]
    if f.startswith("pzopt."):
        f = f[6:]
    return f


def asprof_stacks(run, win):
    jfr = Path(run) / "asprof.jfr"
    if not (jfr.exists() and win and Path(JFRCONV).exists()):
        return None
    cache = Path(run) / f".asprof-collapsed-{win[0]}-{win[1]}.txt"
    if not cache.exists():
        subprocess.run([JFRCONV, "--cpu", "-t", "--from", str(win[0]), "--to", str(win[1]), "-o", "collapsed",
                        str(jfr), str(cache)], capture_output=True, timeout=600)
    if not cache.exists():
        return None
    stacks = Counter()
    for line in cache.read_text(errors="replace").splitlines():
        st, _, n = line.rpartition(" ")
        fr = st.split(";")
        if n.isdigit() and fr[0].startswith("[MainThread"):
            stacks[tuple(norm(f) for f in fr[1:])] += int(n)
    return stacks


# bucket, zombie?  (zombie = counts toward the goal's metric; adjacent = printed beside it)
LISTED = ["zombie update", "zombie update batch (game thread's share + waits)", "separation batch",
          "zombie postupdate", "postupdate batches (action eval / anim / movement)", "zombie groups",
          "zombie vocals", "zombie render (render / renderlast)", "characters draw", "sceneCullZombies"]
ADJACENT = ["zombie population (spawn / reuse)", "scheduler classify"]


def classify(st):
    in_render = False
    post = False
    for i, f in enumerate(st):
        cls = f.split(".", 1)[0]
        if f.startswith("GameWindow.renderInternal") or f.startswith("IngameState.render"):
            in_render = True
        if cls == "IsoZombie":
            if in_render:
                return "zombie render (render / renderlast)" if not any(
                    g.startswith(("FBORenderCell.renderMovingObject", "FBORenderCell.renderPlayer", "CharDraw."))
                    for g in st[:i]) else "characters draw"
            if post:
                return "zombie postupdate"
            if any(g.startswith("UpdateBatch.") and not g.startswith("UpdateBatch.runInlinePhase") for g in st[:i]):
                return "zombie update batch (game thread's share + waits)"
            return "zombie update"
        if cls == "IsoPlayer":
            return "player"
        if cls == "IsoAnimal" or cls.startswith("Animal"):
            return "animals"
        if cls == "BaseVehicle":
            return "vehicles"
        if f.startswith("MovingObjectUpdateScheduler.postupdate"):
            post = True
            continue
        if cls in ("ActionEval", "AnimParallel", "AnimBatch", "PostupdateBatch", "ShadowPrep") and not in_render:
            # a batch the game thread runs or waits on; the entity frame under it may be a worker task
            rest = st[i:]
            if any(g.split(".", 1)[0] in ("IsoPlayer",) for g in rest):
                return "player"
            if any(g.split(".", 1)[0] in ("IsoAnimal",) for g in rest):
                return "animals"
            return "postupdate batches (action eval / anim / movement)"
        if cls == "SeparateBatch":
            return "separation batch"
        if cls == "UpdateBatch" and not f.startswith("UpdateBatch.runInlinePhase"):
            rest = st[i:]
            if any(g.split(".", 1)[0] == "IsoPlayer" for g in rest):
                return "player"
            if any(g.split(".", 1)[0] == "IsoAnimal" for g in rest):
                return "animals"
            return "zombie update batch (game thread's share + waits)"
        if cls in ("ZombieGroupManager", "ZombieGroup"):
            return "zombie groups"
        if f.startswith("IsoCell.updateZombieVocals"):
            return "zombie vocals"
        if f.startswith("IsoWorld.sceneCullZombies"):
            return "sceneCullZombies"
        if f.startswith(("FBORenderCell.renderMovingObject", "FBORenderCell.renderPlayer")) or cls == "CharDraw":
            rest = st[i:]
            if any(g.split(".", 1)[0] == "IsoPlayer" for g in rest):
                return "player"
            if any(g.split(".", 1)[0] == "IsoAnimal" for g in rest):
                return "animals"
            return "characters draw"
        if cls in ("ZombiePopulationManager", "VirtualZombieManager"):
            return "zombie population (spawn / reuse)"
        if f.startswith(("MovingObjectUpdateScheduler.startFrame", "MovingObjectUpdateScheduler.pzoptStartFrame")):
            return "scheduler classify"
    return "other"


def bench_kv(run):
    p = Path(run) / "pzopt-bench.out"
    if not p.exists():
        return {}
    return dict(l.split("=", 1) for l in p.read_text(errors="replace").splitlines() if "=" in l)


def mean_frame_ms(run, win):
    p = Path(run) / "pzopt-overlay.out"
    if p.exists() and win:
        hdr, vals = None, []
        for line in p.read_text(errors="replace").splitlines():
            parts = line.replace(",", " ").split()
            if not parts:
                continue
            if hdr is None and "epoch_ms" in parts:
                hdr = parts
                continue
            if hdr and len(parts) == len(hdr):
                try:
                    row = dict(zip(hdr, parts))
                    t = float(row["epoch_ms"])
                    if win[0] <= t <= win[1]:
                        vals.append(float(row.get("frametime", row.get("frame_ms", "nan"))))
                except (KeyError, ValueError):
                    pass
        vals = [v for v in vals if v == v]
        if vals:
            return sum(vals) / len(vals)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--source", choices=["auto", "asprof", "stacks"], default="auto")
    ap.add_argument("--detail", type=int, default=6, help="top frames under each zombie bucket")
    ap.add_argument("--leaves", type=int, default=25, help="hottest leaf frames of zombie work")
    a = ap.parse_args()
    for run in a.runs:
        win = route_window(run)
        stacks, src = None, None
        if a.source in ("auto", "asprof"):
            stacks = asprof_stacks(run, win)
            src = "asprof cpu"
        if not stacks:
            if a.source == "asprof":
                print(f"== {Path(run).name}: no asprof.jfr")
                continue
            _, raw, _, _, _ = read_stacks(run, win)
            stacks = Counter({tuple(norm(f) for f in k): v for k, v in raw.items()})
            src = "pzopt-stacks.out 25 Hz (safepoint-biased)"
        total = sum(stacks.values()) or 1
        fms = mean_frame_ms(run, win)
        kv = bench_kv(run)
        buckets = Counter()
        under = defaultdict(Counter)
        leaves = Counter()
        for st, c in stacks.items():
            b = classify(st)
            buckets[b] += c
            if b in LISTED:
                leaves[st[-1]] += c
                # the frame just under the bucket's anchor: first frame after the outermost zombie-ish frame
                key = next((st[j] for j in range(len(st)) if classify(st[:j + 1]) == b), st[-1])
                k = st.index(key)
                under[b][" > ".join(st[k:k + 3])] += c
        pct = lambda c: 100.0 * c / total
        ms = lambda c: f"{pct(c) / 100 * fms:6.3f} ms" if fms else ""
        print(f"== {Path(run).name}: {total} game-thread samples ({src}); mean frame "
              + (f"{fms:.2f} ms" if fms else "?") + f"; zombies_loaded={kv.get('zombies_loaded', '?')}")
        listed = sum(buckets[b] for b in LISTED)
        adj = sum(buckets[b] for b in ADJACENT)
        print(f"   ZOMBIE SHARE (listed): {pct(listed):5.1f} %  {ms(listed)}/frame    + adjacent {pct(adj):4.1f} %"
              f" = {pct(listed + adj):5.1f} %")
        for b in LISTED + ADJACENT + ["player", "animals", "vehicles", "other"]:
            if buckets[b]:
                tag = "  " if b in LISTED else ("~ " if b in ADJACENT else "  (not zombie) ")
                print(f"   {pct(buckets[b]):5.1f} %  {ms(buckets[b])}  {tag}{b}")
                if a.detail and (b in LISTED or b in ADJACENT):
                    for k, c in under[b].most_common(a.detail):
                        print(f"            {pct(c):5.2f} %  {k}")
        if a.leaves:
            print("   hottest leaves of zombie work:")
            for k, c in leaves.most_common(a.leaves):
                print(f"     {pct(c):5.2f} %  {k}")


if __name__ == "__main__":
    main()
