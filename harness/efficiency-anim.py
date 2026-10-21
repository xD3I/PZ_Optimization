#!/usr/bin/env python3
"""Workshop description animations of the stitch-efficiency.py videos (2026-10-06): a 10 s window of each video as one
revealing scene, encoded as animated AVIF (harness/anim-encode.py, 60 fps).

Layout (the maintainer's specimen-card reference, 2026-10-06: no header, one slide): a vertical strip on the left edge with
the video's feature ("Handheld Efficiency", "MacOS Upgrade", "Low End Mode") set in Martian Mono, rotated; right of it one
full-width view of the scene, stock left of a white divider and optimized right of it, the divider moving as on the
Workshop's "New!" cards (carglass-card-gif.py, ao-card-gif.py): hold on the optimized picture, sweep to stock, hold,
sweep back (cosine ease), one cycle per loop. Both panes come from the stitched video, already aligned
in time. Bottom left the old number (grey), bottom right the new one (white): the route means of the clean runs behind the
video (stitch-efficiency.py --numbers), a secondary line under each (the flip's socket watts; the Mac's OpenGL version).
Tone-map as the headline GIF (hable, 200 nits). Font: ~/.local/share/fonts/specimens/MartianMono-sWdRg.ttf (OFL), from
github.com/nicoverbruggen/ebook-fonts.

    harness/efficiency-anim.py [--theme light|dark] [--still png] [--crf 35] [--size 630 --gif] [name ...]     (queue `media` job)

--size N makes an N x N canvas like the headline animation (630): the scene is cropped square from the panes' centre and the
strip and text scale with it; --gif writes workshop-media/template-<name>.gif through gifski instead of the AVIF.
--font <ttf> sets the strip's and the numbers' type (default Martian Mono).
--single <mp4> --t0 S [--crop x:y:w] [--len 10] --out <file>: one view of any HDR capture in the same look (strip, scene, no
divider and no numbers), e.g. the headline: --single "~/Videos/Project Zomboid/Video_2026-10-06_16-23-03.mp4" --t0 23.3
--crop 1750:464:1620 --len 10 --label PZ_Optimization --size 630. The crop is the headline's square; the scene takes the
same centre at its own aspect (the strip takes some of the width). --numbers A:B [--second "x:y"] [--words "BEFORE:AFTER"]
adds the comparison videos' numbers (A fps grey bottom left, B fps white bottom right, the second line under each), e.g. the
showcase drive: --numbers 156:512 --second "p99 16.5 ms:p99 5.8 ms". --header puts the label in a bar across the top
instead of the strip (13 % of the height, the label centred, --label-px sets its size, default 42 at 630); the scene takes the
rest of the canvas round the same centre; --align left puts the label at the numbers' left padding. --label-px without
--header sets the strip label's size (the strip is 80 px with a 42 px label at 630 by default, the header bar's band).

A TRIPLES name (2026-10-06, the maintainer's choice for the Workshop mods section) is the three-pane cut of the same template:
stock | another performance mod | ours from three desktop `--record` runs of one route (the route start found in each
recording from the quit-to-black instant, the other two shifted onto stock by thumbnail matching); the view starts on ours,
both dividers sweep out to thirds, the three stand side by side THIRDS_HOLD s with a number block centred in each (route fps
and p99 of the uncaptured runs), then sweep back. `python3 harness/efficiency-anim.py --size 630 [--still png] mods-drive120`.

A RACES name (2026-10-06, boot and load) is a real-time race from launch: stock | ours from two desktop `--record` runs of the
`load` bench, each started at the game's first log line, synced on the world-visible instant and stretched per phase onto the
uncaptured runs' mean boot (-> Continue) and load (-> world visible); a live clock per side freezes when its world shows. The
divider runs one cycle while both boot, holds on ours past its finish, then on stock past its finish, and sweeps back.
`python3 harness/efficiency-anim.py --size 630 [--still png] boot-load`.

A RACES entry with kind='fps' (2026-10-15, the Louisville New! card) is a frame-rate race over the bench route: stock | ours
from two desktop `--record` runs, the same crop in two fixed halves (stock left, ours right, white separator), both
recordings synced on route time (the route is time-driven; route start = the quit-to-black at route end minus the route's
length), a live fps counter per side from its own pzopt-overlay.out (FPS_WINDOW s of frames, updated FPS_TICK times a
second), then a black end card with each side's route-mean fps and the ratio. `--still png` = one race frame + the end card.
"""
import argparse
import json
import os
import shutil
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONTS = os.path.expanduser('~/.local/share/fonts/specimens')
MONO = f'{FONTS}/MartianMono-sWdRg.ttf'
FONT = MONO                        # --font replaces it
TONEMAP = ('zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
           'zscale=p=bt709:t=bt709:m=bt709,format=rgb24')
W, STRIP = 1260, 46
SWEEP, HOLD = 2.0, 1.2             # the New! cards' divider: seconds per sweep, seconds on each whole picture
LINE_FADE = 0.12                   # the divider fades over the last 12 % of the width at each edge
END_HOLD = 1.5                     # the optimized picture held after the sweep back, so the loop does not cut off right after it
BEFORE, AFTER, CAPTION = (150, 150, 156), (240, 240, 244), (190, 190, 196)   # grey before, white after

