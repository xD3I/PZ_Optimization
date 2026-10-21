#!/usr/bin/env python3
"""The New! card for the built-in benchmark and the PZ Optimization menu (2026-10-18, docs/findings-builtin-benchmark-2026-10-18.md)
in the AVIFs' template (harness/efficiency-anim.py: 630 square, the left strip with its label, Martian Mono). Real in-game
screenshots only, six segments with a short cross-fade between them, each captioned on a dark band under the picture:

    menu      the main menu's one PZ OPTIMIZATION item
    settings  the PZ Optimization screen on an Optimizations category (sidebar, breadcrumbs, list, detail, the controller
              hint bar), panned left to right
    setup     Benchmark > Setup: the five scenes, the stock comparison and the pass options, panned
    pass      a horde shooting pass on the desktop: the HUD line at 1:1 panned, then out to the horde
    report    Benchmark > Report of the desktop's A/B run 20261018-035343 (every scene stock and optimized, the score, the
              charts, what limited the frames), panned
    compare   Benchmark > Compare: what the two runs' settings, game settings, mods and PC have in common (the
              section column only; its scores are those of two other runs)

The menu screens are the desktop's 1280x720 large-font tour of the release build (benchmark-run.sh ui:read --screen
1280x720 --font 3, run bench-card-720), whose report page is the newest A/B run, 20261018-035343; the pass is the
desktop's end-of-pass screenshot (bench-horde-ready, 5120x2160). A 1280x720 shot is shown as a 698x720 window (the
picture's aspect above the band) at 0.79x, so the large font reads at ~14 px.

    python3 harness/benchmark-menu-card.py [--still png --t S] [--gif] [--out workshop-media/benchmark-menu.avif]
"""
import argparse
import importlib.util
import os
import shutil

import numpy as np
from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(__file__)
spec = importlib.util.spec_from_file_location('anim', os.path.join(here, 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)

LABEL = 'Benchmark and Menu'
FPS = 60
FADE = 0.3   # s of cross-fade between two segments

TOUR = 'bench-card-720-20261018-223032/shots/pzopt-bench-ui-read-'
SHOTS = {
    'menu': TOUR + '01-menu.png',
    'settings': TOUR + '04-pzmenu-perf-smooth.png',
    'setup': TOUR + '16-panel.png',
    'pass': 'bench-horde-ready-20261018-154604/results/shot-00.png',
    'report': TOUR + '26-report.png',
    'compare': TOUR + '20-runs.png',
}
W720 = 698   # 720 * 550 / 567: a 1280x720 shot's window at the picture's aspect
PAN = [(0.0, (0, 0, W720, 720)), (0.8, (0, 0, W720, 720)), (3.2, (1280 - W720, 0, W720, 720)), (4.2, (1280 - W720, 0, W720, 720))]
# (name, caption, keyframes (s, box x y w h)); a box between two keyframes follows the cards' ease
SEGMENTS = [
    ('menu', 'PZ OPTIMIZATION', [(0.0, (0, 0, W720, 720)), (2.6, (0, 0, W720, 720))]),
    ('settings', 'EVERY SETTING', PAN),
    ('setup', 'FIVE RUNS', PAN),
    ('pass', 'A PASS', [(0.0, (2010, 0, 550, 567)), (0.5, (2010, 0, 550, 567)), (2.5, (2560, 0, 550, 567)),
                        (2.9, (2560, 0, 550, 567)), (4.1, (2030, 480, 1100, 1134)), (5.6, (2030, 480, 1100, 1134))]),
    ('report', 'THE REPORT', PAN),
    ('compare', 'COMPARE RUNS', [(0.0, (360, 296, 400, 412)), (2.8, (360, 296, 400, 412))]),
]


def at(keys, t):
    for (t0, b0), (t1, b1) in zip(keys, keys[1:]):
        if t <= t1:
            u = anim.ease(0 if t1 == t0 else (t - t0) / (t1 - t0))
            return tuple(round(p + (q - p) * u) for p, q in zip(b0, b1))
    return keys[-1][1]


def view(img, box, w, h):
    x, y, bw, bh = box
    return np.asarray(img.crop((x, y, x + bw, y + bh)).resize((w, h), Image.LANCZOS))


def bandh(k):
    return round(84 * k)


def caption(text, sw, sh, k):
    """The dark band under the picture with the segment's caption bottom left."""
    img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle((0, sh - bandh(k), sw, sh), fill=(11, 11, 14, 255))
    pad = round(26 * k)
    d.text((pad, sh - pad), text, font=ImageFont.truetype(anim.FONT, max(11, round(40 * k))), fill=anim.AFTER, anchor='ls')
    return [(np.asarray(img), None)]


def frames(base, sw, sh, k, strip):
    """Every frame of the card, in order (numpy RGB at the card's size), streamed: the last FADE s of a segment are held
    back and blended into the first FADE s of the next one."""
    imgs = {n: Image.open(os.path.join(here, 'runs', p)).convert('RGB') for n, p in SHOTS.items()}
    vh = sh - bandh(k)
    n = round(FADE * FPS)
    pending = []
    for name, word, keys in SEGMENTS:
        ovs = caption(word, sw, sh, k)
        old, pending = pending, []
        for i in range(round(keys[-1][0] * FPS)):
            pic = np.zeros((sh, sw, 3), np.uint8)
            pic[:vh] = view(imgs[name], at(keys, i / FPS), sw, vh)
            f = anim.compose(base, pic, pic, ovs, 0, strip)
            if i < len(old):
                u = anim.ease((i + 1) / (len(old) + 1))
                f = (old[i] * (1 - u) + f * u).astype(np.uint8)
            pending.append(f)
            if len(pending) > n:
                yield pending.pop(0)
    yield from pending


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--theme', default='light', choices=anim.THEMES)
    ap.add_argument('--crf', default='38')
    ap.add_argument('--gif', action='store_true', help='a review GIF (gifski) instead of the AVIF')
    ap.add_argument('--gif-fps', default='25')
    ap.add_argument('--still', help='one frame of the card (at --t s) to this PNG')
    ap.add_argument('--t', type=float, default=2.0)
    ap.add_argument('--out')
    a = ap.parse_args()
    cw = sh = a.size
    s = cw / 630
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 if a.size <= 700 else 1.0
    sw = cw - strip
    base = np.asarray(anim.chrome(a.theme, LABEL, sh, cw, strip, round(42 * s), round(26 * k))).copy()
    if a.still:
        for i, f in enumerate(frames(base, sw, sh, k, strip)):
            if i == round(a.t * FPS):
                Image.fromarray(f).save(a.still)
                print('still', a.still)
                return
        raise SystemExit('--t past the end')
    work = f'build/anim/benchmark-menu-{cw}'
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f'{work}/frames')
    i = 0
    for f in frames(base, sw, sh, k, strip):
        Image.fromarray(f).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
        i += 1
    print(f'== benchmark-menu: {i} frames, {i / FPS:.1f} s, {cw}x{sh}')
    out = a.out or ('workshop-media/template-benchmark-menu.gif' if a.gif else 'workshop-media/benchmark-menu.avif')
    os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
    anim.encode(a, work, base.shape, out)


if __name__ == '__main__':
    main()
