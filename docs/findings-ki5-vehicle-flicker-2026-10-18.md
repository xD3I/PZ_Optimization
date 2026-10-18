# KI5 vehicles "strobing" with car glass: report check (2026-10-18)

Discord thread 1561327249431597066, 2026-10-18 10:37 UTC, user sourceshard (an admin on a multiplayer server; the game's
version line reads 42.21.0 4a0e9546ec with pzBullet 1.0.0.28). Report: car glass works on vanilla vehicles (Dash Rancher)
and Autotsar trucks, but every KI5 vehicle (Oshkosh M911, LAV-300, Touran, Bushmaster, ...) flickers fast at the windows.
Their explanation: `CarGlass.windowClass` knows only the six vanilla window zones, KI5's damnlib uses zones 7-12 and 17 for
glass, so a KI5 car has no glass texels, the glass pass discards everything, and the car "fights with the mirror
reflection pass". Attached: a 4.4 s, 2560x1440, 30 fps clip.

Verdict so far: the explanation is wrong, and the single-player repro does not flicker. Their clip shows a 1 Hz
brighten-then-snap of the whole vehicle's shading on several vehicles, not a per-frame strobe. Cause open; it is probably
specific to multiplayer and may not involve PZ Optimization at all.

## The code facts

- **Zones.** `windowClass` quantises the mask colour to 0 / 0.5 per channel and switches on the bit pattern; its cases 1-6
  are the stock window zones 7-12 ((0,.5,.5) ... (0,.5,0)), not zones 1-6. damnlib's `damn_vehicle_shader.frag`
  `#include`s the stock `vehicle_common.frag` (the same 27 zone colours) and adds zones 17-27 in a second matrix (`texen2`).
  Its window alpha is zones 7-12 plus zone 17. Zone 17's mask colour is (0,0,0): the same as an unmarked texel, so it can
  never be told apart in the mask.
- **No glass on KI5 cars at all.** KI5 vehicles draw with `damn_vehicle_shader` / `damn_vehicle_noreflect_shader` (the
  LAV-300's scripts: 18 / 26 parts; wheels `damn_wheel_shader` / `vehiclewheel`). `CarGlass.glassFor` asks
  ShaderManager for `pzopt_glass_<effect name>`; build.sh only copies the stock vehicle shaders (`pzopt_glass_vehicle*`), so
  there is no program, the null is cached and the car keeps the windows the game draws. The glass map is not involved.
- **No other shader patch reaches them.** `EntityShadow.target` takes `basicEffect*`, `animalEffect*` and `vehicle*`
  only; HDR patches `vehicle.frag` / `vehicle_multiuv.frag` / `vehicle_norandom_multiuv.frag` only. The damn programs run
  as damnlib ships them.

## Test (desktop, RTX 4090, single player, direct launch)

Installed: "that DAMN Library" 3171167894 (damnlib 0.9877b, 42.20 folder) and "'84 Cadillac Gage LAV-300" 3171184800
(mod id `84gageV300`, scripts `Base.84gageV300apc` / `Base.84gageV300fsv`), subscribed on the maintainer's account through
ISteamUGC `SubscribeItem` + `DownloadItem` with the game's `libsteam_api.so` (no steamcmd); they live in
`/games/steamapps/workshop/content/108600/`.

Scene: the LAV-300 APC and a vanilla `Base.Van` parked 5 squares either side of the player at 8158,11514, held 14 s, zoom 1.

```
harness/run.sh --label ki5-lav-repro2 --mode bench --no-mangohud --record --mod damnlib --mod 84gageV300 \
  --route-seconds 10 --flag start=8158,11514 --flag route=E:0 --flag hold=14 --flag zombies=off --flag zoom=1 \
  --flag car_rig=2 --flag car_rig_models=Base.84gageV300apc,Base.Van --flag car_rig_r=5
```

| Run | Settings | LAV region, frame-to-frame mean abs diff (luma) | Van region | Result |
|---|---|---|---|---|
| `ki5-lav-repro2-20261018-162047` | the maintainer's tab file (car glass and sun shadows off in it) | 0.30 | 0.30 | steady |
| `ki5-lav-allenh-20261018-162433` | + `weather=rain` and every Visuals key on (`pixelLight torchSource sunShadows ambientOcclusion godRays reflections ripples mirrors carGlass bloodWet memoryTint colorGrading foliageSway relief`) | 0.69 (rain streaks) | 0.43 | steady: six consecutive full-resolution crops identical, the LAV's mean luma flat within +-0.2 over 6 s |

Both runs: car rig spawned `Base.84gageV300apc@8163,11514` and `Base.Van@8153,11514`; the all-on run's console has the
entity-shadow patches, car glass and mirrors initialised. Job 13499 (first attempt) sat in run.sh's default `verify` mode
because `--mode bench` was missing and was cancelled; 13514 failed on argument quoting.

## The reporter's clip

- **No per-frame strobe and no motion.** A block-matching search (+-6 px) on the M911, Kenworth, black van and APC finds 0,0
  shift in every frame. The mean luma per vehicle has no frame-to-frame alternation.
- **A 1 Hz sawtooth.** The KI5 LAV and APC, the M911 and a black plowed "Franklin Valuline" brighten by 1-3 luma steps over
  ~30 frames, then snap back (frames 29 -> 30, 60 -> 61). The Kenworth beside them and the road stay flat. The difference
  image across the snap covers the vehicle bodies, the outlines of their details and above all the window glass (the M911's
  windshield, the van's windows).
- In a 30 fps capture, a two-state strobe at the game's frame rate would show as a square-wave beat, not a ramp. A ramp and a
  snap look like a light or shading value fading towards a target and being reset once a second.

## Open / next steps

1. From the reporter: `console.txt` of that session, their settings ("Export settings"), whether it still happens with
   "Disable all (stock game)", and whether it happens in single player.
2. If it shows with everything disabled, it is not ours (KI5 / damnlib or the MP vehicle sync). If not: an MP repro with
   the KI5 mods on the stock server (`queue.sh submit mp`), parked vehicles, rain.
3. Separate feature request, if wanted: glass on KI5 cars needs build-time glass copies of damnlib's programs (and zone 17,
   which the mask cannot mark: it would have to come from the diffuse, like the CarLuxury quarter windows).
