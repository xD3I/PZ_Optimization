#!/bin/bash
# harness/uninstall-e2e.sh - install and uninstall the way a player does, end to end, ON a laptop (flip: Linux; mac:
# macOS, bash 3.2), 2026-09-27. Started by harness/uninstall-e2e-remote.sh (a queue cmd job on the desktop), which
# copies into ~/pzopt-e2e/: classes/ (the build under test), item/ (the Workshop mod folder PZ_Optimization/ as
# scripts/workshop.sh stages it: 42/mod.info, install.bash, the install helper Lua, pzopt-classes/), install.sh.
#
#   A  build installed, devUninstallDrive=true: the main menu's PZ OPTIMIZATION screen opens, its home page's Uninstall PZ
#      Optimization... row, UNINSTALL and Yes are pressed (screenshots of the screen and the dialog); the game quits, pzopt.Uninstall's helper
#      removes the files. Checked: every installed file and folder gone, the launcher JSON free of pzopt's edits
#      (AOT jar form, G1 / JIT flags), uninstall.log clean.
#   B  nothing installed, the helper mod enabled (~/Zomboid/mods, the game scans Steam's Workshop folder only with
#      Steam), e2e flag file: the helper window shows the command (screenshot), Copy is pressed, the OS clipboard is
#      read and compared, the pasted command is started while the game runs (it must wait), the game quits, the
#      install finishes. Checked: manifest hashes, build-info commit = the build under test.
#   C  installed again: the helper shows its installed note, the overrides load.
#   D  (only when no real Workshop item 3805285544 is on the machine) the one-liner path: the item staged in the
#      Steam library, uninstall, `cat install.sh | bash` with no --dir: the game found, the Workshop copy used.
#   E-H the boot repair and the in-folder uninstaller (2026-10-07, docs/findings-uninstall-2026-10-07.md): E an in-game
#      uninstall whose helper left its list (the game removes the files at the next start and restarts stock; the
#      helper window says so); F a build for another game revision with the Workshop copy staged (replaced by the copy,
#      restarted with the overrides); G the same without a copy (removed, restarted stock); H
#      `bash <game>/uninstall-pz-optimization.bash`.
#   Restore (EXIT trap): the previous install exactly (files, manifest, AOT folder, launcher JSON, pzopt.properties),
#   options.ini, mods/default.txt, the helper mod and staged item removed, the restored manifest verified.
# Refuses to start when a game runs or a harness flag file exists.
set -u
E2E="${E2E:-$HOME/pzopt-e2e}"
OUT="$E2E/out-$(date +%Y%m%d-%H%M%S)"
ZOMBOID="$HOME/Zomboid"
if [[ "$(uname)" == Darwin ]]; then
  OS=mac
  STEAMAPPS="$HOME/Library/Application Support/Steam/steamapps"
  PZ_APP="$STEAMAPPS/common/ProjectZomboid/Project Zomboid.app"
  G="$PZ_APP/Contents/Java"
  GAME_RE='zombie\.gameStates\.MainScreenState'
else
  OS=linux
  STEAMAPPS="$HOME/.local/share/Steam/steamapps"
  G="$STEAMAPPS/common/ProjectZomboid/projectzomboid"
  GAME_RE='^([^ ]*/)?ProjectZomboid64( |$)'
  # the desktop session's display / D-Bus for spectacle and the clipboard (as the queue's laptop wrapper does)
  p=$(pgrep -o plasmashell || true)
  if [[ -n "$p" ]]; then
    for v in DISPLAY WAYLAND_DISPLAY XAUTHORITY DBUS_SESSION_BUS_ADDRESS XDG_SESSION_TYPE XDG_CURRENT_DESKTOP XDG_RUNTIME_DIR; do
      val=$(tr '\0' '\n' < /proc/$p/environ 2>/dev/null | sed -n "s/^$v=//p" | head -1); [[ -n "$val" ]] && export "$v=$val"
    done
  fi
