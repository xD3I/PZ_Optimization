# Steam Workshop item (pure distribution)

The Workshop item is a mirror of the GitHub release, packaged the way
[BetterFPS](https://steamcommunity.com/workshop/filedetails/?id=3022543997) does it: Steam
downloads the files, the player installs them with the installer that ships in the item. The
game loads none of the optimizations from it; the one file it loads is the install helper
(`src/workshop/42/media/lua/client/PZ_Optimization_InstallHelper.lua`, 2026-09-27): enabled in the
Mods list, the main menu shows the install command for that computer (the item's own folder from
the mod's version dir) with a Copy button, or, once the overrides are installed, a note that the
mod can be disabled again. The installers wait for a running game, so the flow is copy, paste,
quit. GitHub releases stay the canonical channel; the Workshop
page states the commit and the zip sha256 so the two can be checked against each other.

## Layout (`scripts/workshop.sh` writes it)

```
~/Zomboid/Workshop/PZ_Optimization/          staging folder scripts/workshop-upload.py sends
├── workshop.txt                             title / description= lines / tags=Build 42; / visibility
├── preview.png                              512x512 (256 or 512 square, <= 1 MB) from the showcase thumbnail
├── preview.gif                              animated preview, sent when <= 1,000,000 bytes (see Images)
└── Contents/mods/PZ_Optimization/42/        B42 versioned mod layout
    ├── mod.info                             id=PZ_Optimization, modversion=<commit>, versionMin
    ├── poster.png
    ├── install.ps1                          the repo-root installer (the next release's asset)
    ├── install.bash                         install.sh renamed (.sh is a banned extension)
    ├── media/lua/client/                    the install helper (src/workshop/42/), the item's only Lua
    └── pzopt-classes/                       the release zip unpacked (pzopt-files.txt included)
```

Steam installs it under `steamapps/workshop/content/108600/<id>/mods/PZ_Optimization/42/`.
Both installers look for a `pzopt-classes/` folder next to themselves first, so the whole
Windows instruction is one `powershell -ExecutionPolicy Bypass -File ...\install.ps1` line and
the Linux and macOS one is `bash .../install.bash` (on macOS the item lives under
`~/Library/Application Support/Steam/steamapps/workshop/...` and the files go into
`Project Zomboid.app/Contents/Java`); `--from <dir>` / `-From <dir>` name the folder
explicitly. The page text lives in `docs/workshop/description.txt` (Steam BBCode;
`@REV@ @VERSION@ @COMMIT@ @NFILES@ @NOVERRIDES@ @SHA@ @ID@` are filled in by the script).
**Steam caps the description at 8,000 characters** and the game appends `\n\nWorkshop ID: <id>\nMod ID:
PZ_Optimization` (~50) before submitting, so the staged `description=` lines must stay under ~7,950;
over that the upload ends in `failed to update workshop item, result=8` (`Invalid Parameter` in
`workshop_log.txt`) with nothing changed (2026-09-21, three attempts). A description-only update gets no
change-notes entry (`No content change detected`); verify it on the item page instead.

Rules the game's validator (`zombie.core.znet.SteamWorkshopItem.validateContents`) enforces,
checked by the script before the in-game screen has to refuse:

- only `mods/`, `buildings/`, `creative/` directly under `Contents/`, no loose files;
- no `*.exe *.dll *.bat *.app *.dylib *.sh *.so *.zip` anywhere in `Contents/`;
- `preview.png` must be a square 256 or 512 PNG under 1,024,000 bytes.

## Publishing

1. Publish the GitHub release first (`scripts/release.sh --publish`, skill `release-windows`),
   so the item mirrors a tagged, pushed commit.
2. Stage the published asset: `scripts/workshop.sh --tag b<version>-<yyyymmdd>-<hhmm>-<commit>` (gh downloads
   the zip into `build/workshop/<tag>/`, the commit for `modversion` comes from the tag).
   `--zip <file> [--commit <sha>]` stages a local zip; with neither, the script runs
   `scripts/release.sh` (build + test + zip) and stages that as HEAD.
3. Upload: `scripts/workshop-upload.py --notes "<change notes>"` (or step 2 with `--upload "<notes>"`).
   It loads the game's `natives/libsteam_api.so` with `SteamAppId=108600`, attaches to the running,
   logged-on Steam client and sends what the game's own uploader sends from `workshop.txt` (title,
   description + `Workshop ID:` / `Mod ID:` lines, visibility, tags, `Contents/`), the preview and the
   notes; Steam's `SubmitItemUpdateResult_t` is the verdict. No game, no screen, a few seconds;
   `--check` stops before the submit. Creating a *new* item is the only step that needs the game:
   Main menu > Workshop > Create and update items > `PZ_Optimization` > Upload; it opens the Workshop
   legal agreement in the overlay and writes `id=<number>` back into `workshop.txt`.
4. Copy that `workshop.txt` to `docs/workshop/workshop.txt` and commit it: the script reads the
   `id=` from there on every later staging, so updates go to the same item, and the
   description's install commands carry the real path.
5. After the first upload, re-stage (step 2) and upload once more so the description no longer
   says `<item id>` in the install command.

Updating after a new release is steps 1-3 again (`scripts/workshop.sh --tag <tag> --upload "<notes>"`);
the id and the visibility come from `workshop.txt`. Preflight and verification:
`.claude/skills/release-windows`, "Steam Workshop deploy". EResult 2 is a dead Steam session
("Session Replaced" in `connection_log.txt`); restart Steam.

## Images

`docs/workshop/images/` holds the page images: `00` the Workshop thumbnail (2560x1440; the
YouTube one with the header "PZ Optimized" and only the 632 fps readout: `THUMB_HEADER="PZ
Optimized" THUMB_ONLY_OPT=1 python3 harness/showcase-thumbnail.py docs/workshop/images/00-showcase-thumbnail.jpg`;
`preview.png` is its square centre crop), `01`-`07`
one SDR still per segment of `docs/media/showcase-stock-vs-all-optimizations.mp4` (boot/load,
120 km/h drive, options tab, Rosewood spin, fog, storm, results card; 1920x900, the posters'
hable tone-map at 18 / 33 / 50 / 65 / 82 / 98 / 116 s), `08` the options-tab close-up from
`docs/media/`, `09` the overlay, `10` the Workshop-mods comparison table (`docs/media/workshop-mods-comparison.png`
from `harness/mods-table.py`, scaled to 1920 wide as JPG), `11` the "New! Low-end hardware mode" table
(`docs/media/dell-lowend-comparison.png` from `harness/lowend-table.py`; the image carries the whole
section's text and the date, the description holds only the `[img]`, see the release-windows skill),
`12` the "New! macOS support" table (`docs/media/mac-comparison.png` from `harness/mac-table.py`: the
M1 Pro 120 km/h drive, two-run means, the Terminal install line in the footer; 2026-09-21), `13` the "New! Better
profiling" card (`docs/media/profiler-card.png` from `harness/profiler-card.py`: the whole F9 overlay cropped from a
recorded run, `docs/media/profiler-overlay-full.png`, with the caption lines; 2026-09-22), `14` the "New! See what
every setting does" card (`docs/media/preview-card.png` from `harness/preview-card.py`: the Optimizations tab with its
preview panel, `docs/media/options-preview-full.png`; 2026-09-22; the macOS section left the page with it, macOS stays
in the intro line), `15` the "New! In-game updater" card (`docs/media/updater-card.png` from `harness/updater-card.py`:
the main menu with the item greyed out and enabled plus the dialog before and after the install, from the desktop
captures `docs/media/updater-menu-current.png`, `updater-menu.png`, `updater-dialog-available.png`,
`updater-dialog-installed.png`; 2026-09-22; the two earlier "New!" sections lost their "New!" with it), `16` the
"New! Smooth zoom" card (`docs/media/zoom-card.png` from `harness/zoom-card.py`: the worst-frame table of the four zoom
cases, stock vs `zoomRetain` + `zoomEase`, from `docs/archive/2026-09-24/results.md` "Camera zoom changes"; 2026-09-22 evening; the updater
section lost its "New!" with it), `17` the "New! Upscaler: FSR 1.0 and DLSS" card (`docs/media/upscaler-card.png` from
`harness/upscaler-card.py`: the per-mode table of the 120 km/h drive — off, FSR 1.0 50 %, bicubic 50 %, DLSS 50 % preset F
and default — from `docs/plan-upscalers.md`; 2026-09-22 night; the zoom section lost its "New!" with it, and the "Better
profiling" heading went: its image `13` now sits inside the "Performance overlay (F9)" section, which kept the page under
the limit). Later that day a text section "Upscaling: how it works, when it helps" (how the world frame is scaled, how to
turn it on, when the GPU is the limit) went under the card; to pay for its ~1,000 characters the `14`, `15` and `16`
card sections left the page (the images stay in the folder and the item's carousel; the README and the options tab keep
those features), the tab close-up `08` left the frame-by-frame section, and several captions were shortened. `18` the
"New! Render distance" card (`docs/media/render-distance-card.png` from `harness/render-distance-card.py`: vanilla 19x19
chunk grid vs 15x15 on the Rosewood spin and the 120 km/h drive, everything on, runs `prev-gridspin3-*` /
`card-grid-drive-*`; 2026-09-22 night, release da3cdea): the upscaler card lost its "New!" and took the place of the
"Upscaling: how it works" heading, and two upscaling sentences were shortened (page 7,878 substituted characters). `19`
the "New! Performance overlay in the menus" card (`docs/media/overlay-item-card.png` from `harness/overlay-item-card.py`:
the main menu with the pad focus on the SHOW / HIDE PERFORMANCE OVERLAY item and the pause menu, captures of
`harness/pad-overlay-check.sh`; 2026-09-23, release 4ab3fe8): the render distance card lost its "New!", the showcase
caption and two upscaler sentences were shortened (page 7,899 substituted characters). `20` the "New! Zombie hordes on
all cores" card (`docs/media/zombie-cores-comparison.png` from `harness/zombie-cores-table.py`: the Louisville horde,
four alternating pairs of the same build with the zombie postupdate pass's keys off vs on, runs `zt4-r-*`; 2026-09-23,
release 5db6a37): the overlay, render distance and upscaler cards lost their `[h1]` headings (their images carry the
titles; page 7,892 substituted characters). `21` the "New! NVIDIA Reflex-style low latency" card
(`docs/media/input-latency-reflex.png` from `harness/reflex-table.py`: input -> screen at a 60 fps cap with vsync, stock
`il-60-base` vs all input-latency keys + `reflexBoost`, mean of `il-60-boost` / `il-60-boostb`; 2026-09-24): the zombie
card lost its `[h1]`, the upscaler caption was shortened (page 7,891 substituted characters). `22` the "New! Variable refresh" card
(`docs/media/vrr.png` from `harness/vrr-table.py`: the desktop VRR runs of `docs/archive/2026-09-24/findings-vrr-2026-09-24.md`, without vs with the
VRR work; 2026-09-24): the low-latency card lost its `[h1]`, the upscaler caption was shortened again. `23` the "New! Zombie detail follows the frame cap" card
(`docs/media/zombie-lod-card.png` from `harness/zombie-lod-card.py`: the Louisville horde at a 144 and a 240 fps cap, `zombieLodDynamic`
off vs on, runs `lod-144-off` / `lod2-144-off` / `lod2-144-on` / `lod-240-off` / `lod2-240-on`; 2026-09-24, release eb305b6): the VRR card
lost its `[h1]`, the upscaler card's "Helps when ..." line went (page 7,890 substituted characters). `24` the "New! HDR output (Linux)" card
(`docs/media/hdr-card.png` from `harness/hdr-card.py`: stock SDR vs HDR at the defaults, panel nits of the panes of
`docs/media/hdr-stock-vs-hdr-vs-enhanced.mp4` (runs `hdrvid-*`) and the frame cost of `hdrperf-off` / `hdrperf-enc-false`; 2026-09-24,
release ecc868d): the zombie-detail card lost its `[h1]`, the upscaler caption lost its console line (page 7,847 substituted characters).
`25` the "New! Power efficiency" card (`docs/media/power-efficiency.png` from `harness/power-table.py`: package power at a
120 fps cap on the 100 s walk, stock / the previous release / this one, the flip in balanced and power-saver and the MacBook's
system power, runs of `docs/findings-ecores-2026-09-24.md`; 2026-09-24): the HDR card lost its `[h1]`, the mods-comparison
caption lost three parentheticals (page 7,873 substituted characters).
`26` the "New! Clear audio" card (`docs/media/clear-audio.png` from `harness/audio-card.py`: the sound pass of
`docs/findings-sound-2026-09-24.md` in the Louisville horde + thunderstorm + alarms + gunfire scene, stock vs this release:
clipped samples with a pistol and the assault-rifle stress, true peak, sound code per game-thread frame, Jev's audio verdict;
runs `snd-i4-*`, `snd-i6-*`, `snd-l2-*`; 2026-09-24): the power card lost its `[h1]`, the upscaler caption became one line
(page 7,854 substituted characters). The same day the section became the first animated "New!" card:
`26-clear-audio.gif` (`harness/audio-card-gif.py` on `harness/newcard.py`, 1280 px wide, fonts sized for Steam's 655 px
description column): the still card's text and table in
the right half, and in the left half the assault-rifle stress runs `snd-i6-stress-stock2` / `snd-i6-stress-opt` playing
from 4 s after their route start, each with the game's own audio stream drawn as it plays (the last 1.5 s of the
waveform, samples at full scale in amber) and a running clipped-sample count. Every "New!" section from here on is a GIF
in that layout (release-windows skill); the JPG `26` stays for the item's carousel.
`27` the "New! Ambient occlusion" card (`harness/ao-card-gif.py`, the second animated card: a divider sweeping across the
same frame of the Rosewood house with AO off and on, the `--shot-at` captures `ao-shot-off2` / `ao-final-on`, which line
up to the pixel; the table is the AO GPU cost standing / walking / 120 km/h drive and the capped drive's fps and p99 with
AO off vs on, from `docs/findings-ambient-occlusion-2026-09-24.md`; 2026-09-24): the clear-audio card lost its `[h1]`.
`28` the HDR card again, animated (`harness/hdr-card-gif.py`; 2026-09-25, replaces `24` in its place on the page at the
maintainer's request): the left half plays the HDR showcase reel (`docs/media/hdr-showcase-sdr-vs-hdr.mp4`: fires and
torch, headlights, a lightning strike) with a divider sweeping between the stock SDR pane and the HDR pane, both decoded to
nits and shown with one exposure (the HDR peak at the GIF's white, the SDR white at ~63 % grey); the table keeps the still
card's numbers and adds the 2026-09-25 fix (indoors by day the light map no longer follows the facing). `24` stays in
the folder for older links.
`29` the "New! Enhancements tab" card (`harness/enhancements-card-gif.py`; 2026-09-25, release f837467): the left half
plays run `enh-live-card`, a running game in which the harness's `live_set` rig does what Apply does (ambient occlusion
on, strengths 150 %, off) with a label naming the change in force; the table: upscaler / AO / HDR slider changes need no
restart, DLSS switches the next frame, four AO strengths instead of one, seven before / after preview pairs. The AO card
`27` lost its "New!" heading (its image stays). The HDR card `28` still names Options > Optimizations > HDR output in its
footer (the section moved to the Enhancements tab); re-render it (`harness/hdr-card-gif.py`) when it next changes.
`30` the "New! Smooth Operator - Driving" card (`harness/smooth-card-gif.py`; 2026-09-25, the town-drive pass; re-rendered
for release f05e11d+ from runs `card3-t-*` (table), `card2-stock-1` / `card2-new240-1` (clips) and `flip-td-*` (laptop row);
`docs/findings-town-drive-2026-09-24.md`): the left half plays the stock game (run `td-rel-stock-1`) above this release
(`td-rel-new-1`) on the Rosewood 120 km/h drive at the same route second (the release clip matched to the stock one on
the picture, the stock run's roughest 5 s) with both runs' frame times scrolling under them; the table: fps, 1 % low, p99,
frames off their 240 Hz slot, game-thread load, and the delay present pacing adds (a cost row). The Enhancements card
`29` lost its "New!" heading (its image stays).
`31` the "New! Per-pixel lighting" card (`harness/ppl-card-gif.py`; 2026-09-25, release e1b28ab;
`docs/findings-per-pixel-lighting-2026-09-25.md`): the left half sweeps between the same frame of the torch-lit house at
night with per-pixel lighting off and on (desktop `--shot-at` runs `ppl-shot-false` / `ppl-shot-true`, only `pixelLight`
differs); the table: light between tiles, light through walls, the torch beam, and the GPU cost on the flip laptop from
the drift-free A/B against the stock chunk program (night street +0.05 ms, torch-lit house +0.15 ms, cost rows). The
Smooth Operator card `30` lost its "New!" heading (its image stays).
`32` the "New! Texture compression on the GPU" card (`harness/texcomp-card-gif.py`; 2026-09-25, release dc24455;
`docs/findings-texcompress-2026-09-25.md`): the left half draws the flip's main-menu frame rate from launch as it
happened, stock driver compression (run `tc-final-driver`) vs this release's defaults (run `tc-stage-128`), both with
Texture compression on; the table: menu fps 4-16 s after launch, the slowest 1 % of menu frames, the render thread's
compression time over a boot, game CPU in the first 30 s, and texture detail against uncompressed (zoom-1 screenshot).
The per-pixel lighting card `31` lost its "New!" heading (its image stays).
`33` the "New! Let There Be Light" card (`harness/sun-card-gif.py`; 2026-09-25, sun and contact shadows,
`docs/findings-contact-shadows-2026-09-25.md`): the left half plays the horde-shoot scene at the Riverside pier (Jev shooting
150 zombies, 16:30) from one recorded run whose sun shadows switch off and on every 4 s (`devSunTogglePeriod=4000`, run
`cs-card-horde`), labelled from the console's toggle lines; 7 fps, temporally denoised (8.4 MB); the table: sun shadows,
characters' and cars' shadows, torch / headlight shadows, the GPU cost and the p99 of the capped 120 km/h drive. The
texture-compression card `32` lost its "New!" heading (its image stays).
`34` the "New! Faster local updates" card (`harness/updater-fast-card-gif.py`; 2026-09-26, the near-instant updater,
`docs/findings-updater-2026-09-26.md`): no recording behind it; the left half replays pressing Update now in real time
from the times measured on the MacBook (the previous updater's 2,164 ms download-and-unpack against this release's 4.7 ms
write of the prefetched files, then Restart game), drawn as two copies of the update dialog; 20 fps, 0.19 MB; the table:
click to installed on the Mac and the desktop, bytes per update, the check, the Workshop copy (issue #16). The
Let There Be Light card `33` lost its "New!" heading (its image stays).
`35` the "New! Reflections" card (`harness/ssr-card-gif.py`; 2026-09-26, reflections on water and puddles,
`docs/findings-reflections-2026-09-25.md`): the left half plays the channel below the Sunset bar on the Riverside pier (15:00,
zoom 1) from one run whose reflections switch on and off every 3 s (`devSsrAlternate=3000`, run `ssr-card2`), taken with
the game's own frame capture (`devCapture=6,12,10,50`: the screen recorder had caught the desktop beside a half-width game
window) and labelled from the alternation clock; 96 frames at 10 fps, 96 colours (6.4 MB); the table: what water and
puddles reflect, characters, the GPU cost without water, at a river and in rain. The faster-local-updates card `34` lost
its "New!" heading (its image stays).
`36` the "New! Fluid driving" card (`harness/drive-card-gif.py`; 2026-09-26, vehicles drawn between the physics steps and
the camera placed after the car moved, `docs/findings-car-jitter-2026-09-26.md`): the left half plays the presented frames
of two flip runs on the same stretch east of Rosewood (zoom 0.5, 60 km/h, `devCapture=22,2.5,120,50` stock with
`enabled=false`, run `cj-card-stock1`, whose session had the stale camera on 88 % of frames; `devCapture=9,2.5,120,50`
this release, `cj-card-fix2`), each a fixed window round the car with a fixed cross-hair, 0.6 s of capture shown 4x slower
(every captured frame), and under them each run's car offset from its smooth path frame by frame from its drive log;
72 frames at 30 fps, 128 colours (8.4 MB; 1.6 s with taller panels was 44 MB: the moving asphalt does not compress); the
table: world judder, the car's wobble, frames it moves backwards, frames without motion (Rosewood, 60 km/h, zoom 1, each
frame judged at the refresh it was shown on) and zoomed-in world judder. The Reflections card `35` lost its "New!"
heading (its image stays).
`38` the "New! Sharp sprites" card (`harness/spritefilter-card-gif.py`; 2026-09-26, candidate A of the graphics plan,
`docs/findings-sprite-filter-2026-09-26.md`): the left half stacks the same spot at the same moment, stock filtering above and
`spriteFilter=sharp` below, as 3x nearest-neighbour close-ups of the game's own 1:1 frames (the runs of
`docs/media/sprite-filter-stock-vs-sharp.mp4`: `sfv-walk0.75-*`, `sfv-walk1.5-*`, `sfv-drive-*`, `devCapture=...,crop=...,ram`),
3.2 s per scene at 10 fps, 96 colours (3.2 MB); the table: fine detail at 75 % / 175 % / in the 120 km/h drive, the world
pass's GPU time on the drive and zoomed out. The darkness card `37` lost its "New!" heading (its image stays).
`39` the "New! Sun, moon and cloud shadows" card (`harness/sky-card-gif.py`; 2026-09-26, `docs/findings-sky-2026-09-26.md`):
left, the game's own frames (`devCapture`) of the stock look against sun shadows on, runs `card-sky-{clouds,evening,moon}-{off,on}`:
Rosewood at 16:00 under 50 % clouds drifting at 8x speed, at 19:30 (long shadows), at 23:00 under the 1993-07-03 full moon;
right, what is new and the chunk composite's GPU time with clouds on (632 vs 623 us). The sharp sprites card `38` lost its
"New!" heading (its image stays).
`40` the "New! Installation" card (`harness/install-walkthrough.py --card`; 2026-09-27, the in-game install / uninstall), with
a layout of its own (the New! template's left column is
~276 px on the page, too small for a terminal): the Windows walkthrough across the whole card (1 Copy the command in the
install helper window, 2 paste into PowerShell, the installer waits, 3 QUIT, 4 installed, 5 Options > Optimizations with the
Uninstall button), before / now as two lines under it. The sun, moon and cloud shadows card `39` lost its "New!" heading
(its image stays). The game pictures are the game's own
screenshots of `harness/uninstall-e2e.sh` on the flip (install window, main menu, the Optimizations tab); the install window
is redrawn per OS with that OS's command, the terminals are drawn with the installers' real lines. The same script writes the
install window's own animation (`--frames`: 13 PNG frames per OS under `src/workshop/42/media/ui/pzopt_install/<os>/` (unique names: `getTexture` resolves a bare name like `01` to a vanilla pack image first) + the
hold times in `PZ_Optimization_InstallFrames.lua`; the game has no GIF decoder before the classes are installed) and the
Mods list posters (`--posters`: `poster-install.png`, the three steps, drawn at ~200 px next to the description, and
`poster-step1..5.png`, the thumbnails, large on hover), which a subscriber sees before enabling anything.
`description.txt` embeds them with `[img]` from the raw GitHub URL of `master`,
so they render only after the folder is pushed. The staged page carries da.gd short links instead (2026-09-24,
`scripts/workshop-shorten.py`, cache `docs/workshop/short-urls.txt`, ~80 characters saved per image; da.gd is the one
shortener whose redirect sends `Access-Control-Allow-Origin: *`, which Steam's `crossorigin="anonymous"` images need).
If da.gd ever goes away, delete the cache line or switch the helper to another CORS-clean shortener; the long URLs in
`description.txt` still work but the page is ~1,600 characters longer. The same files go in the item's own carousel:
on the Workshop page, "Add/edit images & videos" takes the JPGs (upload `00` first, it becomes
the header) and a YouTube URL for the showcase video.
`41` the "New! God rays" card (`harness/godrays-card-gif.py`; 2026-09-27): left, god rays off then on in the game's own frames (the lossless panes of the showcase video `docs/media/god-rays-off-vs-on.mp4`, runs `gv-room-*` and `gv-fog-*`: a Rosewood diner at 17:00, the church lot in morning fog at 08:00); right, what it draws and the frame time it adds (12 us, 0.3 % of a 240 fps frame; `docs/findings-god-rays-2026-09-27.md`). The installation card `40` is pinned under the "Install" heading instead (the maintainer, 2026-09-27: it stays on top of the install instructions whatever "New!" section comes next).
`43` the "New! Foliage sway" card (`harness/sway-card-gif.py`; 2026-09-28, `docs/findings-foliage-sway-2026-09-27.md`): the left half plays two in-game captures of the church lot's graveyard in a strong wind (`--flag wind=1`, default settings, `devCapture=10,5,20,100,crop=2100:200:1024:640`, runs `sway-card-false` / `sway-card-true`), the game as it is above and foliage sway below, cropped at 1:1 on the bushes (a scaled-down crop hid the movement at the page's half size); 99 frames at 20 fps, 128 colours (6.2 MB); the table: plants in the wind, walking through bushes, the frame time against the game's own wind option. The pixel perfect shadows card `42` lost its "New!" heading (its image stays).
`44` the "New! Real Blood" card (`harness/blood-card-gif.py`; 2026-09-29, `docs/findings-blood-decals-2026-09-29.md`): the left half plays two in-game captures of a zombie crowd (`--flag crowd=15`) round the bench car at 15:00 in 400 fresh splats (`--flag blood_burst=400 --flag blood_burst_at=1 --flag blood_fill=40`, `devCapture=2,6,20,100,crop=2090:800:1024:640`, runs `rb-card-stock` with `--prop enabled=false` / `rb-card-new` with `bloodWet=true reflections=true`), the game as it is above and this release below, at 1:1; the table: blood per chunk picture, floor re-draws for new blood, the game thread on blood in a big fight, wet blood's cost. The foliage sway card `43` lost its "New!" heading (its image stays).
`45` the "New! Zero Lag UI" card (`harness/ui-card-gif.py`; 2026-09-30, `docs/findings-ui-snappy-2026-09-30.md`): the left half plays this release's recording of the UI rig's scenes (run `final-rec`, `--flag ui_script=scenes`: HUD, inventory open, crafting window, world map; 3 s each from 2 s after the scene's mark, tone-mapped crops) with bars for the UI's share of every frame, stock vs this release (runs `final-stock` / `final-scenes`, `harness/uiprof.py`); the table: HUD, inventory, crafting, map, and a click / key reaching the screen (the next 60 Hz UI render vs the same frame). The Real Blood card `44` lost its "New!" heading (its image stays). To stay under 7,900 characters the render distance card `18` left the page (its image stays in the repo) and the upscaler card's line dropped "then restart" (it applies live since 2026-09-25).
`46` the "New! Relief" card (`harness/relief-card-gif.py`; 2026-09-30, `docs/findings-relief-2026-09-30.md`): the left half is the same frame of the Rosewood stone house at 11:00 with relief off and on (runs `px30-final-off` / `px30-final-on`, the released build, pixelLight, depth 150 %, shown at 1.6x: the relief is fine detail and the page shows the card at half size), a divider sweeping across it; the table: GPU time a frame for the sun (baked), each new chunk picture, the torch with pixelLight, the 120 km/h drive, video memory. The Zero Lag UI card `45` lost its "New!" heading (its image stays). To stay under 7,900 characters the overlay item card `19` left the page (its image stays in the repo).
`47` the "New! Share settings" card (`harness/share-card-gif.py`; 2026-10-01, the Export settings / Import settings... buttons of the three options tabs): no recording behind it; the left half steps through the game's own screenshots of the `options_io` rig (run `settings-io-check`: the tab's buttons with the pressed one ringed, the Export message, the Import box filled from the clipboard, the result) with a crossfade, 10 fps, 0.96 MB; the Export message's path line (the rig's test folder under the home directory) is repainted as the generic `.../Zomboid/pzopt/settings-export.ini`; the table: copying a setup, loading a shared one, the backup file. The Relief card `46` lost its "New!" heading (its image stays). To stay under 7,900 characters the upscaler card `17` and its "Turn it on" line left the page (its image stays in the repo).
`59` the "New! Settings in one place" card (`harness/settings-card-gif.py`; 2026-10-04): left, the game's own screenshots of the options window, the old Optimizations tab (run `settings-card-before`, origin/master 69d02d9) then the new single tab's home page, a category, the Visuals cards, Fix a problem and the search (run `settings-card-after`, harness flag `options_shots`), each held with a crossfade (a pan made every frame differ: 17.5 MB, held 3.1 MB); right, before vs this release (tabs, first screen, the Simple view's 80 of 382 settings, Fix a problem, search).

`48` the "New! Mod compatibility" card (`harness/modcompat-card-gif.py`; 2026-10-01, release 4f09a4f, `docs/findings-mod-compat-2026-10-01.md`): the left half plays CleanUI's inventory with PZ Optimization (run `mc-cleanui-rec`: NeatUI Framework + CleanUI, `--flag ui_script=scenes --flag inventory=open`, 6 s from 27 s, tone-mapped crop with a gamma lift, the scene is dark) and under it the tested mods ticking in one by one (CleanUI + NeatUI, Inventory Tetris, TwisTonFire Proximity Inventory, Item Condition Overlay, Bandits, Project A-Life NPCs, PZMulticore, Lugli Optimizations, Ultimate ZBetterFPS); the table: Lua errors with zombies trampling crops 13 -> 0, late frames of a mod's HUD 18 -> 0, a mod's ISInventoryPage kept, a Java mod's patches switching settings off; the footer says ZombieBuddy cannot load Java mods on 42.21 yet. 12 fps, 8 s loop, 1.23 MB. The Share settings card `47` lost its "New!" heading (its image stays); to stay under 7,900 characters the zombie cores card `20` left the page (its image stays in the repo).
`50` the "New! Glass car windows" card (`harness/carglass-card-gif.py`; 2026-10-02, release c70cda6, `docs/findings-car-glass-2026-10-01.md`): the left half sweeps a divider between the same frame of parked cars (the sheriff car, the black CarLuxury coupe, a sedan; run `cg-card`, `car_rig=6`, 10:30) with the stock windows and with the glass, both frames from one devCapture run alternating the glass (`devCarGlassAlternate`) over a still camera; the right half the cost rows (the 120 km/h drive, 33 parked cars on a busy desktop, the render thread per car, no car on screen).
`51` the "New! Mod compatibility check" card (`harness/compatcheck-card-gif.py`; 2026-10-02): the left half steps through four in-game screenshots of the `compat_check` rig with the Java test mod of `harness/compat/make-java-fixture.sh` (runs compat-prof-a/b/c: the main menu with the new item, the check on Max performance, Max compatibility in force after a restart, switching back with Restart game), the relevant part outlined; the right half is before / this release. No numbers: the change is the choice and its default.
`52` the "New! Driving through the trees" card (`harness/treecut-card-gif.py`, from `harness/smooth-card-gif.py`; 2026-10-03, `docs/findings-4221-drive-regression-2026-10-03.md`): the left half plays the recorded 120 km/h drives `r4221-final-drive-rec-off` (the previous release's tree handling) and `r4221-card-hotfix` (this release, the cutaway hotfix) at the same route second, hable tone-mapped, cropped right of the in-game overlay, with live fps and the last 3 s of frame times; the right half the desktop and Mac numbers from the unrecorded runs; the "Mod compatibility check" card lost its "New!" heading with it, and `21-low-latency.jpg` left the page (7,933 characters otherwise). `53` the "New! Dynamic resolution" card (`harness/dynres-card-gif.py`; 2026-10-03, release 3bd4ae3,
`docs/findings-dynamic-resolution-2026-10-03.md`): the left half plays the same still spot on the flip (Radeon 890M, 1920x1080,
zoom 1) while the render size is forced 60 <-> 100 % every second, with FSR 1.0 (`flip-dr-card-fsr1`) and with taau
(`flip-dr-card-taau`), 1:1 RGBA devCapture crops shown 2x with each frame's render size, and under them the frame rate per
quarter second of the desktop's heavy-GPU-load runs `dr-sq-off4` / `dr-sq-model10-taau`; the right half the desktop and flip
numbers; the trees card lost its "New!" heading with it, and `26-clear-audio.gif` left the page (7,926 characters otherwise). `54` the "New! People in cars" card (`harness/occupant-card-gif.py`; 2026-10-03, `docs/findings-car-occupant-2026-10-03.md`): the left half plays the same 60 km/h drive twice (runs `occ-card-off` / `occ-card-impostor`, car glass on, zoom 0.5, in-game 30 fps devCapture crops of the car) cut at the same capture frame, the empty cabin above and the driver below; the right half the GPU cost parked and driving and the drive's frame rate from the occ-cost / occ-final runs; the "Dynamic resolution" card lost its "New!" heading with it, and `27-ambient-occlusion.gif` left the page. `55` the "New! Occluded zombie outlines" card (`harness/outline-card-gif.py`; 2026-10-03, `docs/findings-occluded-outlines-2026-10-03.md`): the left half is run `outl-video4` (200 idle zombies round the church lot, zoom 1, width 2, the setting switching every 2.5 s, in-game 1:1 capture at 10 fps), each OFF frame above the ON frame at the same moment of the next period; the right half the in-run A/B frame times (crowd of 60, 300 zombies) and the PR as submitted; the "People in cars" card lost its "New!" heading with it, and `23-zombie-detail.jpg` and `22-vrr.jpg` left the page (7,934 characters otherwise). `56` the "New! Light from the torch itself" card (`harness/torchsource-card-gif.py`; 2026-10-03, `docs/findings-torch-source-2026-10-03.md`): the left half plays the same slow turn at 01:00 with a hand torch and a lantern twice (runs `ts-card-off` / `ts-card-on`, per-pixel lighting, in-game 12 fps devCapture crops), stock above and the light from the item below; the right half where the light starts, the game-thread, frame and GPU cost and the headlight correction. The outlines card lost its "New!" heading with it, and `25-power-efficiency.jpg` left the page (over 7,900 characters otherwise).
`80` the "New! Glass, screens and steel reflect" card (`harness/props-card-gif.py`; 2026-10-09, `docs/findings-prop-reflections-2026-10-08.md`): the left half plays the same walk past the showroom row of props (runs `card-props-false` / `card-props-true` on the desktop, in-game 30 fps devCapture of the whole frame at 25 %, cropped to the props and the car), reflections off above and this release below; the right half the desktop and flip GPU cost and the flip walk's p99.

### Animated thumbnail

`docs/workshop/images/00-showcase-thumbnail.gif` (`harness/showcase-thumbnail-gif.py`): the
results-card capture from 25 s, a square crop centred on the character (the game camera follows
them, so the crop is fixed), "PZ Optimized" on a band at the top and the performance overlay
pasted live along the bottom (the left 747x305 of the panel at 0.6x: fps / ms, percentiles,
1 %-low / jitter / spikes, loads, verdict, graph; text ~14 px, not denoised). 448x448, 12 fps,
53 frames (4.4 s: the shot, then the in-game zoom-out and the walk; per-frame delays 8/8/9 cs so
the GIF plays at real time), 96 colours, median-3 denoise on the game part, gifsicle
`--lossy=100`: 987,416 bytes. Steam's preview limit is
1,000,000 bytes, not 1 MiB (the game's own check says 1,024,000): a 1,011,209-byte GIF came back
from steamcmd with `Failed to update workshop item (Limit exceeded)`. The asphalt
grain is what costs (plain LZW is ~145 KB a frame at 512 px whatever the palette); ImageMagick's
fuzz transparency ghosts on the panning camera and dither triples the size, so neither is used.

Until 2026-09-21 the GIF was 6 fps (26 frames at ~35 KB). Doubling the rate at the same per-frame
quality came from the HUD elements, not the scene (script header for the numbers): the banner's
text rows and the header band are translucent, so the scrolling game behind them changed every
pixel of a static panel; they now keep their previous pixels within a tolerance (glyph changes
always pass, the panel background freezes: `GIF_TEXT_TOL`, `GIF_HUD_TOL`), and the frame graph is
redrawn every 4th frame (`GIF_GRAPH_EVERY`). The scene rows only get a spatial denoise
(`GIF_SCENE_VF`, hqdn3d 3/2 + a mild bilateral: SSIM 0.943 against the composited frames vs the old
GIF's 0.948). Temporal holds on the scene are out: the camera is never still in this clip (a
(-4,-2) px/frame drift under the opening shot), so held low-contrast asphalt turns into a stale
mosaic (SSIM 0.915 at a 6-level tolerance); gifski's dither crawls; 64-80 colours posterize.
Steam re-encodes the GIF on its CDN (frames kept) and shows it at 268 px on the item page, 448 px
behind the enlarge click, and at native size where the description embeds it from GitHub.
Needs `gifsicle` (`pacman -S gifsicle`, or `GIFSICLE=<binary>`; on the desktop it is built from
source into `~/.local/bin`). `GIF_END=x:y:w` gives the older zoom-into-the-overlay variant.
`09-performance-overlay.jpg` is the overlay panel cropped
from the 25 s frame for the carousel and the "Performance overlay" section.

The game's uploader hard-codes `preview.png` and rejects anything that is not a PNG, so every in-game
upload replaced the GIF. `scripts/workshop-upload.py` calls `SetItemPreview` itself and sends
`preview.gif` whenever it is staged and <= 1,000,000 bytes (`--preview png|<file>` overrides); the
steamcmd `item.vdf` route is gone (2026-09-24).

## What the item cannot do

- Nothing on the Workshop can write to the game folder; the install stays one command (the helper
  window, or `irm .../install.ps1 | iex` / `curl .../install.sh | bash`, which find the Workshop copy
  in the game's library themselves). The uninstall is in the game (main menu > PZ OPTIMIZATION > Uninstall PZ Optimization...).
- The three Lua files under `pzopt-classes/media/lua/` are not loaded from the item (they are
  not under `42/media/`); they reach the game with the class files, as on the GitHub path.
- A game update makes the runtime guard turn the classes off until a build for the new
  revision is uploaded; the page says so under "Updates".
`81` + `82` the "New! Glass, screens and steel reflect" card redone in the AVIF template (2026-10-09, the maintainer: "the new
visual style, same as the entities shadows avif", which it replaces on the page with its still `75`; `80` left the page):
`harness/props-reflect-card.py` = the 630 square, strip "Glass Reflections", stock left of the divider, one sweep per segment:
a Rosewood back yard with two sliding glass doors and a glass table placed on the patio, the player walking on foot along the
doors to the table and back (desktop runs `gpc2-stock-cap` / `gpc2-enh-cap`, enhanced = every Visuals card but remembered
places, HDR and upscaling off; in-game 30 fps 1:1 devCapture crops round the player), the patio halved, then 1:1 at the table
and the doors; `harness/props-reflect-changes.py` = the still under it (what reflects, stock vs now, the cost per frame).
`89` the "New! A built-in benchmark, one menu for everything" card (2026-10-18, the AVIF template, one animation and no
still under it, the maintainer's choice; it replaces the Louisville New! section on the page, whose `87` / `88` stay in the
README under a plain heading): `harness/benchmark-menu-card.py` = the 630 square, strip "Benchmark and Menu", real in-game
screenshots only, six segments with a short cross-fade, each captioned on a dark band under the picture: the main menu's
PZ OPTIMIZATION item, an Optimizations category (sidebar, breadcrumbs, detail, controller hint bar), Benchmark > Setup (the
five runs), a horde shooting pass on the desktop (the HUD line at 1:1, then the horde; `bench-horde-ready-20261018-154604`),
Benchmark > Report of the desktop's A/B run `20261018-035343` (stock and optimized fps per scene, score 1,636 vs 576, the
charts) and Benchmark > Compare (its section column only: its scores belong to two other runs). The menu screens come from
the desktop's 1280x720 large-font tour of the release build (`benchmark-run.sh ui:read --screen 1280x720 --font 3`, run
`bench-card-720-20261018-223032`), shown as 698x720 windows panned left to right at 0.79x.
`90` the "New! Voxel lighting" card (2026-10-19, under `89`, both New! sections stay; the AVIF template, one animation):
`harness/voxel-card.py` = the 630 square, strip "Voxel Lighting", stock left of the divider, one sweep: the night lot by the
bench save at 01:00, grid power and light switches on, clear, zoom 1, the camera panning east (desktop devCapture runs
`vxcard-lot2-stock` with `enabled=false` / `vxcard-lot2-enh` with `pixelLight=true voxelLight=true`, empty options file, half
size, 30 fps), the stock frame matched to the enhanced one within 0.35 s of route time; caption under it with the menu path.