VIDEOS = {  # name: (t0 s, strip label, stitch-efficiency.py kind, secondary line: 'watts', 'jpf', (before, after) text or None
           #        [, 'visual': no numbers, the (before, after) texts alone label the two sides of the divider])
    'flip-efficient-cores-vs-stock-60fps': (12, 'Handheld Efficiency', 'flip60', 'watts'),
    'flip-max-efficiency-vs-stock': (12, 'Handheld Efficiency', 'flip', 'watts'),
    'flip-storm-120fps-vs-stock': (6, 'Handheld Efficiency', 'flipstorm', 'jpf'),   # energy per frame under the fps
    'mac-opengl-2.1-vs-4.1-day': (4, 'MacOS Upgrade', 'macday', ('OpenGL 2.1', 'OpenGL 4.1')),
    'mac-opengl-2.1-vs-4.1-pond': (5, 'MacOS OpenGL 4.1', 'macpond', ('OpenGL 2.1', 'OpenGL 4.1')),
    'dell-low-end-vs-stock': (2.0, 'Low End HW Mode', 'dell', None),
    # Rosewood south drive at golden hour, partly cloudy, every visual enhancement but remembered places (runs wsc4-*, 2026-10-07): 16.7-24.6 s, the town stretch after the enhanced capture's 817 ms freeze at 15.8 s; stock's own 559 ms hitch at 23.8 s left in, b re-aligned after it
    'desktop-rosewood-golden-hour-visuals-vs-stock': (16.7, 'Entities Shadows', 'clouds', ('STOCK', 'ENHANCED'), 'visual'),
    # the same drive uncapped, 2026-10-08 (runs wsf-*): stock vs every optimization at its default, and stock vs everything
    # (+ the experimental optimizations + every Visuals card but remembered places); second line = the route's p99. Windows:
    # the captures' clean stretches (opt-cap 195 ms gap at route 15.6 s, max-cap 358 ms at 16.6 s; video 0 = route +13.8 s)
    'desktop-rosewood-all-optimizations-vs-stock': (2.2, 'Optimizations', 'wsf-opt', ('p99 26.5 ms', 'p99 7.9 ms')),
    'desktop-rosewood-everything-vs-stock': (3.2, 'Enhancements', 'wsf-max', ('p99 26.5 ms', 'p99 14.2 ms')),
}
TRIPLES = {  # three-pane comparisons (2026-10-06, the Workshop mods section): stock | another mod | ours, one view of the same
             # route from three desktop recordings (run.sh --record --prop overlay=false); numbers from uncaptured runs.
             # cap / num: run labels (newest run dir of each), words: the captions, crop: x:y:w square of the screen whose
             # centre the scene keeps, t0: route seconds where the clip starts; label: '{x}' = ours over the other mod's
             # route fps, e.g. "6x" (2026-10-06, the maintainer)
    'mods-drive120': dict(label='{x} Vs Other Mods', t0=6.0, crop='1750:270:1620',
                          cap=('mxcap-drive120-stock', 'mxcap-drive120-zedska', 'mxcap-drive120-opt'),
                          num=('mx-drive120-stock', 'mx-drive120-zedska-r', 'mx-drive120-optg1'),  # ours on G1 = what
                          # our install gives players (gcMode=g1); stock / Zed's on the game's own ZGC (2026-10-06)
                          words=('STOCK', "ZED'S BETTER FPS KA", 'ENHANCED')),
    # 2026-10-06, the maintainer: the 120 km/h drive in a thunderstorm with heavy fog, every side's car in view: split =
    # each pane a column of its own recording round its car ('cx:y0:h' screen px, one per side or one for all; the drive
    # camera keeps the car near 1760,720 at 5120x2160), the thirds held for the whole clip
    'mods-stormfog120': dict(label='{x} Vs Other Mods', t0=6.0, split='1760:226:1300',
                             cap=('mxcap-stormfog120-stock', 'mxcap-stormfog120-zedska', 'mxcap-stormfog120-opt'),
                             num=(('mx-stormfog120-stock-b1', 'mx-stormfog120-stock-b2'), 'mx-stormfog120-zedska-b',
                                  'mx-stormfog120-optg1-b'),   # one back-to-back block, 2026-10-07 00:19-00:24
                             words=('STOCK', "ZED'S BETTER FPS KA", 'ENHANCED')),
}
RACES = {  # real-time races from launch (2026-10-06, the maintainer's choice for boot and load): stock | ours from two desktop
           # `--record` runs of the `load` bench, each clock running from the game's first log line and frozen when the world
           # shows. cap: capture run labels; num: the uncaptured runs' label (every run dir of it, averaged); crop: x:y:w square
           # of the screen whose centre the scene keeps; mask: x:y:w:h box blacked out in the first mask_s video seconds (the
           # queue's "job started" desktop notification over the still-black game window)
    'boot-load': dict(label='Fast Boot & Load', cap=('boot-stock-cap', 'boot-opt-cap'), num=('boot-stock', 'boot-opt'),
                      crop='1480:0:2160', mask='2040:1400:420:280', mask_s=3.0),
}
# the same race with both clocks always on screen (maintainer, 2026-10-06: the divider wipes the pictures, not the numbers)
RACES['boot-load-clocks'] = dict(RACES['boot-load'], keep_numbers=True)
# no sweep (maintainer, 2026-10-06): each side its own half of the scene (the crop's centre at half the width), a fixed
# separator between them, both clocks always on screen
RACES['boot-load-split'] = dict(RACES['boot-load'], keep_numbers=True, split=True)
# frame-rate race over the Louisville horde bench (2026-10-15, the maintainer's New! card): stock vs round 10 with every
# optimization on (defaults + opt-ins), Enhancements off, both uncapped (stock: frameRate=240, never reached), recorded,
# no MangoHud. label: '{x}' = ours over stock in route-mean fps; t0: route second where the race clip starts; len: its
# seconds; end_hold: the black results card. Either crop (x:y:w square of the 5120x2160 screen whose centre both halves
# show) or path: a crop that follows the walk, keyframes (route s, centre x, centre y, height) in screen px, smoothed
# (crop_path); fps_window / fps_tick: the live counters. Maintainer, 2026-10-15 evening: follow the bench through the
# frame drops where it meets the hordes, not a still stretch. The walk: out of the dark building into the first horde's
# sight (top left, route 1.5-3.5 s: stock 24-34 fps, 118 ms frames; ours 108-143), the street crowd west of the Nite Glo
# (10-11 s: 31 / 111), the parking-lot horde (19.5-22 s: stock 24-28 fps, 134 ms frames; ours 99-137) and the crowd
# beside it (23-25 s). avoid: the queue's "job started" notification in the stock recording (route 11.5-16.25 s).
RACES['louisville-fps'] = dict(kind='fps', label='{x} faster Louisville', cap=('lourace-stock1', 'lourace-opt1'),
                               words=('STOCK', 'OPTIMIZED'), t0=1.0, len=23.75, end_hold=2.5, fps_window=0.25, fps_tick=4,
                               path=[(1.0, 2450, 1000, 1900), (2.0, 2150, 1000, 2000), (3.5, 2150, 1000, 2000),
                                     (5.5, 2450, 1000, 1700), (9.0, 2300, 1000, 1800), (10.5, 2170, 1000, 1800),
                                     (12.0, 2350, 980, 1700), (17.0, 2450, 980, 1700), (18.5, 2400, 900, 1700), (20.0, 2300, 1330, 1500),
                                     (21.0, 2250, 1345, 1450), (23.0, 2800, 1000, 1500), (24.75, 2750, 1080, 1500)],
                               path_sigma=0.5, path_ymax=2070, avoid=[(11.4, 16.4, 2640, 1850, 3110, 2160)])
FPS_WINDOW, FPS_TICK = 0.5, 4      # fps race: the live counter's window (s) and its updates per second
THIRDS_HOLD = 2.6                  # triple: seconds the three panes stand side by side
THEMES = {  # strip, strip text
    'dark': ((240, 240, 244), (11, 11, 14)),
    'light': ((0, 0, 0), (255, 255, 255)),
}


