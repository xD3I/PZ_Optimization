# Signed game jar (Build 42.22.1, 2026-11-04): loading the overrides through a Java agent

Game: 42.22.1 pre-release, revision `71e8410c42`, Steam install, native Linux (Flip 1S). Branch `integrity-agent`, not released.

## What changed in the game

`projectzomboid.jar` is signed (`META-INF/PROJECTZ.SF/.EC`, self-signed `CN=Project Zomboid, O=The Indie Stone`, certificate from
2026-10-14). A class loader refuses unsigned classes in a package that holds signed ones, so loose classes ahead of the jar stop the
game before `main`. The launcher prints only `Failed to find class: zombie/gameStates/MainScreenState`, in the Steam journal;
`console.txt` is not written. The cause: `SecurityException: class "zombie.gameStates.GameState"'s signer information does not match`.
BootRepair lives in `MainScreenState.main`, so it never runs.

New `zombie.core.IntegrityReport`: verifies the jar against a pinned certificate, lists extra class path entries, `.class` files in
class path folders, agents and an attach listener, and adds a summary to `Core.getVersion()` (logs, `version.txt`, statistics classes,
debug UI). No game code refuses to run on its result. Its source says: "If you are messing with this code, you give us permission to
perma ban you from the workshop <3". Of the 469 stock classes we shadow only `MainScreenState` and its two inner classes changed,
all for this report.

## The design

- `build.sh` on a signed jar moves our classes of the game's packages to `pzopt/overrides/` (not a class path root) and builds
  `pzopt/agent/pzopt-agent.jar` (`src/agent`). An unsigned jar builds exactly as before. `pzopt.*` stays loose: the jar has no such package.
- The launcher JSON gains one argument, `-javaagent:pzopt/agent/pzopt-agent.jar`. `.` stays first on the class path.
- The agent hands the JVM our bytes when a jar class loads, so each class keeps the jar's signed protection domain. The 13 classes
  the jar does not have are defined at start beside a class of their package, with that class's domain.
- A jar of another revision gets no override: stock game. `premain` never throws.
- **We do not hide from the report, and nothing touches `IntegrityReport`.** The game reports `classpath=1612,agents=1` with the mod
  on. `build.sh` fails if `IntegrityReport` joins the overrides or our `MainScreenState` stops starting it (the audit skips `main`,
  which carries pzopt edits).
- `ModCompat` skips this agent and PZ_Controller's (`loaderAgent`): any other `-javaagent` turns `renderLastFast` off, these two only
  load the mods. The game's report still counts them.
- `AotCache` stays off on a signed jar: the cache form's `pzopt/aot/pzopt.jar` first on the class path would stop the game like loose
  classes do. Startup loses the AOT cache; not measured.
- The argument leaves in `removeAgentArg` (Uninstall, BootRepair, the Updater before it swaps files) and `strip_agent` /
  `Remove-AgentArg` (installers), before the jar goes. Never in `AotCache.resetLauncher`: `step()` calls that at every boot, and the
  first version of this branch switched the mod off after one launch that way (`AotCacheTest` now pins it).

## Checks on this machine

- `build.sh`: audit 9,539 unedited methods, 0 mismatches. `test.sh`: all pass, 121 JVMs through the agent. `install-sh-test.sh` passes.
- `harness/run.sh --mode verify --quit-after 100 --launcher direct` (`agent-smoke2-20261104-184514`): 84 overrides active, 0 disabled,
  0 signer errors, 0 linkage errors, the bench save rendered, quit reached at frame 3003, jar checksum unchanged.
  That run had the class path jar-first to step around PZ_Controller's loose class (below). `agent-smoke3` repeated it in the shipped
  `.`-first order after `pzctl.sh uninstall`.
- Fail-safes, class loading only: a revision mismatch gives stock classes and one log line; a launcher without the argument gives stock
  classes, nothing refused.

## Not done

- **A missing agent jar with the argument still present stops the JVM** (`Error opening zip file or JAR manifest missing`), for
  example a player deleting `pzopt/` by hand. Every uninstall path removes the argument first.
