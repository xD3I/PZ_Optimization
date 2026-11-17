# Shared by the scripts and the harness: where the game is installed and where
# its user directory is. Sourced, not executed.
#
# Steam keeps one depot per platform in <steamapps>/common/ProjectZomboid and
# swaps it when the compat tool changes:
#   native Linux : common/ProjectZomboid/projectzomboid/{projectzomboid.jar,ProjectZomboid64,ProjectZomboid64.json}, user dir ~/Zomboid
#   Windows/Proton: common/ProjectZomboid/{projectzomboid.jar,ProjectZomboid64.exe,ProjectZomboid64.json}, user dir in the compatdata prefix
# Override with PZ_DIR / ZOMBOID in the environment.
# Default library /games (the desktop); machines without it (the laptops) use Steam's own library under ~.
if [[ -z "${PZ_ROOT:-}" ]]; then
  PZ_ROOT=/games/steamapps/common/ProjectZomboid
  for c in "$PZ_ROOT" "$HOME/.local/share/Steam/steamapps/common/ProjectZomboid" "$HOME/.steam/steam/steamapps/common/ProjectZomboid"; do
    if [[ -f "$c/projectzomboid/projectzomboid.jar" || -f "$c/projectzomboid.jar" ]]; then PZ_ROOT="$c"; break; fi
  done
fi
if [[ -z "${PZ_DIR:-}" ]]; then
  if [[ -f "$PZ_ROOT/projectzomboid/projectzomboid.jar" ]]; then
    PZ_DIR="$PZ_ROOT/projectzomboid"
  else
    PZ_DIR="$PZ_ROOT"
  fi
fi
if [[ -x "$PZ_DIR/ProjectZomboid64" && ! -f "$PZ_DIR/ProjectZomboid64.exe" ]]; then
  PZ_LAYOUT=native
  ZOMBOID="${ZOMBOID:-$HOME/Zomboid}"
  PZ_DIR_JVM="$PZ_DIR"                      # the install dir as the JVM sees it (-Xlog / JFR file paths)
else
  PZ_LAYOUT=proton
  ZOMBOID="${ZOMBOID:-/games/steamapps/compatdata/108600/pfx/drive_c/users/steamuser/Zomboid}"
  PZ_DIR_JVM="S:/common/ProjectZomboid"     # Wine path (see the gc.log line in a Proton run's JSON)
fi
export PZ_DIR ZOMBOID PZ_LAYOUT PZ_DIR_JVM
