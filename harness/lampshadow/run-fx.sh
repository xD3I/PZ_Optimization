#!/usr/bin/env bash
# Static-light shadow rig, generic (sunShadowStaticLights): 01:00, torch off, clear, still camera, zoom 1, a shot 2 s into
# the route, the empty options file, the dev static-light log. The scene (start=, pin_zombies=, lights=on|lamps,
# campfire=, burn_pinned=, fire=) comes from the arguments.
# usage: harness/lampshadow/run-fx.sh <label> [extra run.sh args...]   (submits through this worktree's queue)
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
label="$1"; shift
exec "$here/harness/queue.sh" submit run --install opt --resource gpu,visual-parity \
  --intent "${LAMP_INTENT:-static-light shadows (sunShadowStaticLights): lamps / fires / burning zombies at night}" \
  --progress "${LAMP_PROGRESS:-visual check / A-B of the static-light shadows}" -- \
  --label "$label" --mode bench --flag route=S:1 --flag speed=0.05 --flag zoom=1 \
  --flag time_of_day=1 --flag torch=off --flag weather=clear \
  --shot-at 2 --route-seconds 12 --no-dashboard --prop overlay=false \
  --vmarg -Dpzopt.userOptionsFile="$here/harness/lampshadow/empty-options.ini" \
  --prop sunShadows=true --prop devStaticLightLog=300 --prop devAoTiming=true "$@"
