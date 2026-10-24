#!/usr/bin/env python3
"""Press-to-screen latency of the game's controller on-screen keyboard (2026-10-24, worktree osk-lag).

  harness/osklag.py <run dir>... [--presses] [--json <file>]

A run is `run.sh --pad harness/pad/osk-lag.txt --flag inputlag=1` (uinput Xbox pad; the script opens PZ OPTIMIZATION's
search keyboard with Back, types with A, moves with the D-pad, deletes with X, spaces with Y). Inputs in the run dir:
pad.log (harness/pad.py: "press <b> t=<ms> up=<ms> us=<epoch us>", marks), pzopt-inputlag.out (pzopt.InputLag: smp /
game input stages with every pad button as bit 16 + its GLFW index, frame stages P / A / S / G, and "U" lines =
what each frame drew of the keyboard) and present.txt (presentprobe: when each frame reached the screen).

Per press, ms after the uinput SYN:
  smp    the render thread's input poll copied it into the polling state
  game   the game thread's logic() swapped it in
  in     the frame that saw it was handed to the render thread (its Lua pad handler runs in OnRenderTick, after that
         frame's UI was drawn, so the earliest frame that can show anything is the next one)
  drawn  the first frame whose UI drew the change (typing: the entry's text; D-pad: the pad focus moved to another key),
         handed to the render thread
  screen that frame on screen (first flip after its GPU completion)
  D-pad only: the stock key highlight fades in over 100 ms and the old one out over 200 ms (UITransition), so a moved
  focus is first drawn identical to the frame before. vis = first frame the new key is lit at all (fade > 0) on screen;
  open: Back pressed -> the keyboard first drawn (drawn / screen) -> a key lit on it (vis; none = no cursor at all).
  cross = the new key at least as bright as the old one on screen (the cursor reads as moved); full = settled.
  frames = drawn frame - in frame (whole frames between the handler and the frame showing the result).
Frame times over the keyboard window: game frame interval (P) and screen flip interval, p50 / p99 / max.
"""
import bisect
import json
import re
import statistics
import sys
from pathlib import Path

GLFW = {"a": 0, "b": 1, "x": 2, "y": 3, "lb": 4, "rb": 5, "back": 6, "start": 7, "guide": 8, "l3": 9, "r3": 10,
        "up": 11, "right": 12, "down": 13, "left": 14}


def pct(xs, p):
    xs = sorted(x for x in xs if x is not None)
    return xs[min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1))))] if xs else None


def load_pad(run):
    presses, marks = [], []
    for line in (run / "pad.log").read_text(errors="replace").splitlines():
        m = re.match(r"(press|hold) (\S+) t=(\d+) up=(\d+)(?: us=(\d+))?", line)
        if m:
            presses.append({"b": m[2], "t": int(m[5]) if m[5] else int(m[3]) * 1000, "up": int(m[4]) * 1000})
            continue
        m = re.match(r"(mark|done) ?(.*?) t=(\d+)", line)
        if m:
            marks.append((int(m[3]) * 1000, m[2].strip() or m[1]))
    return presses, marks


def load_game(run):
    g = {"smp": [], "game": [], "P": [], "A": [], "S": {}, "G": {}, "U": []}
    for line in (run / "pzopt-inputlag.out").read_text(errors="replace").splitlines():
        p = line.split()
        if not p or p[0].startswith("#"):
            continue
        try:
            if p[0] in ("smp", "game"):
                g[p[0]].append((int(p[3][2:]), int(p[1], 16)))
            elif p[0] == "P":
                g["P"].append((int(p[2]), int(p[1])))
            elif p[0] == "A":
                g["A"].append((int(p[2]), int(p[1])))
            elif p[0] in ("S", "G"):
                g[p[0]][int(p[1])] = int(p[2])
            elif p[0] == "U":
                if p[3] == "none":
                    g["U"].append({"f": int(p[1]), "t": int(p[2]), "vis": 0})
                    continue
                r, c = p[4].split(",")
                g["U"].append({"f": int(p[1]), "t": int(p[2]), "vis": int(p[3]), "pos": (int(r), int(c)), "focus": p[5],
                               "len": int(p[6]), "hash": p[7], "ff": float(p[8]), "fo": float(p[9]),
                               "fresh": int(p[10]), "ilen": int(p[11]) if len(p) > 11 else None})
        except (ValueError, IndexError):
            pass
    for k in ("smp", "game", "P", "A"):
        g[k].sort()
    return g


