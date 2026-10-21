#!/usr/bin/env bash
# tail2: block until every listed queue job has ended; print each one's final status line
cd /home/diegov/Documents/ZedProjects/PZ_Optimization
for id in "$@"; do
  while true; do
    s=$(harness/queue.sh status "$id" 2>/dev/null | head -1)
    case "$s" in *done*|*failed*|*cancel*) echo "$s"; break;; esac
    sleep 20
  done
done
