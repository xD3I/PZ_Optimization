#!/usr/bin/env python3
"""Jev directs the explore=restaurant scene (2026-09-25, the flip HDR "north-facing bloom" report) and the
explore=stairs scene (2026-09-25, walls flicker on the stairs: up to the top floor, down to the basement; the state
says "scene": "stairs") and the explore=circle scene with director=jev (2026-09-29, the gas-station shelves flicker while
the player walks in circles: circles round the start square and the aisles beside the shelves; "scene": "circle") and the
explore=mirror scene (2026-10-04, pzopt.MirrorWalk: every mirror of the save's house, stations in front of each; "scene": "mirror")
and the explore=tour scene (2026-10-21, pzopt.TourWalk: the reflections showcase walk, a fixed list of stops round a house by the
water, a view to face and a look round at some; "scene": "tour").

The game (pzopt.Explore, flags explore=restaurant director=jev) writes the scene's facts every 0.3 s to
~/Zomboid/pzopt-explore-state.json; this loop asks TypeSafe's Jev what the character does next and writes
"<seq> <action>" to ~/Zomboid/pzopt-explore-cmd.txt, which the game carries out until the next command. Jev only chooses;
the walking is the game's own movement keys along a grid path, doors opened on the way. Every decision goes to the log
with Jev's probabilities and latency.

    harness/explore-director.py [--machine flip] [--log /tmp/explore-director.log] [--wait 900]

--machine <m> (a [m] entry of harness/queue/machines.conf): the game runs there; one ssh connection streams its state
file here and carries the commands back (the TypeSafe key stays on this machine). Start it before the run (it waits for a
state file newer than itself); it exits when the state goes stale for 15 s (the run ended).
"""
import argparse
import base64
import configparser
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from typesafe_client import ask, choice  # noqa: E402

ZOMBOID = Path(os.environ.get("ZOMBOID", Path.home() / "Zomboid"))

QUESTION = choice(
    "You direct a character in Project Zomboid who inspects a restaurant, one decision at a time, from the scene "
    "state. The visit goes in this order: walk to the restaurant; in every room of it, first look around once (a full "
    "turn in place), then face north for about four seconds; then walk to the next room not visited yet; when no room is "
    "left to visit, finish. Pick the character's next action.",
    {
        "go_to_restaurant": "Walk to the restaurant from the street. Right only while visited_any_room is false; never "
                            "once any room of the restaurant has been visited (then next_room moves between rooms).",
        "next_room": "Walk to the nearest room of the restaurant not visited yet. Right when rooms are left to visit and "
                     "either the character is outside after the first visit, or in the current room the full turn is "
                     "done (looked_around true) and the character has faced north for at least 4 seconds.",
        "look_around": "Turn once round in place. Right when the character is inside the restaurant and has not "
                       "finished looking around in this room (looked_around false).",
        "face_north": "Turn to face north and stand. Right when the character is inside, has looked around in this room "
                      "(looked_around true) and has faced north here for less than 4 seconds.",
        "hold": "Stand still. Right only while nothing else applies.",
        "done": "Finish the visit. Right when no room is left to visit (rooms_left_to_visit is 0) and the current room is "
                "finished (looked around and faced north for 4 seconds), or the character is outside with no room left.",
    },
)

STAIRS = choice(
    "You direct a character in Project Zomboid who inspects a building floor by floor, one decision at a time, from the "
    "scene state. The visit goes in this order: on every level the character arrives at (and on the starting level), "
    "first look around once (a full turn in place); then, while the top floor has not been reached, go up one level; once "
    "the top floor has been reached, go down one level at a time until the lowest level (the basement); after looking "
    "around on the lowest level again, finish. Pick the character's next action.",
    {
        "go_up": "Climb the nearest staircase one level up. Right when looked_around_on_this_level is true, "
                 "top_floor_reached is false, the level is below top_level and no_stairs_up_from_this_level is false; also "
                 "right to keep going while current_action is go_up and the character is on the stairs or has not arrived.",
        "go_down": "Walk down the nearest staircase one level. Right when looked_around_on_this_level is true and either "
                   "top_floor_reached is true or no stairs lead up, the level is above lowest_level and "
                   "no_stairs_down_from_this_level is false; also right to keep going while current_action is go_down and "
                   "the character is on the stairs or has not arrived.",
        "look_around": "Turn once round in place. Right when the character is not on the stairs and "
                       "looked_around_on_this_level is false.",
        "hold": "Stand still. Right only while nothing else applies.",
        "done": "Finish. Right when back_at_lowest_after_top is true and looked_around_on_this_level is true, or when "
                "neither a staircase up nor down can be used.",
    },
)

