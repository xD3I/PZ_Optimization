#!/usr/bin/env bash
# Stage the Steam Workshop item (pure distribution, BetterFPS-style): the release zip
# unpacked under a mod folder plus the two installers, so Steam downloads the files and the
# player runs the installer (or copies the tree by hand). The game loads nothing of the
# optimizations from the item; its only media/ file is the install helper
# (src/workshop/42/media/lua/client/): enabled in the Mods list, it shows the install command for
# that computer with a Copy button at the main menu.
#
#   scripts/workshop.sh                       # scripts/release.sh (build + test + zip), then stage
#   scripts/workshop.sh --tag b42.21-20260928-2114-aa5b92d   # stage the asset of that GitHub release (exact mirror)
#   scripts/workshop.sh --zip build/pzopt-b0bbce05d5-classes.zip [--commit cc99c05]
#   scripts/workshop.sh --out /tmp/ws         # somewhere other than ~/Zomboid/Workshop/PZ_Optimization
#   scripts/workshop.sh --tag b<version>-<yyyymmdd>-<hhmm>-<commit> --upload "Release <commit> (Build <version>, game revision <rev>). ..."
#
# With --tag, a successful --upload is then announced on Discord (scripts/discord-announce.py).
# --upload stages, then uploads with scripts/workshop-upload.py: the Steamworks API through the
# game's libsteam_api.so and the running, logged-on Steam client, a few seconds, no game launch.
# The item id comes from workshop.txt (docs/workshop/workshop.txt); only the very first upload of a
# new item needed the game (Main menu > Workshop > Create/Update item), which wrote the id back.
# See docs/workshop.md.
#
# The uploader's validator (zombie.core.znet.SteamWorkshopItem) refuses anything but
# mods/ buildings/ creative/ under Contents/, any file named *.exe *.dll *.bat *.app *.dylib
# *.sh *.so *.zip, and a preview.png over 1 MB. Hence install.sh ships as install.bash and
# the zip is unpacked.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
source scripts/pz-env.sh

zip=""; tag=""; commit=""; out="$ZOMBOID/Workshop/PZ_Optimization"; upload=0; notes=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --zip) zip="$2"; shift ;;
    --tag) tag="$2"; shift ;;
    --commit) commit="$2"; shift ;;
    --out) out="$2"; shift ;;
    --upload) upload=1; notes="$2"; shift ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

if [[ -n "$tag" ]]; then
  # the published bytes: gh release download of the zip asset; the tag ends in the commit
  command -v gh >/dev/null || { echo "gh not on PATH" >&2; exit 1; }
  mkdir -p "build/workshop/$tag"
  gh release download "$tag" -p 'pzopt-*-classes.zip' -D "build/workshop/$tag" --clobber
  zip=$(ls "build/workshop/$tag"/pzopt-*-classes.zip | head -1)
  commit="${commit:-${tag##*-}}"
elif [[ -z "$zip" ]]; then
  scripts/release.sh
  zip=$(ls -t build/pzopt-*-classes.zip | head -1)
fi
[[ -f "$zip" ]] || { echo "zip not found: $zip" >&2; exit 1; }

rev=$(unzip -p "$zip" pzopt/build-info.properties | sed -n 's/^revision=//p')
# build-info has no commit; without --tag/--commit assume the zip is from HEAD (what release.sh tags)
commit="${commit:-$(git rev-parse --short HEAD 2>/dev/null || true)}"
[[ -n "$rev" ]] || { echo "$zip has no pzopt/build-info.properties" >&2; exit 1; }
version=$(sed -n 's/.*Build \(42\.[0-9.]*\).*/\1/p' docs/windows-test.md | head -1)
# a tagged release names its own game build: the tag starts with it (b<version>-<yyyymmdd>-<hhmm>-<commit>)
if [[ "$tag" =~ ^b(42\.[0-9.]+)- ]]; then
  version="${BASH_REMATCH[1]}"
