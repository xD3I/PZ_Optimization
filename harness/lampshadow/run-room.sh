#!/usr/bin/env bash
# Room ceiling-light shadow rig (sunShadowRoomLights): the player in the Rosewood living room beside the church lot
# (8147,11508, the lamp-indoor1 house) at 01:00, every switch round on (lights=on), torch off, zombies pinned in the room,
# a still camera, the shot 2 s into the route.
# usage: harness/lampshadow/run-room.sh <label> [extra run.sh args...]   (submits through this worktree's queue)
# e.g. ... run-room.sh ceil-on            /  ... run-room.sh ceil-off --prop sunShadowRoomLights=false
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
label="$1"; shift
exec "$here/harness/queue.sh" submit run --install opt --resource gpu,visual-parity \
  --intent "${ROOM_INTENT:-room ceiling-light shadows (sunShadowRoomLights): characters in a lit room at night}" \
  --progress "${ROOM_PROGRESS:-visual check / A-B of the ceiling-light shadows}" -- \
  --label "$label" --mode bench --flag start="${ROOM_START:-8147,11508}" --flag route=S:1 --flag speed=0.2 --flag zoom="${ROOM_ZOOM:-1}" \
  --flag time_of_day=1 --flag lights=on --flag torch=off --flag weather=clear \
  --flag pin_zombies="${ROOM_PINS:-8146,11507/8148,11510/8145,11510}" \
  --shot-at 2 --route-seconds 12 --no-dashboard --prop overlay=false \
  --vmarg -Dpzopt.userOptionsFile="$here/harness/lampshadow/empty-options.ini" \
  --prop sunShadows=true --prop devStaticLightLog=600 --prop devAoTiming=true "$@"
