#!/bin/bash
# stockrig-job.sh <row> <bench> <label>     (2026-10-24, the PZO comparison phase 2; a queue `cmd` job)
#
# One harness run (this worktree's harness/run.sh) of a bench on one row, the game dir set up for the row inside this job
# and put back + checked by the EXIT trap (template: pzo-cmp-ownbench/harness/pzo-compare/ownbench.sh):
#   stock    TRULY stock: our install (every file of pzopt-installed.txt) and PZ_Controller's (pzctl/installed.txt: its
#            Lua, pzctl/ and a zombie/input/AimingReticle.class override) renamed into a stash on the same dataset, the
#            launcher JSON = the stock 42.21 JSON our install saved as ProjectZomboid64.json.pzopt-backup (ZGC, 3 GB);
#            the bench is driven by the stockrig Lua (harness mod) + stockrig-wrap.sh / stockrig-helper.py;
#   stockg1  stock + PZO's memory settings: run.sh --gc g1 --vmarg -Xmx8192m;
#   pzo      stock + PZO V1.1.2.2 as its Linux installer lays it out (stockrig-wrap.sh) + the Workshop mod MPOptimizer 1.4.3;
#   ours     our installed build through the normal harness path (pzopt.Harness drives the bench); only PZ_Controller's
#            files are moved aside, so every row runs without them.
# benches: louisville (--preset louisville, recorded), drive120 (drive-120), storm120 (drive-120 + weather=storm).
# Exit 3 = a restore check failed (restore-check.txt in the run dir says which).
set -u
row=$1 bench=$2 label=$3
WT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$WT/harness/pzo-compare"
REL=/home/diegov/src/pzo-work/rel-1122
PZ_DIR=/games/steamapps/common/ProjectZomboid/projectzomboid
PARENT=$(dirname "$PZ_DIR")
Z="$HOME/Zomboid"
STASH=/games/steamapps/.pzo-cmp-stockrig-stash
GAME_PATTERN='^([^ ]*[/\\])?ProjectZomboid64(\.exe)?( |$)'
STOCK_JSON_SHA=39c87081ca37c7e7f375e2906fff8a1b93494e5f72f84059c188d1cc1a628cfa   # ProjectZomboid64.json.pzopt-backup
case $row in stock|stockg1|pzo|ours) ;; *) echo "row? stock|stockg1|pzo|ours" >&2; exit 2 ;; esac
case $bench in
  louisville) B=(--preset louisville --prop instrument=true --no-dashboard --record); route_s=25; settle_s=20 ;;
  drive120)   B=(--mode drive --flag path=8010,11204.5/9210,11204.5 --flag kmh=120 --flag zoom=max --route-seconds 60 --prop instrument=true --no-dashboard); route_s=60; settle_s=5 ;;
  storm120)   B=(--mode drive --flag path=8010,11204.5/9210,11204.5 --flag kmh=120 --flag zoom=max --route-seconds 60 --prop instrument=true --no-dashboard --flag weather=storm); route_s=60; settle_s=5 ;;
  *) echo "bench? louisville|drive120|storm120" >&2; exit 2 ;;
esac
pgrep -f "$GAME_PATTERN" >/dev/null && { echo "a game is running; refusing" >&2; exit 2; }
[[ -f "$PZ_DIR/pzopt-installed.txt" ]] || { echo "our install is not in place before the job; refusing" >&2; exit 2; }
[[ -e "$STASH" ]] && { echo "$STASH exists (an earlier job died?); refusing" >&2; exit 2; }
[[ -f "$Z/options.ini.pzopt-orig" ]] && { echo "stale options.ini.pzopt-orig; refusing" >&2; exit 2; }
[[ "$(sha256sum < "$PZ_DIR/ProjectZomboid64.json.pzopt-backup" | cut -d' ' -f1)" == "$STOCK_JSON_SHA" ]] || { echo "stock JSON backup changed; refusing" >&2; exit 2; }