def chrome(theme, label, h, W=W, STRIP=STRIP, px=None, top=None):
    """The static frame: the left strip with its label; the scene area right of it left empty."""
    c_strip, c_strip_txt = THEMES[theme]
    img = Image.new('RGB', (W, h), c_strip)
    lab = Image.new('RGBA', (1600, STRIP), (0, 0, 0, 0))
    ImageDraw.Draw(lab).text((0, STRIP // 2), label, font=ImageFont.truetype(FONT, px or max(13, round(20 * STRIP / 46))), fill=c_strip_txt, anchor='lm')
    lab = lab.crop(lab.getbbox()).rotate(-90, expand=True)   # reads top to bottom, as on the specimen card
    img.paste(lab, ((STRIP - lab.width) // 2, round(26 * STRIP / 46) if top is None else top), lab)
    return img


def visual_overlay(second, sw, sh, k=1.0):
    """Layers over the scene of a visual comparison: (rgba, side) with side None = always, 'a' = while the stock side
    shows (left of the divider), 'b' = while the optimized side shows. A light gradient and the two side labels."""
    g = np.zeros((sh, sw, 4), np.uint8)
    rows = int(sh * 0.25)
    g[sh - rows:, :, 3] = (np.linspace(0, 1, rows) ** 1.6 * 170).astype(np.uint8)[:, None]
    layers = [(g, None)]
    f, pad = ImageFont.truetype(FONT, max(11, round(40 * k))), round(26 * k)
    for side, col, right in (('a', BEFORE, False), ('b', AFTER, True)):
        img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
        ImageDraw.Draw(img).text((sw - pad if right else pad, sh - pad), second[side == 'b'], font=f, fill=col,
                                 anchor='rs' if right else 'ls')
        layers.append((np.asarray(img), side))
    return layers


def numbers_overlay(n, second, sw, sh, k=1.0, words=('STOCK', 'ENHANCED')):
    """Layers over the scene (see visual_overlay): a dark gradient along the bottom (always), the stock block bottom left
    (side 'a') and the enhanced block bottom right (side 'b'); the divider wipes each block with its picture."""
    g = np.zeros((sh, sw, 4), np.uint8)
    rows = int(sh * 0.42)
    g[sh - rows:, :, 3] = (np.linspace(0, 1, rows) ** 1.6 * 200).astype(np.uint8)[:, None]
    layers = [(g, None)]
    F = lambda px: ImageFont.truetype(FONT, max(11, round(px * k)))
    cap, big, unit, sec, sec_u = F(15), F(64), F(32), F(34), F(18)
    pad = round(26 * k)
    for side, col, word, right in (('a', BEFORE, words[0], False), ('b', AFTER, words[1], True)):
        img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        v = n[side]
        lines = [(f"{v['fps']:.0f}", 'fps', big, unit)]
        if second == 'watts':
            lines.append((f"{v['watts']:.1f}", 'W', sec, sec_u))
        elif second == 'jpf':
            lines.append((f"{v['jpf'] * 1000:.0f}", 'mJ/frame', sec, sec_u))
        elif second:
            lines.append((second[side == 'b'], '', sec, sec_u))
        y = sh - pad
        for num, u, f, fu in reversed(lines):   # baselines bottom-up
            gap = round(8 * k) if u else 0
            wn, wu = d.textlength(num, font=f), d.textlength(u, font=fu) if u else 0
            x0 = sw - pad - (wn + gap + wu) if right else pad
            d.text((x0, y), num, font=f, fill=col, anchor='ls')
            if u:
                d.text((x0 + wn + gap, y), u, font=fu, fill=col, anchor='ls')
            y -= f.size + round(14 * k)
        d.text((sw - pad if right else pad, y + round(4 * k)), word, font=cap, fill=CAPTION, anchor='rs' if right else 'ls')
        layers.append((np.asarray(img), side))
    return layers


def panes(video, t0, secs, geo, sw, sh, square=False):
    """Tone-mapped (stock, optimized) pane pairs of the stitched video, each scaled to the scene size."""
    head, pw, ph, gap = geo['head'], geo['pw'], geo['ph'], geo['gap']
    fit = f'scale=-2:{sh}:flags=lanczos,crop={sw}:{sh}' if square else f'scale={sw}:{sh}:flags=lanczos'   # square: the centre
    fc = (f'[0:v]fps=60,{TONEMAP},split[a][b];'
          f'[a]crop={pw}:{ph}:0:{head},{fit}[l];'
          f'[b]crop={pw}:{ph}:{pw + gap}:{head},{fit}[r];[l][r]vstack,format=rgb24')
    p = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-ss', str(t0), '-t', str(secs), '-i', video,
                          '-filter_complex', fc, '-f', 'rawvideo', '-'], stdout=subprocess.PIPE)
    n = sw * sh * 2 * 3
    while True:
        b = p.stdout.read(n)
        if len(b) < n:
            break
        f = np.frombuffer(b, np.uint8).reshape(2 * sh, sw, 3)
        yield f[:sh], f[sh:]
    p.stdout.close()
    p.wait()


def single_frames(video, t0, secs, crop, sw, sh):
    """Tone-mapped frames of one HDR capture: the crop's centre at the scene's aspect (crop x:y:w = a square of side w, the
    headline's notation; empty = the whole frame), scaled to the scene size."""
    if crop:
        x, y, w = (int(v) for v in crop.split(':'))
        cw = round(w * sw / sh)
        cut = f'crop={cw}:{w}:{x + (w - cw) // 2}:{y},'
    else:
        cut = f'scale={sw}:{sh}:force_original_aspect_ratio=increase,crop={sw}:{sh},'
    p = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-ss', str(t0), '-t', str(secs), '-i', video,
                          '-vf', f'fps=60,{TONEMAP},{cut}scale={sw}:{sh}:flags=lanczos,format=rgb24', '-f', 'rawvideo', '-'],
                         stdout=subprocess.PIPE)
    n = sw * sh * 3
    while True:
        b = p.stdout.read(n)
        if len(b) < n:
            break
        yield np.frombuffer(b, np.uint8).reshape(sh, sw, 3)
    p.stdout.close()
    p.wait()


def encode(a, work, shape, dst):
    """The frames in work/frames to an AVIF (or with --gif a GIF) at dst; the frames are removed afterwards."""
    if a.gif:
        variant = f'gifski:{a.gif_fps}:{shape[1]}x{shape[0]}:q=80'
    else:
        variant = f'avif:60:{shape[1]}x{shape[0]}:crf={a.crf}'
    subprocess.run(['python3', 'harness/anim-encode.py', '--frames', f'{work}/frames', '--src-fps', '60', '--out', work, variant],
                   check=True)
    ext = '.gif' if a.gif else '.avif'
    shutil.copy(next(f'{work}/{f}' for f in sorted(os.listdir(work)) if f.endswith(ext)), dst)
    shutil.rmtree(f'{work}/frames')
    print('->', dst, os.path.getsize(dst))


def compose(base, left, right, ov, x, STRIP=STRIP):
    """One frame: stock (`left`) left of the divider at x, optimized (`right`) right of it, the divider, the overlay layers."""
    fr = base.copy()
    sc = fr[:, STRIP:]
    sc[:, :x] = left[:, :x]
    sc[:, x:] = right[:, x:]
    for layer, side in ov:   # each side's labels only over its own picture: the divider wipes them with it
        cols = slice(0, x) if side == 'a' else slice(x, None) if side == 'b' else slice(None)
        a = layer[:, cols, 3:4].astype(np.float32) / 255.0
        sc[:, cols] = (sc[:, cols] * (1 - a) + layer[:, cols, :3] * a).astype(np.uint8)
    w = sc.shape[1]
    fade = ease(min(1.0, min(x, w - x) / (LINE_FADE * w)))   # the line fades out towards the edges instead of vanishing
    if 0 < x < w and fade > 0:
        sh_ = slice(max(0, x - 3), x + 3)
        sc[:, sh_] = (sc[:, sh_] * (1 - 0.45 * fade)).astype(np.uint8)   # a soft shadow round the line
        ln = slice(max(0, x - 1), x + 1)
        sc[:, ln] = (sc[:, ln] * (1 - fade) + 255 * fade).astype(np.uint8)
    return fr


def ease(t):
    return 0.5 - 0.5 * np.cos(np.pi * t)


def divider_positions(fps=60):
    """Share of the width showing stock (left of the divider), per frame, as the New! cards: hold on the optimized picture,
    sweep to stock, hold, sweep back, hold on the optimized picture again (END_HOLD)."""
    n, h = round(SWEEP * fps), round(HOLD * fps)
    return ([0.0] * h + [ease((i + 1) / n) for i in range(n)] + [1.0] * h + [1 - ease((i + 1) / n) for i in range(n)] + [0.0] * round(END_HOLD * fps))


def triple_positions(fps=60):
    """(a, b) per frame for the three-pane cut: stock left of a, the other mod between a and b, ours right of b. Hold on
    ours, sweep both dividers out to thirds, hold the three side by side, sweep back, hold on ours again."""
    n, h = round(SWEEP * fps), round(HOLD * fps)
    out = [(0.0, 0.0)] * h
    out += [(ease((i + 1) / n) / 3, ease((i + 1) / n) * 2 / 3) for i in range(n)]
    out += [(1 / 3, 2 / 3)] * round(THIRDS_HOLD * fps)
    out += [((1 - ease((i + 1) / n)) / 3, (1 - ease((i + 1) / n)) * 2 / 3) for i in range(n)]
    return out + [(0.0, 0.0)] * round(END_HOLD * fps)


def kv(path):
    return dict(l.strip().split('=', 1) for l in open(path) if '=' in l)


def run_dir(label):
    runs = sorted(d for d in os.listdir('harness/runs') if d.startswith(label + '-2'))
    if not runs:
        raise SystemExit(f'no run {label}')
    return f'harness/runs/{runs[-1]}'


def route_window(d):
    """(route start, route end) epoch ms of a run."""
    s = kv(f'{d}/pzopt-schedule.out')
    b = kv(f'{d}/pzopt-bench.out')
    return int(s['route_start_epoch_ms']), int(s.get('route_end_epoch_ms') or b['route_end_epoch_ms'])


def route_numbers(d):
    """Route-window fps and p99 frame time of an uncaptured run's presented frames (pzopt-overlay.out)."""
    r0, r1 = route_window(d)
    rows = [l.strip().split(',') for l in open(f'{d}/pzopt-overlay.out') if l.strip()]
    head = rows[0]
    ie, ift = head.index('epoch_ms'), head.index('frametime')
    ft = np.array([float(r[ift]) for r in rows[1:] if len(r) == len(head) and r0 <= float(r[ie]) <= r1])
    return dict(fps=len(ft) / ((r1 - r0) / 1000.0), p99=float(np.percentile(ft, 99)))


def thumbs(video, t, secs, fps=30, w=96, h=40):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-ss', f'{max(0.0, t):.3f}', '-t', f'{secs:.3f}', '-i', video,
                          '-vf', f'fps={fps},scale={w}:{h},format=gray', '-f', 'rawvideo', '-'], capture_output=True, check=True).stdout
    n = len(raw) // (w * h)
    return np.frombuffer(raw[:n * w * h], np.uint8).reshape(n, h, w).astype(np.float32)


