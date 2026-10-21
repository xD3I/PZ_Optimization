#!/bin/bash
# Controller check of every built-in benchmark screen with a virtual Xbox 360 pad (harness/pad.py, uinput; 2026-10-18).
# Run it through the queue (desktop, the pad is a uinput device):
#   harness/queue.sh submit cmd --install opt --label bench-pad -- harness/benchmark-pad-check.sh
# The game starts through benchmark-run.sh ui:pad (BENCH_PAD=1: the pad's GUID as an active controller in options.ini,
# put back after; devBenchmarkUi=pad: every change of what the pad drives is a BENCHPAD line in console.txt). The script
# reads that trace after each press and walks the PZ Optimization screen (pzopt_mainscreen_menu.lua: the sidebar tree,
# breadcrumbs, list | detail; the trace's row is the sidebar row, 100 + the list row or 200 + the detail button): home
# list, OPEN, B closes onto the item; the search (L3: the on-screen keyboard, "fog" typed with the D-pad, RT accepts; a
# result's page and back), a preset's confirm (No), Fix a problem and a problem's settings; the sidebar (a group opens its
# categories, a category its subcategories), a category's page (its own switch first), a subcategory's settings, a
# setting's chooser, DEFAULT and the before / after clips on and off, B up one level at a time; Enhancements' page; the benchmark's pages, UPDATE, MOD COMPATIBILITY CHECK in the sidebar (each shows
# its page without taking the pad), the compatibility and update pages with the pad (B closes them; never A in the update
# page: A there is "Update now"); the benchmark's setup page and from it every benchmark screen as before (setup rows and
# buttons, Past results, the run comparison, the report and its code box), back up to home and the column. A is pressed
# only on a control named here (press_a_on): never on Start benchmark, Run again or Open full report; L3 + R3 together on
# home must toggle the overlay once (measuring switched on if off) with neither L3 nor R3 alone firing; the walk stops on a
# wrong focus or when a benchmark plan started. Steps go to <run>/pad-steps.txt; harness/benchmark-pad-judge.py <run>
# turns them into the Jev verdict. On a laptop: queue `run` job `-- --label bench-pad --benchmark pad` (run.sh hands it here).
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
ZD="$ZOMBOID"
FIFO=/tmp/pzopt-bench-pad.fifo
STEPS=$(mktemp /tmp/pzopt-bench-pad-steps.XXXXXX)
rm -f "$FIFO" "$ZD/Lua/pzopt-bench-pad-done.txt"; mkfifo "$FIFO"
python3 harness/pad.py serve "$FIFO" > "$STEPS.pad" 2>&1 &
padpid=$!
sleep 1.5; grep -q "pad ready" "$STEPS.pad" || { echo "pad failed: $(cat "$STEPS.pad")"; exit 1; }
# BENCH_PROPS (run.sh --prop): extra keys for the game, e.g. devUpdateOffer=true for the update-offered walk below
# shellcheck disable=SC2086
BENCH_PAD=1 harness/benchmark-run.sh ui:pad pad devBenchmarkUi=pad ${BENCH_PROPS:-} &
bench=$!

