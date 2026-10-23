#!/usr/bin/env python3
"""Is the harness walk clean? (2026-10-08, the maintainer: "an error free and stuck free Jev powered harness to walk in the
game efficiently without colliding with any obstacle".)

Reads a walk run (pzopt.Nav under explore=mirror director=jev; the `harness: nav:` and `harness: mirror walk` lines) and
the director's log, and reports, over the walk (route start .. walk done):

  collisions        the player's collision episodes (the game's per-frame flags: wall, object, door, vehicle, bump state)
  stuck             walking with under 0.15 squares of progress in 1.5 s
  failed_legs       walks that ended without arriving (after the one retry), retries, lost walks (the action left the queue)
  errors            ERROR / exception / stack-trace lines in the game log during the walk (the start-up noise before the
                    route is not counted), Lua errors of the walk bridge (lua_errors)
  director_errors   lines of the director's log that are not decisions (tracebacks, failed calls)
  efficiency        walked / straight distance over the arrived legs, seconds a leg, the walk's share of stations visited
  done              the walk ended with its summary line (not the harness's time limit)

The game rotates console.txt on long, chatty runs: when the run's console.txt lacks the walk, the newest
~/Zomboid/Logs/*/…_DebugLog.txt holding the run's label window is read (or pass --log).

  python3 harness/navjudge.py <run> [--log <game log>] [--director /tmp/x.log] [--json out.json] [--no-jev]
Exit 0 when clean (no collision, stuck, failed leg, error), 1 otherwise.
"""
import argparse, glob, json, os, re, sys


