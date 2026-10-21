#!/usr/bin/env python3
"""What the UI costs and how fast it answers, from Zomboid/pzopt-ui.out (pzopt.UiProfile, key uiProfile, on in
instrumented runs) of a run.

1. Steady state over the route window (or --all): UI time per game frame (UIManager.update every frame + UIManager.render
   on UI frames) as a share of the frame time, UI frames per second, per-UI-frame render time, and a per-element table
   (top-level UI elements by their Lua Type): renders/s, microseconds per render and per game frame, draw commands per
   render, and how many renders produced exactly the previous render's commands ("same").
2. Interactions (the harness UI rig, harness/mod/.../pzopt_harness_ui.lua, flag ui_script=interactions): per step the
   Lua handler's microseconds, the delay from the handler to the end of the next UI render (when the change reaches the
   UI texture; the screen shows it with that frame), the frame that ran the handler, and the worst frame in the 500 ms after.

Usage: uiprof.py RUN [--all] [--top 20] [--json out.json]
"""
import argparse
import collections
import json
import statistics as st
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("run")
ap.add_argument("--all", action="store_true", help="whole run instead of the route window")
ap.add_argument("--top", type=int, default=20)
ap.add_argument("--json")
ap.add_argument("--calls", type=int, default=0, help="per scene, the N element types with the most Lua call time")
a = ap.parse_args()
run = Path(a.run)
lines = (run / "pzopt-ui.out").read_text(errors="replace").splitlines()
kv = {}
bench = run / "pzopt-bench.out"
if bench.exists():
    kv = dict(l.split("=", 1) for l in bench.read_text().splitlines() if "=" in l)
A = int(kv.get("route_start_epoch_ms", 0)) if not a.all else 0
B = int(kv.get("route_end_epoch_ms", 1 << 62)) if not a.all else 1 << 62


def fields(l):
    return dict(p.split("=", 1) for p in l.split()[2:] if "=" in p)


# 1. steady state
secs = []
elems = collections.defaultdict(lambda: [0, 0, 0, 0, 0, 0, 0])  # renders, us, max_us, sprites, same, replays, replay_us
ret = collections.Counter()
stale = collections.Counter()
cur_in = False
for l in lines:
    if l.startswith("T "):
        t = int(l.split()[1])
        cur_in = A + 1000 <= t <= B
        if cur_in:
            f = fields(l)
            secs.append({k: int(v) for k, v in f.items() if "/" not in v})
    elif l.startswith("E ") and cur_in:
        p = l.split()
        f = dict(x.split("=", 1) for x in p[2:])
        e = elems[p[1]]
        e[0] += int(f["renders"]); e[1] += int(f["us"]); e[2] = max(e[2], int(f["max_us"]))
        e[3] += int(f["sprites"]); e[4] += int(f["same"])
        e[5] += int(f.get("replays", 0)); e[6] += int(f.get("replay_us", 0))
    elif l.startswith("U ") and cur_in:
        for k, v in (x.split("=", 1) for x in l.split()[2:]):
            ret[k] += int(v)
    elif l.startswith("S ") and cur_in:
        stale[l.split()[2]] += 1
