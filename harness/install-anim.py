#!/usr/bin/env python3
"""The install walkthrough in the Workshop animation template (2026-10-07): two 630x630 AVIFs for the page's Install
section, made the way the README's Install section says (Method A, the Workshop item's helper window + its install
command; by hand, the Workshop item's unpacked build copied in: the same files as Method C's release zip), Windows pictures.

  install-script  1. SUBSCRIBE (Steam's Workshop page, drawn), 2. ENABLE PZ_Optimization in the game's Mods list (desktop
                  captures of the real screen, job 9051), 3. COPY the command (the helper window), 4. PASTE it into
                  PowerShell (it waits while the game runs), 5. QUIT the game, 6. DONE (the installer's own lines), 7. PLAY
  install-manual  1. SUBSCRIBE, 2. FIND the item's files (Steam: Manage > Browse local files opens the game folder; up to
                  steamapps via the address bar, then workshop > content > 108600 > 3805285544 > mods > PZ_Optimization >
                  42 > pzopt-classes), 3. COPY everything there (Ctrl+A, Ctrl+C), 4. PASTE into the game folder (Browse
                  local files again, Ctrl+V), 5. PLAY
PLAY is the page headline's footage (efficiency-anim.py --single: the same capture, start and crop).

The template (harness/efficiency-anim.py, the maintainer's 2026-10-06 specimen): light theme, the black 80 px strip on the
left with the label ("2 Minute Install", the maintainer's 2026-10-07 wording, both animations) in Martian Mono 42 px,
rotated, top-aligned at the numbers' padding; the scene right of it; bottom left over the dark gradient, where the
comparison cards put their numbers, the step as "1." (grey) + the big word and two detail lines, Martian Mono; no step
counter. A virtual screen per step and a camera that eases over it, cross-fades between steps.

Game pictures: the game's own screenshot of a desktop run (queue job 9038 instanim-menu `--flag compat_check=1`, an empty
-Dpzopt.userOptionsFile; copy in workshop-media/install-shots/menu-9038.png): the main menu with the installed-only items
(HIDE PERFORMANCE OVERLAY, PZ OPTIMIZATION UPDATE, ... COMPATIBILITY CHECK) taken out, as the menu is before the install.
The helper window is drawn as src/workshop/42/media/lua/client/PZ_Optimization_InstallHelper.lua lays it out (its
walkthrough frames from src/workshop/42/media/ui/pzopt_install/win/); PowerShell, Steam's menu and Explorer
are drawn; every terminal line is install.ps1's own output.

    python3 harness/install-anim.py --menu <pzopt-menu.png> [--still png --t S] [--gif] install-script install-manual
    (an encode: run it as a queue `media` job)
"""
import argparse
import importlib.util
import os
import shutil

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location('effanim', os.path.join(REPO, 'harness/efficiency-anim.py'))
EA = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(EA)

NOTO = '/usr/share/fonts/noto'
SIZE, STRIP, LABEL_PX, K = 630, 80, 42, 0.75
SW, SH = SIZE - STRIP, SIZE
FPS, FADE = 60, 0.35
CAPTION, WHITE, DETAIL = (190, 190, 196), (240, 240, 244), (200, 200, 206)

OSES = {   # the helper's command per OS (PZ_Optimization_InstallHelper.lua installCommand) and the installers' own lines
    'win': dict(item=r'C:\Program Files (x86)\Steam\steamapps\workshop\content\108600\3805285544\mods\PZ_Optimization\42',
                game=r'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid', sep='\\', prompt='PS C:\\Users\\player> ',
                shell='PowerShell (Start menu, type powershell, Enter)', paste='into PowerShell, press Enter',
                console='C:\\Users\\player\\Zomboid\\console.txt', uninstall=' -Uninstall'),
    'linux': dict(item='/home/player/.local/share/Steam/steamapps/workshop/content/108600/3805285544/mods/PZ_Optimization/42',
                  game='/home/player/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid', sep='/',
                  prompt='player@pc:~$ ', shell='a terminal', paste='into a terminal, press Enter', console='~/Zomboid/console.txt',
                  uninstall=' --uninstall'),
    'mac': dict(item='/Users/player/Library/Application Support/Steam/steamapps/workshop/content/108600/3805285544/mods/PZ_Optimization/42',
                game='/Users/player/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java',
                sep='/', prompt='player@Mac ~ % ', shell='Terminal (Applications > Utilities)', paste='into Terminal, press Enter',
                console='~/Zomboid/console.txt', uninstall=' --uninstall'),
}
OS = 'win'
ITEM = OSES['win']['item']
GAME = OSES['win']['game']
REV, NFILES = '4a0e9546ec', 1064   # the 5f910d9 release (2026-10-07): pzopt-files.txt lists 1064 files
COMMAND = f'powershell -ExecutionPolicy Bypass -File "{ITEM}\\install.ps1"'
PROMPT = 'PS C:\\Users\\player> '
WAITING = f'the game is running from {GAME}: quit it (QUIT in the main menu); this goes on once it has closed (Ctrl+C cancels)'
DONE = [f'installing from folder {ITEM}\\pzopt-classes',
        f'installed {NFILES} files into {GAME} for game revision {REV}; projectzomboid.jar untouched',
        "launch from Steam; C:\\Users\\player\\Zomboid\\console.txt shows one '[pzopt] loaded override ... active' line per class",
        f'settings: Options > PZ Optimization in the game, or {GAME}\\pzopt.properties']


def set_os(o):
    """Rebind the command, prompt and installer lines to one OS (install.ps1 on Windows, install.sh elsewhere)."""
    global OS, ITEM, GAME, COMMAND, PROMPT, WAITING, DONE
    OS, c = o, OSES[o]
    ITEM, GAME, PROMPT = c['item'], c['game'], c['prompt']
    if o == 'win':
        COMMAND = f'powershell -ExecutionPolicy Bypass -File "{ITEM}\\install.ps1"'
    else:
        COMMAND = "bash '" + ITEM.replace("'", "'\\''") + "/install.bash'"
    WAITING = (f'the game is running from {GAME}: quit it (QUIT in the main menu); this goes on once it has closed '
               '(Ctrl+C cancels)')
    sep = c['sep']
    DONE = [f'installing from folder {ITEM}{sep}pzopt-classes',
            f'installed {NFILES} files into {GAME} for game revision {REV}; projectzomboid.jar untouched',
            f"launch from Steam; {c['console']} shows one '[pzopt] loaded override ... active' line per class",
            f'settings: Options > PZ Optimization in the game, or {GAME}{sep}pzopt.properties']


DET_PX = 16


def set_canvas(w, h, strip, label_px, k, det_px):
    """The canvas: w x h with a strip of `strip` px (the AVIFs 630 x 630 / 80; the helper's frames 960 x 600 / 64)."""
    global SIZE, SW, SH, STRIP, LABEL_PX, K, DET_PX
    SIZE, SH, STRIP, LABEL_PX, K, DET_PX = w, h, strip, label_px, k, det_px
    SW = w - strip
    _play.clear()


HEADLINE = os.path.expanduser('~/Videos/Project Zomboid/Video_2026-10-06_16-23-03.mp4')   # the page headline's capture
HEADLINE_T0, HEADLINE_CROP = 23.3, '1750:464:1620'                                       # its window and square
_play = []


