#!/usr/bin/env bash
# Does the "Texture compression: who compresses" setting (key texCompress, 2026-09-26) show and save? (An Options-tab
# check until 2026-10-18; the settings now live in the main menu's PZ OPTIMIZATION screen, driven here through the
# harness's options rigs, no clicks.) Two runs of harness/run.sh --mode verify (Steam off, a scratch tab file):
#  1. options_search "texture compression": the results page lists the setting (OCR of Screenshots/pzopt-options.png).
#  2. options_nav key:texCompress + options_joy focus,a,shot: the setting on its page, A on its row moves it auto -> worker
#     (saved at once, the restart prompt opens: a next-launch key); the scratch tab file must hold texCompress=worker.
# Run through the queue: harness/queue.sh submit cmd --install opt --label <l> -- harness/texcompress-tab-check.sh
set -u
cd "$(dirname "$0")/.."
source scripts/pz-env.sh
OUT=${OUT:-/tmp/texcompress-tab-check}; rm -rf "$OUT"; mkdir -p "$OUT"
UI=harness/ui-drive.py
scripts/pzopt.sh status | grep -q "^installed: *yes" || { echo "overrides not installed"; exit 1; }
FAILS=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
run_rig() {
  local label=$1; shift
  : > "$OUT/$label-user.ini"
  rm -f "$ZOMBOID"/Screenshots/pzopt-options*.png "$ZOMBOID"/Screenshots/pzopt-joy-*.png
  harness/run.sh --label "$label" --mode verify --launcher direct --no-mangohud --no-dashboard \
    --vmarg "-Dpzopt.userOptionsFile=$OUT/$label-user.ini" "$@" > "$OUT/$label-run.log" 2>&1
  RUN=$(ls -td harness/runs/"$label"-* 2>/dev/null | head -1)
  for f in "$ZOMBOID"/Screenshots/pzopt-options*.png "$ZOMBOID"/Screenshots/pzopt-joy-*.png; do
    [[ -f "$f" ]] && cp "$f" "$OUT/$label-$(basename "$f")"
  done
  grep -aq "\[pzopt-harness\] options: PZ OPTIMIZATION screen shown" "$RUN/console.txt" 2>/dev/null \
    && pass "$label: the screen opened" || fail "$label: the screen did not open (see $OUT/$label-run.log)"
}

# 1. the search finds it
run_rig texchk-search --flag "options_search=texture_compression"
f="$OUT/texchk-search-pzopt-options.png"
python3 $UI read --screen "$f" > "$OUT/search.ocr.txt" 2>/dev/null
grep -aE "options: search" "$RUN/console.txt" 2>/dev/null | tail -1
grep -qi "who compresses" "$OUT/search.ocr.txt" && pass "the search lists 'Texture compression: who compresses'" || fail "the search does not list the setting"
grep -qi "auto" "$OUT/search.ocr.txt" && pass "its value auto shown" || fail "its value auto not on the screenshot"

# 2. A on its row: auto -> worker, saved at once
run_rig texchk-set --flag options_nav=key:texCompress --flag options_joy=focus,a,shot
grep -a "\[pzopt-harness\] joy:" "$RUN/console.txt" 2>/dev/null | tail -3
grep -q "^texCompress=worker" "$OUT/texchk-set-user.ini" && pass "tab file: texCompress=worker" \
  || fail "tab file: $(grep texCompress "$OUT/texchk-set-user.ini" || echo 'no texCompress line')"
grep -aq "unknown key texCompress" "$RUN/console.txt" 2>/dev/null && fail "console: unknown key texCompress" || pass "console: key known"
grep -aq "menu: restart prompt shown" "$RUN/console.txt" 2>/dev/null && pass "the restart prompt opened (a next-launch key)" || fail "no restart prompt"
echo "fails=$FAILS (screens and logs in $OUT)"
exit $FAILS
