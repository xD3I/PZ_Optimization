#!/usr/bin/env python3
"""Live hang detector for a running game (tail pass 2026-10-13, after a zomb2 run deadlocked in AnimParallel.waitEval).

    harness/slowframes/hangwatch.py [--secs 15] [--poll 1] [--out DIR] [--exit-on-end]

Watches the ProjectZomboid64 process: the frame log (~/Zomboid/pzopt-pacing.out, flushed once a second while frames are
presented) and the game thread's CPU time (/proc/<pid>/task/<tid>/stat of "MainThread"). Once frames have started, a
pacing log that stops growing for --secs seconds is a frozen frame counter:
  parked   the game thread used < 5 % of a core meanwhile (a deadlock / a lost wake-up: it waits for something)
  busy     it kept running (an endless loop, or a frame that takes longer than --secs)
It then prints one line, takes a thread dump with `jcmd <pid> Thread.print` (no signal, the game keeps running) into
--out (default ~/.cache/pzopt-hangwatch/), prints the game thread's stack and the frame workers' states, and exits 2.
Exit 0 when the game ends (--exit-on-end) without a freeze. Read-only: never stops the game.
"""
import argparse, os, subprocess, sys, time
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--secs", type=float, default=15.0)
ap.add_argument("--poll", type=float, default=1.0)
ap.add_argument("--out", default=str(Path.home() / ".cache/pzopt-hangwatch"))
ap.add_argument("--exit-on-end", action="store_true")
ap.add_argument("--zomboid", default=str(Path.home() / "Zomboid"))
a = ap.parse_args()
TICK = os.sysconf("SC_CLK_TCK")


def game_pid():
    for p in Path("/proc").iterdir():
        if p.name.isdigit():
            try:
                if (p / "comm").read_text().strip().startswith("ProjectZomboid"):
                    return int(p.name)
            except OSError:
                pass
    return None


def thread(pid, name):
    try:
        for t in (Path("/proc") / str(pid) / "task").iterdir():
            try:
                if (t / "comm").read_text().strip() == name:
                    return int(t.name)
            except OSError:
                pass
    except OSError:
        pass
    return None


def cpu_ticks(pid, tid):
    try:
        f = (Path("/proc") / str(pid) / "task" / str(tid) / "stat").read_text()
        p = f[f.rindex(")") + 2:].split()
        return int(p[11]) + int(p[12]), p[0]
    except (OSError, ValueError, IndexError):
        return None, "?"


def say(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


pacing = Path(a.zomboid) / "pzopt-pacing.out"
pid = tid = None
started = False
last_size = -1
last_change = time.time()
cpu_at_change = None
while True:
    if pid is None or not Path(f"/proc/{pid}").exists():
        if pid is not None:
            say(f"game {pid} ended")
            if a.exit_on_end:
                sys.exit(0)
        pid, tid, started, last_size = game_pid(), None, False, -1
        if pid is None:
            time.sleep(a.poll)
            continue
        say(f"watching game {pid}")
        start_mtime = time.time()
    if tid is None:
        tid = thread(pid, "MainThread")
    try:
        st = pacing.stat()
        size, mtime = st.st_size, st.st_mtime
    except OSError:
        size, mtime = -1, 0
    now = time.time()
    ticks, state = cpu_ticks(pid, tid) if tid else (None, "?")
    if size != last_size:
        if last_size >= 0 and size > last_size and mtime >= start_mtime:
            started = True
        last_size, last_change, cpu_at_change = size, now, ticks
    elif started and now - last_change >= a.secs:
        used = (ticks - cpu_at_change) / TICK if ticks is not None and cpu_at_change is not None else -1
        kind = "unknown (no MainThread task)" if used < 0 else "parked" if used < 0.05 * (now - last_change) else "busy"
        say(f"FROZEN: no frame logged for {now - last_change:.0f} s; game thread {kind} (state {state}, {used:.1f} s CPU meanwhile)")
        out = Path(a.out)
        out.mkdir(parents=True, exist_ok=True)
        dump = out / time.strftime("hang-%Y%m%d-%H%M%S.txt")
        try:
            r = subprocess.run(["jcmd", str(pid), "Thread.print"], capture_output=True, text=True, timeout=60)
            dump.write_text(r.stdout + r.stderr)
            say(f"thread dump: {dump}")
            blocks = r.stdout.split("\n\n")
            for b in blocks:
                if b.startswith('"MainThread"'):
                    print("\n".join(b.splitlines()[:25]))
            workers = [b.splitlines()[1].strip() if len(b.splitlines()) > 1 else "" for b in blocks if b.startswith('"pzopt-frame')]
            if workers:
                print("frame workers: " + "; ".join(sorted(set(workers))))
        except Exception as e:
            say(f"jcmd failed: {e}")
        sys.exit(2)
    time.sleep(a.poll)
