#!/usr/bin/env bash
# Java mod compatibility fixture (2026-10-02): writes the mod Zomboid/mods/pzopt-compat-javafixture, enabled per run with
# `run.sh --mod pzopt-compat-javafixture`. Its jar holds ZombieBuddy-style @Patch classes (the annotation's descriptor,
# no ZombieBuddy needed: nothing loads them, pzopt.ModCompat only reads the class files):
#   WorldStreamer.threadLoop     an edited method with a switch    -> wake off
#   IsoChunkMap.updateInternal   an edited method with a switch    -> chunkHandoffSlack off
#   WorldStreamer.isBusy         not an edited method              -> nothing
# plus a second jar with no patch (a library: "patches no class PZ Optimization ships"). Rig of the main menu's
# PZ OPTIMIZATION > MOD COMPATIBILITY CHECK dialog: `--mode verify --mod pzopt-compat-javafixture --flag compat_check=1`.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../../scripts/pz-env.sh"
MOD="$ZOMBOID/mods/pzopt-compat-javafixture"
SRC=$(mktemp -d)
trap 'rm -rf "$SRC"' EXIT
rm -rf "$MOD"; mkdir -p "$MOD/42/media/java" "$SRC/me/zed_0xff/zombie_buddy" "$SRC/fixture" "$SRC/lib"
cat > "$MOD/42/mod.info" <<'INFO'
name=PZ Optimization Java compatibility fixture
id=pzopt-compat-javafixture
description=Test fixture of harness/compat/make-java-fixture.sh; not for players.
versionMin=42.0
INFO
cp "$MOD/42/mod.info" "$MOD/mod.info"
cat > "$SRC/me/zed_0xff/zombie_buddy/Patch.java" <<'JAVA'
package me.zed_0xff.zombie_buddy;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Patch { String className(); String methodName(); }
JAVA
cat > "$SRC/fixture/Patches.java" <<'JAVA'
package fixture;
import me.zed_0xff.zombie_buddy.Patch;
@Patch(className = "zombie.iso.WorldStreamer", methodName = "threadLoop") class StreamerLoop {}
@Patch(className = "zombie.iso.IsoChunkMap", methodName = "updateInternal") class ChunkMapUpdate {}
@Patch(className = "zombie.iso.WorldStreamer", methodName = "isBusy") class StreamerBusy {}
JAVA
cat > "$SRC/lib/Helper.java" <<'JAVA'
package lib;
public class Helper { public static int one() { return 1; } }
JAVA
javac --release 17 -d "$SRC/out" "$SRC/me/zed_0xff/zombie_buddy/Patch.java" "$SRC/fixture/Patches.java"
javac --release 17 -d "$SRC/libout" "$SRC/lib/Helper.java"
jar --create --file "$MOD/42/media/java/pzopt-compat-fixture.jar" -C "$SRC/out" .
jar --create --file "$MOD/42/media/java/fixture-helper-lib.jar" -C "$SRC/libout" .
echo "Java fixture mod written: $MOD"
