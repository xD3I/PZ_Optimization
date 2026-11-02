#!/usr/bin/env python3
"""stockrig-helper.py <out dir> <route seconds> <settle seconds>   (2026-10-24, the PZO comparison; started by stockrig-wrap.sh)

The parts of a harness run that live in our Java classes, for a game dir without them:
  - click-to-start: once the harness Lua has pressed Continue (consumed=1 in Zomboid/Lua/pzopt-harness.txt) a uinput
    mouse clicks the left button every 1.5 s while the focused window is "Project Zomboid" (the loading screen's
    GameLoadingState polls Mouse.isButtonDown(0)), until the stockrig Lua writes Lua/pzostock-started.txt;
  - the schedule files: Lua/pzostock-schedule.txt -> Zomboid/pzopt-schedule.out and Lua/pzostock-bench.txt ->
    Zomboid/pzopt-bench.out (run.sh's MangoHud scheduler and analyze.py read those), Zomboid/pzopt-logdone ->
    Lua/pzostock-logdone.txt (the Lua quits once the MangoHud log is closed);
  - a watchdog: no Continue 150 s after launch, no world 240 s after Continue, no route end settle + route + 120 s
    after the world, or no exit 90 s after the route end -> the game is killed (TERM, then KILL), so no job hangs.
Everything goes to <out>/stockrig-helper.log.
"""
import os
import subprocess
import sys
import time

from evdev import UInput, ecodes as e

Z = os.path.expanduser("~/Zomboid")
LUA = os.path.join(Z, "Lua")
GAME_RE = r"^([^ ]*[/\\])?ProjectZomboid64(\.exe)?( |$)"


def now():
    return time.time()


def game_pid():
    r = subprocess.run(["pgrep", "-f", GAME_RE], capture_output=True, text=True)
    p = r.stdout.split()
    return int(p[0]) if p else None


def focused():
    try:
        return subprocess.run(["xdotool", "getactivewindow", "getwindowname"], capture_output=True, text=True,
                              timeout=3).stdout.strip() == "Project Zomboid"
    except (OSError, subprocess.TimeoutExpired):
        return False


def mirror(src, dst):
    if os.path.exists(src) and not os.path.exists(dst):
        tmp = dst + ".tmp"
        with open(src) as f, open(tmp, "w") as g:
            g.write(f.read())
        os.replace(tmp, dst)
        return True
    return False


def main():
    out, route_s, settle_s = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
    logf = open(os.path.join(out, "stockrig-helper.log"), "a", buffering=1)

    def log(s):
        logf.write("%s %s\n" % (time.strftime("%H:%M:%S"), s))

    mouse = UInput({e.EV_KEY: [e.BTN_LEFT, e.BTN_RIGHT], e.EV_REL: [e.REL_X, e.REL_Y]}, name="pzo-cmp stockrig mouse")
    t0 = now()
    consumed_t = started_t = bench_t = None
    last_click = 0.0
    clicks = skipped = 0
    seen_game = False
    log("helper up (route %.0f s, settle %.0f s), mouse %s" % (route_s, settle_s, mouse.device.path))
    try:
        while True:
            time.sleep(0.25)
            pid = game_pid()
            if pid:
                seen_game = True
            elif seen_game or now() - t0 > 150:
                log("game gone; helper exits (clicks %d, skipped unfocused %d)" % (clicks, skipped))
                return
            if consumed_t is None:
                try:
                    with open(os.path.join(LUA, "pzopt-harness.txt")) as f:
                        if "consumed=1" in f.read():
                            consumed_t = now()
                            log("Continue pressed by the harness Lua; clicking click-to-start from now")
                except OSError:
                    pass
            if started_t is None and os.path.exists(os.path.join(LUA, "pzostock-started.txt")):
                started_t = now()
                log("player exists after %d clicks (%.1f s after Continue)" % (clicks, started_t - (consumed_t or t0)))
            if consumed_t is not None and started_t is None and now() - consumed_t > 2 and now() - last_click >= 1.5:
                last_click = now()
                if focused():
                    mouse.write(e.EV_KEY, e.BTN_LEFT, 1); mouse.syn(); time.sleep(0.1)
                    mouse.write(e.EV_KEY, e.BTN_LEFT, 0); mouse.syn()
                    clicks += 1
                else:
                    skipped += 1
                    if skipped % 10 == 1:
                        log("not clicking: the focused window is not the game (%d skips)" % skipped)
            if mirror(os.path.join(LUA, "pzostock-schedule.txt"), os.path.join(Z, "pzopt-schedule.out")):
                log("schedule mirrored")
            if bench_t is None and mirror(os.path.join(LUA, "pzostock-bench.txt"), os.path.join(Z, "pzopt-bench.out")):
                bench_t = now()
                log("route end mirrored (pzopt-bench.out)")
            if os.path.exists(os.path.join(Z, "pzopt-logdone")) and not os.path.exists(os.path.join(LUA, "pzostock-logdone.txt")):
                open(os.path.join(LUA, "pzostock-logdone.txt"), "w").write("1\n")
                log("MangoHud log closed; told the Lua")
            why = None
            if consumed_t is None and now() - t0 > 150:
                why = "no Continue 150 s after launch"
            elif consumed_t is not None and started_t is None and now() - consumed_t > 240:
                why = "no world 240 s after Continue"
            elif started_t is not None and bench_t is None and now() - started_t > settle_s + route_s + 120:
                why = "no route end %.0f s after the world" % (settle_s + route_s + 120)
            elif bench_t is not None and now() - bench_t > 90:
                why = "the game did not quit 90 s after the route end"
            if why and pid:
                log("WATCHDOG: %s; killing pid %d" % (why, pid))
                os.kill(pid, 15)
                for _ in range(20):
                    time.sleep(0.5)
                    if not game_pid():
                        break
                else:
                    p2 = game_pid()
                    if p2:
                        os.kill(p2, 9)
                open(os.path.join(out, "WATCHDOG"), "w").write(why + "\n")
    finally:
        mouse.close()


if __name__ == "__main__":
    main()
