#!/usr/bin/env bash
# Install, remove or inspect the PZ_Optimization class overrides on Linux or macOS from a release zip.
# Standalone: needs bash, curl or gh, and unzip (or python3 / bsdtar). No JDK, no clone.
#
#   ./install.sh                     # find the game, download the zip for its revision, install
#   ./install.sh --zip pzopt-b0bbce05d5-classes.zip
#   ./install.sh --from /path/to/pzopt-classes   # an unpacked zip, e.g. the Steam Workshop item
#   ./install.sh --dir /path/to/ProjectZomboid/projectzomboid   # macOS: .../Project Zomboid.app/Contents/Java
#   ./install.sh --status
#   ./install.sh --uninstall
#   ./install.sh --force      # replace class files another Java mod put into the game folder (moved aside first)
#
# Without downloading anything first (any folder; the Steam Workshop copy is used when present):
#   curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.sh | bash
#   curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.sh | bash -s -- --uninstall
#   curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/uninstall.sh | bash
#
# Every install also leaves uninstall-pz-optimization.bash in the game folder: `bash <game folder>/uninstall-pz-optimization.bash`
# removes PZ Optimization without starting the game (it runs the copy of this script kept in pzopt/uninstall/).
#
# The zip holds the same class files for Windows, Linux and macOS (the Steam depots ship one jar);
# the runtime guard disables them, with one console.txt line, if the game revision differs.
# Files written are recorded in <game dir>/pzopt-installed.txt, the manifest scripts/pzopt.sh
# uses, so either tool can uninstall what the other installed. projectzomboid.jar is never
# modified. On macOS the game is "Project Zomboid.app": the files go into Contents/Java, which the
# bundle's JavaAppLauncher puts ahead of the jar on the class path (no launcher file to edit).
#
# The zip is fetched from the GitHub releases with curl (GITHUB_TOKEN is used if set, to
# avoid API rate limits) or with the gh CLI when it is logged in; --zip skips the download.
# --from installs the same tree from a folder instead (no network, no unzip); a pzopt-classes/
# folder next to this script (the Steam Workshop item layout), else the Workshop item's copy in the
# Steam library that holds the game, is used automatically. A running game is waited for: quit it
# and the install (or --uninstall) goes on.
set -euo pipefail

REPO_SLUG="xD3I/PZ_Optimization"
WORKSHOP_ID="3805285544"
mode=install; zip=""; from=""; dir="${PZ_DIR:-}"; tag=""; force=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --zip) zip="$2"; shift ;;
    --from) from="$2"; shift ;;
    --dir) dir="$2"; shift ;;
    --tag) tag="$2"; shift ;;
    --uninstall) mode=uninstall ;;
    --status) mode=status ;;
    --force) force=1 ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

die() { echo "error: $*" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }  # macOS has shasum only

# --- locate the game ----------------------------------------------------------------------

candidates() {
  local lib
  for vdf in "$HOME/.local/share/Steam/steamapps/libraryfolders.vdf" "$HOME/.steam/root/steamapps/libraryfolders.vdf" \
             "$HOME/.steam/steam/steamapps/libraryfolders.vdf" "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/libraryfolders.vdf" \
             "$HOME/Library/Application Support/Steam/steamapps/libraryfolders.vdf"; do
    [[ -f "$vdf" ]] || continue
    sed -n 's/^[[:space:]]*"path"[[:space:]]*"\(.*\)"/\1/p' "$vdf" | while IFS= read -r lib; do
      echo "$lib/steamapps/common/ProjectZomboid/projectzomboid"
      echo "$lib/steamapps/common/ProjectZomboid"
      echo "$lib/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java"
    done
  done
  echo "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid"
  echo "$HOME/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid"
  echo "$HOME/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java"
}
# Linux/Windows depots: the launcher JSON next to the jar; macOS: the jar is Contents/Java of the .app bundle
is_game_dir() { [[ -f "$1/projectzomboid.jar" ]] && { [[ -f "$1/ProjectZomboid64.json" || -f "$1/../Info.plist" ]]; }; }

if [[ -z "$dir" ]]; then
  while IFS= read -r c; do
    is_game_dir "$c" && { dir="$c"; break; }
  done < <(candidates)
  [[ -n "$dir" ]] || die "game folder not found; pass --dir <folder containing projectzomboid.jar>"