def load_flips(run):
    f = run / "present.txt"
    if not f.exists():
        return []
    off, flips = 0, []
    for line in f.read_text(errors="replace").splitlines():
        if line.startswith("# mono_to_epoch_us"):
            off = int(line.split()[-1])
        elif line and not line.startswith("#"):
            p = line.split()
            if len(p) >= 4 and p[3] != "2":
                flips.append(int(p[0]) + off)
    return sorted(flips)


def analyse(run):
    presses, marks = load_pad(run)
    g = load_game(run)
    flips = load_flips(run)
    push_t = [p[0] for p in g["P"]]
    push_of = {n: t for t, n in g["P"]}
    acq_t = [a[0] for a in g["A"]]

    def first(rows, t, bit):
        i = bisect.bisect_left(rows, (t - 2000,))
        for r in rows[i:]:
            if r[0] > t + 1_000_000:
                return None
            if r[1] >> bit & 1:
                return r[0]
        return None

    def frame_in(t):  # the frame pushed first after game time t
        i = bisect.bisect_left(push_t, t)
        return g["P"][i] if i < len(g["P"]) else None

    def screen(frame):
        tp = push_of.get(frame)
        if tp is None:
            return None
        i = bisect.bisect_left(acq_t, tp - 500)
        if i >= len(g["A"]):
            return None
        a = g["A"][i][1]
        done = max(v for v in (g["G"].get(a), g["S"].get(a), tp) if v is not None)
        j = bisect.bisect_left(flips, done - 200)
        return flips[j] if j < len(flips) and flips[j] - done < 100_000 else None

    U = g["U"]
    u_t = [u["t"] for u in U]
    u_f = [u["f"] for u in U]

    def state_before(t):
        i = bisect.bisect_right(u_t, t) - 1
        return U[i] if i >= 0 else None

    events = []
    for pr in presses:
        b, t = pr["b"], pr["t"]
        sec = ""
        for tm, name in marks:
            if tm <= t:
                sec = name
        if sec not in ("type", "nav-h", "nav-v", "backspace", "space"):
            continue
        bit = 16 + GLFW[b]
        ev = {"section": sec, "b": b, "t": t}
        ev["smp"] = first(g["smp"], t, bit)
        ev["game"] = first(g["game"], t, bit)
        fin = frame_in(ev["game"]) if ev["game"] else None
        ev["in_f"] = fin[1] if fin else None
        ev["in"] = fin[0] if fin else None
        s0 = state_before(ev["game"] or t)
        resp = None
        if s0 and s0.get("vis") and ev["in_f"] is not None:
            i = bisect.bisect_left(u_f, ev["in_f"])
            for u in U[i:]:
                if u["t"] > t + 1_000_000:
                    break
                if "len" not in u:
                    continue
                if b in ("a", "y", "x") and u["len"] != s0["len"] or b in ("up", "down", "left", "right") and (
                        u["pos"] != s0["pos"] or u["focus"] != s0["focus"]):
                    resp = u
                    break
        if s0 and s0.get("ilen") is not None and ev["in_f"] is not None and b in ("a", "y", "x"):
            i = bisect.bisect_left(u_f, ev["in_f"])
            for u in U[i:]:
                if u.get("ilen") is not None and u["ilen"] != s0["ilen"]:
                    ev["internal_f"] = u["f"] - ev["in_f"]
                    break
        if resp:
            ev["drawn_f"] = resp["f"]
            ev["drawn"] = push_of.get(resp["f"])
            ev["screen"] = screen(resp["f"])
            ev["frames"] = resp["f"] - ev["in_f"]
            if b in ("up", "down", "left", "right") and resp["focus"] == "k":
                i = bisect.bisect_left(u_f, resp["f"])
                for key, cond in (("vis", lambda u: u["ff"] > 0), ("cross", lambda u: u["ff"] >= u["fo"]),
                                  ("full", lambda u: u["ff"] >= 0.99 and u["fo"] <= 0.01)):
                    for u in U[i:]:
                        if "pos" not in u or u["pos"] != resp["pos"]:
                            if u["t"] > resp["t"] and "pos" in u and u["pos"] != resp["pos"]:
                                break
                            continue
                        if cond(u):
                            ev[key] = screen(u["f"])
                            ev[key + "_f"] = u["f"] - ev["in_f"]
                            break
        events.append(ev)

    # opening: Back (or L3) in section osk-open -> the keyboard drawn, -> a key lit (the cursor)
    for pr in presses:
        sec = ""
        for tm, name in marks:
            if tm <= pr["t"]:
                sec = name
        if sec != "osk-open":
            continue
        ev = {"section": sec, "b": "open:" + pr["b"], "t": pr["t"]}
        i = bisect.bisect_left(u_t, pr["t"])
        for u in U[i:]:
            if u["t"] > pr["t"] + 3_000_000:
                break
            if u.get("vis") and "drawn" not in ev:
                ev["drawn_f"], ev["drawn"], ev["screen"] = u["f"], push_of.get(u["f"]), screen(u["f"])
            if u.get("vis") and u.get("ff", -1) > 0:
                ev["vis"] = screen(u["f"])
                break
        events.append(ev)

    # frame times over the keyboard window
    t0 = next((tm for tm, n in marks if n == "osk-open"), None)
    t1 = next((tm for tm, n in marks if n == "osk-close"), None)
    win = {}
    if t0 and t1:
        pt = [x for x in push_t if t0 + 1_000_000 <= x <= t1]
        ft = [x for x in flips if t0 + 1_000_000 <= x <= t1]
        for name, xs in (("game", pt), ("flip", ft)):
            d = [(b - a) / 1000 for a, b in zip(xs, xs[1:])]
            if d:
                win[name] = {"n": len(d), "p50": pct(d, 50), "p99": pct(d, 99), "max": max(d),
                             "fps": len(d) / ((xs[-1] - xs[0]) / 1e6)}
        fr = [u["fresh"] for u in U if t0 <= u["t"] <= t1 and "fresh" in u]
        win["fresh_share"] = sum(fr) / len(fr) if fr else None
    return events, win