fi
ITEM="$STEAMAPPS/workshop/content/108600/3805285544"
LOCALMOD="$ZOMBOID/mods/PZ_Optimization"
CONSOLE="$ZOMBOID/console.txt"
JSON="$G/ProjectZomboid64.json"
fails=0
die() { echo "e2e: $*" >&2; exit 1; }
check() { if [[ "$1" == ok ]]; then echo "e2e: ok   $2"; else echo "e2e: FAIL $2"; fails=$((fails + 1)); fi; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

pgrep -f "$GAME_RE" >/dev/null && die "a game is running"
[[ -f "$ZOMBOID/Lua/pzopt-harness.txt" ]] && die "a harness flag file exists (a run is going?)"
[[ -d "$E2E/classes/pzopt" && -d "$E2E/item/42" && -f "$E2E/install.sh" ]] || die "missing classes/, item/ or install.sh in $E2E"
[[ -f "$G/projectzomboid.jar" ]] || die "no game in $G"
mkdir -p "$OUT/shots"; rm -f "$ZOMBOID"/Screenshots/pzopt-e2e-*.png
BACK="$OUT/backup"; mkdir -p "$BACK"
exec > >(tee -a "$OUT/e2e.log") 2>&1

# --- backup ---------------------------------------------------------------------------------------------------------
( cd "$G" && {
    [[ -f pzopt-installed.txt ]] && { grep -v '^#' pzopt-installed.txt | cut -d' ' -f1; echo pzopt-installed.txt; }
    [[ -f pzopt-files.txt ]] && echo pzopt-files.txt
    [[ -d pzopt/aot ]] && find pzopt/aot -type f
    [[ -f natives/pzopt-dlss-installed.txt ]] && { echo natives/pzopt-dlss-installed.txt; grep -v '^#' natives/pzopt-dlss-installed.txt | grep -v = | sed 's|^|natives/|'; }
  } | sort -u | while IFS= read -r f; do [[ -f "$f" ]] && echo "$f"; done > "$BACK/files.txt" )
( cd "$G" && tar cf "$BACK/install.tar" -T "$BACK/files.txt" )
for f in ProjectZomboid64.json ProjectZomboid64.json.pzopt-backup pzopt.properties; do [[ -f "$G/$f" ]] && cp "$G/$f" "$BACK/$f"; done
cp "$ZOMBOID/options.ini" "$BACK/options.ini"
[[ -f "$ZOMBOID/mods/default.txt" ]] && cp "$ZOMBOID/mods/default.txt" "$BACK/default.txt"
[[ -e "$LOCALMOD" ]] && mv "$LOCALMOD" "$BACK/localmod"
staged_item=0
echo "e2e: $OS, game $G; backed up $(wc -l < "$BACK/files.txt" | tr -d ' ') installed files"

pids=()
restore() {
  set +e
  for p in "${pids[@]:-}"; do [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null && kill "$p" 2>/dev/null; done
  for p in $(pgrep -f "$GAME_RE" || true); do kill "$p" 2>/dev/null; done
  sleep 2
  [[ -f "$G/pzopt-installed.txt" || -f "$G/pzopt-files.txt" ]] && bash "$E2E/install.sh" --uninstall --dir "$G" >/dev/null 2>&1
  ( cd "$G" && tar xf "$BACK/install.tar" )
  for f in ProjectZomboid64.json ProjectZomboid64.json.pzopt-backup pzopt.properties; do
    if [[ -f "$BACK/$f" ]]; then cp "$BACK/$f" "$G/$f"; else rm -f "$G/$f"; fi
  done
  cp "$BACK/options.ini" "$ZOMBOID/options.ini"
  if [[ -f "$BACK/default.txt" ]]; then cp "$BACK/default.txt" "$ZOMBOID/mods/default.txt"; else rm -f "$ZOMBOID/mods/default.txt"; fi
  rm -rf "$LOCALMOD"; [[ -e "$BACK/localmod" ]] && mv "$BACK/localmod" "$LOCALMOD"
  if [[ $staged_item == 1 ]]; then rm -rf "$ITEM"; rmdir "$(dirname "$ITEM")" 2>/dev/null; fi
  rm -f "$ZOMBOID/Lua/pzopt-e2e-helper.txt" "$ZOMBOID/Lua/pzopt-e2e-quit.txt" "$ZOMBOID/Lua/pzopt-boot-repair.txt" \
    "$ZOMBOID/pzopt/uninstall-files.txt" "$ZOMBOID/pzopt/uninstall-dirs.txt"
  python3 - "$G" <<'EOF'
import hashlib, sys, os
d = sys.argv[1]; m = os.path.join(d, "pzopt-installed.txt")
if not os.path.exists(m): print("e2e: restored: no install (there was none before)"); sys.exit()
bad = n = 0
for line in open(m):
    line = line.strip()
    if not line or line.startswith("#"): continue
    rel, sha = line.split(" ", 1); n += 1
    p = os.path.join(d, rel)
    if not os.path.exists(p) or hashlib.sha256(open(p, "rb").read()).hexdigest() != sha: bad += 1; print("e2e: restore mismatch", rel)
print("e2e: restored the previous install, %d files, %d mismatches" % (n, bad))
EOF
  echo "e2e: output in $OUT"
}
trap restore EXIT

# --- helpers --------------------------------------------------------------------------------------------------------
launch() { # $1 = stdout file; sets GAME
  rm -f "$CONSOLE"
  if [[ $OS == mac ]]; then
    local java; java=$(ls -d "$PZ_APP"/Contents/PlugIns/jre-*/Contents/Home/bin/java 2>/dev/null | head -1)
    local opts=()
    while IFS= read -r a; do [[ "$a" == "-Dzomboid.steam=1" ]] && a="-Dzomboid.steam=0"; opts+=("$a"); done \
      < <(plutil -extract JVMOptions json -o - "$PZ_APP/Contents/Info.plist" | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)))')
    ( cd "$G" && exec "$java" "${opts[@]}" -Djava.library.path=. -cp ".:projectzomboid.jar" zombie.gameStates.MainScreenState </dev/null >"$1" 2>&1 ) &
    GAME=$!
    caffeinate -dis -w "$GAME" &
  else
    ( cd "$G" && export PATH="$G/jre64/bin:$PATH" LD_LIBRARY_PATH="$G/natives:$G:$G/jre64/lib:${LD_LIBRARY_PATH:-}" LC_NUMERIC=C \
        && XMODIFIERS= LD_PRELOAD="libjsig.so:libPZXInitThreads64.so" exec ./ProjectZomboid64 </dev/null >"$1" 2>&1 ) &
    GAME=$!
  fi
  pids+=("$GAME")
  echo "e2e: game pid $GAME"
}
wait_line() { # pattern, timeout s; prints the matching line
  local i
  for i in $(seq 1 $(( $2 * 5 ))); do
    local l; l=$(grep -a -m1 -E "$1" "$CONSOLE" 2>/dev/null)
    [[ -n "$l" ]] && { echo "$l"; return 0; }
    kill -0 "$GAME" 2>/dev/null || { grep -a -m1 -E "$1" "$CONSOLE" 2>/dev/null; return 1; }
    sleep 0.2
  done
  return 1
}
wait_exit() { local i; for i in $(seq 1 $(( $1 * 5 ))); do kill -0 "$GAME" 2>/dev/null || return 0; sleep 0.2; done; return 1; }
# the boot repair ends the launched process and pzopt.Restart starts the game again: follow the new process (sets GAME)
follow_relaunch() { # $1 = timeout s for the first process to end
  local old="$GAME" i p
  wait_exit "$1" || return 1
  for i in $(seq 1 100); do
    p=$(pgrep -n -f "$GAME_RE" || true)
    [[ -n "$p" && "$p" != "$old" ]] && { GAME=$p; pids+=("$GAME"); echo "e2e: restarted game pid $GAME"; return 0; }
    sleep 0.2
  done
  return 1
}
fake_revision() { # the installed build claims another game revision: what a game update does to an install
  sed -i.e2e 's/^revision=.*/revision=0000000000/' "$G/pzopt/build-info.properties" && rm -f "$G/pzopt/build-info.properties.e2e"
}
shot() { # the game's own screenshot (the Lua drives call getCore():TakeFullScreenshot("pzopt-e2e-<name>.png"))
  local f="$ZOMBOID/Screenshots/pzopt-e2e-$1.png" i
  for i in $(seq 1 25); do [[ -s "$f" ]] && break; sleep 0.2; done
  if [[ -s "$f" ]]; then mv "$f" "$OUT/shots/$1.png"; echo "e2e: screenshot $1.png"; else echo "e2e: screenshot $1 missing"; fi
}
clipboard() {
  if [[ $OS == mac ]]; then pbpaste; return; fi
  if command -v wl-paste >/dev/null; then wl-paste -n 2>/dev/null && return; fi
  for q in qdbus6 qdbus; do command -v $q >/dev/null && { $q org.kde.klipper /klipper getClipboardContents 2>/dev/null; return; }; done
}
no_lua_errors() { # $1 = console copy, $2 = phase: the game's red error counter counts these (a pcall included)
  local n; n=$(grep -a -c 'STACK TRACE' "$1")
  [[ $n == 0 ]] && check ok "$2: no Lua error in the console" || check fail "$2: $n Lua stack traces in the console"
}
json_clean() { # no pzopt edit left in the launcher JSON (Linux; the Mac bundle has none)
  [[ $OS == mac ]] && { echo ok; return; }
  python3 - "$JSON" <<'EOF'
import json, sys
j = json.load(open(sys.argv[1])); args = list(j.get("vmArgs", []))
for v in j.values():
    if isinstance(v, dict): args += v.get("vmArgs", [])
bad = [a for a in args if a.startswith("-Dpzopt.") or a.startswith("-XX:AOTCache") or "pzopt/aot" in a or "TrapLimit" in a or a == "-XX:-UseTypeSpeculation" or a == "-XX:-AOTInvokeDynamicLinking"]
bad += [e for e in j.get("classpath", []) if "pzopt" in e]
print("ok" if not bad else "left: " + " ".join(bad))
EOF
}
manifest_ok() { # prints "ok" when every manifest line matches and build-info is the build under test
  python3 - "$G" "$E2E/classes/pzopt/build-info.properties" <<'EOF'
import hashlib, sys, os
d, want = sys.argv[1], sys.argv[2]; m = os.path.join(d, "pzopt-installed.txt")
if not os.path.exists(m): print("no manifest"); sys.exit()
bad = n = 0
for line in open(m):
    line = line.strip()
    if not line or line.startswith("#"): continue
    rel, sha = line.split(" ", 1); n += 1
    p = os.path.join(d, rel)
    if not os.path.exists(p) or hashlib.sha256(open(p, "rb").read()).hexdigest() != sha: bad += 1
same = open(os.path.join(d, "pzopt/build-info.properties")).read() == open(want).read()
print("ok" if bad == 0 and n > 0 and same else "%d files, %d mismatches, build-info %s" % (n, bad, "same" if same else "differs"))
EOF
}
enable_helper_mod() {
  python3 - "$ZOMBOID/mods/default.txt" <<'EOF'
import os, re, sys
p = sys.argv[1]
s = open(p).read() if os.path.exists(p) else "VERSION = 1,\n\nmods\n{\n}\n\nmaps\n{\n}\n"
if not re.search(r"^\s*mod\s*=\s*\\?PZ_Optimization,", s, re.M):
    s = re.sub(r"(mods\s*\{\n)", r"\1    mod = PZ_Optimization,\n", s, count=1)
open(p, "w").write(s)
EOF
}

