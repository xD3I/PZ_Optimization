#!/usr/bin/env python3
"""The install walkthrough (2026-09-27): what a subscriber does, as frames. One storyboard, three outputs:

  --frames   the Workshop item's in-game animation: src/workshop/42/media/ui/pzopt_install/<os>/pzopt_install_<os>_NN.png (640x400, one
             set per OS: win, linux, mac) + src/workshop/42/media/lua/client/PZ_Optimization_InstallFrames.lua (file and
             hold time of each frame), played by the install helper window above the command
  --posters  the Mods list: src/workshop/42/poster-install.png (poster 0, a 512 px square the Mods screen draws at
             ~200 px next to the description: the three steps) and poster-step1..5.png (the thumbnails; hovering one
             shows it large), OS-neutral text, Windows pictures
  --card     docs/workshop/images/40-install.gif, the picture under the Workshop page's Install heading (newcard.py's
             style and GIF writer, its own layout): the Windows walkthrough across the card, before / now under it

The storyboard: 1 Copy the command (the helper window, the cursor presses Copy), 2 paste it into PowerShell / a
terminal, 3 the installer waits: quit the game (the main menu's QUIT), 4 installed, 5 start the game: Options has the
Optimizations tab. The game pictures are the game's own screenshots of harness/uninstall-e2e.sh (the flip, 1920x1080:
B-helper = main menu, A-tab = the Optimizations tab); the helper window is drawn again per OS with that OS's command
(the flip's shows the Linux one with the maintainer's home folder), in the window's own style and text
(PZ_Optimization_InstallHelper.lua); the terminal windows are drawn; every terminal line is the installers' real output.

    python3 harness/install-walkthrough.py --shots harness/runs/flip-uninstall-e2e-<ts>/shots --frames --posters --card
    (--still <png> [--os win] [--t 3.5]: one canvas frame, the layout check)
"""
import argparse
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from newcard import BG, INK, INK2, MUTED, OPT, RULE, STOCK, font, wrap, write_gif  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
W, H = 960, 600                      # the storyboard canvas
CAP_H = 84                           # caption bar at the bottom
VIEW_H = H - CAP_H
ITEM = "3805285544"
NFILES = 695                         # files the item's pzopt-classes holds (the e2e build; the installers print it)
REV = "b0bbce05d5"

OS = {
    "win": dict(
        shell="Windows PowerShell", open_how="PowerShell",
        prompt="PS C:\\Users\\player> ",
        item="C:\\Program Files (x86)\\Steam\\steamapps\\workshop\\content\\108600\\" + ITEM + "\\mods\\PZ_Optimization\\42",
        game="C:\\Program Files (x86)\\Steam\\steamapps\\common\\ProjectZomboid",
        sep="\\", console="%USERPROFILE%\\Zomboid\\console.txt"),
    "linux": dict(
        shell="Terminal", open_how="a terminal",
        prompt="player@pc:~$ ",
        item="/home/player/.local/share/Steam/steamapps/workshop/content/108600/" + ITEM + "/mods/PZ_Optimization/42",
        game="/home/player/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid",
        sep="/", console="~/Zomboid/console.txt"),
    "mac": dict(
        shell="Terminal — zsh", open_how="Terminal",
        prompt="player@Mac ~ % ",
        item="/Users/player/Library/Application Support/Steam/steamapps/workshop/content/108600/" + ITEM + "/mods/PZ_Optimization/42",
        game="/Users/player/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java",
        sep="/", console="~/Zomboid/console.txt"),
}


def command(o):
    """What the helper window shows and copies (installCommand in PZ_Optimization_InstallHelper.lua)."""
    c = OS[o]
    if o == "win":
        return 'powershell -ExecutionPolicy Bypass -File "' + c["item"] + '\\install.ps1"'
    return "bash '" + c["item"].replace("'", "'\\''") + "/install.bash'"


def output(o):
    """The installers' real lines: while the game runs, then after it closed (install.ps1 / install.sh)."""
    c = OS[o]
    waiting = f"the game is running from {c['game']}: quit it (QUIT in the main menu); this goes on once it has closed (Ctrl+C cancels)"
    done = [f"installing from folder {c['item']}{c['sep']}pzopt-classes",
            f"installed {NFILES} files into {c['game']} for game revision {REV}; projectzomboid.jar untouched"]
    return waiting, done


# --- the helper window, as PZ_Optimization_InstallHelper.lua draws it ------------------------------------------------

