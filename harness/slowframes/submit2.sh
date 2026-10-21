#!/usr/bin/env bash
# tail2 run helper (goal-120 setup baked in: G1, tiered updates, the four timing keys):
#   harness/slowframes/submit2.sh <label> <kind> "<intent>" "<progress tail>" [extra run.sh args...]
# kind: p   = uncapped + per-frame sections + 1 ms CPU profile + safepoint / GC-phase log (copy
#             ~/.cache/pzopt-bench/tail1-jvm-<label>.log into the run dir as jvm.log for slowframes.py)
#       c   = uncapped, clean (no profiler, no dev timers)
#       cap = capped at 120, clean
#       capp= capped at 120 + sections + GC log (no CPU profiler)
#       drive = drive-120-south bench, uncapped, clean
set -euo pipefail
label=$1; kind=$2; intent=$3; prog=$4; shift 4
bench=louisville
extra=()
case "$kind" in
  p) extra=(--prop uncappedFps=true --prop devFrameSections=true --asprof event=cpu,interval=1ms
            --vmarg "-Xlog:safepoint=info,gc+phases=info:file=/home/diegov/.cache/pzopt-bench/tail1-jvm-$label.log:time,uptime") ;;
  c) extra=(--prop uncappedFps=true) ;;
  cap) extra=(--prop frameCapFps=120) ;;
  capp) extra=(--prop frameCapFps=120 --prop devFrameSections=true
            --vmarg "-Xlog:safepoint=info,gc+phases=info:file=/home/diegov/.cache/pzopt-bench/tail1-jvm-$label.log:time,uptime") ;;
  drive) bench=drive-120-south; extra=(--prop uncappedFps=true) ;;
  *) echo "kind?"; exit 2 ;;
esac
: > /home/diegov/.cache/pzopt-bench/opts-tail1.ini
cd /home/diegov/Documents/ZedProjects/PZ_Optimization
harness/queue.sh submit run --name pz-optimization-f8-tail2 --intent "$intent" \
  --progress "1:1 interleave with reenc-1-* (maintainer's order); tail2: $prog" \
  --resource game-thread,gc,frame-pacing --bench "$bench" --install "${INSTALL:-/home/diegov/pzopt-wt/f8-tail1}" -- \
  --label "$label" --vmarg -Dpzopt.userOptionsFile=/home/diegov/.cache/pzopt-bench/opts-tail1.ini \
  --prop enhancementsEnabled=false --prop cloudShadows=false --prop vrr=off --option vsync=false --record \
  --gc g1 --option tieredZombieUpdates=true --prop zombieSpawnSpread=true --prop lootDefer=true \
  --prop zombieReuseSpread=true --prop zombieModelAddBudgetUs=500 \
  "${extra[@]}" "$@" 2>&1 | grep -E '^job|error|refus' || true