def play_frames(secs):
    """The headline animation's frames (efficiency-anim.py --single, same capture, start and crop), tone-mapped at the
    scene's size, 60 fps; read once and shared by both walkthroughs' PLAY step."""
    if not _play:
        _play.extend(Image.fromarray(f.copy()) for f in EA.single_frames(HEADLINE, HEADLINE_T0, secs + 0.2, HEADLINE_CROP, SW, SH))
    return _play


def headline(lt):
    fr = play_frames(4.0)
    return fr[min(len(fr) - 1, int(lt * FPS))], None, None


def font(px, weight='Regular', mono=False):
    return ImageFont.truetype(f"{NOTO}/NotoSans{'Mono' if mono else ''}-{weight}.ttf", px)


def mfont(px):
    return ImageFont.truetype(EA.MONO, px)


def ease(a, b, t0, t1, t):
    k = min(1.0, max(0.0, (t - t0) / (t1 - t0)))
    k = 0.5 - 0.5 * np.cos(np.pi * k)
    return a + (b - a) * k


def ease_box(b0, b1, t0, t1, t):
    return tuple(ease(x, y, t0, t1, t) for x, y in zip(b0, b1))


# --- the game screens (4096x1691 screenshots, the game's UI at about twice its 1080p size) --------------------------------

MENU_ROWS = {'OPTIONS': 1085, 'MODS': 1197, 'WORKSHOP': 1253, 'CREDITS': 1309, 'QUIT': 1519}   # text tops in pzopt-menu.png
STOCK_ROWS = {'OPTIONS': 1085, 'MODS': 1141, 'WORKSHOP': 1197, 'CREDITS': 1253, 'QUIT': 1325}  # the menu without our items
MENU_X = 462


def stock_menu(shot):
    """The main menu as it is before the install: the column cleared (a min filter keeps the dark room, drops the
    letters) and the stock items pasted back at the stock spacing."""
    m = shot.copy()
    bg = shot.filter(ImageFilter.MinFilter(15)).filter(ImageFilter.GaussianBlur(6))
    box = (MENU_X - 20, 1070, 1120, 1580)
    m.paste(bg.crop(box), box[:2])
    for name, y in MENU_ROWS.items():
        row = shot.crop((MENU_X - 10, y - 8, MENU_X + 330, y + 30))
        m.paste(row, (MENU_X - 10, STOCK_ROWS[name] - 8))
    return m


HELPER_LINES = [   # PZ_Optimization_InstallHelper.lua as of the 5f910d9 release: (line, command box under it)
    ("The optimizations are class files for the game folder; Steam downloaded them with", None),
    ("this item, and one command copies them in. The game does not load them from here.", None),
    ("", None),
    ("1. Copy the command below.", 'install'),
    ("2. Open {shell}, paste it, press Enter.", None),
    ("3. Quit the game (QUIT). The installer waits for that, then copies the files.", None),
    ("4. Start the game: the main menu now has a PZ OPTIMIZATION item.", None),
    ("", None),
    ("Then disable this mod in the Mods list: it only shows this window.", None),
    ("To uninstall later: Options > PZ Optimization > Uninstall PZ Optimization...,", None),
    ("or double-click Uninstall-PZ-Optimization.cmd in the game folder (Steam: Manage >", None),
    ("Browse local files). Installed, but no PZ OPTIMIZATION item? This removes it:", 'uninstall'),
]


_frame01 = {}


def helper_frame_01(o):
    """The helper's own first walkthrough frame, as the window shows it (read once: --helper-frames rewrites the files)."""
    if o not in _frame01:
        _frame01[o] = Image.open(f'{REPO}/src/workshop/42/media/ui/pzopt_install/{o}/pzopt_install_{o}_01.png').convert('RGB')
    return _frame01[o]


def helper_window(screen, copied):
    """PZOptInstallHelper's layout (pad 20, the walkthrough frames 480x300 at k = 0.75, a command box under line 4 and
    the uninstall command under the last line, 25 px buttons) on the 4096x1691 menu; returns the window box and the
    Copy button's centre."""
    d = ImageDraw.Draw(screen, 'RGBA')
    small, medium, code = font(18), font(24, 'Medium'), font(18, mono=True)
    sh_, mh, ch = 25, 32, 25
    pad, aw, ah = 20, 480, 300
    cmds = {'install': COMMAND, 'uninstall': COMMAND + OSES[OS]['uninstall']}
    lines = [(l.format(shell=OSES[OS]['shell']), b) for l, b in HELPER_LINES]
    w = max([aw, medium.getlength('PZ Optimization is not installed yet')] + [small.getlength(l) for l, _ in lines]
            + [code.getlength(c) + 16 for c in cmds.values()])
    w = int(w + 2 * pad)
    h = pad + mh + 12 + ah + 12 + len(lines) * sh_ + len(cmds) * (ch + 24) + 12 + 25 + pad
    x0, y0 = (screen.width - w) // 2, (screen.height - h) // 2
    d.rectangle((x0, y0, x0 + w, y0 + h), fill=(0, 0, 0, 235), outline=(255, 255, 255, 102))
    y = y0 + pad
    d.text((x0 + pad, y), 'PZ Optimization is not installed yet', font=medium, fill=(255, 217, 102))
    y += mh + 12
    fr = helper_frame_01(OS)
    fx = x0 + (w - aw) // 2
    screen.paste(fr.resize((aw, ah), Image.LANCZOS), (fx, y))
    d.rectangle((fx, y, fx + aw, y + ah), outline=(128, 128, 128))
    y += ah + 12
    for line, box in lines:
        d.text((x0 + pad, y), line, font=small, fill=(255, 255, 255))
        y += sh_
        if box:
            y += 6
            d.rectangle((x0 + pad, y, x0 + w - pad, y + ch + 12), fill=(31, 31, 31), outline=(128, 128, 128))
            d.text((x0 + pad + 8, y + 6), cmds[box], font=code, fill=(153, 255, 153))
            y += ch + 18
    by = y0 + h - pad - 25
    bx, buttons = x0 + pad, []
    for title, minw in (('Copied' if copied else 'Copy the command', 140), ('Copy the uninstall command', 180),
                        ('Open the Workshop page', 140)):
        bw = max(minw, int(small.getlength(title)) + 20)
        buttons.append((bx, by, bx + bw, by + 25, title))
        bx += bw + 10
    buttons.append((x0 + w - pad - 100, by, x0 + w - pad, by + 25, 'Close'))
    for (a, b, c, e, title) in buttons:
        d.rectangle((a, b, c, e), fill=(8, 8, 8), outline=(200, 200, 200))
        d.text(((a + c) / 2, (b + e) / 2), title, font=small, fill=(255, 255, 255), anchor='mm')
    cb = buttons[0]
    return (x0, y0, x0 + w, y0 + h), ((cb[0] + cb[2]) / 2, (cb[1] + cb[3]) / 2)


# --- drawn windows ----------------------------------------------------------------------------------------------------

def wrap_chars(text, per):
    rows = []
    while len(text) > per:
        rows.append(text[:per])
        text = text[per:]
    return rows + [text]