def route_start_in_video(d):
    """Seconds into <run>/recording.mp4 where the route starts: the game quits the instant the route ends and the capture
    goes black (stitch-louisville-sbs.sh), minus the route's length."""
    r0, r1 = route_window(d)
    video = f'{d}/recording.mp4'
    guess = r1 / 1000 - int(subprocess.run(['stat', '-c', '%W', video], capture_output=True, text=True).stdout) - 4
    m = thumbs(video, guess, 8, 20, 64, 27).mean(axis=(1, 2))
    black = next(i for i in range(1, len(m)) if m[i] < 8 and m[i - 1] >= 8)
    return guess + black / 20 - (r1 - r0) / 1000


def video_shift(va, ta, vb, tb, secs=14.0, search=1.0, fps=30):
    """Seconds to add to tb so that vb shows what va shows at ta (thumbnail difference over `secs` of route)."""
    a = thumbs(va, ta + 1, secs, fps)
    b = thumbs(vb, tb + 1 - search, secs + 2 * search, fps)
    best, err = 0.0, None
    for k in range(0, int(2 * search * fps) + 1):
        n = min(len(a), len(b) - k)
        e = float(np.abs(a[:n] - b[k:k + n]).mean())
        if err is None or e < err:
            best, err = k / fps - search, e
    return best


def triple_frames(videos, starts, secs, crop, sw, sh):
    """Tone-mapped frames of the three recordings from their own start seconds, the crop square's centre at the scene size."""
    x, y, w = (int(v) for v in crop.split(':'))
    procs = [subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-ss', f'{t:.3f}', '-t', str(secs), '-i', v,
                               '-vf', f'fps=60,{TONEMAP},crop={w}:{w}:{x}:{y},scale=-2:{sh}:flags=lanczos,crop={sw}:{sh},format=rgb24',
                               '-f', 'rawvideo', '-'], stdout=subprocess.PIPE) for v, t in zip(videos, starts)]
    n = sw * sh * 3
    while True:
        bufs = [p.stdout.read(n) for p in procs]
        if any(len(b) < n for b in bufs):
            break
        yield [np.frombuffer(b, np.uint8).reshape(sh, sw, 3) for b in bufs]
    for p in procs:
        p.stdout.close()
        p.wait()


def triple_frames_split(videos, starts, secs, cars, sw, sh):
    """Split triple: per recording a column of the screen round its car (cars: 'cx:y0:h' each = the column's centre x,
    top y and height in screen px), scaled to a third of the scene, put in its own third of a scene-sized frame."""
    pw = sw // 3
    cols = [(0, pw), (pw, 2 * pw), (2 * pw, sw)]
    procs = []
    for v, t, car, (c0, c1) in zip(videos, starts, cars, cols):
        cx, y0, h = (int(x) for x in car.split(':'))
        w = round((c1 - c0) * h / sh)
        procs.append(subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-ss', f'{t:.3f}', '-t', str(secs), '-i', v,
                                       '-vf', f'fps=60,{TONEMAP},crop={w}:{h}:{cx - w // 2}:{y0},scale={c1 - c0}:{sh}:flags=lanczos,'
                                       f'format=rgb24', '-f', 'rawvideo', '-'], stdout=subprocess.PIPE))
    ns = [(c1 - c0) * sh * 3 for c0, c1 in cols]
    while True:
        bufs = [p.stdout.read(n) for p, n in zip(procs, ns)]
        if any(len(b) < n for b, n in zip(bufs, ns)):
            break
        panes = []
        for b, (c0, c1) in zip(bufs, cols):
            f = np.zeros((sh, sw, 3), np.uint8)
            f[:, c0:c1] = np.frombuffer(b, np.uint8).reshape(sh, c1 - c0, 3)
            panes.append(f)
        yield panes
    for p in procs:
        p.stdout.close()
        p.wait()


def triple_overlay(nums, words, sw, sh, k=1.0):
    """Layers over the three-pane scene: the bottom gradient (always) and one number block centred in each third, side
    'a' (stock, grey), 'm' (the other mod, grey), 'b' (ours, white); each is wiped with its picture."""
    g = np.zeros((sh, sw, 4), np.uint8)
    rows = int(sh * 0.42)
    g[sh - rows:, :, 3] = (np.linspace(0, 1, rows) ** 1.6 * 200).astype(np.uint8)[:, None]
    layers = [(g, None)]
    pad = round(26 * k)
    third = sw / 3
    kk = k
    while True:   # one size for all three blocks, as large as fits a third
        F = lambda px: ImageFont.truetype(FONT, max(9, round(px * kk)))
        cap, big, unit, sec = F(15), F(64), F(32), F(26)
        d = ImageDraw.Draw(Image.new('RGBA', (8, 8)))
        wide = max(max(d.textlength(f"{v['fps']:.0f}", font=big) + round(8 * kk) + d.textlength('fps', font=unit),
                       d.textlength(f"p99 {v['p99']:.1f} ms", font=sec), d.textlength(wd, font=cap)) for v, wd in zip(nums, words))
        if wide <= third - 2 * round(10 * k) or kk < 0.3:
            break
        kk *= 0.95
    for i, (side, col) in enumerate((('a', BEFORE), ('m', BEFORE), ('b', AFTER))):
        img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        v, cx, y = nums[i], third * (i + 0.5), sh - pad
        s = f"p99 {v['p99']:.1f} ms"
        d.text((cx, y), s, font=sec, fill=col, anchor='ms')
        y -= sec.size + round(14 * kk)
        num, gap = f"{v['fps']:.0f}", round(8 * kk)
        wn, wu = d.textlength(num, font=big), d.textlength('fps', font=unit)
        x0 = cx - (wn + gap + wu) / 2
        d.text((x0, y), num, font=big, fill=col, anchor='ls')
        d.text((x0 + wn + gap, y), 'fps', font=unit, fill=col, anchor='ls')
        y -= big.size + round(14 * kk)
        d.text((cx, y + round(4 * kk)), words[i], font=cap, fill=CAPTION, anchor='ms')
        layers.append((np.asarray(img), side))
    return layers


def compose3(base, panes, ov, xa, xb, STRIP=STRIP):
    """One frame of the three-pane cut: stock left of xa, the other mod from xa to xb, ours right of xb, two dividers."""
    fr = base.copy()
    sc = fr[:, STRIP:]
    sc[:, :xa] = panes[0][:, :xa]
    sc[:, xa:xb] = panes[1][:, xa:xb]
    sc[:, xb:] = panes[2][:, xb:]
    cols = {'a': slice(0, xa), 'm': slice(xa, xb), 'b': slice(xb, None), None: slice(None)}
    for layer, side in ov:
        c = cols[side]
        a = layer[:, c, 3:4].astype(np.float32) / 255.0
        sc[:, c] = (sc[:, c] * (1 - a) + layer[:, c, :3] * a).astype(np.uint8)
    w = sc.shape[1]
    for x in sorted({xa, xb}):
        fade = ease(min(1.0, min(x, w - x) / (LINE_FADE * w)))
        if 0 < x < w and fade > 0:
            sh_ = slice(max(0, x - 3), x + 3)
            sc[:, sh_] = (sc[:, sh_] * (1 - 0.45 * fade)).astype(np.uint8)
            ln = slice(max(0, x - 1), x + 1)
            sc[:, ln] = (sc[:, ln] * (1 - fade) + 255 * fade).astype(np.uint8)
    return fr