fi
nfiles=$(unzip -Z1 "$zip" | grep -vc '/$')
noverrides=$(unzip -p "$zip" pzopt/build-info.properties | sed -n 's/^overrides=//p' | tr ',' '\n' | grep -c .)
sha=$(sha256sum "$zip" | cut -d' ' -f1)

MOD="$out/Contents/mods/PZ_Optimization/42"
CLASSES="$MOD/pzopt-classes"
mkdir -p "$out/Contents/mods/PZ_Optimization"
rm -rf "$MOD"
mkdir -p "$CLASSES"
unzip -q "$zip" -d "$CLASSES"

# installers: same bytes as the GitHub release assets; .sh is a banned extension
cp install.ps1 "$MOD/install.ps1"
cp install.sh "$MOD/install.bash"
chmod +x "$MOD/install.bash"

# the install helper, the item's only Lua (the pzopt Lua files under pzopt-classes/ reach the game with the classes)
cp -r src/workshop/42/. "$MOD/"
# The install helper uses the same translations before the game-side files are installed.
mkdir -p "$MOD/media/lua/shared/pzopt"
cp src/lua/shared/pzopt/pzopt_text*.lua "$MOD/media/lua/shared/pzopt/"
cp -r src/lua/shared/Translate "$MOD/media/lua/shared/"

# mod.info: id, name, description lines (<LINE> is the in-game line break)
{
  echo "name=PZ_Optimization (class overrides, manual install)"
  echo "id=PZ_Optimization"
  echo "modversion=${commit:-$rev}"
  echo "versionMin=${version:-42.20.0}"
  echo "author=xD3I"
  # the Mods screen draws poster 0 next to the description and the rest as thumbnails (hover = large): the three
  # steps first, then the five walkthrough pictures (harness/install-walkthrough.py --posters, src/workshop/42/),
  # then the showcase square
  for p in poster-install.png poster-step1.png poster-step2.png poster-step3.png poster-step4.png poster-step5.png; do
    [[ -f "$MOD/$p" ]] && echo "poster=$p"
  done
  echo "poster=poster.png"
  echo "description=Class files for the game folder, not a Lua mod: enable this mod once and the main menu shows the install command for your computer with a Copy button; after installing, disable it again. <LINE> Or run install.ps1 (Windows) or install.bash (Linux, macOS) from this item's folder under steamapps/workshop/content/108600/. <LINE> To remove it: Options > Optimizations > Uninstall PZ Optimization, before you unsubscribe. <LINE> Built for game revision $rev${version:+ (Build $version)}. Source and releases: github.com/xD3I/PZ_Optimization"
} > "$MOD/mod.info"

# images: preview.png for the Workshop page (square, <= 1 MB), poster.png for the mod list
src_img=docs/workshop/images/00-showcase-thumbnail.jpg     # "PZ Optimized / 632 fps" (harness/showcase-thumbnail.py, THUMB_ONLY_OPT=1)
[[ -f "$src_img" ]] || src_img=docs/media/showcase-stock-vs-all-optimizations-thumbnail.jpg
if [[ -f "$src_img" ]] && command -v ffmpeg >/dev/null; then
  ffmpeg -loglevel error -y -i "$src_img" -vf "crop='min(iw,ih)':'min(iw,ih)',scale=512:512" "$out/preview.png"
  ffmpeg -loglevel error -y -i "$src_img" -vf "crop='min(iw,ih)':'min(iw,ih)',scale=256:256" "$MOD/poster.png"
else
  echo "warning: no $src_img or no ffmpeg; put a square preview.png in $out and poster.png in $MOD" >&2
fi

# the animated preview (harness/showcase-thumbnail-gif.py); workshop-upload.py sends it when it is
# <= 1,000,000 bytes (the in-game uploader could only send preview.png)
[[ -f docs/workshop/images/00-showcase-thumbnail.gif ]] && cp docs/workshop/images/00-showcase-thumbnail.gif "$out/preview.gif"

