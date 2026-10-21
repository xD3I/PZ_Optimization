#!/usr/bin/env python3
"""The still under the Louisville New! card's race AVIF (efficiency-anim.py louisville-fps), in the ripples / props still's
layout (harness/ripples-changes.py: the AVIFs' strip and label, Martian Mono, grey stock / white optimized): every test
device on the Louisville horde bench, uncapped, Enhancements off, stock against round 10 with every optimization on.
Numbers as harness/slowframes/goal.py computes them (route window of pzopt-overlay.out: frames over their summed frame time,
p99 = the frame at 99 % of the sorted list), each side the mean of its runs.

    python3 harness/louisville-devices-card.py [--size 630] [--out build/anim/louisville-devices.png] [--print]
"""
import argparse
import importlib.util
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('anim', os.path.join(here, 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)
spec = importlib.util.spec_from_file_location('ent', os.path.join(here, 'entity-shadows-card.py'))
ent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ent)

HOME = os.path.expanduser('~')
MAIN = f'{HOME}/Documents/ZedProjects/PZ_Optimization/harness/runs'
R8 = f'{HOME}/pzopt-wt/integ-r8/harness/runs'
R10 = f'{HOME}/pzopt-wt/integ-r10/harness/runs'
# name, hardware line, stock runs, round 10 runs (2026-10-15)
DEVICES = [
    ('Desktop, 5120x2160', 'Ryzen 7 9800X3D, RTX 4090',
     [f'{R10}/lourace-stock1-20261015-215851'], [f'{R10}/lourace-opt1-20261015-220030']),
    ('Ayaneo Flip, 1080p', 'Ryzen AI 9 HX 370, Radeon 890M',
     [f'{MAIN}/flip-stock-d1-20261015-151542', f'{MAIN}/flip-stock-d2-20261015-151923'],
     [f'{R10}/flip-r10-now1-20261015-215402', f'{R10}/flip-r10-now2-20261015-215532']),
    # stock on the game's own OpenGL 2.1 context (--prop macGlCore=false: enabled=false keeps our 4.1 core context; the
    # maintainer's mac-n-stock1/2 ran on it, 29.2 / 28.3 fps, the same as these); round 10 now1 / now2 failed (ssh dropped,
    # then a stale options backup), now3 / now4 replace them
    ('MacBook Pro 14', 'M1 Pro, 1512x982',
     [f'{R10}/mac-stock21-1-20261015-221731', f'{R10}/mac-stock21-2-20261015-222046'],
     # round 10 on the quiet Mac (f10-mac, 23:06 / 23:08): now3 / now4 ran beside a Colima VM's x86 docker build (3.7 cores
     # of outside load, 38.5 / 43.5 fps); stock was not slowed by it (stock21 28.9 / 29.1 = the quiet 18:16 pair)
     [f'{HOME}/pzopt-wt/f10-mac/harness/runs/mac-f10-r10-b-20261015-230624',
      f'{HOME}/pzopt-wt/f10-mac/harness/runs/mac-f10-r10-c-20261015-230835']),
    ('Dell laptop, 1080p', 'Core i5-6300HQ, GTX 960M',
     [f'{R8}/dell-pf-stock1-20261015-175310', f'{R8}/dell-pf-stock2-20261015-180454'],
     [f'{R10}/dell-r10-now1-20261015-215500', f'{R10}/dell-r10-now2-20261015-220018']),
]
INTRO = ('Downtown Louisville with the population maxed (~2,000 zombies), the 25 s bench route, every device uncapped, '
         'Enhancements off')
FOOT = ('Route average of the presented frames (the in-game frame log), mean of two runs per side (desktop: the recorded '
        'race above, one run per side). Stock: the game as it ships, its highest frame cap (240) never reached. Optimized: '
        'every optimization on, the experimental ones included.')


def numbers(d):
    kv = dict(l.split('=', 1) for l in open(f'{d}/pzopt-bench.out').read().splitlines() if '=' in l)
    a, b = int(kv['route_start_epoch_ms']), int(kv['route_end_epoch_ms'])
    lines = open(f'{d}/pzopt-overlay.out').read().splitlines()
    h = lines[0].split(',')
    it, ie = h.index('frametime'), h.index('epoch_ms')
    ft = []
    for l in lines[1:]:
        p = l.split(',')
        try:
            e, f = int(p[ie]), float(p[it])
        except (ValueError, IndexError):
            continue
        if a <= e <= b:
            ft.append(f)
    ft.sort()
    return 1000.0 * len(ft) / sum(ft), ft[min(len(ft) - 1, int(0.99 * len(ft)))]


