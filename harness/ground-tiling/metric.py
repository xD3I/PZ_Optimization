#!/usr/bin/env python3
"""Tileability metrics for a 1:1 field of natural floor (2x tiles), cheap proxies of TexTile (CVPR 2024):

- grid: mean |luma gradient| across the square edges (diamond outlines) / elsewhere. 1.0 = the grid is not there.
- rep:  repeat score, the mean of the four largest normalised autocorrelation peaks at square-lattice shifts
        (64(i-j), 32(i+j)), |i|,|j| <= 4, (0,0) excluded; a field without repeat has ~0 there.
- bg:   the 99th percentile of the autocorrelation away from the lattice and the origin (the texture's own
        correlation; rep should come down to it).
- con:  luma standard deviation / the exemplar's (blending washes contrast out: < 1).
- sharp: mean |Laplacian| of luma / the exemplar's (blur: < 1).

    metric.py IMG.png [--ref EXEMPLAR.png] [--origin-px X,Y]
"""
import sys
import numpy as np

LW = np.array([0.299, 0.587, 0.114], np.float32)


def luma(img):
    return img[..., :3] @ LW


def acf(l):
    l = l - l.mean()
    F = np.fft.fft2(l, s=(l.shape[0] * 2, l.shape[1] * 2))
    a = np.real(np.fft.ifft2(F * np.conj(F)))
    # normalise by the overlap count so large shifts are not biased low
    H, W = l.shape
    ny = np.clip(H - np.abs(np.where(np.arange(2 * H) < H, np.arange(2 * H), np.arange(2 * H) - 2 * H)), 1, None)
    nx = np.clip(W - np.abs(np.where(np.arange(2 * W) < W, np.arange(2 * W), np.arange(2 * W) - 2 * W)), 1, None)
    a = a / (ny[:, None] * nx[None, :])
    return a / a[0, 0]


def lattice_shifts(k=4):
    s = set()
    for i in range(-k, k + 1):
        for j in range(-k, k + 1):
            if i == 0 and j == 0:
                continue
            s.add((64 * (i - j), 32 * (i + j)))
    return sorted(s)


def repeat_score(img, maxdx=None, maxdy=None):
    l = luma(img); H, W = l.shape
    a = acf(l)
    vals = []
    mask = np.ones_like(a, bool)
    for dx, dy in lattice_shifts():
        if abs(dx) > W // 2 or abs(dy) > H // 2:
            continue
        best = -1
        for ex in (-1, 0, 1):
            for ey in (-1, 0, 1):
                y, x = (dy + ey) % (2 * H), (dx + ex) % (2 * W)
                best = max(best, a[y, x]); mask[y, x] = False
        vals.append(best)
    # background: shifts within the same range, away from the lattice and the origin
    ys = np.where(np.arange(2 * H) < H, np.arange(2 * H), np.arange(2 * H) - 2 * H)
    xs = np.where(np.arange(2 * W) < W, np.arange(2 * W), np.arange(2 * W) - 2 * W)
    Y, X = np.meshgrid(ys, xs, indexing="ij")
    rng = (np.abs(X) <= W // 2) & (np.abs(Y) <= H // 2) & ((np.abs(X) > 24) | (np.abs(Y) > 12))
    bg = np.percentile(a[rng & mask], 99)
    vals = sorted(vals, reverse=True)
    return float(np.mean(vals[:4])), float(bg)


def grid_energy(img, ox=0, oy=0):
    l = luma(img); H, W = l.shape
    X, Y = np.meshgrid(np.arange(W) + ox, np.arange(H) + oy)
    cx = X + 0.5 - 63.0; cy = Y + 0.5
    u = np.floor((cx / 64.0 + cy / 32.0) * 0.5); v = np.floor((cy / 32.0 - cx / 64.0) * 0.5)
    lab = u * 100003 + v
    gx = np.abs(l[:, 1:] - l[:, :-1]); gy = np.abs(l[1:] - l[:-1])
    sx = lab[:, 1:] != lab[:, :-1]; sy = lab[1:] != lab[:-1]
    # per direction (grass strokes make gx and gy differ; the edges mix them in another proportion)
    return float(0.5 * (gx[sx].mean() / max(gx[~sx].mean(), 1e-6) + gy[sy].mean() / max(gy[~sy].mean(), 1e-6)))


def lap(l):
    return np.abs(4 * l[1:-1, 1:-1] - l[:-2, 1:-1] - l[2:, 1:-1] - l[1:-1, :-2] - l[1:-1, 2:]).mean()


def summary(img, ref=None, ox=0, oy=0):
    rep, bg = repeat_score(img)
    g = grid_energy(img, ox, oy)
    s = f"grid={g:.3f} rep={rep:.3f} bg={bg:.3f}"
    if ref is not None:
        con = luma(img).std() / max(luma(ref).std(), 1e-6)
        sh = lap(luma(img)) / max(lap(luma(ref)), 1e-6)
        dm = (img[..., :3].mean((0, 1)) - ref[..., :3].mean((0, 1))) * 255
        s += f" con={con:.3f} sharp={sh:.3f} dmean=({dm[0]:+.1f},{dm[1]:+.1f},{dm[2]:+.1f})"
    return s


if __name__ == "__main__":
    from PIL import Image
    img = np.asarray(Image.open(sys.argv[1]).convert("RGB"), np.float32) / 255
    ref = None
    if "--ref" in sys.argv:
        ref = np.asarray(Image.open(sys.argv[sys.argv.index("--ref") + 1]).convert("RGB"), np.float32) / 255
    ox, oy = 0, 0
    if "--origin-px" in sys.argv:
        ox, oy = map(int, sys.argv[sys.argv.index("--origin-px") + 1].split(","))
    print(summary(img, ref, ox, oy))
