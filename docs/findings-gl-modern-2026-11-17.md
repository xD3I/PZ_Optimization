# GL 4.3–4.6 APIs: DSA, multi-draw indirect, SPIR-V (2026-11-17, the flip)

The request: implement direct state access (GL 4.5), multi-draw indirect (GL 4.3) and SPIR-V shaders (GL 4.6), with a
second code path for macOS (GL 4.1), and profile each one. Machine: diego-flip (Ryzen AI 9 HX 370, Radeon 890M, Mesa
26.2.6 radeonsi, GL 4.6 compatibility, 3840x2160). Code: `pzopt.GlModern`, keys `compositeMdi` and `glDsa`, both off by
default. `GlModern.available()` needs GL 4.5, multi-bind and draw parameters, and is decided on the render thread, so
macOS (4.1, `CoreGl.active`) always takes the stock path.

## Where the render thread's GL time goes (baseline, run `glm-base-asprof`)

Spinning route (`route=S:450 turn=90 zoom=max`, uncapped, empty options file). 135 fps; the game thread is the wall at
96 %. The render thread spends 1.41 ms a frame and the GPU is ~40 % busy. `harness/rtprof.py` (asprof, 1 ms, thread
`main`) puts every GL entry point together at 0.28 ms a frame; draws and binds are microseconds each. The largest
bind-to-edit site is the vision-cone upload (~15 us). So none of the three APIs can move fps on this machine through
the render thread. What they can change is render-thread time, GPU time, and the game-thread work that feeds the
calls.

## DSA (`glDsa`): the vision-cone polygon

`VisibilityPolygon2.Drawer.PolygonData.draw` used bind, glBufferSubData, 3 enables, 3 attribute pointers, a draw,
3 disables and an unbind. With DSA: a VAO made once (`glCreateVertexArrays`, `glVertexArrayAttribFormat`),
`glNamedBufferSubData`, bind the VAO, draw, unbind.

In-run A/B (`devGlAlternate=500`, ABBA, the `vispoly` GPU section split `.on` / `.off`, `harness/glab.py`), three runs:

| run | GPU on - off | render thread on - off |
|---|---|---|
| glm-ab1 | -1.5 us [-9.6, +10.7] | +4.0 us [-3.5, +15.8] |
| glm-ab2 | -0.2 us [-6.1, +8.5] | +3.0 us [-2.7, +11.8] |
| glm-ab3 | +0.3 us [-6.5, +10.8] | -2.0 us [-16.5, +10.4] |

No measurable change. Mesa's validation of the state that bind-to-edit touches is not where this render thread
spends its time. The key stays off. The VAO keeps the code ready for a site with more state churn.

## Multi-draw (`compositeMdi`): the chunk-level composite

Stock does this once per chunk-level texture, ~105 a frame on this route: on the game thread `IndieGL.StartShader`,
the depth / chunkDepth uniforms, the blend / depth state and one sprite through the ring buffer; on the render thread a
shader start, binds and one draw. `pzoptCompositeMdi` (FBORenderCell) makes one record per texture instead (the same
rectangle, chunk depth and filters) and queues one generic drawer. The drawer puts the records in a shader storage
buffer and draws them with `glMultiDrawArraysIndirect` in groups of 16 textures. Each group is one `glBindTextures` of
32 units (colour 0-15, depth 16-31) plus sampler objects for the colour filters; `gl_DrawID` picks the unit in a
`switch` with constant indices. Only while the composite is the game's own program (pixel light, relief, reflections,
sun shadows, sway, god rays, sprite filter and AO all off).

Picture: the same as stock. Still camera (`turn=0`, zombies off, 14:00, clear): `glm-still-final` against
`glm-still-false` differ in 701 / 654 of 2.07 M px exactly and 0 px at 2 %. Two shots 2 s apart within one stock run
differ by 689 px (animation).

Cost, in-run A/B (500 ms ABBA, `composite.on` / `.off`):

