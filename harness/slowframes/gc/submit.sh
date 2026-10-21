#!/usr/bin/env bash
# gc run helper: submit.sh <label> "<intent>" [extra run.sh args...]
# = integ3/submit.sh base args (Louisville, cap 120 + frameQueue) with the f8-gc install, opts-gc.ini and a detailed GC log
# GC flags: GCARGS env (default "-Xms4096m -XX:MaxGCPauseMillis=10", integ3's), BENCH (default louisville), CAP=0 for uncapped
set -euo pipefail
label=$1; intent=$2; shift 2
GCARGS=${GCARGS:--Xms4096m -XX:MaxGCPauseMillis=10}
BENCH=${BENCH:-louisville}
INSTALL=${INSTALL:-/home/diegov/pzopt-wt/f8-gc}
gcv=(); for a in $GCARGS; do gcv+=(--vmarg "$a"); done
if [[ ${CAP:-1} == 1 ]]; then capv=(--prop frameCapFps=120 --prop frameQueue=true); else capv=(--prop uncappedFps=true); fi
: > /home/diegov/.cache/pzopt-bench/opts-gc.ini
cd /home/diegov/Documents/ZedProjects/PZ_Optimization
harness/queue.sh submit run --name pz-optimization-f8-gc --intent "$intent" \
  --progress "1:1 interleave with reenc-1-* (maintainer's order)" \
  --resource gc,game-thread,frame-pacing --bench "$BENCH" --install "$INSTALL" -- \
  --label "$label" --vmarg -Dpzopt.userOptionsFile=/home/diegov/.cache/pzopt-bench/opts-gc.ini \
  --prop enhancementsEnabled=false --prop cloudShadows=false --prop vrr=off --option vsync=false --record \
  --gc g1 --option tieredZombieUpdates=true --prop zombieSpawnSpread=true --prop lootDefer=true \
  --prop zombieReuseSpread=true --prop zombieModelAddBudgetUs=500 \
  --prop tileRecordParallel=true --prop glNoSync=true --prop glNamePool=4096 --prop animalLosWalk=true \
  --prop animalPreload=true --prop combinerPool=true --prop pacingQueryBuffer=true \
  "${capv[@]}" "${gcv[@]}" \
  --vmarg -Xlog:async --vmarg "-Xlog:safepoint=info,gc=info,gc+phases=debug,gc+heap=debug,gc+age=trace,gc+cpu=info,gc+ergo+heap=debug:file=/home/diegov/.cache/pzopt-bench/gc-jvm-$label.log:time,uptime" \
  "$@" 2>&1 | grep -E '^job|order|rror|refus' || true