last() { grep -a "BENCHPAD" "$ZD/console.txt" 2>/dev/null | tail -1 | sed 's/.*BENCHPAD //'; }
step() { echo "$*" >> "$STEPS"; }
press() {
  echo "$1" > "$FIFO"; sleep "${2:-0.7}"; step "press $1 -> $(last)"
  # a benchmark run never starts in this check: if one did (a plan running), stop the walk (benchmark-run.sh stops the run)
  if grep -qs "^state=\(running\|report\)" "$ZD/pzopt/benchmark/plan.properties"; then
    step "FAIL: a benchmark run started (plan $(grep -s '^state=' "$ZD/pzopt/benchmark/plan.properties")); the walk stops"
    exit 0
  fi
}
el_is() { last | grep -q "el=$1 "; }
focus_is() { last | grep -q "^focus=$1 "; }
# press <btn> until the focused control's title starts with <name> (at most <n> presses); 0 when found
walk() {
  local btn=$1 name=$2 n=${3:-12}
  for _ in $(seq 1 "$n"); do el_is "$name" && return 0; press "$btn"; done
  el_is "$name"
}
expect() { if "$@"; then step "ok: $*"; else step "FAIL: $* (now $(last))"; fi; }
# A only on the control named: when the cursor is elsewhere (a walk that failed) the check stops here instead of pressing
# A blind (2026-10-18: a failed walk left the cursor on Start benchmark and the next blind A started a 15-pass run)
press_a_on() {
  local name=$1 wait=${2:-1.2}
  if el_is "$name"; then press a "$wait"; return 0; fi
  step "FAIL: the cursor is not on $name, A not pressed; the walk stops (now $(last))"
  exit 0
}
finish() {
  touch "$ZD/Lua/pzopt-bench-pad-done.txt"
  wait "$bench"
  echo quit > "$FIFO" 2>/dev/null; sleep 0.5; kill "$padpid" 2>/dev/null; rm -f "$FIFO"
  run=$(ls -td harness/runs/bench-pad-* | head -1)
  cp "$STEPS" "$run/pad-steps.txt"; cp "$STEPS.pad" "$run/pad.log"; rm -f "$STEPS" "$STEPS.pad"
  echo "pad steps: $run/pad-steps.txt ($(grep -c '^ok:' "$run/pad-steps.txt") ok, $(grep -c '^FAIL' "$run/pad-steps.txt") failed)"
  exit 0
}
trap finish EXIT

# the new game's trace: console.txt still holds the last game's lines until the new one rewrites it (a walk once started
# on the old trace and pressed into a loading game)
for _ in $(seq 1 120); do sleep 1; [[ "$ZD/console.txt" -nt "$STEPS" ]] && last | grep -q "^focus=" && break; done
sleep 4
step "start -> $(last)"

el_has() { last | grep -q "el=$1"; }
row_is() { last | grep -q " row=$1 "; }

page_is() { last | grep -q " page=$1 "; }
side_is() { last | grep -Eq " row=[0-9]{1,2} "; }
st() { last | sed 's/ open=.*//'; }
pg() { last | grep -o 'page=[^ ]*' | head -1; }
# a press that should change something; when the trace shows no change, the reason is noted ("unused: ..."): the judge
# counts a press without effect only when it has no note
try() {
  local btn=$1 wait=$2 note=$3 before
  before=$(st); press "$btn" "$wait"
  if [[ "$(st)" == "$before" ]]; then step "unused: $btn on $(pg) - $note"; fi
}
# a confirm (it restarts, installs or opens a window outside the game) answered No
confirm_no() { expect focus_is ISModalDialog; press b 1.0; expect focus_is PzoptMainMenu; }
# the buttons every page shares, the cursor on its list: the left stick (the D-pad's moves), LT / RT (a page), the right
# stick (the detail's text), View (sidebar <-> page), L3 (the search's on-screen keyboard, B closes it), R3 (the help, R3
# again closes it)
sweep() {
  try lsdown 0.8 "the end of the list"
  try lsup 0.8 "the top of the list"
  try rt 0.7 "the list fits on one page"
  try lt 0.7 "the list fits on one page"
  try rsdown 0.9 "the detail's text fits its box"
  try rsup 0.9 "the detail's text fits its box"
  press back 0.6; expect side_is
  press back 0.6; expect row_is "1[0-9][0-9]"
  press l3 1.2; expect focus_is OnScreenKeyboardPanel
  press b 0.8; expect focus_is PzoptMainMenu
  press r3 1.0; expect sh -c "grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q 'page=[^ ]*+help '"
  press r3 0.8; expect sh -c "! grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q '+help '"
}
console_has() { grep -aq "$1" "$ZD/console.txt"; }
# the game's on-screen keyboard: the D-pad to the key at row r, column c (rows: 1 the digits, 2 q..\, 3 Caps a..', 4 Shift
# z../, as the trace's row= / col= count them), wherever the cursor started; then el must be that key
osk_key() {
  local tr=$1 tc=$2 ch=$3 r c
  for _ in $(seq 1 30); do
    read -r r c < <(last | sed -n 's/.* row=\([0-9]*\) col=\([0-9]*\) .*/\1 \2/p')
    [[ -z "$r" ]] && break
    if [[ "$r" == "$tr" && "$c" == "$tc" ]]; then break; fi
    if (( r < tr )); then press down 0.35; elif (( r > tr )); then press up 0.35
    elif (( c < tc )); then press right 0.35; else press left 0.35; fi
  done
  expect el_is "$ch"
}
count_of() { grep -ac "$1" "$ZD/console.txt"; }

