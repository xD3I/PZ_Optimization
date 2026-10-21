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
            q = QUESTION_BACK_OFF if st.get("back_off_allowed") else QUESTION_READY if st.get("horde_comes_when_aiming") else QUESTION
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
                  f"fire {st['fire_line_lit']} killed {st.get('zombies_killed', '-')} hands_ok {w.get('hands_hold_wanted_weapon', '-')} | {probs}\n")


if __name__ == "__main__":
    sys.exit(main())
