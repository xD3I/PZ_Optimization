#!/usr/bin/env python3
"""Does a controller reach everything on the PZ Optimization screen (every page, the benchmark's included)? (2026-10-18)

Reads <run>/pad-steps.txt of harness/benchmark-pad-check.sh (each press with the focus trace after it, "ok: ..." /
"FAIL: ..." checks) and builds a card: the checks, the screens the pad reached, per screen the controls it focused,
presses after which nothing changed (a stuck focus), the dialogs B closed. Asks Jev (numbers only) whether controller
navigation works on every screen. Writes <run>/benchmark-pad.json.

    harness/benchmark-pad-judge.py <run> [--no-jev]
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

PRESS = re.compile(r"press (\S+) -> focus=(\S+) row=(\d+) col=(\d+) el=(.*?)(?: list=(\S+))?(?: option=(\S+))?(?: scroll=(\S+))?(?: value=(\S+))?(?: page=(\S+))?(?: search=(\S+))?(?: text=(\S+))? open=(\S+)(?: modal=(\S+))?$")
SCREENS = ["MainScreen", "PzoptMainMenu", "OnScreenKeyboardPanel", "ISTextBox", "ISModalDialog"]  # the column, the PZ Optimization screen, the on-screen keyboard of its search, the share-code box, confirms
PAGES = ["home", "perf", "perf/drive", "perf/drive/1", "vis", "search", "problems", "problems/1", "bench", "bench/setup",
         "bench/history", "bench/compare", "bench/report", "update", "compat"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    run = Path(a.run)
    lines = open(run / "pad-steps.txt", encoding="utf-8", errors="replace").read().splitlines()
    oks = [l[4:] for l in lines if l.startswith("ok: ")]
    fails = [l[6:] for l in lines if l.startswith("FAIL: ")]
    seen = {s: set() for s in SCREENS}
    pages = {}
    stuck = []
    prev = None
    closed_by_b = []
    documented = []
    for i, l in enumerate(lines):
        if l.startswith("unused: "):
            documented.append(l[8:])
            # the press before it changed nothing on purpose: not a press without effect
            if stuck and stuck[-1][0] == i - 1:
                stuck.pop()
            continue
        # a check of the game's console right after presses that left the trace as it was (X = "Only what differs", COPY
        # SHARE CODE, a compatibility choice): their effect is in the console, not in the focus, so they are not presses
        # without effect
        if l.startswith("ok: ") and "console.txt" in l:
            j = i - 1
            while stuck and stuck[-1][0] == j:
                stuck.pop()
                j -= 1
            continue
        m = PRESS.match(l)
        if not m:
            continue
        btn, focus, row, col, el = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5).strip()
        state = l.split(" -> ", 1)[1]
        page = m.group(10)
        if focus in seen:
            seen[focus].add(el)
        if focus == "PzoptMainMenu" and page:
            pages.setdefault(page.split("+")[0], set()).add(el)
        if prev is not None:
            if state == prev[1] and btn not in ("a", "b", "start"):
                stuck.append((i, f"{btn} on {prev[0]}{':' + prev[2] if prev[2] else ''}: {el}"))
            if btn == "b" and (prev[0] != focus or prev[2] != page):
                closed_by_b.append(f"{prev[0]}{':' + prev[2] if prev[2] else ''} -> {focus}{':' + page if page else ''}")
        prev = (focus, state, page)
    card = {
        "checks_ok": len(oks), "checks_failed": len(fails), "failed": fails[:12],
        "screens_reached": [s for s in SCREENS if seen[s]],
        "pages_reached": [p for p in PAGES if p in pages],
        "pages_missing": [p for p in PAGES if p not in pages],
        "controls_focused": {p: sorted(v) for p, v in pages.items()},
        "other_controls": {s: sorted(seen[s]) for s in SCREENS if seen[s] and s != "PzoptMainMenu"},
        "b_closed": closed_by_b,
        # a press that changed nothing: the end of a row or list (expected now and then), or a control that keeps the pad
        "presses_without_effect": {"count": len(stuck), "examples": [t for _, t in stuck][:12]},
        # presses the walk expected to change nothing, with the reason (a list that fits one page, a page without X / Y)
        "documented_unused": documented,
        "buttons_pressed": sorted({m.group(1) for m in (PRESS.match(l) for l in lines) if m}),
        "presses": sum(1 for l in lines if l.startswith("press ")),
    }
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul
        q = {
            "all_pages": noul("Did the pad reach every page of the PZ Optimization screen (`pages_missing` empty) and the other "
                              "screens (`screens_reached` lists MainScreen, PzoptMainMenu, OnScreenKeyboardPanel for the search's "
                              "on-screen keyboard, ISTextBox for the share-code box and ISModalDialog for the confirms)?"),
            "checks_pass": noul("Did every scripted check pass (`checks_failed` 0)?"),
            "buttons_reachable": noul("Does `controls_focused` show each page's rows and detail controls (the setup page: scenes, "
                                      "options, Passes, Between passes, Name, START BENCHMARK, PAST RESULTS; past results: OPEN, "
                                      "COMPARE, SET AS BASELINE, SHOW RESULTS FOLDER; the comparison: Only what differs; the report: "
                                      "COPY SHARE CODE, COMPARE A CODE, PAST RESULTS; a setting: its control, DEFAULT, SHOW CLIPS / HIDE CLIPS; the search: the keys typed on the on-screen keyboard, a result's OPEN ITS PAGE)?"),
            "b_closes": noul("Did B go back each time (`b_closed` lists the share-code box, the on-screen keyboard, the confirms and the screen back to "
                             "MainScreen; B inside the screen moves between its pages, which `checks_ok` confirms)?"),
            "every_button": noul("Was every controller button pressed (`buttons_pressed` holds a, b, x, y, lb, rb, lt, rt, back, start, "
                                 "l3, r3, l3+r3 (the overlay chord), up, down, left, right, lsup, lsdown, rsup, rsdown), each press changing something or "
                                 "noted with its reason in `documented_unused`?"),
            "no_trap": noul("Is there no sign of a control that keeps the pad: `presses_without_effect` (presses with no change and "
                            "no note) only a few at the ends of rows or lists, not long runs of presses on one control?"),
            "verdict": choice("Does controller navigation work on every page of the PZ Optimization screen?", {
                "works": "every page reached, every entry and action reachable, B goes back, nothing traps the pad",
                "minor_issues": "everything reachable, but some presses feel wasted or a check failed for a timing reason",
                "broken": "a page, a control or the way back cannot be reached with the pad",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    json.dump(card, open(run / "benchmark-pad.json", "w"), indent=1)
    print(f"wrote {run / 'benchmark-pad.json'}")


if __name__ == "__main__":
    main()