# an update offered (--prop devUpdateOffer=true): the item says so and opens the UPDATE page itself; never A on UPDATE NOW
if [[ " ${BENCH_PROPS:-} " == *" devUpdateOffer=true "* ]]; then
  press a 1.2
  expect focus_is MainScreen
  walk down "PZ OPTIMIZATION - UPDATE AVAILABLE" 16; expect el_is "PZ OPTIMIZATION - UPDATE AVAILABLE"
  press_a_on "PZ OPTIMIZATION - UPDATE AVAILABLE" 1.2; expect focus_is PzoptMainMenu
  expect page_is update; expect el_is "State"
  press right 0.6; expect row_is "20[0-9]"
  expect sh -c "! grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q 'el=UPDATE NOW '"   # the cursor lands past it
  press y 1.0; confirm_no   # Y = UPDATE NOW asks first (No)
  press b 0.5; press b 1.0; expect page_is home; expect el_is "Update"
  press b 1.2; expect focus_is MainScreen
  expect el_is "PZ OPTIMIZATION - UPDATE AVAILABLE"
  exit 0
fi

# main menu: activate, walk to the item, open the screen: its home page, the cursor on the list (row 101)
press a 1.2
expect focus_is MainScreen
walk down "PZ OPTIMIZATION" 16; expect el_is "PZ OPTIMIZATION"
press_a_on "PZ OPTIMIZATION"; expect focus_is PzoptMainMenu
expect el_is "Optimizations"; expect page_is home; expect row_is 101
sweep
try x 0.5 "home has no second action"
try y 0.5 "home has no main action (A opens the entry)"
# L3 + R3 together = the performance overlay (pzopt.Overlay's chord, measuring switched on if it was off; benchmark-run.sh
# gives the game an empty tab file, so nothing of the player's is saved): one overlay toggle per chord, and neither L3 alone
# (to the top) nor R3 alone (the help) fires on the screen
# a toggle's own line (shown / hidden / measuring switched on and shown / still off), not the reconfigure lines around it
OVL='\[pzopt\] overlay: \(shown\|hidden\|measuring switched on\|sampling is off\)'
chord_l3r3() {
  local before n
  before=$(st); n=$(count_of "$OVL")
  echo "chord l3 r3" > "$FIFO"; sleep 1.2; step "press l3+r3 -> $(last)"
  step "unused: l3+r3 on $(pg) - the overlay chord: it toggles the overlay (the console line checked next), the screen stays as it was"
  expect sh -c "[ \$(grep -ac '$OVL' '$ZD/console.txt') -eq $((n + 1)) ]"
  expect sh -c "[ \"\$(grep -a BENCHPAD '$ZD/console.txt' | tail -1 | sed 's/.*BENCHPAD //; s/ open=.*//')\" = '$before' ]"
}
press down 0.5; expect el_is "Enhancements"   # off the top, so an L3 alone would show (to the top)
chord_l3r3; expect sh -c "grep -a '$OVL' '$ZD/console.txt' | tail -1 | grep -q 'shown'"
chord_l3r3; expect sh -c "grep -a '$OVL' '$ZD/console.txt' | tail -1 | grep -q 'hidden'"
press up 0.5; expect el_is "Optimizations"
# L3: the search box takes the game's on-screen keyboard; "fog" typed with the D-pad and A, RT (released) accepts: the
# results page, Y = OPEN ITS PAGE (the setting on its own page), B back to the results, B again home (the box emptied)
press l3 1.2; expect focus_is OnScreenKeyboardPanel
osk_key 3 5 f; press a 0.5
osk_key 2 9 o; press a 0.5
osk_key 3 6 g; press a 0.5
expect sh -c "grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q 'text=fog '"
press rt 1.5
expect focus_is PzoptMainMenu; expect page_is search
expect sh -c "grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q ' search=fog '"
expect row_is 101
press y 1.0; expect sh -c "grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q 'page=\(perf\|vis\|tools\)/'"
expect console_has "menu: opened [a-zA-Z]* on "
press b 0.8; expect page_is search
press b 0.8; expect page_is home
expect sh -c "! grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q ' search='"
# the home page's presets (Y applies, after a confirm: No here) and Fix a problem (a problem's page: its settings)
walk down "Recommended (every optimization on)" 14; expect el_is "Recommended (every optimization on)"
press y 1.0; confirm_no
walk down "Fix a problem" 8; expect el_is "Fix a problem"
press_a_on "Fix a problem" 0.8; expect page_is problems
press_a_on "Stutter while driving" 0.8; expect page_is problems/1
expect el_is "Vehicles drawn between physics steps"
press b 0.6; expect page_is problems; expect el_is "Stutter while driving"
press b 0.6; expect page_is home; expect el_is "Fix a problem"
walk up "Optimizations" 24; expect el_is "Optimizations"
press rb 0.8; expect page_is perf   # the sections: RB the next one, LB back
press lb 0.8; expect page_is home
walk up "Optimizations" 6
# the home list in order, its detail's OPEN, B at home closes the menu onto the item
for name in Enhancements Profiler Benchmark Update "Mod compatibility check"; do press down 0.5; expect el_is "$name"; done
walk up "Optimizations" 6; expect el_is "Optimizations"
press right 0.5; expect el_is "OPEN"; expect row_is 201
press left 0.5; expect row_is 101
press b 1.2; expect focus_is MainScreen
expect el_is "PZ OPTIMIZATION"
press_a_on "PZ OPTIMIZATION"; expect focus_is PzoptMainMenu
expect el_is "Optimizations"