rig="$WT/harness/runs/.stockrig-$label-$(date +%Y%m%d-%H%M%S)"   # this job's backups + log; moved into the run dir at the end
BK="$rig/backup"; mkdir -p "$BK"
log() { echo "[$(date +%T)] $*" | tee -a "$rig/rig.log"; }
log "row=$row bench=$bench label=$label"
[[ -f "$PZ_DIR/ProjectZomboid64.json.pzo-bak" ]] && { rm -f "$PZ_DIR/ProjectZomboid64.json.pzo-bak"; log "removed a leftover ProjectZomboid64.json.pzo-bak"; }

snap() {
  ( cd "$PARENT" && find . \( -path ./projectzomboid/media -o -path ./projectzomboid/zombie -o -path ./projectzomboid/jre64 \
       -o -path ./pzsrv-stock \) -prune -o -type f -printf '%p %s\n' | grep -v '/hs_err_pid\|/gc\.log' | sort )
}
snap > "$BK/gamedir.before"
sha256sum "$PZ_DIR/ProjectZomboid64.json" "$PARENT/libpzo_native64.so" "$PZ_DIR/linux64/libpzo_native64.so" "$Z/options.ini" > "$BK/sha.before" 2>/dev/null
ls -A "$Z" "$Z/Lua" "$Z/mods" > "$BK/zomboid.before" 2>/dev/null
cp -p "$PZ_DIR/pzopt-installed.txt" "$BK/pzopt-installed.txt"
cp -p "$PZ_DIR/pzctl/installed.txt" "$BK/pzctl-installed.txt"
( cd "$PZ_DIR" && sha256sum $( (cat "$BK/pzctl-installed.txt"; echo pzctl/installed.txt) | sort -u) ) > "$BK/pzctl.sha"
(cd "$WT" && scripts/pzopt.sh status 2>&1 | sed -n '1,5p') > "$BK/status.before"

backup() { [[ -e "$1" ]] && cp -a "$1" "$BK/$(echo "$1" | tr / %)"; }
restore_file() { local k="$BK/$(echo "$1" | tr / %)"; if [[ -e "$k" ]]; then cp -a "$k" "$1"; else rm -f "$1"; fi; }
TOUCHED=("$PZ_DIR/ProjectZomboid64.json" "$Z/debug-options.ini" "$Z/Lua/MPOptim_Settings.ini"
         "$PARENT/libpzo_native64.so" "$PZ_DIR/linux64/libpzo_native64.so")
for d in "$PZ_DIR/Lua" "$PZ_DIR/Zomboid/Lua"; do for f in pzo_status.json pzo_telemetry.json pzo_update.json pzo_engine.log; do TOUCHED+=("$d/$f"); done; done
for f in "${TOUCHED[@]}"; do backup "$f"; done
parent_linux64_existed=0; [[ -d "$PARENT/linux64" ]] && parent_linux64_existed=1

moved=0 run_dir=""
cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  log "cleanup (exit $rc)"
  local gp; gp=$(pgrep -f "$GAME_PATTERN" | head -1)
  if [[ -n "$gp" ]]; then log "game still running (pid $gp): TERM"; kill "$gp" 2>/dev/null; sleep 5; kill -9 "$gp" 2>/dev/null; sleep 1; fi
  pkill -f '[s]tockrig-helper.py' 2>/dev/null
  # PZO's leftovers in ~/Zomboid (evidence kept) and in the game dir
  mkdir -p "$rig/pzo-files"
  for f in "$Z"/Lua/pzo_* "$Z"/Logs/pzo_* "$Z"/pzo_*; do [[ -e "$f" ]] && mv -f "$f" "$rig/pzo-files/" 2>/dev/null; done
  rm -f "$Z"/Lua/pzostock-*.txt
  rm -f "$PZ_DIR/ProjectZomboid64.json.pzo-bak"
  rm -f "$PZ_DIR/PZOptimEngine.jar" "$PARENT/PZOptimEngine.jar" "$PZ_DIR/libpzo_native64.so" "$PZ_DIR/natives/libpzo_native64.so"
  for f in "${TOUCHED[@]}"; do restore_file "$f"; done
  if (( ! parent_linux64_existed )); then rm -f "$PARENT/linux64/libpzo_native64.so"; rmdir "$PARENT/linux64" 2>/dev/null; fi
  [[ -f "$rig/pzopt.properties.during-run" ]] && mv -f "$rig/pzopt.properties.during-run" "$PZ_DIR/pzopt.properties"
  # our install (and PZ_Controller's files) back
  if (( moved )) || [[ -d "$STASH" ]]; then
    python3 - "$STASH" "$PZ_DIR" <<'PY'
