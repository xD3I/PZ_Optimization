#!/usr/bin/env bash
# Does the "Low-end hardware (4 cores or less)" preset tick texture compression and set its Display options? (2026-09-23
# as an Options-tab check; since 2026-10-18 the preset is on the home page of the main menu's PZ OPTIMIZATION screen and
# this applies it through the harness's options_profile rig, no clicks.) One run of harness/run.sh --mode verify (Steam
# off, a scratch tab file, the game's options.ini starting with textureCompression=false): the rig logs every pzopt
# setting the preset moved ("profile: key=value") and the stock Display options before / after ("vsync=... lightFPS=...
# textureCompression=..."). Checked: workers=1, after textureCompression=true, lightFPS=10, uiRenderFPS=30, framerate=60.
# ~/Zomboid/options.ini and pzopt/framecap.ini are backed up first and restored on exit (shared machine).
# Run through the queue: harness/queue.sh submit cmd --install opt --label <l> -- harness/preset-texcompress-check.sh
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/preset-texcompress-check}; rm -rf "$OUT"; mkdir -p "$OUT"
scripts/pzopt.sh status | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
GAMEOPT="$ZOMBOID/options.ini"; CAP="$ZOMBOID/pzopt/framecap.ini"
cp "$GAMEOPT" "$OUT/options.ini.orig"
[[ -f "$CAP" ]] && cp "$CAP" "$OUT/framecap.ini.orig"
cleanup() {
  cp "$OUT/options.ini.orig" "$GAMEOPT"
  if [[ -f "$OUT/framecap.ini.orig" ]]; then cp "$OUT/framecap.ini.orig" "$CAP"; else rm -f "$CAP"; fi
  echo "options restored"
}
trap cleanup EXIT
sed -i 's/^textureCompression=.*/textureCompression=false/' "$GAMEOPT"   # start from off, so a true afterwards is the preset's
: > "$OUT/user.ini"
harness/run.sh --label presetchk --mode verify --launcher direct --no-mangohud --no-dashboard \
  --vmarg "-Dpzopt.userOptionsFile=$OUT/user.ini" --flag "options_profile=Low-end_hardware_(4_cores_or_less)" > "$OUT/run.log" 2>&1
RUN=$(ls -td harness/runs/presetchk-* 2>/dev/null | head -1)
cp "$GAMEOPT" "$OUT/options.ini.after"
grep -a "\[pzopt-harness\] profile" "$RUN/console.txt" 2>/dev/null | tee "$OUT/profile-lines.txt" | tail -8
after=$(grep -a "\[pzopt-harness\] profile: '.*' applied" "$OUT/profile-lines.txt" | tail -1)
FAILS=0
check() { if eval "$2"; then echo "PASS: $1"; else echo "FAIL: $1"; FAILS=$((FAILS + 1)); fi; }
check "workers=1 (the preset's pzopt settings)" 'grep -q "profile: workers=1" "$OUT/profile-lines.txt"'
check "tab file: workers=1" 'grep -q "^workers=1" "$OUT/user.ini"'
check "textureCompression=true" '[[ "$after" == *"textureCompression=true"* ]]'
check "lightFPS=10" '[[ "$after" == *"lightFPS=10"* ]]'
check "uiRenderFPS=30" '[[ "$after" == *"uiRenderFPS=30"* ]]'
check "framerate=60" '[[ "$after" == *"framerate=60"* ]]'
grep -a "settings: preset could not set stock option" "$RUN/console.txt" 2>/dev/null | tail -3
echo "fails=$FAILS (logs in $OUT)"
exit $FAILS
