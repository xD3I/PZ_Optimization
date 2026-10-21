# Install

[Back to the README](../../README.md)

Four ways to get the same files into the game folder. A and B are the ones to use; C is
copying them by hand, no command; D is for changing the code. The game reads the files at start,
so **close the game first** whichever method you pick.

> **Turn off Steam's in-game performance monitor** (Settings > In Game > "In-game
> performance monitor", the newer overlay, not the classic Shift+Tab one). It hooks every
> GL call and serialises the render thread: with it on the optimized game stops at about
> 160 fps and the headroom these patches recover is hidden (164 vs 237 fps on the 120 km/h
> route, `docs/archive/2026-09-24/results.md` 2026-09-19). Use the build's own overlay
> ([F9 / L3 + R3](settings.md#performance-overlay-f9--l3--r3)), MangoHud or RivaTuner instead.

## Requirements

- Project Zomboid on Steam on **Build 42.21** (the default branch since 2026-09-28; right-click the game, Properties,
  Betas: none). The Windows, Linux and macOS depots all work; the jar is the same.
- Windows: Windows PowerShell 5.1 or newer (built in). Linux and macOS: `bash`, `curl` or
  the `gh` CLI, and `unzip` (or `python3`); a stock Mac has all of them, no Xcode or Homebrew
  needed. No JDK anywhere.
- Nothing is compiled; nothing is written outside the game folder and `Zomboid/pzopt/`.

Every installer below does the same thing: locate the game through Steam's library list
(or `-Dir` / `--dir <folder>` / `PZ_DIR`), read the game revision from the jar, check that
the launcher classpath loads loose classes, record every file in `pzopt-installed.txt` so the
uninstall is exact, then copy the classes next to `projectzomboid.jar` (on macOS that is
`Project Zomboid.app/Contents/Java`). The jar is never touched. The list is written before the
copy, so an install that stopped half-way (an antivirus blocking a file, a closed window) is
replaced by the next run or removed by the uninstall; files of ours that an older unfinished
install left without a list are replaced too.

## Method A: Steam Workshop

Subscribe to [PZ_Optimization on the Workshop](https://steamcommunity.com/sharedfiles/filedetails/?id=3805285544)
and let Steam download it. The Workshop cannot write into the game folder, so the item
carries the files and the installer, and one command finishes the install.

![The script install: subscribe, enable the mod, copy the command, paste it, quit, play (animated)](../media/install-script.gif)

**Easiest**, about two minutes:

1. Subscribe, let Steam download it.
2. In the game's main menu, **MODS**: tick **PZ_Optimization**, Accept. The main menu now shows the install command
   for your computer, with the item's real folder in it.
3. Press **Copy the command**.
4. Paste it into PowerShell (Windows) or a terminal (Linux, macOS), Enter. It waits while the game runs.
5. Quit the game (QUIT); the installer then copies the files.
6. Start the game: the main menu has a **PZ OPTIMIZATION** item. Disable the mod again: it only carries that window.

**The command by hand** (Windows, PowerShell; adjust the drive if your Steam library is elsewhere):

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Program Files (x86)\Steam\steamapps\workshop\content\108600\3805285544\mods\PZ_Optimization\42\install.ps1"
```

**Linux:**

```sh
bash ~/.local/share/Steam/steamapps/workshop/content/108600/3805285544/mods/PZ_Optimization/42/install.bash
```

**macOS** (Terminal):

```sh
bash ~/Library/Application\ Support/Steam/steamapps/workshop/content/108600/3805285544/mods/PZ_Optimization/42/install.bash
```

The script finds the unpacked `pzopt-classes/` folder next to itself and installs from it,
no download. Updates: the main menu's PZ OPTIMIZATION UPDATE item copies a newer Workshop
download over the installed files, no installer run needed. Item layout and upload procedure:
[`docs/workshop.md`](../workshop.md).

## Method B: installer script from the GitHub release

One line, no path to type. The script uses the Steam Workshop copy when it finds one in the
Steam library that holds the game; otherwise it downloads `pzopt-<revision>-classes.zip` for
your game's revision from the matching release. A running game is waited for. Run over an
existing install (an older build, or a build for the game before its last update), it replaces it.

**Windows** (Start menu, type `powershell`):

```powershell
irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.ps1 | iex
```

**Linux and macOS** (Terminal):

```sh
curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.sh | bash
```

Options go after the script: `& ([scriptblock]::Create((irm <url>))) -Status` on Windows,
`curl -fsSL <url> | bash -s -- --status` on Linux and macOS. Or download the file and run it
(`powershell -ExecutionPolicy Bypass -File install.ps1`, `bash install.sh`).

On macOS the script finds `Project Zomboid.app` through Steam's library list and installs
into its `Contents/Java`; the bundle's launcher already searches that folder before the jar,
so nothing in the app is edited.

Both scripts take the same options: `-Status` / `--status` (installed for which revision,
any `MISSING` / `MODIFIED` file, the contents of `pzopt.properties` if present),
`-Uninstall` / `--uninstall`, `-Dir` / `--dir <game folder>`, `-Zip` / `--zip <file>` to
install a zip you already have, `-From` / `--from <folder>` to install an unpacked tree.

## Method C: copy the files by hand

No command at all. The files are the same in the Workshop download and in the release zip.

![The manual install: subscribe, find the mod's files, copy them, paste them into the game folder, play (animated)](../media/install-manual.gif)

**From the Workshop download** (subscribed, nothing to download):

1. Find the mod's files. In Steam, right-click Project Zomboid, **Manage > Browse local files**: that opens the game
   folder (`...\steamapps\common\ProjectZomboid`). Go up to **steamapps**, then
   `workshop\content\108600\3805285544\mods\PZ_Optimization\42\pzopt-classes`.
2. Copy everything in `pzopt-classes` (Ctrl+A, Ctrl+C).
3. Open the game folder again (Browse local files) and paste it there, beside `projectzomboid.jar` (Ctrl+V). Skip any
   file that already exists.
4. Start the game: the main menu has a **PZ OPTIMIZATION** item.

**From the release zip:** download `pzopt-4a0e9546ec-classes.zip` (about 64 MB) from the
[release page](https://github.com/xD3I/PZ_Optimization/releases/latest) and unpack it into
the folder that holds `projectzomboid.jar`, without overwriting anything (`Expand-Archive`
without `-Force`, or `unzip -n`).

On macOS the game folder is inside the app bundle: in
`~/Library/Application Support/Steam/steamapps/common/ProjectZomboid`, right-click
`Project Zomboid.app`, Show Package Contents, `Contents/Java`. Both carry `pzopt-files.txt`, the list of everything
they add, and `Uninstall-PZ-Optimization.cmd` / `uninstall-pz-optimization.bash`. The revision in the zip's name must
match your game (42.21 is `4a0e9546ec`); a build for another revision disables itself at start-up. To remove it, run
the uninstaller beside `projectzomboid.jar`, or delete the files listed in `pzopt-files.txt` and the empty folders they
leave, or run either installer's uninstall.

## Method D: build from source (Linux)

For changing the code. Needs a JDK 25 or newer (`javac`, `javap`), Python 3, `git`, `bash`.

```sh
sudo pacman -S jdk-openjdk            # Arch / CachyOS
sudo apt install openjdk-25-jdk       # Debian / Ubuntu (or the newest available)
sudo dnf install java-latest-openjdk-devel   # Fedora

git clone https://github.com/xD3I/PZ_Optimization.git
cd PZ_Optimization
scripts/build.sh          # compiles src/ against your jar, checks the stock class signatures
scripts/pzopt.sh install  # copies build/classes/ in, records pzopt-installed.txt
scripts/pzopt.sh status   # must say installed: yes, no MISSING / MODIFIED
```

If the game is not in `/games/steamapps/common/ProjectZomboid`, export `PZ_ROOT` first
(the folder that contains `projectzomboid/` with the jar and `ProjectZomboid64.json`):
`export PZ_ROOT="$HOME/.local/share/Steam/steamapps/common/ProjectZomboid"`. `build.sh` ends
with `built N class files into build/classes for game revision 4a0e9546ec` and fails
loudly on a revision or signature mismatch. `scripts/test.sh` runs the JVM-only unit tests
(no game needed); `scripts/release.sh` builds the release zip. `scripts/pzopt.sh` and
`install.sh` read the same manifest, so either can uninstall what the other installed.

On Windows the repository is not needed to play; if you clone it, use a short path
(`C:\Users\<you>\PZ_Optimization`, some files sit ten folders deep) and the build scripts
do not run there.

## Check that it loaded

Start the game from Steam as usual. The first boot is slower (caches are written under
`Zomboid/pzopt/`). Once at the main menu, `Zomboid/console.txt` (Windows
`%USERPROFILE%\Zomboid`, Linux and macOS `~/Zomboid`) shows one `[pzopt] loaded override ... active`
line per class in its first seconds:

```powershell
Select-String -Path "$env:USERPROFILE\Zomboid\console.txt" -Pattern "\[pzopt\] loaded override" | Measure-Object | Select-Object -ExpandProperty Count
```

```sh
grep -c '\[pzopt\] loaded override' ~/Zomboid/console.txt
```

| You see | Meaning |
|---|---|
| dozens of lines | the overrides are active. the main menu has a **PZ OPTIMIZATION** item and Options > Display has the **Uncapped** entry. Done. |
| `0` | the class files did not load. Check that `<game>\pzopt\Overrides.class` exists and that `ProjectZomboid64.json` lists `"."` before `"projectzomboid.jar"` under `classpath` (it does on the stock depot; the macOS bundle has no JSON, its launcher puts `Contents/Java` first by itself). |
| a line saying the overrides were built for another revision | your game is not 42.21 / `4a0e9546ec`; the game runs as stock. Switch Steam to that branch or wait for a matching release. |
| the game stops at start with `NoClassDefFoundError: pzopt/...` | an unfinished install: some override classes are in the game folder without the `pzopt` folder they call. Run the installer again (it replaces what is there), or run it with `-Uninstall` / `--uninstall`. |
| the game closes at start after a game update, or Uninstall in the menu removed nothing | builds since 2026-10-07 fix both at the next start (`Zomboid/pzopt/boot-repair.log`). Older installs: double-click `Uninstall-PZ-Optimization.cmd` in the game folder if it is there, else the `uninstall.ps1` / `uninstall.sh` line above. |

## Uninstall

**In the game:** main menu > PZ OPTIMIZATION > **Uninstall PZ Optimization**. The
game closes; once it has, a helper removes every file the installer or the updater recorded,
the DLSS files the Visuals page fetched and the AOT cache, and the launcher settings it
changed are put back (log: `Zomboid/pzopt/uninstall.log`). If the files are still there at the
next start, the game removes them then and restarts stock (`Zomboid/pzopt/boot-repair.log`).
Unsubscribing from the Workshop item and Steam's "Verify integrity of game files" leave the
installed files in the game folder.

**Without starting the game:** double-click `Uninstall-PZ-Optimization.cmd` in the game folder
(Steam: Project Zomboid > Manage > Browse local files; Linux / macOS: `bash
uninstall-pz-optimization.bash` there). Every install since 2026-10-07 leaves it there, with its
own copy of the installer under `pzopt/uninstall/`.

**After a game update** the first start removes a build made for the old game revision (or
installs the matching one from the Steam Workshop copy) and starts the game again, so the game
never crashes on an outdated install.

**From a terminal** (the game may be running; the script waits for it to close):

```powershell
irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/uninstall.ps1 | iex
```

```sh
curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/uninstall.sh | bash
scripts/pzopt.sh uninstall      # Linux, from-source install
```

Without a list of installed files (an install that stopped early) the uninstallers still find
PZ Optimization's files: paths with `pzopt` in them, classes that call the `pzopt` package, and
files identical to the Workshop copy's. Another Java mod's class in the way of an install is
refused by name; `-Force` / `--force` moves it to `Zomboid/pzopt/replaced-files/` first.

A local copy works the same (`install.ps1 -Uninstall`, `install.sh --uninstall`, the Workshop
item's `install.bash --uninstall`). The jar was never modified, so no Steam file verification
is needed. Each removes exactly the files it recorded and the empty folders they leave. The caches and
settings under `Zomboid/pzopt/` (`options.ini`, `framecap.ini`, `anims/`, `packs/`) can be
deleted by hand; without the overrides the game uses whatever its own `options.ini` holds.

## Updating the mod

Once installed by either script (Workshop copy or release download), the game updates it
by itself: the main menu has an **PZ OPTIMIZATION UPDATE** item between CREDITS and QUIT,
styled like the stock ones, greyed out while your build is current. Once per boot the menu
checks the GitHub releases and, when a newer build for your game revision exists, the item
lights up. It opens a dialog with the installed and offered builds and
the release notes; **Update now** replaces the files that changed (only files listed in
`pzopt-installed.txt`; the game's own files are never touched, saves and options stay) and
offers **Restart game**, because the classes already loaded stay the old ones until the next
start: the game closes and starts again by itself with the update.

The update is near instant. The check runs while the game boots (a cached, conditional
request: usually ~70 ms and no data), so its answer is there when the menu appears. Releases
differ in a few files, and the updater reads which from the end of the release zip and fetches
only those entries (a release apart: ~60 KB instead of 59 MB); that happens in the background
as soon as an update is offered, so Update now only writes them (1 ms on the desktop, 5 ms on
a MacBook, instead of 1.2-2.4 s). Without range support it downloads the whole zip in parallel
parts. Measurements: `docs/findings-updater-2026-09-26.md`.
With a controller the lit item is one more row of the menu's D-pad list (A opens the dialog;
there A is the first button, B the second one or close, the D-pad scrolls the notes).
Only the changed entries are fetched before that click (`updatePrefetch`, capped at 16 MB;
the whole zip never); the check itself is one request to `api.github.com` (the release page
when the API's hourly limit is used up) and can be switched off in PZ OPTIMIZATION > Optimizations > System, sound and mods >
Updates (`updateCheck=false`). A copy unpacked by hand (Method C, no `pzopt-installed.txt`) only
gets a link to the release page from the item.

Workshop subscribers do not need GitHub for it: Steam already downloads every release with
the Workshop item (the same files as the release zip). The check looks at that copy first,
in the Steam library that holds the game, and offers it at once when it is newer than your
build; **Update now** then copies it from the disk. GitHub is still asked afterwards and only
replaces the offer with a newer release, so where GitHub is slow or blocked the item still
lights up, and otherwise it goes grey ("up to date") instead of failing. Switch:
PZ OPTIMIZATION > Optimizations > System, sound and mods > Updates, `updateFromWorkshop` (default on).

## After a game update

The classes are compiled against one exact jar. They switch their own changes off on another
revision, but they still stand in for the game's copies of the classes they replace, and a game
update that removes or changes what those old copies call stops the game at start: Build 42.21
(2026-09-28) closes before the main menu with a build for 42.20.4 installed, so neither the
in-game updater nor the Uninstall button can be reached. Run the install command again (above)
once a release for the new revision is out: it replaces the old build (since the 42.21 release;
older installers asked for `-Uninstall` / `--uninstall` first). The uninstall command alone
gives back the vanilla game at any time. The Workshop item is updated at the same time. From source, on an unchanged revision (a Steam re-verify, for example) just
`scripts/pzopt.sh uninstall && scripts/build.sh && scripts/pzopt.sh install`; if
`build.sh` reports a mismatch the game changed and the overrides need porting
(`scripts/regen-overrides.sh`, `.claude/skills/game-update`).
