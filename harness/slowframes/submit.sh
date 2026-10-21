#!/usr/bin/env bash
# tail1 run helper: harness/slowframes/submit.sh <label> "<intent>" "<progress tail>" [extra run.sh args...]
# Submits an uncapped (or, with --prop frameCapFps=120 in the extras, capped) Louisville run through the main checkout's
# queue with the goal-120 run setup, per-frame sections, a 1 ms CPU profile and a safepoint / GC-phase log
# (~/.cache/pzopt-bench/tail1-jvm-<label>.log; copy it into the run dir as jvm.log for slowframes.py).
set -euo pipefail
label=$1; intent=$2; prog=$3; shift 3
uncap=(--prop uncappedFps=true)
for a in "$@"; do [[ "$a" == frameCapFps=* ]] && uncap=(); done
: > /home/diegov/.cache/pzopt-bench/opts-tail1.ini
cd /home/diegov/Documents/ZedProjects/PZ_Optimization
harness/queue.sh submit run --name pz-optimization-f8-tail1 --intent "$intent" \
  --progress "1:1 interleave with reenc-1-* (maintainer's order); tail1: $prog" \
  --resource game-thread,gc,frame-pacing --bench louisville --install /home/diegov/pzopt-wt/f8-tail1 -- \
  --label "$label" --vmarg -Dpzopt.userOptionsFile=/home/diegov/.cache/pzopt-bench/opts-tail1.ini \
  --prop enhancementsEnabled=false --prop cloudShadows=false --prop vrr=off --option vsync=false --record \
  "${uncap[@]}" --prop devFrameSections=true --asprof event=cpu,interval=1ms \
  --vmarg "-Xlog:safepoint=info,gc+phases=info:file=/home/diegov/.cache/pzopt-bench/tail1-jvm-$label.log:time,uptime" \
  "$@" 2>&1 | grep -E '^job'