CIRCLE = choice(
    "You direct a character in Project Zomboid who walks in circles to show a flicker on the shelves of a store, one "
    "decision at a time, from the scene state. At every spot, in order: circle clockwise until clockwise_laps_done is "
    "true, then counter-clockwise until counterclockwise_laps_done is true, then walk to the next spot; when both are "
    "done at the last spot (is_last_spot true), finish. A spot where blocked_here is true (the character cannot move "
    "round it) counts as done. Pick the character's next action.",
    {
        "circle_cw": "Walk in a circle clockwise round the spot. Right only while clockwise_laps_done is false and the "
                     "character is at the spot (at_spot true).",
        "circle_ccw": "Walk in a circle counter-clockwise round the spot. Right only when clockwise_laps_done is true "
                      "and counterclockwise_laps_done is false.",
        "next_spot": "Walk to the next spot. Right when clockwise_laps_done and counterclockwise_laps_done are both true "
                     "and is_last_spot is false, or when blocked_here is true and is_last_spot is false; also right to keep "
                     "going while current_action is next_spot and at_spot is false.",
        "hold": "Stand still. Right only while nothing else applies.",
        "done": "Finish the walk. Right when is_last_spot is true and either clockwise_laps_done and "
                "counterclockwise_laps_done are both true or blocked_here is true.",
    },
)

MIRROR = choice(
    "You direct a character in Project Zomboid who walks around every mirror of a house so that a tester can look for "
    "glitches in the reflections, one decision at a time, from the scene state. Mirrors are visited one after another "
    "(mirror.number of mirrors_total). In front of the current mirror the stations form a small grid (map rows from "
    "nearest the mirror to furthest, columns along the wall). At every station you arrive at: first face the mirror "
    "(until faced_mirror_here is true), then turn your back to it (until turned_back_here is true). Once per mirror, after "
    "those checks, walk one small circle (until circled_at_this_mirror is true). Then move one station at a time with "
    "left, right, closer or back towards the stations not visited yet, until stations_left_to_visit is 0. When "
    "all_done_here is true, go to the next mirror with next_mirror, or finish with done at the last mirror. Never pick a "
    "move or next_mirror while arrived_at_station is false: keep the current action with hold. When time_is_up is true, "
    "finish with done.",
    {
        "left": "Walk to the next station to the left along the wall. Right when arrived_at_station and checks_done_here "
                "are true, circled_at_this_mirror is true, can_go_left is true and unvisited_to_the_left is true.",
        "right": "Walk to the next station to the right along the wall. Right when arrived_at_station and checks_done_here "
                 "are true, circled_at_this_mirror is true, can_go_right is true and unvisited_to_the_right is true.",
        "closer": "Walk one station closer to the mirror. Right when arrived_at_station and checks_done_here are true, "
                  "circled_at_this_mirror is true, can_go_closer is true and unvisited_closer is true.",
        "back": "Walk one station further back from the mirror. Right when arrived_at_station and checks_done_here are "
                "true, circled_at_this_mirror is true, can_go_back is true and unvisited_further_back is true.",
        "face_mirror": "Stand and face the mirror. Right when arrived_at_station is true and faced_mirror_here is false.",
        "turn_around": "Stand with the back to the mirror. Right when arrived_at_station is true, faced_mirror_here is "
                       "true and turned_back_here is false.",
        "circle": "Walk one small circle round the station. Right when arrived_at_station is true, checks_done_here is "
                  "true and circled_at_this_mirror is false.",
        "next_mirror": "Walk to the next mirror of the house. Right when all_done_here is true and is_last_mirror is false.",
        "hold": "Keep doing the current action. Right while arrived_at_station is false, or while the current check "
                "(face_mirror, turn_around, circle) is not finished yet.",
        "done": "Finish the walk. Right when all_done_here is true and is_last_mirror is true, or when time_is_up is true.",
    },
)