fi
[[ -f "$dir/projectzomboid.jar" ]] || die "no projectzomboid.jar in $dir"
dir=$(cd "$dir" && pwd)   # one spelling (".../projectzomboid/." from the uninstaller), so the running-game check matches
JAR="$dir/projectzomboid.jar"
JSON="$dir/ProjectZomboid64.json"
MANIFEST="$dir/pzopt-installed.txt"
MAC_LAUNCHER="$dir/../MacOS/JavaAppLauncher"   # the .app bundle's launcher (macOS depot)

jar_revision() {
  # zombie.GitVersion holds REVISION as a constant-pool string; no JDK needed to read it
  if command -v unzip >/dev/null; then unzip -p "$JAR" zombie/GitVersion.class
  else python3 -c 'import zipfile,sys; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read("zombie/GitVersion.class"))' "$JAR"
  fi | grep -aoE '\b[0-9a-f]{10}\b' | head -1
}
REV=$(jar_revision || true)
ZOMBOID_PZOPT="$HOME/Zomboid/pzopt"
SHORTCUTS="Uninstall-PZ-Optimization.cmd
uninstall-pz-optimization.bash"

# What the game itself left about an earlier install (an unfinished in-game uninstall, the boot repair's note for the
# Workshop item's install helper) no longer applies once this script has installed or removed one.
clear_game_notes() {
  rm -f "$ZOMBOID_PZOPT/uninstall-files.txt" "$ZOMBOID_PZOPT/uninstall-dirs.txt" "$HOME/Zomboid/Lua/pzopt-boot-repair.txt"
}

# Is a file in the game folder PZ Optimization's? A path with "pzopt" in it (the package, the Lua, the shaders, the
# lists), a class whose bytes name the pzopt package (every override that calls it), or an inner class of one. Other
# Java mods' class files (Better Vehicle Dynamics ships zombie/iso/IsoChunkMap.class) are not. pzopt.properties is the
# player's own settings file and stays.
names_pzopt() { LC_ALL=C grep -qa 'pzopt/' "$1" 2>/dev/null; }
is_ours() {
  local rel="$1" base outer
  [[ "$rel" == pzopt.properties ]] && return 1
  grep -qxF -- "$rel" <<< "$SHORTCUTS" && return 0
  [[ "/$rel" =~ /[^/]*pzopt ]] && return 0
  [[ "$rel" == *.class ]] || return 1
  names_pzopt "$dir/$rel" && return 0
  base=$(basename "$rel")
  if [[ "$base" == *'$'* ]]; then
    outer="$(dirname "$dir/$rel")/${base%%\$*}.class"
    [[ -f "$outer" ]] && names_pzopt "$outer" && return 0
  fi
  return 1
}

# A launcher JSON that pzopt's AOT-cache mode (pzopt.AotCache) switched to its jar form goes back to the loose
# classes ("." first, no AOT options), and the jar and cache go: the loose files are about to change.
reset_aot() {
  # The game is closed here. A launcher edit pzopt staged while it ran (ProjectZomboid64.json.pzopt-pending, Windows: the
  # running game holds the JSON) or a leftover .pzopt-tmp goes first, so neither can land over this reset afterwards.
  rm -f -- "$JSON.pzopt-pending" "$JSON.pzopt-tmp"
  if [[ -f "$JSON" ]]; then
    if command -v python3 >/dev/null; then
      python3 - "$JSON" <<'PYEOF'
import json,sys
p=sys.argv[1]; j=json.load(open(p)); jar="pzopt/aot/pzopt.jar"
cp=j.get("classpath",[]); args=j.get("vmArgs",[])
aot=[a for a in args if a.startswith("-XX:AOTCache") or a.startswith("-Xlog:aot=info:file=pzopt/aot/")]
if jar in cp or aot:
    j["classpath"]=["."]+[e for e in cp if e not in (".",jar)]
    j["vmArgs"]=[a for a in args if a not in aot]
    json.dump(j,open(p,"w"),indent="\t"); print("launcher: AOT-cache form put back to the loose classes")
PYEOF
    elif [[ -f "$JSON.pzopt-backup" ]] && grep -q 'pzopt/aot/' "$JSON"; then
      cp "$JSON.pzopt-backup" "$JSON"; echo "launcher: restored $JSON.pzopt-backup"
    fi
  fi
  rm -rf "$dir/pzopt/aot"
}

