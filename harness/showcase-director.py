#!/usr/bin/env python3
"""Jev directs the showcase=horde scene (2026-09-24, the HDR horde video; 2026-10-06 the street shootout of the Workshop
headline GIF: flags horde_when=pier back_off=N, a second question with the back_off action).

The game (pzopt.Showcase, flag director=jev) writes the scene's facts every 0.3 s to ~/Zomboid/pzopt-showcase-state.json;
this loop asks TypeSafe's Jev which action the character takes next and writes "<seq> <action>" to
~/Zomboid/pzopt-showcase-cmd.txt, which the game carries out until the next command. Jev only chooses; the moves are the
game's own (movement keys, the aim / fire buttons, the reload action). Every decision goes to the log with Jev's
probabilities and latency.

    harness/showcase-director.py [--log /tmp/showcase-director.log] [--wait 900] [--runs N] [--machine flip|dell|mac]

--machine (2026-10-18): the game runs on a laptop of harness/queue/machines.conf; the state is read and the command
written over one shared ssh connection (ControlMaster), the freshness judged from the state's own "t" (epoch ms).

Start it before the run (it waits for a state file newer than itself) and it exits when the state goes stale for 15 s
(the run ended); with --runs N it then waits for the next run's state, N runs in all (a queued series such as the
horde-shoot bench, harness/ragdoll-judge.py).
"""
import argparse
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
STATE = ZOMBOID / "pzopt-showcase-state.json"
CMD = ZOMBOID / "pzopt-showcase-cmd.txt"

QUESTION = choice(
    "You direct a short cinematic last stand in Project Zomboid, one decision at a time, from the scene state. The scene "
    "plays out in this order: the character sprints to the pier; at the pier the character turns to face the burning "
    "horde that chases them; then lights a line of fires across the horde's path; then fires the M16 at the horde, "
    "reloading whenever the magazine runs dry, until the horde is gone. Pick the character's next action.",
    {
        "run_to_pier": "Sprint to the pier. Right while the character is not at the pier yet.",
        "face_horde": "Turn to face the nearest zombie. Right when the character is at the pier, the fire line is not lit "
                      "yet and the angle between the facing and the nearest zombie is over 20 degrees.",
        "light_fire_line": "Light the line of fires across the horde's path. Right when the character is at the pier, "
                           "faces the horde (angle under 20 degrees) and the fire line is not lit.",
        "shoot": "Aim and fire the M16 at the nearest zombie. Right when the character is at the pier, the fire line is "
                 "lit, zombies are alive and the magazine has rounds or a round is chambered.",
        "reload": "Reload from a spare magazine. Right when the magazine has no rounds and no round is chambered, spare "
                  "magazines remain and no reload is in progress.",
        "hold": "Stand still. Right when no zombies are left, or while a reload is in progress.",
    },
)

# the street shot (flags horde_when=pier back_off=N, 2026-10-06, the Workshop headline GIF): the zombies appear only once the
# character stands at the spot, and the character may give ground while firing; the state says back_off_allowed
QUESTION_BACK_OFF = choice(
    "You direct a short cinematic shootout in Project Zomboid, one decision at a time, from the scene state. The scene "
    "plays out in this order: the character runs to a spot in the street (the 'pier' in the state) and stops; only then do "
    "the zombies appear (horde_spawned) and walk at the character; the character turns to face them; the fight starts; the "
    "character fires the rifle at them, reloading whenever the magazine runs dry, and runs back away from them whenever "
    "they get close, then turns and fires again, until every zombie is down. Pick the character's next action.",
    {
        "run_to_pier": "Run to the spot. Right while the character is not at the spot yet and the fight has not started "
                       "(fire_line_lit false).",
        "face_horde": "Turn to face the nearest zombie. Right when the zombies have appeared, the fight has not started and "
                      "the angle between the facing and the nearest zombie is over 20 degrees.",
        "light_fire_line": "Start the fight (no fire is lit in this scene; it only marks the fight as started). Right when the "
                           "zombies have appeared, the character faces them (angle under 20 degrees) and the fight has not "
                           "started.",
        "shoot": "Stand and fire the rifle at the nearest zombie. Right when the fight has started, zombies are alive, the "
                 "magazine has rounds or a round is chambered, and either no zombie is within 4 tiles or no back-off room "
                 "is left (back_off_room_tiles under 0.5).",
        "back_off": "Run away from the zombies (the rifle lowered: nobody can run while aiming) to win back distance. Right "
                    "when the fight has started, the nearest zombie is within 4 tiles, no reload is in progress and "
                    "back_off_room_tiles is over 0.5.",
        "reload": "Reload from a spare magazine. Right when the magazine has no rounds and no round is chambered, spare "
                  "magazines remain and no reload is in progress.",
        "hold": "Stand still. Right when the character is at the spot and the zombies have not appeared yet, when no zombies "
                "are left, or while a reload is in progress.",
    },
)


