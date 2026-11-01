#!/bin/bash
# run.sh --wrap for the stock / stockg1 / pzo rows of the PZO comparison (2026-10-24; set up by stockrig-job.sh, which
# exports STOCKRIG_ROW, STOCKRIG_ROUTE_S, STOCKRIG_SETTLE_S, STOCKRIG_REL and the rig dir STOCKRIG_RIG).
# run.sh has already written the launcher JSON for this run (direct launch: -Dzomboid.steam=0, the -Xlog:gc line, --gc /
# --vmarg); this wrapper, per launch:
#   - pzo: lays PZO V1.1.2.2 out as its Linux installer does (pzo_optimizer.sh: the engine jar into the game dir and the
#     parent, libpzo_native64.so into the game dir, linux64/, natives/ and the parent) and runs the installer's own
#     JSON-editing Python (extracted from the release's pzo_optimizer.sh: 8 GB on this 30 GB machine, G1 + its JIT
#     flags, mainClass PZOEntrypoint, -agentlib:pzo_native64) on run.sh's JSON, then -Dzomboid.steam=1 -> 0 (direct
#     launch, as run.sh does for every run);
#   - moves run.sh's pzopt.properties out of the game dir while the game runs (inert without our classes, but it is
#     one of our files) and puts it back after the game exits (run.sh copies it into the run dir);
#   - starts stockrig-helper.py (click-to-start, the schedule files, the watchdog);
#   - runs the game, then removes PZO's files again (stockrig-job.sh's trap restores and checks everything once more).
set -u
row=${STOCKRIG_ROW:?} rig=${STOCKRIG_RIG:?}
out=$(dirname "$(readlink /proc/$$/fd/1)")     # run.sh sends the launcher's stdout to <run dir>/launcher-stdout.txt
PZ_DIR=/games/steamapps/common/ProjectZomboid/projectzomboid
PARENT=$(dirname "$PZ_DIR")
Z="$HOME/Zomboid"
REL=${STOCKRIG_REL:-/home/diegov/src/pzo-work/rel-1122}
say() { echo "stockrig-wrap: $*" | tee -a "$out/stockrig-wrap.log"; }
say "row=$row out=$out game=$*"
rm -f "$Z"/Lua/pzostock-*.txt
if [[ -f "$PZ_DIR/pzopt.properties" ]]; then mv -f "$PZ_DIR/pzopt.properties" "$rig/pzopt.properties.during-run"; say "pzopt.properties out of the game dir for the run"; fi

if [[ $row == pzo ]]; then
  cp -f "$REL/PZOptimEngine.jar" "$PZ_DIR/PZOptimEngine.jar"
  cp -f "$REL/PZOptimEngine.jar" "$PARENT/PZOptimEngine.jar"
  cp -f "$REL/libpzo_native64.so" "$PZ_DIR/libpzo_native64.so"
  mkdir -p "$PZ_DIR/linux64" "$PZ_DIR/natives" "$PARENT/linux64"
  cp -f "$REL/libpzo_native64.so" "$PZ_DIR/linux64/libpzo_native64.so"
  cp -f "$REL/libpzo_native64.so" "$PZ_DIR/natives/libpzo_native64.so"
  cp -f "$REL/libpzo_native64.so" "$PARENT/libpzo_native64.so"
  cp -f "$REL/libpzo_native64.so" "$PARENT/linux64/libpzo_native64.so"
  awk "/^    python3 - << 'EOF' \"\\\$JSON_FILE\"/{f=1;next} f&&/^EOF\$/{exit} f" "$REL/pzo_optimizer.sh" > "$out/pzo-installer-json.py"
  [[ -s "$out/pzo-installer-json.py" ]] || { say "could not extract the installer's JSON code"; exit 1; }
  python3 "$out/pzo-installer-json.py" "$PZ_DIR/ProjectZomboid64.json" 8 8192 0 0 | tee -a "$out/stockrig-wrap.log"
  python3 - "$PZ_DIR/ProjectZomboid64.json" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p))
d["vmArgs"] = ["-Dzomboid.steam=0" if a == "-Dzomboid.steam=1" else a for a in d["vmArgs"]]
json.dump(d, open(p, "w"), indent=4)
PY
  cp "$PZ_DIR/ProjectZomboid64.json" "$out/ProjectZomboid64.pzo-launch.json"
  say "PZO laid out; launcher JSON: $(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print(d["mainClass"], d["classpath"], " ".join(d["vmArgs"]))' "$PZ_DIR/ProjectZomboid64.json")"
fi
for m in pzopt-installed.txt pzopt/BootRepair.class Uninstall-PZ-Optimization.cmd; do
  [[ -e "$PZ_DIR/$m" ]] && { say "our marker $m is in the game dir: refusing"; exit 1; }
done
cp "$PZ_DIR/ProjectZomboid64.json" "$out/ProjectZomboid64.launch.json"

python3 "$(dirname "$0")/stockrig-helper.py" "$out" "${STOCKRIG_ROUTE_S:-60}" "${STOCKRIG_SETTLE_S:-5}" &
helper=$!
"$@"
rc=$?
say "game launcher exited ($rc)"
sleep 1
kill "$helper" 2>/dev/null; wait "$helper" 2>/dev/null
[[ -f "$rig/pzopt.properties.during-run" ]] && mv -f "$rig/pzopt.properties.during-run" "$PZ_DIR/pzopt.properties"
if [[ $row == pzo ]]; then
  mkdir -p "$out/pzo-files"
  for f in "$Z"/Lua/pzo_* "$Z"/Logs/pzo_* "$Z"/pzo_*; do [[ -e "$f" ]] && cp -p "$f" "$out/pzo-files/"; done
  for d in Lua Zomboid/Lua; do for f in "$PZ_DIR/$d"/pzo_*; do [[ -e "$f" ]] && cp -p "$f" "$out/pzo-files/gamedir-${d//\//_}-$(basename "$f")"; done; done
  cp -p "$Z/projectzomboid.sh.log" "$out/pzo-files/" 2>/dev/null
  rm -f "$PZ_DIR/PZOptimEngine.jar" "$PARENT/PZOptimEngine.jar" "$PZ_DIR/libpzo_native64.so" "$PZ_DIR/natives/libpzo_native64.so"
  say "PZO's engine files removed (the job's trap restores the rest)"
fi
exit $rc
