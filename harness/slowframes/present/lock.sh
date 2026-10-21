#!/usr/bin/env bash
# lock.sh <label> [extra]: round 8 lock set = LOD 128 + first sight + pace2 queue set + fixed D (frameQueueMinPct=260)
# + burstGovernor + lookupMemo + bakeBudgetGpu + lightingStrongFrameMs=9 + -XX:-UseTypeSpeculation (f8-resid's cfb set)
S=/tmp/claude-1000/-home-diegov-Documents-ZedProjects-PZ-Optimization/d58632ae-39ff-4522-b9f8-e961b2c88289/scratchpad
l=$1; shift
NOLOG=${NOLOG:-1} $S/f9p/submit.sh "$l" louisville "${INTENT:-f9-present: Louisville cap 120 on the round-8 lock set}" \
  --prop frameCapFps=120 --prop zombieLodDynamic=true --prop zombieLodFixed3d=128 --prop zombieLodHysteresis=16 --prop zombieLodFadeMs=300 --prop zombieFirstSightSprite=true \
  --prop frameQueueGpu=true --prop frameQueueSlewUs=500 --prop frameQueueDecayUsPerS=1000 --prop frameQueueMaxPct=260 --prop frameQueueMinPct=260 --prop gcSlackQueue=slack \
  --prop burstGovernor=true --prop lookupMemo=true --prop bakeBudgetGpu=true --prop lightingStrongFrameMs=9 --vmarg -XX:-UseTypeSpeculation "$@"
