#!/usr/bin/env bash
# Lamp-shadow rig (sunShadowStaticLights): the player two squares east of the Rosewood church lot's street lamp
# (8154,11501, lighting_outdoor_01_1, r 18) at 01:00, torch off, four zombies pinned round the lamp, a still camera.
# usage: harness/lampshadow/run-lamp.sh <label> [extra run.sh args...]   (submits through this worktree's queue)
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
label="$1"; shift
exec "$here/harness/queue.sh" submit run --install opt --resource gpu,visual-parity \
  --intent "${LAMP_INTENT:-lamp shadows (sunShadowStaticLights): characters by a street lamp at night}" \
  --progress "${LAMP_PROGRESS:-visual check / A-B of the lamp shadows}" -- \
  --label "$label" --mode bench --flag start=8156,11503 --flag route=S:1 --flag speed=0.2 --flag zoom=1 \
  --flag time_of_day=1 --flag lights=on --flag torch=off --flag weather=clear \
  --flag pin_zombies=8157,11505/8151,11504/8153,11507/8160,11504 \
  --shot-at 2 --route-seconds 12 --no-dashboard --prop overlay=false \
  --vmarg -Dpzopt.userOptionsFile="$here/harness/lampshadow/empty-options.ini" \
  --prop sunShadows=true --prop devStaticLightLog=600 --prop devAoTiming=true "$@"
