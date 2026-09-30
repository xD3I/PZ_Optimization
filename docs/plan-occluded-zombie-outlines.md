# Occluded zombie outlines

Implementation on `occluded-zombie-outlines`, based on upstream
`d6222b5516527798284f85edcc9c8cf45c7ee930` (Build 42.21, release `08a66cd`). The source-floor
experiment remains separate. This is local development: no push or installation into the customized
personal game.

This implements the outline portion of candidate M in
[the graphics enhancement plan](plan-graphics-enhancements.md). It does not add wall fading, change
character vision, or provide unrestricted X-ray visibility.

## Player controls

Options > Enhancements > **Occluded zombie outlines**:

| Key                           | Default  | Meaning                                                           |
| ----------------------------- | -------- | ----------------------------------------------------------------- |
| `occludedZombieOutlines`      | `false`  | Enable hidden-contour rendering                                   |
| `occludedOutlineIgnorePlants` | `true`   | Ignore grass/bush occlusion using a separate cache; trees remain  |
| `occludedOutlineWidth`        | `1`      | Inner contour width, 1–4 render pixels                            |
| `occludedOutlineColour`       | `FFC740` | RGB hex selected with the native hue/saturation/brightness picker |
| `occludedOutlineOpacityPct`   | `70`     | Maximum opacity; native character fading still applies            |

Colour updates immediately on Apply. The other controls require a restart. The material shader
programs and the window/ translucent bake policy must change together. Width is explicitly in
**render pixels**, so upscaling enlarges it. The Optimization and Enhancements master switches
suppress the feature. Unsupported contexts or initialization failures leave ordinary rendering in
place and log one failure, not routine success lines.

Requires OpenGL 4.3 and the modern chunk renderer.

The colour swatch opens the native `ISColorPickerHSB` widget. Accepting a colour changes the pending
selection; Apply saves six-digit RGB hex and updates the next outline composite without rebuilding
shaders or caches. Opacity is separate. Default reset, pinned settings, mouse/controller acceptance
and popup cancellation follow the options screen's normal behavior. Named colour presets are no
longer supported.

## Rendering contract

- The game thread snapshots `isVisibleToPlayer` and the native alpha threshold for each zombie draw.
  Loaded, remembered or nearby zombies are not sufficient. Native corpse proxies marked
  `isReanimatedForGrappleOnly` are excluded even though their temporary zombie is alive.
- A car covering a zombie's legs produces contours around the hidden legs. The visible torso is
  unchanged. There is no artificial line across the car's top.
- Characters, including players, other zombies and character atlas sprites, do not contribute
  outline-occluder depth. Their normal colour/depth rendering is unchanged; scenery and vehicles
  remain occluders.
- Each candidate has its own silhouette. Overlapping zombies do not become a single merged
  silhouette; their resulting outline coverage is combined once.
- Native 3D poses and atlas sprites are supported. The implementation does not promote atlas zombies
  into 3D models or invent silhouettes when the game supplies no geometry, including some
  staircase/floor-culling cases.
- Targeting and interaction outlines retain their existing state and resources.

## Grass and bushes

The optional **Hidden outline: ignore grass and bushes** control uses
[separate cached depth](outline-low-vegetation.md), not per-frame plant rendering. Trees retain
their native wind metadata; walls behind bushes remain available to the outline pass. It defaults on
and requires a restart. The extra cache costs four bytes per allocated terrain and
intermediate/world texel, plus bake/composite GPU work.

## Opaque depth, including glass

The final scene depth is not an opaque-only input. Optimization normally bakes windows and
translucent doors into chunk textures, and vehicle shaders can output full alpha for window
material. Deleting glass pixels from a flattened depth texture would also lose solid bodywork behind
the glass.

With this startup option enabled:

1. Windows and translucent tiles use their existing per-frame rendering paths, including the
   corresponding curtain ordering, rather than baking into chunk depth. The policy remains stable
   for the session even if the optional pass subsequently fails.