reset_gc() {  # undo pzopt.GcChoice's launcher edits (-Dpzopt.gc=g1 marker: G1 back to ZGC, our pause target removed; -Dpzopt.jit=steady: our JIT flags removed; -Dpzopt.heap=: the old -Xmx / -Xms back, our pre-touch removed)
  [[ -f "$1" ]] && command -v python3 >/dev/null || return 0
  python3 - "$1" <<'PYEOF'
import json,sys
p=sys.argv[1]; j=json.load(open(p)); ch=[False]
M,MP="-Dpzopt.gc=g1","-Dpzopt.gc=g1,pause"
def fix(a):
    if M not in a and MP not in a: return a
    if MP in a: a=[x for x in a if not x.startswith("-XX:MaxGCPauseMillis=")]
    a=[x for x in a if x not in (M,MP)]
    ch[0]=True; return ["-XX:+UseZGC" if x=="-XX:+UseG1GC" else x for x in a]
J="-Dpzopt.jit=steady"; JP=("-XX:PerMethodTrapLimit=","-XX:PerBytecodeTrapLimit=")
def fixj(a):
    if J not in a: return a
    ch[0]=True; return [x for x in a if x!=J and not x.startswith(JP)]
H="-Dpzopt.heap="
def fixh(a):
    m=[x for x in a if x.startswith(H)]
    if not m: return a
    old=(m[-1][len(H):].split(",")+["none","none","0"])[:3]
    a=[x for x in a if not x.startswith(H)]
    for f,o in (("-Xmx",old[0]),("-Xms",old[1])):
        i=max([k for k,x in enumerate(a) if x.startswith(f)],default=-1)
        if o=="none": a=[x for k,x in enumerate(a) if k!=i]
        elif i>=0: a[i]=f+o
        else: a.append(f+o)
    if old[2]=="1" and "-XX:+AlwaysPreTouch" in a: a.remove("-XX:+AlwaysPreTouch")
    ch[0]=True; return a
def walk(o):  # every vmArgs array at any depth: the switch lands where ZGC was, on Windows in windows."10.0.17134"; each undo needs its marker
    if isinstance(o,dict):
        if isinstance(o.get("vmArgs"),list): o["vmArgs"]=fixh(fixj(fix(o["vmArgs"])))
        for k,v in o.items():
            if k!="vmArgs": walk(v)
walk(j)
if ch[0]: json.dump(j,open(p,"w"),indent="\t"); print("launcher: pzopt's G1 switch / JIT flags / heap size undone (back to the launcher's own)")
PYEOF
}

game_running() {
  local pid
  for pid in $(pgrep -f '[P]rojectZomboid64' || true); do
    [[ "$(readlink -f "/proc/$pid/cwd" 2>/dev/null)" == "$(readlink -f "$dir")" ]] && return 0
  done
  if [[ -x "$MAC_LAUNCHER" ]] && pgrep -f "$(cd "$dir/.." && pwd)/MacOS/JavaAppLauncher" >/dev/null 2>&1; then return 0; fi
  # a JVM started some other way (java ... zombie.gameStates.MainScreenState) with the game folder as its working dir;
  # macOS has no /proc: lsof names the cwd
  for pid in $(pgrep -f 'zombie[.]gameStates[.]MainScreenState' || true); do
    local cwd
    if [[ -e "/proc/$pid/cwd" ]]; then cwd=$(readlink -f "/proc/$pid/cwd" 2>/dev/null)
    else cwd=$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1); fi
    [[ -n "$cwd" && "$(cd "$cwd" 2>/dev/null && pwd -P)" == "$(cd "$dir" && pwd -P)" ]] && return 0
  done
  return 1
}
# The files must not change under a running game: wait for it (the in-game helper's flow is paste, then quit)
wait_game_closed() {
  game_running || return 0
  echo "the game is running from $dir: quit it (QUIT in the main menu); this goes on once it has closed (Ctrl+C cancels)"
  while game_running; do sleep 1; done
  sleep 1
}

