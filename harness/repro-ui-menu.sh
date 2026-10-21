#!/usr/bin/env bash
# repro-ui (2026-10-16, Workshop "Show Performance Overlay blocks the mod list"): a queue `cmd` job. Boots to the main
# menu with --record and the harness rig menu_hold, reads the logged item rectangles, hovers the REAL mouse over MODS and
# clicks it (xdotool), logs which item the game dispatched the click to. With a fixture mod (harness/compat/
# make-menu-fixture.sh, pass `--mod pzopt-menu-fixture`) that lays the stock items out again, as menu mods do.
#   harness/repro-ui-menu.sh <label> [run.sh args...]
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
LABEL=${1:?label}; shift
OUT=/tmp/claude-1000/repro-ui-$LABEL; rm -rf "$OUT"; mkdir -p "$OUT"
CON="$ZOMBOID/console.txt"
RUN_PID=""
cleanup() {
  if [[ -n "$RUN_PID" ]] && kill -0 "$RUN_PID" 2>/dev/null; then
    GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
    [[ -n "$GAME_PID" ]] && kill "$GAME_PID" 2>/dev/null
    for i in $(seq 1 30); do kill -0 "$RUN_PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$RUN_PID" 2>/dev/null && kill "$RUN_PID"
  fi
}
trap cleanup EXIT
harness/compat/make-menu-fixture.sh
harness/run.sh --label "$LABEL" --mode bench --launcher direct --record --no-dashboard --no-mangohud \
  --flag menu_hold=22 "$@" > "$OUT/run.log" 2>&1 &
RUN_PID=$!
sleep 12
DEADLINE=$(( $(date +%s) + 200 ))
until grep -aq "menu hold: menu items logged" "$CON" 2>/dev/null; do
  (( $(date +%s) < DEADLINE )) && kill -0 "$RUN_PID" 2>/dev/null || { echo "menu never came up"; exit 1; }
  sleep 0.5
done
read DW DH < <(xdotool getdisplaygeometry)
SW=$(grep -a "menu hold: menu up" "$CON" | tail -1 | sed -E 's/.*screen=([0-9]+)x.*/\1/')
SCALE=$(python3 -c "print($DW/$SW)")
grep -a "menu item (1s)" "$CON" | sed 's/.*menu item (1s): //' | tee "$OUT/items.txt"
mods=$(grep -a "menu item (1s): MODS " "$CON" | tail -1)
[[ -n "$mods" ]] || { echo "no MODS item"; exit 1; }
read MX MY MW MH < <(sed -E "s/.* x=([0-9-]+) y=([0-9-]+) w=([0-9]+) h=([0-9]+).*/\1 \2 \3 \4/" <<<"$mods")
# the hit point: on the MODS text (the label is as wide as the panel; the text starts at its left)
TX=$(( MX + 60 )); TY=$(( MY + MH / 2 ))
echo "MODS item at $MX,$MY ${MW}x$MH; pointer to UI $TX,$TY (scale $SCALE)"
xdotool search --name "Project Zomboid" windowactivate 2>/dev/null; sleep 0.5
GX=$(python3 -c "print(int($TX*$SCALE))"); GY=$(python3 -c "print(int($TY*$SCALE))")
SX=$(python3 -c "print(int(($TX+600)*$SCALE))"); SY=$(python3 -c "print(int(($TY-150)*$SCALE))")
{ echo "goto $SX $SY"; for i in $(seq 1 12); do echo "goto $(( SX - (SX-GX)*i/12 )) $(( SY - (SY-GY)*i/12 ))"; echo "sleep 0.08"; done
  echo "goto $GX $GY"; echo "sleep 2"; echo "click"; echo "sleep 3"; echo "goto $((DW - 30)) $((DH - 30))"; } \
  | python3 harness/uinput-pointer.py 2>&1 | tee "$OUT/pointer.log"
grep -a "menu hold: click" "$CON" | tee "$OUT/clicks.txt"
grep -a "overlay: shown\|overlay: hidden\|\[pzopt\] overlay" "$CON" | tail -5 | tee "$OUT/overlay.txt"
wait "$RUN_PID"; echo "run.sh exit $?"; RUN_PID=""
grep -a "pzopt-menu-fixture\|menu hold\|LuaError\|attempt to" "$CON" | head -40 > "$OUT/console-menu.txt"
tail -4 "$OUT/run.log"
