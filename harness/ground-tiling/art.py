#!/usr/bin/env python3
"""Offline prototype of the ground-tiling material art (pzopt.GroundTileArt does the same at runtime).

A natural floor sprite's diamond (2x tiles: 126 px wide, 64 rows, 4096 px = one lattice cell of a=(64,32),
b=(64,-32)) tiles the screen exactly, so a 128N x 64N rectangle is an exact period of any pattern laid on the
square lattice (world vectors (N,-N), (N,N)). The exemplars are stored in that iso layout: no resampling, every
integer-pixel fetch copies a source texel.

    art.py extract OUTDIR                      # natural sprites -> OUTDIR/sprites/*.png
    art.py exemplar SPRITES OUT.png ID[,ID..] [--n 2] [--seam none|poisson|avg] [--seed 1] [--layout ...]
    art.py unwrap SPRITES OUT.png ID [--size 64]   # world-square unwrap (resampled), technique 1 alternative
    art.py seams IMG.png [--n N]               # seam energy of an iso exemplar: gradient on slot edges / interior

The slot layout of an N x N exemplar: the lattice cosets Z^2 / <(N,-N),(N,N)> (2N^2 slots); variants are dealt
so that no slot touches a slot of the same variant where avoidable.
"""
import argparse, io, os, struct, sys
import numpy as np
from PIL import Image

GAME = os.environ.get("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid")
DIAMOND_TOP = 192  # the diamond's first row inside the 128x256 sprite (2x tiles)


def pack_pages(path, want):
    f = open(path, "rb")
    def i32(): return struct.unpack("<i", f.read(4))[0]
    def s(): return f.read(i32()).decode("latin-1")
    ver = 0
    if f.read(4) == b"PZPK":
        ver = i32()
    else:
        f.seek(0)
    for _ in range(i32()):
        s(); n = i32(); i32()
        subs = [(s(), [i32() for _ in range(8)]) for _ in range(n)]
        hit = [x for x in subs if want(x[0])]
        ln = i32(); start = f.tell()
        if hit:
            img = Image.open(io.BytesIO(f.read(ln))).convert("RGBA")
            for e, (x, y, w, h, ox, oy, fw, fh) in hit:
                frame = Image.new("RGBA", (fw, fh)); frame.paste(img.crop((x, y, x + w, y + h)), (ox, oy))
                yield e, frame
        else:
            f.seek(start + ln)


def diamond(sprite):
    """float32 RGBA (premultiplied off) of the 128x64 diamond rows of a 2x floor sprite."""
    a = np.asarray(sprite, dtype=np.float32)[DIAMOND_TOP:DIAMOND_TOP + 64] / 255.0
    return a


def diamond_mask():
    m = np.zeros((64, 128), bool)
    for y in range(64):
        r = y if y < 32 else 63 - y
        m[y, 62 - 2 * r:64 + 2 * r] = True
    return m


def slots(n):
    """lattice positions (i, j) of the 2n^2 cosets of Z^2 / <(n,-n),(n,n)>: i in [0,2n), j in [0,n)."""
    return [(i, j) for i in range(2 * n) for j in range(n)]


def splat(tiles, n, layout):
    """iso exemplar (64n x 128n x 4) and slot label map from per-slot diamonds."""
    H, W = 64 * n, 128 * n
    out = np.zeros((H, W, 4), np.float32); lab = -np.ones((H, W), np.int32)
    m = diamond_mask(); ys, xs = np.nonzero(m)
    for k, (i, j) in enumerate(slots(n)):
        t = tiles[layout[k]]
        ox, oy = 64 * (i - j), 32 * (i + j)  # top-left of the slot's sprite rows in screen px
        X = (xs + ox) % W; Y = (ys + oy) % H
        out[Y, X] = t[ys, xs]; lab[Y, X] = k
    assert (lab >= 0).all(), "slots do not cover the period"
    return out, lab


