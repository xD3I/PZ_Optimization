#!/usr/bin/env python3
"""Does the player's car blink while driving? A devCapture drive run (the camera holds the car at one screen spot while
cruising): the car box's mean luma per frame against a road box beside it under the same lights, blinks counted (a frame
brighter or darker than both neighbours by more than 8 luma and 30 % of the brighter neighbour; a snap is one frame-to-frame
step of more than 20 luma and 40 % of the brighter frame: captured at every presented frame, the car's light ramps up over
several frames and snaps back, which the one-frame blink test misses), split by voxel lighting's on / off phases when
the run alternates (devVoxelLightAlternate). Jev reads the numbers.

    car-flicker.py <run> --box x0,y0,x1,y1 [--road x0,y0,x1,y1] [--from N] [--json f] [--no-jev]

--box: the car in capture pixels (find it once in a contact sheet: harness/voxel/sheet-style crops); --road: default the
car box moved 45 px right and 20 px down (the road ahead of a car driving east at max zoom); --from: the first frame to
count (the camera's lead settles once the car cruises)."""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ppl"))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import capture  # noqa: E402
from typesafe_client import ask, choice, noul, fmt  # noqa: E402


def luma(f):
    f = f.astype(np.float32)
    return 0.2126 * f[..., 0] + 0.7152 * f[..., 1] + 0.0722 * f[..., 2]


def phases(run, st):
    """The capture frames by voxel lighting phase (0, 2, 4 ... on), from the console's alternation line; {} without one."""
    t0 = per = None
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        if "voxel light: alternating every" in line:
            p = line.split("alternating every ")[1].split()
            per = int(p[0])
            t0 = int(line.split("epoch_ms ")[1].split()[0])
    if t0 is None:
        return {}
    return {i: int((t - t0) // per) for i, t in enumerate(st) if t >= t0}


def blinks(v):
    out = []
    for i in range(1, len(v) - 1):
        a, b, c = v[i - 1], v[i], v[i + 1]
        if (b - a) * (b - c) > 0 and min(abs(b - a), abs(b - c)) > max(8.0, 0.3 * max(a, c)):
            out.append(i)
    return out


def snaps(v):
    return [i for i in range(1, len(v)) if abs(v[i] - v[i - 1]) > max(20.0, 0.4 * max(v[i], v[i - 1]))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--box", required=True)
    ap.add_argument("--road")
    ap.add_argument("--from", dest="first", type=int, default=0)
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    fr, st = capture.load(a.run)
    x0, y0, x1, y1 = map(int, a.box.split(","))
    rx0, ry0, rx1, ry1 = map(int, a.road.split(",")) if a.road else (x0 + 45, y0 + 20, x1 + 45, y1 + 20)
    idx = list(range(a.first, len(fr)))
    car = np.array([luma(fr[i][y0:y1, x0:x1]).mean() for i in idx])
    road = np.array([luma(fr[i][ry0:ry1, rx0:rx1]).mean() for i in idx])
    ph = phases(a.run, st)
    cb, rb = blinks(car), blinks(road)
    cs, rs = snaps(car), snaps(road)
    secs = max(1e-3, (st[idx[-1]] - st[idx[0]]) / 1000.0)
    on = [k for k, i in enumerate(idx) if ph.get(i, -1) >= 0 and ph[i] % 2 == 0]
    off = [k for k, i in enumerate(idx) if ph.get(i, -1) >= 0 and ph[i] % 2 == 1]
    card = {"frames": len(idx), "car_luma_median": round(float(np.median(car)), 1),
            "car_luma_p10_p90": [round(float(np.percentile(car, 10)), 1), round(float(np.percentile(car, 90)), 1)],
            "car_mean_abs_frame_step": round(float(np.abs(np.diff(car)).mean()), 1),
            "road_mean_abs_frame_step": round(float(np.abs(np.diff(road)).mean()), 1),
            "car_blinks_per_100_frames": round(100.0 * len(cb) / max(1, len(idx)), 1),
            "road_blinks_per_100_frames": round(100.0 * len(rb) / max(1, len(idx)), 1),
            "captured_fps": round((len(idx) - 1) / secs, 1),
            "car_snaps_per_second": round(len(cs) / secs, 1),
            "road_snaps_per_second": round(len(rs) / secs, 1),
            "car_median_frames_between_snaps": float(np.median(np.diff(cs))) if len(cs) > 1 else None}
    if on and off:
        son, soff = set(on), set(off)
        card["car_blinks_per_100_frames_voxel_on"] = round(100.0 * sum(1 for b in cb if b in son) / len(on), 1)
        card["car_blinks_per_100_frames_voxel_off"] = round(100.0 * sum(1 for b in cb if b in soff) / len(off), 1)
        card["car_snaps_per_100_frames_voxel_on"] = round(100.0 * sum(1 for b in cs if b in son) / len(on), 1)
        card["car_snaps_per_100_frames_voxel_off"] = round(100.0 * sum(1 for b in cs if b in soff) / len(off), 1)
    print(json.dumps(card, indent=1))
    print("car luma, first 40 frames:", [round(float(v)) for v in car[:40]])
    if not a.no_jev:
        facts = ("Facts: a car driven at 120 km/h at night, the camera holding it at one screen spot; its box's brightness per "
                 "captured frame (captured_fps a second) against a road box beside it under the same street lamps. A blink "
                 "is a frame brighter or darker than both neighbours by more than 30 %; a snap is one frame-to-frame jump of more "
                 "than 40 %. A car lit like the road snaps and steps about as often as the road box; several snaps a second "
                 "while the road has none is the car flickering. ")
        q = {
            "car_flickers": noul(facts + "Does the car flicker (its brightness jumping frame to frame on its own)?"),
            "cause_voxel": choice(facts + "If it flickers, is voxel lighting the cause?", {
                "voxel": "it blinks clearly more in the voxel lighting on frames than in the off frames",
                "not_voxel": "it blinks about as much with voxel lighting on as off",
                "unknown": "no on / off split, or too few frames"}),
            "look_needed": noul(facts + "Should a person look at the frames?"),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    if a.json:
        json.dump(card, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