| run | variant | composite GPU | composite render thread | whole frame | frame GPU |
|---|---|---|---|---|---|
| glm-ab1 | first version | +235 us [+219, +250] | +17 [+10, +23] | -0.31 ms [-0.54, -0.07] | +0.10 ms |
| glm-ab2 | textureGrad outside the switch, one 32-unit bind, no unbind | +250 | +1 [-24, +19] | -0.33 [-0.60, -0.05] | +0.13 |
| glm-ab-k1 | `devMdiK=1` (no switch, one texture a draw) | +227 | +39 | 0.00 | +0.11 |
| glm-ab3 | records through a persistent coherent mapping | +256 | +15 | -0.23 [-0.48, +0.02] | +0.13 |
| glm-ab-direct | `devMdiDirect` (glMultiDrawArrays, no indirect fetch) | +246 | +8 | -0.42 [-0.65, -0.18] | +0.15 |
| glm-ab-nosamp | `devMdiNoSampler` (the textures' own filters) | +254 | +21 | -0.28 [-0.54, -0.04] | +0.15 |
| glm-ab-flat | `devMdiFsFlat` (no texture reads, wrong picture) | **-213** | -85 | -0.80 | -0.43 |

- The whole frame gets 0.2–0.4 ms faster with it on (about +4 % fps on this game-thread-bound route). That is
  game-thread work: ~105 shader starts, state calls and ring-buffer sprites become one record each.
- Render thread: the first version paid ~112 us a frame in `glBindTextures` (asprof). One call of 32 units, and no
  unbind afterwards, brought it level with stock.
- GPU: +0.13–0.15 ms a frame (composite section +230–255 us), the same in every variant that reads textures. Ruled
  out one by one: implicit derivatives inside the switch, the switch itself (K=1), the record upload (persistent
  mapping), the indirect argument fetch (direct multi-draw), and the sampler objects. Without texture reads the pass
  is 213 us cheaper than stock, so drawing and blending the quads is cheap; the program's two fetches cost about twice
  what stock's cost. Open. Next things to try: read the colour through bindless handles (needs a handle lifecycle
  tied to `TextureID` destruction: GL names are reused), or one `sampler2DArray`-style layout; look at the program
  with `AMD_DEBUG=ps` / RGP on the desktop.
- Off by default. On the flip it trades 0.15 ms of a 40 %-busy GPU for 0.3 ms of the bound game thread, a net fps
  gain. On a GPU-bound machine (the desktop uncapped at ~500 fps) the GPU cost would count; not measured there.

## SPIR-V

The game's shaders cannot load as SPIR-V as they are:

- They are compatibility GLSL (`#version 110` / `120`: `varying`, `gl_FragColor`, uniform initialisers, `#include`).
- About 16 patchers in `ShaderUnit.compile` (HDR, grading, pixel light, cloud shadows, sway, sprite filter...) rewrite
  the sources at runtime.
- Every uniform is looked up by name, while SPIR-V for OpenGL needs explicit locations.

So SPIR-V would mean a GLSL→SPIR-V compiler in the game at runtime, after the patchers. macOS has no `GL_ARB_gl_spirv`
at all. What can be measured is the price: what a cold shader cache costs, and what SPIR-V does to it.

Offline (`spvprobe.c`, surfaceless EGL, GL 4.6 compat, `MESA_SHADER_CACHE_DISABLE=true`, one cold compile per process,
median of 5). 46 of the 80 game fragment shaders convert with a mechanical port (`varying` → in/out, `gl_FragColor` →
an output, explicit uniform locations). Over the 40 that compile both ways, the GLSL total is 316 ms and the SPIR-V total
is 287 ms (-9 %). Small shaders get *slower* through SPIR-V, ~3.5 → ~4.8 ms each (SPIR-V → NIR, and glslang's output is
unoptimised). Only the big ones gain:

| shader | GLSL | SPIR-V |
|---|---|---|
| water_hq | 79 ms | 52 ms |
| water | 43 ms | 35 ms |
| skybox_hires | 23 ms | 12 ms |

In game (`glm-coldcache`: a fresh `MESA_SHADER_CACHE_DIR`, asprof), against the warm baseline `glm-base-asprof`:

- Boot 7.0 s cold vs 9.2 s warm, Continue → world ready 3.7 vs 4.2 s: no cost above run noise.
- The render thread spends +0.2 s more in libgallium over the whole run. radeonsi compiles lazily, mostly at first
  draw.
- Route: p99 17.7 vs 17.6 ms, p99.9 31.3 vs 31.3 ms, max 46 vs 37 ms. At most one compile hitch.

Mesa's disk cache already removes this after the first launch. SPIR-V would save a fraction of 0.2 s once per driver
update, at the price of a runtime compiler and losing the patchers' text rewriting. Not implemented. On macOS
(`glProgramBinary` is there) a program-binary cache is the equivalent, if Mac boots ever show compile time.

## Rigs

- `harness/rtprof.py <run> [--match RE] [--callers glName]`: render-thread self / inclusive time from asprof.
- `harness/glab.py <run>`: the `devGlAlternate` A/B by GPU section and whole frame.
- `--prop devGlAlternate=<ms>` with `devGlAlternateKeys=compositeMdi,glDsa`.
- Dev variants: `devMdiK`, `devMdiDirect`, `devMdiNoSampler`, `devMdiFsFlat`.
- The probe and the conversion script were in the session scratchpad: `spvprobe.c` (one cold compile, GLSL or SPIR-V)
  and `to460.py` (the mechanical GLSL port).
