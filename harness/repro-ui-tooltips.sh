#!/usr/bin/env bash
# repro-ui (2026-10-16, Workshop "hover tooltips flicker at the wrong coordinates for a frame"): a queue `cmd` job.
# Boots to the main menu with --record, opens Options through the harness rig options_seq (each tab in turn), and moves
# the REAL mouse (xdotool) back and forth over every control the rig logs as having a tooltip ("TIP" lines). The Lua quits
# the game after the last step; a hard deadline + trap kill run.sh / the game otherwise.
#   harness/repro-ui-tooltips.sh <label> <options_seq> [run.sh args...]
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
LABEL=${1:?label}; SEQ=${2:?options_seq}; shift 2
OUT=/tmp/claude-1000/repro-ui-$LABEL; rm -rf "$OUT"; mkdir -p "$OUT"
CON="$ZOMBOID/console.txt"
RUN_PID=""; GAME_PID=""
cleanup() {
  if [[ -n "$RUN_PID" ]] && kill -0 "$RUN_PID" 2>/dev/null; then
    GAME_PID=$(pgrep -f '^([^ ]*/)?ProjectZomboid64( |$)' | head -1)
    [[ -n "$GAME_PID" ]] && kill "$GAME_PID" 2>/dev/null
    for i in $(seq 1 30); do kill -0 "$RUN_PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$RUN_PID" 2>/dev/null && kill "$RUN_PID"
  fi
}
trap cleanup EXIT

if [[ -n "${INGAME:-}" ]]; then   # in the world: pause menu -> Options (flag ingame_options), bench save
  FL=(--flag "ingame_options=$INGAME" --flag "options_seq=$SEQ" --flag route=S:1 --flag speed=0.0075 --flag zombies=off)
else
  FL=(--flag options_tab=Optimizations --flag "options_seq=$SEQ")
fi
harness/run.sh --label "$LABEL" --mode bench --launcher direct --record --no-dashboard --no-mangohud \
  "${FL[@]}" "$@" > "$OUT/run.log" 2>&1 &
RUN_PID=$!
sleep 12   # console.txt is rewritten at launch
DEADLINE=$(( $(date +%s) + 330 ))
read DW DH < <(xdotool getdisplaygeometry)
FIFO=$OUT/pointer.fifo; mkfifo "$FIFO"
python3 harness/uinput-pointer.py < "$FIFO" > "$OUT/pointer.log" 2>&1 &
exec 3> "$FIFO"
step=1; REC_PID=""
while (( $(date +%s) < DEADLINE )) && kill -0 "$RUN_PID" 2>/dev/null; do
  if grep -aq "options seq: done" "$CON" 2>/dev/null; then echo "seq done"; break; fi
  line=$(grep -a "options seq: step $step " "$CON" 2>/dev/null | tail -1)
  tips=$(grep -a "options seq: tips $step " "$CON" 2>/dev/null | tail -1)
  if [[ -z "$line" || -z "$tips" ]]; then sleep 0.2; continue; fi
  SW=$(sed -E 's/.*screen=([0-9]+)x.*/\1/' <<<"$line")
  SECS=$(sed -E 's/.*secs=([0-9.]+).*/\1/' <<<"$line")
  SCALE=$(python3 -c "print($DW/$SW)")
  echo "step $step: $line"; echo "  $tips  display=${DW}x${DH} scale=$SCALE"
  if [[ -n "${HIREC:-}" && -z "$REC_PID" ]]; then   # a second capture at the display's refresh: every presented frame
    gpu-screen-recorder -w DP-1 -f "$HIREC" -q very_high -k av1_hdr -cursor no -o "$OUT/recording-hi.mp4" > "$OUT/recording-hi.log" 2>&1 &
    REC_PID=$!; echo "hi-rate capture at $HIREC fps from epoch $(date +%s.%N)" | tee "$OUT/recording-hi.txt"
  fi
  xdotool search --name "Project Zomboid" windowactivate 2>/dev/null
  mapfile -t rects < <(grep -a "options seq: TIP $step " "$CON" | sed -E 's/.* TIP [0-9]+ ([0-9]+) ([0-9]+) ([0-9]+) ([0-9]+) .*/\1 \2 \3 \4/')
  # visit up to 8 rects spread over the list, each ~1.4 s of side-to-side movement inside it (the real pointer)
  python3 - "$SCALE" "$SECS" "${rects[@]}" >&3 <<'PY2'
import sys
scale = float(sys.argv[1]); secs = float(sys.argv[2]) - 1.4
rects = [tuple(map(int, r.split())) for r in sys.argv[3:] if r.strip()]
rects = [r for r in rects if r[2] >= 16]
if rects:
    k = max(1, len(rects) // 8)
    pick = rects[::k][:8]
    i, t = 0, 0.0
    while t + 1.4 <= secs:
        x, y, w, h = pick[i % len(pick)]
        span = min(w - 8, 240)
        print("goto %d %d" % ((x + 4) * scale, (y + h / 2) * scale))
        print("sweep %d %d %d 1.3" % ((x + 4) * scale, (y + h / 2) * scale, span * scale))
        i += 1; t += 1.45
PY2
  sleep "$SECS"
  step=$((step + 1))
done
echo "goto $((DW - 30)) $((DH - 30))" >&3; exec 3>&-
[[ -n "$REC_PID" ]] && { sleep 1; kill -INT "$REC_PID"; wait "$REC_PID" 2>/dev/null; }
for i in $(seq 1 20); do kill -0 "$RUN_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$RUN_PID" 2>/dev/null && { echo "game did not quit (an open Options screen blocks quit in the world): closing it"; cleanup; }
wait "$RUN_PID"; echo "run.sh exit $?"; RUN_PID=""
grep -a "options seq\|options: tabs\|LuaError\|attempt to" "$CON" | head -80 > "$OUT/console-seq.txt"
cat "$OUT/console-seq.txt" | grep -v " TIP " | head -20
tail -5 "$OUT/run.log"
RUN_DIR=$(ls -d harness/runs/"$LABEL"-* 2>/dev/null | tail -1)
[[ -n "$RUN_DIR" && -f "$OUT/recording-hi.mp4" ]] && cp "$OUT/recording-hi.mp4" "$OUT/recording-hi.txt" "$RUN_DIR/" && echo "hi-rate capture -> $RUN_DIR/recording-hi.mp4"