def game_log(run, given):
    if given:
        return given
    c = os.path.join(run, "console.txt")
    txt = open(c, errors="replace").read() if os.path.exists(c) else ""
    if "harness: route start (" in txt and ("harness: nav summary" in txt or "harness: mirror walk: done" in txt or "harness: tour walk: done" in txt or "mirrors with glass" in txt):
        return c
    # the rotated full log: the newest DebugLog with this run's route start
    cands = sorted(glob.glob(os.path.expanduser("~/Zomboid/Logs/*/*_DebugLog.txt")) + glob.glob(os.path.expanduser("~/Zomboid/Logs/*_DebugLog.txt")),
                   key=os.path.getmtime, reverse=True)
    start = os.path.getmtime(os.path.join(run, "run.opts")) if os.path.exists(os.path.join(run, "run.opts")) else 0
    for f in cands:
        if os.path.getmtime(f) < start - 60:
            break
        t = open(f, errors="replace").read()
        if "harness: route start" in t and ("harness: mirror walk" in t or "harness: tour walk" in t or "harness: square walk" in t):
            return f
    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--log")
    ap.add_argument("--director")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    log = game_log(a.run, a.log)
    lines = open(log, errors="replace").read().split("\n")
    i0 = next((i for i, l in enumerate(lines) if "harness: route start (" in l), 0)
    i1 = next((i for i, l in enumerate(lines) if "harness: nav summary" in l), len(lines) - 1)
    walk = lines[i0:i1 + 1]
    summary = next((l for l in walk if "harness: nav summary" in l), "")
    kv = {}
    mf = re.search(r"\((\d+) frames\)", summary)
    m = re.search(r"nav summary: (.*)", summary)
    if m:
        toks = re.sub(r"\(\d+ frames\)", "", m.group(1)).split()
        for k, v in zip(toks[::2], toks[1::2]):
            try:
                kv[k] = float(v)
            except ValueError:
                pass
    legs = [l for l in walk if re.search(r"harness: nav: leg \d+ '.*' (arrived|failed|stopped|lost|stuck|timeout) in", l)]
    arrived = [l for l in legs if "' arrived in" in l]
    ratios, secs = [], []
    for l in arrived:
        # walked over the pathfinder's own route (stairs and doors make the straight line meaningless), else straight
        mm = re.search(r"in ([\d.]+) s, walked ([\d.]+) / straight ([\d.]+) squares \(x[\d.]+\)(?:, planned ([-\d.]+))?", l)
        if mm and float(mm.group(3)) >= 1.0:  # short hops (lap points) say little about the route
            ref = float(mm.group(4)) if mm.group(4) and float(mm.group(4)) > 0.05 else float(mm.group(3))
            ratios.append(float(mm.group(2)) / ref)
            secs.append(float(mm.group(1)))
    st_end = next((i for i, l in enumerate(walk) if "harness: nav: self-test:" in l and "not counted" in l), -1)
    selftest = next((l.split("[pzopt] ")[-1] for l in walk if "harness: nav: self-test:" in l and "not counted" in l), None)
    coll = [l.split("[pzopt] ")[-1] for l in walk[st_end + 1:] if "harness: nav: collision" in l and " detail:" not in l]
    stuck = [l.split("[pzopt] ")[-1] for l in walk if "harness: nav: stuck" in l]
    failed = [l.split("[pzopt] ")[-1] for l in legs if "' arrived in" not in l]
    err_re = re.compile(r"ERROR|Exception|STACK TRACE|LuaError|attempted index|Callframe|\bat zombie\.|java\.lang\.")
    errors = [l.split("] ", 1)[-1][:220] for l in walk if err_re.search(l)]
    done = any("harness: mirror walk: done" in l or "harness: tour walk: done" in l or "harness: square walk: done" in l for l in walk)  # explore=mirror / tour / square
    timed_out_tour = any("harness: tour walk: time limit passed" in l for l in walk)
    timed_out = any("time limit passed by 5 s" in l for l in walk) or timed_out_tour
    stations = next((re.search(r"(\d+) of (\d+) stations visited", l) for l in walk if "stations visited" in l), None)
    skipped = [l.split("[pzopt] ")[-1] for l in walk if "no station that can be reached" in l or "circle skipped" in l or "circle cut short" in l]
    derr = []
    dlog = a.director
    if dlog and os.path.exists(dlog):
        for l in open(dlog, errors="replace"):
            if re.search(r"Traceback|Error|error|failed|exception", l) and "conf" not in l:
                derr.append(l.strip()[:200])
    st = {
        "log": log,
        "done": done,
        "time_limit_hit": timed_out,
        "legs": int(kv.get("legs", len(legs))),
        "arrived": len(arrived),
        "failed_legs": len(failed),
        "retries": int(kv.get("retries", 0)),
        "lost": int(kv.get("lost", 0)),
        "collisions": len(coll),
        "collision_frames": int(mf.group(1)) if mf else 0,
        "stuck": len(stuck),
        "lua_errors": int(kv.get("lua_errors", 0)),
        "fence_or_window_climbs": int(kv.get("climbs", 0)),
        "detours_round_fences": int(kv.get("detours", 0)),
        "errors": len(errors),
        "director_errors": len(derr),
        "walk_ratio_median": round(sorted(ratios)[len(ratios) // 2], 3) if ratios else None,
        "walk_ratio_max": round(max(ratios), 3) if ratios else None,
        "leg_secs_median": round(sorted(secs)[len(secs) // 2], 2) if secs else None,
        "stations_visited": f"{stations.group(1)}/{stations.group(2)}" if stations else None,
        "skipped_no_room": len(skipped),
        "control_push_detected": (" seen in" in selftest) if selftest else None,
    }
    print(json.dumps(st, indent=1))
    for name, rows in (("collisions", coll), ("stuck", stuck), ("failed legs", failed), ("errors", errors), ("director errors", derr), ("skipped", skipped)):
        if rows:
            print(f"-- {name} ({len(rows)}):")
            for r in rows[:12]:
                print("  ", r[:300])
    clean = done and st["legs"] > 0 and not timed_out and st["collisions"] == 0 and st["stuck"] == 0 and st["failed_legs"] == 0 and st["errors"] == 0 \
        and st["lua_errors"] == 0 and st["director_errors"] == 0 and st["lost"] == 0 and st["control_push_detected"] is not False and st["fence_or_window_climbs"] == 0
    verdict = None
    if not a.no_jev:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from typesafe_client import ask, noul, choice
        qs = {
            "no_collisions": noul("Did the character walk without touching any obstacle: collisions is 0? (control_push_detected "
                                  "true means the deliberate push into a wall before the walk was detected, i.e. the "
                                  "collision counter works; it is not a walk collision.)"),
            "walked_not_climbed": noul("Did the character only walk, never vault a fence or climb through a window "
                                       "(fence_or_window_climbs is 0; detours_round_fences are walks round them, fine)?"),
            "never_stuck": noul("Was the character never stuck (stuck is 0, failed_legs is 0, lost is 0)?"),
            "error_free": noul("Was the walk free of errors (errors, lua_errors and director_errors are all 0)?"),
            "efficient": noul("Did the character walk efficiently: walk_ratio_median at most 1.3 (distance walked over the "
                              "pathfinder's planned route) and walk_ratio_max at most 1.6, the walk ended by itself (done true, time_limit_hit false)?"),
            "verdict": choice("Overall, is this walking harness clean? (skipped_no_room counts laps or mirrors the walk "
                              "left out on purpose because furniture or a wall was too near: by design, not a fault.)",
                              {"clean": "no collision, never stuck, no error, efficient routes, the walk finished",
                               "nearly": "one minor flaw (a single retry or a long detour) but no collision, stuck or error",
                               "broken": "collisions, stuck episodes, failed walks or errors"}),
        }
        verdict = ask(st, qs)
        print("jev:", json.dumps({k: (v.get("choice"), v.get("confidence")) if "choice" in v else round(v.get("noul", 0), 3) for k, v in verdict.items()}))
    if a.json:
        json.dump({"state": st, "collisions": coll, "stuck": stuck, "failed": failed, "errors": errors, "director_errors": derr, "jev": verdict}, open(a.json, "w"), indent=1)
    return 0 if clean else 1


if __name__ == "__main__":
    sys.exit(main())
