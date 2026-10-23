#!/usr/bin/env python3
"""Is a menu preview clip's effect clearly visible? Code measures the pair the way the menu shows it, Jev judges.

  harness/clip-judge.py <off run> <on run> --crop x:y:w:h [--after S] [--dur 4] --feature "<what the setting does>"
                        [--expect brightness|shadow|detail|motion|colour|reflection|shape] [--json f] [--png dir] [--no-jev]
  harness/clip-judge.py <off.gif> <on.gif> --feature "..."                  # the encoded GIFs themselves
  harness/clip-judge.py <run> - --alt '<console regex: (period ms) .. (start epoch ms)>' [--phases 0,1] --crop ...
                                                                             # one run whose setting alternates (still scene)

Run dirs: two devCapture runs of the same route (pzopt.FrameCapture, <run>/capture), only the setting differs. Each GIF frame
is rebuilt as menu-gifs.py's kind='capture' does: route time `after` + j/24 s, the off frame matched to the on frame within
0.35 s (props-reflect-card.py's Matcher), cropped (capture pixels) and scaled to 512 px wide like the GIF. GIFs are read as
they are (frame j against frame j).

Jev is text-only, so every property is a number (per frame pair, then mean / p10 / p90 over the clip):
  * differing pixels: share of pixels whose largest channel differs by more than 16 / 32 / 64 of 255
  * mean absolute luma difference, the luma of each side (a brighter / darker picture)
  * SSIM of the luma (8x8 blocks): 1 = the same picture
  * where the difference sits: share per cell of a 3 x 3 grid (one hot cell = a local effect, e.g. a mirror)
  * motion: each side's mean frame-to-frame change (sway, ripples, wakes add motion the other side lacks)
  * detail: mean Laplacian magnitude of each side (sprite filtering, relief, upscalers change sharpness)
  * alignment: correlation of the two sides' gradient magnitudes (low = the two runs show different views, the clip lies)
Prints the numbers, Jev's answers, and one line `visible=yes|no confidence=<0..1> kind=<..>`; exit 0 when visible.
"""
import argparse
import importlib.util
import json
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

W, FPS = 512, 24


def kv(p):
    return dict(l.strip().split('=', 1) for l in open(p) if '=' in l)