import os, sys
stash, game = sys.argv[1], sys.argv[2]
n = 0
for root, dirs, files in os.walk(stash):
    for f in files:
        src = os.path.join(root, f); rel = os.path.relpath(src, stash); dst = os.path.join(game, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True); os.rename(src, dst); n += 1
for root, dirs, files in os.walk(stash, topdown=False):
    os.rmdir(root)
print("restored", n, "files")
PY
  fi
  # ---- checks
  local bad=0 chk="$rig/restore-check.txt"
  : > "$chk"
  ( cd "$PZ_DIR" && awk 'NR>2 && $1 !~ /^#/ {print $2"  "$1}' "$BK/pzopt-installed.txt" | sha256sum -c --quiet ) >> "$chk" 2>&1 \
    && echo "install files: all $(awk 'NR>2' "$BK/pzopt-installed.txt" | wc -l) match pzopt-installed.txt" >> "$chk" || { echo "install files: MISMATCH" >> "$chk"; bad=1; }
  cmp -s "$BK/pzopt-installed.txt" "$PZ_DIR/pzopt-installed.txt" && echo "manifest: identical" >> "$chk" || { echo "manifest: DIFFERS" >> "$chk"; bad=1; }
  ( cd "$PZ_DIR" && sha256sum -c --quiet "$BK/pzctl.sha" ) >> "$chk" 2>&1 && echo "PZ_Controller files: all $(wc -l < "$BK/pzctl.sha") identical" >> "$chk" || { echo "PZ_Controller files: MISMATCH" >> "$chk"; bad=1; }
  sha256sum "$PZ_DIR/ProjectZomboid64.json" "$PARENT/libpzo_native64.so" "$PZ_DIR/linux64/libpzo_native64.so" "$Z/options.ini" > "$BK/sha.after" 2>/dev/null
  if cmp -s "$BK/sha.before" "$BK/sha.after"; then echo "JSON, the two old libpzo leftovers, options.ini: identical" >> "$chk"
  else { echo "JSON / leftovers / options.ini: DIFFER"; diff "$BK/sha.before" "$BK/sha.after"; } >> "$chk"; bad=1; fi
  snap > "$BK/gamedir.after"
  if diff "$BK/gamedir.before" "$BK/gamedir.after" > "$BK/gamedir.diff"; then echo "game dir listing (outside media/zombie/jre64): identical" >> "$chk"
  else { echo "game dir listing: DIFFERS"; cat "$BK/gamedir.diff"; } >> "$chk"; bad=1; fi
  [[ -e "$STASH" ]] && { echo "stash still exists" >> "$chk"; bad=1; }
  ls -A "$Z" "$Z/Lua" "$Z/mods" > "$BK/zomboid.after" 2>/dev/null
  diff "$BK/zomboid.before" "$BK/zomboid.after" > "$BK/zomboid.diff" && echo "~/Zomboid, Lua/, mods/ listings: identical" >> "$chk" \
    || { echo "~/Zomboid listings differ (informational):"; cat "$BK/zomboid.diff"; } >> "$chk"
  (cd "$WT" && scripts/pzopt.sh status 2>&1 | sed -n '1,5p') > "$BK/status.after"
  cmp -s "$BK/status.before" "$BK/status.after" && echo "pzopt.sh status (first 5 lines): identical to the job start" >> "$chk" || { echo "pzopt.sh status: DIFFERS"; diff "$BK/status.before" "$BK/status.after"; } >> "$chk"
  cmp -s "$BK/status.before" "$BK/status.after" || bad=1
  cat "$chk" | tee -a "$rig/rig.log"
  if [[ -n "$run_dir" && -d "$run_dir" ]]; then cp -a "$rig/." "$run_dir/stockrig/" && rm -rf "$rig" && log_dir="$run_dir/stockrig"; fi
  echo "stockrig: run_dir=${run_dir:-none} rig_log=${log_dir:-$rig}"
  if (( bad )); then echo "stockrig: RESTORE CHECK FAILED"; exit 3; fi
  echo "stockrig: restore check ok"
  exit $rc
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ---- the row's game dir
python3 - "$PZ_DIR" "$STASH" "$row" <<'PY' || exit 4
import os, sys
game, stash, row = sys.argv[1], sys.argv[2], sys.argv[3]
rels = []
if row != "ours":
    rels = [l.split()[0] for l in open(os.path.join(game, "pzopt-installed.txt")) if l.strip() and not l.startswith("#")]
    rels.append("pzopt-installed.txt")
