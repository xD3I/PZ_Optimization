#!/usr/bin/env python3
"""Does voxel lighting draw the lights correctly? Code measures a devVoxelLightAlternate + devCapture run (voxel lighting on
and off in turns, the same scene), TypeSafe's Jev classifies the numbers (it never sees a frame).

    harness/voxel/voxel-judge.py <run> [--mode still|moving] [--context "..."] [--json out.json] [--no-jev] [--skip S]
                                       [--maps out-prefix]

still (a held camera, default): the median voxel-on frame against the median voxel-off frame (the game's light), per pixel
the luma ratio R = (on + 2) / (off + 2):
  * brightness: mean luma on / off, and per 4 x 4 screen cell (the worst cells named)
  * colour shift: the mean chroma difference where both are lit (a tint the native does not have)
  * specks: small isolated blobs much darker (R < 0.55, the game had light there) or brighter (R > 1.8) than all round them
    (the cache's dots: a layer texel that took another surface's light)
  * streaks: thin elongated dark blobs (dashes along chunk-texture edges)
  * horizontal edges: long straight horizontal runs of a strong R step (an isometric picture has almost none: a texture's or a
    layer's border, a level seam)
  * crushed black: on near black where the game had light; new light: on bright where the game was dark (both can be real
    shadows / light through windows: sizes are given)
  * flicker: within each phase, the share of pixels whose luma spreads by > 20 over the phase's frames, on vs off phases
moving (a walk or a drive): A-B-A transients (flicker.py) and frame-to-frame mean-luma pops, on phases vs off phases.
HUD corners and the hotbar are masked. --maps writes the R map and the speck / streak / edge masks as PNGs.
"""
import argparse
import json
import os
import re
import sys

import numpy as np
from PIL import Image

class ndimage:  # the few scipy.ndimage operations used here, in numpy (no scipy on the desktop)
    @staticmethod
    def uniform_filter(a, size):
        k = int(size)
        if k <= 1:
            return a
        p = np.pad(a, ((0, k - 1), (0, k - 1)), mode="edge")
        c = np.cumsum(np.cumsum(p, 0), 1)
        c = np.pad(c, ((1, 0), (1, 0)))
        h, w = a.shape
        return (c[k:k + h, k:k + w] - c[:h, k:k + w] - c[k:k + h, :w] + c[:h, :w]) / (k * k)

    @staticmethod
    def binary_dilation(m, iterations=1):
        for _ in range(iterations):
            d = m.copy()
            d[1:] |= m[:-1]
            d[:-1] |= m[1:]
            d[:, 1:] |= m[:, :-1]
            d[:, :-1] |= m[:, 1:]
            m = d
        return m

    @staticmethod
    def label(m):
        """4-connected components: min-label propagation with pointer jumping."""
        h, w = m.shape
        lab = np.where(m, np.arange(h * w, dtype=np.int64).reshape(h, w), np.int64(h * w))
        big = np.int64(h * w)
        while True:
            prev = lab
            n = lab.copy()
            n[1:] = np.minimum(n[1:], np.where(m[1:] & m[:-1], lab[:-1], big))
            n[:-1] = np.minimum(n[:-1], np.where(m[:-1] & m[1:], lab[1:], big))
            n[:, 1:] = np.minimum(n[:, 1:], np.where(m[:, 1:] & m[:, :-1], lab[:, :-1], big))
            n[:, :-1] = np.minimum(n[:, :-1], np.where(m[:, :-1] & m[:, 1:], lab[:, 1:], big))
            n = np.where(m, n, big)
            flat = n.ravel()
            fl = np.where(flat < big, flat, 0)
            n = np.where(m, np.minimum(n, fl[fl].reshape(h, w)), big)  # pointer jumping
            if np.array_equal(n, prev):
                break
            lab = n
        ids = np.unique(lab[m])
        out = np.zeros((h, w), np.int32)
        out[m] = np.searchsorted(ids, lab[m]) + 1
        return out, len(ids)

    @staticmethod
    def find_objects(lab):
        n = lab.max()
        ys, xs = np.nonzero(lab)
        v = lab[ys, xs] - 1
        y0 = np.full(n, 1 << 30); y1 = np.zeros(n, int); x0 = np.full(n, 1 << 30); x1 = np.zeros(n, int)
        np.minimum.at(y0, v, ys); np.maximum.at(y1, v, ys); np.minimum.at(x0, v, xs); np.maximum.at(x1, v, xs)
        return [(slice(int(y0[i]), int(y1[i]) + 1), slice(int(x0[i]), int(x1[i]) + 1)) for i in range(n)]

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "ppl"))
sys.path.insert(0, os.path.join(HERE, ".."))
import capture  # noqa: E402
from flicker import transients  # noqa: E402
from typesafe_client import ask, choice, noul, fmt  # noqa: E402

