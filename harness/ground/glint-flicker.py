#!/usr/bin/env python3
"""Do the glints flicker? Spark toggling and blinking blocks over a devCapture sequence, and Jev's verdict (2026-10-25).

Built for the maintainer's report "the glints flicker like crazy" (the built-in benchmark's horde scene in winter, rain
falling as snow, 8:00, uncapped, the e++ options). Glints are 1-3 px sparks: a glint that is world-anchored stays on the
same ground point from one frame to the next (it moves with the camera only), a flickering one switches on and off.

Per run (<run>/capture, pzopt.FrameCapture: gray or RGBA, a 1:1 crop; capture with
  --prop devCapture=<start>,<secs>,60,100,gray,crop=1920:1080:1600:540,ram):
  * consecutive frames are aligned by phase correlation (the camera follows the player);
  * a spark is a pixel brighter than the mean of its 7x7 neighbourhood by more than --spark (40/255) and at least
    --floor (110) in luma;
  * a spark of frame i with no spark within 1 px in frame i+1 dies, one of i+1 with none in i is born; toggles = births +
    deaths. Rain / snow particles, muzzle flashes and running characters toggle too: that is the background, read from a
    --control run of the same scene with the glints off; the glints' own flicker is the excess over it;
  * blinking blocks (as harness/zoom-flicker.py): 16x16 block means leaving their 3x3 neighbourhood's range in both the
    previous and the next frame by more than 4/255 (a patch that flashes for one frame);
  * mass toggles: frames where more than a quarter of the sparks of the previous frame die at once.

Usage: glint-flicker.py --test RUN [--before RUN] [--control RUN] [--reference NAME=RUN] [--scene "..."] [--context "..."]
                        [--json out] [--no-jev]
  --test     the build under test (glints on)
  --before   the same scene on the build that showed the flicker (optional)
  --control  the same scene with the glints off (the background of particles, flashes, motion)
  --reference NAME=RUN (repeatable) the same scene and build with one setting changed (e.g. glintTwinklePct): its numbers
             go to Jev as `reference_NAME` beside `test`, the change described in --context
  --scene    what the captures show (default: the horde scene of the report; the wet-night drive needs its own)
Exit 0 = Jev says `test` does not flicker.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def capture(run):
    d = os.path.join(run, "capture")
    lines = open(os.path.join(d, "index.txt")).read().splitlines()
    head = lines[0]
    w = int(re.search(r"w=(\d+)", head).group(1))
    h = int(re.search(r"h=(\d+)", head).group(1))
    gray = "fmt=gray" in head
    stamps = np.array([int(x.split()[0]) for x in lines[1:] if x.strip() and x.split()[0].isdigit()], dtype=np.int64)
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * (1 if gray else 4)))
    frames = raw[: n * w * h * (1 if gray else 4)].reshape(n, h, w, *(() if gray else (4,)))
    return w, h, gray, stamps[:n], frames


def luma(f, gray):
    if gray:
        return f[::-1].astype(np.float32)
    return f[::-1, :, :3].astype(np.float32) @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)


def box(a, r):
    c = np.cumsum(np.cumsum(np.pad(a, ((r + 1, r), (r + 1, r))), 0), 1)
    k = 2 * r + 1
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def shift(a, b):
    fa = np.fft.rfft2(a - a.mean())
    fb = np.fft.rfft2(b - b.mean())
    r = fb * np.conj(fa)
    r /= np.abs(r) + 1e-9
    c = np.fft.irfft2(r, s=a.shape)
    dy, dx = np.unravel_index(np.argmax(c), c.shape)
    if dy > a.shape[0] // 2:
        dy -= a.shape[0]
    if dx > a.shape[1] // 2:
        dx -= a.shape[1]
    return int(dy), int(dx)


def overlap(a, b, dy, dx):
    h, w = a.shape
    ya0, yb0 = max(0, -dy), max(0, dy)
    xa0, xb0 = max(0, -dx), max(0, dx)
    hh, ww = h - abs(dy), w - abs(dx)
    return a[ya0:ya0 + hh, xa0:xa0 + ww], b[yb0:yb0 + hh, xb0:xb0 + ww]


def dilate(m):
    o = m.copy()
    o[1:, :] |= m[:-1, :]
    o[:-1, :] |= m[1:, :]
    p = o.copy()
    p[:, 1:] |= o[:, :-1]
    p[:, :-1] |= o[:, 1:]
    return p


def analyse(run, spark, floor, block, thresh, skip):
    w, h, gray, stamps, frames = capture(run)
    n = len(stamps)
    lum = [None] * n
    sp = [None] * n
    hb, wb = h // block, w // block
    blk = [None] * n
    for i in range(skip, n):
        a = luma(frames[i], gray)
        lum[i] = a
        sp[i] = (a - box(a, 3) > spark) & (a >= floor)
        blk[i] = a[: hb * block, : wb * block].reshape(hb, block, wb, block).mean(axis=(1, 3))
    sparks, births, deaths, mass = [], [], [], 0
    for i in range(skip, n - 1):
        dy, dx = shift(lum[i], lum[i + 1])
        a, b = overlap(sp[i], sp[i + 1], dy, dx)
        na, nb = int(a.sum()), int(b.sum())
        dead = int((a & ~dilate(b)).sum())
        born = int((b & ~dilate(a)).sum())
        sparks.append(na)
        births.append(born)
        deaths.append(dead)
        if na >= 20 and dead > 0.25 * na:
            mass += 1
    per = []
    for i in range(skip + 1, n - 1):
        def span(g):
            v = sliding_window_view(g, (3, 3))
            return v.min(axis=(2, 3)), v.max(axis=(2, 3))
        pmn, pmx = span(blk[i - 1])
        nmn, nmx = span(blk[i + 1])
        c = blk[i][1:-1, 1:-1]
        per.append(int(((c > np.maximum(pmx, nmx) + thresh) | (c < np.minimum(pmn, nmn) - thresh)).sum()))
    sparks, births, deaths, per = map(np.array, (sparks, births, deaths, per))
    secs = max(1e-3, (stamps[-1] - stamps[skip]) / 1000.0)
    tog = births + deaths
    return {
        "run": os.path.basename(run.rstrip("/")),
        "frames": int(n - skip), "seconds": round(secs, 1), "fps": round((n - skip) / secs), "capture": f"{w}x{h}",
        "sparks_per_frame": round(float(sparks.mean()), 1),
        "spark_toggles_per_frame": round(float(tog.mean()), 1),
        "spark_toggle_share": round(float(tog.sum() / max(1, 2 * sparks.sum())), 3),
        "spark_toggles_p90": int(np.percentile(tog, 90)),
        "mass_toggle_frames": int(mass),
        "blinking_blocks_per_10s": round(10 * float(per.sum()) / secs, 1),
        "frames_with_10_or_more_blinking_blocks": int((per >= 10).sum()),
        "largest_frame_blocks": int(per.max()) if len(per) else 0,
    }


def analyse_target(run, roi, skip):
    """HDR debug view 5 (hdrTune debugView=5, RGBA capture): the HDR glint target over a dimmed world, glints yellow (shown)
    or red (dropped by the composite's colour match). A glint pixel: red channel 40/255 above blue. No snowflakes, flashes or
    characters in it, so no control run: every toggle is the glints' own."""
    w, h, gray, stamps, frames = capture(run)
    if gray:
        raise SystemExit(f"{run}: target mode needs an RGBA capture (no gray field)")
    x0, y0, x1, y1 = roi if roi else (0, 0, w, h)
    n = len(stamps)
    masks, counts, lums = [], [], []
    for i in range(skip, n):
        a = frames[i][::-1, :, :3].astype(np.int16)[y0:y1, x0:x1]
        m = (a[:, :, 0] - a[:, :, 2]) > 40
        masks.append(m)
        lums.append(a.mean(axis=2).astype(np.float32))
        counts.append(int(m.sum()))
    counts = np.array(counts)
    tog = []
    for i in range(len(masks) - 1):
        dy, dx = shift(lums[i], lums[i + 1])  # the camera follows the player: compare the overlap
        a, b = overlap(masks[i], masks[i + 1], dy, dx)
        d = int((a & ~dilate(b)).sum())
        bn = int((b & ~dilate(a)).sum())
        tog.append(d + bn)
    tog = np.array(tog)
    ref = max(1.0, float(np.percentile(counts, 90)))
    empty = counts < 0.1 * ref
    longest = cur = 0
    for e in empty:
        cur = 0 if e else cur + 1
        longest = max(longest, cur)
    jumps = int((np.abs(np.diff(counts)) > 0.5 * ref).sum())
    d = np.diff(counts).astype(np.float64)
    # one-frame flashes / dropouts of the field: a frame off from both neighbours by a quarter of the lit field, same sign
    flashes = int(((np.abs(d[:-1]) > 0.25 * ref) & (np.abs(d[1:]) > 0.25 * ref) & (np.sign(d[:-1]) == -np.sign(d[1:]))).sum())
    secs = max(1e-3, (stamps[-1] - stamps[skip]) / 1000.0)
    return {
        "run": os.path.basename(run.rstrip("/")), "view": "hdr glint target (debugView 5)",
        "frames": int(len(counts)), "seconds": round(secs, 1), "roi": [x0, y0, x1, y1],
        "glint_px_per_frame": round(float(counts.mean()), 1), "glint_px_p90": int(ref),
        "frames_with_the_field_empty": int(empty.sum()), "share_of_frames_empty": round(float(empty.mean()), 3),
        "whole_field_jumps_per_10s": round(10 * jumps / secs, 1),
        "one_frame_flashes_or_dropouts_per_10s": round(10 * flashes / secs, 1),
        "median_frame_to_frame_change_of_the_glint_count": round(float(np.median(np.abs(d)) / max(1.0, counts.mean())), 3),
        "longest_lit_stretch_frames": int(longest),
        "glint_px_toggling_per_frame": round(float(tog.mean()), 1),
        "toggle_share": round(float(tog.sum() / max(1, 2 * counts.sum())), 3),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", required=True)
    ap.add_argument("--before")
    ap.add_argument("--control")
    ap.add_argument("--reference", action="append", default=[], help="NAME=RUN, repeatable")
    ap.add_argument("--scene", default="a player shoots a horde of zombies on a snowy pier in winter, snow falling, early morning, "
                    "the camera follows the player")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    ap.add_argument("--spark", type=float, default=40.0)
    ap.add_argument("--floor", type=float, default=110.0)
    ap.add_argument("--block", type=int, default=16)
    ap.add_argument("--thresh", type=float, default=4.0)
    ap.add_argument("--skip", type=int, default=5)
    ap.add_argument("--target", action="store_true", help="captures of the HDR glint target (hdrTune debugView=5, RGBA)")
    ap.add_argument("--roi", help="x0:y0:x1:y1 of the capture (target mode: leave out the water's own glitter)")
    a = ap.parse_args()
    runs = {"test": a.test, "before": a.before, "control": a.control}
    for r in a.reference:
        name, _, run = r.partition("=")
        runs["reference_" + name] = run
    if a.target:
        roi = tuple(int(v) for v in a.roi.split(":")) if a.roi else None
        res = {k: analyse_target(v, roi, a.skip) for k, v in runs.items() if v}
    else:
        res = {k: analyse(v, a.spark, a.floor, a.block, a.thresh, a.skip) for k, v in runs.items() if v}
    if "control" in res:
        for k in res:
            if k != "control":
                c = res["control"]
                res[k]["excess_spark_toggles_per_frame_over_control"] = round(res[k]["spark_toggles_per_frame"] - c["spark_toggles_per_frame"], 1)
                res[k]["excess_blinking_blocks_per_10s_over_control"] = round(res[k]["blinking_blocks_per_10s"] - c["blinking_blocks_per_10s"], 1)
    for k, r in res.items():
        print(f"{k:8s} {json.dumps(r)}")
    out = {"metrics": res}
    code = 0
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul  # noqa: E402
        setup_target = ("Frame-exact captures (every presented frame read back in game, a 1:1 crop of the screen) of the same scene: a "
                     "player shoots a horde of zombies on a snowy pier in winter, snow falling, 8:00, a torch and a lamp nearby; the "
                     "camera holds still. The picture is the HDR output's debug view of the glint layer: the world dimmed, every "
                     "glint the HDR composite adds drawn yellow. Glints are 1-3 pixel sparkles on snow; a correct glint stays on "
                     "from frame to frame while its light and the view hold. Only the glints are counted (no snowflakes, flashes "
                     "or characters), so every toggle is the glints' own: `frames_with_the_field_empty` = frames where (almost) "
                     "every glint of the field vanished at once, `whole_field_jumps_per_10s` = the glint count changing by half the lit field "
                     "between two frames, `one_frame_flashes_or_dropouts_per_10s` = a frame whose glint count is a quarter of the "
                     "lit field above or below both neighbours, `glint_px_toggling_per_frame` / `toggle_share` = glint pixels appearing or vanishing between "
                     "consecutive frames. " + a.context)
        state = {
            "setup": setup_target if a.target else "Frame-exact captures (every presented frame read back in game, a 1:1 crop of the screen centre) of the same "
                     "scene: " + a.scene + ". Glints are 1-3 pixel sparkles on snow / frost / wet ground; a correct glint is "
                     "anchored to the ground and stays on from frame to frame while its light and the view hold (it may fade or "
                     "move with the camera), a flickering one switches on and off frame by frame. Frames are aligned for the "
                     "camera's motion. A spark is a pixel 40/255 brighter than its 7x7 surroundings; toggles = sparks that "
                     "appear or vanish between consecutive frames; snowflakes, muzzle flashes and moving characters toggle "
                     "too, which is why a `control` run with the glints switched off gives the background, and the glints' own "
                     "flicker is the excess of `test` (or `before`) over it. Blinking blocks = 16x16 pixel areas that flash for "
                     "one frame. Mass toggle frames = frames where over a quarter of the sparks vanish at once. " + a.context,
            "report": "the maintainer sees the glints flicker 'like crazy' in this scene",
            **res,
        }
        if a.target:
            state["note"] = "no control run in this mode: the view holds glints only"
        questions = {
            "test_flickers": noul("Does `test` show glint flicker a player would object to: clearly more spark toggles per frame "
                                  "than `control` (the glints-off background), a large share of its sparks toggling every frame, "
                                  "or blinking blocks / mass toggles well above `control`?"),
            "verdict": choice({"question": "What best describes `test`?",
                               "note": "Judge the excess over `control`: `control` has no glints, so its toggles are snowflakes, "
                                       "flashes and motion. A fixed build sits near `control` in toggles per frame and blinking "
                                       "blocks while still showing sparks of its own."},
                              {"fixed": "`before` flickers and `test` is at or near the `control` background",
                               "still_flickers": "`test` still flickers well above `control`",
                               "flicker_confirmed": "`test` flickers well above `control` and there is no fixed build to compare (a repro)",
                               "no_flicker": "`test` is at the `control` background and there is no flickering `before`: not reproduced",
                               "inconclusive": "too few sparks or frames, or contradicting numbers"}),
        }
        if a.reference:
            questions["residual_is_twinkle"] = noul("Compare `test` with the `reference_*` runs (same scene and build, one "
                                                    "setting changed, see the setup): is what `test` still shows above "
                                                    "`control` the glints' intended behaviour as that setting describes it, "
                                                    "rather than a flicker of its own?")
        if a.before:
            questions["before_flickers"] = noul("Does `before` show that objectionable glint flicker?")
        log = []
        answers = ask(state, questions, log=log)
        print("jev:")
        print("  " + fmt(answers).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out.update({"state": state, "answers": answers, "typesafe": log[0]})
        v = answers.get("verdict", {})
        top = v.get("choice") if isinstance(v, dict) else None
        code = 0 if top in ("fixed", "no_flicker") else 1
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))
    sys.exit(code)


if __name__ == "__main__":
    main()