def neighbours(n):
    """slot index pairs that share a diamond edge (world 4-neighbours) under the period."""
    S = slots(n); idx = {}
    def canon(i, j):
        # reduce (i, j) modulo (n,-n), (n,n): i+j mod 2n and i-j mod 2n determine the coset
        for k, (a, b) in enumerate(S):
            if ((i - a) - (j - b)) % (2 * n) == 0 and ((i - a) + (j - b)) % (2 * n) == 0 :  # (i-a, j-b) = p(n,-n) + q(n,n)
                return k
        raise ValueError((i, j))
    pairs = set()
    for k, (i, j) in enumerate(S):
        for di, dj in ((1, 0), (0, 1), (-1, 0), (0, -1)):
            pairs.add(tuple(sorted((k, canon(i + di, j + dj)))))
    return pairs


def deal(nvar, n, seed):
    """a slot -> variant layout avoiding equal neighbours (random restarts)."""
    rng = np.random.default_rng(seed); S = len(slots(n)); pr = neighbours(n)
    best, bad = None, 1e9
    for _ in range(4000):
        lay = [k % nvar for k in range(S)]; rng.shuffle(lay)
        b = sum(1 for a, c in pr if a != c and lay[a] == lay[c])
        if b < bad:
            best, bad = lay, b
            if b == 0:
                break
    return best


def poisson_periodic(img, lab, mode, selfimgs=None):
    """gradient-domain seam repair on a periodic image (FFT Poisson). Inside a slot the guidance is the image
    gradient; across a slot seam it is 0 (mode poisson) or the mean of the one-sided gradients either side (avg).
    Solves lap(u) = div(g) with periodic boundaries; the mean of each channel is kept."""
    out = img.copy()
    H, W = lab.shape
    seam_x = lab != np.roll(lab, -1, axis=1)  # between (y,x) and (y,x+1)
    seam_y = lab != np.roll(lab, -1, axis=0)
    fy = np.fft.fftfreq(H)[:, None]; fx = np.fft.fftfreq(W)[None, :]
    den = (2 * np.cos(2 * np.pi * fx) - 2) + (2 * np.cos(2 * np.pi * fy) - 2)
    den[0, 0] = 1
    for c in range(3):
        u = img[..., c]
        gx = np.roll(u, -1, axis=1) - u; gy = np.roll(u, -1, axis=0) - u
        if mode == "self":
            # across a seam: the mean of the two tiles' own gradients there (each tile continued by its own period),
            # so the seam keeps the texture's ordinary gradient statistics while the colours are reconciled
            gxs = np.zeros_like(gx); gys = np.zeros_like(gy)
            for k, per in enumerate(selfimgs):
                v = per[..., c]
                tx = np.roll(v, -1, axis=1) - v; ty = np.roll(v, -1, axis=0) - v
                ax = (lab == k) | (np.roll(lab, -1, axis=1) == k); ay = (lab == k) | (np.roll(lab, -1, axis=0) == k)
                gxs += np.where(ax, 0.5 * tx, 0.0); gys += np.where(ay, 0.5 * ty, 0.0)
            gx = np.where(seam_x, gxs, gx); gy = np.where(seam_y, gys, gy)
        elif mode == "avg":
            gxs = 0.5 * (np.roll(gx, 1, axis=1) + np.roll(gx, -1, axis=1))
            gys = 0.5 * (np.roll(gy, 1, axis=0) + np.roll(gy, -1, axis=0))
            gx = np.where(seam_x, gxs, gx); gy = np.where(seam_y, gys, gy)
        else:
            gx = np.where(seam_x, 0.0, gx); gy = np.where(seam_y, 0.0, gy)
        div = (gx - np.roll(gx, 1, axis=1)) + (gy - np.roll(gy, 1, axis=0))
        F = np.fft.fft2(div) / den; F[0, 0] = np.fft.fft2(u)[0, 0]
        out[..., c] = np.real(np.fft.ifft2(F))
    return np.clip(out, 0, 1)