2. After chunk compositing, a GPU pass seeds an independent R32UI depth image from the static
   world's depth. Positive float depth is stored as ordered integer bits.
3. Supported world-material fragments atomically minimize that depth image only for opaque coverage.
   Standard vehicle window masks are excluded independently of final alpha. There are deliberately
   no forced early fragment tests: solid surfaces behind glass must contribute even if normal depth
   testing rejects them.
4. TextureFBO, model and VBO scope hooks exclude atlas-cache, thumbnail, imposter, screen-space and
   shadow-view draws. The shadow atlas explicitly brackets its raw-framebuffer model replays. The
   immediate character draw and queued character-instance flush pause capture with `finally`
   restoration, including held/attached models. The `DeadBodyAtlas` material is not instrumented.
   Shared `basicEffect` shaders remain instrumented for non-character world items and lighting
   export.
5. Capture ends after moving and translucent geometry, before fog. Image/texture barriers make the
   independent depth available to the contour pass.

The stock model-property loader cannot represent the private image and integer-vector uniforms.
`ShaderBufferData` excludes only the exact private names owned by
`OccludedOutlineShaders.ownsUniform` (including the optional plant cache's control and image
uniforms), so it neither constructs unsupported parameters nor resets the outline renderer's
bindings during model property uploads. The EGL loader probe exercises this engine boundary in
addition to shader linking.

Material instrumentation preserves the original colour and depth computation. It accepts ordinary
fragment output and the primary MRT output used by HDR and foliage sway. Compile/contract failures
disable this feature and retain the original material source.

Transparent window texels do not occlude; opaque frame, curtain and board pixels can. Fully opaque
custom artwork representing glass needs material information; alpha alone cannot identify it.
Unknown custom shader families are not promised coverage. Tinted-glass attenuation is outside this
version.

## Candidate capture and compositing

`pzopt.OccludedOutline` owns the frame commands and GPU resources; `OccludedOutlineShaders` owns
source instrumentation. `OccludedOutlineBounds` computes conservative capture rectangles
independently of game/GL initialization.

- Model eligibility is carried by `TextureDraw`; prepared model-data references and camera
  parameters are retained only until the frame's finish command. Replays use dedicated shaders and a
  private VAO, without changing `Model.effect`, animations, simulation or targeting flags.
- Atlas draws retain their actual texture rectangle, UVs, transform and native depth conversion.
  Pool reuse clears the eligibility bit for ordinary corpse draws.
- Model bounds union the transformed mesh box for each skin bone. Invalid bounds use the viewport;
  empty and wholly off-screen bounds do not overflow integer rectangles. Temporal viewport jitter is
  retained during capture.
- One RG8 coverage/brightness texture and D32F depth attachment are reused for individual
  silhouettes. Only the previous candidate's rectangle needs clearing. A clipped viewport edge is
  unknown coverage, not an invented silhouette boundary.
- The mask shader forms contours from complete coverage, then intersects them with opaque occlusion.
  Neighbours' occlusion status never creates a contour edge. Two normalized 16-bit depth steps are
  tolerated to avoid depth-quantization noise.
- Candidates accumulate alpha and premultiplied brightness into RG8 with maximum blending. One
  premultiplied colour composite follows. World depth is never modified by silhouette or composite
  draws; fog and later world treatment still apply.

The replay targets total approximately **12 bytes per render pixel**: R32UI opaque depth, RG8
candidate coverage/brightness, D32F candidate depth and RG8 accumulated alpha/brightness. They are
reused across players and rebuilt on a viewport-size change. At 1920×1080 this is about 24.9 MB; at
3840×2160, 99.5 MB, before driver overhead. Frames with no eligible draw skip capture work. There is
no CPU image readback. Uniform locations are cached; skin/candidate loops do not query GL state.

