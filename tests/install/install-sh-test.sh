#!/usr/bin/env bash
# install.sh on a fake game folder (2026-10-07, the Workshop "can't uninstall" reports): install from a folder, the
# double-click uninstaller files listed and removed, uninstall, an install cut short before its manifest (replaced), a
# class file another Java mod put there (refused, --force moves it aside), and --uninstall without any list (finds what
# is ours, leaves the other mod's file and the stock files). HOME points at the test folder: the real ~/Zomboid is never
# touched. Run: tests/install/install-sh-test.sh
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
export HOME="$T/home"
mkdir -p "$HOME/Zomboid/pzopt" "$HOME/Zomboid/Lua"
fail=0
check() { if eval "$1"; then :; else echo "FAIL: $2" >&2; fail=1; fi; }

G="$T/game"
mkdir -p "$G/media/lua/client"
echo stock > "$G/media/lua/client/Stock.lua"
printf '{"mainClass":"zombie/gameStates/MainScreenState","classpath":[".","projectzomboid.jar"],"vmArgs":["-Xmx3072m"]}' > "$G/ProjectZomboid64.json"
python3 - "$G/projectzomboid.jar" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    z.writestr('zombie/GitVersion.class', b'\xca\xfe\xba\xbe REVISION 4a0e9546ec ')
EOF

# the release, unpacked (what the Workshop item's pzopt-classes/ holds)
R="$T/release"
mkdir -p "$R/pzopt/uninstall" "$R/zombie/iso" "$R/zombie/core" "$R/media/lua/client/pzopt"
printf 'revision=4a0e9546ec\ncommit=abc1234\nbuilt=1\n' > "$R/pzopt/build-info.properties"
printf 'class Overrides' > "$R/pzopt/Overrides.class"
printf 'calls pzopt/Overrides' > "$R/zombie/iso/IsoChunkMap.class"
printf 'inner' > "$R/zombie/iso/IsoChunkMap\$Inner.class"
printf 'no reference to the package' > "$R/zombie/FliesSound.class"
printf 'calls pzopt/Config' > "$R/zombie/core/PerformanceSettings.class"
echo 'lua' > "$R/media/lua/client/pzopt/tab.lua"
cp "$REPO/install.sh" "$R/pzopt/uninstall/install.bash"
cp "$REPO/src/uninstall/Uninstall-PZ-Optimization.cmd" "$REPO/src/uninstall/uninstall-pz-optimization.bash" "$R/"
(cd "$R" && find . -type f | sed 's#^\./##' | LC_ALL=C sort; echo pzopt-files.txt) > "$T/list" && mv "$T/list" "$R/pzopt-files.txt"

run() { bash "$REPO/install.sh" --dir "$G" "$@" > "$T/out" 2>&1; }

# 1. install
run --from "$R"; rc=$?
check '[[ $rc -eq 0 ]]' "install exits 0: $(cat "$T/out")"
check '[[ -f "$G/zombie/iso/IsoChunkMap.class" && -f "$G/uninstall-pz-optimization.bash" && -f "$G/Uninstall-PZ-Optimization.cmd" ]]' "install copies the classes and the uninstallers"
check 'grep -q "^uninstall-pz-optimization.bash [0-9a-f]\{64\}$" "$G/pzopt-installed.txt"' "manifest lists the uninstaller with its hash"
check '! grep -q "^# unfinished" "$G/pzopt-installed.txt"' "the final manifest has no unfinished line"

# 2. the in-folder uninstaller (pzopt/uninstall/install.bash, a temp copy) removes everything, the stock files stay
echo "x" > "$HOME/Zomboid/pzopt/uninstall-files.txt"; echo "action=removed" > "$HOME/Zomboid/Lua/pzopt-boot-repair.txt"
bash "$G/uninstall-pz-optimization.bash" > "$T/out" 2>&1; rc=$?
check '[[ $rc -eq 0 ]]' "uninstaller exits 0: $(cat "$T/out")"
check '[[ ! -e "$G/zombie" && ! -e "$G/pzopt" && ! -e "$G/media/lua/client/pzopt" && ! -e "$G/pzopt-installed.txt" ]]' "uninstall removes every file and emptied folder: $(cd "$G" && find . -type f | head)"
check '[[ ! -e "$G/uninstall-pz-optimization.bash" && ! -e "$G/Uninstall-PZ-Optimization.cmd" ]]' "uninstall removes the uninstallers"
check '[[ -f "$G/projectzomboid.jar" && -f "$G/media/lua/client/Stock.lua" ]]' "stock files stay"
check '[[ ! -e "$HOME/Zomboid/pzopt/uninstall-files.txt" && ! -e "$HOME/Zomboid/Lua/pzopt-boot-repair.txt" ]]' "the game's notes about the old install are cleared"