def triple(a, name, cw, strip, label_px, k):
    c = TRIPLES[name]
    sw = cw - strip
    sh = a.size or int(round(sw * 9 / 16))
    sh -= sh % 2
    caps = [run_dir(l) for l in c['cap']]
    videos = [f'{d}/recording.mp4' for d in caps]
    rs = [route_start_in_video(d) for d in caps]
    shifts = [0.0] + [video_shift(videos[0], rs[0], v, t) for v, t in zip(videos[1:], rs[1:])]
    starts = [t + c['t0'] + s for t, s in zip(rs, shifts)]
    def num_of(l):   # a tuple of labels = the mean of those runs (stock at both ends of a back-to-back block)
        ls = l if isinstance(l, tuple) else (l,)
        ns = [route_numbers(run_dir(x)) for x in ls]
        return {k: sum(n[k] for n in ns) / len(ns) for k in ns[0]}
    nums = [num_of(l) for l in c['num']]
    r = nums[2]['fps'] / nums[1]['fps']   # ours over the other mod
    x = f'{r:.0f}x' if r >= 10 or abs(r - round(r)) < 0.05 else f'{r:.1f}x'
    base = np.asarray(chrome(a.theme, (a.label or c['label']).replace('{x}', x), sh, cw, strip, label_px, round(26 * k))).copy()
    print(f'== {name}: route start in the videos {[round(t, 2) for t in rs]}, shifts {[round(s, 3) for s in shifts]}, '
          f'numbers ' + ', '.join(f"{w} {n['fps']:.1f} fps p99 {n['p99']:.1f}" for w, n in zip(c['words'], nums)))
    ov = triple_overlay(nums, c['words'], sw, sh, k)
    split = c.get('split')
    if split:   # each pane on its own car, thirds held for the whole clip
        cars = [split] * 3 if isinstance(split, str) else list(split)
        seq = [(1 / 3, 2 / 3)] * round(a.len * 60)
        frames_of = lambda st, secs: triple_frames_split(videos, st, secs, cars, sw, sh)
    else:
        seq = triple_positions()
        frames_of = lambda st, secs: triple_frames(videos, st, secs, c['crop'], sw, sh)
    if a.still:
        panes = next(frames_of([s + 2 for s in starts], 0.1))
        Image.fromarray(compose3(base, panes, ov, sw // 3, sw * 2 // 3, strip)).save(a.still)
        print('still', a.still, base.shape)
        return
    work = f'build/anim/{name}-{a.theme}-{cw}'
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f'{work}/frames')
    i = 0
    for panes in frames_of(starts, len(seq) / 60):
        xa, xb = (round(sw * f) for f in seq[min(i, len(seq) - 1)])
        Image.fromarray(compose3(base, panes, ov, xa, xb, strip)).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
        i += 1
    print(f'== {name}: {i} frames {base.shape[1]}x{base.shape[0]} from route +{c["t0"]} s')
    encode(a, work, base.shape, a.out or (f'workshop-media/template-{name}.gif' if a.gif else f'workshop-media/{name}.avif'))


def load_events(d):
    """Epoch ms of the first log line, the Continue press and the world showing (pzopt-loadtrace.out; LoadTrace's
    "load step: world visible", the player's chunk lit, is the moment the picture changes from the loading screen)."""
    ev = {}
    for l in open(f'{d}/pzopt-loadtrace.out', errors='replace'):
        try:
            ep = int(l.split('\t', 1)[0])
        except ValueError:
            continue
        ev.setdefault('log', ep)
        if 'continuing latest save' in l:
            ev.setdefault('cont', ep)
        if 'load step: world visible' in l:
            ev.setdefault('vis', ep)
    return ev


def race_numbers(label):
    """(boot, load) seconds averaged over every run dir of an uncaptured label: launch -> Continue, Continue -> world visible."""
    runs = sorted(f'harness/runs/{r}' for r in os.listdir('harness/runs') if r.startswith(label + '-2'))
    if not runs:
        raise SystemExit(f'no run {label}')
    ev = [load_events(d) for d in runs]
    return (float(np.mean([(e['cont'] - e['log']) / 1000 for e in ev])), float(np.mean([(e['vis'] - e['cont']) / 1000 for e in ev])),
            [os.path.basename(d) for d in runs])


def race_sync(d):
    """Video seconds of the first log line, Continue and the world showing in <run>/recording.mp4. The recorder starts a few
    hundred ms after its file is created; the exact offset comes from the world-visible instant, where the picture jumps
    from the black loading screen."""
    ev = load_events(d)
    video = f'{d}/recording.mp4'
    birth = float(subprocess.run(['stat', '-c', '%.9W', video], capture_output=True, text=True).stdout.replace(',', '.'))
    guess = ev['vis'] / 1000 - birth
    m = thumbs(video, guess - 2, 5, 60).mean(axis=(1, 2))
    rise = next(i for i in range(len(m)) if m[i] > 3)
    off = guess - 2 + rise / 60 - ev['vis'] / 1000
    return {k: v / 1000 + off for k, v in ev.items()}


def race_positions(done, fps=60):
    """Divider per frame for a race (share of the width showing stock): the New! cards' cycle once while both boot, hold on
    ours until a hold after it finished, sweep to stock and hold there until a hold after stock finished, sweep back.
    done = (stock, ours) seconds to the world."""
    n = round(SWEEP * fps)
    out = [0.0] * round(HOLD * fps) + [ease((i + 1) / n) for i in range(n)] + [1.0] * round(HOLD * fps)
    out += [1 - ease((i + 1) / n) for i in range(n)]
    out += [0.0] * max(0, round((done[1] + HOLD) * fps) - len(out))
    out += [ease((i + 1) / n) for i in range(n)]
    out += [1.0] * max(0, round((done[0] + HOLD) * fps) - len(out))
    out += [1 - ease((i + 1) / n) for i in range(n)]
    return out + [0.0] * round((END_HOLD - 0.7) * fps)


def race_overlay(t, nums, sw, sh, k=1.0, words=('STOCK', 'ENHANCED'), keep=False):
    """Layers at race second t: the bottom gradient and per side a live clock (frozen at the world), the boot line and,
    from the Continue press, the load line, each ticking until its phase ends. keep: both blocks always shown (not wiped
    with their pictures)."""
    g = np.zeros((sh, sw, 4), np.uint8)
    rows = int(sh * 0.42)
    g[sh - rows:, :, 3] = (np.linspace(0, 1, rows) ** 1.6 * 200).astype(np.uint8)[:, None]
    layers = [(g, None)]
    F = lambda px: ImageFont.truetype(FONT, max(11, round(px * k)))
    cap, big, unit, sec = F(15), F(64), F(32), F(26)
    pad = round(26 * k)
    for side, col, word, right, (boot, load) in (('a', BEFORE, words[0], False, nums[0]), ('b', AFTER, words[1], True, nums[1])):
        img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        x = sw - pad if right else pad
        anchor = 'rs' if right else 'ls'
        y = sh - pad
        if t >= boot:
            d.text((x, y), f'load {min(t - boot, load):4.1f} s', font=sec, fill=col, anchor=anchor)
        y -= sec.size + round(8 * k)
        d.text((x, y), f'boot {min(t, boot):4.1f} s', font=sec, fill=col, anchor=anchor)
        y -= sec.size + round(14 * k)
        num, gap = f'{min(t, boot + load):.1f}', round(8 * k)
        wn, wu = d.textlength(num, font=big), d.textlength('s', font=unit)
        x0 = sw - pad - (wn + gap + wu) if right else pad
        d.text((x0, y), num, font=big, fill=col, anchor='ls')
        d.text((x0 + wn + gap, y), 's', font=unit, fill=col, anchor='ls')
        y -= big.size + round(14 * k)
        d.text((x, y + round(4 * k)), word, font=cap, fill=CAPTION, anchor=anchor)
        layers.append((np.asarray(img), None if keep else side))
    return layers


class RaceSide:
    """One capture read forward from its first log line at 60 fps, tone-mapped and cut to the scene; frame(v) returns the
    frame at video second v (monotonic calls), so a time map can stretch the capture onto the uncaptured run's phases."""

    def __init__(self, video, t0, secs, crop, mask, mask_s, sw, sh):
        x, y, w = (int(v) for v in crop.split(':'))
        cw = round(w * sw / sh)
        box = ''
        if mask:   # None: nothing to black out
            mx, my, mw, mh = (int(v) for v in mask.split(':'))
            box = f'drawbox=x={mx}:y={my}:w={mw}:h={mh}:color=black:t=fill:enable=\'lt(t,{mask_s - t0:.3f})\','
        vf = (f'fps=60,{TONEMAP},{box}'
              f'crop={cw}:{w}:{x + (w - cw) // 2}:{y},scale={sw}:{sh}:flags=lanczos,format=rgb24')
        self.p = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-ss', f'{t0:.3f}', '-t', f'{secs:.3f}', '-i', video,
                                   '-vf', vf, '-f', 'rawvideo', '-'], stdout=subprocess.PIPE)
        self.t0, self.n, self.shape, self.i, self.cur = t0, sw * sh * 3, (sh, sw, 3), -1, None

    def frame(self, v):
        want = max(0, round((v - self.t0) * 60))
        while self.i < want:
            b = self.p.stdout.read(self.n)
            if len(b) < self.n:
                break   # past the end: hold the last frame
            self.cur, self.i = np.frombuffer(b, np.uint8).reshape(self.shape), self.i + 1
        return self.cur

    def close(self):
        self.p.stdout.close()
        self.p.kill()
        self.p.wait()