out = {}
if secs:
    frames = sum(s["frames"] for s in secs)
    uif = sum(s["uiframes"] for s in secs)
    upd = sum(s["update_us"] for s in secs)
    ren = sum(s["render_us"] for s in secs)
    n = len(secs)
    frame_us = n * 1e6 / max(frames, 1)
    ui_per_frame = (upd + ren) / max(frames, 1)
    out["steady"] = dict(seconds=n, fps=frames / n, ui_fps=uif / n, update_us_per_frame=upd / max(frames, 1),
                         render_us_per_ui_frame=ren / max(uif, 1), render_max_us=max(s["render_max_us"] for s in secs),
                         ui_us_per_frame=ui_per_frame, ui_pct_of_frame=100 * ui_per_frame / frame_us,
                         sprites_per_ui_frame=st.mean(s["sprites"] for s in secs))
    s = out["steady"]
    print(f"{run.name}: {n} s, {s['fps']:.1f} fps, UI rendered {s['ui_fps']:.1f}/s")
    print(f"  UI per game frame {s['ui_us_per_frame']:.1f} us = {s['ui_pct_of_frame']:.2f} % of the frame "
          f"(update {s['update_us_per_frame']:.1f} us/frame, render {s['render_us_per_ui_frame']:.0f} us per UI frame, "
          f"max {s['render_max_us']} us, {s['sprites_per_ui_frame']:.0f} draw commands)")
    if ret:
        out["retained"] = dict(ret)
        print(f"  retained: UI frames {ret['uiframes'] / n:.1f}/s (input-triggered {ret['input'] / n:.1f}/s), fresh {ret['fresh'] / n:.0f}/s, "
              f"replayed {ret['replay'] / n:.0f}/s ({100 * ret['replay'] / max(1, ret['replay'] + ret['fresh']):.0f} %), "
              f"UI events {ret['uievents'] / n:.1f}/s, check: {ret['checked']} checked, {ret['stale']} stale" + (f" {dict(stale.most_common(6))}" if stale else ""))
    rows = sorted(elems.items(), key=lambda kv: -(kv[1][1] + kv[1][6]))
    print(f"  {'element':34s} {'fresh/s':>8s} {'replay/s':>8s} {'us/fresh':>8s} {'us/frame':>8s} {'max_us':>7s} {'cmds':>5s} {'same%':>6s}")
    out["elements"] = {}
    for name, (r, us, mx, sp, same, rp, rpus) in rows[:a.top]:
        if r == 0 and rp == 0:
            continue
        per_frame = (us + rpus) / max(frames, 1)
        out["elements"][name] = dict(fresh_per_s=r / n, replays_per_s=rp / n, us_per_fresh=us / max(r, 1), us_per_frame=per_frame,
                                     max_us=mx, cmds=sp / max(r, 1), same_pct=100 * same / max(r, 1))
        print(f"  {name[:34]:34s} {r / n:8.1f} {rp / n:8.1f} {us / max(r, 1):8.1f} {per_frame:8.2f} {mx:7d} {sp / max(r, 1):5.0f} {100 * same / max(r, 1):6.1f}")

# 2. interactions
marks = []
frames_t = []
renders = []
for l in lines:
    p = l.split()
    if not p:
        continue
    if p[0] == "M":
        marks.append((int(p[1]), p[2], int(p[3])))
    elif p[0] == "F":
        frames_t.append(int(p[1]))
    elif p[0] == "R":
        renders.append((int(p[1]), int(p[2])))
if marks:
    by = collections.defaultdict(list)
    for i, (t, name, hus) in enumerate(marks):
        nxt = marks[i + 1][0] if i + 1 < len(marks) else t + 2_000_000
        rs = [r for r in renders if t < r[0] <= min(nxt, t + 2_000_000)]
        fs = [f for f in frames_t if t - 100_000 <= f <= min(nxt, t + 500_000)]
        before = [f for f in fs if f <= t]
        after = [f for f in fs if f > t]
        frame_at = (after[0] - before[-1]) / 1000 if before and after else None
        durs = [(b - x) / 1000 for x, b in zip(after, after[1:])]
        worst = max([frame_at or 0] + durs) if (durs or frame_at) else None
        by[name].append(dict(handler_ms=hus / 1000, to_ui_ms=(rs[0][0] - t) / 1000 if rs else None,
                             first_render_us=rs[0][1] if rs else None, frame_ms=frame_at, worst_ms=worst))
    print(f"\n  {'interaction':16s} {'n':>2s} {'handler ms':>10s} {'->UI ms':>8s} {'1st render us':>13s} {'frame ms':>8s} {'worst ms':>8s}  (medians; worst = max)")
    out["interactions"] = {}
    for name, v in by.items():
        def med(k):
            xs = [x[k] for x in v if x[k] is not None]
            return st.median(xs) if xs else float("nan")
        worst = max([x["worst_ms"] for x in v if x["worst_ms"] is not None] or [float("nan")])
        out["interactions"][name] = dict(n=len(v), handler_ms=med("handler_ms"), to_ui_ms=med("to_ui_ms"),
                                         first_render_us=med("first_render_us"), frame_ms=med("frame_ms"), worst_ms=worst)
        o = out["interactions"][name]
        print(f"  {name:16s} {len(v):2d} {o['handler_ms']:10.2f} {o['to_ui_ms']:8.2f} {o['first_render_us']:13.0f} {o['frame_ms']:8.2f} {worst:8.2f}")