def caption_buttons(d, x1, yc, fs=1.0, col=(220, 220, 220)):
    """Windows' minimise / maximise / close, drawn (Noto has no glyphs for them)."""
    r = 5 * fs
    cx = x1 - 24 * fs   # close
    d.line((cx - r, yc - r, cx + r, yc + r), fill=col, width=max(1, round(fs)))
    d.line((cx - r, yc + r, cx + r, yc - r), fill=col, width=max(1, round(fs)))
    cx = x1 - 70 * fs   # maximise
    d.rectangle((cx - r, yc - r, cx + r, yc + r), outline=col, width=max(1, round(fs)))
    cx = x1 - 116 * fs  # minimise
    d.line((cx - r, yc, cx + r, yc), fill=col, width=max(1, round(fs)))


def tri(d, x, y, s, col, down=False):
    """A small solid triangle pointing right (or down) with its centre at x, y."""
    if down:
        d.polygon([(x - s, y - s / 2), (x + s, y - s / 2), (x, y + s / 2)], fill=col)
    else:
        d.polygon([(x - s / 2, y - s), (x - s / 2, y + s), (x + s / 2, y)], fill=col)


def file_icon(d, x, y, folder, s=1.0):
    """Explorer's folder (yellow, with its tab) or a page; x, y = the icon's left centre."""
    if folder:
        d.rectangle((x, y - 5 * s, x + 7 * s, y - 3 * s), fill=(232, 190, 80))
        d.rectangle((x, y - 3 * s, x + 16 * s, y + 7 * s), fill=(240, 200, 90))
    else:
        d.polygon([(x + 2 * s, y - 8 * s), (x + 10 * s, y - 8 * s), (x + 14 * s, y - 4 * s), (x + 14 * s, y + 8 * s),
                   (x + 2 * s, y + 8 * s)], outline=(200, 200, 200), fill=(60, 60, 64))


def key_chord(d, cx, cy, keys):
    """Key caps with '+' between them, centred on cx, cy (a shortcut the step presses)."""
    f = font(20, 'Medium')
    widths = [max(46, f.getlength(k) + 28) for k in keys]
    gap = 34
    x = cx - (sum(widths) + gap * (len(keys) - 1)) / 2
    for i, (k, w) in enumerate(zip(keys, widths)):
        d.rounded_rectangle((x, cy - 22, x + w, cy + 22), radius=7, fill=(232, 232, 236), outline=(140, 140, 146), width=2)
        d.text((x + w / 2, cy), k, font=f, fill=(20, 20, 24), anchor='mm')
        x += w
        if i < len(keys) - 1:
            d.text((x + gap / 2, cy), '+', font=font(22, 'Bold'), fill=(235, 235, 240), anchor='mm')
            x += gap


def win_frame(d, box, title, fs=1.0, dark=True):
    """A Windows 11 dark window: rounded, a title bar with the caption buttons; returns the client area's top."""
    x0, y0, x1, y1 = box
    tb = round(40 * fs)
    d.rounded_rectangle(box, radius=round(8 * fs), fill=(32, 32, 32) if dark else (243, 243, 243), outline=(70, 70, 74))
    d.text((x0 + round(14 * fs), y0 + tb / 2), title, font=font(round(15 * fs)), fill=(230, 230, 230), anchor='lm')
    caption_buttons(d, x1, y0 + tb / 2, fs)
    return y0 + tb


def terminal(screen, box, lines, cursor_on=True, fs=1.0):
    """Windows Terminal with a Windows PowerShell tab (Linux: a plain terminal, macOS: Terminal with its traffic lights):
    the lines wrapped at the window's width, the last rows that fit."""
    d = ImageDraw.Draw(screen)
    x0, y0, x1, y1 = box
    tb = round(40 * fs)
    if OS != 'win':
        d.rounded_rectangle(box, radius=round(10 * fs), fill=(30, 30, 30), outline=(80, 80, 88))
        d.rectangle((x0 + 1, y0 + 1, x1 - 1, y0 + tb), fill=(48, 48, 52))
        title = 'player@pc: ~' if OS == 'linux' else 'player \u2014 zsh'
        d.text(((x0 + x1) / 2, y0 + tb / 2), title, font=font(round(15 * fs), 'Medium'), fill=(225, 225, 228), anchor='mm')
        if OS == 'mac':
            for i, col in enumerate(((255, 95, 86), (255, 189, 46), (39, 201, 63))):
                cx = x0 + round((20 + i * 22) * fs)
                d.ellipse((cx - 6 * fs, y0 + tb / 2 - 6 * fs, cx + 6 * fs, y0 + tb / 2 + 6 * fs), fill=col)
        else:
            caption_buttons(d, x1, y0 + tb / 2, fs)
        _term_text(d, box, tb, lines, cursor_on, fs)
        return
    d.rounded_rectangle(box, radius=round(8 * fs), fill=(12, 12, 12), outline=(70, 70, 74))
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y0 + tb), fill=(32, 32, 32))
    tab = (x0 + round(8 * fs), y0 + round(6 * fs), x0 + round(250 * fs), y0 + tb)
    d.rounded_rectangle(tab, radius=round(6 * fs), fill=(12, 12, 12))
    d.text((tab[0] + round(12 * fs), (tab[1] + tab[3]) / 2), 'Windows PowerShell', font=font(round(15 * fs)), fill=(235, 235, 235), anchor='lm')
    tx, ty, r = tab[2] - 16 * fs, (tab[1] + tab[3]) / 2, 4 * fs
    d.line((tx - r, ty - r, tx + r, ty + r), fill=(170, 170, 170), width=max(1, round(fs)))
    d.line((tx - r, ty + r, tx + r, ty - r), fill=(170, 170, 170), width=max(1, round(fs)))
    d.text((tab[2] + round(22 * fs), (tab[1] + tab[3]) / 2), '+', font=font(round(20 * fs)), fill=(200, 200, 200), anchor='mm')
    caption_buttons(d, x1, y0 + tb / 2, fs)
    _term_text(d, box, tb, lines, cursor_on, fs)


