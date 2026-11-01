#!/usr/bin/env python3
"""Workshop item carousel slides (2026-10-11): the stills of the item's own image gallery ("Add/edit images & videos" on the
Workshop page) in the AVIF comparison template of harness/efficiency-anim.py, at the carousel's 16:9.

The template's look, scaled from the 630 square by the height: the black strip on the left with the label in Martian Mono,
rotated, reading from the top; one view of the scene, stock left of a white divider and enhanced right of it (a still: the
divider stands where --split puts it); the stock numbers grey bottom left, the other side's white bottom right. Captions
(maintainer, 2026-10-11): STOCK / OPTIMIZED on the slides of the optimizations alone (--words STOCK:OPTIMIZED, the default),
STOCK / ENHANCED on optimizations + enhancements. Every comparison slide shows the exact same crop in both halves (maintainer,
2026-10-11), side by side, stock left, enhanced right, a fixed white separator between them. The two pictures are aligned by phase
correlation first (the offset is printed; shot-at pairs of one camera come out 0,0). Optional: a small context line top
right (machine, scene, cap).

    harness/carousel-slide.py --a <stock png> --b <enhanced png> --label "120 km/h Drive" \\
        [--runs <stock run label>:<enhanced run label> | --numbers 173:552 --second "p99 26.5 ms:p99 7.9 ms"] \\
        [--crop x:y:w:h] [--split 0.5] [--note "RTX 4090 · 5120x2160 · uncapped"] --out docs/workshop/carousel/NN-<slug>.jpg
    harness/carousel-slide.py --single <png> --label Settings [--crop x:y:w:h] --out ...       (one picture, no divider / numbers)

--a / --b take a png or a run label (its shot-game.png, `--shot 2` for shot2-game.png). --runs reads each run's route-window
fps and p99 (pzopt-overlay.out, as efficiency-anim.py). The crop (source pixels, same for both pictures) is fitted to the scene
area by covering it round the crop's centre. Writes a JPG (quality 92) or PNG by the extension.
"""
import argparse
import importlib.util
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

_spec = importlib.util.spec_from_file_location('effanim', os.path.join(os.path.dirname(__file__), 'efficiency-anim.py'))
EA = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(EA)


def picture(src, shot):
    if os.path.isfile(src):
        return Image.open(src).convert('RGB')
    d = EA.run_dir(src)
    return Image.open(f'{d}/shot{"2" if shot == 2 else ""}-game.png').convert('RGB')


def fit(img, crop, sw, sh):
    """The crop (x:y:w:h, empty = the whole picture) covering sw x sh round its centre."""
    x, y, w, h = (int(v) for v in crop.split(':')) if crop else (0, 0, img.width, img.height)
    if w / h > sw / sh:   # wider than the scene: keep the height
        cw = round(h * sw / sh)
        x, w = x + (w - cw) // 2, cw
    else:
        ch = round(w * sh / sw)
        y, h = y + (h - ch) // 2, ch
    return np.asarray(img.crop((x, y, x + w, y + h)).resize((sw, sh), Image.LANCZOS))


