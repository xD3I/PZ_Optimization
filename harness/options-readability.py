#!/usr/bin/env python3
"""Is every settings page of the main menu's PZ OPTIMIZATION screen readable? (2026-10-04 as the options-tab check, the
1080p report: the "Before / after clips" switch overlapped the search line on a 1920 x 1080 window; since 2026-10-18 the
settings live in the PZ OPTIMIZATION screen and the game's Options screen has no PZ Optimization tab.)

Reads a run of the harness rig `options_read=1` (pzopt_harness.lua: the screen's readability tour through the benchmark's
BENCHREAD rig, one stop per page: home, each group, each category, each subcategory and each further page of its list in
the Everything view, Fix a problem and each problem, a search), gathers what harness/benchmark-readability.py reads
(<run>/benchread.txt from the run's console.txt, the game's screenshots pzopt-bench-ui-read-<n>-<stop>.png into
<run>/shots/ from --shots, the run dir itself or ~/Zomboid/Screenshots/) and runs it: geometry (off screen, outside its
dialog, clipped, overlapping), "..." cuts, every text read back with tesseract, and Jev's verdict, in
<run>/benchmark-readability.json.

    harness/options-readability.py <run> [--shots <dir>] [--no-jev]

The OCR helpers below (norm, ocr) are also benchmark-readability.py's.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# the English model is under ~/.local/share/tessdata on this machine (the system tessdata has none; ui-drive.py does the same)
ENV = dict(os.environ)
if "TESSDATA_PREFIX" not in ENV and os.path.isdir(os.path.expanduser("~/.local/share/tessdata")):
    ENV["TESSDATA_PREFIX"] = os.path.expanduser("~/.local/share/tessdata")


def norm(s):
    return " ".join(re.sub(r"[^a-z0-9%]+", " ", s.lower()).split())


def ocr(img):
    """One text line: grey, 3x (Lanczos), then light text -> black on white at a fixed threshold, tesseract --psm 7. Upscale
    before the threshold: thresholding the 13 px text first broke its strokes (the menu art shows through the panel)."""
    g = ImageOps.grayscale(img)
    g = g.resize((g.width * 3, g.height * 3), Image.LANCZOS)
    # light text on the dark panel -> black on white; a selected button (dark text on a white fill) the other way round
    light_bg = sum(g.get_flattened_data()) / (g.width * g.height) > 128
    g = g.point((lambda v: 0 if v < 140 else 255) if light_bg else (lambda v: 0 if v > 140 else 255))
    p = subprocess.run(["tesseract", "stdin", "stdout", "--psm", "7", "-l", "eng"], input=_png(g),
                       capture_output=True, check=False, env=ENV)
    return p.stdout.decode("utf-8", "replace").strip()


def _png(im):
    import io
    b = io.BytesIO()
    im.save(b, "PNG")
    return b.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--shots", help="where the pzopt-bench-ui-read-*.png screenshots are (default: the run, then ~/Zomboid/Screenshots)")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    run = Path(a.run)
    console = run / "console.txt"
    if not console.is_file():
        sys.exit(f"{console}: no console.txt")
    lines = [l for l in open(console, errors="replace") if "BENCHREAD" in l]
    if not lines:
        sys.exit("no BENCHREAD lines: was the run made with --flag options_read=1 (and an options_tab / options_nav flag)?")
    (run / "benchread.txt").write_text("".join(lines))
    shots = run / "shots"
    shots.mkdir(exist_ok=True)
    found = 0
    # from ~/Zomboid/Screenshots only this run's (a later tour on the machine writes the same names)
    since = (run / "run.opts").stat().st_mtime - 5 if (run / "run.opts").is_file() else 0
    for src in [Path(a.shots)] if a.shots else [run, Path.home() / "Zomboid" / "Screenshots"]:
        for f in sorted(src.glob("pzopt-bench-ui-read-*-opt-*.png")):
            if src.name == "Screenshots" and f.stat().st_mtime < since:
                continue
            if not (shots / f.name).exists():
                shutil.copy2(f, shots / f.name)
            found += 1
        if found:
            break
    print(f"{len(lines)} BENCHREAD lines, {found} screenshots -> {shots}")
    cmd = [sys.executable, os.path.join(HERE, "benchmark-readability.py"), str(run)] + (["--no-jev"] if a.no_jev else [])
    sys.exit(subprocess.call(cmd))


if __name__ == "__main__":
    main()
