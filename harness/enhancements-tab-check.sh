#!/usr/bin/env bash
# Do the Enhancements settings show their pages, impact chart and before / after clips? (2026-09-25 as an Options-tab
# check; since 2026-10-18 the settings live in the main menu's PZ OPTIMIZATION screen and this drives it through the
# harness's options rigs, no clicks.) Two runs of harness/run.sh --mode verify (Steam off, a scratch tab file):
#  1. options_clips=1 + options_shots: the Enhancements group page (its four categories), Image quality, the Upscaler,
#     HDR output and Ambient occlusion settings with their clips and the "Effect on your hardware" chart, and the
#     Optimizations group page (no Visuals category there); each screenshot is OCRed.
#  2. options_profile "Low-end hardware + FSR 1.0 upscaling": the preset reaches the Enhancements (upscaler=fsr1 in the
#     console's "profile:" lines).
# The player's ~/Zomboid/options.ini and pzopt/framecap.ini are backed up and restored (a preset writes Display options).
# Run through the queue: harness/queue.sh submit cmd --install opt --label <l> -- harness/enhancements-tab-check.sh
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/enhancements-tab-check}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
scripts/pzopt.sh status | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
GAMEOPT="$ZOMBOID/options.ini"; CAP="$ZOMBOID/pzopt/framecap.ini"
[[ -f "$GAMEOPT" ]] && cp "$GAMEOPT" "$OUT/options.ini.orig"
[[ -f "$CAP" ]] && cp "$CAP" "$OUT/framecap.ini.orig"
cleanup() {
  [[ -f "$OUT/options.ini.orig" ]] && cp "$OUT/options.ini.orig" "$GAMEOPT"
  [[ -f "$OUT/framecap.ini.orig" ]] && cp "$OUT/framecap.ini.orig" "$CAP"
  echo "options restored"
}
trap cleanup EXIT
FAILS=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
# run_rig <label> <run.sh flags...>: one options-rig run; RUN = its dir, the screenshots copied into $OUT/<label>-*
run_rig() {
  local label=$1; shift
  : > "$OUT/$label-user.ini"
  rm -f "$ZOMBOID"/Screenshots/pzopt-options*.png "$ZOMBOID"/Screenshots/pzopt-profile.png
  harness/run.sh --label "$label" --mode verify --launcher direct --no-mangohud --no-dashboard \
    --vmarg "-Dpzopt.userOptionsFile=$OUT/$label-user.ini" "$@" > "$OUT/$label-run.log" 2>&1
  RUN=$(ls -td harness/runs/"$label"-* 2>/dev/null | head -1)
  for f in "$ZOMBOID"/Screenshots/pzopt-options*.png "$ZOMBOID"/Screenshots/pzopt-profile.png; do
    [[ -f "$f" ]] && cp "$f" "$OUT/$label-$(basename "$f")"
  done
  [[ -n "$RUN" ]] && cp "$OUT/$label"-pzopt-*.png "$RUN/" 2>/dev/null
  grep -aq "\[pzopt-harness\] options: PZ OPTIMIZATION screen shown" "$RUN/console.txt" 2>/dev/null \
    && pass "$label: the screen opened" || fail "$label: the screen did not open (see $OUT/$label-run.log)"
  grep -a "rig error\|LuaError\|attempt to" "$RUN/console.txt" 2>/dev/null | head -3 | grep -q . && fail "$label: Lua errors in the console" || true
}
ocr() { python3 $UI read --screen "$1" 2>/dev/null; }

# 1. the pages, the chart, the clips
run_rig enhchk-pages --flag options_tab=Enhancements --flag options_clips=1 \
  --flag "options_shots=vis;vis/image;key:upscaler;key:hdrAuto;key:ambientOcclusion;perf"
has() { grep -qiE "$2" "$OUT/enhchk-pages-pzopt-options-$1.ocr.txt"; }
for n in 1 2 3 4 5 6; do
  f="$OUT/enhchk-pages-pzopt-options-$n.png"
  [[ -f "$f" ]] && ocr "$f" > "${f%.png}.ocr.txt" || { fail "no screenshot $n"; : > "${f%.png}.ocr.txt"; }
done
for s in "Image quality" "Lighting and shadows" "Reflections and glass" "Atmosphere"; do
  has 1 "$s" && pass "Enhancements page lists '$s'" || fail "Enhancements page lacks '$s'"
done
has 2 "Upscaling" && pass "Image quality lists Upscaling" || fail "Image quality lacks Upscaling"
has 3 "NO ?UPSCALER" && pass "upscaler clip captions" || fail "upscaler clip captions missing"
has 4 "HDR ?ON" && pass "HDR clip captions" || fail "HDR clip captions missing"
has 5 "OCCLUSION ?ON" && pass "AO clip captions" || fail "AO clip captions missing"
for n in 3 4 5; do
  has $n "Effect on your hardware" && pass "shot $n: impact chart" || fail "shot $n: no impact chart"
  has $n "no clip for this setting|could not be decoded" && fail "shot $n: a clip is missing"
done
has 6 "Image quality|HDR output|Ambient occlusion" && fail "Optimizations page lists an Enhancements category" || pass "Optimizations page without the Enhancements categories"

# 2. the FSR preset sets the Enhancements' upscaler
run_rig enhchk-profile --flag "options_profile=Low-end_hardware_+_FSR_1.0_upscaling"
grep -a "\[pzopt-harness\] profile" "$RUN/console.txt" 2>/dev/null | tail -12 | tee "$OUT/profile-lines.txt"
grep -aq "\[pzopt-harness\] profile: upscaler=fsr1" "$RUN/console.txt" 2>/dev/null \
  && pass "the FSR preset set upscaler=fsr1" || fail "upscaler not fsr1 after the preset"

echo "fails=$FAILS (screens and logs in $OUT)"
exit $FAILS
