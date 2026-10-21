#!/usr/bin/env bash
# Vendor javif (https://github.com/Glavo/javif, pure-Java AV1 / AVIF decoder, MPL-2.0) into src/vendor/ for the menu's
# AVIF clips (pzopt.ClipDecoder, docs/findings-clip-format-2026-10-20.md). build.sh compiles src/vendor with our classes.
#
# Mechanical changes only (the files stay MPL-2.0, their headers are kept, src/vendor/javif/README.md lists the changes):
#   - package org.glavo.avif -> pzopt.vendor.avif (a mod shipping another javif must not clash with ours on the
#     game's classpath)
#   - the JetBrains nullability annotations removed (compile-time only, CLASS retention; no jar to carry)
#   - the optional JavaFX package and module-info.java left out
#
#   scripts/vendor-javif.sh [commit]     # default: the pinned commit below
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
COMMIT="${1:-1bf5b82}"
DEST="$REPO/src/vendor"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git clone -q https://github.com/Glavo/javif.git "$tmp/javif"
git -C "$tmp/javif" checkout -q "$COMMIT"
full=$(git -C "$tmp/javif" rev-parse HEAD)
rm -rf "$DEST/pzopt/vendor/avif"
mkdir -p "$DEST/pzopt/vendor/avif" "$DEST/javif"
(cd "$tmp/javif/src/main/java/org/glavo/avif" && find . -name '*.java' ! -path './javafx/*' -print0 \
  | while IFS= read -r -d '' f; do mkdir -p "$DEST/pzopt/vendor/avif/$(dirname "$f")"; cp "$f" "$DEST/pzopt/vendor/avif/$f"; done)
find "$DEST/pzopt/vendor/avif" -name '*.java' -print0 | xargs -0 sed -i -E \
  -e 's/\borg\.glavo\.avif\b/pzopt.vendor.avif/g' \
  -e '/^import org\.jetbrains\.annotations\./d' \
  -e 's/@(org\.jetbrains\.annotations\.)?(UnmodifiableView|Unmodifiable|UnknownNullability|NotNullByDefault|Nullable)\b ?//g'
cp "$tmp/javif/LICENSE" "$DEST/javif/LICENSE"
cat > "$DEST/javif/README.md" <<EOF
# javif (vendored)

Pure-Java AV1 / AVIF decoder by Glavo, https://github.com/Glavo/javif, commit \`$full\`, licensed under the Mozilla
Public License 2.0 (\`LICENSE\` here). Vendored by \`scripts/vendor-javif.sh\` for the options menu's AVIF clips
(\`pzopt.ClipDecoder\`). Changes, all mechanical: package \`org.glavo.avif\` renamed to \`pzopt.vendor.avif\`, the
JetBrains nullability annotations (compile-time only) removed, the JavaFX package and \`module-info.java\` left out.
The modified files remain under the MPL-2.0; their source is this directory.
EOF
n=$(find "$DEST/pzopt/vendor/avif" -name '*.java' | wc -l)
echo "vendored javif $full: $n files under src/vendor/pzopt/vendor/avif"
