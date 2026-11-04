#!/usr/bin/env bash
# Unit tests for the pzopt classes that do not need the game running.
# Compiles tests/ against build/classes and the game jar, then runs each *Test main.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/pz-env.sh"
JAR="$PZ_DIR/projectzomboid.jar"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/build/tests"
[[ -d "$REPO/build/classes" ]] || { echo "run scripts/build.sh first" >&2; exit 1; }
rm -rf "$OUT"; mkdir -p "$OUT"
# a signed game jar (build.sh): our classes of the game's packages are under pzopt/overrides, the agent supplies them at run time.
# That folder stays after the jar on the run's class path: the bytecode tests find the files through it, while the JVM takes each
# class from the signed jar first (a class loaded from it would be refused).
AGENT="$REPO/build/classes/pzopt/agent/pzopt-agent.jar"
JVM_AGENT=(); [[ -f "$AGENT" ]] && JVM_AGENT=("-javaagent:$AGENT")
javac --release 25 -nowarn -cp "$REPO/build/classes:$REPO/build/classes/pzopt/overrides:$JAR" -d "$OUT" $(find "$REPO/tests" -name '*.java')
fail=0
# never the player's Zomboid/pzopt/options.ini: a saved menu choice (dlssPreset=e on this machine, 2026-09-24) made
# UpscalerTest read a non-default Config value and fail the release; tests that need the file set their own path
NO_USER_OPTIONS="$OUT/no-user-options.ini"
for t in $(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||;s|\.class$||;s|/|.|g' | sort); do
  # the game runs with -XX:-OmitStackTraceInFastThrow (launcher JSON): exception messages stay in hot code (LuaJitDiffTest)
  if ! java ${JVM_AGENT[@]+"${JVM_AGENT[@]}"} -XX:-OmitStackTraceInFastThrow -Dpzopt.dev=true -Dpzopt.userOptionsFile="$NO_USER_OPTIONS" -cp "$OUT:$REPO/build/classes:$JAR:$REPO/build/classes/pzopt/overrides" "$t"; then echo "FAILED: $t" >&2; fail=1; fi
done
exit $fail