# the sidebar: OPTIMIZATIONS opens its categories under it, a category its subcategories
press left 0.6; expect el_is "OPTIMIZATIONS"; expect page_is perf
press down 0.5; expect el_is "Smoothness and pacing"
press down 0.5; expect el_is "Frame pacing"
walk down "Driving" 6; expect el_is "Driving"; expect page_is perf/drive
# a category's page: its own switch first (never A on it here), its subcategories; A opens one: its settings, each editable
# in its detail
press right 0.6; expect el_is "Driving enabled"
press down 0.5; expect el_is "Car smoothness"
press_a_on "Car smoothness" 0.8; expect page_is perf/drive/1
expect row_is 101
sweep
walk up "Vehicles drawn between physics steps" 4
press right 0.6; expect row_is 201
n0=$(count_of "\[pzopt\] menu: [a-zA-Z]* -> ")
# the setting's chooser (vehicleSmooth, a next-launch key): the next choice saves it and opens the restart prompt (LATER under
# the cursor); Y there asks to restart (No); B = Later; X = DEFAULT puts the value back and the prompt's reminder goes
press right 0.8; expect sh -c "grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q 'page=[^ ]*+prompt '"
expect el_is "LATER"
press left 0.5; expect el_is "RESTART NOW"
press right 0.5; expect el_is "LATER"
press y 1.0; confirm_no
press b 0.8; expect sh -c "! grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q '+prompt '"
press x 0.8   # DEFAULT (the detail's X)
expect sh -c "[ \$(grep -ac '\[pzopt\] menu: [a-zA-Z]* -> ' '$ZD/console.txt') -ge $((n0 + 2)) ]"
expect console_has "menu: vehicleSmooth -> (default)"
press down 0.5; expect el_is "DEFAULT"
# the before / after clips (saved at once, the run's own empty options file): on, then off again
press right 0.5; expect el_is "SHOW CLIPS"
press_a_on "SHOW CLIPS" 1.5; expect console_has "menu: before / after clips on"; expect el_is "HIDE CLIPS"
press_a_on "HIDE CLIPS" 1.0; expect console_has "menu: before / after clips off"; expect el_is "SHOW CLIPS"
press b 0.5; expect row_is 101
# B goes up a level each time: the category (on Car smoothness), the group (on Driving), home (on Optimizations)
press b 0.6; expect el_is "Car smoothness"
press b 0.6; expect el_is "Driving"
press b 0.6; expect el_is "Optimizations"
# ENHANCEMENTS: its page (the master switch first: never A on it here), B back
press down 0.5; expect el_is "Enhancements"
press_a_on "Enhancements" 0.6; expect el_is "Enhancements enabled"
press right 0.5; expect el_is "Enhancements enabled"; expect row_is 201   # its tick box in the detail (not pressed)
press left 0.5
press down 0.5; expect el_is "Image quality"
press b 0.6; expect el_is "Enhancements"

