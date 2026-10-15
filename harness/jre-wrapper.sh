#!/bin/bash
# GAME_WRAPPER for a run on another JRE without touching the game's jre64 (shared install):
#   GAME_WRAPPER=$PWD/harness/jre-wrapper.sh PZOPT_JRE=~/.cache/pzopt-jre/<jre> harness/run.sh --launcher direct ...
# Same steps as the game's projectzomboid.sh, with PZOPT_JRE in place of jre64: the native launcher
# (pzexe) finds libjvm.so by running the `java` first on PATH. console.txt's java.home / java.version
# lines show which JVM the run got.

JRE="${PZOPT_JRE:?PZOPT_JRE=<jre dir> is required}"
[[ -x "$JRE/bin/java" ]] || { echo "jre-wrapper: no $JRE/bin/java" >&2; exit 1; }
. "$(dirname "$(readlink -f "$0")")/../scripts/pz-env.sh"
cd "$PZ_DIR" || exit 1
export PATH="$JRE/bin:$PATH"
export LD_LIBRARY_PATH="$PZ_DIR/natives:$PZ_DIR:$JRE/lib:${LD_LIBRARY_PATH:-}"
echo "jre-wrapper: $("$JRE/bin/java" -version 2>&1 | sed -n 2p)"
XMODIFIERS= LD_PRELOAD="${LD_PRELOAD:-}:libjsig.so:libPZXInitThreads64.so" exec ./ProjectZomboid64 "$@" > "${ZOMBOID:-$HOME/Zomboid}/projectzomboid.sh.log"
