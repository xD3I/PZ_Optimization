# Optional exclusion of grass and bushes from hidden outlines

The restart-required Enhancements option `occludedOutlineIgnorePlants` defaults to `true`. When
selected, native low vegetation (`isBush` or `canBeRemoved`) does not trigger hidden zombie
outlines. `IsoTree` and sprites with the native tree type always take precedence and remain
occluders. Normal plant rendering, character visibility, character exclusion and carried-corpse
eligibility are unchanged.

## Cached depth, not per-frame plant rendering

The ordinary cached colour/depth image cannot simply have its grass pixels erased: that would also
lose a wall or tree behind the grass. `OutlinePlantDepth` maintains a separate R32UI image alongside
each terrain framebuffer. Supported opaque material fragments contribute their depth through an
atomic minimum while low-vegetation draws pause capture. Normal depth rejection does not suppress
these writes, so solid scenery behind a bush survives in either draw order.

Each texel packs 16-bit depth in the upper half and the two native sway attribute bytes in the lower
half. Depth and attributes are selected atomically, with deterministic attribute ordering for depth
ties. This matches the terrain's DEPTH16 precision and remains finer than the outline depth
tolerance. Empty pixels are `0xffffffff`, with no sway attributes when sampled.

Chunk composites reconstruct filtered depth into paired intermediate/world images. Those images are
cleared for each composite generation; terrain images persist between bakes. Normal, combined-FBO,
pixel-lighting and sprite-filter composites use the same shader hook. The main scene's depth texture
is obtained from FogPass's attachment registry when it is not a native TextureFBO field.

## Trees and wind

The filtered composite repeats the native sway lookup using the filtered cache's own depth and tree
attributes. It shares the wind uniforms but not mutable lookup variables or motion-image writes.
Grass cannot displace a cached wall, and a tree behind grass retains its own wind data. Sampling
runs before native fragment discard, which must not erase hidden solid coverage. Native colour and
scene depth outputs remain unchanged.

The direct sway tree-batch shader is instrumented too; its draw explicitly enables cache capture
because it bypasses VBORenderer. Ordinary tree VBO draws use the normal material path. Sway source
recording occurs before the outer cache transform so generated shader twins are not instrumented
twice.

## Lifetime, state and cost

- Native framebuffer clears reset paired caches; append draws retain existing minimum depths.
- Changing depth attachments or dimensions replaces the paired cache and removes its old lookup.
- Both framebuffer destruction paths release cached textures. No save data is involved.
- Image unit 4 and sampler unit 8 are borrowed and restored; UBO binding 9 is reserved for cache
  controls. The ordinary outline image remains on unit 7. Private model-loader exclusions are exact
  names, not a blanket suppression of unsupported uniforms.
- Clear operations preserve framebuffer bindings, scissor state and colour masks. Filter failures
  disable the filtered outline path rather than silently using unfiltered foliage depth.
- Storage is **4 bytes per allocated terrain/intermediate/world texel**, plus small control
  resources. A 1024×1024 terrain cache adds 4 MiB. A 1920×1080 scene target adds about 7.9 MiB;
  native padding, multiple scene targets and the optional oversized combined framebuffer add their
  own allocations.
- Bakes gain image writes; composites gain filtered depth/tree lookups and image writes. There are
  no additional per-frame grass/bush object draws. These are implementation costs, not measured FPS
  claims.

## Checks

`tests/render/outline_plants.py` runs the normal outline GPU suite with the additional
instrumentation, then checks depth behind grass in both orders, native discard, actual tree-shader
attributes, sway shader variants and tree wind hidden behind grass. It uses the actual Sway source
generators. `OutlinePlantCacheProbe` exercises production cache clear, append/reuse, resize,
destruction and borrowed-binding restoration on EGL. The model-loader probe includes the new private
uniforms.

Full gameplay and performance confirmation remain pending. **Hidden outline: ignore grass and
bushes** is on by default; an explicitly saved choice still takes precedence. Changing it requires a
restart.
