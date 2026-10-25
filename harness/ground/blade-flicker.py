#!/usr/bin/env python3
"""Do the grass blades flicker? One-frame flashes and blinking blocks over a devCapture walk, and Jev's verdict (2026-10-25).

Built for the maintainer's report "the grass blades flicker when the character moves, most visibly at night with a torch
pointed at them". Blades are thin strips over the painted lawn; a correct blade keeps its colour from one frame to the next
while the light and the view hold (it sways smoothly, it moves with the camera), a flickering one changes brightness for a
frame and comes back.

Per run (<run>/capture, pzopt.FrameCapture RGBA or gray, a 1:1 crop round the player; capture every frame with
  --prop devCapture=<start>,<secs>,1000,100,crop=x:y:w:h,ram):
  * frames i+1 and i+2 are aligned to frame i by phase correlation (the camera follows the player);
  * a flash pixel: its luma in i+1 differs from both i and i+2 by more than --flash (6/255) in the same direction while i
    and i+2 agree within half of that (a one-frame blink: motion and sway do not come back, a blink does);
    counted separately in lit pixels (luma >= --lit, the torch beam / lamp pools) and dark ones;
  * blinking blocks (as harness/zoom-flicker.py): 16x16 block means leaving their 3x3 neighbourhood's range in both the
    previous and the next frame by more than --thresh (3/255);
  * the --control run is the same walk with the blades off (the painted lawn, the character, the torch's own changes):
    the blades' flicker is the excess of `test` / `before` over it.

Usage: blade-flicker.py --test RUN [--before RUN] [--control RUN] [--reference NAME=RUN]... [--scene "..."] [--context "..."]
                        [--json out] [--no-jev] [--roi x0:y0:x1:y1]
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


def luma(f, gray, roi):
    a = f[::-1].astype(np.float32) if gray else f[::-1, :, :3].astype(np.float32) @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    if roi:
        x0, y0, x1, y1 = roi
        a = a[y0:y1, x0:x1]
    return a


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


def crop3(a, b, c, s1, s2):
    """a, and b / c shifted by s1 / s2 (b[y + dy, x + dx] ~ a[y, x]), on their common area."""
    h, w = a.shape
    ys = [0, s1[0], s2[0]]
    xs = [0, s1[1], s2[1]]
    y0, y1 = max(0, -min(ys)), h - max(0, max(ys))
    x0, x1 = max(0, -min(xs)), w - max(0, max(xs))
    if y1 - y0 < 32 or x1 - x0 < 32:
        return None
    return (a[y0:y1, x0:x1], b[y0 + s1[0]:y1 + s1[0], x0 + s1[1]:x1 + s1[1]], c[y0 + s2[0]:y1 + s2[0], x0 + s2[1]:x1 + s2[1]])


def local_min_diff(b, a, r=1):
    """Per pixel, b - a at the shift (within +-r px) where |b - a| is smallest: a registration error of a pixel (layers
    moving by different whole pixels in one frame, sub-pixel motion) explains itself away; a pixel that blinks does not."""
    best = None
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            d = b - np.roll(np.roll(a, dy, axis=0), dx, axis=1)
            if best is None:
                best = d
            else:
                best = np.where(np.abs(d) < np.abs(best), d, best)
    best[:r, :] = 0
    best[-r:, :] = 0
    best[:, :r] = 0
    best[:, -r:] = 0
    return best


def blocks(a, block):
    hb, wb = a.shape[0] // block, a.shape[1] // block
    return a[: hb * block, : wb * block].reshape(hb, block, wb, block).mean(axis=(1, 3))


def analyse(run, flash, lit, block, thresh, skip, roi, windows=None):
    """Streams the capture (three frames held at a time). windows: [(t0, t1), ...] seconds from the first frame to keep."""
    w, h, gray, stamps, frames = capture(run)
    n = len(stamps)
    cache = {}

    def L(i):
        if i not in cache:
            cache[i] = luma(frames[i], gray, roi)
            for k in [k for k in cache if k < i - 2]:
                del cache[k]
        return cache[i]

    def keep(i):
        if not windows:
            return True
        t = (stamps[i] - stamps[0]) / 1000.0
        return any(a <= t <= b for a, b in windows)

    fl_lit, fl_dark, n_lit, n_dark, blinks, mad, lit_mean, moving, times, per_frame = [], [], [], [], [], [], [], [], [], []
    prev_shift = None
    for i in range(skip, n - 2):
        if not keep(i) or not keep(i + 2):
            prev_shift = None
            continue
        a0, b0, c0 = L(i), L(i + 1), L(i + 2)
        s1 = prev_shift if prev_shift is not None else shift(a0, b0)
        s12 = shift(b0, c0)
        prev_shift = s12
        s2 = (s1[0] + s12[0], s1[1] + s12[1])
        t = crop3(a0, b0, c0, s1, s2)
        if t is None:
            continue
        a, b, c = t
        d1, d2 = b - a, b - c
        f = (np.abs(d1) > flash) & (np.abs(d2) > flash) & (np.sign(d1) == np.sign(d2)) & (np.abs(a - c) < flash / 2)
        Lm = (a >= lit) & (c >= lit)
        nl, nd = int(Lm.sum()), int((~Lm).sum())
        fl_lit.append(int((f & Lm).sum()))
        fl_dark.append(int((f & ~Lm).sum()))
        n_lit.append(nl)
        n_dark.append(nd)
        mad.append(float(np.abs(a - c).mean()))
        lit_mean.append(float(a[Lm].mean()) if nl else 0.0)
        moving.append(abs(s1[0]) + abs(s1[1]) > 0)
        times.append((stamps[i + 1] - stamps[0]) / 1000.0)
        ba, bb, bc = blocks(a, block), blocks(b, block), blocks(c, block)
        nb = 0
        if min(ba.shape) >= 3:
            def span(g):
                v = sliding_window_view(g, (3, 3))
                return v.min(axis=(2, 3)), v.max(axis=(2, 3))
            pmn, pmx = span(ba)
            nmn, nmx = span(bc)
            cc = bb[1:-1, 1:-1]
            nb = int(((cc > np.maximum(pmx, nmx) + thresh) | (cc < np.minimum(pmn, nmn) - thresh)).sum())
        blinks.append(nb)
    fl_lit, fl_dark, n_lit, n_dark, blinks, moving, times = map(np.array, (fl_lit, fl_dark, n_lit, n_dark, blinks, moving, times))
    m = len(times)
    secs = max(1e-3, float(times[-1] - times[0])) if m > 1 else 1e-3
    per_lit = fl_lit / np.maximum(1, n_lit)
    # one-second windows: where the lit flashes are
    win = []
    if m:
        for t0 in np.arange(np.floor(times[0]), times[-1], 1.0):
            sel = (times >= t0) & (times < t0 + 1.0)
            if sel.sum() >= 10:
                win.append((round(float(t0), 1), round(1000 * float(fl_lit[sel].sum() / max(1, n_lit[sel].sum())), 2),
                            round(1000 * float(fl_dark[sel].sum() / max(1, n_dark[sel].sum())), 2), round(float(moving[sel].mean()), 2)))
    return {
        "run": os.path.basename(run.rstrip("/")),
        "frames": int(m), "seconds": round(secs, 1), "fps": round(m / secs), "capture": f"{w}x{h}" + (f" roi {roi}" if roi else ""),
        "lit_share_of_pixels": round(float(n_lit.sum() / max(1, n_lit.sum() + n_dark.sum())), 3),
        "lit_mean_luma": round(float(np.mean(lit_mean)), 1) if m else 0,
        "flash_px_per_1000_lit_px": round(1000 * float(fl_lit.sum() / max(1, n_lit.sum())), 2),
        "flash_px_per_1000_dark_px": round(1000 * float(fl_dark.sum() / max(1, n_dark.sum())), 2),
        "flash_lit_p90_per_1000": round(1000 * float(np.percentile(per_lit, 90)), 2) if m else 0,
        "flash_lit_while_camera_moves_per_1000": round(1000 * float(fl_lit[moving].sum() / max(1, n_lit[moving].sum())), 2) if moving.any() else None,
        "flash_lit_while_camera_still_per_1000": round(1000 * float(fl_lit[~moving].sum() / max(1, n_lit[~moving].sum())), 2) if m and (~moving).any() else None,
        "camera_moving_share_of_frames": round(float(moving.mean()), 3) if m else 0,
        "blinking_blocks_per_10s": round(10 * float(blinks.sum()) / secs, 1),
        "frames_with_10_or_more_blinking_blocks": int((blinks >= 10).sum()),
        "mean_abs_change_over_two_frames": round(float(np.mean(mad)), 2) if m else 0,
        "per_second_lit_dark_flash_per_1000_and_camera_moving_share": win,
    }


def analyse_alternation(run, lit, skip, roi, guard_ms=150, jump=3.0):
    """A run with devGrassAlternate=N (blades A / B every N ms, the same walk): per-frame flicker energy split by phase.
    Energy of frame i+1 = mean over lit (or dark) pixels of |b - (a + c) / 2| after aligning a and c to b (motion and
    sway are smooth: a steady change cancels, a blink does not). Frames within guard_ms of a switch, and triplets with a
    whole-frame jump (mean luma off its neighbours by > jump: another object popping in, e.g. a tree crown) are dropped.
    The pairing: each A period against the mean of the B periods on either side."""
    w, h, gray, stamps, frames = capture(run)
    per = t0 = None
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        mm = re.search(r"grass blades: alternating every (\d+) ms from epoch_ms (\d+)", line)
        if mm:
            per, t0 = int(mm.group(1)), int(mm.group(2))
    if per is None:
        raise SystemExit(f"{run}: no grass blades alternation in console.txt")
    n = len(stamps)
    cache = {}

    def L(i):
        if i not in cache:
            cache[i] = luma(frames[i], gray, roi)
            for k in [k for k in cache if k < i - 2]:
                del cache[k]
        return cache[i]

    rows = []  # (period, energy lit, energy dark, flash lit per 1000)
    dropped_jump = 0
    for i in range(skip, n - 2):
        ph = [(stamps[k] - t0) % per for k in (i, i + 1, i + 2)]
        k0 = [(stamps[k] - t0) // per for k in (i, i + 1, i + 2)]
        if len(set(k0)) > 1 or min(ph) < guard_ms or max(ph) > per - guard_ms or stamps[i] < t0:
            continue
        a0, b0, c0 = L(i), L(i + 1), L(i + 2)
        ma, mb, mc = a0.mean(), b0.mean(), c0.mean()
        if abs(mb - (ma + mc) / 2) > jump:
            dropped_jump += 1
            continue
        s1, s2 = shift(b0, a0), shift(b0, c0)
        t = crop3(b0, a0, c0, s1, s2)
        if t is None:
            continue
        b, a, c = t
        e = np.abs(b - (a + c) / 2)
        Lm = (a >= lit) & (b >= lit) & (c >= lit)
        d1, d2 = b - a, b - c
        f = (np.abs(d1) > 6) & (np.abs(d2) > 6) & (np.sign(d1) == np.sign(d2)) & (np.abs(a - c) < 3)
        if Lm.sum() < 500:
            continue
        # the motion-tolerant flash: b off both neighbours (same sign) at every shift within a pixel
        r1, r2 = local_min_diff(b, a), local_min_diff(b, c)
        g = (np.abs(r1) > 6) & (np.abs(r2) > 6) & (np.sign(r1) == np.sign(r2))
        rows.append((int(k0[0]), float(e[Lm].mean()), float(e[~Lm].mean()), 1000.0 * float((f & Lm).sum()) / float(Lm.sum()),
                     1000.0 * float((g & Lm).sum()) / float(Lm.sum())))
    r = np.array(rows)
    A, B = r[r[:, 0] % 2 == 0], r[r[:, 0] % 2 == 1]
    pm = {}
    for k in np.unique(r[:, 0]).astype(int):
        sel = r[r[:, 0] == k]
        if len(sel) >= 10:
            pm[k] = sel[:, 1:].mean(axis=0)
    diffs = [pm[k] - (pm[k - 1] + pm[k + 1]) / 2 for k in pm if k % 2 == 0 and k - 1 in pm and k + 1 in pm]
    d = np.array(diffs) if diffs else np.zeros((0, 4))

    def se(col):
        return round(float(d[:, col].std(ddof=1) / np.sqrt(len(d))), 3) if len(d) > 2 else None
    return {
        "run": os.path.basename(run.rstrip("/")), "period_ms": per, "triplets_A": int(len(A)), "triplets_B": int(len(B)),
        "dropped_whole_frame_jumps": dropped_jump,
        "A_energy_lit": round(float(A[:, 1].mean()), 3), "B_energy_lit": round(float(B[:, 1].mean()), 3),
        "A_energy_dark": round(float(A[:, 2].mean()), 3), "B_energy_dark": round(float(B[:, 2].mean()), 3),
        "A_flash_lit_per_1000": round(float(A[:, 3].mean()), 3), "B_flash_lit_per_1000": round(float(B[:, 3].mean()), 3),
        "paired_periods": int(len(d)),
        "paired_A_minus_B_energy_lit": round(float(d[:, 0].mean()), 3) if len(d) else None, "se_energy_lit": se(0),
        "paired_A_minus_B_energy_dark": round(float(d[:, 1].mean()), 3) if len(d) else None, "se_energy_dark": se(1),
        "paired_A_minus_B_flash_lit": round(float(d[:, 2].mean()), 3) if len(d) else None, "se_flash_lit": se(2),
        "A_over_B_energy_lit": round(float(A[:, 1].mean() / max(1e-6, B[:, 1].mean())), 3),
        "A_robust_flash_lit_per_1000": round(float(A[:, 4].mean()), 3), "B_robust_flash_lit_per_1000": round(float(B[:, 4].mean()), 3),
        "paired_A_minus_B_robust_flash_lit": round(float(d[:, 3].mean()), 3) if len(d) else None, "se_robust_flash_lit": se(3),
    }


def alt_jev(a):
    """Jev on in-run A / B pairs (devGrassAlternate): each pair's A and B halves share the walk, the light and the frames'
    pacing, so the paired difference (each A second against the B seconds either side) is the blades' change alone."""
    desc = dict(d.partition("=")[::2] for d in a.alt_desc)
    res = {}
    for r in a.alt:
        name, _, run = r.partition("=")
        m = analyse_alternation(run, a.lit, a.skip, tuple(int(v) for v in a.roi.split(":")) if a.roi else None)
        m["what"] = desc.get(name, "")
        res[name] = m
        print(f"{name:12s} {json.dumps(m)}")
    if a.no_jev:
        return
    from typesafe_client import ask, choice, fmt, noul  # noqa: E402
    state = {
        "setup": "Frame-exact captures (every presented frame read back in game, a 1:1 crop round the player) of " + a.scene
                 + ". Each run alternates two settings every second on the same walk (`what` says what A and B are). Grass "
                 "blades are thin strips drawn over the painted lawn; a correct blade keeps its brightness and place from frame "
                 "to frame (it sways smoothly and moves with the ground under it), a flickering one blinks for a frame or "
                 "jitters against the ground. Frames are aligned for the camera's motion. flash = pixels off both neighbouring "
                 "frames by 6/255 the same way while those agree; robust_flash = the same at every shift within one pixel "
                 "(registration and slow motion explained away), per 1000 lit pixels (the torch-lit ground); energy = mean "
                 "|b - (a + c) / 2|. paired_A_minus_B_* = A against the B seconds either side, with its standard error. A "
                 "blades-off half (B = off) is the background: the painted lawn, the character, the light's own changes. " + a.context,
        "report": "the maintainer sees the grass blades flicker when the character moves, most visibly at night with a torch "
                  "pointed at them",
        **res,
    }
    questions = {}
    for name in res:
        questions[name + "_A_flickers_more"] = noul(f"In `{name}`, does A flicker clearly more than B (judge "
                                                    "paired_A_minus_B_robust_flash_lit and _flash_lit against their errors)?")
    questions["verdict"] = choice(
        {"question": "Taking the runs together, what is the state of the blade flicker?",
         "note": "A fix should flicker clearly less than the old build in the same walk; compare it with the blades-off background too."},
        {"fixed": "the fix removes the blade flicker: at or near the blades-off background",
         "improved": "the fix flickers clearly less than the old build but still clearly above the blades-off background",
         "still_flickers": "the fix is no better than the old build",
         "flicker_confirmed": "only a repro: blades on flicker clearly above blades off",
         "inconclusive": "the numbers contradict each other or their errors are too large"})
    log = []
    answers = ask(state, questions, log=log)
    print("jev:")
    print("  " + fmt(answers).replace("\n", "\n  "))
    print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
    if a.json:
        Path(a.json).write_text(json.dumps({"metrics": res, "state": state, "answers": answers, "typesafe": log[0]}, indent=1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test")
    ap.add_argument("--before")
    ap.add_argument("--control")
    ap.add_argument("--reference", action="append", default=[], help="NAME=RUN, repeatable")
    ap.add_argument("--scene", default="night (1:00), a lawn, the player walks east across it and then turns on the spot with a "
                    "lit torch, the camera follows the player, zoom 1")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    ap.add_argument("--flash", type=float, default=6.0)
    ap.add_argument("--lit", type=float, default=40.0)
    ap.add_argument("--block", type=int, default=16)
    ap.add_argument("--thresh", type=float, default=3.0)
    ap.add_argument("--skip", type=int, default=5)
    ap.add_argument("--roi", help="x0:y0:x1:y1 of the capture")
    ap.add_argument("--windows", help="t0-t1,t0-t1 seconds from the first frame to analyse (a video's walking stretches)")
    ap.add_argument("--alternation", nargs="+", help="runs with devGrassAlternate: split A / B per run, print, exit")
    ap.add_argument("--alt", action="append", default=[], help="NAME=RUN of a devGrassAlternate run for Jev (repeatable); "
                    "--alt-desc NAME=what A and B are")
    ap.add_argument("--alt-desc", action="append", default=[], help="NAME=description of its A and B halves")
    a = ap.parse_args()
    if a.alt:
        alt_jev(a)
        return
    if a.alternation:
        for r in a.alternation:
            print(json.dumps(analyse_alternation(r, a.lit, a.skip, tuple(int(v) for v in a.roi.split(":")) if a.roi else None)))
        sys.exit(0)
    roi = tuple(int(v) for v in a.roi.split(":")) if a.roi else None
    runs = {"test": a.test, "before": a.before, "control": a.control}
    for r in a.reference:
        name, _, run = r.partition("=")
        runs["reference_" + name] = run
    wins = [tuple(float(x) for x in w.split("-")) for w in a.windows.split(",")] if a.windows else None
    res = {k: analyse(v, a.flash, a.lit, a.block, a.thresh, a.skip, roi, wins) for k, v in runs.items() if v}
    if "control" in res:
        c = res["control"]
        for k in res:
            if k != "control":
                for key in ("flash_px_per_1000_lit_px", "flash_px_per_1000_dark_px", "blinking_blocks_per_10s"):
                    res[k]["excess_" + key + "_over_control"] = round(res[k][key] - c[key], 2)
    for k, r in res.items():
        print(f"{k:10s} {json.dumps(r)}")
    out = {"metrics": res}
    code = 0
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul  # noqa: E402
        state = {
            "setup": "Frame-exact captures (every presented frame read back in game, a 1:1 crop round the player) of the same "
                     "scene: " + a.scene + ". Grass blades are thin strips drawn over the painted lawn near the player; a correct "
                     "blade keeps its brightness from frame to frame while the light and view hold (it sways smoothly and moves "
                     "with the camera), a flickering one changes brightness for one frame and comes back. Frames are aligned for "
                     "the camera's motion. A flash pixel = its brightness in a frame differs from both neighbouring frames by "
                     "6/255 the same way while those two agree (a one-frame blink; steady motion or sway does not come back). "
                     "Counted per 1000 pixels, separately where the torch / lamps light the ground (lit) and elsewhere (dark). "
                     "Blinking blocks = 16x16 pixel areas that flash for one frame. `control` is the same walk with the grass "
                     "blades switched off (the painted lawn, the character, the light's own changes): the blades' flicker is the "
                     "excess of `test` (or `before`) over it. " + a.context,
            "report": "the maintainer sees the grass blades flicker when the character moves, most visibly at night with a "
                      "torch pointed at them",
            **res,
        }
        questions = {
            "test_flickers": noul("Does `test` show grass-blade flicker a player would object to: clearly more flash pixels "
                                  "(especially lit ones) or blinking blocks than `control`?"),
            "verdict": choice({"question": "What best describes `test`?",
                               "note": "Judge the excess over `control` (no blades). A fixed build sits near `control`."},
                              {"fixed": "`before` flickers and `test` is at or near the `control` background",
                               "still_flickers": "`test` still flickers well above `control`",
                               "flicker_confirmed": "`test` flickers well above `control` and there is no fixed build to compare (a repro)",
                               "no_flicker": "`test` is at the `control` background and there is no flickering `before`: not reproduced",
                               "inconclusive": "too few frames or lit pixels, or contradicting numbers"}),
        }
        if a.before:
            questions["before_flickers"] = noul("Does `before` show that objectionable blade flicker?")
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
