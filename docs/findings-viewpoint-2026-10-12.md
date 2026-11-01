# Project Viewpoint 0.1.6: profile and its upscalers vs ours (2026-10-12)

Workshop 3809306528 updated 2026-10-11 to 0.1.6 (`build.properties` date 2026-10-10, still pinned to Build 42.21.0). The jar
grew 2.1 -> 33.7 MB: 654 -> 4,919 classes, of which 3,840 are a bundled LWJGL Vulkan binding (`org.lwjgl.vulkan`) and the
rest new renderer code (`viewpoint/upscale` 36 classes, render / world / far / camera / gunplay ...), plus textures (cloud
noise, terrain relief, Tony McMapface LUT, SMAA area texture). Follow-up of `docs/findings-viewpoint-2026-10-07.md`.

Rig: `~/src/viewpoint-1012/` (`submit.sh`, `vp-wrap.sh`; CFR output in `cfr/`, unpacked jar in `jar/`, the download in
`dl/` fetched with `HOME=<tmp> steamcmd`). The run cache `~/.cache/pzopt-workshop/.../3809306528` now holds 0.1.6; 0.1.5a is
kept at `~/src/viewpoint-1007/ws-0.1.5a-3809306528`. Two rig changes since 10-07:

- 0.1.6 gates first person on `onboarding.edition >= 1` as well as `onboarding.finished=true`; the 10-07 wrapper only wrote
  the latter, so the O press did nothing (runs `vp16-*`, discarded). The new `vp-wrap.sh` writes both.
- The desktop is 165 Hz now: without `--option vsync=false` every side sat at the vsync cap (164 fps, runs `vp16-*`).

## Numbers (route window, in-game overlay; desktop 5120x2160, vsync off, Default preset, upscaling off)

| bench | side | fps | p99 ms | p99.9 ms | 1 %-low | GPU | busiest core | game thread waiting |
|---|---|---|---|---|---|---|---|---|
| drive 120 km/h (42 s) | Viewpoint 0.1.6 first person | 133.0 | 10.7 | 19.7 | 93 | 92 % / 372 W | 81 % | 53 % |
| | ours + Viewpoint 0.1.6 first person | 137.3 | 10.3 | 15.7 | 97 | 97 % / 384 W | 57 % | 76 % |
| | *0.1.5a first person (10-07)* | *217.6* | *8.8* | *15.4* | *114* | *97 %* | | *45-52 %* |
| spin (Rosewood, 25 s) | Viewpoint 0.1.6 first person | 123.4 | 11.9 | 23.8 | 84 | 95 % / 373 W | 63 % | 63 % |
| | ours + Viewpoint 0.1.6 first person | 117.4 | 12.2 | 19.2 | 82 | 95 % / 360 W | 59 % | 68 % |
| | *0.1.5a first person (10-07)* | *174.2* | *9.4* | *18.3* | *106* | *97 %* | | *45-52 %* |

Runs `vp16b-drive120-vpfp`, `vp16b-drive120-optvp`, `vp16b-spin-vpfp`, `vp16b-spin-optvp` (jobs 11115-11118). One run each.

- **Still GPU-bound, and the GPU frame is ~65 % dearer.** Viewpoint's own timers: 6.9-7.6 ms a frame on the drive, 7.7-8.5 ms
  on the spin (0.1.5a: 4.1-4.6 ms). New or grown passes: G-buffer 1.25-1.75 (was 0.8-1.0), "occlusion" 1.2-1.65 (new
  category), sky+light 1.25-1.6 (was 0.4), GTAO 0.72, reflections 0.3-0.8 (+ reflection copy 0.1-0.6), cloud trace 0.36,
  floors 0.4-0.8. post+TAA unchanged at 1.1. So 0.1.5a -> 0.1.6 is 218 -> 133 fps on the drive and 174 -> 123 on the spin at the
  default preset. (The 10-07 runs were on the 240 Hz desktop; both sets are uncapped and GPU-bound, so the display does
  not explain the gap.)
- **Ours under it:** the frame rate is Viewpoint's (137 vs 133, 117 vs 123: inside the spread of one run each, and the spin
  scene's GPU cost differed by 0.5-0.8 ms between the two runs). The game thread is lighter: busiest core 81 -> 57 % on the
  drive, hand-off wait 53 -> 76 %; p99.9 19.7 -> 15.7 (drive) and 23.8 -> 19.2 (spin). Same 92 patch targets found
  (`[Viewpoint] compat: all 92 patch targets and 73 private members found`).