def side(runs):
    v = np.array([numbers(d) for d in runs])
    return dict(fps=float(v[:, 0].mean()), p99=float(v[:, 1].mean()), runs=[os.path.basename(d) for d in runs],
                each=[f'{x:.1f}' for x in v[:, 0]])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--out', default='build/anim/louisville-devices.png')
    ap.add_argument('--label', default='Louisville Horde')
    ap.add_argument('--theme', default='light', choices=anim.THEMES)
    ap.add_argument('--print', action='store_true', help='print the table only')
    a = ap.parse_args()
    devs = DEVICES
    rows = []
    for n, hw, st, op in devs:
        s, o = side(st), (side(op) if op else None)
        rows.append((n, hw, s, o))
        print(f'{n:15} stock {s["fps"]:6.1f} fps p99 {s["p99"]:6.1f} ms ({", ".join(s["each"])})  '
              + (f'optimized {o["fps"]:6.1f} fps p99 {o["p99"]:6.1f} ms ({", ".join(o["each"])})  {o["fps"] / s["fps"]:.2f}x' if o else 'optimized -'))
    if a.print:
        return
    cw = a.size
    s = cw / 630
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 if a.size <= 700 else 1.0
    pad = round(26 * k)
    sw = cw - strip
    F = lambda px: ImageFont.truetype(anim.FONT, max(8, round(px * s)))
    cap, name, hwf, big, unit, small, ratio, foot = F(10.5), F(14), F(9.6), F(22), F(11), F(9.6), F(24), F(9.6)
    probe = ImageDraw.Draw(Image.new('RGB', (10, 10)))
    intro = ent.wrap(probe, INTRO, cap, sw - 2 * pad)
    foot_lines = ent.wrap(probe, FOOT, foot, sw - 2 * pad)
    row_h, lh, flh = round(62 * s), round(15 * s), round(13.5 * s)
    fixed = pad + lh * len(intro) + round(10 * s) + round(20 * s) + round(24 * s) + flh * len(foot_lines) + pad
    sh = max(cw, fixed + row_h * len(rows))   # square like the other cards (the strip label needs the height) unless rows need more
    sh += sh % 2
    row_h = min(round(96 * s), (sh - fixed) // len(rows))
    img = anim.chrome(a.theme, a.label, sh, cw, strip, round(42 * s), pad)
    scene = Image.new('RGB', (sw, sh), (11, 11, 14))
    d = ImageDraw.Draw(scene)
    x0, x1 = pad, sw - pad
    c_ratio = x1
    c_now = x1 - round(84 * s)
    c_stock = c_now - round(96 * s)
    y = pad
    for line in intro:
        d.text((x0, y), line, font=cap, fill=anim.CAPTION, anchor='lt')
        y += lh
    y += round(10 * s)
    d.text((x0, y), 'DEVICE', font=cap, fill=anim.CAPTION, anchor='lt')
    d.text((c_stock, y), 'STOCK', font=cap, fill=anim.CAPTION, anchor='rt')
    d.text((c_now, y), 'OPTIMIZED', font=cap, fill=anim.CAPTION, anchor='rt')
    d.text((c_ratio, y), 'FASTER', font=cap, fill=anim.CAPTION, anchor='rt')
    y += round(20 * s)
    d.line((x0, y, x1, y), fill=(60, 60, 66), width=1)

    def fps_cell(x, v, col):
        num = f'{v["fps"]:.0f}'
        gap = round(4 * s)
        wu = d.textlength('fps', font=unit)
        d.text((x - wu - gap, base), num, font=big, fill=col, anchor='rs')
        d.text((x, base), 'fps', font=unit, fill=col, anchor='rs')
        d.text((x, base + round(16 * s)), f'p99 {v["p99"]:.0f} ms' if v['p99'] >= 20 else f'p99 {v["p99"]:.1f} ms',
               font=small, fill=col, anchor='rs')
    for i, (n, hw, st, op) in enumerate(rows):
        base = y + row_h // 2 + round(1 * s)
        d.text((x0, base), n, font=name, fill=anim.AFTER, anchor='ls')
        d.text((x0, base + round(16 * s)), hw, font=hwf, fill=anim.BEFORE, anchor='ls')
        fps_cell(c_stock, st, anim.BEFORE)
        if op:
            fps_cell(c_now, op, anim.AFTER)
            d.text((c_ratio, base), f'{op["fps"] / st["fps"]:.1f}x', font=ratio, fill=anim.AFTER, anchor='rs')
        else:
            d.text((c_now, base), 'pending', font=small, fill=anim.BEFORE, anchor='rs')
        y += row_h
        d.line((x0, y, x1, y), fill=(40, 40, 46), width=1)
    fy = sh - pad - flh * len(foot_lines)
    for line in foot_lines:
        d.text((x0, fy), line, font=foot, fill=(120, 120, 126), anchor='lt')
        fy += flh
    img.paste(scene, (strip, 0))
    os.makedirs(os.path.dirname(a.out) or '.', exist_ok=True)
    img.save(a.out)
    print('->', a.out, img.size)


if __name__ == '__main__':
    main()