STAGES = ["smp", "game", "in", "drawn", "screen", "vis", "cross", "full"]


def summarise(run, events, win, show):
    print("== %s" % run)
    groups = {}
    for ev in events:
        kind = "open (%s)" % ev["b"][5:] if ev["b"].startswith("open:") else "type (A)" if ev["b"] == "a" else "space (Y)" if ev["b"] == "y" else "backspace (X)" if ev["b"] == "x" else "D-pad"
        groups.setdefault(kind, []).append(ev)
    out = {}
    for kind, evs in groups.items():
        print("  %-14s n=%d  matched=%d" % (kind, len(evs), sum(1 for e in evs if e.get("screen"))))
        row = {}
        for st in STAGES:
            xs = [(e[st] - e["t"]) / 1000 for e in evs if e.get(st)]
            if not xs:
                continue
            row[st] = {"p50": statistics.median(xs), "p90": pct(xs, 90), "max": max(xs), "n": len(xs)}
            print("    %-7s p50 %6.1f  p90 %6.1f  max %6.1f ms  (n=%d)" % (st, row[st]["p50"], row[st]["p90"], row[st]["max"], len(xs)))
        fr = [e["frames"] for e in evs if e.get("frames") is not None]
        if fr:
            row["frames"] = {"p50": statistics.median(fr), "max": max(fr)}
            print("    frames handler->drawn: median %s, max %s" % (statistics.median(fr), max(fr)))
        for k in ("internal_f", "vis_f", "cross_f", "full_f"):
            fr = [e[k] for e in evs if e.get(k) is not None]
            if fr:
                print("    frames handler->%s: median %s, max %s" % (k[:-2], statistics.median(fr), max(fr)))
        out[kind] = row
        if show:
            for e in evs:
                print("      %s %s " % (e["section"], e["b"]) + " ".join(
                    "%s=%.1f" % (st, (e[st] - e["t"]) / 1000) for st in STAGES if e.get(st)) + " frames=%s" % e.get("frames"))
    for k, v in win.items():
        if isinstance(v, dict):
            print("  %-5s interval p50 %.2f  p99 %.2f  max %.2f ms  (%.1f fps, n=%d)" % (k, v["p50"], v["p99"], v["max"], v["fps"], v["n"]))
    if win.get("fresh_share") is not None:
        print("  keyboard keys rendered fresh in %.0f %% of the logged frames" % (100 * win["fresh_share"]))
    return {"groups": out, "window": win}


def main(argv):
    show = "--presses" in argv
    js = None
    if "--json" in argv:
        js = argv[argv.index("--json") + 1]
    runs = [a for a in argv if not a.startswith("--") and a != js]
    res = {}
    for r in runs:
        events, win = analyse(Path(r))
        res[r] = summarise(r, events, win, show)
    if js:
        Path(js).write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main(sys.argv[1:])
