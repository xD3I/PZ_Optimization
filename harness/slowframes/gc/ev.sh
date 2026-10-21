#!/usr/bin/env bash
# ev.sh <label>...: goal numbers + GC pauses in the route + per-GC phases for the newest run dir of each label
S=/tmp/claude-1000/-home-diegov-Documents-ZedProjects-PZ-Optimization/d58632ae-39ff-4522-b9f8-e961b2c88289/scratchpad
for l in "$@"; do
R=$(ls -d /home/diegov/Documents/ZedProjects/PZ_Optimization/harness/runs/$l-2026* | tail -1)
[[ -f $R/jvm.log ]] || cp /home/diegov/.cache/pzopt-bench/gc-jvm-$l.log $R/jvm.log 2>/dev/null
python3 /home/diegov/pzopt-wt/f8-gc/harness/slowframes/goal.py "$R" | tail -1
python3 /home/diegov/pzopt-wt/f8-gc/harness/slowframes/gcwin.py "$R"
python3 $S/gc/gcphase.py "$R" 2>/dev/null
echo "pzopt lines $(grep -c '\[pzopt\]' $R/console.txt)  crashed $(grep -o 'crashed=.' $R/run.opts)  $(grep -o 'bake_counters=.*' $R/pzopt-bench.out | cut -c1-120)"
done
