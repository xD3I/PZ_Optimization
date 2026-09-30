# KI5 vehicle outlines: missing material capture

## Cause confirmed from the local mod files

KI5 vehicles use DAMNLib shader names that do not start with the stock `vehicle` prefix:

- `damn_vehicle_shader`
- `damn_vehicle_noreflect_shader`
- `damn_wheel_shader`

The original `OccludedOutlineShaders.material` filter rejected all three, returning their shader
source unchanged. Consequently these draws never contributed to the independent opaque-depth image,
even though the installed `Model.DrawVehicle` hook was present. Player model shaders were
recognized, which explains the screenshot's contour appearing at player overlap but not around
surfaces hidden only by the truck. Testing stock vehicle shaders did not cover this case.

The now-accessible profile confirms DAMNLib is loaded. KI5 model scripts, including
`93fordF350/42.13/media/scripts/vehicles/93fordF350.txt` and
`93fordF350/media/scripts/vehicles/93fordF350pd.txt`, explicitly select these names. The source
shaders are under `damnlib/common/media/shaders` in the profile's mod directory. No mod files were
changed.

## Repository correction

The three exact names are now recognized. Unknown `damn_*` shaders are not broadly enabled.
DAMNLib's `windowAlpha` includes the black roof mask zone (`texen2[0][0]`) for its colour/reflection
handling. Capture therefore excludes only its six actual window zones, not the opaque roof.
Bodywork, roofs and wheels contribute opaque depth; faded material and window pixels remain
excluded.

The previously prepared optional runtime diagnostic was never installed and has been removed: shader
selection now supplies a concrete cause without requiring diagnostic readbacks during play.

## Daylight brightness correction

Native character and atlas shading multiply ambient light by 0.45. Using raw captured brightness as
the outline fade therefore dimmed even normally lit daytime characters. The contour now uses
`clamp(surfaceBrightness / 0.45, 0, 1)` as its per-pixel fade. Full native ambient gives full
configured outline strength, half that illumination gives half strength, and zero illumination
remains invisible. No whole-character brightness modifier, minimum night brightness or foreground
sampling is introduced.

## Verification and scope

- Source-contract tests cover all three DAMNLib names and reject unrelated mod shader names.
- With `PZ_DAMNLIB_SHADERS` pointing at the actual installed mod sources, the EGL suite
  compiles/links their static body/wheel programs and renders opaque body, opaque roof, window and
  faded cases.
- Brightness tests cover the 0.45 daylight reference, partial light, darkness and unchanged contour
  geometry across lighting boundaries.
- In-game visual confirmation remains outstanding. This KI5/daylight update left carried-corpse
  eligibility unchanged; the subsequent fix is documented in the outline rendering contract. After
  user authorization, the two corrected runtime files were installed:
  `pzopt/OccludedOutlineShaders.class` and `media/shaders/pzopt_occludedOutline.frag`. Installation
  verified 815 runtime checksum records, 783 protected files, and no unexpected unresolved member
  references. Original backups were retained; settings and saves were untouched.
