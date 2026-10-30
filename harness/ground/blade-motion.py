#!/usr/bin/env python3
"""Do the grass blades move like foliage sway's plants round them? Motion of a still-camera devCapture (2026-10-31).

Built for the maintainer's "make the grass blades move in the same way the foliage sway does". The run alternates the blades
on (A) and off (B) every few seconds (`--prop devGrassAlternate=4000 --prop devGrassAltB=on:false`, wind pinned, camera
still, `--prop devCapture=<start>,<secs>,60,100,gray,crop=x:y:w:h,ram`):

  * every 2nd frame, the horizontal shift of every BxB block against the median frame of its half (A or B), by normalised
    cross-correlation over -S..S px with a parabolic sub-pixel step: the block's dx(t);
  * blocks are sorted by what the B half (blades off) shows: `sway` = the painted plants moving there (foliage sway:
    tufts, bushes), `lawn` = still in B but moving in A (only the blades move there), the rest dropped;
  * per class: the motion's size (median std of dx, px), its dominant frequency (mean spectrum over the A or B windows),
    the share of the power within 0.35 Hz of the sway oscillator's frequency and of the old blades' wave frequency (0.54 Hz);
  * coherence: the correlation of dx(t) between a `lawn` block and the `sway` blocks within --near px in the A half,
    against the correlation between two `sway` blocks the same distance apart in the B half (foliage sway with itself).
    A blade field that moves with foliage sway's field reads the same as sway against sway.

Usage: blade-motion.py RUN [--block 24] [--search 18] [--near 260] [--json f]
"""
import argparse
import json
import os
import re
import sys

import numpy as np


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
    return f[::-1].astype(np.float32) if gray else f[::-1, :, :3].astype(np.float32) @ np.array([0.2126, 0.7152, 0.0722], np.float32)


def alternation(run):
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        m = re.search(r"grass blades: alternating every (\d+) ms from epoch_ms (\d+)", line)
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def bsum(a, b):
    hb, wb = a.shape[0] // b, a.shape[1] // b
    return a[: hb * b, : wb * b].reshape(hb, b, wb, b).sum(axis=(1, 3))


