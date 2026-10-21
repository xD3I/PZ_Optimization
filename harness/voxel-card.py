#!/usr/bin/env python3
"""The New! card for voxel lighting (2026-10-18, docs/findings-voxel-lighting-2026-10-18.md) in the AVIFs' template
(harness/efficiency-anim.py: 630 square, the left strip with its label, Martian Mono, stock left of the white divider and
enhanced right of it, the New! cards' sweep; the layout of harness/ripples-card.py).

One segment from a pair of in-game devCapture runs (half-size whole screen, 30 fps): the night lot by the bench save at
1 AM, grid power and light switches on, clear, zoom 1, the camera panning east at 0.8 squares a second:

    --mode bench --flag start=8162,11502 --flag route=E:10 --flag speed=0.8 --flag hold=3 --flag time_of_day=1
    --flag weather=clear --flag zombies=off --flag zoom=1 --flag lights=on --flag torch=off --route-seconds 16 --prop overlay=false
    --prop devCapture=4,22,30,50,ram -Dpzopt.userOptionsFile=<empty file>

    stock     vxcard-lot2-stock  --prop enabled=false
    enhanced  vxcard-lot2-enh    --prop pixelLight=true --prop voxelLight=true

The stock frame shown is the best image match within 0.35 s of the enhanced frame's route time (props-reflect-card.py's
Matcher), so the sides stay in step through the small drift between two runs.

    python3 harness/voxel-card.py [--still png --t S] [--gif] [--out workshop-media/voxel-lighting.avif]
"""
import argparse
import importlib.util
import os
import shutil

import numpy as np
from PIL import Image

here = os.path.dirname(__file__)
spec = importlib.util.spec_from_file_location('anim', os.path.join(here, 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)
spec = importlib.util.spec_from_file_location('props', os.path.join(here, 'props-reflect-card.py'))
props = importlib.util.module_from_spec(spec)
spec.loader.exec_module(props)

LABEL = 'Voxel Lighting'
# (stock run, enhanced run, route second the segment starts, crop x, y, w, h in the capture (rows top-down))
SEGMENTS = [
    ('vxcard-lot2-stock', 'vxcard-lot2-enh', 1.0, (120, 0, 940, 1077)),  # the church, the brick house and the lot, scaled to the 550 x 630 pane
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--theme', default='light', choices=anim.THEMES)
    ap.add_argument('--crf', default='35')
    ap.add_argument('--gif', action='store_true', help='a review GIF (gifski) instead of the AVIF')
    ap.add_argument('--gif-fps', default='25')
    ap.add_argument('--still', help='one composed frame to this PNG (--t s in, the divider at a third)')
    ap.add_argument('--t', type=float, default=2.0)
    ap.add_argument('--box', help='x,y,w,h: try another crop (capture pixels)')
    ap.add_argument('--out')
    a = ap.parse_args()
    cw = sh = a.size
    s = cw / 630
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 if a.size <= 700 else 1.0
    sw = cw - strip
    base = np.asarray(anim.chrome(a.theme, LABEL, sh, cw, strip, round(42 * s), round(26 * k))).copy()
    ov = anim.visual_overlay(('STOCK', 'ENHANCED'), sw, sh, k)
    st, en, t0, box = SEGMENTS[0]
    if a.box:
        box = tuple(int(v) for v in a.box.split(','))
    caps = [props.load(st), props.load(en)]
    match = props.Matcher(*caps)
    if a.still:
        left, right = (props.frame(c, i, box, sw, sh) for c, i in zip(caps, match(t0 + a.t)))
        Image.fromarray(anim.compose(base, left, right, ov, sw // 3, strip)).save(a.still)
        print('still', a.still)
        return
    seq = anim.divider_positions()
    work = f'build/anim/voxel-lighting-{cw}'
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f'{work}/frames')
    for j, pos in enumerate(seq):
        left, right = (props.frame(c, n, box, sw, sh) for c, n in zip(caps, match(t0 + j / 60.0)))
        Image.fromarray(anim.compose(base, left, right, ov, round(sw * pos), strip)).save(f'{work}/frames/f{j:05d}.png', compress_level=1)
    print(f'== voxel-lighting: {len(seq)} frames {cw}x{sh}')
    out = a.out or ('workshop-media/template-voxel-lighting.gif' if a.gif else 'workshop-media/voxel-lighting.avif')
    anim.encode(a, work, base.shape, out)


if __name__ == '__main__':
    main()
