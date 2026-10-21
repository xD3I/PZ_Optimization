#!/usr/bin/env python3
"""Render docs/workshop/images/51-mod-compatibility-check.gif: the Workshop's "New!" card of the main menu's mod
compatibility check with its max performance / max compatibility choice (harness/newcard.py). Left half: the
in-game screenshots of the compat_check rig (runs compat-prof-a/b/c, 2026-10-02, the Java test mod of
harness/compat/make-java-fixture.sh) as captioned slides: the main menu, the check on Max performance, Max
compatibility in force after a restart, switching back (Restart game). Right half: before / this release.

    harness/queue.sh submit media --label compatcheck-card-gif --out docs/workshop/images/51-mod-compatibility-check.gif \\
        -- python3 harness/compatcheck-card-gif.py
    (--still <png> [--slide N]: one frame, the layout check)
"""
import argparse
import glob
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from newcard import INK, INK2, MUTED, OPT, PANEL, RULE, Card, font, write_gif  # noqa: E402

OUT = "docs/workshop/images/51-mod-compatibility-check.gif"
FPS = 4
SLIDE_S = 3.5

INTRO = ("The main menu has a mod compatibility check: every Java mod file of this launch, the code of ours it "
         "patches and what that changes. One click picks Max performance (now the default: every optimization "
         "stays on) or Max compatibility. Checked on the desktop with a test mod:")
ROWS = [
    ("A Java mod patching our code", "default for its settings",
     (None, "switched off"), (None, "kept, listed"), "faster"),
    ("Windows a mod draws in", "mod HUDs and inventories",
     (None, "game's rate"), (None, "reused"), "faster"),
    ("What a mod changes", "per jar file and method",
     (None, "log file"), (None, "main menu"), "new"),
    ("Max compatibility", "the 1 October behaviour",
     (None, "2 tab settings"), (None, "1 button"), "new"),
]
FOOTER = [
    "Main menu > PZ OPTIMIZATION > MOD COMPATIBILITY CHECK, "
    "also Options > Optimizations > Mod compatibility. A choice applies after Restart game; your own value for a "
    "setting on the tab still wins.",
    "Runs compat-prof-* on the desktop, 2026-10-02: github.com/xD3I/PZ_Optimization (harness/compat/make-java-fixture.sh).",
]
# (run glob, screenshot, crop x,y,w,h of the 5120x2160 screenshot, caption, the part to outline in screenshot pixels);
# positions measured on those screenshots (the dialog of run a sits 13 px higher: its text is one line shorter)
SLIDES = [
    ("compat-prof-c-2*", "pzopt-menu.png", (460, 1220, 840, 820), "Main menu: the new item", (490, 1828, 1105, 1945)),
    ("compat-prof-a-2*", "pzopt-compat.png", (2005, 742, 1110, 675), "Max performance: mods listed, nothing off",
     (2016, 801, 2660, 841)),
    ("compat-prof-b-2*", "pzopt-compat.png", (2005, 755, 1110, 650), "Max compatibility: 2 settings off",
     (2016, 814, 2660, 854)),
    ("compat-prof-c-2*", "pzopt-compat-chosen.png", (2005, 755, 1110, 650), "Switching back: Restart game",
     (2016, 814, 3104, 854)),
]


def slide_image(spec, width, height):
    run_glob, name, (cx, cy, cw, ch), _, box = spec
    runs = sorted(glob.glob(os.path.join(HERE, "runs", run_glob)))
    if not runs:
        sys.exit(f"no run {run_glob} under harness/runs")
    im = Image.open(Path(runs[-1]) / name).convert("RGB").crop((cx, cy, cx + cw, cy + ch))
    d = ImageDraw.Draw(im)
    bx0, by0, bx1, by1 = box
    d.rounded_rectangle((bx0 - cx - 6, by0 - cy - 6, bx1 - cx + 6, by1 - cy + 6), radius=8, outline=OPT, width=5)
    s = min(width / cw, height / ch)
    return im.resize((int(cw * s), int(ch * s)), Image.LANCZOS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--still")
    ap.add_argument("--slide", type=int, default=1)
    a = ap.parse_args()
    card = Card("New! Mod compatibility check", "2026-10-02", INTRO, ROWS, FOOTER, cols=("BEFORE", "THIS RELEASE"))
    x, y, mw, mh = card.media
    cap_f, step_f = font(24, "bold"), font(21, "semibold")
    pics = [slide_image(s, mw, mh - 120) for s in SLIDES]

    def frame(i):
        im = card.base()
        d = ImageDraw.Draw(im)
        d.rectangle((x, y, x + mw, y + mh), fill=PANEL)
        d.text((x, y + 16), SLIDES[i][3], font=cap_f, fill=INK, anchor="lm")
        pic = pics[i]
        im.paste(pic, (x + (mw - pic.width) // 2, y + 40))
        # the step dots under the picture: where the loop is
        dy = y + 40 + pic.height + 26
        d.line((x, dy - 14, x + mw, dy - 14), fill=RULE, width=1)
        for k, s in enumerate(SLIDES):
            cx = x + 8 + k * (mw // len(SLIDES))
            d.ellipse((cx, dy, cx + 14, dy + 14), fill=OPT if k == i else RULE)
            d.text((cx + 22, dy + 7), f"{k + 1}", font=step_f, fill=INK2 if k == i else MUTED, anchor="lm")
        return im

    if a.still:
        frame(a.slide).save(a.still)
        print(f"wrote {a.still} ({card.W}x{card.H}, media {mw}x{mh}, slide {pics[a.slide].size})")
        return
    work = Path(tempfile.mkdtemp(prefix="pzopt-newcard-compatcheck-"))
    n = 0
    for i in range(len(SLIDES)):
        im = frame(i)
        for _ in range(int(SLIDE_S * FPS)):
            n += 1
            im.save(work / f"{n:04d}.png")
    write_gif(str(work), FPS, OUT, colours=int(os.environ.get("CARD_COLOURS", "128")))
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
