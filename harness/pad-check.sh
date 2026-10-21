#!/bin/bash
# Controller check of the main menu's PZ Optimization screen's UPDATE and MOD COMPATIBILITY CHECK pages with a virtual
# Xbox 360 pad (harness/pad.py, uinput; pzopt_mainscreen_menu.lua, 2026-10-18). Run it through the queue:
#   harness/queue.sh submit cmd --install keep --label pad-check -- env OFFER=true harness/pad-check.sh
# The game runs with devBenchmarkUi=pad: every change of what the pad drives is a BENCHPAD line in console.txt, and the walk
# reads it back after each press (harness/benchmark-pad-check.sh does the same over every page of the screen).
#  OFFER=true (default, -Dpzopt.devUpdateOffer): an update is offered. A activates the pad, down to PZ OPTIMIZATION, A opens
#    the screen, the home list to Update, A opens the UPDATE page (its rows), right into its detail: UPDATE NOW is offered and
#    the cursor is not on it; B back; the compatibility page the same way; B closes the screen with the cursor on PZ
#    OPTIMIZATION; down to QUIT, A -> "Quit to desktop?", A (Yes) exits.
#  OFFER=false: the UPDATE page offers nothing (no UPDATE NOW).
# A is never sent on UPDATE NOW: the checks stop the game instead.
# Needs python-evdev and write access to /dev/uinput (the `input` group here); the GUID goes into options.ini as a
# `controller=` line for the run (backed up and restored, like run.sh --option).
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OFFER=${OFFER:-true}
OUT=${OUT:-/tmp/pad-check-$OFFER}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
FIFO=/tmp/pzopt-pad.fifo
GUID=030000005e0400008e02000010010000
scripts/pzopt.sh status | tee "$OUT/status.txt" | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
for f in pzopt_mainscreen_menu.lua pzopt_mainscreen_update.lua; do cmp -s "src/lua/client/pzopt/$f" "$PZ_DIR/media/lua/client/pzopt/$f" || { echo "installed $f differs from src"; exit 1; }; done

ocr() { python3 $UI read 2>/dev/null; }
shot() { sleep "${2:-1.2}"; spectacle -b -n -f -o "$OUT/$1.png" >/dev/null 2>&1; echo "shot $1"; }
pad() { echo "$1" > "$FIFO"; sleep "${2:-0.6}"; }
GAME_PID=""
finish() {
  local code=$1
  if [[ -n "$GAME_PID" ]]; then
    for i in $(seq 1 20); do kill -0 "$GAME_PID" 2>/dev/null || break; sleep 1; done
    if kill -0 "$GAME_PID" 2>/dev/null; then echo "game still up after 20 s, killing"; pkill -f '^([^ ]*/)?ProjectZomboid64( |$)'; sleep 3; fi
  fi
  echo quit > "$FIFO" 2>/dev/null; sleep 0.5; rm -f "$FIFO"
  cp "$OUT/options.ini.orig" "$ZOMBOID/options.ini"; rm -f "$ZOMBOID/joypads/$GUID.config"
  grep -a "\[pzopt\] update\|\[pzopt\] menu\|LuaError\|Callframe\|attempt to\|ERROR: General\|pzopt_mainscreen_update" "$ZOMBOID/console.txt" | tail -30 > "$OUT/console-tail.txt"
  echo "console: $(wc -l < "$OUT/console-tail.txt") lines of interest (see $OUT/console-tail.txt)"
  echo "done ($code): $(ls $OUT | tr '\n' ' ')"
  exit "$code"
}

# the pad first, so GLFW's startup scan sees it; then the GUID as an active controller in options.ini
# (Core.loadOptions: controller=<guid>), restored on exit like run.sh --option
rm -f "$FIFO"; mkfifo "$FIFO"
python3 harness/pad.py serve "$FIFO" > "$OUT/pad.log" 2>&1 &
sleep 1.5; grep -q "pad ready" "$OUT/pad.log" || { echo "pad failed: $(cat "$OUT/pad.log")"; exit 1; }
cp "$ZOMBOID/options.ini" "$OUT/options.ini.orig"
[[ -z "$(tail -c1 "$ZOMBOID/options.ini")" ]] || echo >> "$ZOMBOID/options.ini"   # the game leaves no newline after the last option
grep -q "^controller=$GUID" "$ZOMBOID/options.ini" || echo "controller=$GUID" >> "$ZOMBOID/options.ini"

