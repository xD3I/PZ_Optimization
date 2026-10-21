# God rays through open doors (2026-10-18)

Asked by the maintainer: "implement god rays when doors are open; secondary objective: cost virtually nothing; implement all
the state-of-the-art techniques found in other games, don't discard any ideas, implement them one by one and profile them".
Worktree `~/pzopt-wt/godrays-doors`, branch `godrays-doors` (from origin/master f535eb83). Snapshot builds
`~/pzopt-wt/grdoor-b<N>` (`~/pzopt-wt/grdoor-snapbuild.sh`, queue cmd jobs), runs `grdoor-*` on the desktop (RTX 4090,
5120x2160, zoom 1, NVIDIA GL), god rays on through a clean options file (`-Dpzopt.userOptionsFile=` empty: no other Visual).
Classes: `pzopt.GodRays` (light volumes, local lights), `pzopt.DoorLight` (leaf, swing, dust, spill), `pzopt.DoorRig` (rig).

## Where it stood

An open doorway was already an aperture of the light volumes (`E_DOOR`: 0.15 frame margins, floor to 0.80 of the level)
and closed doors with glass let light through since 2026-10-04. The first repro (`find=sundoor`, below) showed the shaft
coming in the frame the door opens (the geometry bake hook marks the chunk; the door watch added later is equivalent there).
What was missing or wrong: the door itself (the open leaf, the swing), people in the doorway, a wide door's tiles each with
their own frame margins (bars across a garage's shaft), lamplight spilling out at night, porch roofs (the roof rule closes
26-35 % of exterior doors: census below), and two bugs found on the way (the chunk composite's haze patch never compiled on
the stock composite; the swing's first version dropped a frame's world render per swing).

Door census (`door census` log line of the rig, levels 0-1, 80 squares):

| place | exterior doors | roof-ruled | garages (ruled) | see-through |
|---|---|---|---|---|
| bench area 8043,11154 (commercial) | 31 | 11 (35 %) | 23 (8) | 2 |
| Rosewood 8147,11507 | 89 | 23 (26 %) | 15 (0) | 40 |
| West Point 11900,6900 | 91 | 29 (32 %) | 3 (3) | 47 |

Most roof-ruled doors have an upper storey's floor right above their outside square (an inset doorway), the rest porch roofs.

## The rig