def block_shifts(f, ref, b, S):
    """dx (px, + = right) and the NCC peak of every block of f against ref."""
    H, W = f.shape
    W2 = (W - 2 * S) // b * b
    H2 = H // b * b
    r = ref[:H2, S:S + W2]
    r = r.reshape(H2 // b, b, W2 // b, b)
    rm = r.mean(axis=(1, 3), keepdims=True)
    rc = r - rm
    rn = np.sqrt((rc * rc).sum(axis=(1, 3)))
    sc = []
    for s in range(-S, S + 1):
        g = f[:H2, S + s:S + s + W2].reshape(H2 // b, b, W2 // b, b)
        gc = g - g.mean(axis=(1, 3), keepdims=True)
        num = (gc * rc).sum(axis=(1, 3))
        den = np.sqrt((gc * gc).sum(axis=(1, 3))) * rn + 1e-6
        sc.append(num / den)
    sc = np.stack(sc)  # (2S+1, hb, wb)
    i = np.argmax(sc, axis=0)
    best = np.take_along_axis(sc, i[None], 0)[0]
    ic = np.clip(i, 1, 2 * S - 1)
    a = np.take_along_axis(sc, (ic - 1)[None], 0)[0]
    c = np.take_along_axis(sc, ic[None], 0)[0]
    e = np.take_along_axis(sc, (ic + 1)[None], 0)[0]
    d = a - 2 * c + e
    sub = np.where((d < 0) & (i == ic), 0.5 * (a - e) / np.where(d < 0, d, -1), 0.0)
    # f[x + s] ~ ref[x]: the content moved right by s
    return (i - S + sub).astype(np.float32), best.astype(np.float32), rn


def spectrum(series, fps):
    """mean normalised power spectrum of a list of (n,) series (each demeaned, Hann window)."""
    out = None
    for s in series:
        if len(s) < 16:
            continue
        x = (s - s.mean()) * np.hanning(len(s))
        p = np.abs(np.fft.rfft(x, n=256)) ** 2
        p /= p.sum() + 1e-9
        out = p if out is None else out + p
    f = np.fft.rfftfreq(256, 1 / fps)
    return f, (out / max(1, len(series)) if out is not None else None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--block", type=int, default=24)
    ap.add_argument("--search", type=int, default=18)
    ap.add_argument("--near", type=float, default=260.0)
    ap.add_argument("--step", type=int, default=2)
    ap.add_argument("--sway-hz", type=float, default=1.56)
    ap.add_argument("--json")
    a = ap.parse_args()
    w, h, gray, stamps, frames = capture(a.run)
    alt = alternation(a.run)
    if alt is None:
        sys.exit("no 'grass blades: alternating' line in console.txt")
    period, t0 = alt
    idx = np.arange(0, len(stamps), a.step)
    half = ((stamps[idx] - t0) // period) % 2  # 0 = A (blades on), 1 = B (off)
    win = (stamps[idx] - t0) // period
    # drop the frames within 150 ms of a switch (the regeneration, a frame of the other set)
    ph = (stamps[idx] - t0) % period
    keep = (ph > 150) & (ph < period - 50)
    fps = 1000.0 / np.median(np.diff(stamps[idx]))
    L = {}
    for k in (0, 1):
        sel = idx[(half == k) & keep]
        stack = np.stack([luma(frames[i], gray) for i in sel[:: max(1, len(sel) // 60)]])
        L[k] = np.median(stack, axis=0)
    dx = {0: [], 1: []}
    ncc = {0: [], 1: []}
    wins = {0: [], 1: []}
    rn = None
    for j, i in enumerate(idx):
        if not keep[j]:
            continue
        k = int(half[j])
        d, c, rn_k = block_shifts(luma(frames[i], gray), L[k], a.block, a.search)
        dx[k].append(d)
        ncc[k].append(c)
        wins[k].append(int(win[j]))
        if k == 1:
            rn = rn_k
    for k in (0, 1):
        dx[k] = np.stack(dx[k])
        ncc[k] = np.stack(ncc[k])
        wins[k] = np.array(wins[k])
    hb, wb = dx[0].shape[1:]
    textured = rn > 4.0 * a.block  # the block has texture (std over ~4 grey levels)
    good = lambda k: np.median(ncc[k], axis=0) > 0.5

    def wstd(k):
        """per block: the mean over the half's windows of dx's std inside a window (no drift between windows)."""
        out = []
        for wv in np.unique(wins[k]):
            m = wins[k] == wv
            if m.sum() > 8:
                out.append(dx[k][m].std(axis=0))
        return np.mean(out, axis=0)

    sA, sB = wstd(0), wstd(1)
    sway = textured & good(1) & (sB > 1.0)
    lawn = textured & good(0) & (sB < 0.35) & (sA > 0.6)
    res = {"run": os.path.basename(a.run.rstrip("/")), "fps": round(float(fps), 1), "blocks": int(hb * wb),
           "sway_blocks": int(sway.sum()), "lawn_blocks": int(lawn.sum())}
    if sway.sum() == 0 or lawn.sum() == 0:
        print(json.dumps(res))
        sys.exit("not enough sway / lawn blocks")
    res["amp_lawn_A_px"] = round(float(np.median(sA[lawn])), 2)
    res["amp_sway_B_px"] = round(float(np.median(sB[sway])), 2)
    res["amp_sway_A_px"] = round(float(np.median(sA[sway])), 2)
    res["ncc_sway_A"] = round(float(np.median(ncc[0][:, sway])), 3)
    res["ncc_sway_B"] = round(float(np.median(ncc[1][:, sway])), 3)

    def series(k, mask):
        out = []
        for wv in np.unique(wins[k]):
            m = wins[k] == wv
            if m.sum() > 16:
                for y, x in zip(*np.nonzero(mask)):
                    out.append(dx[k][m, y, x])
        return out

    for name, k, mask in (("lawn_A", 0, lawn), ("sway_B", 1, sway), ("sway_A", 0, sway)):
        f, p = spectrum(series(k, mask), fps)
        if p is None:
            continue
        band = lambda c: float(p[(f > c - 0.35) & (f < c + 0.35)].sum())
        lo = f > 0.2
        res["peak_hz_" + name] = round(float(f[lo][np.argmax(p[lo])]), 2)
        res["share_sway_hz_" + name] = round(band(a.sway_hz), 3)
        res["share_wave_hz_" + name] = round(band(0.54), 3)

    # coherence: lawn vs sway blocks within --near px (A), sway vs sway at the same distances (B)
    yy, xx = np.mgrid[0:hb, 0:wb]
    cy, cx = (yy + 0.5) * a.block, (xx + 0.5) * a.block

    def corr_pairs(k, ma, mb, same):
        out, dists = [], []
        A_ = list(zip(*np.nonzero(ma)))
        B_ = list(zip(*np.nonzero(mb)))
        for (y1, x1) in A_:
            for (y2, x2) in B_:
                if same and (y2, x2) <= (y1, x1):
                    continue
                dd = np.hypot(cy[y1, x1] - cy[y2, x2], cx[y1, x1] - cx[y2, x2])
                if dd < a.block * 1.5 or dd > a.near:
                    continue
                cs = []
                for wv in np.unique(wins[k]):
                    m = wins[k] == wv
                    if m.sum() > 16:
                        u, v = dx[k][m, y1, x1], dx[k][m, y2, x2]
                        if u.std() > 1e-3 and v.std() > 1e-3:
                            cs.append(np.corrcoef(u, v)[0, 1])
                if cs:
                    out.append(float(np.mean(cs)))
                    dists.append(dd)
        return np.array(out), np.array(dists)

    cl, dl = corr_pairs(0, lawn, sway, False)
    cs_, ds_ = corr_pairs(1, sway, sway, True)
    cll, dll = corr_pairs(0, lawn, lawn, True)
    res["corr_lawn_sway_A"] = round(float(np.median(cl)), 3) if len(cl) else None
    res["corr_sway_sway_B"] = round(float(np.median(cs_)), 3) if len(cs_) else None
    res["corr_lawn_lawn_A"] = round(float(np.median(cll)), 3) if len(cll) else None
    res["pairs"] = [int(len(cl)), int(len(cs_)), int(len(cll))]
    # with a phase per plant the signed correlation of two places spreads round 0; |r| says how locked they are
    res["abs_lawn_sway_A"] = round(float(np.mean(np.abs(cl))), 3) if len(cl) else None
    res["abs_sway_sway_B"] = round(float(np.mean(np.abs(cs_))), 3) if len(cs_) else None
    print(json.dumps(res))
    print(f"blocks: sway {res['sway_blocks']} lawn {res['lawn_blocks']} of {res['blocks']}, {fps:.0f} fps analysed")
    print(f"motion (std of dx in a window, px): blades on lawn {res['amp_lawn_A_px']}, foliage sway alone {res['amp_sway_B_px']}, "
          f"sway squares with blades {res['amp_sway_A_px']}")
    print(f"dominant frequency: blades {res.get('peak_hz_lawn_A')} Hz, sway {res.get('peak_hz_sway_B')} Hz; power near the sway oscillator "
          f"{res.get('share_sway_hz_lawn_A')} vs {res.get('share_sway_hz_sway_B')}, near the old wave frequency {res.get('share_wave_hz_lawn_A')} vs {res.get('share_wave_hz_sway_B')}")
    print(f"coherence: mean |r| blades vs sway {res['abs_lawn_sway_A']}, sway vs sway {res['abs_sway_sway_B']}; median r blades vs sway {res['corr_lawn_sway_A']}, sway vs sway {res['corr_sway_sway_B']}, blades vs blades {res['corr_lawn_lawn_A']} "
          f"(pairs {res['pairs']}); match quality on sway squares A {res['ncc_sway_A']} / B {res['ncc_sway_B']}")
    if a.json:
        json.dump(res, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
