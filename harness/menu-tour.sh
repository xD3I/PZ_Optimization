#!/bin/bash
# The PZ OPTIMIZATION screen's mouse tour for a preview video (2026-10-19, harness/mod/.../pzopt_harness_tour.lua,
# flag options_tour=1). Runs harness/run.sh on the main menu with the rig's flags and, while the game runs, turns every
# line the rig appends to Zomboid/Lua/pzopt-tour-input.txt into real input on the game window through xdotool:
#   move X Y MS      the cursor glides to window point X,Y over MS ms
#   click X Y MS     the same, rests 0.3 s, then a left click
#   wheel up|down N  N wheel notches where the cursor is
#   type TEXT        the text typed into the focused field
# Submit as a queue cmd job (with --install <this checkout>):
#   harness/queue.sh submit cmd --label menu-tour ... -- harness/menu-tour.sh [--label menu-tour] [extra run.sh args]
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REQ=~/Zomboid/Lua/pzopt-tour-input.txt
rm -f "$REQ"
label=menu-tour
[[ "${1:-}" == --label ]] && { label=$2; shift 2; }
# --no-tour: only park the cursor in the screen's bottom-right corner (for a controller tour through options_joy)
tour=(--flag options_tour=1)
park=0
[[ "${1:-}" == --no-tour ]] && { tour=(); park=1; shift; }
# --tour <variant>: another tour of pzopt_harness_tour.lua (mods = a modded benchmark report's Mods (Lua) row)
[[ "${1:-}" == --tour ]] && { tour=(--flag "options_tour=$2"); shift 2; }
"$HERE/run.sh" --label "$label" --mode verify --launcher direct --no-mangohud --no-dashboard --quit-after 240 \
  "${tour[@]}" --flag options_clips=1 "$@" &
runpid=$!

win=""
wx=0; wy=0
find_win() {
  win=$(xdotool search --onlyvisible --name '^Project Zomboid' 2>/dev/null | head -1)
  [[ -z "$win" ]] && return 1
  eval "$(xdotool getwindowgeometry --shell "$win" 2>/dev/null | grep -E '^(X|Y)=')"
  wx=${X:-0}; wy=${Y:-0}
  return 0
}
glide() { # window x y, ms
  local tx=$(( wx + $1 )) ty=$(( wy + $2 )) ms=$3
  eval "$(xdotool getmouselocation --shell 2>/dev/null | grep -E '^(X|Y)=')"
  local sx=${X:-$tx} sy=${Y:-$ty}
  local n=$(( ms / 16 )); (( n < 1 )) && n=1
  local i
  for (( i = 1; i <= n; i++ )); do
    # ease in-out (smoothstep) so the cursor reads like a hand, not a teleport
    local t=$(( i * 1000 / n ))
    local e=$(( t * t * (3000 - 2 * t) / 1000000 ))
    xdotool mousemove $(( sx + (tx - sx) * e / 1000 )) $(( sy + (ty - sy) * e / 1000 ))
    sleep 0.016
  done
  xdotool mousemove "$tx" "$ty"
}

done_n=0
while kill -0 $runpid 2>/dev/null; do
  if (( park == 1 )) && find_win; then
    # the bottom-right corner, out of the screen's panels (X = game px * the desktop's scale, read from the X screen size)
    read -r sw sh < <(xdotool getdisplaygeometry)
    xdotool mousemove $(( sw - 6 )) $(( sh - 6 ))
    echo "menu-tour: cursor parked at $(( sw - 6 )),$(( sh - 6 ))"
    park=2
  fi
  if [[ -f "$REQ" ]]; then
    n=$(wc -l < "$REQ")
    while (( done_n < n )); do
      done_n=$((done_n + 1))
      line=$(sed -n "${done_n}p" "$REQ")
      if [[ -z "$win" ]]; then
        if ! find_win; then echo "menu-tour: no game window for '$line'"; continue; fi
        xdotool windowactivate --sync "$win" 2>/dev/null
        echo "menu-tour: game window $win at $wx,$wy"
      fi
      read -r cmd a1 a2 a3 <<< "$line"
      case "$cmd" in
        move)  glide "$a1" "$a2" "${a3:-500}" ;;
        click) glide "$a1" "$a2" "${a3:-500}"; sleep 0.3; xdotool mousedown 1; sleep 0.12; xdotool mouseup 1 ;;
        wheel) b=4; [[ "$a1" == down ]] && b=5
               for (( k = 0; k < ${a2:-1}; k++ )); do xdotool mousedown $b; sleep 0.03; xdotool mouseup $b; sleep 0.03; done ;;
        type)  xdotool type --delay 110 "${line#type }" ;;
        *)     echo "menu-tour: unknown request '$line'" ;;
      esac
      echo "menu-tour: #$done_n $line"
    done
  fi
  sleep 0.05
done
wait $runpid
rc=$?
rm -f "$REQ"
grep -h '\[pzopt-tour\]' ~/Zomboid/console.txt | sed 's/^.*\[pzopt-tour\]/[pzopt-tour]/'
exit $rc
