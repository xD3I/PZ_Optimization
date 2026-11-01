# Light and shadow report from Discord (Nick.Lu, 2026-10-04)

Seven issues in #bug-reports ("Light and Shadow"), reproduced on the release build with the player's own options file
(ambient occlusion at the defaults, per-pixel lighting, torch source, reflections, colour grading; sun shadows off) and
judged by Jev on numbers from in-game captures (`devCapture`), each against a control (pixel lighting off, or the stock game).

| # | Report | Cause | Fix (key, default on) | Before | After | Control |
|---|---|---|---|---|---|---|
| 1 | No torch light with a car's headlights behind | the native adds only the brightest of the torch and vehicle lights to a square (max, measured per square: error 0.001); pixelLight took the listed torch out of the base where a headlight had been added instead | `pplTorchVehicleMix` | beam 80.3 with torch, torch adds 41.8, 3,274 px darkened | 100.7, adds 61.7, 179 px | pixelLight off: 89.2, 228 px |
| 2 | Dark contour round the torch under a street lamp | where lamp + torch saturate a square, the base was the night ambient estimate: the lamp's light vanished round the player (a black wedge) | `pplClipBase` (the square's light without the torch remembered on `JNILighting`) | ~2,250 still px darkened per torch switch | ~960, of which ~840 a distant roof trim turning cooler | ~110 |
| 3 | Light pixels round object contours with AO | a leaf's soft edge carries the leaf's depth but mostly the wall's colour, and leaves are unshaded: a light outline on a shaded wall | `aoEdgeShade` (a texel beside a deeper surface takes the darker AO; a step, not its own slope) | 61 % of the leaf border lit | 31 % (day), 35 % (night) | stock 0 % |
| 4 | Double shadows on shelves | not reproduced (store shelves, placed racks); the picture's stripes on boxes look like issue 7's pattern on object faces | (issue 7) | - | - | - |
| 5 | Torn character shadows with "true shape" | a low headlight's perspective shadow tile stretches the legs' shadow into thin sharp strands | `sunShadowLampMeshNearPct` / `FarPct` (100 / 300): the mesh shadow fades into the capsules' soft shadow from 1 to 3 squares | strand energy 4.55 | 2.01 | - |
| 6, 7 | Dark mesh / dots on walls and container backs | not AO (bisect: with AO off the mesh stayed, with pixel lighting off it went): pixelLight's texel normals tilted past the plane snap on a DEPTH16 rounding lattice | `pplNormalSpanWide` (4): an unsnapped normal tries neighbours 4 texels away, kept only on planar texels of a square with a wall on that edge, within 0.2 of it | wall detail 147,661 px | 91,615 (day), 93,342 (night) | stock 88,197 |

Jev (final): 1, 2, 3, 5, 6, 7 improved; 4 not reproduced.

Open: the roof trim under the street lamp (2), the remaining leaf outline (3), issue 4 itself.

2026-10-27, item 5 revisited: once street lamps cast character shadows (`sunShadowStaticLights`, 2026-10-25) the 1-3
square fade made their long shadows (a lamp 3 squares up, the caster 5 away: 6-7 squares of shadow) capsule blobs past
the feet (maintainer's screenshot). The fade now applies only where the light grazes the receiver:
`sunShadowLampMeshGrazeNearPct` / `FarPct` (300 / 600) on ground distance from the lamp over its height above the
receiver. A headlight (0.75 up) is past 4 from about a square beyond the caster and fades as before; a street lamp's
shadow stays under 3 over its length and keeps the model's shape (runs `blobfix-new` vs `blobfix-old`, `harness/lampshadow/run-lamp.sh`;
`GrazeFarPct=0` = the old rule). The far end of a long lamp shadow shows the view tile's texels as a ragged edge.
With the shape kept to the end, the shadow's head was cut flat (maintainer's screenshot by the Rosewood church lot's
utility pole, 8157,11482): the light quad's far end came from the bounding capsule's centre line, and a lamp a little
above the head throws the head's top far past it. The quad now reaches the projection of the capsule's top
(`p.z + rr` in the light pass's vertex shader); runs `spot2-old` vs `spot2-fix`: the end 0.6 square longer and rounded.

Rigs: `exit_car=true`, `headlights_toggle=S` (harness/CLAUDE.md); scripts used (pairs, car, lampmask, aohf) in
/tmp/nick-shadows on the desktop. Dead ends: AO blur plane prediction, AO angle bias, AO silhouette min at 0.36 squares,
plant marking in the AO blur (indoor potted plants are not vegetation to the AO kernel), the AO canopy term (the mesh was
never AO), `pplNormalSpan=4` alone (black blobs on leaves), a wall-edge band of 0.06 (wall faces sit 0.01-0.18 in). A
microwave that looked dark only with the wide span was run-to-run variation (four repeats: 157-172 either way).