# --- the build under test, the helper mod ----------------------------------------------------------------------------
if [[ -f "$G/pzopt-installed.txt" || -f "$G/pzopt-files.txt" ]]; then bash "$E2E/install.sh" --uninstall --dir "$G" >/dev/null || die "uninstall of the previous install failed"; fi
bash "$E2E/install.sh" --from "$E2E/classes" --dir "$G" > "$OUT/install-A.txt" 2>&1 || { cat "$OUT/install-A.txt"; die "install failed"; }
tail -1 "$OUT/install-A.txt"
[[ -f "$JSON" ]] && sed -i.e2e 's/-Dzomboid.steam=1/-Dzomboid.steam=0/' "$JSON" && rm -f "$JSON.e2e"   # no Steam client in these launches
mkdir -p "$ZOMBOID/mods"; cp -R "$E2E/item" "$LOCALMOD"   # enabled from B on: in A its installed note would cover the screen's screenshot
printf 'devUninstallDrive=true\nupdateCheck=false\nhdrAuto=false\n' > "$G/pzopt.properties"
cp "$G/pzopt-installed.txt" "$OUT/manifest-A.txt"

# --- A: the in-game uninstall ----------------------------------------------------------------------------------------
echo "=== A: PZ OPTIMIZATION > Uninstall PZ Optimization... > UNINSTALL"
t0=$(now_ms)
launch "$OUT/A-stdout.txt"
l=$(wait_line '\[pzopt-e2e\] screen shown' 150) && { echo "$l" | sed 's/^.*\[pzopt-e2e\]/  /' | cut -c1-400; shot A-screen; } || check fail "A: the screen and its UNINSTALL button"
[[ "$l" == *'enabled=true'* && "$l" == *'visible=true'* ]] && check ok "A: button enabled and visible" || check fail "A: button enabled and visible"
l=$(wait_line '\[pzopt-e2e\] dialog shown' 30) && { echo "$l" | sed 's/^.*\[pzopt-e2e\]/  /' | cut -c1-400; shot A-dialog; } || check fail "A: confirm dialog"
wait_exit 90 && check ok "A: the game quit after Yes" || check fail "A: the game quit after Yes"
t_exit=$(now_ms)
cp "$CONSOLE" "$OUT/A-console.txt"
no_lua_errors "$OUT/A-console.txt" A
grep -a -E '\[pzopt\] (uninstall|install helper)|\[pzopt-e2e\]' "$OUT/A-console.txt" | sed 's/^.*\] \[pzopt/[pzopt/' | cut -c1-300
UNLOG="$ZOMBOID/pzopt/uninstall.log"
# the log appends across runs: this run's helper is done when the last line is a "finished" one (after its "requested")
for i in $(seq 1 150); do tail -1 "$UNLOG" 2>/dev/null | grep -q 'uninstall finished' && break; sleep 0.2; done
t_done=$(now_ms)
cp "$UNLOG" "$OUT/uninstall.log" 2>/dev/null
tail -2 "$UNLOG" 2>/dev/null | sed 's/^/  log: /'
echo "e2e: helper finished $((t_done - t_exit)) ms after the game ended ($(( (t_exit - t0) / 1000 )) s from launch to quit)"
tail -1 "$UNLOG" 2>/dev/null | grep -q '; 0 files could not be removed' && check ok "A: uninstall.log clean" || check fail "A: uninstall.log clean"
left=$(grep -v '^#' "$OUT/manifest-A.txt" | cut -d' ' -f1 | while IFS= read -r rel; do [[ -e "$G/$rel" ]] && echo "$rel"; done | wc -l | tr -d ' ')
[[ "$left" == 0 && ! -e "$G/pzopt-installed.txt" && ! -e "$G/pzopt" && ! -e "$G/zombie" ]] && check ok "A: every installed file and folder removed" || check fail "A: $left installed files left (pzopt/: $( [[ -e "$G/pzopt" ]] && echo yes || echo no))"
r=$(json_clean); [[ "$r" == ok ]] && check ok "A: launcher JSON free of pzopt edits" || check fail "A: launcher JSON: $r"
[[ -f "$G/projectzomboid.jar" ]] && check ok "A: projectzomboid.jar still there" || check fail "A: projectzomboid.jar missing"
# a later phase on a half-removed install only runs into timeouts (a game that cannot load its classes): stop here
[[ $fails == 0 ]] || { echo "e2e: A failed, B-D skipped"; echo "e2e: FAIL"; exit $fails; }