# the horde shooting bench (flag horde_when=ready, 2026-10-18): the horde appears only once the character stands at the pier,
# turned to the horde side, aiming a loaded gun (the state says horde_comes_when_aiming); a .44 Magnum is drawn instead of
# the M16's second reload (the game does it on "reload"), the bench ends when every zombie of the horde is dead
QUESTION_READY = choice(
    "You direct a horde shooting test in Project Zomboid, one decision at a time, from the scene state. Zombies never "
    "attack the character. The scene plays out in this order: the character runs to the pier and stops; turns to face the "
    "horde side; the fight is marked as started (fire_line_lit; no fire is lit); the character aims the loaded gun, and only "
    "then does the horde of zombies appear (horde_spawned) and run at the character; the character keeps firing at the "
    "nearest zombie, reloading whenever the gun runs dry (the reload action also swaps to the revolver and back, and fixes "
    "the hands when hands_hold_wanted_weapon is false), until every zombie of the horde is dead. The test only ends when "
    "zombies_alive is 0, so never stop firing while zombies are alive. Pick the character's next action.",
    {
        "run_to_pier": "Run to the pier. Right while the character is not at the pier and the fight has not started "
                       "(fire_line_lit false).",
        "face_horde": "Turn to face the horde side. Right when the character is at the pier, the fight has not started and "
                      "the angle between the facing and the nearest zombie (or the horde side) is over 8 degrees.",
        "light_fire_line": "Mark the fight as started. Right when the character is at the pier, faces the horde side (angle "
                           "under 8 degrees) and fire_line_lit is false.",
        "shoot": "Aim and fire at the nearest zombie. Right when the fight has started, the gun has a round in it (rounds "
                 "over 0 or a round chambered), hands_hold_wanted_weapon is true and no reload is in progress, and either "
                 "the horde has not appeared yet (aiming brings it) or zombies are alive.",
        "reload": "Reload, swap weapons, or fix the hands. Right when the fight has started, no reload is in progress, and "
                  "either the gun is empty (no rounds and no round chambered) or hands_hold_wanted_weapon is false.",
        "hold": "Stand still. Right while a reload is in progress, or when the horde has appeared and zombies_alive is 0.",
    },
)


