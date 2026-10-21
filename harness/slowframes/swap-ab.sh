#!/usr/bin/env bash
# tail2: the paired runs for the swap / THP A/B. Run once BEFORE and once AFTER the root change
# (vm.swappiness 150 -> 10 and/or THP defrag defer+madvise -> madvise), with a tag: swap-ab.sh <tag>  (e.g. before / after)
# Each call: one uncapped profiled run + one cap-120 run of the tail stack, build b6. Compare with goal.py / slowframes.py
# (the "major faults" lines) and memwatch.sh's VmSwap.
set -euo pipefail
tag=$1
export INSTALL=/home/diegov/pzopt-wt/f8-tail2-b6
d=$(dirname "$0")
T=(--prop glNoSync=true --prop glNamePool=4096 --prop pacingQueryBuffer=true --prop combinerPool=true --prop animalLosWalk=true --prop animalPreload=true --vmarg -Xms4096m --vmarg -XX:MaxGCPauseMillis=10)
bash "$d/submit2.sh" "t2-swap-$tag-p" p "swap/THP A/B ($tag): major faults, GC pause length and render-thread stalls with the machine's VM settings $tag the change" "swap A/B $tag 1/2" "${T[@]}"
bash "$d/submit2.sh" "t2-swap-$tag-cap" cap "swap/THP A/B ($tag): cap-120 lock metrics $tag the VM change" "swap A/B $tag 2/2" "${T[@]}"
