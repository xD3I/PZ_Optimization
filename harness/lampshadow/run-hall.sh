#!/usr/bin/env bash
# Hallway rig (sunShadowRoomLights, several fixtures): the Rosewood church's long room 8131,11516-8150,11520 (z 0, 19 x 4;
# StaticLightShadow.roomPoints gives three fixtures at x 8134.2 / 8140.5 / 8146.8, y 11518.4), 01:00, every switch on, torch
# off, zombies pinned near the midpoints between fixtures and under the middle one, a gray 1:1 frame capture (devCapture) of
# the 1600 x 1000 px round the player. No --shot-at (it holds the camera). devStaticLightLog also logs the player's picks on change.
#   walk: harness/lampshadow/run-hall.sh <label> walk [extra run.sh args]   (on foot, explore=walk, 8133 <-> 8148 along y 11518)
#   hold: harness/lampshadow/run-hall.sh <label> hold [extra run.sh args]   (the player still at the f1-f2 midpoint)
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
label="$1"; mode="$2"; shift 2
if [[ "$mode" == walk ]]; then
  M=(--flag start=8133,11518 --flag explore=walk --flag "walk=8133,11518;8148,11518" --flag route=S:1 --flag speed=0.05 --route-seconds 20 --prop "devCapture=5,14,30,100,gray,crop=1760:580:1600:1000,ram")
else
  M=(--flag start=8137,11517 --flag route=E:0 --flag hold=10 --route-seconds 10 --prop "devCapture=6,4,60,100,gray,crop=1760:580:1600:1000,ram")
fi
exec "$here/harness/queue.sh" submit run --install opt --resource gpu,visual-parity \
  --intent "${HALL_INTENT:-ceiling-light shadows in a hallway with three fixtures: nearest fixtures, no flip between them}" \
  --progress "${HALL_PROGRESS:-follow-up 3/3 (hallway)}" -- \
  --label "$label" --mode bench --flag zoom=1 --flag time_of_day=1 --flag lights=on --flag torch=off --flag weather=clear \
  --flag pin_zombies="${HALL_PINS:-8137,11518/8140,11519/8143,11517}" "${M[@]}" --no-dashboard --prop overlay=false \
  --vmarg -Dpzopt.userOptionsFile="$here/harness/lampshadow/empty-options.ini" \
  --prop sunShadows=true --prop devStaticLightLog=600 --prop devAoTiming=true "$@"