def align(a, b):
    """b shifted onto a by the phase correlation of their luma (whole pixels); prints the shift."""
    ga = np.asarray(a.convert('L').reduce(2), np.float32)
    gb = np.asarray(b.convert('L').reduce(2), np.float32)
    R = np.fft.fft2(ga - ga.mean()) * np.conj(np.fft.fft2(gb - gb.mean()))
    r = np.fft.ifft2(R / (np.abs(R) + 1e-9)).real
    dy, dx = np.unravel_index(r.argmax(), r.shape)
    dy -= r.shape[0] if dy > r.shape[0] // 2 else 0
    dx -= r.shape[1] if dx > r.shape[1] // 2 else 0
    dy, dx = int(dy) * 2, int(dx) * 2
    print(f'align: b shifted by {dx},{dy} px (peak {r.max():.2f})')
    if dx or dy:
        b = b.transform(b.size, Image.AFFINE, (1, 0, -dx, 0, 1, -dy))
    return b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--a', help='stock picture: png or run label')
    ap.add_argument('--b', help='enhanced picture: png or run label')
    ap.add_argument('--single', help='one picture (png or run label): no divider, no numbers')
    ap.add_argument('--shot', type=int, default=1, choices=(1, 2), help='with run labels: shot-game.png (1) or shot2 (2)')
    ap.add_argument('--label', required=True)
    ap.add_argument('--runs', help='stock:enhanced run labels for the numbers (route fps + p99)')
    ap.add_argument('--numbers', help='stock:enhanced fps')
    ap.add_argument('--second', help='stock:enhanced second lines (default with --runs: the p99)')
    ap.add_argument('--words', default='STOCK:OPTIMIZED', help='STOCK:ENHANCED on optimizations + enhancements slides')
    ap.add_argument('--one-view', dest='same_crop', action='store_false',
                    help='one view split at --split instead of the same crop side by side (the default, every comparison slide)')
    ap.add_argument('--crop', default='')
    ap.add_argument('--contain', action='store_true', help='with --single: the whole crop inside the scene area, dark ground round it')
    ap.add_argument('--split', type=float, default=0.5, help='with --one-view: share of the scene width showing stock')
    ap.add_argument('--note', help='small context line, top right of the scene')
    ap.add_argument('--size', default='1920x1080')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()

    cw, ch = (int(v) for v in a.size.split('x'))
    s = ch / 630                       # the 630 template scaled by the height
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 * s
    sw = cw - strip
    base = np.asarray(EA.chrome('light', a.label, ch, cw, strip, round(42 * s), round(26 * k))).copy()

    if a.single:
        img = picture(a.single, a.shot)
        frame = base.copy()
        if a.contain:   # the whole crop inside the scene area on a dark ground (the options window)
            if a.crop:
                x, y, w, h = (int(v) for v in a.crop.split(':'))
                img = img.crop((x, y, x + w, y + h))
            pad = round(26 * k)
            sc = min((sw - 2 * pad) / img.width, (ch - 2 * pad) / img.height)
            img = img.resize((round(img.width * sc), round(img.height * sc)), Image.LANCZOS)
            frame[:, strip:] = (16, 16, 18)
            ox, oy = strip + (sw - img.width) // 2, (ch - img.height) // 2
            frame[oy:oy + img.height, ox:ox + img.width] = np.asarray(img)
        else:
            frame[:, strip:] = fit(img, a.crop, sw, ch)
        ov, x = [], None
    else:
        pa = picture(a.a, a.shot)
        pb = align(pa, picture(a.b, a.shot))
        if a.same_crop:   # each half the same crop: stock fills the left half, enhanced the right
            half = sw // 2
            left = np.zeros((ch, sw, 3), np.uint8)
            right = np.zeros((ch, sw, 3), np.uint8)
            left[:, :half] = fit(pa, a.crop, half, ch)
            right[:, half:] = fit(pb, a.crop, sw - half, ch)
        else:
            left = fit(pa, a.crop, sw, ch)
            right = fit(pb, a.crop, sw, ch)
        n = second = None
        if a.runs:
            ra, rb = (EA.route_numbers(EA.run_dir(l)) for l in a.runs.split(':'))
            n = {'a': ra, 'b': rb}
            second = (f"p99 {ra['p99']:.1f} ms", f"p99 {rb['p99']:.1f} ms")
            print(f"numbers: stock {ra['fps']:.1f} fps p99 {ra['p99']:.2f} | enhanced {rb['fps']:.1f} fps p99 {rb['p99']:.2f}")
        if a.numbers:
            fa, fb = (float(v) for v in a.numbers.split(':'))
            n = {'a': {'fps': fa}, 'b': {'fps': fb}}
        if a.second:
            second = tuple(a.second.split(':', 1))
        ov = EA.numbers_overlay(n, second, sw, ch, k, tuple(a.words.split(':'))) if n else []
        x = sw // 2 if a.same_crop else round(sw * a.split)
        frame = EA.compose(base, left, right, ov, x, strip)

    if a.note:
        img = Image.fromarray(frame)
        d = ImageDraw.Draw(img)
        f = ImageFont.truetype(EA.FONT, round(15 * k))
        pad = round(26 * k)
        tx, ty = cw - pad, pad
        bx = d.textbbox((tx, ty), a.note, font=f, anchor='ra')
        g = Image.new('L', img.size, 0)
        ImageDraw.Draw(g).rectangle((bx[0] - pad // 2, bx[1] - pad // 3, bx[2] + pad // 2, bx[3] + pad // 3), fill=150)
        img.paste((0, 0, 0), mask=g)
        d.text((tx, ty), a.note, font=f, fill=EA.CAPTION, anchor='ra')
        frame = np.asarray(img)

    os.makedirs(os.path.dirname(a.out) or '.', exist_ok=True)
    out = Image.fromarray(frame)
    if a.out.lower().endswith(('.jpg', '.jpeg')):
        out.save(a.out, quality=92, subsampling=0, optimize=True)
    else:
        out.save(a.out, optimize=True)
    print('->', a.out, os.path.getsize(a.out))


if __name__ == '__main__':
    main()
