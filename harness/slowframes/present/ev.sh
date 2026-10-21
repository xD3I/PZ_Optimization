#!/usr/bin/env bash
# ev.sh <label>: goal numbers, settle frames, counters, GC for the newest run dir with that label
S=/tmp/claude-1000/-home-diegov-Documents-ZedProjects-PZ-Optimization/d58632ae-39ff-4522-b9f8-e961b2c88289/scratchpad
R=$(ls -d /home/diegov/pzopt-wt/f9-present/harness/runs/$1-2026* /home/diegov/Documents/ZedProjects/PZ_Optimization/harness/runs/$1-2026* 2>/dev/null | tail -1); echo "$R"
[[ -f $R/jvm.log ]] || { J=$(ls -S /home/diegov/.cache/pzopt-bench/gc-jvm-$1-*.log 2>/dev/null | head -1); [[ -n $J ]] && cp $J $R/jvm.log; }
python3 /home/diegov/pzopt-wt/f8-tail1/harness/slowframes/goal.py "$R"
$S/integ3/check.sh "$R"
echo "stream task failures: $(grep -c 'task failed' $R/console.txt)  settle notes: $(grep -c 'waited for its evaluation task' $R/console.txt)  frame queue: $(grep -o 'frame queue:.*' $R/console.txt | tail -1 | cut -c1-160)"
grep -o 'horde batch: mode=[a-z]* frames=[0-9]* chars=[0-9]*' $R/console.txt | tail -1
grep -o 'zombie lod:.*' $R/console.txt | tail -1 | cut -c1-200
grep -o 'gc_slack=.*' $R/pzopt-bench.out | cut -c1-230
python3 /home/diegov/pzopt-wt/f8-pace2/harness/slowframes/gc/gcframes.py "$R" 2>&1 | tail -6