def _term_text(d, box, tb, lines, cursor_on, fs):
    x0, y0, x1, y1 = box
    f = font(round(20 * fs), mono=True)
    cw, lh = f.getlength('M'), round(27 * fs)
    per = int((x1 - x0 - 28 * fs) // cw)
    rows = []
    for text, colour in lines:
        rows += [(r, colour) for r in wrap_chars(text, per)]
    y = y0 + tb + round(12 * fs)
    rows = rows[-int((y1 - y - 10) // lh):]
    for text, colour in rows:
        d.text((x0 + 14 * fs, y), text, font=f, fill=colour)
        y += lh
    if cursor_on and rows:
        cx = x0 + 14 * fs + f.getlength(rows[-1][0])
        d.rectangle((cx + 2, y - lh + 4, cx + cw, y - 3), fill=(204, 204, 204))


def wallpaper(w, h):
    """A dark desktop: a soft blue bloom on near-black."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r = np.sqrt(((xx - w * 0.62) / w) ** 2 + ((yy - h * 0.55) / h) ** 2)
    k = np.clip(1 - r * 1.6, 0, 1) ** 2
    rgb = np.stack([10 + 30 * k, 14 + 55 * k, 26 + 110 * k], -1)
    return Image.fromarray(rgb.astype(np.uint8))


# --- the steps --------------------------------------------------------------------------------------------------------
# Each step: (seconds, caption word, big word, detail lines, frame function lt -> (screen, camera box, cursor)); a camera
# box is (x0, y0, width) in screen pixels at the scene's aspect; cursor = (x, y, pressed) in screen pixels or None.

def win_box(x0, y0, w):
    """A window box at the scene's aspect (the camera frames it edge to edge: nothing of the desktop shows)."""
    return (x0, y0, x0 + w, y0 + round(w * SH / SW))


def frame_cam(box):
    """The camera that shows exactly this window box."""
    return (box[0], box[1], box[2] - box[0])


def cam(cx, top, w):
    """A camera of width w whose top edge is at `top`, centred on cx."""
    return (cx - w / 2, top, w)


# --- step 1 of both: Subscribe on the Workshop page (Steam's overlay browser), drawn; the item's own preview GIF ----------

PREVIEW = os.path.join(REPO, 'docs/workshop/images/00-preview.gif')
STEAM_WIN = win_box(380, 100, 760)
_sub = {}


def workshop_page(subscribed):
    """A 1920x1080 desktop with Steam's Workshop page of the item: breadcrumb, title, the preview, the Subscribe button
    (green "+ Subscribe", after the click "Subscribed" with a tick). Returns the screen and the button's centre."""
    if subscribed in _sub:
        return _sub[subscribed]
    s = wallpaper(1920, 1080)
    d = ImageDraw.Draw(s)
    x0, y0, x1, y1 = STEAM_WIN
    d.rectangle(STEAM_WIN, fill=(27, 40, 56), outline=(60, 70, 84))
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y0 + 34), fill=(23, 26, 33))
    caption_buttons(d, x1, y0 + 17, 0.9, (190, 196, 204))
    d.rectangle((x0 + 1, y0 + 35, x1 - 1, y0 + 92), fill=(23, 29, 37))
    for i, t in enumerate(('STORE', 'LIBRARY', 'COMMUNITY')):
        d.text((x0 + 40 + i * 150, y0 + 64), t, font=font(20, 'Bold'), fill=(26, 159, 255) if t == 'COMMUNITY' else (220, 225, 230), anchor='lm')
    y = y0 + 120
    d.text((x0 + 40, y), 'Project Zomboid  \u203a  Workshop  \u203a  Items', font=font(15), fill=(140, 160, 180))
    d.text((x0 + 40, y + 34), 'PZ_Optimization', font=font(34, 'Medium'), fill=(255, 255, 255))
    try:
        pv = Image.open(PREVIEW).convert('RGB').resize((330, 330), Image.LANCZOS)
    except Exception:
        pv = Image.new('RGB', (330, 330), (40, 50, 60))
    s.paste(pv, (x0 + 40, y + 100))
    bx0, by0 = x0 + 390, y + 100
    d.rectangle((bx0, by0, x1 - 40, by0 + 150), fill=(16, 24, 34))
    d.text((bx0 + 20, by0 + 22), 'Subscribe to download', font=font(18, 'Medium'), fill=(255, 255, 255))
    d.text((bx0 + 20, by0 + 54), 'PZ_Optimization', font=font(16), fill=(140, 160, 180))
    btn = (bx0 + 20, by0 + 86, bx0 + 210, by0 + 130)
    if subscribed:
        d.rectangle(btn, fill=(80, 130, 30))
        cx, cy = btn[0] + 32, (btn[1] + btn[3]) / 2
        d.line((cx - 9, cy, cx - 3, cy + 7, cx + 10, cy - 8), fill=(255, 255, 255), width=4)
        d.text((btn[0] + 52, cy), 'Subscribed', font=font(18, 'Medium'), fill=(255, 255, 255), anchor='lm')
    else:
        d.rectangle(btn, fill=(117, 176, 34))
        d.text(((btn[0] + btn[2]) / 2, (btn[1] + btn[3]) / 2), '+  Subscribe', font=font(18, 'Medium'), fill=(255, 255, 255), anchor='mm')
    for i, line in enumerate(('Class overrides for Build 42:', 'smoother frames, faster loads,', 'optional graphics. Needs the',
                              'one-time install.')):
        d.text((bx0, by0 + 180 + i * 26), line, font=font(16), fill=(190, 200, 210))
    _sub[subscribed] = (s, ((btn[0] + btn[2]) / 2, (btn[1] + btn[3]) / 2))
    return _sub[subscribed]


def subscribe(lt):
    s, (bx, by) = workshop_page(lt >= 1.6)
    w = STEAM_WIN
    return s, frame_cam(w), (ease(w[0] + 600, bx, 0.3, 1.3, lt), ease(w[1] + 600, by, 0.3, 1.3, lt), 1.4 <= lt < 1.9)


SUBSCRIBE = (2.8, 'SUBSCRIBE', ['to PZ_Optimization on the Steam', 'Workshop: Steam downloads it.'], subscribe)


MODS_SHOTS = [os.path.join(REPO, f'workshop-media/install-shots/mods-desk-{n}.png') for n in (1, 2)]
MODS_TICK = (294, 300)   # the PZ_Optimization row's tick box in the 5120x2160 desktop captures


class Script:
    def __init__(self, menu):
        self.menu = stock_menu(menu)
        self.cache = {}
        # the game's Mods screen (desktop captures of job 9051, mods_shot=1: the row selected, then ticked); the camera
        # stays on the list: the info panel shows the capture machine's home path and the overlay sits bottom right
        self.mods = [Image.open(f).convert('RGB') for f in MODS_SHOTS]

    def screen(self, key, make):
        if key not in self.cache:
            self.cache[key] = make()
        return self.cache[key]

    def with_helper(self, copied):
        def make():
            s = self.menu.copy()
            self.helper_box, self.copy_at = helper_window(s, copied)
            return s
        return self.screen(('helper', copied), make)

    def steps(self):
        self.with_helper(False)
        hb, (bx, by) = self.helper_box, self.copy_at
        portrait = SH >= SW
        term = win_box(1500, 300, 800 if portrait else 1000)
        self.term = term

        def copy(lt):
            s = self.with_helper(lt >= 2.0)
            # inside the window from its left edge, the Copy button at ~70 % of the height (above the caption); what is
            # below the window falls under the caption's opaque bottom
            cw = 620 if portrait else 760
            c = (hb[0] + 4, by - 0.70 * cw * SH / SW, cw)
            cx, cy = ease(hb[0] + 700, bx, 0.9, 1.8, lt), ease(hb[3] - 200, by, 0.9, 1.8, lt)
            return s, c, (cx, cy, 1.85 <= lt < 2.35)

        def enable(lt):
            c = cam(700, 160, 860)
            tx, ty = MODS_TICK
            return self.mods[1 if lt >= 1.8 else 0], c, \
                (ease(tx + 500, tx, 0.6, 1.5, lt), ease(ty + 400, ty, 0.6, 1.5, lt), 1.6 <= lt < 2.1)

        def paste(lt):
            def make(stage):
                s = self.with_helper(True).copy()
                ImageDraw.Draw(s, 'RGBA').rectangle((0, 0, s.width, s.height), fill=(0, 0, 0, 200))
                lines = [(PROMPT + (COMMAND if stage >= 1 else ''), (204, 204, 204))]
                if stage >= 2:
                    lines.append((WAITING, (204, 204, 204)))
                terminal(s, term, lines, cursor_on=stage < 2 or stage == 3)
                return s
            stage = 0 if lt < 0.9 else 1 if lt < 1.8 else 2 + (int(lt * 2) % 2 == 0)
            s = self.screen(('paste', stage), lambda: make(stage))
            return s, frame_cam(term), None

        def quit_(lt):
            s = self.with_helper(True)
            qy = STOCK_ROWS['QUIT'] + 10
            c = cam(MENU_X + 260, qy - 0.62 * 720 * SH / SW, 720)
            cx, cy = ease(MENU_X + 420, MENU_X + 40, 0.3, 1.2, lt), ease(qy - 220, qy + 4, 0.3, 1.2, lt)
            if lt > 1.8:   # the game closes
                k = min(1.0, (lt - 1.8) / 0.5)
                s = Image.blend(s, Image.new('RGB', s.size, (0, 0, 0)), k)
            return s, c, (cx, cy, 1.3 <= lt < 1.8) if lt < 1.9 else None

        def done(lt):
            def make(stage):
                s = wallpaper(*self.menu.size)
                lines = [(PROMPT + COMMAND, (204, 204, 204)), (WAITING, (204, 204, 204))]
                lines += [(l, (204, 204, 204)) for l in DONE[:stage]]
                if stage >= len(DONE):
                    lines.append((PROMPT, (204, 204, 204)))
                terminal(s, term, lines, cursor_on=stage >= len(DONE))
                return s
            stage = 0 if lt < 0.5 else 1 if lt < 1.0 else len(DONE)
            s = self.screen(('done', stage), lambda: make(stage))
            return s, frame_cam(term), None


        return [
            SUBSCRIBE,
            (3.0, 'ENABLE', ['PZ_Optimization in the Mods list', '(main menu > MODS), then Accept.'], enable),
            (3.2, 'COPY', ['the install command: the main menu', 'shows it with a Copy button.'], copy),
            (3.2, 'PASTE', ['into PowerShell (Linux, macOS: a', 'terminal). It waits for the game.'], paste),
            (2.4, 'QUIT', ['the game (QUIT): the installer', 'goes on once it has closed.'], quit_),
            (2.8, 'DONE', [f'{NFILES} files in the game folder;', 'projectzomboid.jar untouched.'], done),
            (3.0, 'PLAY', ['Options > PZ Optimization.', 'Then disable the mod again.'], headline),
        ]


class Manual:
    """By hand from the Workshop download (2026-10-07, the maintainer: the item carries the same files as the release zip,
    unpacked in its 42/pzopt-classes/): find that folder from the game folder Steam opens, copy its contents into the game
    folder. 1920x1080 Windows desktop, UI at 1x. Folder contents as on this machine's Steam library (Windows names)."""
    W_, H_ = 1920, 1080
    STEAM = win_box(360, 150, 640)
    EXPLORER = win_box(520, 120, 700)
    F, T = 'File folder', 'Text Document'
    BASE = ['This PC', 'Local Disk (C:)', 'Program Files (x86)', 'Steam', 'steamapps']
    GAME = BASE + ['common', 'ProjectZomboid']
    GAME_ROWS = [('fmod', F), ('jre64', F), ('media', F), ('org', F), ('pzopt', F), ('se', F), ('zombie', F),
                 ('projectzomboid.jar', 'Executable Jar File'), ('ProjectZomboid64.exe', 'Application'),
                 ('ProjectZomboid64.json', 'JSON File'), ('pzopt-files.txt', T),
                 ('uninstall-pz-optimization.bash', 'BASH File'), ('Uninstall-PZ-Optimization.cmd', 'Windows Command Script')]
    NEW = {'fmod', 'org', 'pzopt', 'se', 'zombie', 'pzopt-files.txt', 'uninstall-pz-optimization.bash',
           'Uninstall-PZ-Optimization.cmd'}
    # the walk from steamapps down to the item's unpacked build: (folder entered, its rows, the row opened next)
    WALK = [
        ('steamapps', [('common', F), ('downloading', F), ('shadercache', F), ('sourcemods', F), ('temp', F), ('workshop', F),
                       ('appmanifest_108600.acf', 'ACF File'), ('libraryfolders.vdf', 'VDF File')], 'workshop'),
        ('workshop', [('content', F), ('downloads', F), ('temp', F), ('appworkshop_108600.acf', 'ACF File')], 'content'),
        ('content', [('108600', F)], '108600'),
        ('108600', [('3119788162', F), ('3397561666', F), ('3498294068', F), ('3600186927', F), ('3606878738', F),
                    ('3805285544', F)], '3805285544'),
        ('3805285544', [('mods', F)], 'mods'),
        ('mods', [('PZ_Optimization', F)], 'PZ_Optimization'),
        ('PZ_Optimization', [('42', F)], '42'),
        ('42', [('media', F), ('pzopt-classes', F), ('install.bash', 'BASH File'), ('install.ps1', 'Windows PowerShell Script'),
                ('mod.info', 'INFO File'), ('poster.png', 'PNG File'), ('poster-install.png', 'PNG File')], 'pzopt-classes'),
    ]
    ITEM_ROWS = [('fmod', F), ('media', F), ('org', F), ('pzopt', F), ('se', F), ('zombie', F), ('pzopt-files.txt', T),
                 ('uninstall-pz-optimization.bash', 'BASH File'), ('Uninstall-PZ-Optimization.cmd', 'Windows Command Script')]

    def __init__(self, menu):
        self.desk = wallpaper(self.W_, self.H_)
        self.cache = {}

    def screen(self, key, make):
        if key not in self.cache:
            self.cache[key] = make()
        return self.cache[key]

    def steam_menu(self, sub):
        def make():
            s = self.desk.copy()
            d = ImageDraw.Draw(s)
            x0, y0, x1, y1 = self.STEAM
            d.rectangle(self.STEAM, fill=(23, 26, 33), outline=(60, 64, 72))
            d.rectangle((x0 + 1, y0 + 1, x1 - 1, y0 + 34), fill=(23, 29, 37))
            d.text((x0 + 14, y0 + 17), 'LIBRARY', font=font(14, 'Bold'), fill=(220, 225, 230), anchor='lm')
            y = y0 + 60
            d.text((x0 + 16, y - 14), 'ALL GAMES', font=font(12, 'Medium'), fill=(140, 150, 160))
            d.rectangle((x0 + 8, y, x0 + 260, y + 30), fill=(62, 76, 98))
            d.text((x0 + 18, y + 15), 'Project Zomboid', font=font(15), fill=(240, 240, 240), anchor='lm')
            # the context menu of the game, Manage > Browse local files
            mx, my = x0 + 160, y + 22
            items = ['Play', 'Add to Favorites', 'Add to', 'Manage', 'Properties...']
            d.rectangle((mx, my, mx + 210, my + 12 + 32 * len(items)), fill=(61, 67, 77), outline=(90, 96, 106))
            for i, it in enumerate(items):
                iy = my + 6 + i * 32
                if it == 'Manage':
                    d.rectangle((mx + 2, iy, mx + 208, iy + 32), fill=(220, 222, 228))
                    self.manage_at = (mx + 80, iy + 16)
                d.text((mx + 14, iy + 16), it, font=font(15), fill=(30, 32, 36) if it == 'Manage' else (220, 224, 230), anchor='lm')
                if it in ('Add to', 'Manage'):
                    tri(d, mx + 192, iy + 16, 5, (30, 32, 36) if it == 'Manage' else (220, 224, 230))
            sx, sy = mx + 210, my + 6 + 3 * 32
            subitems = ['Add desktop shortcut', 'Browse local files', 'Hide this game', 'Uninstall']
            if sub:
                d.rectangle((sx, sy, sx + 220, sy + 12 + 32 * len(subitems)), fill=(61, 67, 77), outline=(90, 96, 106))
                for i, it in enumerate(subitems):
                    iy = sy + 6 + i * 32
                    if it == 'Browse local files':
                        d.rectangle((sx + 2, iy, sx + 218, iy + 32), fill=(220, 222, 228))
                    d.text((sx + 14, iy + 16), it, font=font(15),
                           fill=(30, 32, 36) if it == 'Browse local files' else (220, 224, 230), anchor='lm')
            self.browse_at = (sx + 90, sy + 6 + 32 + 16)
            return s
        return self.screen(('steam', sub), make)

    def row_at(self, i):
        """Screen centre of list row i (the name's middle)."""
        return self.EXPLORER[0] + 120, self.EXPLORER[1] + 40 + 86 + i * 32 + 15

    def folder(self, crumbs, rows, hover=None, all_sel=False, new=frozenset(), keys=None):
        """Explorer on a folder: the address bar (collapsed with a leading « when too long, as Explorer does), the rows;
        hover = the row under the pointer, all_sel = Ctrl+A, new = rows just pasted (green), keys = a chord drawn under
        the list. Remembers the crumbs' screen x for clicks."""
        key = ('folder', tuple(crumbs), tuple(rows), hover, all_sel, tuple(sorted(new)), keys)

        def make():
            s = self.desk.copy()
            d = ImageDraw.Draw(s)
            top = win_frame(d, self.EXPLORER, crumbs[-1])
            x0, y0, x1, y1 = self.EXPLORER
            d.rounded_rectangle((x0 + 12, top + 6, x1 - 12, top + 40), radius=6, fill=(45, 45, 45))
            f, sep = font(13), ' \u203a '
            shown = list(crumbs)
            while f.getlength(sep.join(shown)) > x1 - x0 - 70 and len(shown) > 2:
                shown.pop(0)
            text = ('\u00ab ' if len(shown) < len(crumbs) else '') + sep.join(shown)
            d.text((x0 + 26, top + 23), text, font=f, fill=(225, 225, 225), anchor='lm')
            y = top + 60
            d.text((x0 + 40, y), 'Name', font=font(13), fill=(170, 170, 170), anchor='lm')
            d.text((x0 + 470, y), 'Type', font=font(13), fill=(170, 170, 170), anchor='lm')
            y += 18
            d.line((x0 + 12, y, x1 - 12, y), fill=(60, 60, 60))
            y += 8
            for name, kind in rows:
                if all_sel:
                    d.rectangle((x0 + 14, y, x1 - 14, y + 30), fill=(32, 72, 118))
                elif name in new:
                    d.rectangle((x0 + 14, y, x1 - 14, y + 30), fill=(30, 62, 44))
                elif name == hover:
                    d.rectangle((x0 + 14, y, x1 - 14, y + 30), fill=(58, 58, 62))
                file_icon(d, x0 + 40, y + 15, kind == self.F)
                d.text((x0 + 66, y + 15), name, font=font(15, 'Medium' if name == 'projectzomboid.jar' else 'Regular'),
                       fill=(240, 240, 240), anchor='lm')
                d.text((x0 + 470, y + 15), kind, font=font(14), fill=(170, 170, 170), anchor='lm')
                y += 32
            if keys:
                key_chord(d, (x0 + x1) / 2, y + 50, keys)   # under the list, above the caption
            return s
        return self.screen(key, make)

    def crumb_x(self, crumbs, name):
        """Screen x of a crumb's middle in the (uncollapsed) address bar."""
        f, sep = font(13), ' \u203a '
        i = crumbs.index(name)
        before = sep.join(crumbs[:i]) + (sep if i else '')
        return self.EXPLORER[0] + 26 + f.getlength(before) + f.getlength(name) / 2

    def game_rows(self, pasted):
        return [r for r in self.GAME_ROWS if pasted or r[0] not in self.NEW]

    def browse(self, lt, t_manage, t_sub, t_click):
        """Steam's Manage > Browse local files, the pointer on Manage by t_manage, on Browse local files by t_sub,
        pressed until t_click."""
        st = self.STEAM
        s = self.steam_menu(lt >= t_manage)
        mx, my = self.manage_at
        bx, by = self.browse_at
        if lt < t_manage:
            p = (ease(st[0] + 120, mx, 0.1, t_manage - 0.1, lt), ease(st[1] + 90, my, 0.1, t_manage - 0.1, lt), False)
        else:
            p = (ease(mx, bx, t_manage + 0.1, t_sub, lt), ease(my, by, t_manage + 0.1, t_sub, lt), t_sub <= lt < t_click)
        return s, frame_cam(st), p

    def steps(self):
        self.steam_menu(True)
        ex = self.EXPLORER
        ecam = frame_cam(ex)
        item_crumbs = self.BASE + ['workshop', 'content', '108600', '3805285544', 'mods', 'PZ_Optimization', '42', 'pzopt-classes']
        HOP = 0.7

        def find(lt):
            if lt < 2.0:
                return self.browse(lt, 0.8, 1.4, 1.8)
            if lt < 2.8:   # the game folder; the pointer goes to "steamapps" in the address bar and clicks it
                gx, gy = self.crumb_x(self.GAME, 'steamapps'), ex[1] + 40 + 23
                return self.folder(self.GAME, self.game_rows(False)), ecam, \
                    (ease(ex[0] + 300, gx, 2.0, 2.5, lt), ease(ex[1] + 300, gy, 2.0, 2.5, lt), 2.55 <= lt < 2.8)
            k = int((lt - 2.8) / HOP)
            if k < len(self.WALK):
                name, rows, nxt = self.WALK[k]
                crumbs = self.BASE + [w[0] for w in self.WALK[1:k + 1]]
                i = [r[0] for r in rows].index(nxt)
                rx, ry = self.row_at(i)
                if k == 0:
                    px, py = self.crumb_x(self.GAME, 'steamapps'), ex[1] + 40 + 23
                else:
                    prows = self.WALK[k - 1][1]
                    px, py = self.row_at([r[0] for r in prows].index(self.WALK[k - 1][2]))
                into = lt - 2.8 - k * HOP
                hover = nxt if into > HOP * 0.5 else None
                p = (ease(px, rx, 0.08, HOP * 0.5, into), ease(py, ry, 0.08, HOP * 0.5, into), HOP * 0.62 <= into < HOP * 0.85)
                return self.folder(crumbs, rows, hover=hover), ecam, p
            return self.folder(item_crumbs, self.ITEM_ROWS), ecam, None

        def copy(lt):
            keys = ('Ctrl', 'A') if 0.3 <= lt < 1.3 else ('Ctrl', 'C') if 1.6 <= lt < 2.7 else None
            return self.folder(item_crumbs, self.ITEM_ROWS, all_sel=lt >= 0.8, keys=keys), ecam, None

        def paste(lt):
            if lt < 1.6:
                return self.browse(lt, 0.5, 1.0, 1.35)
            keys = ('Ctrl', 'V') if 1.9 <= lt < 2.9 else None
            pasted = lt >= 2.4
            return self.folder(self.GAME, self.game_rows(pasted), new=self.NEW if pasted else frozenset(), keys=keys), ecam, None

        return [
            SUBSCRIBE,
            (2.8 + 8 * 0.7 + 0.8, 'FIND', ['the mod files. Steam: Manage >', 'Browse local files, up to steamapps,',
                           'workshop\\content\\108600\\3805285544'], find),
            (3.0, 'COPY', ['everything in ...\\42\\pzopt-classes', '(Ctrl+A, Ctrl+C).'], copy),
            (3.8, 'PASTE', ['into the game folder (Browse local', 'files again), Ctrl+V. Skip existing.'], paste),
            (3.0, 'PLAY', ['Options > PZ Optimization.', 'To remove: the files in pzopt-files.txt.'], headline),
        ]


# --- compositing ------------------------------------------------------------------------------------------------------

def view(screen, box):
    """The camera box (x0, y0, w) of the screen, scaled to the scene; outside the screen is black."""
    x0, y0, w = box
    h = w * SH / SW
    if x0 >= 0 and y0 >= 0 and x0 + w <= screen.width and y0 + h <= screen.height:
        return screen.resize((SW, SH), Image.LANCZOS, box=(x0, y0, x0 + w, y0 + h))
    pad = int(max(w, h)) + 4
    big = Image.new('RGB', (screen.width + 2 * pad, screen.height + 2 * pad), (0, 0, 0))
    big.paste(screen, (pad, pad))
    return big.resize((SW, SH), Image.LANCZOS, box=(x0 + pad, y0 + pad, x0 + pad + w, y0 + pad + h))


def draw_cursor(img, x, y, pressed):
    d = ImageDraw.Draw(img)
    pts = [(0, 0), (0, 21), (5, 16), (9, 25), (12, 24), (8, 15), (15, 15)]
    d.polygon([(x + px * 1.1, y + py * 1.1) for px, py in pts], fill=(255, 255, 255), outline=(0, 0, 0))
    if pressed:
        d.ellipse((x - 15, y - 15, x + 15, y + 15), outline=(255, 255, 255), width=3)


def step_overlay(n, total, big, detail):
    """The comparison cards' number block, bottom left: "n." and the big word, two detail lines under it."""
    g = np.zeros((SH, SW, 4), np.uint8)
    rows = int(SH * 0.5)
    g[SH - rows:, :, 3] = (np.clip(np.linspace(0, 1.35, rows), 0, 1) ** 1.3 * 242).astype(np.uint8)[:, None]
    img = Image.fromarray(g, 'RGBA')
    d = ImageDraw.Draw(img)
    pad = round(26 * K)
    f_det, f_big = mfont(DET_PX), mfont(round(64 * K))
    y = SH - pad
    for line in reversed(detail):
        d.text((pad, y), line, font=f_det, fill=DETAIL, anchor='ls')
        y -= f_det.size + 7
    y -= 6
    num = f'{n}.'
    d.text((pad, y), num, font=f_big, fill=CAPTION, anchor='ls')
    d.text((pad + f_big.getlength(num + ' '), y), big, font=f_big, fill=WHITE, anchor='ls')
    return img


def render(name, menu, a):
    story = (Script if name == 'install-script' else Manual)(menu)
    steps = story.steps()
    label = '2 Minute Install'
    base = EA.chrome('light', label, SH, SIZE, STRIP, LABEL_PX, round(26 * K))
    overlays = [step_overlay(i + 1, len(steps), big, det) for i, (_, big, det, _) in enumerate(steps)]
    starts = np.cumsum([0] + [s[0] for s in steps])
    total = starts[-1]

    def scene(i, lt):
        s, box, cur = steps[i][3](lt)
        img = s.copy() if box is None else view(s, box)
        if cur:
            x0, y0, w = box
            sc = SW / w
            draw_cursor(img, (cur[0] - x0) * sc, (cur[1] - y0) * sc, cur[2])
        img = img.convert('RGBA')
        img.alpha_composite(overlays[i])
        return img.convert('RGB')

    def frame(t):
        i = int(np.searchsorted(starts, t, side='right') - 1)
        i = min(i, len(steps) - 1)
        lt = t - starts[i]
        img = scene(i, lt)
        left = steps[i][0] - lt
        if left < FADE:   # cross-fade into the next step (the last one into the first: the loop)
            j = (i + 1) % len(steps)
            img = Image.blend(img, scene(j, 0.0), 1 - left / FADE)
        out = base.copy()
        out.paste(img, (STRIP, 0))
        return out

    if a.still:
        frame(a.t).save(a.still)
        print('still', a.still, f'{name} at {a.t:.2f} s of {total:.1f}')
        return
    work = f'build/anim/{name}'
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f'{work}/frames')
    n = int(round(total * FPS))
    for k in range(n):
        frame(k / FPS).save(f'{work}/frames/f{k:05d}.png', compress_level=1)
    print(f'== {name}: {n} frames, {total:.1f} s')
    EA.encode(a, work, (SH, SIZE), a.out or (f'workshop-media/template-{name}.gif' if a.gif else f'workshop-media/{name}.avif'))


