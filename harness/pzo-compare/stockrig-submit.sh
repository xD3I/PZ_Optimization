#!/bin/bash
# stockrig-submit.sh <row> <bench> [label-suffix]   (2026-10-24, PZO comparison phase 2): one queue cmd job of stockrig-job.sh
# from THIS worktree's queue.sh (the job runs in this tree). Labels: sr-<bench>-<row>[<suffix>].
set -u
row=$1 bench=$2 sfx=${3:-}
cd "$(dirname "$0")/../.."
label="sr-$bench-$row$sfx"
case $bench in louisville) size=200 ;; *) size=240 ;; esac
harness/queue.sh submit cmd --name pzo-cmp-stockrig --label "$label" --size "$size" \
  --intent "PZO comparison phase 2 (same-window table: stock ZGC / stock G1 8 GB / PZO V1.1.2.2 full / ours on louisville, drive120, storm120); this job: $row on $bench. Truly stock game dir for the non-ours rows, set up and restored + checked inside the job" \
  --progress "${STOCKRIG_PROGRESS:-12-run table}" -- harness/pzo-compare/stockrig-job.sh "$row" "$bench" "$label" 2>&1 | head -1