TOUR = choice(
    "You direct a character in Project Zomboid on a showcase walk past mirrors, windows and water so that a camera can show "
    "their reflections, one decision at a time, from the scene state. The walk is a fixed list of stops (stop.number of "
    "stops_total). At every stop you arrive at: if has_a_view_to_face is true, first face the view (until faced_view_here is "
    "true); then, if wants_a_look_around is true, turn once round on the spot (until looked_around_here is true); then go to "
    "the next stop with next_stop, or finish with done when is_last_stop is true. Never pick next_stop while arrived_at_stop is "
    "false: keep the current action with hold. When time_is_up is true, finish with done.",
    {
        "face_view": "Turn to the stop's view (a mirror, a window, the river) and hold it. Right when arrived_at_stop is true "
                     "and faced_view_here is false.",
        "look_around": "Turn once round on the spot. Right when arrived_at_stop is true, faced_view_here is true, "
                       "wants_a_look_around is true and looked_around_here is false.",
        "next_stop": "Walk to the next stop. Right when all_done_here is true and is_last_stop is false.",
        "hold": "Keep doing the current action. Right while arrived_at_stop is false, or while the current action (face_view, "
                "look_around) is not finished yet.",
        "done": "Finish the walk. Right when all_done_here is true and is_last_stop is true, or when time_is_up is true.",
    },
)

BRIDGE = r"""
import os, sys, time, select
z = os.path.expanduser('~/Zomboid')
st, cmd = os.path.join(z, 'pzopt-explore-state.json'), os.path.join(z, 'pzopt-explore-cmd.txt')
last = None
while True:
    try:
        m = os.stat(st).st_mtime_ns
        if m != last:
            last = m
            with open(st) as f:
                s = f.read().strip()
            if s:
                sys.stdout.write(s + '\n'); sys.stdout.flush()
    except OSError:
        pass
    r, _, _ = select.select([sys.stdin], [], [], 0.05)
    if r:
        line = sys.stdin.readline()
        if not line:
            break
        with open(cmd + '.tmp', 'w') as f:
            f.write(line)
        os.replace(cmd + '.tmp', cmd)
"""


class Local:
    def __init__(self):
        self.state, self.cmd, self.t0 = ZOMBOID / "pzopt-explore-state.json", ZOMBOID / "pzopt-explore-cmd.txt", time.time()

    def read(self):
        try:
            if self.state.exists() and self.state.stat().st_mtime > self.t0:
                return json.loads(self.state.read_text())
        except (OSError, json.JSONDecodeError):
            pass
        return None

    def send(self, line):
        tmp = self.cmd.with_suffix(".tmp")
        tmp.write_text(line)
        tmp.replace(self.cmd)


