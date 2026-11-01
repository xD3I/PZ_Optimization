#!/usr/bin/env python3
"""stockrig-table.py <run dir>...   (2026-10-24, PZO comparison phase 2): one markdown row per run.

Frame times from MangoHud (mangohud.csv, the route window from pzopt-bench.out: the Java harness writes it for ours, the
stockrig Lua + stockrig-helper.py for the stock / PZO rows); machine CPU / busiest core / GPU and the game's CPU (100 =
one core) from sysmon.csv over the same window; console ERROR lines counted in console.txt; the route facts."""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from analyze import summarize  # noqa: E402


def row(run):
    run = Path(run)
    s = summarize(run)
    m = s.get("mangohud") or {}
    us = m.get("us", {})
    sm = s.get("sysmon") or {}
    sc = s.get("scenario") or {}
    con = run / "console.txt"
    text = con.read_text(errors="replace") if con.exists() else ""
    errors = sum(1 for l in text.splitlines() if l.startswith("ERROR"))
    rig_errors = text.count("[pzostock] rig error")

    def g(k):
        return sm.get(k, {}).get("mean")

    fps = 1e6 / us["mean"] if us.get("mean") else 0
    return ("| %s | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %s / %s | %s | %s | %s | %d | %s | %s |" % (
        run.name, fps, us.get("p50", 0) / 1e3, us.get("p99", 0) / 1e3, us.get("p99_9", 0) / 1e3, us.get("max", 0) / 1e3,
        m.get("fps_1pct_low", 0),
        "%.0f" % g("cpu_pct") if g("cpu_pct") is not None else "-", "%.0f" % g("cpu_busiest_core_pct") if g("cpu_busiest_core_pct") is not None else "-",
        "%.0f" % g("gpu_pct") if g("gpu_pct") is not None else "-", "%.0f" % g("game_cpu_pct") if g("game_cpu_pct") is not None else "-",
        m.get("over_33ms", "-"), errors, sc.get("route_status", "?") + ("" if not rig_errors else " RIGERR"),
        "%s s, zoom %s, %s" % (sc.get("route_seconds", "?") if "route_seconds" in sc else "%.1f" % ((int(sc.get("route_end_epoch_ms", 0)) - int(sc.get("route_start_epoch_ms", 0))) / 1000),
                               sc.get("zoom", "?"), "zombies " + sc["zombies_loaded"] if "zombies_loaded" in sc else (sc.get("vehicle_end", "") or ""))))


print("| run | fps | p50 ms | p99 ms | p99.9 ms | max ms | 1 % low fps | CPU % / busiest core % | GPU % | game CPU (100=1 core) | >33 ms | console ERROR | route | route facts |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for r in sys.argv[1:]:
    print(row(r))
