#!/usr/bin/env python3
"""repro-ui (2026-10-16): a real pointer for menu repros on the Wayland desktop. xdotool's warps do not reach KWin
(the pointer stays where it is), so this drives a uinput mouse (relative moves + BTN_LEFT, i.e. kernel input that KWin
routes to the game like a hand on the mouse) closed-loop: the position is read back through XWayland
(`xdotool getmouselocation`, valid while the pointer is over the game's X window). Coordinates are X screen px.

Commands on stdin, one per line:
  goto X Y            put the pointer there (within 1 px)
  sweep X Y W SECS    side-to-side over [X, X+W] at row Y for SECS (4 px a step, ~100 steps/s), slight vertical jitter
  click               left press + release
  sleep S
"""
import subprocess
import sys
import time

from evdev import UInput, ecodes as e

ui = UInput({e.EV_KEY: [e.BTN_LEFT, e.BTN_RIGHT, e.BTN_MIDDLE], e.EV_REL: [e.REL_X, e.REL_Y, e.REL_WHEEL]},
            name="pzopt repro-ui pointer")
time.sleep(0.8)  # let libinput / KWin pick the device up


def pos():
    out = subprocess.run(["xdotool", "getmouselocation"], capture_output=True, text=True).stdout.split()
    return int(out[0][2:]), int(out[1][2:])


def rel(dx, dy):
    ui.write(e.EV_REL, e.REL_X, int(dx))
    ui.write(e.EV_REL, e.REL_Y, int(dy))
    ui.syn()


G = [1.0, 1.0]  # observed screen px per uinput unit (libinput's acceleration at our speeds), per axis


def goto(tx, ty, tol=1, budget=3.0):
    t0 = time.time()
    x, y = pos()
    while time.time() - t0 < budget:
        dx, dy = tx - x, ty - y
        if abs(dx) <= tol and abs(dy) <= tol:
            return True
        sx = int(max(-24, min(24, round(dx * 0.6 / G[0])))) or (1 if dx > 0 else -1 if dx < 0 else 0)
        sy = int(max(-24, min(24, round(dy * 0.6 / G[1])))) or (1 if dy > 0 else -1 if dy < 0 else 0)
        rel(sx, sy)
        time.sleep(0.006)
        nx, ny = pos()
        if abs(sx) >= 3 and 0 < nx < 5119:
            G[0] = max(0.2, min(8.0, 0.7 * G[0] + 0.3 * abs(nx - x) / abs(sx)))
        if abs(sy) >= 3 and 0 < ny < 2159:
            G[1] = max(0.2, min(8.0, 0.7 * G[1] + 0.3 * abs(ny - y) / abs(sy)))
        x, y = nx, ny
    return False


def sweep(x, y, w, secs):
    t0 = time.time()
    d = 0
    while time.time() - t0 < secs:
        p = d % (2 * w)
        px = p if p < w else 2 * w - p
        goto(x + px, y + ((d // 28) % 5) - 2, tol=2, budget=0.03)
        d += 4
        time.sleep(0.006)


for _ in range(3):  # home in the top-left corner (over the game window), so XWayland reports the pointer
    rel(-6000, -6000)
    time.sleep(0.05)
print("homed at", pos(), flush=True)

for line in sys.stdin:
    a = line.split()
    if not a:
        continue
    if a[0] == "goto":
        ok = goto(int(float(a[1])), int(float(a[2])))
        print("goto", a[1], a[2], "ok" if ok else "MISSED", pos(), "gain %.2f %.2f" % tuple(G), flush=True)
    elif a[0] == "sweep":
        sweep(int(float(a[1])), int(float(a[2])), int(float(a[3])), float(a[4]))
    elif a[0] == "click":
        ui.write(e.EV_KEY, e.BTN_LEFT, 1); ui.syn(); time.sleep(0.12)
        ui.write(e.EV_KEY, e.BTN_LEFT, 0); ui.syn()
        print("click at", pos(), flush=True)
    elif a[0] == "sleep":
        time.sleep(float(a[1]))
ui.close()