def seam_energy(img, lab):
    """mean |gradient| (luma) across slot seams / inside slots. 1.0 = the seams are invisible statistically."""
    l = img[..., :3] @ np.array([0.299, 0.587, 0.114], np.float32)
    gx = np.abs(np.roll(l, -1, axis=1) - l); gy = np.abs(np.roll(l, -1, axis=0) - l)
    sx = lab != np.roll(lab, -1, axis=1); sy = lab != np.roll(lab, -1, axis=0)
    return float(0.5 * (gx[sx].mean() / max(gx[~sx].mean(), 1e-6) + gy[sy].mean() / max(gy[~sy].mean(), 1e-6)))


def load_tiles(spr_dir, ids):
    out = []
    for i in ids:
        name = i if not i.isdigit() else "blends_natural_01_" + i
        out.append(diamond(Image.open(os.path.join(spr_dir, name + ".png")).convert("RGBA")))
    return out


def build(spr_dir, ids, n, seam, seed, layout=None):
    tiles = load_tiles(spr_dir, ids)
    lay = layout or deal(len(tiles), n, seed)
    img, lab = splat(tiles, n, lay)
    raw = seam_energy(img, lab)
    if seam != "none":
        selfimgs = None
        if seam == "self":
            # per slot: the slot's tile laid on its own one-square period, as big as the exemplar
            selfimgs = []
            for k in range(len(lay)):
                one, _ = splat([tiles[lay[k]]], 1, [0, 0])
                selfimgs.append(np.tile(one, (n, n, 1)))
        img = poisson_periodic(img, lab, seam, selfimgs)
    img[..., 3] = 1.0
    return img, lab, lay, raw, seam_energy(img, lab)


def unwrap(sprite_diamond, size):
    """world-square unwrap: (u, v) in [0,1)^2 -> diamond px (63 + 64(u - v), 32(u + v)), bilinear, wrapped."""
    t = sprite_diamond
    m = diamond_mask()
    # the periodic iso image of this diamond, so bilinear taps near the diamond edge read the right neighbour
    per, _ = splat([t], 1, [0, 0])
    u = (np.arange(size) + 0.5) / size
    U, V = np.meshgrid(u, u)
    x = 63.0 + 64.0 * (U - V) - 0.5; y = 32.0 * (U + V) - 0.5
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int); fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    def g(yy, xx): return per[yy % 64, xx % 128]
    return (g(y0, x0) * (1 - fx) * (1 - fy) + g(y0, x0 + 1) * fx * (1 - fy) + g(y0 + 1, x0) * (1 - fx) * fy
            + g(y0 + 1, x0 + 1) * fx * fy)


def save(img, path):
    Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA").save(path)


def main():
    ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("extract"); e.add_argument("out")
    x = sub.add_parser("exemplar"); x.add_argument("sprites"); x.add_argument("out"); x.add_argument("ids")
    x.add_argument("--n", type=int, default=2); x.add_argument("--seam", default="poisson"); x.add_argument("--seed", type=int, default=1)
    u = sub.add_parser("unwrap"); u.add_argument("sprites"); u.add_argument("out"); u.add_argument("id"); u.add_argument("--size", type=int, default=64)
    a = ap.parse_args()
    if a.cmd == "extract":
        d = os.path.join(a.out, "sprites"); os.makedirs(d, exist_ok=True)
        want = lambda e: e.startswith(("blends_natural_", "floors_exterior_natural_", "e_newgrassbase_snow"))
        k = 0
        for name, img in pack_pages(os.path.join(GAME, "media", "texturepacks", "Tiles2x.floor.pack"), want):
            img.save(os.path.join(d, name + ".png")); k += 1
        print(k, "sprites")
    elif a.cmd == "exemplar":
        img, lab, lay, raw, fixed = build(a.sprites, a.ids.split(","), a.n, a.seam, a.seed)
        save(img, a.out)
        print(f"{a.out}: {img.shape[1]}x{img.shape[0]} layout={lay} seam energy raw={raw:.3f} -> {fixed:.3f}")
    elif a.cmd == "unwrap":
        t = load_tiles(a.sprites, [a.id])[0]
        save(unwrap(t, a.size), a.out)


if __name__ == "__main__":
    main()
