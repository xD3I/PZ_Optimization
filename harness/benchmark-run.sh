#!/usr/bin/env bash
# Runs the game's built-in benchmark unattended (pzopt.Benchmark, technique 25: benchmarkAuto) on the installed build
# and collects its results into harness/runs/bench-<label>-<ts>/. Submit as a queue cmd job:
#   harness/queue.sh submit cmd --install opt --label bench-x ... -- harness/benchmark-run.sh <spec> <label> [prop=value ...]
# spec = all | scenario[,scenario...][:passes[:ab]]   e.g. spin:1, louisville,horde:3, all:2:ab
# Extra prop=value pairs go into the game dir's pzopt.properties for every process of the chain (e.g. devX=true).
# BENCH_USER_OPTIONS=1 keeps the player's Zomboid/pzopt/options.ini (the desktop's tab file turns HDR, DLSS, AO on);
# by default the chain reads an empty options file, so the numbers are the build's defaults.
# The chain: launch -> main menu plans the run and restarts -> one process per pass -> the last one writes the report
# and quits. Waits for that (plan state=done and no game process), max BENCH_TIMEOUT s (default 3600).
# UI checks (desktop captures with spectacle, then the game is closed): a spec with the option "report"
# (e.g. spin:1:standard:report) ends on the in-game report screen as a player's run does -> report.png; spec ui:panel or
# ui:history opens that screen at the main menu (devBenchmarkUi) without running anything -> panel.png / history.png;
# ui:compare opens the newest A/B report with another run's share code applied (the "Compare a code" view) -> compare.png;
# ui:runs opens Past results and compares its two newest runs (scores, settings, game settings, mods, PC) -> runs.png;
# ui:read tours every benchmark screen (harness/benchmark-readability.py reads benchread.txt + shots/, html/ every report).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
mac=0
[[ "$(uname)" == Darwin ]] && mac=1   # the Mac: the .app bundle's JRE started directly, as harness/run-mac.sh does
if (( mac )); then
  PZ_APP="${PZ_APP:-$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app}"
  ZOMBOID="${ZOMBOID:-$HOME/Zomboid}"
  GAME_PAT='[z]ombie.gameStates.MainScreenState'
else
  source "$REPO/scripts/pz-env.sh"
  GAME_PAT='[P]rojectZomboid64'
fi
spec="${1:?spec}"
label="${2:?label}"
shift 2
ZD="$ZOMBOID"
PLAN="$ZD/pzopt/benchmark/plan.properties"
if (( mac )); then
  LAUNCHER=""   # no launcher JSON: the JVM options come from Info.plist and go on the command line
  PROPS="$PZ_APP/Contents/Java/pzopt.properties"
else
  LAUNCHER="$PZ_DIR_JVM/ProjectZomboid64.json"
  [[ -f "$LAUNCHER" ]] || LAUNCHER="$PZ_DIR/ProjectZomboid64.json"
  PROPS="$PZ_DIR_JVM/pzopt.properties"
fi
ts=$(date +%Y%m%d-%H%M%S)
out="$REPO/harness/runs/bench-${label#bench-}-$ts"   # a queue label bench-x stays bench-x (the queue collects runs/<label>-*)
mkdir -p "$out"
touch "$out/.started"   # for find -newer (BSD find has no -newermt @epoch)
empty="$out/empty-options.ini"
: > "$empty"

if pgrep -f "$GAME_PAT" >/dev/null; then echo "a game is running" >&2; exit 75; fi
[[ -n "$LAUNCHER" ]] && cp "$LAUNCHER" "$out/launcher.json.orig"
had_props=0
if [[ -f "$PROPS" ]]; then cp "$PROPS" "$out/pzopt.properties.orig"; had_props=1; fi
cp "$ZD/latestSave.ini" "$out/latestSave.ini.orig" 2>/dev/null || true
restore_from_plan() {
  [[ -f "$PLAN" ]] || return 0
  # a plan the game has not cleaned up holds the player's options.ini (the game puts it back at its next launch, which
  # deleting the plan below would skip): restore it here first (2026-10-18: v-sync was left off this way), and before the
  # BENCH_SCREEN / BENCH_PAD edits, which this restore wiped (a pad check stuck at the menu, a 720p tour run at 5K)
  python3 - "$PLAN" "$ZD/options.ini" <<'PY' || true
import sys
plan, target = sys.argv[1:]
def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]; i += 2
            if n == "u": out.append(chr(int(s[i:i + 4], 16))); i += 4
            else: out.append({"n": "\n", "t": "\t", "r": "\r", "f": "\f"}.get(n, n))
        else:
            out.append(c); i += 1
    return "".join(out)