# workshop.txt: keep the id= of an earlier upload (the game writes it back after the first one)
id=""
for f in "$out/workshop.txt" docs/workshop/workshop.txt; do
  [[ -f "$f" ]] && id=$(sed -n 's/^id=//p' "$f" | head -1) && [[ -n "$id" ]] && break
done
{
  echo "version=1"
  echo "id=$id"
  echo "title=PZ_Optimization - FPS Boost & Performance Fix: Less Stutter, Less Lag, Faster Chunk Loading [B42] (manual install)"
  # the page body; the game joins description= lines with newlines. The image URLs go out as da.gd links
  # (scripts/workshop-shorten.py, cache docs/workshop/short-urls.txt): ~80 page characters saved per image
  sed "s/@REV@/$rev/g; s/@VERSION@/${version:-42.20.x}/g; s/@COMMIT@/${commit:-?}/g; s/@NFILES@/$nfiles/g; s/@NOVERRIDES@/$noverrides/g; s/@SHA@/$sha/g; s/@ID@/${id:-<item id>}/g" docs/workshop/description.txt \
    | python3 scripts/workshop-shorten.py --length | sed 's/^/description=/'
  echo "tags=Build 42;"
  echo "visibility=public"
} > "$out/workshop.txt"

# Steam's limit is 8,000 characters and the game appends ~50 (the Workshop ID / Mod ID lines)
plen=$(sed -n 's/^description=//p' "$out/workshop.txt" | python3 -c 'import sys; print(len(sys.stdin.read().rstrip("\n")))')
(( plen <= 7900 )) || { echo "the page is $plen characters, keep it at or under 7,900 (docs/workshop/description.txt)" >&2; exit 1; }

# the validator's rules, checked here so the in-game screen does not have to say no
bad=$(find "$out/Contents" -type f \( -name '*.exe' -o -name '*.dll' -o -name '*.bat' -o -name '*.app' -o -name '*.dylib' -o -name '*.sh' -o -name '*.so' -o -name '*.zip' \) | grep -v 'pyramid\.zip$' || true)
[[ -z "$bad" ]] || { echo "banned file types in Contents/:" >&2; echo "$bad" >&2; exit 1; }
stray=$(find "$out/Contents" -mindepth 1 -maxdepth 1 ! -name mods || true)
[[ -z "$stray" ]] || { echo "only mods/ is allowed directly under Contents/: $stray" >&2; exit 1; }
if [[ -f "$out/preview.png" ]]; then
  psz=$(stat -c %s "$out/preview.png")
  [[ $psz -le 1024000 ]] || { echo "preview.png is $psz bytes, the limit is 1024000" >&2; exit 1; }
fi

rm -f "$out/item.vdf"   # the old steamcmd route for the GIF; workshop-upload.py sends it now

echo "staged $out"
echo "  classes: $CLASSES ($nfiles files, revision $rev, from $zip)"
echo "  id: ${id:-<none yet; the first in-game upload writes it into workshop.txt>}"
if (( upload )); then
  [[ -n "$id" ]] || { echo "no item id in workshop.txt: the first upload of a new item goes through the game" >&2; exit 1; }
  # every change note names the game build: "Release <commit> (Build <version>, game revision <rev>). ..."
  if [[ -n "$version" && "$notes" != *"Build $version"* ]]; then
    if [[ "$notes" == *"(game revision $rev)"* ]]; then
      notes="${notes/"(game revision $rev)"/"(Build $version, game revision $rev)"}"
    else
      notes="Build $version (game revision $rev). $notes"
    fi
  fi
  echo "  notes: ${notes:0:100}..."
  python3 scripts/workshop-upload.py --dir "$out" --notes "$notes"
  # every release is announced on Discord once it is on the Workshop (a failed post does not undo the upload)
  if [[ -n "$tag" ]]; then
    python3 scripts/discord-announce.py --tag "$tag" ||
      echo "discord announce failed; post it with: scripts/discord-announce.py --tag $tag" >&2
  fi
else
  echo "next: scripts/workshop-upload.py --dir $out --notes \"<change notes>\" (Steam running and logged on; no game)"
fi