# the horde bench fought like a player (flag tactics=kite, 2026-10-21): the zombies attack, nothing holds them off; the
# character shoots while the horde is far, runs round it when it gets close, reloads where there is room and draws the
# revolver when the rifle runs dry with the horde near. The state's "tactics" block carries the distances and the
# thresholds; the game's own flinch (reflex_running) runs whatever is chosen while a zombie is within 3.5 tiles
QUESTION_TACTICS = choice(
    "You play a survivor in Project Zomboid, one decision at a time from the game state, the way a skilled player fights a "
    "horde of fast zombies in an open field with an M16 rifle and a .44 revolver. A zombie that reaches the character hurts "
    "them: the goal is to kill every zombie without one ever getting within reach. Before the fight the character goes to "
    "the spot ('pier' in the state), turns to the horde side, marks the fight as started (fire_line_lit) and aims the loaded "
    "gun; aiming brings the horde (horde_spawned). In the fight: shoot while the nearest standing zombie is far; once it "
    "comes closer than tactics.kite_under_tiles, run on (kite: the run follows the loop round a block by itself when one is set, the way a player leads a horde round a block so it trails behind as a column) until it is farther than "
    "tactics.shoot_over_tiles, then turn and shoot again; reload only with room (a reload walks away from close zombies by "
    "itself, a run cancels it); with the rifle empty and zombies too close to reload, draw the revolver. Pick the "
    "character's next action.",
    {
        "run_to_pier": "Run to the spot. Right while the character is not at the spot and the fight has not started "
                       "(fire_line_lit false).",
        "face_horde": "Turn to face the horde side. Right when the character is at the spot, the fight has not started and "
                      "the angle between the facing and the nearest zombie (or the horde side) is over 8 degrees.",
        "light_fire_line": "Mark the fight as started. Right when the character is at the spot, faces the horde side (angle "
                           "under 8 degrees) and fire_line_lit is false.",
        "shoot": "Aim and fire at the nearest zombie, walking back while the horde comes on. Right when the fight has started, the gun in hand does not need a reload "
                 "(weapon.needs_reload false), hands_hold_wanted_weapon is true, no reload is in "
                 "progress, and either the horde has not appeared yet (horde_spawned false: aiming is what brings it, so once "
                 "fire_line_lit is true this is the action) or tactics.nearest_is_closer_than_kite_under is false "
                 "(when current_action is kite: once tactics.nearest_is_farther_than_shoot_over is true).",
        "kite": "Run round the horde and away from every zombie, the gun lowered, to win distance back. Right when the horde "
                "has appeared, zombies are alive and tactics.nearest_is_closer_than_kite_under is true, or when "
                "current_action is kite and tactics.nearest_is_farther_than_shoot_over is still false. Wrong once "
                "tactics.nearest_is_farther_than_shoot_over is true and the gun has a round: then shoot.",
        "reload": "Reload the gun in hand (from an empty revolver: back to the rifle; it also fixes the hands when "
                  "hands_hold_wanted_weapon is false); with zombies near it walks away while reloading. Right when the gun "
                  "needs a reload (weapon.needs_reload true: the magazine is empty, even with a round still chambered, "
                  "which the reload keeps) or a reload is in progress or "
                  "hands_hold_wanted_weapon is false, and tactics.nearest_is_closer_than_kite_under is false; or when "
                  "in_hands_is_revolver is true and the revolver is empty. Wrong while weapon.needs_reload is false and no reload is in progress.",
        "draw_sidearm": "Draw the loaded revolver instead of reloading the rifle. Right when the rifle is in hand and empty, "
                        "no reload is in progress, revolver_rounds is over 0, tactics.room_to_reload is false and "
                        "tactics.nearest_is_closer_than_kite_under is false.",
        "hold": "Stand still. Right only when the horde has appeared (horde_spawned true) and zombies_alive is 0. Wrong "
                "while horde_spawned is false: the horde waits for the aim (shoot).",
    },
)