# 3. an install cut short before its manifest (old installers): ours, replaced
mkdir -p "$G/zombie/iso"; cp "$R/zombie/iso/IsoChunkMap.class" "$G/zombie/iso/"; cp "$R/zombie/FliesSound.class" "$G/zombie/"
run --from "$R"; rc=$?
check '[[ $rc -eq 0 ]] && grep -q "replacing" "$T/out"' "an unfinished install is replaced: $(cat "$T/out")"
check '! ls "$HOME/Zomboid/pzopt/replaced-files" >/dev/null 2>&1 || [[ -z "$(find "$HOME/Zomboid/pzopt/replaced-files" -name IsoChunkMap.class)" ]]' "our own leftover is not treated as another mod's"
run --uninstall

# 4. another mod's IsoChunkMap.class: refused, --force moves it aside
mkdir -p "$G/zombie/iso"; printf 'Better Vehicle Dynamics' > "$G/zombie/iso/IsoChunkMap.class"
run --from "$R"; rc=$?
check '[[ $rc -ne 0 ]] && grep -q "not PZ Optimization" "$T/out"' "another mod's class is refused: $(cat "$T/out")"
check '[[ "$(cat "$G/zombie/iso/IsoChunkMap.class")" == "Better Vehicle Dynamics" ]]' "the other mod's file is untouched after a refusal"
run --from "$R" --force; rc=$?
check '[[ $rc -eq 0 ]]' "--force installs: $(cat "$T/out")"
check '[[ "$(cat "$(find "$HOME/Zomboid/pzopt/replaced-files" -name IsoChunkMap.class | head -1)")" == "Better Vehicle Dynamics" ]]' "--force moved the other mod's file aside"

# 5. no manifest, no pzopt-files.txt: --uninstall finds ours (FliesSound only by the Workshop copy's list) and keeps the other mod's file
run --uninstall
mkdir -p "$G/zombie/iso" "$G/zombie/core" "$G/pzopt" "$G/media/lua/client/pzopt"
cp "$R/zombie/core/PerformanceSettings.class" "$G/zombie/core/"; cp "$R/pzopt/Overrides.class" "$G/pzopt/"; cp "$R/zombie/FliesSound.class" "$G/zombie/"
cp "$R/media/lua/client/pzopt/tab.lua" "$G/media/lua/client/pzopt/"; printf 'Better Vehicle Dynamics' > "$G/zombie/iso/IsoChunkMap.class"
W="$T/steamapps/workshop/content/108600/3805285544/mods/PZ_Optimization/42"
mkdir -p "$W" "$T/steamapps/common"; cp -r "$R" "$W/pzopt-classes"; mv "$G" "$T/steamapps/common/ProjectZomboid"; G="$T/steamapps/common/ProjectZomboid"
run --uninstall; rc=$?
check '[[ $rc -eq 0 ]] && grep -q "no list of installed files" "$T/out"' "uninstall without a list scans: $(cat "$T/out")"
check '[[ ! -e "$G/zombie/core/PerformanceSettings.class" && ! -e "$G/pzopt" && ! -e "$G/media/lua/client/pzopt" && ! -e "$G/zombie/FliesSound.class" ]]' "the scan removes ours: $(cd "$G" && find . -type f)"
check '[[ "$(cat "$G/zombie/iso/IsoChunkMap.class")" == "Better Vehicle Dynamics" ]]' "the scan keeps the other mod's file"
check '[[ -f "$G/media/lua/client/Stock.lua" ]]' "the scan keeps stock files"

# 6. download from release API with large payload (> 128 KB, avoids Argument list too long on Linux)
ZIP="$T/mock.zip"
(cd "$R" && python3 -c '
import zipfile, os, sys
with zipfile.ZipFile(sys.argv[1], "w") as z:
    for root, dirs, files in os.walk("."):
        for f in files:
            p = os.path.relpath(os.path.join(root, f), ".")
            z.write(p)
' "$ZIP")
BIN="$T/bin"
mkdir -p "$BIN"
cat << 'MOCK' > "$BIN/curl"
#!/usr/bin/env bash
out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    *)
      if [[ "$1" == *"/releases/latest"* ]]; then
        echo '{"tag_name":"v-latest","assets":[]}'
        exit 0
      elif [[ "$1" == *"/releases?per_page=50"* ]]; then
        python3 -c '
import json
assets = [{"name": "pzopt-4a0e9546ec-classes.zip", "url": "mock://asset"}]
items = [{"tag_name": f"v{i}", "published_at": f"2026-10-{i:02d}T00:00:00Z", "assets": assets if i == 5 else [{"name": "other.zip", "url": "http://x" * 2000}]} for i in range(50)]
print(json.dumps(items))
'
        exit 0
      fi
      shift
      ;;
  esac
done
if [[ -n "$out" ]]; then
  cp "$MOCK_ZIP" "$out"
else
  cat "$MOCK_ZIP"
fi
MOCK
chmod +x "$BIN/curl"
# remove workshop copy and other mod class so install.sh downloads release zip cleanly
rm -rf "$W" "$G/zombie"
MOCK_ZIP="$ZIP" PATH="$BIN:$PATH" run; rc=$?
check '[[ $rc -eq 0 ]]' "download and install with >128KB releases JSON succeeds: $(cat "$T/out")"
check '[[ -f "$G/zombie/iso/IsoChunkMap.class" ]]' "mock release classes installed"

[[ $fail -eq 0 ]] && echo "install-sh-test ok"
exit $fail
