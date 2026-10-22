#!/usr/bin/env python3
"""Pass / fail of a horde bench run fought like a player (flag tactics=kite, pzopt.HordeTactics, 2026-10-21).

    harness/horde-judge.py <run-dir> [--director /tmp/<label>-dir.log] [--json out.json]

Reads the run's pzopt-horde.out (Showcase.summary: the same horde_* fields as the built-in benchmark's step file),
console.txt and, with --director, the showcase director's log. A run passes when every criterion holds:

  overrides   the overrides loaded ([pzopt] console lines)
  unhurt      no zombie hit, no wounded body part, the kill switch never fired (horde_fail none: "failed: player
              damaged" / "failed: player died" end the pass at once), no safety god mode
  cleared     the horde is dead (horde_end "horde dead", horde_left 0), not the time cap
  not_stuck   no stuck episode (a wanted move making under 0.5 tiles in 2 s)
  ready_before_contact  released when armed / loaded / kitted / idle / aimed (horde_ready), its nearest 14+ tiles out,
              no attack or contact before, no attack begun farther than 1.5 tiles
  route       route_status complete (failed = the kill switch or the hard timeout, route_reason says which)
  unburdened  the Heavy Load moodle never showed (weight under the capacity)
  director    with --director: decisions made, no Jev error left the character without one for long

Exit 0 = pass. Also prints what explains a result: reflexes (the flinch ran while the director chose otherwise),
attacks begun at the player, the closest a zombie came, kites, the clear time, rounds per kill.
"""
import argparse
import json
import re
import sys
from pathlib import Path


def fields(run):
    out = {}
    for name in ("pzopt-horde.out", "pzopt-bench.out"):
        f = run / name
        if f.is_file():
            for line in f.read_text(errors="replace").splitlines():
                if "=" in line and line.startswith(("horde_", "route_status", "route_reason")):
                    k, v = line.split("=", 1)
                    out.setdefault(k.strip(), v.strip())
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--director")
    ap.add_argument("--json")
    a = ap.parse_args()
    run = Path(a.run)
    f = fields(run)
    console = run / "console.txt"
    text = console.read_text(errors="replace") if console.is_file() else ""
    pzopt_lines = text.count("[pzopt]")
    errors = [l for l in text.splitlines() if re.search(r"\bERROR\b|Exception", l) and "harness" not in l]
    crit = {}
    crit["overrides"] = pzopt_lines > 0
    num = lambda k, d=-1.0: float(f.get(k, d)) if f.get(k, "nan") not in ("nan", "") else d
    crit["unhurt"] = f.get("horde_unhurt") == "true" and num("horde_hits") == 0 and num("horde_wounds") == 0 and f.get("horde_safety_god") == "false" \
        and f.get("horde_fail", "none") == "none"
    crit["cleared"] = f.get("horde_end") == "horde dead" and num("horde_left") == 0
    crit["not_stuck"] = num("horde_stuck") == 0
    # the horde released only once the player stood armed, loaded, kitted, idle and aimed (horde_ready "ready"), its nearest
    # zombie 14+ tiles out, and no zombie attack or contact before that; an attack begun farther than 1.5 tiles is a zombie
    # attacking before it reached the player (the stale vectorToTarget, 2026-10-21)
    fa_s, fa_d, rs, fc = num("horde_first_attack_s", 1e9), num("horde_first_attack_tiles", 0.0), num("horde_ready_s", -1.0), num("horde_first_contact_s", 1e9)
    crit["ready_before_contact"] = f.get("horde_ready") == "ready" and rs >= 0 and num("horde_release_nearest") >= 14.0 \
        and fa_s > rs and fa_d <= 1.5 and fc > rs
    crit["route"] = f.get("route_status") == "complete"  # failed: the kill switch or the hard timeout (route_reason)
    crit["unburdened"] = num("horde_heavy_load_max") == 0
    director = {}
    if a.director:
        d = Path(a.director).read_text(errors="replace") if Path(a.director).is_file() else ""
        decisions = [l for l in d.splitlines() if re.match(r"\s*[\d.]+s\s+\w", l) and "jev error" not in l]
        jev_err = [l for l in d.splitlines() if "jev error" in l]
        acts = {}
        for l in decisions:
            m = re.match(r"\s*[\d.]+s\s+(\w+)", l)
            acts[m.group(1)] = acts.get(m.group(1), 0) + 1
        director = {"decisions": len(decisions), "jev_errors": len(jev_err), "actions": acts}
        crit["director"] = len(decisions) > 0 and len(jev_err) <= max(3, len(decisions) // 20)
    ok = all(crit.values())
    info = {k: f.get(k) for k in ("route_reason", "horde_ready", "horde_ready_s", "horde_release_nearest", "horde_first_attack_s", "horde_first_attack_tiles", "horde_first_contact_s", "horde_fail", "horde_fail_s", "horde_total", "horde_killed", "horde_left", "horde_end", "horde_clear_s", "horde_shots", "horde_rounds_per_kill",
                                  "horde_reloads", "horde_switches", "horde_hits", "horde_wounds", "horde_health_start", "horde_health_min", "horde_attacks_begun", "horde_bumps",
                                  "horde_reflexes", "horde_instincts", "horde_close_ins", "horde_kites", "horde_kite_s", "horde_backpedals", "horde_backpedal_s", "horde_backpedal_tiles", "horde_stuck", "horde_stuck_longest_s", "horde_blocked_frames",
                                  "horde_closest_tiles", "horde_moved_tiles", "horde_weight", "horde_capacity", "horde_heavy_load_max", "horde_mags")}
    print(f"horde-judge {run.name}: {'PASS' if ok else 'FAIL'}")
    for k, v in crit.items():
        print(f"  {k:<11} {'ok' if v else 'FAIL'}")
    print("  " + " ".join(f"{k[6:] if k.startswith('horde_') else k}={v}" for k, v in info.items() if v not in (None, "")))
    if director:
        print(f"  director: {director['decisions']} decisions, {director['jev_errors']} Jev errors, {director['actions']}")
    print(f"  console: {pzopt_lines} [pzopt] lines, {len(errors)} error/exception lines")
    for l in errors[:5]:
        print("    " + l[:200])
    if a.json:
        Path(a.json).write_text(json.dumps({"pass": ok, "criteria": crit, "fields": f, "director": director}, indent=1))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
