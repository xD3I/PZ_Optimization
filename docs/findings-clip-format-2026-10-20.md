# Menu clips in AVIF (2026-10-20)

The preview clips in the options menu were GIFs: 512x216, 24 fps, 4 s, 96 colours, tone-mapped from the AV1 HDR
recordings. The goal was clips and pictures that look better, play smoothly, and can carry HDR, at no more than the
GIFs' size. The decoder has to be pure Java, because a release ships no native libraries. Branch `clip-format`,
worktree `~/pzopt-wt/clip-format`.

## Evaluation

Four real menu clips were encoded in each format (queue media jobs 14008, 14013, 14019, 14025;
`harness/clip-format-eval.py`). Decode times are pure Java on the desktop, one thread, per 4 s clip.

"SDR view" is the HDR file put through the GIFs' tone map and scored with VMAF. The highest score it can reach is the
HDR source through the same tone map: 94.0 for hdrday, 95.1 for hdr, 99.3 for towndrive.

| clip | GIF now | WebP q90 | JPEG XL d1 (HDR) | AVIF 10-bit 4:2:0 q75 |
|---|---|---|---|---|
| hdrday | 2.79 MB, VMAF 92.7, 93 ms | 1.32 MB, 95.1, 413 ms | 2.71 MB, SDR view 93.2, 2.9 s | 181 kB, SDR view 91.5, 386 ms |
| hdr (night) | 309 kB, 86.0 | 120 kB, 95.3 | 517 kB, decoded wrong | 37 kB, SDR view 92.6, 201 ms |
| towndrive | 2.71 MB, 71.7 | 2.26 MB, 99.3 | 3.41 MB, 97.3, 3.4 s | 179 kB, SDR view 97.7, 803 ms |
| visuals (SDR pane) | 66 kB, 69.6 | 52 kB, 95.1 | 8-bit d1: 1.5 MB, 95.4, decoded wrong | 8-bit q60: 48 kB, 96.3, 122 ms |

- **APNG:** lossless clips were 13–50 MB each, so APNG was dropped.
- **JPEG XL:** the pure-Java decoder (jxlatte) took 2.2–4 s a clip and decoded two of the four clips completely wrong
  (PSNR 7–14 dB on every frame).
- **WebP:** 8-bit SDR only, and 3–10x the size of the AVIF files.
- **AVIF (chosen):** about 12x smaller than the GIFs, real 10-bit HDR, and it decodes faster than the clip plays.
  - The pure-Java AV1 decoder javif matches dav1d at 66–70 dB on 4:4:4.
  - On 4:2:0 it scores 51–63 dB because it upsamples colour differently; that is not visible at 512 px.
  - 4:2:0 decodes 1.4–1.8x faster than 4:4:4 at the same quality, so it is the one used.

**Licence:** javif is MPL-2.0, a file-level copyleft. Its 129 vendored files keep their source in the repo, and the
licence ships in `pzopt/licenses/javif/`. The maintainer still has to confirm that MPL-2.0 is acceptable.

## Built

- **The decoder.** `scripts/vendor-javif.sh` copies javif 0.1.0 into `src/vendor/` (1.5 MB) as package
  `pzopt.vendor.avif`. `scripts/build.sh` compiles `src/vendor` and ships each vendored library's LICENSE and README.
- **`pzopt.ClipDecoder`** decodes the AVIF frames. It refuses frames over 1024 px, thins a clip to 96 frames and takes
  the frame times from the file.
- **`pzopt.ClipColor`**, the colour conversion:
  - **SDR:** it reproduces the GIFs' ffmpeg tone map to within 2.2/255 on all 96 frames of a real clip (mean 0.28/255).
    The first version missed because ffmpeg's desaturation runs on r+g+b rather than on luma. One result of that: a
    1,000-nit grey already clips to white in the GIFs.
  - **HDR:** it keeps the SDR picture's shadows and midtones, and lets the highlights go past the UI white. Frames are
    stored as GL_RGB9_E5 textures, 4 bytes a pixel as before.
- **`pzopt.GifTextures`** plays `<clip>-<side>.avif` when the file exists, and the GIF otherwise.
  - Decoding runs on two threads at reduced priority, with at most 16 decoded frames waiting. Uploads go to the render
    thread as small jobs.
  - A clip starts playing once the decode is predicted to stay ahead, so a slow PC waits a moment instead of
    stuttering. Both clips of a pair share one clock.
  - A missing file, a decode error or a GL error falls back to the GIF. `-Dpzopt.clipAvif=false` forces the GIFs.
  - Memory is as before: at most two clips, 48 MB each, freed when the menu closes.
- **`harness/menu-gifs.py`** has a new `avif()` beside `gif()` and runs it for every clip (`MENU_AVIF=0` skips it).
  - It writes 10-bit PQ, 4:2:0, at avifenc q80.
  - The burned-in fps counter is blended so it shows the GIF's exact colours in SDR. With HDR on, it is brighter than
    the UI text (the green is about 1.2x the UI white).
  - The sizes go into `harness/menu-gifs.json`.
- **The Lua is unchanged.** It still asks for the `.gif`, and the Java side picks up the `.avif` next to it.
- **Benchmark tool:** `tools/ClipDecodeBench.java`.
- **Tests:** `ClipColorTest` and `ClipDecoderTest`, with fixtures in `tests/pzopt/fixtures/`.

## Sizes

| clip | GIF (stock + opt) | AVIF (stock + opt) |
|---|---|---|
| hdr | 509 kB | 95 kB |
| hdrday | 4.38 MB | 350 kB |
| towndrive (a check of the counter, not a shipped clip) | 4.95 MB | 371 kB |

## In game

Job 14037 installed this build and opened the Enhancements tab on the HDR setting's clips. Both modes played the AVIF
files.

| mode | playing after | all frames up | decode | worst game-thread call | worst render-thread upload |
|---|---|---|---|---|---|
| SDR | 0.77–1.0 s | 2.85 s | 820–898 ms CPU | 1.46 ms (once, at the clip's start) | 1.09 ms |
| hdr=true (HDR textures) | 0.31 s | 0.74 s | ~680 ms CPU | 0.33 ms | 0.28 ms |

- The slower SDR load was probably the game still being busy right after boot.
- The SDR screenshot shows both clips playing cleanly. The HDR screenshot only covers a 4096x1728 corner of the
  window, so it confirms that the clips draw, but not how bright they are on the panel.
- The laptop's decode time was not measured. The estimate is 1.5–2.5x slower than the desktop, still faster than the
  clip plays.

## Open

- The maintainer to confirm the MPL-2.0 licence.
- Merge into `~/pzopt-wt/menu-impeccable`:
  - **Lua:** nothing.
  - **`menu-gifs.py`:** the `avif()` block, two lines at the end of `gif()` and the `avif_bytes` loop in `main()`.
  - **Everything else:** `src/vendor/`, the new classes, the `GifTextures` changes, the `build.sh` vendor and licence
    lines, the tests, the `.avif` files and their `menu-gifs.json` entries.
- The other clips get their AVIF the next time `menu-gifs.py` runs on their recordings. Most of those recordings are
  in cold storage.