# --- the Workshop item's in-game walkthrough (the helper window plays it above the command) ---------------------------

HELPER_BEATS = [   # (Script step, lt s, hold ms, number, big word, detail): the helper's own four steps
    ('COPY', 1.2, 700, 1, 'COPY', 'with the button in this window'),
    ('COPY', 1.95, 600, 1, 'COPY', 'with the button in this window'),
    ('COPY', 2.7, 1100, 1, 'COPY', 'with the button in this window'),
    ('PASTE', 1.3, 1000, 2, 'PASTE', None),          # None: this OS's paste line
    ('PASTE', 2.6, 1800, 2, 'PASTE', None),
    ('QUIT', 1.45, 1300, 3, 'QUIT', 'the game: the installer waits for it'),
    ('DONE', 1.6, 2200, 3, 'QUIT', 'then the installer copies the files'),
    ('PLAY', 1.0, 2400, 4, 'PLAY', 'Options > PZ Optimization'),
]


def helper_frames(menu, a):
    """src/workshop/42/media/ui/pzopt_install/<os>/pzopt_install_<os>_NN.png (640 x 400, the size the helper window
    expects: it draws them at 480 x 300) + PZ_Optimization_InstallFrames.lua, from the Script storyboard on a 960 x 600
    canvas (strip 64 px, bigger type for the small window). Replaces harness/install-walkthrough.py --frames."""
    set_canvas(960, 600, 64, 34, 0.875, 26)
    base = EA.chrome('light', '2 Minute Install', SH, SIZE, STRIP, LABEL_PX, round(26 * K))
    root = os.path.join(REPO, 'src/workshop/42/media/ui/pzopt_install')
    lua = ['-- generated by harness/install-anim.py --helper-frames: the install walkthrough frames the helper window plays',
           '-- (media/ui/pzopt_install/<os>/pzopt_install_<os>_NN.png, hold time in ms)', 'PZOptInstallFrames = {']
    total = 0
    for o in ('win', 'linux', 'mac'):
        helper_frame_01(o)
    for o in ('win', 'linux', 'mac'):
        set_os(o)
        steps = {st[1]: st[3] for st in Script(menu).steps()}
        if not a.still:
            shutil.rmtree(os.path.join(root, o), ignore_errors=True)
            os.makedirs(os.path.join(root, o))
        lua.append(f'    {o} = {{')
        for i, (step, lt, ms, n, big, det) in enumerate(HELPER_BEATS):
            s, box, cur = steps[step](lt)
            img = s.copy() if box is None else view(s, box)
            if cur:
                x0, y0, w = box
                sc = SW / w
                draw_cursor(img, (cur[0] - x0) * sc, (cur[1] - y0) * sc, cur[2])
            img = img.convert('RGBA')
            img.alpha_composite(step_overlay(n, 4, big, [det or OSES[o]['paste']]))
            out = base.copy()
            out.paste(img.convert('RGB'), (STRIP, 0))
            if a.still:
                if o == a.os and i == a.beat:
                    out.save(a.still)
                    print('still', a.still, o, step, lt)
                continue
            # a unique name: getTexture looks the bare file name up in the game's texture packs first
            f = os.path.join(root, o, f'pzopt_install_{o}_{i + 1:02d}.png')
            out.resize((640, 400), Image.LANCZOS).quantize(colors=128, method=Image.Quantize.MEDIANCUT,
                                                           dither=Image.Dither.NONE).save(f, optimize=True)
            total += os.path.getsize(f)
            lua.append(f'        {{ "media/ui/pzopt_install/{o}/pzopt_install_{o}_{i + 1:02d}.png", {ms} }},')
        lua.append('    },')
    lua.append('}')
    set_os('win')
    if a.still:
        return
    with open(os.path.join(REPO, 'src/workshop/42/media/lua/client/PZ_Optimization_InstallFrames.lua'), 'w') as fh:
        fh.write('\n'.join(lua) + '\n')
    print(f'helper frames: {len(HELPER_BEATS)} per OS, {total / 1e6:.2f} MB in all under {root}')