# --- B: the Workshop helper window, Copy, paste, quit ----------------------------------------------------------------
echo "=== B: the install helper window"
enable_helper_mod
: > "$ZOMBOID/Lua/pzopt-e2e-helper.txt"; rm -f "$ZOMBOID/Lua/pzopt-e2e-quit.txt"
launch "$OUT/B-stdout.txt"
l=$(wait_line '\[pzopt-e2e\] helper shown' 150) || check fail "B: helper window"
cmd="${l#*helper shown: }"
echo "  command: $cmd"
want="bash '$(cd "$LOCALMOD" && pwd)/42/install.bash'"
[[ "$cmd" == "$want" ]] && check ok "B: the command names this mod's folder" || check fail "B: command differs from $want"
shot B-helper
l=$(wait_line '\[pzopt-e2e\] copied:' 20) || check fail "B: Copy pressed"
clip=$(clipboard)
echo "  clipboard: $clip"
[[ "$clip" == "$cmd" ]] && check ok "B: the OS clipboard holds the command" || check fail "B: OS clipboard ($clip)"
bash -c "$cmd" > "$OUT/B-paste.txt" 2>&1 &
inst=$!
for i in $(seq 1 60); do grep -q 'the game is running' "$OUT/B-paste.txt" && break; sleep 0.25; done
grep -q 'the game is running' "$OUT/B-paste.txt" && check ok "B: the pasted command waits for the game" || check fail "B: the pasted command did not wait: $(head -2 "$OUT/B-paste.txt")"
: > "$ZOMBOID/Lua/pzopt-e2e-quit.txt"
wait_exit 60 && check ok "B: the game quit" || check fail "B: the game quit"
cp "$CONSOLE" "$OUT/B-console.txt"
no_lua_errors "$OUT/B-console.txt" B
for i in $(seq 1 150); do kill -0 "$inst" 2>/dev/null || break; sleep 0.2; done
wait "$inst"; rc=$?
sed 's/^/  install: /' "$OUT/B-paste.txt" | cut -c1-240
[[ $rc == 0 ]] && check ok "B: the installer finished after the quit" || check fail "B: installer exit $rc"
r=$(manifest_ok); [[ "$r" == ok ]] && check ok "B: installed build = the build under test, hashes match" || check fail "B: $r"
[[ $fails == 0 ]] || { echo "e2e: B failed, C-D skipped"; echo "e2e: FAIL"; exit $fails; }
rm -f "$ZOMBOID/Lua/pzopt-e2e-quit.txt"