# BENCHMARK: its list, the setup page: scenes and options as rows (A ticks), the choosers, PAST RESULTS (never Start)
walk down "Benchmark" 3; expect el_is "Benchmark"
press_a_on "Benchmark" 0.8; expect el_is "Setup"; expect page_is bench
press_a_on "Setup" 1.0; expect page_is bench/setup
expect row_is 102
sweep
walk up "Rosewood spin" 4
press y 1.0; confirm_no   # START BENCHMARK asks first (No)
press down 0.5; press down 0.5; press up 0.5
press a 0.5; press a 0.5   # a scene ticked off and on again
walk down "Show the benchmark line during passes" 14; expect el_is "Show the benchmark line during passes"
press right 0.6; expect el_is "Passes"
press right 0.5; press left 0.5   # the chooser: 5 passes, then 3 again
press down 0.5; expect el_is "Between passes"
press down 0.5; expect el_is "Name (optional)"
press down 0.5; expect el_is "START BENCHMARK"
press right 0.5; expect el_is "PAST RESULTS"
press b 0.5; press x 1.2; expect page_is bench/history   # X = PAST RESULTS

# PAST RESULTS: the runs; the detail's COMPARE: the comparison page (Only what differs), B back to the runs; OPEN: the report
press down 0.5; press up 0.5; expect row_is 101
sweep
press right 0.6; expect el_is "OPEN"
press right 0.5; expect el_is "COMPARE"
press right 0.5; expect el_is "SET AS BASELINE"
press right 0.5; expect el_is "SHOW RESULTS FOLDER"
press b 0.5; press x 1.2; expect page_is bench/compare   # X = COMPARE
expect focus_is PzoptMainMenu
press down 0.5; press down 0.5; press up 0.5
sweep
n1=$(count_of "menu: only what differs")
press x 0.8; press x 0.8   # X = Only what differs, off and on again
expect sh -c "[ \$(grep -ac 'menu: only what differs' '$ZD/console.txt') -ge $((n1 + 2)) ]"
press right 0.6; expect el_is "Only what differs"
press a 0.8; press a 0.8
press b 0.5; press y 1.0; expect page_is bench/history   # Y = Past results
press y 1.5; expect page_is bench/report   # Y = OPEN (the run's report)
sweep
n2=$(count_of "menu: share code copied")
press x 0.8; expect sh -c "[ \$(grep -ac 'menu: share code copied' '$ZD/console.txt') -gt $n2 ]"   # X = COPY SHARE CODE
press y 1.0; confirm_no   # Y = OPEN FULL REPORT asks first (No)
# the report (its scene rows move like every list; the newest run often has one scene, so no up / down here), the detail's
# buttons (the cursor lands past OPEN FULL REPORT), COMPARE A CODE's box, B back
press right 0.6; expect el_is "COPIED"   # (X copied the code above)
press left 0.5; expect el_is "OPEN FULL REPORT"   # reached (never pressed)
press right 0.5; expect el_is "COPIED"
press right 0.5; expect el_is "COMPARE A CODE"
press_a_on "COMPARE A CODE" 1.2; expect focus_is ISTextBox
press b 0.6; press b 1.0; expect focus_is PzoptMainMenu   # B leaves the field, B = Cancel
expect sh -c "! grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q 'modal='"
press right 0.5; expect el_is "PAST RESULTS"
press right 0.5; expect el_is "RUN AGAIN"   # reached (never pressed)
press b 0.5
press rb 0.8; expect page_is update   # RB: the next section from a deep page, LB: the one before (the benchmark's list)
press lb 0.8; expect page_is bench
walk down "Report" 3; press_a_on "Report" 1.2; expect page_is bench/report
press b 1.0; expect page_is bench; expect el_is "Report"
press up 0.4; press up 0.4; expect el_is "Past results"
press_a_on "Past results" 1.0; expect page_is bench/history
press b 1.0; expect page_is bench; expect el_is "Past results"
press b 1.0; expect page_is home; expect el_is "Benchmark"

