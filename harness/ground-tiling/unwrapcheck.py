#!/usr/bin/env python3
"""Technique 1, the other storage: the material unwrapped to world squares (S x S texels a square) and sampled back
through the affine iso map (bilinear, as a bake would), against the iso-layout storage GroundTileArt uses (an exact
texel copy). Prints, per square size S: texels a square (storage), PSNR of the round trip over the diamond, and the
sharpness kept (mean |Laplacian| of luma inside the diamond / the original's).

    unwrapcheck.py SPRITES_DIR [ids...]
"""
import sys
import numpy as np
from art import diamond, diamond_mask, unwrap, load_tiles

LW = np.array([0.299, 0.587, 0.114], np.float32)


def reproject(sq):
    S = sq.shape[0]
    ys, xs = np.mgrid[0:64, 0:128]
    cx = xs + 0.5 - 63.0; cy = ys + 0.5
    u = (cy / 32.0 + cx / 64.0) * 0.5; v = (cy / 32.0 - cx / 64.0) * 0.5
    x = u * S - 0.5; y = v * S - 0.5
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int); fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    def g(yy, xx): return sq[yy % S, xx % S]
    return (g(y0, x0) * (1 - fx) * (1 - fy) + g(y0, x0 + 1) * fx * (1 - fy) + g(y0 + 1, x0) * (1 - fx) * fy + g(y0 + 1, x0 + 1) * fx * fy)


def lap(l, m):
    L = np.abs(4 * l[1:-1, 1:-1] - l[:-2, 1:-1] - l[2:, 1:-1] - l[1:-1, :-2] - l[1:-1, 2:])
    mm = m[1:-1, 1:-1] & m[:-2, 1:-1] & m[2:, 1:-1] & m[1:-1, :-2] & m[1:-1, 2:]
    return L[mm].mean()


def main():
    d = sys.argv[1]
    ids = [int(a) for a in sys.argv[2:]] or [16, 64, 96, 0]
    m = diamond_mask()
    for i in ids:
        t = load_tiles(d, ["blends_natural_01_%d" % i])[0]
        ref = t[..., :3] @ LW
        row = ["%3d" % i]
        for S in (64, 91, 128, 181):
            back = reproject(unwrap(t, S))
            err = ((back[..., :3] - t[..., :3])[m] ** 2).mean()
            psnr = 10 * np.log10(1.0 / max(err, 1e-12))
            sh = lap(back[..., :3] @ LW, m) / lap(ref, m)
            row.append("S=%d %5d tex/sq psnr %.1f dB sharp %.2f" % (S, S * S, psnr, sh))
        print(" | ".join(row) + " | iso: 4096 tex/sq exact")


if __name__ == "__main__":
    main()
