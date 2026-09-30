# Occluded outline rendering checks

These tests execute the feature's real GLSL on a headless EGL **compatibility** context, matching
the game's GL API rather than a core-profile approximation. They do not launch Project Zomboid, open
a save or install a build.

From the repository root:

```sh
python -m venv /tmp/pz-outline-tests
/tmp/pz-outline-tests/bin/pip install moderngl numpy
PZ_DIR=/path/to/ProjectZomboid/projectzomboid \
    /tmp/pz-outline-tests/bin/python tests/render/occluded_outline.py
```

Requires Java 25 or newer, OpenGL 4.3, EGL and a Linux surfaceless EGL backend. The suite compiles
the production source instrumenter and its small export entry point into a temporary directory.
`PZ_DIR` enables checks against the installed shader assets; without it, that test is explicitly
skipped. Context, compilation, linking and assertion failures fail the run. The actual renderer is
printed; a software-rendered run must not be described as a hardware result.

To additionally test KI5/DAMNLib, set `PZ_DAMNLIB_SHADERS` to its `common/media/shaders` directory
alongside `PZ_DIR`. The suite uses the actual mod files without copying them into the repo, resolves
their stock shader includes, and checks body/roof/wheel depth, glass exclusion and fading.

## Upstream composite integration

Run `pzopt.OutlineCompositeLoaderProbe` with the same EGL/native-access setup as the loader probe.
It links the actual full and base PixelLight composites after the CloudShadow, Relief, sway and
filtered outline-depth transforms. This covers the shader-chain integration with upstream release
`1274f92` without opening the game or a save.

## Optional grass/bush exclusion

After building, run `PZ_DIR=/path/to/projectzomboid python tests/render/outline_plants.py` for the
ordinary suite with the additional cache instrumentation and the plant/tree regressions. It uses
actual Sway-generated programs, including prefetch, additional wind probes and DLSS variants.
`PZ_DAMNLIB_SHADERS` also applies to this suite. The separately run `pzopt.OutlinePlantCacheProbe`
uses EGL to check production cache clear, append/reuse, resize, destruction and GL state
restoration; use the same Java classpath/native-access flags as the loader probe below.

## Coverage

- Full/partial camera occlusion and no false waistline at an obstruction boundary.
- Visibility gating, native opacity, depth tolerance and invalid inputs.
- Separate silhouettes for overlapping candidates, combined with maximum blending.
- Characters retain normal colour/depth without contributing outline-occluder depth. Paused 3D draws
  and uninstrumented atlas draws both leave scenery behind them available for capture.
- Atlas rectangles, viewport offsets, clipped screen edges and contour widths.
- Production skinned/static model capture shaders and their world-depth offset.
- Atlas UV/depth conversion and exact static-scene depth seeding.
- Per-pixel darkness gradients, full strength at the native 0.45 daylight ambient reference, no
  contours at lighting boundaries, and no black reveal at zero light.
- Model surface normals affect light independently of clothing RGB. Atlas lighting-only mode exports
  pre-albedo light and restores normal output afterward; cached lighting receives current world RGB.
- Legacy vehicle shaders with and without reflections, including their six window-mask zones.
- Normal depth-writing glass followed by farther opaque geometry: the opaque image must retain that
  geometry even when normal depth rejects it. Both vertex and fragment depth paths are tested, with
  ordinary and MRT colour outputs.
- Paused capture must not be contaminated by an off-screen draw.
- Installed material families compile and link both as flattened sources and as separate
  include-library shader units, matching PZ's normal program layout.

The two JVM tests, `OccludedOutlineShadersTest` and `OccludedOutlineBoundsTest`, run through
`scripts/test.sh`. They check the source transformation contract and conservative bounds, including
blended skin transforms, jitter, empty geometry, very large off-screen coordinates and invalid
projections.

## Model-loader regression (Linux)

Compilation/linking alone does not exercise the game's uniform-property loader. After building, run
this probe from the repository root (with `PZ_DIR` pointing to the inner game directory):

```sh
java --enable-native-access=ALL-UNNAMED \
    -Dpzopt.userOptionsFile="$PWD/build/tests/no-user-options.ini" \
    -cp "build/classes:build/tests:$PZ_DIR/projectzomboid.jar" \
    pzopt.OccludedOutlineLoaderProbe
```

`scripts/test.sh` compiles the probe but does not run it as part of the JVM-only suite. It creates
an EGL compatibility context and runs the actual `ShaderBufferData` constructor and uniform upload
against patched/unpatched, instanced/non-instanced programs. Ordinary alpha/sampler values, instance
layout and the outline image binding must survive. Only the surrounding `Shader` shell bypasses
construction to avoid starting the engine. For a negative control, compile the probe and source
patcher into a separate directory, omit `build/classes` from its classpath, and pass
`--expect-stock-failure`; the stock loader must reproduce the startup null dereference. No game,
window or save is opened.

## Mask shader contract

`pzopt_occludedOutline.vert` is shared by mask and composite fragments. It uses a three-vertex
full-screen triangle. The runtime scissors mask work to conservative candidate bounds instead of
evaluating every pixel for every zombie.

`pzopt_occludedOutline.frag` receives:

- `silhouette`: RG8 coverage in R and per-pixel surface brightness in G. Coverage below 0.5 is
  empty; darkness does not change silhouette membership.
- `silhouetteDepth`: the separate full-precision depth attachment. Depth must already be in
  world-window coordinates, not model/atlas-local coordinates.
- `silhouetteRect`: x, y, width, height within the coverage/depth textures.
- `candidateOrigin`: the tile's lower-left position relative to this viewport.
- `clipEdges`: true for a viewport-clipped capture. Missing samples beyond a clipped view are
  unknown, not new contour edges.
- `opaqueDepth`: R32UI containing positive normalized float depth bits. Clear depth is 1; the
  material pass accumulates with `imageAtomicMin`. It is not the unfiltered final scene depth.
- `depthOrigin`: this viewport's lower-left texel in the opaque depth texture.
- `viewport`: destination framebuffer x, y, width, height.
- `eligible`: current character visibility for this player/frame.
- `candidateOpacity`: native character fading, 0–1.
- `radius`: inner contour width, 1–4 render pixels.
- `depthTolerance`: normalized depth difference required to count as hidden.

Coordinates are bottom-left integer render pixels. The runtime retains temporal viewport jitter
while rasterizing candidates, then samples their exact pixel positions without applying that jitter
twice.

Clear an RG8 accumulation mask for each player/frame and combine candidates using `GL_MAX`. R stores
native alpha times local brightness normalized to the native 0.45 daylight ambient scale; G stores
the same factor for premultiplied colour. Brightness is applied once, not squared. An RG8 regression
test covers dim contours that previously rounded to black, and the atlas export test exercises both
singular and instanced shader output layouts. Source textures must not also be draw attachments. The
colour composite uses `ONE, ONE_MINUS_SRC_ALPHA`, with world depth testing/writes disabled.
Colouring the union once avoids additive brightening where candidate contours overlap.

## Limits of these checks

These checks do not establish in-game visual parity, state isolation under every mod, or frame-time
cost. In particular, they do not turn opaque custom artwork into a glass material automatically.
Real scene checks must still exercise cutaways, floors, moving vehicles, fog, HDR, temporal
upscaling, split screen and crowds on a disposable/copied benchmark save. See the
[feature design](../../docs/plan-occluded-zombie-outlines.md).
