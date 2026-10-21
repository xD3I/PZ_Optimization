#!/usr/bin/env python3
"""Render docs/media/updater-card.png: the Workshop's "New! In-game updater" section as one image (the
description embeds only the picture, see the release-windows skill). Same media style as
harness/preview-card.py (docs/media-style.md): near-black surface, Noto Sans, the date in Noto Sans Mono,
green for what is new. The picture is three captures of the desktop build (5120x2160, 2026-09-22): the main
menu with the item greyed out (build current) and enabled (a newer release out), and the dialog before and
after the install (docs/media/updater-menu-current.png, updater-menu.png, updater-dialog-available.png,
updater-dialog-installed.png).

    python3 harness/updater-card.py
    ffmpeg -y -i docs/media/updater-card.png -vf scale=1920:-1 -q:v 3 docs/workshop/images/15-updater.jpg
"""
from PIL import Image, ImageDraw, ImageFont

OUT = "docs/media/updater-card.png"
SHOTS = {n: "docs/media/updater-%s.png" % n for n in ("menu-current", "menu", "dialog-available", "dialog-installed")}
DATE = "2026-09-22"
BG, PANEL, RING, INK, INK2, MUTED, OPT, AMBER = "#0b0b0e", "#111114", "#505058", "#f0f0f4", "#b4b4b8", "#8a8a90", "#44de7c", "#e8b04a"
FONTS = "/usr/share/fonts/noto/"


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)

TITLE, BODY, NOTE, MONO, CAP = (font("NotoSans-Bold.ttf", 60), font("NotoSans-Regular.ttf", 28), font("NotoSans-Regular.ttf", 26),
                                font("NotoSansMono-Regular.ttf", 32), font("NotoSans-Bold.ttf", 26))

W = 2100
PAD, X0 = 40, 88
inner = W - 2 * X0
GAP = 24

LINES = [
    (BODY, INK2, "UPDATE sits on the main menu's PZ OPTIMIZATION screen, greyed out while your build is current."),
    (BODY, INK2, "Once per boot it asks the GitHub releases for a newer build of your game revision; when one is out the item lights up,"),
    (BODY, INK2, "and Update now downloads it, replaces the installed files and asks to restart. No script after the first install."),
    (NOTE, MUTED, "Only the files the installer wrote are replaced (pzopt-installed.txt); the game's own files, saves and options stay. Off in"),
    (NOTE, MUTED, "Options > Optimizations > Updates. Windows, Linux and macOS."),
]

# the picture: the two menus side by side on the left (current / newer release out), the two dialogs stacked on the right
shots = {n: Image.open(p).convert("RGB") for n, p in SHOTS.items()}
for n in ("menu-current", "menu"):
    shots[n] = shots[n].crop((0, 0, 640, shots[n].height))   # the menu column only, the scene right of it goes
menu_h = 780
menu_w = shots["menu"].width
dlg_w = max(shots["dialog-available"].width, shots["dialog-installed"].width)
# scale so that two menus + gap + the dialogs column fill the inner width
pic_w = 2 * menu_w + GAP + dlg_w
s = inner / pic_w
menus = [shots[n].resize((round(menu_w * s), round(menu_h * s)), Image.LANCZOS) for n in ("menu-current", "menu")]
DGAP = CAP.size + 18   # room for the second dialog's caption between the two
dlg_s = (round(menu_h * s) - DGAP) / (shots["dialog-available"].height + shots["dialog-installed"].height)
dlgs = [shots[n].resize((round(shots[n].width * dlg_s), round(shots[n].height * dlg_s)), Image.LANCZOS)
        for n in ("dialog-available", "dialog-installed")]
CAPS = ["build current", "a newer release is out", "Update now", "installed: restart to load it"]

TOP = 170
y_text = TOP
for f, _, _ in LINES:
    y_text += f.size + 10
SHOT_Y = y_text + 28 + CAP.size + 12
pic_h = menus[0].height
FOOT = [
    "Captured on the 2026-09-22 desktop build (5120x2160). The check is one request to api.github.com per boot; nothing is downloaded",
    "before the click. A hand-unpacked copy without pzopt-installed.txt gets a link to the release page instead.",
]
H = SHOT_Y + pic_h + 18 + len(FOOT) * (NOTE.size + 8) + 24 + PAD

im = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(im)
d.rounded_rectangle((PAD, PAD, W - PAD, H - PAD), radius=10, fill=PANEL, outline=RING, width=1)
d.text((X0, 66), "New! In-game updater", font=TITLE, fill=OPT)
d.text((W - X0, 84), DATE, font=MONO, fill=MUTED, anchor="ra")
y = TOP
for f, colour, text in LINES:
    d.text((X0, y), text, font=f, fill=colour)
    y += f.size + 10

x = X0
cap_y = SHOT_Y - CAP.size - 12
for i, m in enumerate(menus):
    d.text((x, cap_y), CAPS[i], font=CAP, fill=AMBER if i == 0 else OPT)
    d.rounded_rectangle((x - 1, SHOT_Y - 1, x + m.width, SHOT_Y + m.height), radius=4, outline=RING, width=1)
    im.paste(m, (x, SHOT_Y))
    x += m.width + (GAP if i == 0 else GAP)
dx = x
d.text((dx, cap_y), CAPS[2], font=CAP, fill=INK2)
dy = SHOT_Y
for i, dlg in enumerate(dlgs):
    d.rounded_rectangle((dx - 1, dy - 1, dx + dlg.width, dy + dlg.height), radius=4, outline=RING, width=1)
    im.paste(dlg, (dx, dy))
    dy += dlg.height + DGAP
    if i == 0:
        d.text((dx, dy - CAP.size - 12), CAPS[3], font=CAP, fill=OPT)

fy = SHOT_Y + pic_h + 18
for line in FOOT:
    d.text((X0, fy), line, font=NOTE, fill=MUTED)
    fy += NOTE.size + 8
im.save(OUT)
print(OUT, im.size)