HELPER_LINES = [
    "The optimizations are class files for the game folder; Steam downloaded them with",
    "this item, and one command copies them in. The game does not load them from here.",
    "",
    "1. Copy the command below.",
    "2. Open {shell}, paste it, press Enter.",
    "3. Quit the game (QUIT). The installer waits for that, then copies the files.",
    "4. Start the game: the main menu now has a PZ OPTIMIZATION item.",
    "",
    "Then disable this mod in the Mods list: it only shows this window.",
    "To uninstall later: PZ OPTIMIZATION > Uninstall PZ Optimization...",
]
SHELL_NAME = {"win": "PowerShell (Start menu, type powershell, Enter)", "linux": "a terminal", "mac": "Terminal (Applications > Utilities)"}


def helper_window(screen, o, copied):
    """Draw the helper window over the 1920x1080 main menu the way the game does (font sizes of the flip capture);
    returns the window's box and the Copy button's centre."""
    d = ImageDraw.Draw(screen)
    small, medium, code = font(15), font(21, "semibold"), font(15, mono=True)
    lines = [l.format(shell=SHELL_NAME[o]) for l in HELPER_LINES]
    cmd = command(o)
    pad, sh, mh, ch = 20, 19, 26, 19
    w = max([medium.getlength("PZ Optimization is not installed yet")] + [small.getlength(l) for l in lines]
            + [code.getlength(cmd) + 16])
    w = int(min(w + 2 * pad, 1880))
    h = pad + mh + 12 + len(lines) * sh + ch + 24 + 12 + 25 + pad
    x0, y0 = (1920 - w) // 2, (1080 - h) // 2
    d.rectangle((x0, y0, x0 + w, y0 + h), fill=(3, 3, 3), outline=(102, 102, 102))
    y = y0 + pad
    d.text((x0 + pad, y), "PZ Optimization is not installed yet", font=medium, fill=(255, 217, 102))
    y += mh + 12
    for i, l in enumerate(lines):
        d.text((x0 + pad, y), l, font=small, fill=(255, 255, 255))
        y += sh
        if i == 3:
            y += 6
            d.rectangle((x0 + pad, y, x0 + w - pad, y + ch + 12), fill=(31, 31, 31), outline=(128, 128, 128))
            d.text((x0 + pad + 8, y + 5), cmd, font=code, fill=(153, 255, 153))
            y += ch + 18
    by = y0 + h - pad - 25
    buttons = []
    bx = x0 + pad
    for title in ("Copied" if copied else "Copy the command", "Open the Workshop page"):
        bw = max(140, int(small.getlength(title)) + 20)
        buttons.append((bx, by, bx + bw, by + 25, title))
        bx += bw + 10
    buttons.append((x0 + w - pad - 100, by, x0 + w - pad, by + 25, "Close"))
    for i, (a, b, c2, e, title) in enumerate(buttons):
        d.rectangle((a, b, c2, e), fill=(40, 70, 40) if (i == 0 and copied) else (8, 8, 8), outline=(200, 200, 200))
        d.text(((a + c2) / 2, (b + e) / 2), title, font=small, fill=(255, 255, 255), anchor="mm")
    cb = buttons[0]
    return (x0, y0, x0 + w, y0 + h), ((cb[0] + cb[2]) / 2, (cb[1] + cb[3]) / 2)


# --- drawing helpers ----------------------------------------------------------------------------------------------------

def cursor(d, x, y, s=1.0, pressed=False):
    """The pointer: white arrow, black outline (tip at x, y)."""
    pts = [(0, 0), (0, 21), (5, 16), (9, 25), (12, 24), (8, 15), (15, 15)]
    pts = [(x + px * s, y + py * s) for px, py in pts]
    d.polygon(pts, fill=(255, 255, 255), outline=(0, 0, 0))
    if pressed:
        d.ellipse((x - 14 * s, y - 14 * s, x + 14 * s, y + 14 * s), outline=OPT, width=max(2, int(3 * s)))


