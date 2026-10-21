#!/usr/bin/env bash
# tail1 clean measurement run (no profiler, no dev timers): submit-clean.sh <label> <bench> "<intent>" "<progress tail>" [extra run.sh args...]
set -euo pipefail
label=$1; bench=$2; intent=$3; prog=$4; shift 4
: > /home/diegov/.cache/pzopt-bench/opts-tail1.ini
cd /home/diegov/Documents/ZedProjects/PZ_Optimization
harness/queue.sh submit run --name pz-optimization-f8-tail1 --intent "$intent" \
  --progress "1:1 interleave with reenc-1-* (maintainer's order); tail1: $prog" \
  --resource game-thread,gc,frame-pacing --bench "$bench" --install /home/diegov/pzopt-wt/f8-tail1 -- \
  --label "$label" --vmarg -Dpzopt.userOptionsFile=/home/diegov/.cache/pzopt-bench/opts-tail1.ini \
  --prop enhancementsEnabled=false --prop cloudShadows=false --prop vrr=off --option vsync=false --record "$@" 2>&1 | grep -E '^job'