# --- C: installed again ----------------------------------------------------------------------------------------------
echo "=== C: the helper's installed note, the overrides load"
printf 'updateCheck=false\nhdrAuto=false\n' > "$G/pzopt.properties"
launch "$OUT/C-stdout.txt"
wait_line '\[pzopt-e2e\] installed note shown' 150 >/dev/null && { check ok "C: installed note"; shot C-note; } || check fail "C: installed note"
wait_exit 60 || check fail "C: the game quit"
cp "$CONSOLE" "$OUT/C-console.txt"
no_lua_errors "$OUT/C-console.txt" C
n=$(grep -a -c 'loaded override' "$OUT/C-console.txt")
[[ $n -gt 0 ]] && check ok "C: $n overrides loaded" || check fail "C: no override loaded"
rm -f "$ZOMBOID/Lua/pzopt-e2e-helper.txt"

# --- D: the one-liner, the Workshop copy in the Steam library ---------------------------------------------------------
if [[ -e "$ITEM" ]]; then
  echo "=== D: skipped (a real Workshop item 3805285544 is on this machine)"
else
  echo "=== D: cat install.sh | bash (no --dir) with the item in the Steam library"
  staged_item=1
  mkdir -p "$ITEM/mods"; cp -R "$E2E/item" "$ITEM/mods/PZ_Optimization"
  bash "$E2E/install.sh" --uninstall --dir "$G" >/dev/null
  cat "$E2E/install.sh" | bash > "$OUT/D-install.txt" 2>&1; rc=$?
  sed 's/^/  /' "$OUT/D-install.txt" | cut -c1-240
  [[ $rc == 0 ]] && grep -q 'found the Steam Workshop copy' "$OUT/D-install.txt" && check ok "D: game found, Workshop copy used" || check fail "D: exit $rc"
  r=$(manifest_ok); [[ "$r" == ok ]] && check ok "D: installed build = the build under test" || check fail "D: $r"