props = {}
for line in open(plan, encoding="latin-1").read().split("\n"):
    if not line or line[0] in "#!":
        continue
    i = 0
    while i < len(line) and not (line[i] in "=:" and (i == 0 or line[i - 1] != "\\")):
        i += 1
    props[unescape(line[:i])] = unescape(line[i + 1:])
if props.get("cleaned") != "1" and "game_options" in props:
    cur = open(target, encoding="utf-8").read() if __import__("os").path.isfile(target) else None
    if cur != props["game_options"]:
        open(target, "w", encoding="utf-8").write(props["game_options"])
        print("options.ini restored from the unfinished plan of run " + props.get("run", "?"))
PY
}
restore_from_plan
# BENCH_SCREEN=WxH: the game in a window of that size (the readability checks of small screens); BENCH_PAD=1: the virtual
# Xbox 360 pad of harness/pad.py as an active controller (benchmark-pad-check.sh); options.ini put back after either
# BENCH_FONT=N: the game's UI font size option (fontSize, 1-4; 3 = the large font of the desktop)
if [[ -n "${BENCH_SCREEN:-}" || -n "${BENCH_PAD:-}" || -n "${BENCH_FONT:-}" ]]; then cp "$ZD/options.ini" "$out/options.ini.orig"; fi
if [[ -n "${BENCH_PAD:-}" ]]; then
  [[ -z "$(tail -c1 "$ZD/options.ini")" ]] || echo >> "$ZD/options.ini"   # the game leaves no newline after the last option
  grep -q "^controller=030000005e0400008e02000010010000" "$ZD/options.ini" || echo "controller=030000005e0400008e02000010010000" >> "$ZD/options.ini"
fi
if [[ -n "${BENCH_SCREEN:-}" ]]; then
  sw="${BENCH_SCREEN%x*}"; sh="${BENCH_SCREEN#*x}"
  sed -i.bak -e "s/^width=.*/width=$sw/" -e "s/^height=.*/height=$sh/" -e "s/^fullScreen=.*/fullScreen=false/" -e "s/^borderless=.*/borderless=false/" "$ZD/options.ini"
  rm -f "$ZD/options.ini.bak"
  echo "window $BENCH_SCREEN (options.ini restored after)"
fi
if [[ -n "${BENCH_FONT:-}" ]]; then
  sed -i.bak -e "s/^fontSize=.*/fontSize=$BENCH_FONT/" "$ZD/options.ini"
  rm -f "$ZD/options.ini.bak"
  echo "fontSize=$BENCH_FONT (options.ini restored after)"
fi
restore() {
  set +e
  [[ -n "$LAUNCHER" ]] && cp "$out/launcher.json.orig" "$LAUNCHER"
  if (( had_props )); then cp "$out/pzopt.properties.orig" "$PROPS"; else rm -f "$PROPS"; fi
  [[ -f "$out/latestSave.ini.orig" ]] && cp "$out/latestSave.ini.orig" "$ZD/latestSave.ini"
  [[ -f "$out/options.ini.orig" ]] && cp "$out/options.ini.orig" "$ZD/options.ini"
}
trap restore EXIT

[[ -n "$LAUNCHER" ]] && python3 - "$LAUNCHER" "$empty" "${BENCH_USER_OPTIONS:-0}" <<'PY'
import json, sys
path, empty, keep = sys.argv[1:]
j = json.load(open(path))
j["vmArgs"] = ["-Dzomboid.steam=0" if a == "-Dzomboid.steam=1" else a for a in j["vmArgs"]]
if keep != "1":
    j["vmArgs"] = [a for a in j["vmArgs"] if not a.startswith("-Dpzopt.userOptionsFile=")] + ["-Dpzopt.userOptionsFile=" + empty]
