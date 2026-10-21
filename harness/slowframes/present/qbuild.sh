#!/usr/bin/env bash
# qbuild.sh <label> "<intent>": build + test the f9-present tree through the queue
export PZQ_SESSION=pz-optimization-f9-present
cd /home/diegov/pzopt-wt/f9-present
harness/queue.sh submit cmd --name pz-optimization-f9-present --intent "$2" --progress "${PROGRESS:-f9-present: building}" --label "$1" -- \
  bash -c 'cd /home/diegov/pzopt-wt/f9-present && scripts/build.sh > /tmp/claude-1000/-home-diegov-Documents-ZedProjects-PZ-Optimization/d58632ae-39ff-4522-b9f8-e961b2c88289/scratchpad/f9p/'"$1"'.log 2>&1; e=$?; tail -5 /tmp/claude-1000/-home-diegov-Documents-ZedProjects-PZ-Optimization/d58632ae-39ff-4522-b9f8-e961b2c88289/scratchpad/f9p/'"$1"'.log; exit $e'