fi

REPAIR_LOG="$ZOMBOID/pzopt/boot-repair.log"
reinstall() { # $1 = phase
  [[ -f "$G/pzopt-installed.txt" || -f "$G/pzopt-files.txt" ]] && bash "$E2E/install.sh" --uninstall --dir "$G" >/dev/null 2>&1
  bash "$E2E/install.sh" --from "$E2E/classes" --dir "$G" > "$OUT/install-$1.txt" 2>&1 || { check fail "$1: install"; return 1; }
  printf 'updateCheck=false\nhdrAuto=false\n' > "$G/pzopt.properties"
}
repair_line() { tail -n +"$((${1:-0} + 1))" "$REPAIR_LOG" 2>/dev/null | grep -v 'the game is started again\|could not start' | tail -1; }

# --- E: an in-game uninstall the helper did not finish ----------------------------------------------------------------
echo "=== E: boot repair, pending uninstall"
reinstall E
cp "$G/pzopt-installed.txt" "$OUT/manifest-E.txt"
( cd "$G" && grep -v '^#' pzopt-installed.txt | cut -d' ' -f1 | sed "s|^|$G/|" ) > "$ZOMBOID/pzopt/uninstall-files.txt"
: > "$ZOMBOID/pzopt/uninstall-dirs.txt"
age() { python3 -c 'import os, sys, time; t = time.time() - int(sys.argv[2]); os.utime(sys.argv[1], (t, t))' "$1" "$2"; }   # BSD touch has no -d
age "$G/pzopt-installed.txt" 120; age "$ZOMBOID/pzopt/uninstall-files.txt" 60
n0=$(wc -l < "$REPAIR_LOG" 2>/dev/null || echo 0)
: > "$ZOMBOID/Lua/pzopt-e2e-helper.txt"; : > "$ZOMBOID/Lua/pzopt-e2e-quit.txt"
launch "$OUT/E-stdout.txt"
follow_relaunch 90 && check ok "E: the game ended and started again" || check fail "E: no restart after the repair"
l=$(repair_line "$n0"); echo "  log: ${l:0:300}"
[[ "$l" == *' uninstalled: '* ]] && check ok "E: boot-repair.log says uninstalled" || check fail "E: boot-repair.log: $l"
l=$(wait_line '\[pzopt\] install helper: not installed' 150); echo "  ${l##*\] }" | cut -c1-200
[[ "$l" == *'boot repair: uninstalled'* ]] && check ok "E: the restarted game is stock, the helper names the repair" || check fail "E: helper line: $l"
wait_exit 120 || check fail "E: the restarted game quit"
cp "$CONSOLE" "$OUT/E-console.txt"; no_lua_errors "$OUT/E-console.txt" E
left=$(grep -v '^#' "$OUT/manifest-E.txt" | cut -d' ' -f1 | while IFS= read -r rel; do [[ -e "$G/$rel" ]] && echo "$rel"; done | wc -l | tr -d ' ')
[[ "$left" == 0 && ! -e "$ZOMBOID/pzopt/uninstall-files.txt" ]] && check ok "E: every file and the list removed" || check fail "E: $left files left"
r=$(json_clean); [[ "$r" == ok ]] && check ok "E: launcher JSON free of pzopt edits" || check fail "E: launcher JSON: $r"
rm -f "$ZOMBOID/Lua/pzopt-e2e-helper.txt" "$ZOMBOID/Lua/pzopt-e2e-quit.txt"

