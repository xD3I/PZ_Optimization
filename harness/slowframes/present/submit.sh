#!/usr/bin/env bash
# integ7 run helper (copy of integ4/submit.sh): submit.sh <label> <bench> "<intent>" [extra run.sh args...]
export PZQ_SESSION=pz-optimization-f9-present
# = integ3/submit.sh with the integ-r8 install, opts-f9p.ini, the GC agent's lock-run JVM set (fin8.flags + AlwaysPreTouch,
# each --vmarg its own word) + its async GC log (copied into the run as jvm.log by ev.sh), gcSlack 40 %, frameQueue and the
# four horde2 keys
set -euo pipefail
label=$1; bench=$2; intent=$3; shift 3
GCARGS=${GCARGS:-"-Xms8192m -Xmx8192m -XX:MaxGCPauseMillis=5 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=1 -XX:MaxTenuringThreshold=0 -XX:ReservedCodeCacheSize=512m -XX:SweeperThreshold=50 -XX:-G1UseAdaptiveIHOP -XX:InitiatingHeapOccupancyPercent=70 -XX:G1MixedGCCountTarget=24 -XX:G1MixedGCLiveThresholdPercent=50 -XX:G1HeapWastePercent=10 -XX:G1MaxNewSizePercent=2 -XX:+AlwaysPreTouch"}
gcv=(); for a in $GCARGS; do gcv+=(--vmarg "$a"); done
# NOLOG=1: no detailed GC log (gcframes.py then reads run.sh's own gc.log)
logv=(--vmarg -Xlog:async --vmarg "-Xlog:safepoint=info,gc=info,gc+phases=debug,gc+heap=debug,gc+age=trace,gc+cpu=info,gc+ergo+heap=debug:file=/home/diegov/.cache/pzopt-bench/gc-jvm-$label-%p.log:time,uptime")
[[ ${NOLOG:-1} == 1 ]] && logv=()
: > /home/diegov/.cache/pzopt-bench/opts-f9p.ini
cd /home/diegov/pzopt-wt/f9-present
harness/queue.sh submit run --name pz-optimization-f9-present --intent "$intent" \
  --progress "${PROGRESS:-f9-present: diagnosing the present-path swap blocks}" \
  --resource game-thread,render-thread,frame-pacing --bench "$bench" --install /home/diegov/pzopt-wt/f9-present -- \
  --label "$label" --vmarg -Dpzopt.userOptionsFile=/home/diegov/.cache/pzopt-bench/opts-f9p.ini \
  --prop enhancementsEnabled=false --prop cloudShadows=false --prop vrr=off --option vsync=false --record \
  --gc g1 --option tieredZombieUpdates=true --prop zombieSpawnSpread=true --prop lootDefer=true \
  --prop zombieReuseSpread=true --prop zombieModelAddBudgetUs=500 \
  --prop tileRecordParallel=true --prop glNoSync=true --prop glNamePool=4096 --prop animalLosWalk=true \
  --prop animalPreload=true --prop combinerPool=true --prop pacingQueryBuffer=true \
  "${gcv[@]}" \
  "${logv[@]}" \
  --prop gcSlack=true --prop gcSlackEarlyPct=40 --prop frameQueue=true \
  --prop hordeBatchVao=format --prop atlasBatch=true --prop hordeBatchOrder=window --prop hordeBatchUnits=16 \
  "$@" 2>&1 | grep -E '^job|order|rror|refus' || true