def crop_to_view(screen, box, dim=1.0):
    """The screen region box (x0, y0, x1, y1) scaled to fill the view (aspect kept, centred, bars in BG); returns the
    view image and the mapping screen -> view."""
    x0, y0, x1, y1 = box
    region = screen.crop(box)
    sc = min(W / (x1 - x0), VIEW_H / (y1 - y0))
    rw, rh = int((x1 - x0) * sc), int((y1 - y0) * sc)
    view = Image.new("RGB", (W, VIEW_H), BG)
    r = region.resize((rw, rh), Image.LANCZOS)
    if dim < 1.0:
        r = ImageEnhance.Brightness(r).enhance(dim)
    ox, oy = (W - rw) // 2, (VIEW_H - rh) // 2
    view.paste(r, (ox, oy))
    return view, (lambda px, py: (ox + (px - x0) * sc, oy + (py - y0) * sc)), sc


def terminal(o, lines, cursor_on=True):
    """A terminal window filling the view: title bar per OS, the lines wrapped at the window's width."""
    view = Image.new("RGB", (W, VIEW_H), BG)
    d = ImageDraw.Draw(view)
    m, tb = 26, 34
    x0, y0, x1, y1 = m, m, W - m, VIEW_H - m
    body = (12, 12, 12) if o == "win" else (30, 30, 30)
    d.rounded_rectangle((x0, y0, x1, y1), radius=8 if o == "mac" else 4, fill=body, outline=(80, 80, 88))
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y0 + tb), fill=(44, 44, 48) if o != "win" else (32, 32, 32))
    title = OS[o]["shell"]
    if o == "mac":
        for i, col in enumerate(((255, 95, 86), (255, 189, 46), (39, 201, 63))):
            d.ellipse((x0 + 14 + i * 22, y0 + 11, x0 + 26 + i * 22, y0 + 23), fill=col)
        d.text(((x0 + x1) / 2, y0 + tb / 2), title, font=font(16, "semibold"), fill=INK2, anchor="mm")
    else:
        d.text((x0 + 14, y0 + tb / 2), title, font=font(16, "semibold"), fill=INK2, anchor="lm")
        for i, sym in enumerate(("×", "□", "–")):
            d.text((x1 - 22 - i * 34, y0 + tb / 2), sym, font=font(18), fill=INK2, anchor="mm")
    f = font(17, mono=True)
    cw = f.getlength("M")
    per = int((x1 - x0 - 28) // cw)
    y = y0 + tb + 12
    lh = 23
    rows = []
    for text, colour in lines:
        while len(text) > per:
            rows.append((text[:per], colour))
            text = text[per:]
        rows.append((text, colour))
    maxrows = int((y1 - y - 10) // lh)
    rows = rows[-maxrows:]
    for text, colour in rows:
        d.text((x0 + 14, y), text, font=f, fill=colour)
        y += lh
    if cursor_on and rows:
        last = rows[-1][0]
        cx = x0 + 14 + f.getlength(last)
        d.rectangle((cx + 2, y - lh + 3, cx + cw, y - 4), fill=(220, 220, 220))
    return view


def caption(canvas, step, text, sub=""):
    d = ImageDraw.Draw(canvas)
    d.rectangle((0, VIEW_H, W, H), fill=(17, 17, 20))
    d.line((0, VIEW_H, W, VIEW_H), fill=RULE, width=2)
    cx, cy = 52, VIEW_H + CAP_H / 2
    d.ellipse((cx - 26, cy - 26, cx + 26, cy + 26), fill=OPT)
    d.text((cx, cy + 1), str(step), font=font(32, "bold"), fill=(10, 10, 12), anchor="mm")
    if sub:
        d.text((96, cy - 15), text, font=font(32, "semibold"), fill=INK, anchor="lm")
        d.text((96, cy + 20), sub, font=font(20), fill=INK2, anchor="lm")
    else:
        d.text((96, cy), text, font=font(34, "semibold"), fill=INK, anchor="lm")


# --- the storyboard -------------------------------------------------------------------------------------------------------

# (start s, scene); the loop is TOTAL s long
SCENES = [(0.0, "copy"), (3.0, "paste"), (6.8, "quit"), (9.8, "installed"), (12.4, "start")]
TOTAL = 16.0
MENU_BOX = (120, 470, 660, 1050)          # the main menu's item column in the 1920x1080 capture (left of the window)
QUIT_AT = (225, 1005)                      # its QUIT item
TAB_BOX = (292, 110, 1012, 500)            # Options: the tab row and the Optimizations tab's header buttons
TAB_AT = (579, 161)                        # the Optimizations tab
UNINSTALL_AT = (792, 369)                  # "Uninstall PZ Optimization..."


def ease(a, b, t0, t1, t):
    k = min(1.0, max(0.0, (t - t0) / (t1 - t0)))
    k = k * k * (3 - 2 * k)
    return a + (b - a) * k


def frame(o, t, shots):
    """The canvas at t seconds into the loop for OS o."""
    scene, start = SCENES[0][1], 0.0
    for s0, name in SCENES:
        if t >= s0:
            scene, start = name, s0
    lt = t - start
    canvas = Image.new("RGB", (W, H), BG)
    how = OS[o]["open_how"]
    if scene == "copy":
        screen = shots["menu"].copy()
        copied = lt >= 1.9
        box, (bx, by) = helper_window(screen, o, copied)
        pad = 40
        view, to_view, sc = crop_to_view(screen, (box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad))
        d = ImageDraw.Draw(view)
        tx, ty = to_view(bx, by)
        cx, cy = ease(W * 0.8, tx, 0.2, 1.6, lt), ease(VIEW_H * 0.9, ty, 0.2, 1.6, lt)
        cursor(d, cx, cy, 1.3, pressed=1.7 <= lt < 2.3)
        canvas.paste(view, (0, 0))
        caption(canvas, 1, "Copy the command", "Enable the mod once: the main menu shows this window")
    elif scene in ("paste", "quit", "installed"):
        cmd = command(o)
        waiting, done = output(o)
        prompt = OS[o]["prompt"]
        if scene == "paste":
            lines = [(prompt + (cmd if lt >= 0.9 else ""), INK)]
            if lt >= 2.0:
                lines.append((waiting, (240, 200, 90)))
            canvas.paste(terminal(o, lines, cursor_on=(lt < 2.0) or int(lt * 2) % 2 == 0), (0, 0))
            caption(canvas, 2, f"Paste it into {how}, press Enter", "It waits while the game is running")
        elif scene == "quit":
            view, to_view, sc = crop_to_view(shots["menu_raw"], MENU_BOX, dim=1.0 if lt < 2.2 else max(0.0, 1 - (lt - 2.2) / 0.5))
            d = ImageDraw.Draw(view)
            qx, qy = to_view(*QUIT_AT)
            if lt < 2.2:
                d.rectangle((qx - 40, qy - 26, qx + 120, qy + 26), outline=OPT, width=3) if lt >= 1.3 else None
                cursor(d, ease(W * 0.75, qx + 30, 0.1, 1.2, lt), ease(VIEW_H * 0.3, qy + 6, 0.1, 1.2, lt), 1.3,
                       pressed=1.4 <= lt < 2.0)
            canvas.paste(view, (0, 0))
            caption(canvas, 3, "Quit the game (QUIT)", "The installer goes on once the game has closed")
        else:
            lines = [(prompt + cmd, INK), (waiting, (240, 200, 90))]
            if lt >= 0.5:
                lines.append((done[0], INK2))
            if lt >= 1.0:
                lines.append((done[1], OPT))
            if lt >= 1.0:
                lines.append((prompt, INK))
            canvas.paste(terminal(o, lines), (0, 0))
            caption(canvas, 4, "Installed", "projectzomboid.jar is never modified")
    else:
        view, to_view, sc = crop_to_view(shots["tab"], TAB_BOX)
        d = ImageDraw.Draw(view)
        if lt >= 0.6:
            x, y = to_view(*TAB_AT)
            d.rounded_rectangle((x - 58 * sc, y - 14 * sc, x + 58 * sc, y + 14 * sc), radius=6, outline=OPT, width=4)
        if lt >= 1.6:
            x, y = to_view(*UNINSTALL_AT)
            d.rounded_rectangle((x - 92 * sc, y - 14 * sc, x + 92 * sc, y + 14 * sc), radius=6, outline=STOCK, width=3)
        canvas.paste(view, (0, 0))
        caption(canvas, 5, "Start the game: main menu > PZ OPTIMIZATION",
                "Uninstall there too, before you unsubscribe" if lt >= 1.6 else "Then disable the mod again")
    return canvas


def load_shots(d):
    d = Path(d)
    return {"menu": Image.open(d / "B-helper.png").convert("RGB"), "tab": Image.open(d / "A-tab.png").convert("RGB")}


def clean_menu(shots):
    """The main menu without the flip's own helper window (it shows the maintainer's home folder): the capture's panel
    area filled from a blurred copy of the surroundings, then every helper window is drawn fresh on top anyway."""
    m = shots["menu"]
    # the game's hand cursor left of the window: the floor beside it copied over
    m.paste(m.crop((520, 620, 560, 665)), (570, 620))
    shots["menu_raw"] = m.copy()
    box = (683, 366, 1238, 714)
    patch = m.crop((box[0] - 40, box[1] - 40, box[2] + 40, box[3] + 40)).resize((8, 6), Image.BILINEAR).resize(
        (box[2] - box[0] + 80, box[3] - box[1] + 80), Image.BICUBIC)
    m.paste(patch.crop((40, 40, 40 + box[2] - box[0], 40 + box[3] - box[1])), box[:2])
    shots["menu"] = m


# --- outputs --------------------------------------------------------------------------------------------------------------

def key_times():
    """The in-game animation's frames: (t, hold ms) at the story's beats (a PNG per beat, not per 1/10 s)."""
    beats = [(0.0, 700), (0.9, 700), (1.8, 900), (2.4, 800),                 # copy: cursor on the way, pressed, Copied
             (3.2, 900), (5.2, 1600),                                           # paste, waiting
             (7.4, 800), (8.3, 900), (9.4, 500),                                # quit: cursor on QUIT, pressed, closing
             (10.4, 700), (11.2, 1500),                                         # installing, installed
             (13.2, 1200), (14.2, 2200)]                                        # the tab, the Uninstall button
    return beats


def write_frames(shots):
    base = REPO / "src/workshop/42/media/ui/pzopt_install"
    if base.exists():
        shutil.rmtree(base)
    lua = ["-- generated by harness/install-walkthrough.py: the install walkthrough frames the helper window plays",
           "-- (media/ui/pzopt_install/<os>/NN.png, hold time in ms)", "PZOptInstallFrames = {"]
    total = 0
    for o in OS:
        (base / o).mkdir(parents=True)
        lua.append(f"    {o} = {{")
        for i, (t, ms) in enumerate(key_times()):
            im = frame(o, t, shots).resize((640, 400), Image.LANCZOS)
            im = im.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
            # a unique name: getTexture looks the bare file name up in the game's texture packs first (Texture.
            # getSharedTextureInternal), and a frame called 01.png drew the vanilla pack image "01"
            p = base / o / f"pzopt_install_{o}_{i + 1:02d}.png"
            im.save(p, optimize=True)
            total += p.stat().st_size
            lua.append(f'        {{ "media/ui/pzopt_install/{o}/pzopt_install_{o}_{i + 1:02d}.png", {ms} }},')
        lua.append("    },")
    lua.append("}")
    (REPO / "src/workshop/42/media/lua/client/PZ_Optimization_InstallFrames.lua").write_text("\n".join(lua) + "\n")
    print(f"frames: {len(key_times())} per OS, {total / 1e6:.2f} MB in all under {base}")


def write_posters(shots):
    out = REPO / "src/workshop/42"
    sq = Image.new("RGB", (512, 512), BG)
    d = ImageDraw.Draw(sq)
    d.rounded_rectangle((10, 10, 502, 502), radius=18, fill=(17, 17, 20), outline=(80, 80, 88), width=2)
    d.text((256, 64), "PZ Optimization", font=font(44, "bold"), fill=OPT, anchor="mm")
    d.text((256, 112), "How to install", font=font(30, "semibold"), fill=INK, anchor="mm")
    steps = [("1", "Enable this mod"), ("2", "Copy the command"), ("3", "Paste it, then QUIT")]
    for i, (n, text) in enumerate(steps):
        y = 196 + i * 92
        d.ellipse((40, y - 32, 104, y + 32), fill=OPT)
        d.text((72, y + 1), n, font=font(40, "bold"), fill=(10, 10, 12), anchor="mm")
        d.text((126, y), text, font=font(38, "semibold"), fill=INK, anchor="lm")
    d.text((256, 470), "Hover the pictures below for each step", font=font(21), fill=INK2, anchor="mm")
    sq.save(out / "poster-install.png", optimize=True)
    for n, t in enumerate((2.4, 5.2, 8.3, 11.2, 14.2)):
        frame("win", t, shots).save(out / f"poster-step{n + 1}.png", optimize=True)
    print(f"posters: {out}/poster-install.png, poster-step1..5.png")


# The card sits under the page's Install heading (not in the "New!" list), so it gets a layout of its own: the
# walkthrough across the whole card (the New! template's left column is ~276 px on Steam's 655 px description column,
# too small for a terminal), before / now as two lines under it, the footer.
CARD_W, CARD_PAD, CARD_X0 = 1280, 16, 32
BEFORE = "find the item's folder and type its path, close the game first, uninstall with a command before unsubscribing."
NOW = ("enable the mod once, Copy, paste, QUIT: the installer waits for the game. No window? One line: irm .../install.ps1 | iex "
       "(Linux, macOS: curl ... | bash). Uninstall: main menu > PZ OPTIMIZATION.")
FOOTER = [
    "The Windows walkthrough; Linux and macOS show a terminal. The game pictures are the game's own screenshots of the install "
    "check on the flip laptop (harness/uninstall-e2e.sh), which ran every step for real, the uninstall too.",
    "Details: github.com/xD3I/PZ_Optimization#install",
]


def card_base():
    aw = CARD_W - 2 * CARD_X0
    ah = round(aw * H / W)
    f_body, f_foot = font(24), font(20)
    tw = CARD_W - 2 * CARD_X0
    body = [("Before: ", STOCK, BEFORE), ("Now: ", OPT, NOW)]
    body_lines = []
    for label, colour, text in body:
        lines = wrap(label + text, f_body, tw)
        body_lines.append((lines, label, colour))
    foot = [(l, i == len(FOOTER) - 1) for i, t in enumerate(FOOTER) for l in wrap(t, f_foot, tw)]
    top = 110
    y_text = top + ah + 26
    n_body = sum(len(l) for l, _, _ in body_lines)
    height = y_text + n_body * 33 + 16 + len(foot) * 28 + 30
    im = Image.new("RGB", (CARD_W, height), BG)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((CARD_PAD, CARD_PAD, CARD_W - CARD_PAD, height - CARD_PAD), radius=10, fill="#111114", outline="#505058", width=1)
    d.text((CARD_X0 + 12, 66), "New! Installation", font=font(46, "bold"), fill=OPT, anchor="lm")
    d.text((CARD_W - CARD_X0 - 12, 66), "2026-09-27", font=font(22, mono=True), fill=MUTED, anchor="rm")
    y = y_text
    for lines, label, colour in body_lines:
        for i, line in enumerate(lines):
            x = CARD_X0 + 12
            if i == 0:
                d.text((x, y), label, font=font(24, "semibold"), fill=colour, anchor="lm")
                x += font(24, "semibold").getlength(label)
                line = line[len(label):]
            d.text((x, y), line, font=f_body, fill=INK2, anchor="lm")
            y += 33
    y += 16
    for line, last in foot:
        d.text((CARD_X0 + 12, y), line, font=f_foot, fill=MUTED if last else INK2, anchor="lm")
        y += 28
    return im, (CARD_X0, top, aw, ah)


def write_card(shots):
    base, (x, y, w, h) = card_base()
    fps = 10
    work = Path(tempfile.mkdtemp(prefix="pzopt-install-card-"))
    for k in range(int(TOTAL * fps)):
        im = base.copy()
        im.paste(frame("win", k / fps, shots).resize((w, h), Image.LANCZOS), (x, y))
        ImageDraw.Draw(im).rectangle((x, y, x + w - 1, y + h - 1), outline=RULE)
        im.save(work / f"{k + 1:04d}.png")
    out = str(REPO / "docs/workshop/images/40-install.gif")
    write_gif(str(work), fps, out, colours=128)
    shutil.rmtree(work)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shots", required=True)
    ap.add_argument("--frames", action="store_true")
    ap.add_argument("--posters", action="store_true")
    ap.add_argument("--card", action="store_true")
    ap.add_argument("--still")
    ap.add_argument("--card-still")
    ap.add_argument("--os", default="win")
    ap.add_argument("--t", type=float, default=1.0)
    a = ap.parse_args()
    shots = load_shots(a.shots)
    clean_menu(shots)
    if a.card_still:
        base, (x, y, w, h) = card_base()
        base.paste(frame("win", a.t, shots).resize((w, h), Image.LANCZOS), (x, y))
        base.save(a.card_still)
        print(f"wrote {a.card_still}")
        return
    if a.still:
        frame(a.os, a.t, shots).save(a.still)
        print(f"wrote {a.still}")
        return
    if a.frames:
        write_frames(shots)
    if a.posters:
        write_posters(shots)
    if a.card:
        write_card(shots)


if __name__ == "__main__":
    main()
