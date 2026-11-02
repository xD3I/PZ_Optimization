#!/usr/bin/env python3
"""Is the character standing on the dock, and can their reflection show in the water? (2026-09-26, the Riverside
preview shot: `find=shore` and a guessed start= left the character drawn over the river.)

Reads the console of a run with `--flag ground_map=N` (pzopt.Scene: the level-0 squares round the player at the route
start, P / W water / # dry floor / . none, world axes: x to the right, y downwards) and the `lights=on` line. Code counts
the facts; Jev (TypeSafe, numbers only) answers:
  on_dry_floor      the player's square is a floor square, not water
  reflection_visible water lies where the character's mirror image lands on screen: the iso screen's "down" is +x +y,
                    so the squares at (+1,+1), (+1,0), (0,+1), (+2,+2) ... below the feet
  lights_on         grid power exists and light switches were switched on

    harness/stand-spot-judge.py <run dir> [--at x,y] [--no-jev] [--json out]
    (--at: judge another square of the same map as if the character stood there: pick a start= before the next run)
Exit 0 when Jev says yes to the first two (p >= 0.5).
"""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))


def facts(run, at=None):
    con = (run / "console.txt").read_text(errors="replace")
    rows = re.findall(r"harness: ground map y=(-?\d+) x=(-?\d+)\.\.(-?\d+): (\S+)", con)
    if not rows:
        sys.exit(f"no ground map in {run}/console.txt (run with --flag ground_map=N)")
    grid = {}
    for y, x0, _x1, row in rows:
        for i, c in enumerate(row):
            grid[(int(x0) + i, int(y))] = c
    (px, py), = [k for k, c in grid.items() if c == "P"]
    m = re.search(r"harness: ground under the player (\S+): floor=(\S+) water=(\w+) water within 1 tile=(\d+) within 2 tiles=(\d+)", con)
    sq = {"floor": m.group(2), "water": m.group(3) == "true", "w1": int(m.group(4)), "w2": int(m.group(5))}
    if at:  # the same map with the character moved to `at`
        grid[(px, py)] = "W" if sq["water"] else "#"
        px, py = at
        if (px, py) not in grid:
            sys.exit(f"{px},{py} is outside the logged map")
        near = lambda r: sum(grid.get((px + dx, py + dy)) == "W" for dx in range(-r, r + 1) for dy in range(-r, r + 1))
        sq = {"floor": "n/a (--at)", "water": grid[(px, py)] == "W", "w1": near(1), "w2": near(2)}
        grid[(px, py)] = "P"
        rows = [(y, x0, x1, "".join(grid[(int(x0) + i, int(y))] for i in range(len(row)))) for y, x0, x1, row in rows]
    lights = re.search(r"harness: lights=on: .*?grid power (\w+), (\d+) light switches", con)
    # where a reflection lands: screen-down of the feet = +x +y and its neighbours
    below = [(1, 1), (1, 0), (0, 1), (2, 2), (2, 1), (1, 2)]
    water_below = sum(grid.get((px + dx, py + dy)) == "W" for dx, dy in below)
    return {
        "player_square": f"{px},{py}",
        "player_square_is_water": sq["water"],
        "player_floor_sprite": sq["floor"],
        "water_squares_within_1_tile": sq["w1"],
        "water_squares_within_2_tiles": sq["w2"],
        "water_squares_where_the_reflection_lands": f"{water_below} of {len(below)} (screen-down of the feet: +x+y)",
        "ground_map_world_axes_x_right_y_down": [row for _y, _x0, _x1, row in rows],
        "grid_power": lights.group(1) if lights else "lights flag not used",
        "light_switches_switched_on": int(lights.group(2)) if lights else 0,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--at")
    ap.add_argument("--no-jev", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
    f = facts(Path(a.run), tuple(int(v) for v in a.at.split(",")) if a.at else None)
    print(json.dumps(f, indent=1))
    if a.no_jev:
        return
    from typesafe_client import ask, fmt, noul
    state = {"scene": "A game character stands at a riverside dock for a still screenshot (isometric view). The facts "
                      "come from the game's own map data around the character (P = the character's square, W = water, "
                      "# = dry floor, . = nothing).", "facts": f}
    ans = ask(state, {
        "on_dry_floor": noul("Is the character standing on a dry floor square (the dock), not on a water square?"),
        "reflection_visible": noul("Is there water in the squares where the character's mirror image lands (screen-down "
                                   "of the feet), so their reflection can show in the water?"),
        "lights_on": noul("Is grid power on with light switches switched on?"),
    })
    print(fmt(ans))
    if a.json:
        Path(a.json).write_text(json.dumps({"facts": f, "jev": ans}, indent=1))
    sys.exit(0 if all(ans[k]["noul"] >= 0.5 for k in ("on_dry_floor", "reflection_visible")) else 1)


if __name__ == "__main__":
    main()