Image binding 7 is borrowed after Sway's chunk composite and restored before later passes.
Uniform-buffer binding 8 is reserved for capture control. Control storage is orphaned at scope
changes rather than overwriting data still consumed by queued GPU work. Framebuffer, program,
viewport, scissor, texture and VAO state are restored and the game's texture/VBO caches invalidated
as required.

## Per-pixel outline lighting (2026-09-30)

Brightness comes from the hidden zombie, never the foreground wall or car. Model capture evaluates
native ambient and five directional light contributions using each fragment's skinned/interpolated
normal, independently of clothing RGB. This follows the game's character-lighting approximation; it
does not add a new physical shadow or light-transport model.

Atlas bakes replay their prepared pose once into a lighting-only bitmap after the normal
diffuse/depth copy. The same scaled and flipped rectangle is copied into an atlas-owned RGBA8
lighting texture, released with the native atlas. This costs an extra model draw per atlas bake and
4 MiB per 1024×1024 atlas, not a second model draw per atlas zombie per frame. At world draw time
the cached per-pixel lighting is multiplied by the native atlas draw's current RGB lighting. Dark
clothing cannot be mistaken for darkness, and cached geometry still receives current world
illumination.

Coverage remains independent of brightness: a lighting boundary never creates a contour. At each
hidden contour pixel, normalized brightness `L = clamp(surfaceBrightness / 0.45, 0, 1)` controls
alpha and premultiplied colour once (`nativeAlpha * L`). The 0.45 reference matches the native
character/atlas ambient multiplier, keeping ordinary daylight at full configured outline strength.
The composite must not multiply by brightness a second time: the previous squared attenuation
rounded dim contour colour to zero in RG8 while retaining alpha. Linear fading preserves dim lines
without revealing an unlit zombie as a black contour. Visibility eligibility and the full-silhouette
contour rule are unchanged.

The regression check covers both native output layouts: singular shaders declare `fragCol`, whereas
instanced shaders write `colour` directly. Both export illumination before clothing multiplication.
The lighting replay also resets scissoring and write masks before clearing its bitmap, rather than
inheriting the preceding atlas copy's world draw state.

## Vehicle investigation (2026-09-30)

The legacy `vehicle` and `vehicle_noreflect` shaders classify the same six window zones as multi-UV
vehicles but do not declare `windowAlpha`. The original instrumentation rejected these supported
stock shaders and disabled the effect. The patch now derives that value from their verified zone
assignments; unknown layouts still fail explicitly. Both legacy variants are included in
real-material GPU linking tests. A separate, subsequently confirmed cause of the reported KI5 case
was that DAMNLib shader names were not recognized at all. Its three body/wheel shader names are now
included, with roof pixels kept opaque despite DAMNLib including them in `windowAlpha`. See
[the KI5 investigation](findings-vehicle-outline-capture.md). In-game confirmation remains pending.

## Verification and remaining validation

- Build and bytecode parity checks cover every unchanged override method.
- JVM tests cover source instrumentation contracts and conservative projected bounds, including
  empty, huge, invalid and blended-bone cases.
- The [EGL tests](../tests/render/README.md) execute actual GLSL, model/atlas capture, depth
  seeding, hidden contours, and opaque capture behind depth-writing glass. Installed material
  families are compiled both flattened and as separate shader units, matching the game's
  include-library linking. MRT capture checks use the primary output, not HDR/foliage auxiliary
  alpha.
- These are not gameplay or frame-time measurements. Real scene verification is still required for
  vehicle rotations, cutaways, stairs, fog, HDR, upscaling, split screen and dense crowds. Hardware
  timings must include CPU/GPU cost, p99/p99.9 frame times and utilization; no FPS improvement is
  claimed.

Any game check must use a disposable/copied benchmark save and a separately reviewed test
installation. Do not run the stock installer over the customized personal installation or open a
real save. Personal feeding, corpse-burning, FMOD-budget, updater and native-save modifications are
not part of this upstream feature and must remain protected during any later installation.