json.dump(j, open(path, "w"), indent="\t")
PY
ui=""
case "$spec" in ui:*) ui="${spec#ui:}" ;; esac
shot() {  # desktop capture of the game's screen
  if (( mac )); then screencapture -x "$out/$1" >/dev/null 2>&1 || true; else spectacle -b -n -f -o "$out/$1" >/dev/null 2>&1 || true; fi
  echo "screenshot $1: $(wc -c < "$out/$1" 2>/dev/null || echo missing) bytes"
}
{
  if [[ -n "$ui" ]]; then echo "devBenchmarkUi=$ui"; else echo "benchmarkAuto=$spec"; fi
  echo "hdrAuto=false"
  echo "consoleLog=all"
  for kv in "$@"; do echo "$kv"; done
} > "$PROPS"
cp "$PROPS" "$out/pzopt.properties"
rm -f "$PLAN"   # a fresh plan: an old "done" plan would be fine, a stale running one would be resumed
echo "spec=$spec label=$label" > "$out/bench.opts"
# the restart rig must leave the game's options.ini as it was
[[ "$ui" == restart ]] && cksum < "$ZD/options.ini" > "$out/options.ini.cksum"
start=$(date +%s)
caff=""
if (( mac )); then
  # the bundle's JRE with Info.plist's JVMOptions (Steam off), loose classes ahead of the jar: what run-mac.sh starts; the
  # benchmark's restarts repeat this command line (pzopt.Restart), so the options file pin rides along
  jvm=$(ls -d "$PZ_APP"/Contents/PlugIns/jre-*/Contents/Home/bin/java | head -1)
  opts=()
  while IFS= read -r a; do [[ "$a" == "-Dzomboid.steam=1" ]] && a="-Dzomboid.steam=0"; opts+=("$a"); done \
    < <(plutil -extract JVMOptions json -o - "$PZ_APP/Contents/Info.plist" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)))')
  [[ "${BENCH_USER_OPTIONS:-0}" == 1 ]] || opts+=("-Dpzopt.userOptionsFile=$empty")
  ( cd "$PZ_APP/Contents/Java" && exec nohup "$jvm" "${opts[@]}" -Djava.library.path=. -cp ".:projectzomboid.jar" zombie.gameStates.MainScreenState </dev/null >"$out/launcher-stdout.txt" 2>&1 ) &
  caffeinate -dis & caff=$!   # the display stays on for the whole chain
else
  ( cd "$PZ_DIR/.." && export LC_NUMERIC=C && exec setsid ./projectzomboid.sh </dev/null >"$out/launcher-stdout.txt" 2>&1 ) &
fi
timeout="${BENCH_TIMEOUT:-3600}"
idle=0
procs=0
last_pid=""
while true; do
  sleep 2
  now=$(date +%s)
  pid=$(pgrep -f "$GAME_PAT" | head -1 || true)
  if [[ -n "$pid" && "$pid" != "$last_pid" ]]; then
    procs=$((procs + 1)); last_pid="$pid"
    echo "$(date +%T) game process $procs: pid $pid ($(grep -E '^(state|step)=' "$PLAN" 2>/dev/null | tr '\n' ' '))"
  fi
  state=$(sed -n 's/^state=//p' "$PLAN" 2>/dev/null || true)
  if [[ -z "$pid" ]]; then idle=$((idle + 2)); else idle=0; fi
  done_pat="benchmark: \(report of run .* shown\|dev: setup panel shown\|dev: past results shown\|dev: report compare shown\|runs compared\|dev: no two runs\)"
  [[ "$ui" == read ]] && done_pat="benchmark: read tour done"   # the readability tour: every screen, one after the other
  [[ "$ui" == pad ]] && done_pat="benchmark: pad tour done"     # the controller check: benchmark-pad-check.sh drives the pad
  [[ "$ui" == restart ]] && done_pat="benchmark: restart tour done" # the restart rig: the restarted process checks the setting
  if [[ -n "$pid" ]] && grep -q "$done_pat" "$ZD/console.txt" 2>/dev/null; then
    # the game's own screenshot (Lua call("shot"), ~2 s after the dialog): the desktop capture below cannot see the game
    # on every machine (the flip's spectacle shows the wallpaper)
    for _ in $(seq 1 12); do grep -q "benchmark: screenshot pzopt-bench-ui-" "$ZD/console.txt" 2>/dev/null && break; sleep 1; done
    sleep 1
    [[ "$ui" == read ]] && sleep 3   # the last stop's screenshot is written by the render thread a frame later
    case "$ui" in read|pad) ;; panel) shot panel.png ;; history) shot history.png ;; compare) shot compare.png ;; runs) shot runs.png ;; *) shot report.png ;; esac
    kill "$pid" 2>/dev/null || true
    sleep 6
    pkill -f "$GAME_PAT" 2>/dev/null || true
    break
  fi
  if [[ ( "$state" == done || "$state" == aborted ) && $idle -ge 6 ]]; then break; fi
  if [[ -z "$state" && $idle -ge 60 ]]; then echo "no plan and no game for 60 s" >&2; break; fi
  if [[ "$state" == running && $idle -ge 90 ]]; then echo "the chain stopped mid-run (no game for 90 s)" >&2; break; fi
  if [[ -n "$ui" && ( "$state" == running || "$state" == report ) ]]; then
    # a screen check never starts a run: a stray press on Start benchmark / Run again did (2026-10-18, a 15-pass run in a
    # controller check). Stop it, put the player's options back from the plan and drop it (the next launch would resume it)
    echo "a $ui check started a benchmark run (plan state=$state): stopping it" >&2
    pkill -f "$GAME_PAT" 2>/dev/null || true
    sleep 6
    pkill -9 -f "$GAME_PAT" 2>/dev/null || true
    restore_from_plan
    cp "$PLAN" "$out/plan-stray.properties" 2>/dev/null || true
    rm -f "$PLAN"
    break
  fi
  if (( now - start > timeout )); then
    echo "timeout after $timeout s; stopping the game" >&2
    pkill -f "$GAME_PAT" || true
    break
  fi