def load_capture(d):
    lines = open(os.path.join(d, 'capture/index.txt')).read().split('\n')
    head = dict(x.split('=') for x in lines[0].split())
    w, h = int(head['w']), int(head['h'])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    r0 = int(kv(os.path.join(d, 'pzopt-schedule.out'))['route_start_epoch_ms'])
    raw = np.memmap(os.path.join(d, 'capture/frames.rgba'), np.uint8, mode='r')
    n = min(len(stamps), raw.size // (w * h * 4))
    return raw[:n * w * h * 4].reshape(n, h, w, 4), (stamps[:n] - r0) / 1000.0


def frames_from_runs(a, b, crop, after, dur, window=None):
    spec = importlib.util.spec_from_file_location('props', HERE / 'props-reflect-card.py')
    props = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(props)
    if window:
        props.Matcher.WINDOW = window  # drives drift more than 0.35 s between two runs
    ca, cb = load_capture(a), load_capture(b)
    match = props.Matcher(ca, cb)
    cx, cy, cw, ch = (int(v) for v in crop.split(':'))
    sh = max(2, round(ch * W / cw / 2) * 2)
    fa, fb = [], []
    for j in range(round(dur * FPS)):
        ia, ib = match(after + j / FPS)
        for cap, i, out in ((ca, ia, fa), (cb, ib, fb)):
            f = np.ascontiguousarray(cap[0][i][::-1][cy:cy + ch, cx:cx + cw, :3])
            im = Image.fromarray(f)
            out.append(np.asarray(im if im.size == (W, sh) else im.resize((W, sh), Image.LANCZOS)))
    return fa, fb


def alt_phases(run, regex, guard=0.15):
    """A run whose setting alternates (dev rigs devCarGlassAlternate, devOutlineAlternate, ...): the console line that
    `regex` matches gives the period (group 1, ms) and the start (group 2, epoch ms); phase n is on when n is even
    ('on first'). Returns (frames, times in route s, phase index per frame, keep mask) of the run's capture; frames within
    `guard` s of a switch are dropped (the switch lands on the render thread a frame or two late)."""
    import re
    rx = re.compile(regex)
    for l in open(os.path.join(run, 'console.txt'), errors='replace'):
        m = rx.search(l)
        if m:
            period, t0 = int(m.group(1)), int(m.group(2))
            break
    else:
        raise SystemExit(f'{run}: no line matches {regex!r}')
    frames, t = load_capture(run)
    r0 = int(kv(os.path.join(run, 'pzopt-schedule.out'))['route_start_epoch_ms'])
    ep = t * 1000.0 + r0
    ph = np.floor((ep - t0) / period).astype(int)
    pos = (ep - t0) / period - ph
    keep = (ph >= 0) & (pos * period / 1000 > guard) & ((1 - pos) * period / 1000 > guard)
    return frames, t, ph, keep


def frames_from_alt(run, regex, crop, phase_on, phase_off, dur, fps=FPS):
    """Both sides from one alternating run: side 'on' = phase `phase_on`, side 'off' = phase `phase_off` (consecutive
    phases of a still scene); GIF frame j of a side is the kept frame nearest to that phase's first kept time + j/fps."""
    frames, t, ph, keep = alt_phases(run, regex)
    cx, cy, cw, ch = (int(v) for v in crop.split(':'))
    sh = max(2, round(ch * W / cw / 2) * 2)
    out = []
    for p in (phase_off, phase_on):
        idx = np.flatnonzero(keep & (ph == p))
        if not len(idx):
            raise SystemExit(f'{run}: no captured frames in phase {p}')
        side = []
        for j in range(round(dur * fps)):
            i = idx[int(np.argmin(np.abs(t[idx] - (t[idx[0]] + j / fps))))]
            im = Image.fromarray(np.ascontiguousarray(frames[i][::-1][cy:cy + ch, cx:cx + cw, :3]))
            side.append(np.asarray(im if im.size == (W, sh) else im.resize((W, sh), Image.LANCZOS)))
        out.append(side)
    return out[0], out[1]


def frames_from_gif(p):
    im = Image.open(p)
    out = []
    try:
        while True:
            out.append(np.asarray(im.convert('RGB')))
            im.seek(im.tell() + 1)
    except EOFError:
        pass
    return out


def luma(f):
    f = f.astype(np.float32)
    return 0.2126 * f[..., 0] + 0.7152 * f[..., 1] + 0.0722 * f[..., 2]


def ssim(x, y, b=8):
    h, w = (x.shape[0] // b) * b, (x.shape[1] // b) * b
    x = x[:h, :w].reshape(h // b, b, w // b, b).transpose(0, 2, 1, 3).reshape(-1, b * b)
    y = y[:h, :w].reshape(h // b, b, w // b, b).transpose(0, 2, 1, 3).reshape(-1, b * b)
    mx, my = x.mean(1), y.mean(1)
    vx, vy = x.var(1), y.var(1)
    cxy = ((x - mx[:, None]) * (y - my[:, None])).mean(1)
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    return float((((2 * mx * my + c1) * (2 * cxy + c2)) / ((mx ** 2 + my ** 2 + c1) * (vx + vy + c2))).mean())


def lap(y):
    return float(np.abs(4 * y[1:-1, 1:-1] - y[:-2, 1:-1] - y[2:, 1:-1] - y[1:-1, :-2] - y[1:-1, 2:]).mean())


def grad(y):
    gx = np.zeros_like(y); gy = np.zeros_like(y)
    gx[:, 1:-1] = y[:, 2:] - y[:, :-2]; gy[1:-1] = y[2:] - y[:-2]
    return np.hypot(gx, gy)


def stats(v):
    v = np.asarray(v, dtype=np.float64)
    return {'mean': round(float(v.mean()), 4), 'p10': round(float(np.percentile(v, 10)), 4),
            'p90': round(float(np.percentile(v, 90)), 4)}


def measure(fa, fb):
    n = min(len(fa), len(fb))
    d16, d32, d64, dl, ss, la, lb, ka, kb, al, rgb, sa, sb = ([] for _ in range(13))
    grid = np.zeros((3, 3))
    for i in range(n):
        a, b = fa[i].astype(np.int16), fb[i].astype(np.int16)
        d = np.abs(a - b).max(axis=2)
        d16.append((d > 16).mean() * 100); d32.append((d > 32).mean() * 100); d64.append((d > 64).mean() * 100)
        ya, yb = luma(fa[i]), luma(fb[i])
        dl.append(float(np.abs(ya - yb).mean())); la.append(float(ya.mean())); lb.append(float(yb.mean()))
        ss.append(ssim(ya, yb)); ka.append(lap(ya)); kb.append(lap(yb))
        rgb.append(float(np.abs(a - b).mean()))
        for f, out in ((fa[i], sa), (fb[i], sb)):
            f = f.astype(np.float32)
            out.append(float((f.max(axis=2) - f.min(axis=2)).mean()))
        ga, gb = grad(ya).ravel(), grad(yb).ravel()
        al.append(float(np.corrcoef(ga, gb)[0, 1]) if ga.std() > 0 and gb.std() > 0 else 0.0)
        h, w = d.shape
        for r in range(3):
            for c in range(3):
                grid[r, c] += (d[r * h // 3:(r + 1) * h // 3, c * w // 3:(c + 1) * w // 3] > 32).sum()
    ma = [float(np.abs(luma(fa[i + 1]) - luma(fa[i])).mean()) for i in range(n - 1)]
    mb = [float(np.abs(luma(fb[i + 1]) - luma(fb[i])).mean()) for i in range(n - 1)]
    tot = grid.sum()
    names = [['top-left', 'top', 'top-right'], ['left', 'centre', 'right'], ['bottom-left', 'bottom', 'bottom-right']]
    where = {names[r][c]: round(float(grid[r, c] / tot * 100), 1) if tot else 0.0 for r in range(3) for c in range(3)}
    return {
        'frames': n, 'size': f'{fa[0].shape[1]}x{fa[0].shape[0]}',
        'differing_px_pct_over16': stats(d16), 'differing_px_pct_over32': stats(d32), 'differing_px_pct_over64': stats(d64),
        'frames_with_over_1pct_differing_over32': round(float(np.mean(np.array(d32) > 1.0)) * 100, 1),
        'mean_abs_luma_diff_0_255': stats(dl), 'mean_abs_rgb_diff_0_255': stats(rgb),
        'saturation_off_0_255': stats(sa), 'saturation_on_0_255': stats(sb), 'luma_off': stats(la), 'luma_on': stats(lb), 'ssim_luma': stats(ss),
        'where_differing_over32_pct_by_cell': where,
        'motion_frame_to_frame_luma_off': stats(ma or [0]), 'motion_frame_to_frame_luma_on': stats(mb or [0]),
        'detail_laplacian_off': stats(ka), 'detail_laplacian_on': stats(kb),
        'alignment_gradient_correlation': stats(al),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('off'); ap.add_argument('on')
    ap.add_argument('--crop'); ap.add_argument('--after', type=float, default=1.0); ap.add_argument('--dur', type=float, default=4.0)
    ap.add_argument('--alt', help='one alternating run instead of two: the console regex (period ms, start epoch ms)')
    ap.add_argument('--phases', default='0,1', help='with --alt: on phase, off phase (even = on)')
    ap.add_argument('--window', type=float, help='frame match window in s (default 0.35, menu-gifs.py match_window)')
    ap.add_argument('--feature', required=True, help='what the setting changes in the picture')
    ap.add_argument('--expect', default='', help='the kind of change: brightness, shadow, detail, motion, colour, reflection, shape')
    ap.add_argument('--json'); ap.add_argument('--png', help='write frame 0 / middle of both sides here')
    ap.add_argument('--no-jev', action='store_true')
    a = ap.parse_args()
    if a.alt:
        pon, poff = (int(v) for v in a.phases.split(','))
        fa, fb = frames_from_alt(a.off, a.alt, a.crop, pon, poff, a.dur)
    elif a.off.endswith('.gif'):
        fa, fb = frames_from_gif(a.off), frames_from_gif(a.on)
    else:
        if not a.crop:
            sys.exit('--crop is needed for run dirs')
        fa, fb = frames_from_runs(a.off, a.on, a.crop, a.after, a.dur, a.window)
    if a.png:
        os.makedirs(a.png, exist_ok=True)
        for k in (0, len(fa) // 2):
            Image.fromarray(np.concatenate([fa[k], fb[k]], axis=0)).save(os.path.join(a.png, f'pair-{k:03d}.png'))
    m = measure(fa, fb)
    print(json.dumps(m, indent=1))
    out = {'metrics': m}
    verdict = None
    if not a.no_jev:
        from typesafe_client import ask, choice, noul, fmt
        state = {
            'what': 'Two looping preview clips shown side by side in a game settings menu, each 512 px wide, 4 s: the left one '
                    'with a graphics setting off, the right one with it on, same scene, same moment, frames lined up.',
            'setting': a.feature, 'expected_kind_of_change': a.expect or 'unspecified', 'metrics': m,
            'reference_points': {
                'noise': 'two runs of the same setting differ by under 0.5 % of pixels over 32 and SSIM above 0.97 when lined up; '
                         'sway / ripples / moving characters raise that a little',
                'clearly_visible': 'a person sees a difference at a glance when several percent of the picture differs by over 32 '
                                   '(SSIM well under 0.9), or a local effect covers a cell of the grid, or the luma means differ by '
                                   'over ~8, or the saturation / mean RGB difference over ~6 (a colour shift), or one side moves clearly more than the other (motion ratio over ~1.5)',
                'subtle': 'under ~1 % of pixels over 32 spread thinly, SSIM above 0.95 and equal luma means: a person comparing '
                          'the two small clips will hardly tell them apart',
                'alignment': 'gradient correlation under ~0.5 means the two clips show different views (run drift): the clip '
                             'would mislead, whatever the other numbers say'},
        }
        qs = {
            'visible': noul('Would a person who watches the two small clips side by side clearly see the change `setting` '
                            'describes, judging only from `metrics` against `reference_points`?'),
            'kind': choice('What does the difference between the two clips mostly look like?',
                           {'clear': 'a clear, steady difference in the picture of the expected kind',
                            'local': 'a clear difference confined to one area of the picture',
                            'motion': 'one side moves (sways, ripples) where the other is still',
                            'subtle': 'a difference exists but is small or thin: easy to miss at this size',
                            'none': 'no meaningful difference beyond noise',
                            'misaligned': 'the two clips do not show the same view, so the difference is not the setting'}),
        }
        log = []
        ans = ask(state, qs, log=log)
        print('jev:\n  ' + fmt(ans).replace('\n', '\n  '))
        p = ans['visible']['noul']
        kind = ans['kind']['choice']
        vis = p >= 0.6 and kind not in ('none', 'misaligned', 'subtle')
        verdict = {'visible': vis, 'confidence': round(p if vis else 1 - p, 2), 'p_visible': round(p, 3), 'kind': kind}
        out.update(answers=ans, verdict=verdict, typesafe=log[0])
        print(f"visible={'yes' if vis else 'no'} confidence={verdict['confidence']} kind={kind} p_visible={p:.2f}")
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))
    return 0 if (verdict is None or verdict['visible']) else 1


if __name__ == '__main__':
    sys.exit(main())