GRID = 4
ROWS = {0: "top", 1: "upper-middle", 2: "lower-middle", 3: "bottom"}
COLS = {0: "left", 1: "centre-left", 2: "centre-right", 3: "right"}


def luma(f):
    f = f.astype(np.float32)
    return 0.2126 * f[..., 0] + 0.7152 * f[..., 1] + 0.0722 * f[..., 2]


def hud_mask(h, w):
    m = np.ones((h, w), bool)
    m[:, : int(w * 0.035)] = False  # the left icon column
    m[: int(h * 0.11), int(w * 0.94):] = False  # the clock / moodles
    m[int(h * 0.93):, :] = False  # the hotbar, the version line
    return m


def phases(run, st):
    per = t0 = None
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        m = re.search(r"voxel light: alternating every (\d+) ms from epoch_ms (\d+)", line)
        if m:
            per, t0 = int(m.group(1)), int(m.group(2))
    if per is None:
        sys.exit("no devVoxelLightAlternate in console.txt")
    out = {}
    for i, s in enumerate(st):
        ph = (s - t0) % per
        if ph < 200 or ph > per - 120:
            continue  # the render thread lags the switch
        k = (s - t0) // per
        out.setdefault(k, []).append(i)
    return out, per


def cell_of(y, x, h, w):
    return min(GRID - 1, y * GRID // h), min(GRID - 1, x * GRID // w)


def blobs(mask, R, okmask, small, ring_lo, ring_hi):
    """Small components of mask whose surrounding ring is unlike them: (count, list of (y, x, area))."""
    lab, n = ndimage.label(mask)
    if n == 0:
        return []
    out = []
    objs = ndimage.find_objects(lab)
    for i, sl in enumerate(objs):
        comp = lab[sl] == i + 1
        area = int(comp.sum())
        if area < 3 or area > small:
            continue
        y0, y1, x0, x1 = sl[0].start, sl[0].stop, sl[1].start, sl[1].stop
        pad = 4
        Y0, Y1, X0, X1 = max(0, y0 - pad), min(R.shape[0], y1 + pad), max(0, x0 - pad), min(R.shape[1], x1 + pad)
        win = R[Y0:Y1, X0:X1]
        inner = np.zeros(win.shape, bool)
        inner[y0 - Y0:y1 - Y0, x0 - X0:x1 - X0] = comp
        ring = ~ndimage.binary_dilation(inner, iterations=1) & okmask[Y0:Y1, X0:X1]
        if ring.sum() < 8:
            continue
        rm = float(np.median(win[ring]))
        if ring_lo <= rm <= ring_hi:
            out.append(((y0 + y1) // 2, (x0 + x1) // 2, area))
    return out


def still(run, fr, st, a):
    ph, per = phases(run, st)
    # phase k even = on (the alternation starts on)
    on = [i for k, v in ph.items() if k % 2 == 0 for i in v]
    off = [i for k, v in ph.items() if k % 2 == 1 for i in v]
    if len(on) < 2 or len(off) < 2:
        sys.exit("need at least two frames of each phase")
    ON = np.median(fr[on].astype(np.float32), axis=0)
    OFF = np.median(fr[off].astype(np.float32), axis=0)
    h, w = ON.shape[:2]
    Yon, Yoff = luma(ON), luma(OFF)
    ok = hud_mask(h, w) & ((Yon > 4) | (Yoff > 4))
    R = ndimage.uniform_filter((Yon + 2) / (Yoff + 2), 2)
    card = {"scene": a.context or "", "mode": "still", "frames_on": len(on), "frames_off": len(off), "capture": f"{w}x{h}",
            "pixels_measured": int(ok.sum())}
    card["brightness_ratio_on_off"] = round(float(Yon[ok].mean() / max(1e-3, Yoff[ok].mean())), 3)
    cells = []
    for gy in range(GRID):
        for gx in range(GRID):
            m = ok.copy()
            m[: gy * h // GRID] = False
            m[(gy + 1) * h // GRID:] = False
            m[:, : gx * w // GRID] = False
            m[:, (gx + 1) * w // GRID:] = False
            if m.sum() > 500:
                cells.append((round(float(Yon[m].mean() / max(1e-3, Yoff[m].mean())), 3), f"{ROWS[gy]} {COLS[gx]}"))
    cells.sort()
    card["darkest_cells_ratio"] = cells[:3]
    card["brightest_cells_ratio"] = cells[-3:]
    lit = ok & (Yon > 40) & (Yoff > 40)
    if lit.sum() > 100:
        def chroma(I, Y):
            return np.stack([(I[..., 0] - I[..., 1]) / (Y + 1), (I[..., 2] - I[..., 1]) / (Y + 1)], -1)
        card["colour_shift"] = round(float(np.abs(chroma(ON, Yon) - chroma(OFF, Yoff))[lit].mean()), 4)
    else:
        card["colour_shift"] = None
    mp = ok.sum() / 1e6
    dark = ok & (R < 0.55) & (Yoff > 25)
    bright = ok & (R > 1.8) & (Yon > 25)
    small = int(0.00006 * h * w) + 30  # ~a few cache texels
    ds = blobs(dark, R, ok, small, 0.85, 1.25)
    bs = blobs(bright, R, ok, small, 0.8, 1.2)
    card["dark_specks_per_mpx"] = round(len(ds) / mp, 1)
    card["bright_specks_per_mpx"] = round(len(bs) / mp, 1)
    sc = {}
    for y, x, _ in ds + bs:
        c = cell_of(y, x, h, w)
        sc[c] = sc.get(c, 0) + 1
    card["speck_cells"] = [f"{ROWS[c[0]]} {COLS[c[1]]}: {n}" for c, n in sorted(sc.items(), key=lambda t: -t[1])[:3]]
    # thin streaks: elongated dark components
    lab, n = ndimage.label(dark)
    streaks = 0
    for i, sl in enumerate(ndimage.find_objects(lab)):
        hh, ww = sl[0].stop - sl[0].start, sl[1].stop - sl[1].start
        area = int((lab[sl] == i + 1).sum())
        if max(hh, ww) >= 40 and area < 0.25 * hh * ww and min(hh, ww) * 1.0 / max(hh, ww) < 0.6:
            streaks += 1
    card["thin_dark_streaks"] = streaks
    # long straight horizontal / vertical steps of log R
    L = np.log(np.clip(R, 0.05, 20))
    gy_ = np.abs(np.diff(L, axis=0))[:, :-1] > 0.35
    gx_ = np.abs(np.diff(L, axis=1))[:-1, :] > 0.35
    okc = ok[:-1, :-1] & ok[1:, 1:]
    def runs(b, axis, minlen):
        cnt = 0
        b = b & okc
        lines = b if axis == 1 else b.T
        for row in lines:
            if row.sum() < minlen:
                continue
            d = np.diff(np.concatenate([[0], row.astype(np.int8), [0]]))
            st_, en = np.where(d == 1)[0], np.where(d == -1)[0]
            cnt += int(((en - st_) >= minlen).sum())
        return cnt
    minlen = max(32, w // 40)
    card["long_horizontal_steps"] = runs(gy_, 1, minlen)
    card["long_vertical_steps"] = runs(gx_, 0, max(24, h // 25))
    crushed = ok & (Yon < 8) & (Yoff > 35)
    newlit = ok & (Yon > 60) & (Yoff < 20)
    card["crushed_black_share"] = round(float(crushed.sum() / ok.sum()), 4)
    card["new_light_share"] = round(float(newlit.sum() / ok.sum()), 4)
    # flicker within phases: per phase the median share of pixels changing by > 20 between consecutive frames (a one-time
    # settle, the volume's first fill at the capture's start, is not flicker), the first phase of each kind left out
    def spread(idx_sets):
        shares = []
        for v in idx_sets[1:]:
            if len(v) < 3:
                continue
            Y = [luma(fr[i]) for i in v]
            d = [float((np.abs(Y[j] - Y[j - 1]) > 20)[ok].mean()) for j in range(1, len(Y))]
            shares.append(float(np.median(d)))
        return round(float(np.mean(shares)), 5) if shares else None
    card["flicker_share_on_phases"] = spread([v for k, v in sorted(ph.items()) if k % 2 == 0])
    card["flicker_share_off_phases"] = spread([v for k, v in sorted(ph.items()) if k % 2 == 1])
    if a.maps:
        Image.fromarray(np.clip(R * 127, 0, 255).astype(np.uint8)).save(a.maps + "-ratio.png")
        vis = np.zeros((h, w, 3), np.uint8)
        vis[dark] = (255, 0, 0)
        vis[bright] = (0, 255, 0)
        vis[:-1, :-1][gy_ & okc] = (255, 255, 0)
        for y, x, _ in ds + bs:
            vis[max(0, y - 6):y + 6, max(0, x - 6):x + 6, 2] = 255
        Image.fromarray(vis).save(a.maps + "-marks.png")
    return card


def moving(run, fr, st, a):
    ph, per = phases(run, st)
    small = []
    for i in range(fr.shape[0]):
        small.append(np.asarray(Image.fromarray(fr[i]).resize((fr.shape[2] // 2, fr.shape[1] // 2))))
    small = np.stack(small)
    h, w = small.shape[1:3]
    ok = hud_mask(h, w)
    Y = np.stack([luma(f) for f in small]).astype(np.int16)
    res = {"on": [], "off": []}
    pops = {"on": [], "off": []}
    for k, v in ph.items():
        if len(v) < 6:
            continue
        seq = Y[v]
        tr = transients(seq, 24, 3)
        res["on" if k % 2 == 0 else "off"].append(float(tr[:, ok].sum() / max(1, len(v))))
        m = seq[:, ok].mean(1)
        pops["on" if k % 2 == 0 else "off"].append(float(np.abs(np.diff(m)).max()))
    card = {"scene": a.context or "", "mode": "moving", "phases_on": len(res["on"]), "phases_off": len(res["off"]), "capture": f"{w * 2}x{h * 2}"}
    card["transient_px_per_frame_on"] = round(float(np.mean(res["on"])), 1) if res["on"] else None
    card["transient_px_per_frame_off"] = round(float(np.mean(res["off"])), 1) if res["off"] else None
    card["max_luma_pop_on"] = round(float(np.max(pops["on"])), 2) if pops["on"] else None
    card["max_luma_pop_off"] = round(float(np.max(pops["off"])), 2) if pops["off"] else None
    card["mean_luma_on"] = round(float(np.mean([Y[i][ok].mean() for k, v in ph.items() if k % 2 == 0 for i in v])), 1)
    card["mean_luma_off"] = round(float(np.mean([Y[i][ok].mean() for k, v in ph.items() if k % 2 == 1 for i in v])), 1)
    return card


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--mode", default="still", choices=["still", "moving"])
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--maps")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    fr, st = capture.load(a.run)
    card = still(a.run, fr, st, a) if a.mode == "still" else moving(a.run, fr, st, a)
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        if a.mode == "still":
            facts = ("Facts for reading the numbers: a ratio of 1 is the game's own light; voxel lighting is meant to keep the overall "
                     "brightness within 0.8-1.25 and add real shadows and facing, so a few darker cells are expected, but isolated "
                     "specks (more than about 20 per megapixel), thin dark streaks (more than a few), long horizontal steps (more than "
                     "about 15: an isometric picture has almost no horizontal edges), a colour shift above 0.06, or more flicker in "
                     "the on phases than in the off phases (more than 1.5 times, and above 0.002) are artifacts. ")
            q = {
                "lights_correct": noul(facts + "Do the numbers show the voxel lighting drawing the lights correctly, without artifacts?"),
                "artifact": choice(facts + "Which artifact do the numbers point to most?", {
                    "none": "no artifact: brightness, colour, specks, streaks, edges and flicker within the facts' limits",
                    "specks": "too many isolated dark or bright specks",
                    "streaks": "thin dark streaks or dashes",
                    "seams": "long horizontal steps (texture or layer seams)",
                    "flicker": "more flicker with voxel lighting on than off",
                    "colour": "a colour shift",
                    "brightness": "overall brightness outside 0.8-1.25",
                    "inconclusive": "the numbers support none of these clearly",
                }),
                "look_needed": noul(facts + "Should a person look at the frames (borderline numbers or a likely artifact)?"),
            }
        else:
            facts = ("Facts: the character or the car moves, voxel lighting on and off in turns; transients are pixels that change "
                     "and change back within 3 frames (flicker), luma pops the largest frame-to-frame brightness step. More than 1.5 "
                     "times the off phases' transients (and more than 200 px a frame) or luma pops above 6 that the off phases do not "
                     "have mean voxel lighting flickers or pops. ")
            q = {
                "lights_correct": noul(facts + "Do the numbers show the voxel lighting stable while moving, with no flicker or pops of its own?"),
                "artifact": choice(facts + "Which does the moving run show?", {
                    "none": "stable: transients and pops like the off phases",
                    "flicker": "more transients with voxel lighting on",
                    "pops": "brightness pops with voxel lighting on",
                    "inconclusive": "too few phases or frames",
                }),
                "look_needed": noul(facts + "Should a person look at the frames?"),
            }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    if a.json:
        json.dump(card, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