- MangoHud's GPU clock / power columns read idle (600 MHz, 37 W) in these runs while sysmon had 2.9 GHz / 372 W: use the
  sysmon line.
- Every run (both sides) ends with a JVM SIGSEGV in the VM thread at exit (`MethodTrainingData::cleanup` /
  `SystemDictionaryShared::check_for_exclusion`: the game JVM's AOT training-data dump), after `run finished, quitting`.
  It does not touch the route window.

## How their DLSS works, next to ours

| | Viewpoint 0.1.6 | ours (`upscaler=dlss`, `pzopt.Dlss` + `src/native/pzopt_ngx.cpp`) |
|---|---|---|
| What it upscales | its own first-person deferred renderer (replaces its TAA); the iso view gets nothing | the stock iso world pass (chunk composite) |
| NVIDIA API | **Streamline 2.14.1** (`slInit`, `slDLSSSetOptions`, `slSetConstants`, `slSetTagForFrame`, `slEvaluateFeature`, `slHookVkPresent`), DLSS 310.9.1 | **NGX directly** (NVSDK_NGX Vulkan API, static SDK in our C++ shim) |
| Glue code | pure Java: LWJGL Vulkan + `JNI.callP*I` on function pointers from `sl.interposer.dll`; C structs laid out by hand (`NativeStruct` with Streamline GUID headers) | a C++ shim `libpzopt_ngx64.so` called from Java |
| GL <-> Vulkan sharing | a separate `VkDevice`; 4 exported images (`VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32`) imported with `GL_EXT_memory_object_win32`, two exported semaphores imported with `GL_EXT_semaphore_win32`; queue-family ownership barriers to / from `VK_QUEUE_FAMILY_EXTERNAL` | same scheme (device matched by UUID, exported images + semaphores) with `GL_EXT_memory_object_fd` / `GL_EXT_semaphore_fd`; the shim also has a Win32 branch |
| Images | colour RGBA16F (copied from their frame with `glCopyImageSubData`), depth R32F + motion RG16F drawn by `upscale_inputs.frag` from their scene depth, far-world depth and velocity G-buffer, output RGBA16F; one image set | colour (the world pass draws straight into it, `dlssDirectColor`), depth resampled, camera motion + per-object MVs via stencil ids, output; two sets possible (`dlssPipeline`) |
| Frame sync | `glSignalSemaphoreEXT` + `glFlush`, submit on a 2-deep fence ring, `glWaitSemaphoreEXT`: no CPU wait unless the ring is full | same pattern (ring of fences, semaphores per set) |
| Camera data | full perspective constants: view->clip, clip->view, clip->prev-clip and back, camera position / axes, near 0.05 / far 400, FOV, jitter (negated), `motionVectorScale` = render size, reset on cuts | orthographic iso camera, Halton jitter through the viewport offset |
| Quality | Streamline modes by render scale 100/67/58/50/33 -> DLAA / MaxQuality / Balanced / MaxPerformance / UltraPerformance; preset not set (NVIDIA's default for 310.9) | `dlssPreset=e`, `dlssOutputPct=67` + RCAS: on our ortho 2D world the default transformer preset cost more than the render-scale saved (plan-upscalers, 2026-09-22) |
| Natives | **downloaded at runtime** from the vendors' GitHub into `Zomboid/cache/viewpoint-natives` on a click in their settings: `nvngx_dlss.dll` (59 MB, NVIDIA/DLSS v310.9.1 `lib/Windows_x86_64`), `sl.interposer/common/dlss.dll` pulled out of the 600 MB Streamline release zip with HTTP Range requests (`ZipRanges`: central directory, then only the three entries), each checked by size + SHA-256 | none in releases (`PZOPT_DLSS=0`); DLSS needs a checkout build with the SDK; a released install runs `dlss` as fsr1 |
| Platforms | **Windows only**: `VulkanDevice.unavailable()` needs `GL_EXT_memory_object_win32` + `GL_EXT_semaphore_win32` and the libraries are DLLs. On Linux / macOS it logs "cannot run here ... the frame is drawn with the TAA meanwhile" | Linux (tested); the Win32 branch is untested |
| Fallback | TAA (their `taaPlus` default) | fsr1 |
| Also | FSR 3 upscaling and **FSR 3 frame generation** (`amd_fidelityfx_vk.dll` v1.1.4, interpolated frame + HUD-less image through the same bridge), XeSS 3.0.2 (`libxess.dll`); GL-side FSR1 EASU, SMAA, FXAA | fsr1 (EASU + RCAS), bicubic, DLSS; no frame generation |

Not measured: their DLSS / FSR 3 / XeSS cannot run on this Linux desktop (Windows-only interop), and no Windows machine is
on the queue. A Proton run would need the Win32 GL extensions, which the native NVIDIA GL under Proton does not expose
either.

What we could take from it (ideas, not code):

1. **Runtime download of NVIDIA's own library** answers our "no natives in a release" rule (2026-09-22: Steam bans `.so`, and
   the 58 MB library is NVIDIA's): fetch `libnvidia-ngx-dlss.so.310.x` from github.com/NVIDIA/DLSS `lib/Linux_x86_64/rel/` on
   request, pinned by SHA-256. Our C++ shim is still ours to ship though (NGX's Vulkan entry points live in NVIDIA's static
   SDK library, so a pure-Java NGX path is not available on Linux; Streamline, which Viewpoint calls from Java, has no Linux
   build). Whether a shim `.so` may ship is the maintainer's call; the Workshop uploader refuses `*.so`.
2. On Windows, Streamline from Java (their route) would avoid a native of our own entirely.

## Code overlap (re-audit of the 10-07 findings)

Identifier-blind 25-token shingles minus stock game code, against a control of unrelated corpora (script and report in
`~/src/viewpoint-1012/audit/`), then a read of every pair that shares distinctive strings or metric definitions. The
shingle pass misses code that was restructured and reworded, so the read is what decides.

Still derived from our `pzopt.Overlay` (the in-game performance overlay), reworded in 0.1.6:

- `viewpoint.platform.Utilization` (byte-identical to 0.1.5a; shingles 0.147 vs control 0.034): our `utilLoop()` step for
  step: `isThreadCpuTimeEnabled` / `setThreadCpuTimeEnabled`, `rid >= 0 ? getThreadCpuTime(rid) : 0L`, the
  `sampledNs != 0 && dt > 0` / `g > 0 && gameCpuNs > 0` guards, `Math.min(100f, 100f * (g - gameCpuNs) / dt)` for game and
  render, `getProcessCpuLoad` / `getCpuLoad` x 100 on a daemon thread every 500 ms, fed by a `renderThreadId` latched on the
  first render frame. (They kept the thread CPU reads on the game thread; ours moved them off it on 2026-09-21.)
- `viewpoint.platform.FrameStats` (new in 0.1.6; in 0.1.5a the same lived in `FrameTimes` / `PerformanceOverlay`, whose
  strings were ours verbatim: "   %4.2f ms   cap %s", "p50 %.1f   p99 %.1f   p99.9 %.1f   max %.1f ms", "1%%-low %.0f fps
  jitter %.1f ms   spikes %d", "heap %.1f/%.1f GB"): our `refreshStats` metric set and definitions, including the two
  that are ours rather than common practice: 1 %-low = 1000 / p99 and spikes = frames over twice the median; jitter = mean
  absolute frame-to-frame change, fps = frames in the last second. Now printed as "median / 99% / 99.9% / worst" and
  "slowest 1% / jitter / spikes".
- `PerformanceOverlay.limit()`: our verdict: at the cap within a tolerance (0.97 vs our 0.98) -> "held by the cap"; else the
  busiest of game / render / GPU, >= 90 % -> "<who> limits it" in our tie-break order (ours "<who> bound"), with a detail
  suffix like our `overlayVerdict=detailed`; else "nothing saturated: waiting or syncing" (ours "nothing saturated: waits or
  sync").

Rewritten: `FrameCaps`, `Viewpoint_FrameCaps.lua` (0.000 / 0.012, down from 0.051 / 0.446): only the names `lockNow` /
`uncappedNow`, the 24 / 244 bounds and the `addCombo` wrapping idea remain.

Same idea, own code: `viewpoint.upscale.ZipRanges` fetches single entries of a remote zip by HTTP range requests, as our
updater's `pzopt.UpdateDelta` does (2026-09-26), and both say "no local header for"; the shared offsets are the ZIP format's,
and the structure differs (ByteBuffer streams per entry vs byte arrays with `le32` helpers and parallel part fetches).

Not derived: the upscaler (all `viewpoint.upscale.*` <= 0.023; their EASU / Catmull-Rom shaders match AMD's code and public
ports closer than ours). A scan of every string literal >= 12 characters and identifier >= 10 characters shared with our
`src/` and absent from the game found nothing else beyond JDK / LWJGL / GLSL API names and game class names.