# --- F: a game update with a Workshop item in the library (the staged copy, or the machine's real subscription) -------
# replaced by the copy when it is a complete build for this game revision whose stock classes match the jar, else removed
if [[ -e "$ITEM" ]]; then
  echo "=== F: boot repair, build mismatch, Workshop item present ($( [[ $staged_item == 1 ]] && echo staged || echo "the machine's own" ))"
  reinstall F && fake_revision
  cp "$G/pzopt-installed.txt" "$OUT/manifest-F.txt"
  n0=$(wc -l < "$REPAIR_LOG" 2>/dev/null || echo 0)
  : > "$ZOMBOID/Lua/pzopt-e2e-helper.txt"; : > "$ZOMBOID/Lua/pzopt-e2e-quit.txt"
  launch "$OUT/F-stdout.txt"
  follow_relaunch 90 && check ok "F: the game ended and started again" || check fail "F: no restart after the repair"
  l=$(repair_line "$n0"); echo "  log: ${l:0:400}"
  if [[ "$l" == *' updated: '* ]]; then
    check ok "F: boot-repair.log says updated (the Workshop copy installed)"
    wait_line '\[pzopt-e2e\] installed note shown' 150 >/dev/null && check ok "F: the restarted game has the overrides" || check fail "F: no installed note"
    wait_exit 60 || check fail "F: the restarted game quit"
    cp "$CONSOLE" "$OUT/F-console.txt"; no_lua_errors "$OUT/F-console.txt" F
    n=$(grep -a -c 'loaded override' "$OUT/F-console.txt"); [[ $n -gt 0 ]] && check ok "F: $n overrides loaded" || check fail "F: no override loaded"
    grep -a -q 'DISABLED: build mismatch' "$OUT/F-console.txt" && check fail "F: the installed copy is a mismatch too" || check ok "F: the copy matches the game"
    head -1 "$G/pzopt-installed.txt" | grep -q 'boot repair' && check ok "F: manifest written by the boot repair" || check fail "F: manifest header $(head -1 "$G/pzopt-installed.txt")"
    if [[ $staged_item == 1 ]]; then r=$(manifest_ok); [[ "$r" == ok ]] && check ok "F: installed = the staged copy, hashes match" || check fail "F: $r"; fi
  elif [[ "$l" == *' removed: '* && $staged_item == 0 ]]; then
    check ok "F: boot-repair.log says removed (the machine's Workshop item is not a build for this game)"
    l=$(wait_line '\[pzopt\] install helper: not installed' 150)
    [[ "$l" == *'boot repair: removed'* ]] && check ok "F: the restarted game is stock, the helper names the repair" || check fail "F: helper line: $l"
    wait_exit 120 || check fail "F: the restarted game quit"
    cp "$CONSOLE" "$OUT/F-console.txt"; no_lua_errors "$OUT/F-console.txt" F
    left=$(grep -v '^#' "$OUT/manifest-F.txt" | cut -d' ' -f1 | while IFS= read -r rel; do [[ -e "$G/$rel" ]] && echo "$rel"; done | wc -l | tr -d ' ')
    [[ "$left" == 0 ]] && check ok "F: every installed file removed" || check fail "F: $left files left"
  else
    check fail "F: boot-repair.log: $l"
  fi
  rm -f "$ZOMBOID/Lua/pzopt-e2e-helper.txt" "$ZOMBOID/Lua/pzopt-e2e-quit.txt"
  if [[ $staged_item == 1 ]]; then rm -rf "$ITEM"; rmdir "$(dirname "$ITEM")" 2>/dev/null; staged_item=0; fi