class Remote:
    """One ssh connection: the remote bridge prints each new state file, takes one command per stdin line."""

    def __init__(self, machine):
        cfg = configparser.ConfigParser()
        cfg.read(Path(__file__).resolve().parent / "queue" / "machines.conf")
        m = cfg[machine]
        code = base64.b64encode(BRIDGE.encode()).decode()  # one quoting-free word for the remote shell (fish on the flip)
        self.p = subprocess.Popen(["ssh", "-i", os.path.expanduser(m["key"]), "-o", "ServerAliveInterval=5", m["host"], "python3", "-u", "-c",
                                   f"\"import base64;exec(base64.b64decode('{code}'))\""],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        self.fd = self.p.stdout.fileno()
        os.set_blocking(self.fd, False)
        self.buf, self.t0_ms = "", time.time() * 1000 - 2000  # a state from before this director started is a previous run's

    def read(self):
        latest = None
        try:
            chunk = os.read(self.fd, 1 << 16).decode(errors="replace")
        except BlockingIOError:
            chunk = None
        if chunk:
            self.buf += chunk
            *lines, self.buf = self.buf.split("\n")
            for ln in lines:
                try:
                    s = json.loads(ln)
                    if s["t"] > self.t0_ms:
                        latest = s
                except (json.JSONDecodeError, KeyError):
                    pass
        return latest

    def send(self, line):
        self.p.stdin.write(line)
        self.p.stdin.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--machine", default="desktop")
    ap.add_argument("--log", default="/tmp/explore-director.log")
    ap.add_argument("--wait", type=float, default=900, help="seconds to wait for the game's first state")
    a = ap.parse_args()
    io = Local() if a.machine == "desktop" else Remote(a.machine)
    t0 = time.time()
    seq, last_t, last_seen, decisions, st = 0, None, time.time(), 0, None
    log = open(a.log, "a", buffering=1)
    log.write(f"# explore director started {time.strftime('%Y-%m-%d %H:%M:%S')} on {a.machine}\n")
    while True:
        new = io.read()
        now = time.time()
        if new is not None:
            st, last_seen = new, now
        if st is None:
            if now - t0 > a.wait:
                log.write("# no state from the game, giving up\n")
                return 1
            time.sleep(0.1)
            continue
        if now - last_seen > 15 or isinstance(io, Local) and now * 1000 - st["t"] > 15000:
            log.write(f"# state stale for 15 s: the run ended; {decisions} decisions\n")
            return 0
        if st["t"] == last_t:
            time.sleep(0.05)
            continue
        last_t = st["t"]
        lat = []
        try:
            q = {"stairs": STAIRS, "circle": CIRCLE, "mirror": MIRROR, "tour": TOUR}.get(st.get("scene"), QUESTION)
            ans = ask(st, {"action": q}, log=lat)["action"]
        except Exception as e:  # keep the last command; the next state gets another try
            log.write(f"{st['seconds_since_start']:6.1f}s  jev error: {e}\n")
            time.sleep(0.5)
            continue
        act = ans["choice"]
        seq += 1
        decisions += 1
        io.send(f"{seq} {act}\n")
        probs = " ".join(f"{k}={v:.2f}" for k, v in sorted(ans.get("probabilities", {}).items(), key=lambda kv: -kv[1]))
        if st.get("scene") == "circle":
            p, s = st["player"], st["spot"]
            log.write(f"{st['seconds_since_start']:6.1f}s  {act:<12} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                      f"spot {s['number']}/{s['spots_total']} at {p['at_spot']} dist {p['distance_to_spot_tiles']:.1f} "
                      f"laps cw {s['laps_clockwise_here']:.2f} ccw {s['laps_counterclockwise_here']:.2f} | {probs}\n")
            continue
        if st.get("scene") == "mirror":
            p, m, s = st["player"], st["mirror"], st["station"]
            log.write(f"{st['seconds_since_start']:6.1f}s  {act:<12} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                      f"mirror {m['number']}/{m['mirrors_total']} arrived {p['arrived_at_station']} faced {s['faced_mirror_here']} "
                      f"turned {s['turned_back_here']} circled {m['circled_at_this_mirror']} left {m['stations_left_to_visit']} "
                      f"done_here {m['all_done_here']} map {'/'.join(m['map_rows_near_to_far_columns_left_to_right'])} | {probs}\n")
            continue
        if st.get("scene") == "tour":
            p, s = st["player"], st["stop"]
            log.write(f"{st['seconds_since_start']:6.1f}s  {act:<12} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                      f"stop {s['number']}/{s['stops_total']} {s['place']} arrived {s['arrived_at_stop']} faced {s['faced_view_here']} "
                      f"looked {s['looked_around_here']} done_here {s['all_done_here']} dist {p['distance_to_stop_tiles']:.1f} | {probs}\n")
            continue
        if st.get("scene") == "stairs":
            p, g = st["player"], st["progress"]
            log.write(f"{st['seconds_since_start']:6.1f}s  {act:<12} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                      f"level {p['level']} stairs {p['on_stairs']} arrived {p['arrived_at_goal_level']} looked {g['looked_around_on_this_level']} "
                      f"top {g['top_floor_reached']} back {g['back_at_lowest_after_top']} changes {g['level_changes']} | {probs}\n")
            continue
        p, r, h = st["player"], st["restaurant"], st["this_room"]
        log.write(f"{st['seconds_since_start']:6.1f}s  {act:<17} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                  f"room {p['current_room']} inside {p['inside_restaurant']} dist {p['distance_to_restaurant_tiles']:.0f} facing {p['facing']} "
                  f"visited {r['rooms_visited']}/{r['rooms_total']} left {r['rooms_left_to_visit']} looked {h['looked_around']} "
                  f"({h['degrees_turned']:.0f} deg) north {h['seconds_facing_north']:.1f}s | {probs}\n")


if __name__ == "__main__":
    sys.exit(main())