def race(a, name, cw, strip, label_px, k):
    c = RACES[name]
    if c.get('kind') == 'fps':
        return fps_race(a, name, cw, strip, label_px, k)
    sw = cw - strip
    sh = a.size or int(round(sw * 9 / 16))
    sh -= sh % 2
    base = np.asarray(chrome(a.theme, a.label or c['label'], sh, cw, strip, label_px, round(26 * k))).copy()
    caps = [run_dir(l) for l in c['cap']]
    syncs = [race_sync(d) for d in caps]
    nums = [race_numbers(l) for l in c['num']]
    done = [b + l for b, l, _ in nums]
    split = c.get('split', False)
    seq = [0.5] * round((done[0] + HOLD + END_HOLD) * 60) if split else race_positions(done)
    secs = len(seq) / 60
    pw = sw // 2 if split else sw   # pane width
    print(f'== {name}: ' + '; '.join(f"{w} boot {b:.2f} s load {l:.2f} s = {b + l:.2f} s ({', '.join(r)}), capture video "
                                    f"log {s['log']:.2f} cont {s['cont']:.2f} vis {s['vis']:.2f}"
                                    for w, (b, l, r), s in zip(('stock', 'ours'), nums, syncs)) + f'; {secs:.2f} s, {len(seq)} frames')

    def vmap(s, n, t):
        """Race second t -> capture video second: boot and load each stretched onto the uncaptured runs' means."""
        b, l, _ = n
        if t <= b:
            return s['log'] + t / b * (s['cont'] - s['log'])
        if t <= b + l:
            return s['cont'] + (t - b) / l * (s['vis'] - s['cont'])
        return s['vis'] + t - b - l
    sides = [RaceSide(f'{d}/recording.mp4', s['log'], vmap(s, n, secs) - s['log'] + 0.5, c['crop'], c['mask'], c['mask_s'], pw, sh)
             for d, s, n in zip(caps, syncs, nums)]
    tn = [(b, l) for b, l, _ in nums]
    keep = c.get('keep_numbers', False)

    def frames(t):
        """(left, right) scene-sized frames at race second t; split: each pane in its own half."""
        fr = [side.frame(vmap(s, n, t)) for side, s, n in zip(sides, syncs, nums)]
        if not split:
            return fr
        blank = np.zeros((sh, sw - pw, 3), np.uint8)
        return np.hstack([fr[0], blank]), np.hstack([np.zeros((sh, sw - pw, 3), np.uint8), fr[1]])
    if a.still:
        t = done[1] + 2.0
        fr = frames(t)
        Image.fromarray(compose(base, fr[0], fr[1], race_overlay(t, tn, sw, sh, k, keep=keep), pw if split else sw // 2, strip)).save(a.still)
        for side in sides:
            side.close()
        print('still', a.still, base.shape, f'at race {t:.1f} s')
        return
    work = f'build/anim/{name}-{a.theme}-{cw}'
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f'{work}/frames')
    for i, x in enumerate(seq):
        t = i / 60
        fr = frames(t)
        Image.fromarray(compose(base, fr[0], fr[1], race_overlay(t, tn, sw, sh, k, keep=keep), pw if split else round(sw * x), strip)).save(
            f'{work}/frames/f{i:05d}.png', compress_level=1)
    for side in sides:
        side.close()
    print(f'== {name}: {len(seq)} frames {base.shape[1]}x{base.shape[0]}')
    encode(a, work, base.shape, a.out or (f'workshop-media/template-{name}.gif' if a.gif else f'workshop-media/{name}.avif'))


def fps_run(d):
    """A run's presented frames in the route window (pzopt-overlay.out): route second of each frame, its ms, the route mean
    fps (frames over their summed frame time, as harness/slowframes/goal.py), p99 and the route's length."""
    r0, r1 = route_window(d)
    rows = [l.strip().split(',') for l in open(f'{d}/pzopt-overlay.out') if l.strip()]
    head = rows[0]
    ie, ift = head.index('epoch_ms'), head.index('frametime')
    e, f = [], []
    for r in rows[1:]:
        try:
            ee, ff = int(r[ie]), float(r[ift])
        except (ValueError, IndexError):
            continue
        if r0 <= ee <= r1:
            e.append((ee - r0) / 1000)
            f.append(ff)
    e, f = np.array(e), np.array(f)
    p99 = float(np.sort(f)[min(len(f) - 1, int(0.99 * len(f)))])   # goal.py's percentile, so the card matches its table
    return dict(e=e, f=f, fps=1000 * len(f) / f.sum(), p99=p99, secs=(r1 - r0) / 1000)


def live_fps(s, t, window=FPS_WINDOW, tick=FPS_TICK):
    """A side's counter at route second t: the frames of the `window` s before the last tick (`tick` a second)."""
    t = np.floor(t * tick + 1e-6) / tick
    f = s['f'][(s['e'] > t - window) & (s['e'] <= t)]
    return 1000 * len(f) / f.sum() if len(f) else 0.0


class FollowSide:
    """One capture read forward from video second t0 at 60 fps, tone-mapped and halved (2560x1080 for the 5K desktop); frame(v)
    returns the PIL frame at video second v (monotonic calls), for a moving crop (crop_path)."""

    def __init__(self, video, t0, secs, scale=2):
        w, h = (int(x) for x in subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                                                 'stream=width,height', '-of', 'csv=p=0', video], capture_output=True,
                                                text=True, check=True).stdout.split()[0].strip(',').split(',')[:2])
        self.w, self.h, self.scale = w // scale, h // scale, scale
        self.p = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-ss', f'{t0:.3f}', '-t', f'{secs:.3f}', '-i', video,
                                   '-vf', f'fps=60,{TONEMAP},scale={self.w}:{self.h}:flags=lanczos,format=rgb24', '-f', 'rawvideo', '-'],
                                  stdout=subprocess.PIPE)
        self.t0, self.n, self.i, self.cur = t0, self.w * self.h * 3, -1, None

    def frame(self, v):
        want = max(0, round((v - self.t0) * 60))
        while self.i < want:
            b = self.p.stdout.read(self.n)
            if len(b) < self.n:
                break   # past the end: hold the last frame
            self.cur, self.i = Image.frombuffer('RGB', (self.w, self.h), b, 'raw', 'RGB', 0, 1), self.i + 1
        return self.cur

    def crop(self, v, box, pw, sh):
        """The screen-px box (x0, y0, x1, y1) of the frame at v, scaled to pw x sh (float box: sub-pixel pans)."""
        return np.asarray(self.frame(v).resize((pw, sh), Image.LANCZOS, box=tuple(b / self.scale for b in box)))

    def close(self):
        self.p.stdout.close()
        self.p.kill()
        self.p.wait()


