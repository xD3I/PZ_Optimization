# "I can't uninstall it": Workshop comments and the fixes (2026-10-07)

## What the players reported

The 2,318 Workshop comments (to 2026-10-07; 1,963 from players, 355 replies by the maintainer) were sorted by
kind. "Uninstall" was the most frequent subject (185 mentions, 108 of them negative; 177 comments discuss it). Five
separate failures:

| Failure | Comments | Cause |
|---|---|---|
| The game no longer starts, so the in-game Uninstall is out of reach | ~20, almost all after 09-28 | the 42.20 build left in place when the game updated to 42.21 (09-28, one day after the in-game uninstall shipped): `NoSuchMethodError: 'java.util.ArrayList zombie.ZomboidFileSystem.getModIDs()' at GameWindow.setTexturePackLookup`. The guard's "build mismatch, stock path" cannot help: an override's stock path is a copy of the old jar's code, linked against old signatures |
| The PowerShell command typed wrong | ~35 | `-Uninstall` before `-File` or inside the quotes, `-ExecutionPolicy` alone, an invented `Unistall.ps1`; Steam turns the `irm` URL followed by `)))` into a link that 404s ("the uninstall link is gone") |
| `refusing to overwrite existing file`, and `-Uninstall` removes nothing | ~12 | an install cut short (full disk, antivirus) before it wrote `pzopt-installed.txt`; also another Java mod's file (Better Vehicle Dynamics ships `zombie/iso/IsoChunkMap.class`) |
| Unsubscribed first | ~5 | unsubscribing deletes the Workshop item (and its installer), not the files in the game folder |
| Pressed Uninstall in the menu, everything still there | 2 | the Windows helper (a hidden PowerShell with `-EncodedCommand`, started by the game) never ran or failed; unverified, antivirus heuristics stop exactly that pattern. `harness/uninstall-e2e.sh` had only run on the flip and the Mac |

Why no Steam action helps: the stock launcher JSON lists `"."` before `projectzomboid.jar`, so the loose classes always
shadow the jar, and "Verify integrity of game files" never deletes files Steam did not install. A Steam reinstall often
leaves them too.

## Fixes

1. **Boot repair** (`pzopt.BootRepair`, first statement of `MainScreenState.main`, before any game code): on a build
   mismatch (the game updated) the installed files are removed; when the Steam Workshop copy is a build for the running
   revision whose stock-class hashes match the jar, it is installed in their place (manifest header "boot repair");
   then `Restart` starts the game again. When the uninstall helper left its list (`Zomboid/pzopt/uninstall-files.txt`,
   a list older than the manifest only) the files go at that start. Developer installs (manifest by `scripts/pzopt.sh`)
   are left alone on a mismatch; `-Dpzopt.bootRepair=false` turns it off; a process `Restart` started never repairs
   (no loop). Everything from the old install (Uninstall's plan, the Workshop lookup, the launcher JSON undo, Restart)
   loads before the first file changes; the file work uses only the JDK. Log `Zomboid/pzopt/boot-repair.log`; note for
   the Workshop helper `Zomboid/Lua/pzopt-boot-repair.txt`. `tests/pzopt/BootRepairTest`.
2. **Uninstallers in the game folder**: every release carries `Uninstall-PZ-Optimization.cmd` (double-click) and
   `uninstall-pz-optimization.bash` at the top of the game folder and its own installers under `pzopt/uninstall/`
   (`install.ps1`, `install.bash`; `.sh` is banned by the Workshop validator). They are release files, so the installers,
   the updater and the boot repair list them in the manifest and every uninstall removes them. The `.cmd` copies the
   installer to `%TEMP%` (the uninstall deletes the original) and runs it with `-Uninstall -Dir <its folder> -Pause`;
   everything after that runs from the block cmd.exe has already read. No spaces in the names: the manifest format splits
   a line at its first space. The Workshop item has its own `Uninstall-PZ-Optimization.cmd` beside `install.ps1`.
3. **One-liners without arguments**: release assets `uninstall.ps1` (`irm .../uninstall.ps1 | iex`) and `uninstall.sh`
   (`curl -fsSL .../uninstall.sh | bash`); the URL ends at a space, so Steam's link is right.
4. **Installers**: `-Pause` (the `.cmd`), `-Dir` normalised (`...\.`), `-Uninstall` without any list finds our files
   (paths with `pzopt`, classes whose bytes name `pzopt/`, their inner classes, and files byte-identical to the
   Workshop copy's list); before an install, leftovers that are ours are replaced and a class that is not (another
   mod's) is refused unless `-Force` / `--force`, which moves it to `Zomboid/pzopt/replaced-files/<time>/`. Both clear
   the game's notes (`uninstall-files.txt`, the boot repair note). `tests/install/install-sh-test.sh` (fake game folder,
   HOME in the test folder).
5. **In-game Uninstall on Windows**: the helper is a script file (`Zomboid/pzopt/uninstall-helper.ps1`) run with
   `-File`, not an `-EncodedCommand`; both helpers log `helper started` first, keep their lists when a file could not be
   removed, and the boot repair finishes what they left.
6. **Workshop helper window**: an uninstall command under the install command with its own Copy button, the
   double-click file named, and the boot repair's reason when it removed the build. **Workshop page**: the uninstall
   section rewritten (the menu, the double-click file, the two one-liners, "Verify integrity does not remove them").

## Windows test (to do)

(Collected with the other open Windows items in `docs/windows-test.md` "Open on Windows (consolidated 2026-10-10)".)

On a Windows install with the release built from this change (Steam, the game in its default library):

1. Install with the one-liner. The game folder has `Uninstall-PZ-Optimization.cmd` and `pzopt\uninstall\install.ps1`;
   `pzopt-installed.txt` lists both.
2. In-game: Options > PZ Optimization > Uninstall PZ Optimization..., Yes. `Zomboid\pzopt\uninstall.log` has
   `helper started`, then `uninstall finished; 0 files could not be removed`; `Zomboid\pzopt\uninstall-helper.ps1` is
   gone; the next start is stock. If the log has no `helper started`: the helper was blocked (antivirus?); start the
   game: `Zomboid\pzopt\boot-repair.log` should say `uninstalled`, and the game restarts stock.
3. Reinstall, quit, double-click `Uninstall-PZ-Optimization.cmd` in the game folder: the window removes the files,
   waits for Enter, closes without a "batch file cannot be found" line; the `.cmd` is gone.
4. Reinstall, then `irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/uninstall.ps1 | iex` (needs the
   release with the new assets).
5. Boot repair, mismatch: reinstall, edit `pzopt\build-info.properties` in the game folder to `revision=0000000000`,
   start the game from Steam. The process ends and a new one starts; `boot-repair.log` says `updated` (the Workshop copy
   for the real revision was installed; `pzopt-installed.txt` header "boot repair") or `removed` (unsubscribed); the
   game reaches the menu. Restore by reinstalling.
6. Foreign class: put any file at `zombie\iso\IsoChunkMap.class` with no install present; the installer refuses and
   names it; `-Force` moves it to `Zomboid\pzopt\replaced-files\`.
7. Cut-short install: delete `pzopt-installed.txt` and `pzopt-files.txt` after an install; `-Uninstall` says "no list
   of installed files ... removing the N files that are PZ Optimization's" and the game starts stock.