done
[[ -n "$caff" ]] && kill "$caff" 2>/dev/null
echo "chain ended after $(( $(date +%s) - start )) s, $procs processes"
cp "$PLAN" "$out/plan.properties" 2>/dev/null || true
run=$(sed -n 's/^run=//p' "$PLAN" 2>/dev/null || true)
if [[ -n "$run" && -d "$ZD/pzopt/benchmark/runs/$run" ]]; then
  cp -r "$ZD/pzopt/benchmark/runs/$run" "$out/results"
fi
cp "$ZD/console.txt" "$out/console-last.txt" 2>/dev/null || true
mkdir -p "$out/shots"   # the game's own screenshots of this chain (shots option, screen checks)
find "$ZD/Screenshots" -maxdepth 1 -name 'pzopt-bench-*.png' -newer "$out/.started" -exec cp {} "$out/shots/" \; 2>/dev/null || true
mkdir -p "$out/logs"
find "$ZD/Logs" -maxdepth 1 -type f -newer "$out/.started" -exec cp {} "$out/logs/" \; 2>/dev/null || true
for f in pzopt-bench.out pzopt-overlay.out pzopt-pacing.out pzopt-gamethread.out pzopt-stacks.out; do cp "$ZD/$f" "$out/last-$f" 2>/dev/null || true; done
echo "run_dir=$out"
if [[ "$ui" == pad ]]; then  # the controller check: the focus trace
  grep -a "BENCHPAD\|pad tour done" "$out/console-last.txt" > "$out/benchpad.txt" || true
  rm -f "$ZD/Lua/pzopt-bench-pad-done.txt" "$ZD/joypads/030000005e0400008e02000010010000.config"
  echo "pad: $(grep -c BENCHPAD "$out/benchpad.txt") focus changes"
  exit 0
fi
if [[ "$ui" == restart ]]; then  # the restart rig: both processes' lines, options.ini and the tab file after it
  grep -a "restart check\|restart rig\|restart prompt\|restart for" "$out/console-last.txt" "$out/logs/"*DebugLog* 2>/dev/null | sed 's/.*f:0> //' | sort -u
  if [[ "$(cksum < "$ZD/options.ini")" == "$(cat "$out/options.ini.cksum")" ]]; then echo "options.ini: unchanged"; else echo "options.ini: CHANGED"; fi
  echo "tab file (the run's own options file): $(wc -c < "$empty") bytes: $(tr '\n' ' ' < "$empty")"
  echo "game processes: $procs"
  exit 0
fi
if [[ "$ui" == read ]]; then  # the readability tour: its BENCHREAD lines, screenshots and every report rebuilt by this build
  grep -a "BENCHREAD" "$out/console-last.txt" > "$out/benchread.txt" || true
  mkdir -p "$out/html"
  for r in "$ZD"/pzopt/benchmark/runs/*/report.html; do [[ -f "$r" ]] && cp "$r" "$out/html/$(basename "$(dirname "$r")").html"; done
  echo "read: $(grep -c "BENCHREAD-END" "$out/benchread.txt") stops, $(ls "$out/shots" | wc -l) screenshots, $(ls "$out/html" | wc -l) reports"
  exit 0
fi
if [[ -n "$ui" ]]; then  # a screen check: the screenshot is the result
  [[ -f "$out/$ui.png" || -f "$out/shots/pzopt-bench-ui-$ui.png" ]] || { echo "no screenshot"; exit 1; }
  ls "$out/shots"
  exit 0
fi
if [[ -f "$out/results/report.txt" ]]; then
  grep -E '^(SCORE|S|AB|CMP|REC|WARN)	' "$out/results/report.txt" | cut -c1-400
else
  echo "no report"
  exit 1
fi