- **Installs from before this layout still fail on a signed game.** They need a bridge release built on the unsigned jar, whose
  launcher main class sits outside the jar's packages and repairs before any game class loads. Not built: the 42.21 jar is not on this machine.
- **Any other Java mod's loose class fails on a signed game.** PZ_Controller's `zombie/input/AimingReticle.class` throws from
  `Mouse.update` every frame, which keeps the game from progressing. It needs its own port.
- `install.ps1` is untested (no PowerShell here, no Windows run). macOS has no launcher JSON: the installer warns and the game runs
  stock. Dedicated servers do not get the agent: they run stock. No performance run: AOT off and class loading through the agent are unmeasured.
- The mod-only classes and transformed classes run under the jar's signed domain, so `Class.getSigners()` names The Indie Stone for code
  they did not write. The report still shows the agent. Whether The Indie Stone accepts any of this is unanswered: ask before a release.

## Build 42.22.2 stable (2026-11-11, revision `3d39148846`)

Same signing; jar SHA-256 `3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`. Of the shadowed stock classes only
`MainScreenState` changed against 42.22.1, and only its inlined revision constant (`writeOutCurrentVersion`, the one audit mismatch);
our copy now carries `3d39148846`. Audit 9,539 unedited methods, 0 mismatches. Flip, `harness/run.sh --mode verify --quit-after 100
--launcher direct` (`boot-42222-20261111-175801`): every override active for `3d39148846` (same class set as `agent-smoke3`), no
signer / linkage / verify errors, the game reports `classpath=1598,agents=1`, bench save rendered, timed quit reached.

## ZombieBuddy 2.3.4 beside the agent (2026-11-11, flip, 42.22.2)

ZombieBuddy's premain loads four classes we replace before any later agent runs: `KahluaThread`, `LuaCaller`,
`KahluaConverterManager` and `$1` (class-load log of a bare JVM with both agents, `-javaagent:ZombieBuddy.jar` first). Ours add
fields and methods (29, 15, 3), so they cannot be redefined after the fact, and a mix of its stock copies with the rest of ours
would fail mid-game. The agent now checks `getAllLoadedClasses()` before it registers its transformer: any of ours already
loaded means nothing is replaced (stock game) and one line says how to fix the order. Its lines also go to
`Zomboid/pzopt/agent.log` (premain's stdout reaches neither console.txt nor the launcher's output).

- `zb-after-20261111-181346` (ours first in the JSON, ZombieBuddy appended, its documented Linux / macOS launch-option order):
  81 overrides active, `agents=2`, ZombieBuddy patched GameWindow / GameLoadingState / DebugLog on top of ours (its
  transformer is retransform-capable, ours not, so the JVM runs ours first at every load), our mod-compat scan saw its
  patches, timed quit reached, no linkage errors.
- `zb-first-20261111-181835` (ZombieBuddy first in the JSON): `agent.log` names the four classes, the game ran stock, timed
  quit reached, no linkage errors. A `JAVA_TOOL_OPTIONS` form of the same test never started: the launcher script's own
  `java -version` 64-bit check aborted with ZombieBuddy attached.
- Not tested: Windows `-agentlib:zbNative` (the native agent loads ZombieBuddy.jar itself; its order against ours unknown).

macOS: `install.sh` now prints the Steam launch option
`JAVA_TOOL_OPTIONS="-javaagent:'<Contents/Java>/pzopt/agent/pzopt-agent.jar'" %command%` (the JVM reads the variable itself,
so `JavaAppLauncher` needs no change; the quoted path with spaces checked against the game's JRE on Linux, not on a Mac yet).

Quick check, bench route at max zoom, 240 cap, flip, one run each: `--prop enabled=false` (stock path)
`v42222-stockpath-20261111-183056` 46.7 fps, p99 51.4 ms, 509 frames > 33 ms, 1 %-low 19; optimized `v42222-opt-20261111-182750`
68.0 fps, p99 25.6 ms, 17 frames > 33 ms, 1 %-low 39. A stock run with the mod uninstalled (`v42222-stock-20261111-182128`) never
started its route: without our classes the bench driver (pzopt.Harness) is absent; stock comparisons need the stockrig Lua
(`harness/pzo-compare/`).