class Remote:
    """The state / command files of a laptop's ~/Zomboid over one persistent ssh connection."""

    def __init__(self, machine):
        cp = configparser.ConfigParser()
        cp.read(Path(__file__).resolve().parent / "queue" / "machines.conf")
        m = cp[machine]
        self.ssh = ["ssh", "-i", os.path.expanduser(m["key"]), "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
                    "-o", "ControlMaster=auto", "-o", f"ControlPath=/tmp/pzopt-director-{machine}.sock",
                    "-o", "ControlPersist=120", m["host"]]

    def read_state(self):
        r = subprocess.run(self.ssh + ["cat Zomboid/pzopt-showcase-state.json 2>/dev/null"], capture_output=True, text=True, timeout=10)
        return json.loads(r.stdout) if r.stdout.strip() else None

    def write_cmd(self, text):
        subprocess.run(self.ssh + ["cat > Zomboid/pzopt-showcase-cmd.tmp && mv Zomboid/pzopt-showcase-cmd.tmp Zomboid/pzopt-showcase-cmd.txt"],
                       input=text, text=True, timeout=10, check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default="/tmp/showcase-director.log")
    ap.add_argument("--wait", type=float, default=900, help="seconds to wait for the game's first state")
    ap.add_argument("--runs", type=int, default=1, help="runs to direct one after the other")
    ap.add_argument("--machine", help="the game runs on this laptop (harness/queue/machines.conf): files over ssh")
    a = ap.parse_args()
    remote = Remote(a.machine) if a.machine else None
    t0 = time.time()
    seq, last_t, seen, decisions, runs = 0, None, False, 0, 0
    log = open(a.log, "a", buffering=1)
    log.write(f"# director started {time.strftime('%Y-%m-%d %H:%M:%S')}, waiting for {STATE}\n")
    while True:
        try:
            if remote:
                st = remote.read_state()
                st = st if st and st["t"] / 1000 > t0 else None
            else:
                fresh = STATE.exists() and STATE.stat().st_mtime > t0
                st = json.loads(STATE.read_text()) if fresh else None
        except (OSError, json.JSONDecodeError, subprocess.SubprocessError, KeyError):
            st = None
        now = time.time()
        if st is None:
            if not seen and now - t0 > a.wait:
                log.write("# no state from the game, giving up\n")
                return 1
            time.sleep(0.2)
            continue
        if seen and now * 1000 - st["t"] > 15000:
            runs += 1
            log.write(f"# state stale for 15 s: the run ended; {decisions} decisions (run {runs} of {a.runs})\n")
            if runs >= a.runs:
                return 0
            t0, seen, last_t, decisions = time.time(), False, None, 0  # the next run's state must be newer than this
            continue
        if st["t"] == last_t:
            time.sleep(0.05)
            continue
        seen, last_t = True, st["t"]
        lat = []
        try:
            q = QUESTION_TACTICS if "tactics" in st else QUESTION_BACK_OFF if st.get("back_off_allowed") else QUESTION_READY if st.get("horde_comes_when_aiming") else QUESTION
            ans = ask(st, {"action": q}, log=lat)["action"]
        except Exception as e:  # keep the last command; the next state gets another try
            log.write(f"{st['seconds_since_start']:6.1f}s  jev error: {e}\n")
            time.sleep(0.5)
            continue
        act = ans["choice"]
        seq += 1
        decisions += 1
        if remote:
            try:
                remote.write_cmd(f"{seq} {act}\n")
            except (OSError, subprocess.SubprocessError) as e:
                log.write(f"{st['seconds_since_start']:6.1f}s  command write failed: {e}\n")
        else:
            tmp = CMD.with_suffix(".tmp")
            tmp.write_text(f"{seq} {act}\n")
            tmp.replace(CMD)
        probs = " ".join(f"{k}={v:.2f}" for k, v in sorted(ans.get("probabilities", {}).items(), key=lambda kv: -kv[1]))
        p, h, w = st["player"], st["horde"], st["weapon"]
        log.write(f"{st['seconds_since_start']:6.1f}s  {act:<15} conf {ans.get('confidence', 0):.2f}  {lat[0]['ms'] if lat else '?'} ms | "
                  f"pier {p['distance_to_pier_tiles']:.1f} face {p['degrees_between_facing_and_nearest_zombie']:.0f} "
                  f"zombies {h['zombies_alive']} near {h['nearest_zombie_tiles']:.1f} mag {w['rounds_in_magazine']}"
                  f"{'+1' if w['round_chambered'] else ''} spare {w['spare_full_magazines']} reloading {w['reloading']} "
                  f"fire {st['fire_line_lit']} killed {st.get('zombies_killed', '-')} hands_ok {w.get('hands_hold_wanted_weapon', '-')}"
                  + (f" standing {st['tactics']['nearest_standing_zombie_tiles']:.1f} w6 {st['tactics']['zombies_within_6_tiles']}"
                     f" reflex {st['tactics']['reflex_running']} hits {st['tactics']['hits_taken']}" if "tactics" in st else "")
                  + f" | {probs}\n")


if __name__ == "__main__":
    sys.exit(main())