def crop_path(c, times, aspect, screen=(5120, 2160)):
    """Per route second in `times`, the crop box (x0, y0, x1, y1) in screen px: the entry's path keyframes (t, cx, cy, h),
    linear between them, smoothed with a Gaussian of path_sigma s (no stops at the keyframes, no jumps), width = h * aspect,
    kept inside the screen and above path_ymax (the hotbar, the cursor); `avoid` boxes (t0, t1, x0, y0, x1, y1) a crop must not
    touch in that route time (a desktop notification over one recording) are reported."""
    k = np.array(c['path'], float)
    fine = np.arange(times[0] - 3, times[-1] + 3, 1 / 60)
    cx, cy, h = (np.interp(fine, k[:, 0], k[:, i]) for i in (1, 2, 3))
    sig = c.get('path_sigma', 0.5) * 60
    g = np.exp(-0.5 * (np.arange(-int(3 * sig), int(3 * sig) + 1) / sig) ** 2)
    g /= g.sum()
    cx, cy, h = (np.convolve(np.pad(x, len(g) // 2, mode='edge'), g, 'valid') for x in (cx, cy, h))
    ymax = c.get('path_ymax', screen[1])
    boxes = []
    for t in times:
        i = min(len(fine) - 1, int(round((t - fine[0]) * 60)))
        hh = min(h[i], ymax)
        ww = hh * aspect
        x0 = min(max(cx[i] - ww / 2, 0), screen[0] - ww)
        y0 = min(max(cy[i] - hh / 2, 0), ymax - hh)
        boxes.append((x0, y0, x0 + ww, y0 + hh))
        for a0, a1, ax0, ay0, ax1, ay1 in c.get('avoid', []):
            if a0 <= t <= a1 and x0 < ax1 and x0 + ww > ax0 and y0 < ay1 and y0 + hh > ay0:
                print(f'WARNING: crop at route {t:.2f} s touches the avoid box {ax0}:{ay0}-{ax1}:{ay1}')
    return boxes


def fps_overlay(vals, sw, sh, k, words):
    """Race layers: the bottom gradient, per side its caption and live fps (stock grey bottom left, ours white bottom right),
    always shown (the halves are fixed)."""
    g = np.zeros((sh, sw, 4), np.uint8)
    rows = int(sh * 0.30)
    g[sh - rows:, :, 3] = (np.linspace(0, 1, rows) ** 1.6 * 200).astype(np.uint8)[:, None]
    layers = [(g, None)]
    F = lambda px: ImageFont.truetype(FONT, max(11, round(px * k)))
    cap, big, unit = F(15), F(64), F(32)
    pad = round(26 * k)
    img = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for v, col, word, right in ((vals[0], BEFORE, words[0], False), (vals[1], AFTER, words[1], True)):
        y = sh - pad
        num, gap = f'{v:.0f}', round(8 * k)
        wn, wu = d.textlength(num, font=big), d.textlength('fps', font=unit)
        x0 = sw - pad - (wn + gap + wu) if right else pad
        d.text((x0, y), num, font=big, fill=col, anchor='ls')
        d.text((x0 + wn + gap, y), 'fps', font=unit, fill=col, anchor='ls')
        y -= big.size + round(14 * k)
        d.text((sw - pad if right else pad, y + round(4 * k)), word, font=cap, fill=CAPTION, anchor='rs' if right else 'ls')
    layers.append((np.asarray(img), None))
    return layers


def fps_end(base, runs, ratio, sw, sh, k, words, strip):
    """The black results card: the ratio on top, each side's route-mean fps and p99 below (stock grey left, ours white right)."""
    fr = base.copy()
    img = Image.new('RGB', (sw, sh), (0, 0, 0))
    d = ImageDraw.Draw(img)
    F = lambda px: ImageFont.truetype(FONT, max(11, round(px * k)))
    cap, big, unit, sec, huge = F(15), F(64), F(32), F(22), F(150)
    d.text((sw // 2, round(sh * 0.30)), f'{ratio:.1f}x', font=huge, fill=AFTER, anchor='ms')
    d.text((sw // 2, round(sh * 0.30) + round(44 * k)), 'faster, route average', font=sec, fill=CAPTION, anchor='ms')
    for s, col, word, cx in ((runs[0], BEFORE, words[0], sw // 4), (runs[1], AFTER, words[1], sw * 3 // 4)):
        y = round(sh * 0.66)
        d.text((cx, y), word, font=cap, fill=CAPTION, anchor='ms')
        y += round(16 * k) + big.size
        num, gap = f"{s['fps']:.0f}", round(8 * k)
        wn, wu = d.textlength(num, font=big), d.textlength('fps', font=unit)
        x0 = cx - (wn + gap + wu) / 2
        d.text((x0, y), num, font=big, fill=col, anchor='ls')
        d.text((x0 + wn + gap, y), 'fps', font=unit, fill=col, anchor='ls')
        y += round(16 * k) + sec.size
        d.text((cx, y), f"p99 {s['p99']:.1f} ms", font=sec, fill=col, anchor='ms')
    x = sw // 2
    d.line((x, round(sh * 0.60), x, round(sh * 0.90)), fill=(60, 60, 66), width=1)
    fr[:, strip:] = np.asarray(img)
    return fr


def fps_race(a, name, cw, strip, label_px, k):
    c = RACES[name]
    sw = cw - strip
    sh = a.size or int(round(sw * 9 / 16))
    sh -= sh % 2
    caps = [run_dir(l) for l in c['cap']]
    runs = [fps_run(d) for d in caps]
    ratio = runs[1]['fps'] / runs[0]['fps']
    label = a.label or c['label'].format(x=f'{ratio:.1f}'.removesuffix('.0') + 'x')  # '5x', not '5.0x' (maintainer, 2026-10-15)
    top = round(26 * k)
    while label_px > 20 and ImageFont.truetype(FONT, label_px).getlength(label) > sh - 2 * top:
        label_px -= 1   # a label longer than the strip (22 characters at 630) shrinks until it fits with the padding at both ends
    base = np.asarray(chrome(a.theme, label, sh, cw, strip, label_px, top)).copy()
    vs = [route_start_in_video(d) for d in caps]
    t0, secs = c['t0'], c['len']
    words = c.get('words', ('STOCK', 'OPTIMIZED'))
    pw = sw // 2
    print(f'== {name}: strip label {label!r} at {label_px} px; ' + '; '.join(f"{w} {os.path.basename(d)} {r['fps']:.1f} fps p99 {r['p99']:.1f} ms route {r['secs']:.2f} s, "
                                    f"route start at video {v:.2f} s" for w, d, r, v in zip(words, caps, runs, vs))
          + f'; ratio {ratio:.3f}; race route {t0:.2f}-{t0 + secs:.2f} s + {c["end_hold"]} s card')
    follow = 'path' in c
    win, tick = c.get('fps_window', FPS_WINDOW), c.get('fps_tick', FPS_TICK)

    def open_sides(start, length):
        if follow:
            return [FollowSide(f'{d}/recording.mp4', v + start, length + 0.5) for d, v in zip(caps, vs)]
        return [RaceSide(f'{d}/recording.mp4', v + start, length + 0.5, c['crop'], None, 0, pw, sh) for d, v in zip(caps, vs)]
    sides = None if a.still else open_sides(t0, secs)
    blank = np.zeros((sh, sw - pw, 3), np.uint8)
    cache = {}

    def frame(t):
        if follow:   # the same moving box on both recordings at the same route second
            box = crop_path(c, [t], pw / sh)[0]
            fr = [side.crop(v + t, box, pw, sh) for side, v in zip(sides, vs)]
        else:
            fr = [side.frame(v + t) for side, v in zip(sides, vs)]
        vals = tuple(round(live_fps(r, t, win, tick)) for r in runs)
        if vals not in cache:
            cache.clear()
            cache[vals] = fps_overlay(vals, sw, sh, k, words)
        return compose(base, np.hstack([fr[0], blank]), np.hstack([blank, fr[1]]), cache[vals], pw, strip)
    end = fps_end(base, runs, ratio, sw, sh, k, words, strip)
    if a.still:   # --at t,t,...: one still per route second (stem-<t>.png), else the clip's middle
        stem, ext = os.path.splitext(a.still)
        ats = [float(x) for x in a.at.split(',')] if a.at else [t0 + secs / 2]
        for t in ats:
            sides = open_sides(t, 0.2)
            out = a.still if len(ats) == 1 else f'{stem}-{t:g}{ext}'
            Image.fromarray(frame(t)).save(out)
            for side in sides:
                side.close()
            print('still', out, f'at route {t:.2f} s', 'crop' if not follow else 'box %.0f:%.0f-%.0f:%.0f' % crop_path(c, [t], pw / sh)[0])
        Image.fromarray(end).save(f'{stem}-end{ext}')
        return
    if follow:   # print the path once, every second
        for t in np.arange(t0, t0 + secs + 1e-6, 1.0):
            print(f'   route {t:5.2f} s: box %.0f:%.0f-%.0f:%.0f' % crop_path(c, [t], pw / sh)[0])
    work = f'build/anim/{name}-{a.theme}-{cw}'
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f'{work}/frames')
    n_race, n_end = round(secs * 60), round(c['end_hold'] * 60)
    for i in range(n_race):
        Image.fromarray(frame(t0 + i / 60)).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
    for side in sides:
        side.close()
    for i in range(n_race, n_race + n_end):
        Image.fromarray(end).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
    print(f'== {name}: {n_race} race + {n_end} card frames {base.shape[1]}x{base.shape[0]}')
    encode(a, work, base.shape, a.out or (f'workshop-media/template-{name}.gif' if a.gif else f'workshop-media/{name}.avif'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--theme', default='light', choices=THEMES)
    ap.add_argument('--still', help='write one composed frame (2 s in, line mid-sweep at a third) to this PNG and stop')
    ap.add_argument('--crf', default='35')
    ap.add_argument('--at', help='with --still on an fps race: route seconds, comma-separated, one still each')
    ap.add_argument('--len', type=float, default=2 * (SWEEP + HOLD) + END_HOLD, help='clip seconds (default one divider cycle)')
    ap.add_argument('--size', type=int, help='square canvas N x N (the headline animation is 630)')
    ap.add_argument('--gif', action='store_true', help='write workshop-media/template-<name>.gif (gifski) instead of the AVIF')
    ap.add_argument('--gif-fps', default='25')
    ap.add_argument('--label', help='strip text instead of the VIDEOS entry\'s (e.g. PZ_Optimization)')
    ap.add_argument('--out', help='output file instead of workshop-media/<name>.avif / template-<name>.gif (one name only)')
    ap.add_argument('--font', help='TTF for the strip and the numbers (default Martian Mono)')
    ap.add_argument('--single', help='one HDR capture instead of a stitched comparison (no divider, no numbers); needs --out')
    ap.add_argument('--t0', type=float, default=0.0, help='with --single: start second')
    ap.add_argument('--crop', default='', help='with --single: x:y:w square in the capture whose centre the scene keeps')
    ap.add_argument('--numbers', help='with --single: before:after fps, drawn as in the comparison videos')
    ap.add_argument('--second', help='with --numbers: before:after second lines, e.g. "p99 16.5 ms:p99 5.8 ms"')
    ap.add_argument('--words', default='STOCK:ENHANCED', help='with --numbers: the captions over the numbers')
    ap.add_argument('--header', action='store_true', help='with --single: the label in a bar across the top instead of the strip')
    ap.add_argument('--label-px', type=int, help='with --single: the label size in px (header default 42 at 630); without --header '
                    'it also widens the strip to the header bar\'s size')
    ap.add_argument('--align', default='center', choices=('center', 'left'), help='with --header: the label centred or at the left padding')
    ap.add_argument('names', nargs='*')
    a = ap.parse_args()
    global FONT
    FONT = os.path.expanduser(a.font) if a.font else MONO
    cw = a.size or W
    # the strip (maintainer, 2026-10-06): 80 px wide with a 42 px label at 630, top-aligned at the numbers' padding, the same
    # band as --header's bar; scaled with the canvas for other sizes
    s630 = cw / 630 if a.size else W / 1260 * 1.0
    strip = round(80 * s630)
    strip += strip % 2
    label_px = a.label_px or round(42 * s630)
    k = 0.75 if a.size and a.size <= 700 else 1.0
    sw = cw - strip
    if a.single:
        if not a.out:
            raise SystemExit('--single needs --out')
        label = a.label or 'PZ_Optimization'
        if a.header:   # the bar across the top: the scene is the full width under it
            hh = round(cw * 0.127)
            hh += hh % 2
            strip, sw = 0, cw
            sh = (a.size or int(round(cw * 9 / 16)) + hh) - hh
            c_bar, c_txt = THEMES[a.theme]
            img = Image.new('RGB', (cw, hh + sh), c_bar)
            hf = ImageFont.truetype(FONT, a.label_px or round(42 * cw / 630))
            d = ImageDraw.Draw(img)
            x0, y0, x1, y1 = d.textbbox((0, 0), label, font=hf, anchor='ls')   # centre the ink, not the font's metrics
            lx = round(26 * k) - x0 if a.align == 'left' else (cw - (x1 - x0)) // 2 - x0   # left: the numbers' padding
            d.text((lx, (hh - (y1 - y0)) // 2 - y0), label, fill=c_txt, font=hf, anchor='ls')
            base = np.asarray(img).copy()
            top = hh
        else:
            sh = a.size or int(round(sw * 9 / 16))
            sh -= sh % 2
            base = np.asarray(chrome(a.theme, label, sh, cw, strip, label_px, round(26 * k))).copy()
            top = 0
        secs = a.len if '--len' in os.sys.argv else 10.0
        ov = []
        if a.numbers:
            fa, fb = (float(v) for v in a.numbers.split(':'))
            ov = numbers_overlay({'a': {'fps': fa}, 'b': {'fps': fb}}, tuple(a.second.split(':')) if a.second else None,
                                 sw, sh, k, tuple(a.words.split(':')))

        def put(fr, f):
            fr[top:, strip:] = f
            sc = fr[top:, strip:]
            for layer, _ in ov:
                al = layer[:, :, 3:4].astype(np.float32) / 255.0
                sc[:] = (sc * (1 - al) + layer[:, :, :3] * al).astype(np.uint8)
            return fr
        if a.still:
            fr = put(base.copy(), next(single_frames(os.path.expanduser(a.single), a.t0 + 2, 0.1, a.crop, sw, sh)))
            Image.fromarray(fr).save(a.still)
            print('still', a.still, fr.shape)
            return
        work = f'build/anim/single-{os.path.splitext(os.path.basename(a.out))[0]}'
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(f'{work}/frames')
        i = 0
        for f in single_frames(os.path.expanduser(a.single), a.t0, secs, a.crop, sw, sh):
            Image.fromarray(put(base.copy(), f)).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
            i += 1
        print(f'== single {a.single}: {i} frames {base.shape[1]}x{base.shape[0]} from +{a.t0} s')
        encode(a, work, base.shape, a.out)
        return
    for name in a.names or list(VIDEOS):
        if name in TRIPLES:
            triple(a, name, cw, strip, label_px, k)
            continue
        if name in RACES:
            race(a, name, cw, strip, label_px, k)
            continue
        t0, label, kind, second, *mode = VIDEOS[name]
        label = a.label or label
        video = f'workshop-media/{name}.mp4'
        n = json.loads(subprocess.run(['python3', 'harness/stitch-efficiency.py', kind, '--numbers'],
                                      capture_output=True, text=True, check=True).stdout)
        geo = n['panes']
        sh = a.size or int(round(geo['ph'] * sw / geo['pw']))
        sh -= sh % 2
        base = np.asarray(chrome(a.theme, label, sh, cw, strip, label_px, round(26 * k))).copy()
        ov = visual_overlay(second, sw, sh, k) if 'visual' in mode else numbers_overlay(n, second, sw, sh, k)
        if a.still:
            left, right = next(panes(video, t0 + 2, 0.1, geo, sw, sh, bool(a.size)))
            Image.fromarray(compose(base, left, right, ov, sw * 2 // 3, strip)).save(a.still)
            print('still', a.still, base.shape)
            return
        work = f'build/anim/{name}-{a.theme}' + (f'-{cw}' if a.size else '')
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(f'{work}/frames')
        seq = divider_positions()
        i = 0
        for left, right in panes(video, t0, a.len, geo, sw, sh, bool(a.size)):
            x = round(sw * seq[i % len(seq)])
            Image.fromarray(compose(base, left, right, ov, x, strip)).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
            i += 1
        print(f'== {name}: {i} frames {base.shape[1]}x{base.shape[0]} from +{t0} s')
        encode(a, work, base.shape, a.out or (f'workshop-media/template-{name}.gif' if a.gif else f'workshop-media/{name}.avif'))


if __name__ == '__main__':
    main()