`--flag find=sundoor` (`pzopt.DoorRig`): the exterior door within 80 squares the key light would come in through (a room on
one side only, the light from the outside, a rough sun test so another building does not shade it, no porch roof unless
`door_cover=any|only`), plain doors before double and garage doors (`door_kind=garage` for those alone), the most head-on to
the light first; `door_at=x,y,z,N|W` pins one; `door_need=light` takes a door whose room has a ceiling light instead (night
spill). The player stands `door_dist` (3) squares into the room (`door_stand=out`: outside), `door_side` (1) aside; the door
is toggled every `door_toggle` (3) s by the game's own ToggleDoor, `door_max=N` toggles then stays, `door_all=true` toggles
every door of the same kind on the same wall (a fire station's bays). Log lines `harness: door toggled open|closed epoch_ms=`,
`god rays: rig door ...` (the aperture as the light volumes see it), `harness: door census`.

`harness/godrays/doorab.py <run>`: the frames of a devCapture split by the door's state and by the A/B half
(`devGodRaysDoorAlternate`, else `devGodRaysAlternate`): mean frames, contribution heat maps per door state, the opening's
transition frame by frame. `harness/godrays/abframes.py --door`: frame-time A/B of the door features' halves.

## Techniques, one by one

All in the light volume pass that runs anyway (no new pass, no new render target), except the spill and the bounce (one more
draw in an existing pass each). Costs are GL timers of the pass (`devGodRaysTiming`, `.d1` / `.d0` halves of
`devGodRaysDoorAlternate=1000`), uncapped (`uncappedFps=true uiRenderOffscreen=true vrr=off vsync=false`), and the game
thread's own timers (god rays stats line).

| # | key (default) | what | games it comes from | result | cost |
|---|---|---|---|---|---|
| 1 | `godRaysDoorWatch` (on) | the doors (open bit every frame within 2 chunks, the ring to 6 every 8th frame) and curtains / barricades (every 64th frame within 2 chunks) are watched; a change rebuilds the chunk's light the same frame | event-driven light invalidation | latency was already one frame where the bake hook fires; the watch also drives 3 and 5 and catches what bakes nothing | game thread 2.1 us/frame (first version 27 us: `HasCurtains` / `isBarricaded` search object lists) |
| 2 | `godRaysDoorLeaf` (on) | the open leaf stands in its square along the edge the game draws it on (`getSpriteEdge`: N door -> W edge, W -> N, `attachedE` / `attachedS`), hinged at the shared corner; the shader casts the light from each sample toward the light and drops what crosses the leaf's rectangle (soft by the frame's penumbra), a glass leaf passes 80 % through its band | analytic occluders in volumetrics (UE4/5 light shafts with occluders, Quilez's analytic shadows) | the leaf notches the shaft and its sunlit patch where the light runs toward it (matches the drawn leaf: hinge at the doorway's far jamb) | within timer resolution (< 1 us) |
| 3 | `godRaysDoorSwingMs` (350) | a toggle swings the leaf about its hinge for the light (ease-out opening, ease-in closing); a closing door stays an aperture until shut; the swinging leaves are patched in place (no light volume rebuild) | door light sweeps (The Last of Us, Amnesia, Alien: Isolation) | the light sweeps in over ~190 ms while the stock sprite has snapped open; the shaft is cut as the door shuts | game thread: leaf patch per frame during a swing only |
| 4 | `godRaysCharShadows` (on) | people near a shown light volume (picked every 8th frame from the players and the cell's zombies within the volumes' box grown toward the light) as two capsules each (body + head, legs; lying: one), soft Quilez capsule shadows of the ray toward the light, per volume a capsule bit mask (pl7.y) so a volume tests only the capsules that can shade it | capsule shadows in volumetric light (Inside, Hellblade, UE capsule shadows) | the body cuts a dark band through the beam and the patch behind it | GPU +2 us (doorway, player in the beam) to +4 us (window scene, 6 capsules); game thread 0.6 us |
| 5 | `godRaysDoorDustPct` (100) | a door opening stirs the dust of its shaft: three octaves of value noise advected down the light's way (the draft, 0.45 squares/s) and rising, x(1 + 1.6 e^(-t/2.8 s)), more motes; the volume's age in pl7.z | dynamic participating media / disturbed dust (The Last of Us Part II, RDR2) | thicker drifting dust for ~3 s after opening, then settled; subtle | inside the GPU noise of the doorway scene; only volumes of doors opened in the last 9 s pay it |
| 6 | `godRaysSpill` (on), `godRaysSpillPct` (100) | at night a lit room's ceiling light (an `IsoRoomLight` that is on, a point under the ceiling inset in its rectangle, nearest the opening, at least 1.2 squares in) spills out through its open doors and windows: the local lights' closed-form airlight (Sun et al. 2005) clipped to the frustum the opening cuts from it (5 planes), soft sides widening with the distance, its exact screen outline (hull of the opening's corners and where their rays reach the ground or the reach); shows only in fog / rain, like the local lights; candidates cached until the view, the occupancy or a switch changes | portal-clipped light shafts, light spilling out of doorways (Alan Wake 2, Silent Hill 2 remake, Dying Light) | glows out of the lit doors and windows into the fog, ~6 a frame in the Rosewood test (+59 levels at the door, gain 8 heat map) | GPU +6 us (local lights pass 21.5 -> 27.6 us, 4-6 spills), game thread 0.4 us (first version 31 us) |
| 7 | `godRaysBouncePct` (**0**) | one-bounce light of the sunlit patch: the patch (the quad where the rays through the aperture's corners end) as a disk light facing up at its centroid, the disk's form factor onto the surfaces round it in the same room (the occupancy's room hash), one box a patch | instant radiosity / VPL from the lit patch (Keller 1997; the "patch bounce" of RSM-based GI) | physically right and small: a few levels on the walls next to the patch; the game's own per-square room light outweighs it | +17 us at the 60 fps capture cap; off by default |
| 8 | `godRaysDoorMerge` (on) | the tiles of one wide open door (a garage door, double doors) as one opening: margins only at the run's ends, one volume | (correctness) | a 3-tile garage door is one 0.15..2.85 opening instead of three with dark 0.3-square bars between | fewer volumes |
| 9 | `godRaysRoofExact` (**off**) | instead of the roof rule (no light under a porch roof or a floor above, the maintainer's rule of 2026-10-04), the light comes in under the roof's edge: the cover's depth out from the wall (occupancy floors / roofs one level up) gives one more half-space of the volume (pl10), the outer light is looked for beyond the edge | exact overhang shadowing | a porch-roofed kitchen door at 08:00 (sun 10 deg up) gets a 2-square shaft; the rule leaves it closed | one more plane in the clip (no measurable cost); off: the maintainer's rule stands |

Dropped on the way (measured): capsule tests in every sample of every volume (window volumes paid for them: a per-fragment
`doorish` flag now skips the door terms); the capsule pick by walking the volumes' squares (9.8 us, worse than the zombie
list); `IsoZombie.isProne()` in the pick (walks the state machine); the spill's wedge-of-the-reach footprint swept over the
level (5.8 Mpx of local-light quads; exact outline now) and 4 soft samples (2 now).

## Bugs found and fixed

- **The chunk composite's haze patch never compiled on the stock composite** (`chunkShader.frag` is GLSL 1.20; the patch's
  `layout(binding = 29)` needs `GL_ARB_shading_language_420pack`, which needs a newer version). Without pixelLight or cloud
  shadows (which raise the version themselves) the open-air haze was never drawn: `god rays: media/shaders/chunkShader.frag
  haze patch does not compile` in every such run since the chunk haze shipped (voxel-src runs of this morning on master
  too). Now raised to `#version 150 compatibility` (NVIDIA refuses 1.30 and 1.40; 1.50 compatibility keeps `gl_FragColor`,
  `varying`, `texture2D`, `texture3D`, as the cloud patch's 4.20 compatibility): `haze patched into media/shaders/chunkShader.frag`.
  Players without pixelLight now see the haze the docs describe.
- The swing's end threw `IllegalStateException: Entry was removed` (an `IdentityHashMap` entry read after its iterator's
  remove); the game's render catch dropped the frame's world render at every swing end, and a closed door stayed an aperture.
- The light volume program read pl7.y / pl7.z (the GPU-only capsule mask and dust age) where the CPU keeps the aperture's
  middle for the cloud lookup: every window volume ran the dust noise (+30 us GPU, and visible dust). The frame's copy now
  clears both.
- The bounce shader missed a uniform (`uVp`) and switched itself off in game: `tests/pzopt/GodRaysDoorShaderTest` now links
  every door program and the chunk haze patch with the driver (`/tmp/glslcheck`).

## Cost (desktop, 5120x2160, uncapped)

| scene | GPU (light volume / local pass) | frame (median, A/B halves) | game thread |
|---|---|---|---|
| day doorway, door toggled every 3 s, player in the shaft (`grdoor-final2-day`) | 8.2 -> 17.4 us with leaf, capsules, dust on (timer steps of ~1 us; 2 capsules) | 0.958 -> 0.965 ms (+7 us) | 4.1 / 3.7 us (on / off halves) |
| night spill, fog 0.3, lights on (`grdoor-final2-night`) | 21.5 -> 27.6 us | 1.433 -> 1.436 ms (+3 us) | 34.4 / 32.7 us (the existing lamps gather 28 us of it) |
| window-only room (`find=sunwindow`, 17:00, `grdoor-win-ab`) | 22.5 -> 26.5 us (6 capsules of people near windows) | 1.200 -> 1.208 ms (+8 us) | total god rays 5.0 us (master 2.3; door poll 2.1, capsules 0.6) |

The mean frame time of the A/B halves is no measure here: an A/A run (`grdoor-aa-day`, the alternation with every door
feature off) showed +26 us "cost" from the door toggles' stock lighting spikes landing unevenly; medians agree (0.958 vs
0.956 ms). Use the medians, the GL timers and the thread timers.

## The Riverside fire station (the maintainer's request)

`grdoor-firestation-20261018-193317`: `start=6134,5258 time_of_day=15 weather=clear find=sundoor door_kind=garage
door_all=true door_max=1` (the bay `walls_garage_02_37` at 6133,5264 N, room `firegarage`, 7 more doors on the wall opened
with it), god rays alternating every second. The sun at 15:00 stands 62 deg up in the SSW (light -0.27, 0.38, 0.88): the
shafts through the open bays are short (they reach the floor about a square in), a lit slab of dust in each doorway and a
brighter strip of floor inside; at a lower sun from the south there is none (the sun is south only near noon).

Videos (in-game `devCapture=3,22,30,100,crop=...,ram`, 1920x1080 1:1 crops, encoded AV1 10-bit PQ; god rays on, the doors
toggled every 4 s): `grdoor-vid-fire2-20261018-213137` (the bays opening and closing) and `grdoor-vid-spill2-20261018-213550`
(the night spill, `time_of_day=23 lights=on fog=0.5 zoom=1 door_need=light door_stand=out`; at fog 0.3 / zoom 1.5 the spill
moved ~2 % of the pixels by more than 6 levels: hard to see in a video).

## Menu

Visuals > Lighting and shadows > God rays (the unified menu of 2f4e8486): open doors block the light (`godRaysDoorLeaf`), door
swing (`godRaysDoorSwingMs`, Everything view), people's shadows in the shafts (`godRaysCharShadows`), dust stirred by doors
(`godRaysDoorDustPct`), lamplight spilling out at night (`godRaysSpill`) and its brightness (`godRaysSpillPct`), light
bounced off the sunlit floor (`godRaysBouncePct`, off), sun under porch roofs (`godRaysRoofExact`, off, next launch: it
changes the chunk occupancy). The rest are live keys (`Config.loadLive`): the light volumes' build key and the spill cache key
include the ones that shape them. `godRaysDoorWatch` / `godRaysDoorMerge` stay off the menu (fixes, not choices). Checked in
game with `options_tab=Enhancements options_shots=cat:light:4:advanced;cat:light:4:everything` (run `grdoor-menu-*`).

Before / after clips (`harness/menu-gifs.py godrays godraysdoor godraysspill`, kind `pane`, `align='door'`: each side starts
0.4 s before its run's first `harness: door toggled open`, 4 s = opens, closes, loops): `godrays` (the section) the fire
station garage at 15:00, god rays off vs on (runs `grmenu2-fire-*`); `godraysdoor` (leaf, swing, people's shadows, dust) a
plain shop door at 17:00 with the player a square inside, the door keys off vs on (`grmenu3-hinge-*`: the garage door rolls
up, so its pair showed no difference); `godraysspill` (spill, spill brightness) a Rosewood house at 23:00, spill off vs on
(`grmenu3-spill-*`). Traps: `zombies=off` (a zombie walked into one side of the first pair); night fog at 0.5 is pale grey
and hides the spill (the first spill video, `grdoor-vid-spill2`, is washed out by it): `fog_tint=dark`.
GIF against other encodings of the same 4 s 512x216 clip (the `godrays` on side vs the lossless pane): GIF 183 KB, PSNR 30.7
dB, SSIM 0.81 (96 colours: the haze in contour bands, the yellow lines washed out); H.264 baseline 35 KB, 37.7 dB, 0.971;
AV1 31 KB, 39.0, 0.980; animated WebP 65 KB, 35.8, 0.945; MJPEG q5 1.07 MB, 35.1, 0.943; APNG (lossless) 7.9 MB.

## Open

- The swing follows the stock sprite's instant change with our own 350 ms ease; a door with a 3D model (`SpriteModel`) has
  its own animation the leaf could follow (`IsoObjectAnimations`).
- Spill sources: room ceiling lights only (lamps placed by players are not spill sources yet).
