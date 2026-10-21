#!/usr/bin/env bash
# repro-ui (2026-10-16): a queue `cmd` job: run.sh with the given arguments (--record etc.), plus a second capture at the
# display's refresh (HIREC fps, default 165) from the moment the world is up, so one-frame glitches are not lost between
# 60 fps samples. The capture lands in the run dir as recording-hi.mp4 (+ recording-hi.txt with its start epoch).
#   harness/repro-ui-ingame.sh <label> [run.sh args...]
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
LABEL=${1:?label}; shift
OUT=/tmp/claude-1000/repro-ui-$LABEL; rm -rf "$OUT"; mkdir -p "$OUT"
CON="$ZOMBOID/console.txt"
REC_PID=""
cleanup() { [[ -n "$REC_PID" ]] && kill -INT "$REC_PID" 2>/dev/null; }
trap cleanup EXIT
harness/run.sh --label "$LABEL" --launcher direct "$@" > "$OUT/run.log" 2>&1 &
RUN_PID=$!
sleep 12
DEADLINE=$(( $(date +%s) + 300 ))
until grep -aq "harness: route start\|world ready" "$CON" 2>/dev/null; do
  (( $(date +%s) < DEADLINE )) && kill -0 "$RUN_PID" 2>/dev/null || break
  sleep 0.5
done
if kill -0 "$RUN_PID" 2>/dev/null; then
  gpu-screen-recorder -w DP-1 -f "${HIREC:-165}" -q very_high -k av1_hdr -cursor no -o "$OUT/recording-hi.mp4" > "$OUT/recording-hi.log" 2>&1 &
  REC_PID=$!; echo "hi-rate capture at ${HIREC:-165} fps from epoch $(date +%s.%N)" | tee "$OUT/recording-hi.txt"
fi
wait "$RUN_PID"; echo "run.sh exit $?"
[[ -n "$REC_PID" ]] && { kill -INT "$REC_PID"; wait "$REC_PID" 2>/dev/null; REC_PID=""; }
RUN_DIR=$(ls -d harness/runs/"$LABEL"-* 2>/dev/null | tail -1)
[[ -n "$RUN_DIR" && -f "$OUT/recording-hi.mp4" ]] && cp "$OUT/recording-hi.mp4" "$OUT/recording-hi.txt" "$RUN_DIR/" && echo "hi-rate capture -> $RUN_DIR/recording-hi.mp4"
tail -5 "$OUT/run.log"