# 3. scenes (ui_script=scenes): the seconds wholly inside each scene_* mark's span
scene_marks = [(int(l.split()[1]) // 1000, l.split()[2]) for l in lines if l.startswith("M ") and l.split()[2].startswith(("scene_", "lat_"))]
if scene_marks:
    seg = collections.OrderedDict()
    cur = None
    for l in lines:
        if l.startswith("T "):
            t = int(l.split()[1])
            cur = None
            for i, (mt, name) in enumerate(scene_marks):
                end = scene_marks[i + 1][0] if i + 1 < len(scene_marks) else mt + 10_000
                if mt + 1000 <= t - 1000 and t <= end:
                    cur = name
            if cur:
                f = fields(l)
                g = seg.setdefault(cur, dict(n=0, frames=0, uif=0, upd=0, ren=0, sec=[0] * 7, el=collections.Counter(), umax=[], rmax=[]))
                g["umax"].append(int(f.get("update_max_us", 0))); g["rmax"].append(int(f.get("render_max_us", 0)))
                g["n"] += 1; g["frames"] += int(f["frames"]); g["uif"] += int(f["uiframes"])
                g["upd"] += int(f["update_us"]); g["ren"] += int(f["render_us"])
                if "upd_sections_us" in f:
                    for k, v in enumerate(f["upd_sections_us"].split("/")):
                        g["sec"][k] += int(v)
        elif l.startswith("C ") and cur:
            p = l.split()
            f = dict(x.split("=", 1) for x in p[2:])
            cc = seg[cur].setdefault("calls", {})
            v = cc.setdefault(p[1], [0, 0, 0, 0, 0, 0])
            for k, key in enumerate(("pre_us", "pre_n", "ren_us", "ren_n", "upd_us", "upd_n")):
                v[k] += int(f[key])
        elif l.startswith("E ") and cur:
            p = l.split()
            f = dict(x.split("=", 1) for x in p[2:])
            seg[cur]["el"][p[1]] += int(f["us"]) + int(f.get("replay_us", 0))
            seg[cur].setdefault("eupd", collections.Counter())[p[1]] += int(f.get("upd_us", 0))
            seg[cur].setdefault("eupdtick", collections.Counter())[p[1]] += int(f.get("updtick_us", 0))
    print(f"\n  {'scene':16s} {'s':>2s} {'fps':>6s} {'UI us/frm':>9s} {'% frame':>7s} {'upd us/frm':>10s} {'render us/UIfrm':>15s} {'UIfrm/s':>7s} {'upd/ren max us':>14s}  update sections us/frame (upkeep/buttons/click+wheel/move/pick+OnMouseMove/elements/tooltip); top elements us/frame")
    out["scenes"] = {}
    for name, g in seg.items():
        fr = max(g["frames"], 1)
        ui = (g["upd"] + g["ren"]) / fr
        pct = 100 * ui / (g["n"] * 1e6 / fr)
        secs = "/".join(f"{x / fr:.1f}" for x in g["sec"])
        top = ", ".join(f"{k} {v / fr:.1f}" for k, v in g["el"].most_common(4))
        if "eupd" in g:
            top += " | update: " + ", ".join(f"{k} {v / fr:.1f} (tick {g['eupdtick'][k] / max(1, g['n'] * 10):.0f} us/tick)" for k, v in g["eupd"].most_common(4))
        out["scenes"][name] = dict(seconds=g["n"], fps=g["frames"] / g["n"], ui_us_per_frame=ui, ui_pct=pct,
                                   update_us_per_frame=g["upd"] / fr, render_us_per_ui_frame=g["ren"] / max(g["uif"], 1),
                                   ui_frames_per_s=g["uif"] / g["n"], update_sections=[x / fr for x in g["sec"]],
                                   elements={k: v / fr for k, v in g["el"].most_common(8)})
        if "calls" in g and a.calls:
            rows = sorted(g["calls"].items(), key=lambda kv: -(kv[1][0] + kv[1][2] + kv[1][4]))[:a.calls]
            print(f"    {name} Lua calls by type (us per game frame: prerender/render/update, calls per second):")
            for t, v in rows:
                print(f"      {t[:30]:30s} {v[0] / fr:6.2f} {v[2] / fr:6.2f} {v[4] / fr:6.2f}   {v[1] / g['n']:6.0f} {v[3] / g['n']:6.0f} {v[5] / g['n']:6.0f}")
        mx = f"{st.median(g['umax']):.0f}/{st.median(g['rmax']):.0f}" if g["umax"] else "-"
        print(f"  {name:16s} {g['n']:2d} {g['frames'] / g['n']:6.1f} {ui:9.1f} {pct:7.2f} {g['upd'] / fr:10.1f} {g['ren'] / max(g['uif'], 1):15.0f} {g['uif'] / g['n']:7.1f} {mx:>14s}  {secs}; {top}")
# 4. devUiTruth: what each schedule showed against the truth (every element fresh every frame), per scene and cause
xs = [l.split() for l in lines if l.startswith("X ")]
ys = [l for l in lines if l.startswith("Y ")]
if xs:
    def scene_at(ms):
        name = None
        for mt, nm in scene_marks:
            if mt <= ms:
                name = nm
        return name or "-"
    def pct(v, q):
        v = sorted(v)
        return v[min(len(v) - 1, int(q * len(v)))] if v else float("nan")
    print("\n  devUiTruth: stale episodes (the screen shows something other than the truth) per scene, schedule "
          "(ret = uiRetained, stock = uiRenderFPS) and cause; ms = time until the screen matched the truth again")
    print(f"  {'scene':16s} {'sched':5s} {'cause':7s} {'n':>5s} {'p50 ms':>7s} {'p95 ms':>7s} {'max ms':>7s} {'p50 fr':>6s} {'p95 fr':>6s}  worst elements")
    groups = collections.OrderedDict()
    for p in xs:
        t_ms = int(p[1]) // 1000
        sc = scene_at(t_ms)
        key = (sc, "ret" if p[2] == "s" else "stock", p[4])
        g = groups.setdefault(key, dict(ms=[], fr=[], el=collections.Counter()))
        g["ms"].append(int(p[6]) / 1000); g["fr"].append(int(p[5])); g["el"][p[3]] += int(p[6]) / 1000
    out["truth"] = {}
    for (sc, sched, cause), g in sorted(groups.items(), key=lambda kv: ([m[1] for m in scene_marks] + ["-"]).index(kv[0][0]) if kv[0][0] in [m[1] for m in scene_marks] else 999):
        el = ", ".join(f"{k} {v:.0f}" for k, v in g["el"].most_common(3))
        out["truth"][f"{sc}/{sched}/{cause}"] = dict(n=len(g["ms"]), p50_ms=pct(g["ms"], .5), p95_ms=pct(g["ms"], .95), max_ms=max(g["ms"]),
                                                    p50_frames=pct(g["fr"], .5), p95_frames=pct(g["fr"], .95))
        print(f"  {sc[:16]:16s} {sched:5s} {cause:7s} {len(g['ms']):5d} {pct(g['ms'], .5):7.1f} {pct(g['ms'], .95):7.1f} {max(g['ms']):7.1f} {pct(g['fr'], .5):6d} {pct(g['fr'], .95):6d}  {el}")
    # share of frames with any stale element, per scene
    per = collections.OrderedDict()
    for l in ys:
        f = dict(x.split("=", 1) for x in l.split()[2:])
        sc = scene_at(int(l.split()[1]) - 1000)
        g = per.setdefault(sc, [0, 0, 0])
        g[0] += int(f["frames"]); g[1] += int(f["stale_fr_s"]); g[2] += int(f["stale_fr_k"])
    print(f"\n  {'scene':20s} {'frames':>7s} {'stale ret %':>11s} {'stale stock %':>13s}   (frames on which some element on screen differs from the truth)")
    for sc, (n, s1, s2) in per.items():
        print(f"  {sc[:20]:20s} {n:7d} {100 * s1 / max(n, 1):11.1f} {100 * s2 / max(n, 1):13.1f}")
if a.json:
    Path(a.json).write_text(json.dumps(out, indent=1))