# UPDATE: its rows (state, builds, notes) and their detail (no A on its buttons: Update now / Restart game), B back
press down 0.5; expect el_is "Update"
press_a_on "Update" 0.8; expect page_is update
for name in "Installed build" Available From "Release notes"; do press down 0.5; expect el_is "$name"; done
sweep
try x 0.8 "no release page while nothing is offered"
if focus_is ISModalDialog; then press b 1.0; fi
try y 0.8 "no update offered (Update now only with an offer)"
if focus_is ISModalDialog; then press b 1.0; fi
press b 0.8; expect el_is "Update"
# MOD COMPATIBILITY CHECK: overview, the choice (its detail has the two choices: not pressed here), the jars
press down 0.5; expect el_is "Mod compatibility check"
press_a_on "Mod compatibility check" 0.8; expect page_is compat
expect el_is "Overview"
press down 0.5; expect el_is "Choice"
sweep
walk down "Choice" 2
n3=$(count_of "mod compatibility: .* chosen")
# X = the other choice (saved for the next launch): the restart prompt names it, LATER under the cursor; B = Later; Y = the
# page's RESTART GAME asks first (No); X again = back, nothing waits for a restart any more (no prompt, no reminder)
press x 1.0
expect sh -c "[ \$(grep -ac 'mod compatibility: .* chosen' '$ZD/console.txt') -gt $n3 ]"
expect sh -c "grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q 'page=compat+prompt '"
expect el_is "LATER"
press b 0.8; expect sh -c "! grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q '+prompt '"
expect el_is "Choice"
press y 1.0; confirm_no
press x 1.0; expect sh -c "[ \$(grep -ac 'mod compatibility: .* chosen' '$ZD/console.txt') -gt $((n3 + 1)) ]"   # back
expect sh -c "! grep -a BENCHPAD '$ZD/console.txt' | tail -1 | grep -q '+prompt '"
press right 0.6; expect el_is "MAX PERFORMANCE"
press right 0.5; expect el_is "MAX COMPATIBILITY"   # the two choices reached (not pressed: they save the choice)
press left 0.5; press left 0.5
try down 0.5 "no Java mod jar listed under Choice on this machine"; try down 0.5 "no Java mod jar listed under Choice on this machine"
press b 0.8; expect el_is "Mod compatibility check"
# Start (Menu) closes the screen; with a setting waiting for a restart it would ask to restart instead
press start 1.2; expect focus_is MainScreen
expect el_is "PZ OPTIMIZATION"