# --- the Mods list posters of the Workshop item (scripts/workshop.sh lists them in mod.info) ------------------------------

POSTER_BEATS = [   # (Script step, lt s, big word, detail): poster-step1..5, Windows pictures, OS-neutral words
    ('ENABLE', 2.4, 'ENABLE', 'this mod here, then Accept'),
    ('COPY', 2.7, 'COPY', 'the command the main menu shows'),
    ('PASTE', 2.6, 'PASTE', 'into PowerShell or a terminal, Enter'),
    ('DONE', 1.6, 'QUIT', 'the game: the installer copies the files'),
    ('PLAY', 1.0, 'PLAY', 'Options > PZ Optimization'),
]


def posters(menu, a):
    """src/workshop/42/poster-install.png (512 x 512, drawn ~200 px beside the description: the steps as a list over a
    darkened headline frame) and poster-step1..5.png (960 x 600, the thumbnails; hover = large). Replaces
    harness/install-walkthrough.py --posters."""
    out_dir = os.path.join(REPO, 'src/workshop/42')
    set_canvas(960, 600, 64, 34, 0.875, 26)
    set_os('win')
    base = EA.chrome('light', '2 Minute Install', SH, SIZE, STRIP, LABEL_PX, round(26 * K))
    steps = {st[1]: st[3] for st in Script(menu).steps()}
    for i, (step, lt, big, det) in enumerate(POSTER_BEATS):
        sc_, box, cur = steps[step](lt)
        img = sc_.copy() if box is None else view(sc_, box)
        if cur:
            x0, y0, w = box
            draw_cursor(img, (cur[0] - x0) * SW / w, (cur[1] - y0) * SW / w, cur[2])
        img = img.convert('RGBA')
        img.alpha_composite(step_overlay(i + 1, len(POSTER_BEATS), big, [det]))
        out = base.copy()
        out.paste(img.convert('RGB'), (STRIP, 0))
        f = a.still if (a.still and a.beat == i + 1) else os.path.join(out_dir, f'poster-step{i + 1}.png')
        if a.still and a.beat != i + 1:
            continue
        out.save(f, optimize=True)
        print('poster', f)
    # poster 0: the list
    set_canvas(512, 512, 64, 34, 0.75, 16)
    sq = EA.chrome('light', '2 Minute Install', SH, SIZE, STRIP, LABEL_PX, 20)
    bg = play_frames(1.2)[30].convert('RGB')
    bg = Image.blend(bg, Image.new('RGB', bg.size, (0, 0, 0)), 0.62)
    d = ImageDraw.Draw(bg)
    f_big, f_cap = mfont(46), mfont(17)
    d.text((26, 40), 'HOW TO INSTALL', font=f_cap, fill=CAPTION, anchor='ls')
    y = 118
    for i, (_, _, big, _) in enumerate(POSTER_BEATS):
        num = f'{i + 1}.'
        d.text((26, y), num, font=f_big, fill=CAPTION, anchor='ls')
        d.text((26 + f_big.getlength(num + ' '), y), big, font=f_big, fill=WHITE, anchor='ls')
        y += 84
    sq.paste(bg, (STRIP, 0))
    f = a.still if (a.still and a.beat == 0) else os.path.join(out_dir, 'poster-install.png')
    if not a.still or a.beat == 0:
        sq.save(f, optimize=True)
        print('poster', f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--menu', required=True, help='the main menu screenshot (pzopt-menu.png of a compat_check=1 run)')
    ap.add_argument('--still')
    ap.add_argument('--t', type=float, default=1.0)
    ap.add_argument('--gif', action='store_true')
    ap.add_argument('--gif-fps', default='25')
    ap.add_argument('--crf', default='35')
    ap.add_argument('--out')
    ap.add_argument('--helper-frames', action='store_true', help="write the Workshop item's in-game walkthrough frames")
    ap.add_argument('--posters', action='store_true', help="write the Workshop item's Mods list posters")
    ap.add_argument('--os', default='win', help='with --helper-frames --still: the OS of the frame')
    ap.add_argument('--beat', type=int, default=0, help='with --helper-frames --still: the frame index')
    ap.add_argument('names', nargs='*')
    a = ap.parse_args()
    menu = Image.open(a.menu).convert('RGB')
    if a.helper_frames:
        helper_frames(menu, a)
        return
    if a.posters:
        posters(menu, a)
        return
    for name in a.names:
        render(name, menu, a)


if __name__ == '__main__':
    main()