( cd "$PZ_DIR/.." && JAVA_TOOL_OPTIONS="-Dzomboid.steam=0 -Dpzopt.devUpdateOffer=$OFFER -Dpzopt.devBenchmarkUi=pad" exec setsid ./projectzomboid.sh </dev/null >"$OUT/game.log" 2>&1 ) &
sleep 3; GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
for i in $(seq 1 45); do sleep 2; ocr | grep -qi "options" && break; done
sleep 4; shot menu 0

last() { grep -a "BENCHPAD" "$ZOMBOID/console.txt" 2>/dev/null | tail -1 | sed 's/.*BENCHPAD //'; }
el_is() { last | grep -q "el=$1 "; }
focus_is() { last | grep -q "^focus=$1 "; }
check() { local what=$1; shift; if "$@"; then echo "PASS: $what"; else echo "FAIL: $what (now $(last))"; pad b 1.0; finish 2; fi; }
walk() { local btn=$1 name=$2 n=${3:-12}; for _ in $(seq 1 "$n"); do el_is "$name" && return 0; pad "$btn" 0.5; done; el_is "$name"; }

pad a 1.2; shot activated 0.3                       # activation: the stock focus lands on the default item
check "the menu holds the pad" focus_is MainScreen
walk down "PZ OPTIMIZATION" 16; check "down reaches PZ OPTIMIZATION" el_is "PZ OPTIMIZATION"
pad a 1.2; shot screen 0.3
check "A opens the PZ Optimization screen" focus_is PzoptMainMenu
check "the screen opens on its home list" el_is "Optimizations"
walk down "Update" 5; check "the home list reaches Update" el_is "Update"
pad a 0.8; check "A opens the UPDATE page" sh -c "grep -a BENCHPAD '$ZOMBOID/console.txt' | tail -1 | grep -q ' page=update '"
for name in "Installed build" Available From "Release notes"; do pad down 0.5; check "the page lists $name" el_is "$name"; done
walk up "State" 5; pad right 0.6; shot after-a 0.3
ocr > "$OUT/after-a-ocr.txt"
if [[ "$OFFER" == "true" ]]; then
  # the cursor lands past UPDATE NOW (a danger button is never where it arrives): the page's other button
  check "the detail offers the update" sh -c "grep -qi 'UPDATE NOW' '$OUT/after-a-ocr.txt'"
  check "the cursor is not on UPDATE NOW" sh -c "! grep -a BENCHPAD '$ZOMBOID/console.txt' | tail -1 | grep -q 'el=UPDATE NOW '"
else
  if grep -qi "UPDATE NOW" "$OUT/after-a-ocr.txt"; then echo "FAIL: OFFER=false but the page offers an update"; pad b 1.0; finish 2; fi
fi
pad b 0.5; pad b 0.8; check "B goes back to the home list on Update" el_is "Update"
pad down 0.5; check "MOD COMPATIBILITY CHECK has the cursor" el_is "Mod compatibility check"
pad a 0.8; check "A opens its page" el_is "Overview"
pad b 0.8; check "B goes back to the home list" el_is "Mod compatibility check"
pad b 1.2; shot on-item 0.3
check "B closes the screen" focus_is MainScreen
check "the cursor is back on PZ OPTIMIZATION" el_is "PZ OPTIMIZATION"
walk down "QUIT" 12; check "down reaches QUIT" el_is "QUIT"
pad a 1.2; shot quit-confirm 0.3
ocr > "$OUT/quit-ocr.txt"
if grep -qi "desktop" "$OUT/quit-ocr.txt"; then echo "PASS: Quit to desktop? is up"; else echo "FAIL: no quit confirm after A on QUIT"; finish 2; fi
pad a 1.0                                            # Yes: the game exits through the controller
finish 0