else
  echo "=== F: skipped (no Workshop item in the library)"
fi

# --- G: a game update, no Workshop copy ------------------------------------------------------------------------------
if [[ ! -e "$ITEM" ]]; then
  echo "=== G: boot repair, build mismatch, no copy"
  reinstall G && fake_revision
  cp "$G/pzopt-installed.txt" "$OUT/manifest-G.txt"
  n0=$(wc -l < "$REPAIR_LOG" 2>/dev/null || echo 0)
  : > "$ZOMBOID/Lua/pzopt-e2e-helper.txt"; : > "$ZOMBOID/Lua/pzopt-e2e-quit.txt"
  launch "$OUT/G-stdout.txt"
  follow_relaunch 90 && check ok "G: the game ended and started again" || check fail "G: no restart after the repair"
  l=$(repair_line "$n0"); echo "  log: ${l:0:300}"
  [[ "$l" == *' removed: '* ]] && check ok "G: boot-repair.log says removed" || check fail "G: boot-repair.log: $l"
  l=$(wait_line '\[pzopt-e2e\] helper shown' 150) && shot G-helper
  l=$(grep -a -m1 '\[pzopt\] install helper: not installed' "$CONSOLE")
  [[ "$l" == *'boot repair: removed'* ]] && check ok "G: the restarted game is stock, the helper names the repair" || check fail "G: helper line: $l"
  wait_exit 120 || check fail "G: the restarted game quit"
  cp "$CONSOLE" "$OUT/G-console.txt"; no_lua_errors "$OUT/G-console.txt" G
  left=$(grep -v '^#' "$OUT/manifest-G.txt" | cut -d' ' -f1 | while IFS= read -r rel; do [[ -e "$G/$rel" ]] && echo "$rel"; done | wc -l | tr -d ' ')
  [[ "$left" == 0 ]] && check ok "G: every installed file removed" || check fail "G: $left files left"
  r=$(json_clean); [[ "$r" == ok ]] && check ok "G: launcher JSON free of pzopt edits" || check fail "G: launcher JSON: $r"
  rm -f "$ZOMBOID/Lua/pzopt-e2e-helper.txt" "$ZOMBOID/Lua/pzopt-e2e-quit.txt"
else
  echo "=== G: skipped (a real Workshop item is on this machine)"
fi

# --- H: the uninstaller in the game folder ---------------------------------------------------------------------------
echo "=== H: bash <game>/uninstall-pz-optimization.bash"
reinstall H
cp "$G/pzopt-installed.txt" "$OUT/manifest-H.txt"
bash "$G/uninstall-pz-optimization.bash" > "$OUT/H-uninstall.txt" 2>&1; rc=$?
sed 's/^/  /' "$OUT/H-uninstall.txt" | cut -c1-240
left=$(grep -v '^#' "$OUT/manifest-H.txt" | cut -d' ' -f1 | while IFS= read -r rel; do [[ -e "$G/$rel" ]] && echo "$rel"; done | wc -l | tr -d ' ')
[[ $rc == 0 && "$left" == 0 && ! -e "$G/uninstall-pz-optimization.bash" ]] && check ok "H: the folder's uninstaller removed everything" || check fail "H: exit $rc, $left files left"

echo "e2e: $fails failure(s)"
[[ $fails == 0 ]] && echo "e2e: PASS" || echo "e2e: FAIL"
exit $fails