rels += [l.strip() for l in open(os.path.join(game, "pzctl/installed.txt")) if l.strip()]
if "pzctl/installed.txt" not in rels:
    rels.append("pzctl/installed.txt")
for rel in rels:
    src = os.path.join(game, rel); dst = os.path.join(stash, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True); os.rename(src, dst)
print("moved", len(rels), "files aside")
PY
moved=1
log "moved aside to $STASH: $([[ $row == ours ]] && echo "PZ_Controller's files" || echo "our install + PZ_Controller's files")"
if [[ $row != ours ]]; then
  for m in pzopt-installed.txt pzopt/BootRepair.class Uninstall-PZ-Optimization.cmd zombie/input/AimingReticle.class; do [[ -e "$PZ_DIR/$m" ]] && { log "$m still present"; exit 5; }; done
  [[ -n "$(find "$PZ_DIR/pzopt" "$PZ_DIR/zombie" "$PZ_DIR/org" "$PZ_DIR/se" "$PZ_DIR/fmod" "$PZ_DIR/pzctl" -name '*.class' 2>/dev/null | head -1)" ]] && { log "loose classes still present"; exit 5; }
  cp "$PZ_DIR/ProjectZomboid64.json.pzopt-backup" "$PZ_DIR/ProjectZomboid64.json"
  log "launcher JSON = the stock 42.21 JSON (sha $STOCK_JSON_SHA)"
fi

A=(--label "$label" --launcher direct --retries 1 "${B[@]}" --option vsync=false --option textureCompression=false --option uiRenderOffscreen=true)
case $row in
  stock|stockg1|pzo)
    A+=(--flag stockrig=1 --wrap "$HERE/stockrig-wrap.sh" --option frameRate=244 --option uncappedFPS=false) ;;
  ours)
    A+=(--prop uncappedFps=true --prop vrr=off --option uncappedFPS=false --vmarg "-Dpzopt.userOptionsFile=$HERE/empty-options.ini"
        --prop hdr=false --prop upscaler=off --prop vrrCap=false) ;;
esac
[[ $row == stockg1 ]] && A+=(--gc g1 --vmarg -Xmx8192m)
if [[ $row == pzo ]]; then
  A+=(--mod MPOptimizer)
  printf '# Project Zomboid Optimiser Config\nFirstLaunchPromptShown=true\nLastSeenVersion=1.4.3\n' > "$Z/Lua/MPOptim_Settings.ini"
fi
export STOCKRIG_ROW=$row STOCKRIG_RIG=$rig STOCKRIG_ROUTE_S=$route_s STOCKRIG_SETTLE_S=$settle_s STOCKRIG_REL=$REL
log "run.sh ${A[*]}"
"$WT/harness/run.sh" "${A[@]}" 2>&1 | tee "$rig/run-sh.log"
rs=${PIPESTATUS[0]}
run_dir=$(sed -n 's/.*output -> \(.*\)$/\1/p' "$rig/run-sh.log" | tail -1)
log "run.sh exit $rs, run dir $run_dir"
[[ -n "$run_dir" && -d "$run_dir" ]] && cp -p "$Z/projectzomboid.sh.log" "$run_dir/" 2>/dev/null
exit "$rs"
