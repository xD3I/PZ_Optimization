#!/bin/bash
# Real Escape presses for the pause-menu rig (harness/mod/.../pzopt_harness_esc.lua, flag esc_check) (2026-10-19).
# Runs harness/run.sh with the rig's flags and, while the game runs, turns every line the rig appends to
# Zomboid/Lua/pzopt-esc-press.txt into a real key press on the game window (xdotool keydown / keyup), the way a player's
# Escape reaches the game (OnKeyStartPressed + OnKeyPressed from the input loop), not a direct ToggleEscapeMenu call.
# Submit as a queue cmd job:
#   harness/queue.sh submit cmd --label esc-check ... -- harness/esc-check.sh [--label esc-check] [extra run.sh args]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PRESS=~/Zomboid/Lua/pzopt-esc-press.txt
rm -f "$PRESS"
label=esc-check
[[ "${1:-}" == --label ]] && { label=$2; shift 2; }
"$HERE/run.sh" --label "$label" --mode verify --launcher direct --no-mangohud --no-dashboard --quit-after 150 \
  --flag esc_check=10 --flag esc_quit=true "$@" &
runpid=$!
done_n=0
while kill -0 $runpid 2>/dev/null; do
  if [[ -f "$PRESS" ]]; then
    n=$(wc -l < "$PRESS")
    while (( done_n < n )); do
      done_n=$((done_n + 1))
      win=$(xdotool search --onlyvisible --name '^Project Zomboid' 2>/dev/null | head -1)
      if [[ -n "$win" ]]; then
        xdotool windowactivate --sync "$win" 2>/dev/null
        sleep 0.15
        xdotool keydown Escape; sleep 0.08; xdotool keyup Escape
        echo "esc-check: pressed Escape #$done_n on window $win ($(xdotool getactivewindow getwindowname 2>/dev/null))"
      else
        echo "esc-check: no game window for press #$done_n"
      fi
    done
  fi
  sleep 0.2
done
wait $runpid
rc=$?
rm -f "$PRESS"
grep -h '\[pzopt-esc\]' ~/Zomboid/console.txt | sed 's/^.*\[pzopt-esc\]/[pzopt-esc]/'
exit $rc
