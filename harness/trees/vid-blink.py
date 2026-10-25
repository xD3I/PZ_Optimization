#!/usr/bin/env python3
"""Blinking blocks in a decoded capture (vid-blocks.py's npz), 2026-10-25 (the maintainer's tree-flicker video).

A block blinks at frame i when its mean leaves the 3x3 neighbourhood range of BOTH frame i-1 and frame i+K (K = 1: a
one-frame flash; K = 2: a two-frame one) by more than --thresh code values, in the same direction (the camera moves
0-3 px a frame, so a moving edge stays inside the neighbourhood range; a patch that changes and comes back does not).
Clusters: 8-connected blinking blocks of one frame. Prints the busiest frames (time, bbox in screen px, size), a per-
second table, the zoom / fast-motion frames (whole-screen change) and writes a heat map PNG over the median frame.

Usage: vid-blink.py BLOCKS.npz [--thresh 4] [--k 1|2] [--roi x0,y0,x1,y1] [--heat out.png] [--json out.json] [--top 40]
"""
import argparse
import json
import sys

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view


def span(g):
    v = sliding_window_view(g, (3, 3))
    return v.min(axis=(2, 3)), v.max(axis=(2, 3))


def label(mask):
    """8-connected components; returns list of (count, y0, x0, y1, x1)."""
    h, w = mask.shape
    seen = np.zeros_like(mask)
    out = []
    ys, xs = np.nonzero(mask)
    for y, x in zip(ys, xs):
        if seen[y, x]:
            continue
        stack = [(y, x)]
        seen[y, x] = True
        c, y0, x0, y1, x1 = 0, y, x, y, x
        while stack:
            cy, cx = stack.pop()
            c += 1
            y0, x0, y1, x1 = min(y0, cy), min(x0, cx), max(y1, cy), max(x1, cx)
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
        out.append((c, y0, x0, y1, x1))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("npz")
    ap.add_argument("--thresh", type=float, default=4.0)
    ap.add_argument("--k", type=int, default=1)
    ap.add_argument("--roi")
    ap.add_argument("--heat")
    ap.add_argument("--json")
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--min-cluster", type=int, default=3)
    a = ap.parse_args()
    z = np.load(a.npz)
    blk = z["blocks"].astype(np.float32)
    pts = z["pts"]
    B = int(z["block"])
    n, hb, wb = blk.shape
    valid = np.ones((hb, wb), bool)
    # HUD: the left icon column, the top-right clock, the bottom hotbar, the bottom-right version string
    W, H = wb * B, hb * B
    def px2b(x0, y0, x1, y1):
        return slice(int(y0) // B, -(-int(y1) // B)), slice(int(x0) // B, -(-int(x1) // B))
    for r in [(0, 0, 0.035 * W, 0.32 * H), (0.955 * W, 0, W, 0.14 * H), (0.44 * W, 0.94 * H, 0.56 * W, H),
              (0.92 * W, 0.97 * H, W, H)]:
        valid[px2b(*r)] = False
    if a.roi:
        x0, y0, x1, y1 = (float(v) for v in a.roi.split(","))
        m = np.zeros_like(valid)
        m[px2b(x0, y0, x1, y1)] = True
        valid &= m
    K = a.k
    T = a.thresh
    heat = np.zeros((hb, wb), np.int32)
    per_frame = np.zeros(n, np.int32)
    whole = np.zeros(n, np.float32)
    clusters = []
    for i in range(1, n - K):
        whole[i] = float(np.mean(np.abs(blk[i] - blk[i - 1])))
        pmn, pmx = span(blk[i - 1])
        nmn, nmx = span(blk[i + K])
        cs = [blk[i + j][1:-1, 1:-1] for j in range(K)]
        up = np.ones_like(pmn, bool)
        dn = np.ones_like(pmn, bool)
        for c in cs:
            up &= c > np.maximum(pmx, nmx) + T
            dn &= c < np.minimum(pmn, nmn) - T
        m = np.zeros((hb, wb), bool)
        m[1:-1, 1:-1] = (up | dn)
        m &= valid
        cnt = int(m.sum())
        per_frame[i] = cnt
        if cnt:
            heat += m
            for (c, y0, x0, y1, x1) in label(m):
                if c >= a.min_cluster:
                    sub = blk[i][y0:y1 + 1, x0:x1 + 1]
                    prev = blk[i - 1][y0:y1 + 1, x0:x1 + 1]
                    mm = m[y0:y1 + 1, x0:x1 + 1]
                    clusters.append({"frame": i, "t": round(float(pts[i]), 3), "blocks": c,
                                     "bbox_px": [int(x0 * B), int(y0 * B), int((x1 + 1) * B), int((y1 + 1) * B)],
                                     "dir": "up" if float((sub - prev)[mm].mean()) > 0 else "down",
                                     "delta": round(float((sub - prev)[mm].mean()), 1),
                                     "level": round(float(prev[mm].mean()), 1)})
    secs = float(pts[-1] - pts[0])
    clusters.sort(key=lambda c: -c["blocks"])
    persec = {}
    for i in range(n):
        s = int(pts[i])
        persec.setdefault(s, [0, 0, 0.0])
        persec[s][0] += int(per_frame[i])
        persec[s][1] += 1
        persec[s][2] = max(persec[s][2], float(whole[i]))
    persec_c = {}
    for c in clusters:
        persec_c[int(c["t"])] = persec_c.get(int(c["t"]), 0) + 1
    print(f"frames={n} secs={secs:.1f} grid={wb}x{hb} block={B} thresh={T} k={K}")
    print(f"blinking blocks per frame: mean {per_frame.mean():.2f}  p99 {np.percentile(per_frame, 99):.0f}  max {per_frame.max()}")
    print(f"clusters >= {a.min_cluster} blocks: {len(clusters)}")
    print("sec  frames  blinks  clusters  max_whole_change")
    for s in sorted(persec):
        b, f, wmax = persec[s]
        print(f"{s:3d}  {f:5d}  {b:6d}  {persec_c.get(s, 0):6d}   {wmax:5.1f}")
    print("top clusters:")
    for c in clusters[: a.top]:
        print(f"  t={c['t']:7.3f} f={c['frame']:5d} blocks={c['blocks']:4d} bbox={c['bbox_px']} {c['dir']} d={c['delta']} lvl={c['level']}")
    hy, hx = np.unravel_index(np.argsort(-heat, axis=None)[:15], heat.shape)
    print("hot blocks (px x,y: count):", ", ".join(f"{x * B},{y * B}:{heat[y, x]}" for y, x in zip(hy, hx)))
    if a.heat:
        from PIL import Image
        med = np.median(blk[:: max(1, n // 200)], axis=0)
        base = np.clip(med * 2.0, 0, 255).astype(np.uint8)
        hm = np.clip(heat * (255.0 / max(1, np.percentile(heat[heat > 0], 99) if (heat > 0).any() else 1)), 0, 255).astype(np.uint8)
        rgb = np.stack([np.maximum(base, hm), base, base], -1)
        Image.fromarray(rgb).resize((wb * 4, hb * 4), Image.NEAREST).save(a.heat)
    if a.json:
        json.dump({"frames": n, "secs": secs, "thresh": T, "k": K, "per_frame_mean": float(per_frame.mean()),
                   "clusters": clusters[:400], "per_sec": {str(k): v for k, v in persec.items()}},
                  open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