# The newest copy of the Steam Workshop item for this game revision: Steam keeps an app's Workshop content in the
# library of the app itself, <library>/steamapps/workshop/content/108600/<item>/mods/PZ_Optimization/<version>.
# Complete only when every file its pzopt-files.txt lists is there (Steam replaces an item's files one by one).
workshop_copy() {  # $1 = any: a copy for any game revision
  local d v c info built best="" bestbuilt=-1 rel complete any="${1:-}"
  d=$(cd "$dir" && pwd -P)
  while [[ -n "$d" && "$d" != "/" && "$(basename "$d")" != steamapps ]]; do d=$(dirname "$d"); done
  [[ "$(basename "$d")" == steamapps ]] || return 0
  for v in "$d/workshop/content/108600/$WORKSHOP_ID/mods/PZ_Optimization"/*/; do
    c="${v%/}/pzopt-classes"
    info="$c/pzopt/build-info.properties"
    [[ -f "$info" && -f "$c/pzopt-files.txt" ]] || continue
    [[ -n "$any" || "$(sed -n 's/^revision=//p' "$info")" == "$REV" ]] || continue
    complete=1
    while IFS= read -r rel; do
      [[ -z "$rel" || -f "$c/$rel" ]] || { complete=0; break; }
    done < "$c/pzopt-files.txt"
    if [[ $complete -eq 0 ]]; then echo "skipping $c: Steam is still updating it" >&2; continue; fi
    built=$(sed -n 's/^built=//p' "$info")
    [[ "$built" =~ ^[0-9]+$ ]] || built=0
    if (( built > bestbuilt )); then best="$c"; bestbuilt=$built; fi
  done
  if [[ -n "$best" ]]; then echo "$best"; fi
  return 0
}

# --- status / uninstall -------------------------------------------------------------------

if [[ $mode == status ]]; then
  echo "game dir:      $dir"
  echo "game revision: ${REV:-unknown}"
  if [[ -f "$MANIFEST" ]]; then
    echo "installed:     yes, for $(sed -n 's/^# revision=\([^ ]*\).*/\1/p' "$MANIFEST") ($(grep -vc '^#' "$MANIFEST") files)"
    bad=0
    while read -r rel sha; do
      [[ "$rel" == \#* || -z "$rel" ]] && continue
      if [[ ! -f "$dir/$rel" ]]; then echo "  MISSING  $rel"; bad=1
      elif [[ "$(sha256 "$dir/$rel")" != "$sha" ]]; then echo "  MODIFIED $rel"; bad=1; fi
    done < "$MANIFEST"
    [[ $bad -eq 0 ]] && echo "  all files present and unchanged"
  else
    echo "installed:     no"
  fi
  [[ -f "$dir/pzopt.properties" ]] && { echo "pzopt.properties:"; sed 's/^/  /' "$dir/pzopt.properties"; }
  exit 0
fi

# Removes an install: the files of the manifest (or of a hand-unpacked zip's pzopt-files.txt), the folders they leave,
# the launcher edits. Used by --uninstall and by an install that finds a previous one (a build for an older game
# revision crashes the updated game at start, so the install replaces it instead of refusing).
remove_install() {
  reset_aot
  reset_gc "$JSON"
  local list="" n=0 rel d
  if [[ -f "$MANIFEST" ]]; then list=$(grep -v '^#' "$MANIFEST" | cut -d' ' -f1)
  elif [[ -f "$dir/pzopt-files.txt" ]]; then list=$(cat "$dir/pzopt-files.txt")
  else
    list=$(find_leftovers)
    [[ -n "$list" ]] || return 1
    echo "no list of installed files in $dir (an install that stopped early, or files copied by hand): removing the $(grep -c . <<< "$list") files that are PZ Optimization's"
  fi
  while IFS= read -r rel; do [[ -e "$dir/$rel" ]] && list+=$'\n'"$rel"; done <<< "$SHORTCUTS"
  # the overrides first, the pzopt package they call last: a removal cut short never leaves an override without it
  list=$(grep -v '^pzopt/' <<< "$list" || true; grep '^pzopt/' <<< "$list" || true)
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    [[ -f "$dir/$rel" ]] && { rm -f "$dir/$rel"; n=$((n+1)); }
    d=$(dirname "$rel")
    while [[ "$d" != "." && -d "$dir/$d" && -z "$(ls -A "$dir/$d")" ]]; do rmdir "$dir/$d"; d=$(dirname "$d"); done
  done <<< "$list"
  rm -f "$MANIFEST" "$dir/pzopt-files.txt"
  clear_game_notes
  echo "removed $n files; projectzomboid.jar was never modified"
}

# No manifest and no pzopt-files.txt: the files that are PZ Optimization's by is_ours, among the loose class folders,
# every path with "pzopt" in it, and the files of the Steam Workshop copy's list that are byte-identical to it (overrides
# that never name the pzopt package, e.g. zombie/FliesSound.class, only when something of ours is there too). The game's own classes are in the jar.
find_leftovers() {
  local top rel found="" copy
  for top in zombie org se fmod pzopt media natives; do
    [[ -d "$dir/$top" ]] || continue
    while IFS= read -r rel; do
      [[ -z "$rel" ]] && continue
      [[ "$top" == media && "$rel" != *pzopt* ]] && continue
      is_ours "$rel" && found+="$rel"$'\n'
    done < <(cd "$dir" && find "$top" -type f)
  done
  if [[ -n "$found" ]]; then
    copy=$(workshop_copy any)
    if [[ -n "$copy" ]]; then
      while IFS= read -r rel; do
        # only a byte-identical copy: a class of the same name that differs may be another mod's
        [[ -n "$rel" && -f "$dir/$rel" ]] && cmp -s "$copy/$rel" "$dir/$rel" && ! grep -qxF -- "$rel" <<< "$found" && found+="$rel"$'\n'
      done < "$copy/pzopt-files.txt"
    fi
  fi
  printf '%s' "$found"
}

if [[ $mode == uninstall ]]; then
  wait_game_closed
  remove_install || { clear_game_notes; echo "PZ Optimization is not installed in $dir (nothing of it found there)"; exit 0; }
  echo "PZ Optimization is uninstalled: the next launch is the stock game. You can unsubscribe from the Workshop item now."
  echo "caches under ~/Zomboid/pzopt/ (anims, packs, framecap.ini, options.ini) can be deleted by hand"
  exit 0
fi

# --- install ------------------------------------------------------------------------------

wait_game_closed
[[ -n "$REV" ]] || die "could not read the game revision from $JAR"
if [[ -f "$MANIFEST" || -f "$dir/pzopt-files.txt" ]]; then
  old_rev=$(sed -n 's/^# revision=\([^ ]*\).*/\1/p' "$MANIFEST" 2>/dev/null)
  echo "replacing the installed build (for game revision ${old_rev:-unknown}; this game is $REV)"
  remove_install
fi

reset_aot
# the launcher must search "." before the jar or loose classes never load
if [[ ! -f "$JSON" && -x "$MAC_LAUNCHER" ]]; then
  : # macOS: JavaAppLauncher builds -Djava.class.path=<Contents/Java>/ and appends the jars after it (verified 42.20.4)
elif command -v python3 >/dev/null; then
  python3 - "$JSON" <<'EOF' || die "$JSON does not list \".\" before projectzomboid.jar on the classpath; loose classes would never load"
import json,sys
cp=json.load(open(sys.argv[1])).get("classpath",[])
sys.exit(0 if "." in cp and "projectzomboid.jar" in cp and cp.index(".") < cp.index("projectzomboid.jar") else 1)
EOF
else
  tr -d '\n ' < "$JSON" | grep -q '"classpath":\[".","projectzomboid.jar"' || die "$JSON classpath does not put \".\" before projectzomboid.jar"
fi

# ${BASH_SOURCE[0]:-}: empty when the script comes through a pipe (curl ... | bash)
if [[ -z "$zip" && -z "$from" && -f "${BASH_SOURCE[0]:-}" ]]; then
  sibling="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pzopt-classes"
  [[ -f "$sibling/pzopt/build-info.properties" ]] && from="$sibling"
fi
if [[ -z "$zip" && -z "$from" && -z "$tag" ]]; then
  from=$(workshop_copy)
  if [[ -n "$from" ]]; then echo "found the Steam Workshop copy for revision $REV"; fi
fi
if [[ -n "$from" ]]; then
  [[ -f "$from/pzopt/build-info.properties" ]] || die "$from is not an unpacked PZ_Optimization release (no pzopt/build-info.properties)"
  echo "installing from folder $from"
elif [[ -z "$zip" ]]; then
  pattern="pzopt-${REV}-classes.zip"
  tmp=$(mktemp -d)
  if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
    if [[ -z "$tag" ]]; then
      # newest published first: the API's own order is by the tagged commit, not by publish date
      tag=$(gh release list -R "$REPO_SLUG" --json tagName,publishedAt -q 'sort_by(.publishedAt) | reverse | .[].tagName' | grep -- "-${REV}-\|-${REV}\$" | head -1 || true)
      [[ -n "$tag" ]] || die "no release for game revision $REV (your game is a build these classes were not built for)"
    fi
    echo "downloading $pattern from release $tag"
    gh release download "$tag" -R "$REPO_SLUG" -p "$pattern" -D "$tmp"
  else
    command -v curl >/dev/null || die "need curl (or the gh CLI) to download; or pass --zip"
    auth=(); [[ -n "${GITHUB_TOKEN:-}" ]] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
    api="https://api.github.com/repos/$REPO_SLUG/releases"
    # ${auth[@]+...}: bash 3.2 (macOS) treats an empty array as unbound under set -u
    # the list's order is by the tagged commit's date, not by publish date: /releases/latest first, then the
    # list sorted by published_at (the no-python3 scanner takes the list as it comes)
    latest=$(curl -fsSL ${auth[@]+"${auth[@]}"} -H "Accept: application/vnd.github+json" "$api/latest" 2>/dev/null || true)
    rels=$(curl -fsSL ${auth[@]+"${auth[@]}"} -H "Accept: application/vnd.github+json" "$api?per_page=50") || die "could not list releases of $REPO_SLUG"
    [[ -n "$latest" ]] && rels="[$latest,${rels#[}"
    if command -v python3 >/dev/null; then
      found=$(python3 - "$rels" "$pattern" "$tag" <<'EOF2'
import json,sys
rels,pattern,tag=json.loads(sys.argv[1]),sys.argv[2],sys.argv[3]
rels=[rels[0]]+sorted(rels[1:],key=lambda r:r.get("published_at") or "",reverse=True)
for r in rels:
    if tag and r["tag_name"]!=tag: continue
    for a in r["assets"]:
        if a["name"]==pattern: print(r["tag_name"], a["url"]); sys.exit(0)
sys.exit(1)
EOF2
) || die "no release has $pattern (your game revision $REV is a build these classes were not built for)"
    else
      # no python3 (a Mac without the Command Line Tools): each asset object starts with its API url and
      # carries its name a few fields later; the release's tag_name precedes its assets array
      found=$(printf '%s' "$rels" | tr -d '\n ' | grep -oE '"tag_name":"[^"]*"|"url":"[^"]*/releases/assets/[0-9]+","id":[0-9]+,"node_id":"[^"]*","name":"[^"]*"' \
        | awk -v pat="$pattern" -v want="$tag" -F'"' '/^"tag_name"/ {t=$4; next} $NF=="" && $(NF-1)==pat && (want=="" || t==want) {print t, $4; exit}')
      [[ -n "$found" ]] || die "no release has $pattern (your game revision $REV is a build these classes were not built for)"
    fi
    tag=${found%% *}; url=${found#* }
    echo "downloading $pattern from release $tag"
    curl -fsSL ${auth[@]+"${auth[@]}"} -H "Accept: application/octet-stream" -o "$tmp/$pattern" "$url"
  fi
  zip="$tmp/$pattern"
fi
[[ -n "$from" || -f "$zip" ]] || die "zip not found: $zip"

list_zip() {
  if command -v unzip >/dev/null; then unzip -Z1 "$1"
  else python3 -c 'import zipfile,sys; print("\n".join(n for n in zipfile.ZipFile(sys.argv[1]).namelist() if not n.endswith("/")))' "$1"; fi
}
# the pzopt package first, then the overrides that call it (each pass skips what exists)
extract_zip() {
  if command -v unzip >/dev/null; then unzip -q -n "$1" 'pzopt/*' -d "$2" && unzip -q -n "$1" -d "$2"
  elif command -v bsdtar >/dev/null; then bsdtar -xkf "$1" -C "$2" 'pzopt/*' && bsdtar -xkf "$1" -C "$2"
  else python3 -c 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); n=z.namelist(); [z.extract(e, sys.argv[2]) for e in [e for e in n if e.startswith("pzopt/")] + [e for e in n if not e.startswith("pzopt/")]]' "$1" "$2"; fi
}

read_zip_entry() {
  if command -v unzip >/dev/null; then unzip -p "$1" "$2"
  else python3 -c 'import zipfile,sys; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))' "$1" "$2"; fi
}
# the same three operations on a folder: list, read one entry, copy without overwriting
list_dir() { (cd "$1" && find . -type f | sed 's#^\./##'); }
copy_dir() {
  local rel
  while IFS= read -r rel; do
    [[ -z "$rel" ]] && continue
    mkdir -p "$2/$(dirname "$rel")"
    cp "$1/$rel" "$2/$rel" || return 1
  done < <(list_dir "$1" | grep '^pzopt/' || true; list_dir "$1" | grep -v '^pzopt/' || true)
}

if [[ -n "$from" ]]; then
  src="$from"
  files=$(list_dir "$from" | LC_ALL=C sort)
  zip_rev=$(sed -n 's/^revision=//p' "$from/pzopt/build-info.properties")
else
  src="$zip"
  files=$(list_zip "$zip" | grep -v '/$' | LC_ALL=C sort)
  echo "$files" | grep -qx 'pzopt/build-info.properties' || die "$zip is not a PZ_Optimization release zip"
  zip_rev=$(read_zip_entry "$zip" pzopt/build-info.properties | sed -n 's/^revision=//p')
fi
if [[ "$zip_rev" != "$REV" ]]; then
  die "$src was built for game revision $zip_rev but this game is $REV; the classes would disable themselves. Get the build for $REV"
fi
# No manifest is left at this point, so a release file already in the folder is either the remains of an install that
# stopped before writing one (the game keeps its own classes in the jar), replaced (refusing left no way out: --uninstall
# found nothing to remove, and the game crashed on an override whose pzopt classes were missing), or another Java mod's
# copy of a class we replace too (Better Vehicle Dynamics: zombie/iso/IsoChunkMap.class), refused unless --force. What
# is not recognisably ours is moved to ~/Zomboid/pzopt/replaced-files/<time>/ first.
ours=""; foreign=""
while IFS= read -r rel; do
  [[ -n "$rel" && -e "$dir/$rel" ]] || continue
  if is_ours "$rel"; then ours+="$rel"$'\n'; else foreign+="$rel"$'\n'; fi
done <<< "$files"
backup="$ZOMBOID_PZOPT/replaced-files/$(date +%Y%m%d-%H%M%S)"
if [[ -n "$foreign" && -z "$ours" && $force -eq 0 ]]; then
  die "$(grep -c . <<< "$foreign") game classes this release replaces are already in $dir and are not PZ Optimization's, e.g. $(head -3 <<< "$foreign" | paste -sd, -).
Another Java mod put them there (Better Vehicle Dynamics ships zombie/iso/IsoChunkMap.class, for one); two mods cannot both replace the same class. Remove that mod's files, or run the installer again with --force: they are moved to $backup first."
fi
while IFS= read -r rel; do
  [[ -z "$rel" ]] && continue
  mkdir -p "$backup/$(dirname "$rel")" && mv -f "$dir/$rel" "$backup/$rel"
done <<< "$foreign"
[[ -n "$foreign" ]] && echo "moved $(grep -c . <<< "$foreign") files that were not PZ Optimization's to $backup"
[[ -n "$ours" ]] && echo "replacing $(grep -c . <<< "$ours") files an unfinished install left behind"
while IFS= read -r rel; do [[ -n "$rel" ]] && rm -f "$dir/$rel"; done <<< "$ours"

jar_before=$(sha256 "$JAR")
# The manifest goes first, so an install cut short is still replaced by the next run or removed by --uninstall.
stamp="# revision=$zip_rev installed=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  echo "# files written by install.sh — do not edit"
  echo "$stamp"
  echo "# unfinished: the install stopped before the end; run the installer again"
  while IFS= read -r rel; do echo "$rel -"; done <<< "$files"
} > "$MANIFEST"
if [[ -n "$from" ]]; then copy_dir "$from" "$dir"; else extract_zip "$zip" "$dir"; fi \
  || die "the install stopped before the end; run the installer again (it replaces the unfinished install) or with --uninstall"
{
  echo "# files written by install.sh — do not edit"
  echo "$stamp"
  while IFS= read -r rel; do echo "$rel $(sha256 "$dir/$rel")"; done <<< "$files"
} > "$MANIFEST"
jar_after=$(sha256 "$JAR")
[[ "$jar_before" == "$jar_after" ]] || die "projectzomboid.jar changed during install (this should be impossible)"
[[ -n "${tmp:-}" ]] && rm -rf "$tmp"

clear_game_notes
echo "installed $(echo "$files" | wc -l | tr -d ' ') files into $dir for game revision $zip_rev; projectzomboid.jar untouched"
echo "launch from Steam; ~/Zomboid/console.txt shows one '[pzopt] loaded override ... active' line per class"
echo "settings: Options > PZ Optimization in the game, or $dir/pzopt.properties"
echo "to remove it: Options > PZ Optimization > Uninstall PZ Optimization, or: bash \"$dir/uninstall-pz-optimization.bash\""
