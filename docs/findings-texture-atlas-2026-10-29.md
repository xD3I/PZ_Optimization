# Texture atlases cached on disk: the Discord suggestion profiled (2026-10-29)

The suggestion (Discord, 2026-10-29): once a texture is loaded, put it into a texture atlas kept on disk, one per kind
(vehicles, entities, objects, tiles), so the next boot and every later texture load reads the atlas instead of searching
the game's and the mods' texture folders; faster loads, more disk. This pass measured what texture loading costs today,
where it sits on the boot and load path, what it costs during play and in VRAM, and what the closest existing piece of
this idea (`texCompressCache`, an on-disk cache of compressed atlas pages) gives.

Short answer: the game already loads its tiles, UI and most world art from prebuilt atlases (`media/texturepacks`), and
neither the folder lookup nor the disk read is a measurable cost. Where textures are on the load's path at all, the
limit is the hand-off of decoded pixels to the render thread, not reading or decoding files. A disk cache of
ready-to-upload pages already exists (for compressed textures) and saves 0.4 s of a warm load, but costs 3 s on the
boot that fills it and 225 MB of disk. Atlasing the loose textures and loading them whole would multiply their VRAM.

## Rig

- Probe `devTexLoadStats` (dev, off; `pzopt.TexLoadStats`): every `TextureID.generateHwId` timed on the render thread and
  sorted by source (`pack` = an atlas page of `media/texturepacks`, `game` = a loose file of the game's `media`, `mod` =
  a mod's file, `runtime` = no file: framebuffers, clothing composites); totals to world ready and in play, every upload
  over 1 ms during play one console line, per loose folder rows. `FileTaskStats.spans()`: first start .. last end of
  each file-task class (epoch ms, read against `pzopt-loadtrace.out`). The bytes in its lines are the game's own
  accounting (`TextureID.totalMemUsed`: RGBA bytes, never decremented), not resident VRAM; VRAM is sysmon's `vram_mib`.
- Desktop (RTX 4090, 5120x2160, 42.21 `4a0e9546ec`), queue bench `drive-120-south` (60 s budget, 36 s of 120 km/h drive
  through Rosewood: new vehicles, zombies and clothing come into view), `--launcher direct --no-mangohud
  --prop devTexLoadStats=true`, the desktop's settings file (Enhancements on). The harness presses Continue as soon as
  the menu takes input.
- Runs `tex-opt`, `tex-opt2`, `tex-opt-prof`, `tex-stock` (18:21-18:31) had ~3 GB of another program's VRAM in use from
  launch (6.3 GB at launch against 3.2 GB later; the drive ran at 104 fps against 144 fps later): their fps and load times are
  not compared below. `tex-opt-prof` (async-profiler, wall + cpu, every thread) is used only for what the threads do.

## Boot and load

Quiet-GPU runs (18:46-18:57). Boot = launch -> Continue, load = Continue -> world ready, D = the loader's wait for
outstanding assets (`loadtime.py`). Pages / loose = when the last atlas page / loose PNG task ended, seconds after launch.

| run | textures | boot | load | D | pages done | loose done | load end | decoders asleep | render-thread uploads to world ready |
|---|---|---|---|---|---|---|---|---|---|
| `tex-stock2` | stock game, RGBA | 7.69 s | 9.99 s | 3.20 s | 12.15 | 14.09 | 17.25 | 5.1 thread-s | pages 1,782 ms, loose 259 ms |
| `tex-opt3` | ours, RGBA | 5.05 s | 3.13 s | 0.88 s | 7.11 | 7.18 | 7.91 | 18.3 thread-s | pages 1,106 ms, loose 118 ms |
| `tex-comp` | ours, `textureCompression=true` (BC3 built on the GPU) | 5.67 s | 2.62 s | 0.04 s | 6.60 | 8.16 | 8.18 | 5.8 thread-s | pages 1,098 ms, loose 106 ms |
| `tex-cache1` | + `texCompressCache`, first boot (fills it) | 5.45 s | 5.65 s | 3.18 s | 10.07 | 10.09 | 10.84 | 26.6 thread-s | pages 519 ms, loose 101 ms |
| `tex-cache2` | + `texCompressCache`, warm (225 MB on disk) | 5.34 s | **2.22 s** | 0.04 s | 6.35 | 7.43 | 7.45 | 7.4 thread-s | pages 344 ms, loose 92 ms |

- Stock: the textures are done 3 s before the load ends; the load waits for the animation imports (until 16.3 s) and
  the depth maps (17.2 s), both solved in our build (`animClipCache`, `parallelDepthMaps`). Texture loading is not on
  stock's critical path.
- Ours, uncompressed: the last 0.9 s of the load ends with the last loose PNG. The profile of that window
  (`tex-opt-prof`, D = 1.13 s): the 16 file-pool threads were 45 % idle, 24 % decoding PNGs (inflate, the part a disk
  cache would skip reading, 3 %), 20 % asleep in `TextureIDAssetManager.waitFileTask` (50 MB of decoded pixels not yet
  uploaded: `textureBufferMb`), 7 % depth maps. The render thread uploads only in `waitForRenderStateCallback` (while it
  waits for the game thread's next frame, at most 10 ms a flush) and spent the window mostly in the frame pacing and
  waiting for the game frame; uploads were 25-45 % of it. The limit is the hand-off to the render thread, not the disk
  and not the decoder.
- Compressed pages are a quarter of the bytes to hand over: the same load has no texture wait (D 0.04 s).
- `texCompressCache` (deflated BC3 per pack page, `pzopt.TexCache`): warm, the file pool's texture work drops from
  27.0 to 11.7 thread-s and the load from 2.62 to 2.22 s. The boot that fills it encodes every page on the CPU (78.9
  thread-s of pool time) and its load is 3 s longer (5.65 s). It only applies with texture compression on, and is kept
  off by default for its disk space.
- A player who stays at the menu for a few seconds never waits for textures: the last atlas page is decoded about
  6.5-7 s after launch in every one of our runs, before most players press Continue.

## During play

36 s of 120 km/h drive through Rosewood after world ready (route window plus the 5 s settle):

| run | loose game files uploaded | their render-thread time | uploads over 1 ms | slowest | runtime textures (composites, FBOs) |
|---|---|---|---|---|---|
| `tex-stock2` | 1,000 (154 MB) | 104 ms | 125 | (log capped) | 1,531, 510 ms |
| `tex-opt3` | 1,001 (154 MB) | 53 ms | 6, all at world start | 3.4 ms (a moodle icon) | 903, 4.8 ms |
| `tex-comp` / `tex-cache2` | 1,004 / 1,003 | 52 / 51 ms | 10 / 8 | | 910 / 910, 5.7 / 4.6 ms |

The loose files in play are clothing masks, body and damage overlays, vehicle skins, water splashes, 256x256 at most.
Our build already uploads them in ~0.05 ms each; stock's cost per texture (and its 0.5 s of runtime-texture creation)
is the GL name / sync work fixed in the tail pass (`glNoSync`, pooled names). Only two atlas pages loaded during play.
No frame of the drive was a texture upload.

## VRAM

- Atlas pages to world ready: 561 pages, 2,926 MB as RGBA with mips (2,222 MB counted with compression, which the
  game counts as RGBA too: the real BC3 size is a quarter). Loose files to world ready 444 MB, +154 MB during the drive.
- The whole loose library, `media/textures`: 6,140 PNGs, 540 MB on disk, 5.9 GB as RGBA8 (power-of-two sizes, no mips;
  7.9 GB with mips): printMedia 4.8 GB (newspapers, magazines, flyers), Vehicles 381 MB, Clothes 322 MB, WorldItems
  55 MB, the rest under 50 MB each. The drive loaded ~0.6 GB of it, on demand. Atlases loaded per kind would hold the
  whole kind: 0.4 GB of vehicle atlases to show the few cars on screen, 0.3 GB of clothing.
- Whole-GPU peak during the drive (sysmon, quiet runs, minus the 3.2 GB at launch): stock 9.9 GB, ours uncompressed
  13.9 GB, ours compressed 10.4-10.8 GB. Most of it is chunk textures and the Enhancements' targets, not loaded art.

## Verdict on the suggestion

- The premise does not hold for the game's own files: tiles, UI, overlays and trees already come from 561 prebuilt
  atlas pages, and a texture's file is found from the file map built at boot, not by a folder search; reading the
  compressed bytes is 3 % of the decoders' time.
- A cache that skips the decode exists (`texCompressCache`): 0.4 s off a warm load with texture compression on, 3 s
  added to the boot that fills it, 225 MB of disk. A raw-pixel version for uncompressed textures would be ~3 GB of disk
  (the pages' RGBA with mips) to remove a decode that is not the limit.
- Atlasing the loose textures (vehicles, clothing, items) trades on-demand uploads of ~0.05 ms each for whole atlases in
  VRAM; on a 6-8 GB GPU that is the wrong trade.
- Mods' loose textures take the same path as the game's loose PNGs (source `mod` in the probe); not measured with a
  mod list in this pass.

The one lever the profile shows: with uncompressed textures and an immediate Continue, up to ~0.9 s of the load is
decoded pixels waiting for the render thread (the 50 MB hand-off budget, uploads only between game frames). Raising the
budget or flushing uploads harder while the loading screen is up would address it; not tried.
