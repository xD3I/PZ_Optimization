#!/usr/bin/env bash
# tail2: copy the worktree's sources into a numbered snapshot and build it through the run queue, so queued runs that
# install another snapshot (or the worktree itself) never find build/classes wiped.
#   harness/slowframes/snap-build.sh <n> "<progress tail>"   -> /home/diegov/pzopt-wt/f8-tail2-b<n>
set -euo pipefail
n=$1; prog=$2
src=/home/diegov/pzopt-wt/f8-tail1
dst=/home/diegov/pzopt-wt/f8-tail2-b$n
mkdir -p "$dst"
rsync -a --delete --exclude .git --exclude build --exclude 'docs/media' --exclude 'docs/workshop' --exclude 'harness/runs' \
  --exclude decompiled "$src/" "$dst/"
ln -sfn /home/diegov/Documents/ZedProjects/PZ_Optimization/decompiled "$dst/decompiled"
cd /home/diegov/Documents/ZedProjects/PZ_Optimization
harness/queue.sh submit cmd --name pz-optimization-f8-tail2 --label "t2-build-b$n" \
  --intent "build snapshot b$n of the tail2 worktree (TextureCombiner pool / tail fixes) for the next runs" \
  --progress "1:1 interleave with reenc-1-* (maintainer's order); tail2: $prog" \
  -- bash -c "cd $dst && scripts/build.sh > $dst/build.log 2>&1; rc=\$?; tail -5 $dst/build.log; exit \$rc" 2>&1 | grep -E '^job|error|refus' || true
