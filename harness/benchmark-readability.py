#!/usr/bin/env python3
"""Does any text of the built-in benchmark's screens overflow? (2026-10-18)

Reads a `harness/benchmark-run.sh ui:read` run (the game's tour of every benchmark screen: BENCHREAD lines in
<run>/benchread.txt, one per text drawn in one frame of each stop, with the text's rectangle, its element's, its
dialog's and the screen's; the game's own screenshot of each stop in <run>/shots/pzopt-bench-ui-read-<n>-<screen>.png)
and, optionally, the HUD / cool-down notice lines of a benchmark run made with devBenchmarkUi=read (--hud <run>). Per text:
  - off_screen: past an edge of the screen,
  - outside_dialog: past its dialog (the panel the tour opened, the main menu column, the PZ Optimization screen),
  - clipped: wider than the list or rich-text box that draws it through its own stencil (cut off on screen),
  - overlaps: two different texts of one stop on top of each other,
  - cut: shortened with "..." to fit its column,
  - ocr: the text read back from the screenshot with tesseract (the options-readability.py reader) and compared.
Asks Jev (numbers only) whether every text is readable. Writes <run>/benchmark-readability.json and, for texts with a
finding, <run>/benchmark-readability-issues.png (crops).

    harness/benchmark-readability.py <run> [--hud <run>]... [--no-jev]
"""
import argparse
import difflib
import importlib.util
import json
import os
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_spec = importlib.util.spec_from_file_location("options_readability", os.path.join(HERE, "options-readability.py"))
_opt = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_opt)
ocr, norm = _opt.ocr, _opt.norm

LINE = re.compile(r"BENCHREAD stop=(\d+) screen=(\S+) kind=(\S+) font=(\S+) x=(-?\d+) y=(-?\d+) w=(\d+) h=(\d+) "
                  r"el=(-?\d+),(-?\d+),(\d+),(\d+) clip=(\d) root=(-?\d+),(-?\d+),(\d+),(\d+) scr=(\d+),(\d+) text=(.*)$")
TOL = 1        # px: the game rounds positions
OCR_OK = 0.75  # similarity of the read-back text to the drawn one


def parse(path, hud=False):
    out = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = LINE.search(line.rstrip("\n"))
        if not m:
            continue
        g = m.groups()
        t = {"stop": int(g[0]), "screen": g[1], "kind": g[2], "font": g[3], "x": int(g[4]), "y": int(g[5]), "w": int(g[6]),
             "h": int(g[7]), "el": tuple(map(int, g[8:12])), "clip": g[12] == "1", "root": tuple(map(int, g[13:17])),
             "scr": (int(g[17]), int(g[18])), "text": g[19], "hud": hud}
        # a DebugLog copy of the line ends with the logger's own "." (console.txt has none): "..." then reads "....",
        # which the cut test still sees; OCR compares letters and digits only
        out.append(t)
    return out


def inside_x(t, r):
    return t["x"] >= r[0] - TOL and t["x"] + t["w"] <= r[0] + r[2] + TOL


def inside_y(t, r):
    return t["y"] >= r[1] - TOL and t["y"] + t["h"] <= r[1] + r[3] + TOL


def visible(t):
    """A text a scrolling list or rich-text box has scrolled out of its rectangle is not on screen (not an overflow)."""
    return not t["clip"] or inside_y(t, t["el"])


def overlap(a, b):
    ox = min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"])
    oy = min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"])
    return (ox, oy) if ox > 2 and oy > 2 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--hud", action="append", default=[], help="a benchmark run made with devBenchmarkUi=read (its HUD lines)")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    run = Path(a.run)
    texts = parse(run / "benchread.txt")
    for h in a.hud:
        hp = Path(h)
        src = hp / "benchread.txt" if (hp / "benchread.txt").is_file() else None
        lines = []
        for f in [src] if src else sorted(list(hp.glob("console*.txt")) + list(hp.glob("logs/*DebugLog*"))):
            lines += parse(f, hud=True)
        texts += lines
    shots = {}
    for p in (run / "shots").glob("pzopt-bench-ui-read-*.png"):
        m = re.match(r"pzopt-bench-ui-read-(\d+)-(.+)\.png", p.name)
        if m:
            shots[int(m.group(1))] = p
    stops = sorted({(t["stop"], t["screen"]) for t in texts if not t["hud"]})
    findings = {"off_screen": [], "outside_dialog": [], "clipped": [], "overlaps": [], "cut": [], "ocr_failed": []}
    seen_cut = set()
    by_stop = {}
    for t in texts:
        by_stop.setdefault((t["hud"], t["stop"], t["screen"]), []).append(t)
    ocr_n = ocr_ok = 0
    crops = []
    for (hud, stop, screen), ts in sorted(by_stop.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        vis = [t for t in ts if visible(t)]
        img = Image.open(shots[stop]).convert("RGB") if not hud and stop in shots else None
        for t in vis:
            tag = f"{'hud' if hud else stop}:{screen}"
            sw, sh = t["scr"]
            if t["x"] < -TOL or t["y"] < -TOL or t["x"] + t["w"] > sw + TOL or t["y"] + t["h"] > sh + TOL:
                findings["off_screen"].append({"where": tag, "text": t["text"], "x": t["x"], "w": t["w"], "screen_w": sw})
            elif not (inside_x(t, t["root"]) and inside_y(t, t["root"])):
                findings["outside_dialog"].append({"where": tag, "text": t["text"], "x": t["x"], "w": t["w"], "dialog": t["root"]})
            if t["clip"] and not inside_x(t, t["el"]):
                findings["clipped"].append({"where": tag, "text": t["text"], "past_px": t["x"] + t["w"] - (t["el"][0] + t["el"][2])})
            if t["text"].rstrip().endswith("...") and (screen, t["text"]) not in seen_cut:
                seen_cut.add((screen, t["text"]))
                findings["cut"].append({"where": tag, "text": t["text"]})
            box = None
            if img is not None:
                box = (max(0, t["x"] - 2), max(0, t["y"]), min(img.width, t["x"] + t["w"] + 2), min(img.height, t["y"] + t["h"]))
                if box[2] - box[0] < 4 or box[3] - box[1] < 4:
                    findings["off_screen"].append({"where": tag, "text": t["text"], "outside_screenshot": list(box), "shot": [img.width, img.height]})
                    box = None
            if box and t["w"] > 6 and len(norm(t["text"])) >= 3:
                crop = img.crop(box)
                read = ocr(crop)
                ratio = difflib.SequenceMatcher(None, norm(read), norm(t["text"])).ratio()
                ocr_n += 1
                if ratio >= OCR_OK:
                    ocr_ok += 1
                else:
                    findings["ocr_failed"].append({"where": tag, "text": t["text"], "ocr": read, "ratio": round(ratio, 2)})
                    crops.append((crop, f"{tag} {ratio:.2f} ocr: {read[:50]}"))
        for i in range(len(vis)):
            for j in range(i + 1, len(vis)):
                p, q = vis[i], vis[j]
                if p["text"] == q["text"] and abs(p["x"] - q["x"]) <= 2 and abs(p["y"] - q["y"]) <= 2:
                    continue  # the same text drawn twice in place (a shadow, a highlighted row)
                o = overlap(p, q)
                if o:
                    findings["overlaps"].append({"where": f"{'hud' if hud else stop}:{screen}", "a": p["text"][:60], "b": q["text"][:60],
                                                 "overlap_px": f"{o[0]}x{o[1]}"})
    if crops:
        tw = max(c.width for c, _ in crops[:40]) * 2 + 20
        sheet = Image.new("RGB", (tw + 600, 46 * min(40, len(crops)) + 10), (20, 20, 24))
        d = ImageDraw.Draw(sheet)
        for i, (c, label) in enumerate(crops[:40]):
            sheet.paste(c.resize((c.width * 2, c.height * 2)), (10, 10 + 46 * i))
            d.text((tw, 14 + 46 * i), label, fill=(255, 220, 120))
        sheet.save(run / "benchmark-readability-issues.png")
    screens = sorted({s for _, s in stops})
    card = {
        "screen_size": f"{texts[0]['scr'][0]}x{texts[0]['scr'][1]}" if texts else "?",
        "stops": len(stops), "screens": screens, "screenshots": len(shots),
        "texts_logged": len(texts), "hud_or_notice_lines": sum(1 for t in texts if t["hud"]),
        "texts_visible": sum(1 for t in texts if visible(t)),
        "off_screen": {"total": len(findings["off_screen"]), "examples": findings["off_screen"][:8]},
        "outside_dialog": {"total": len(findings["outside_dialog"]), "examples": findings["outside_dialog"][:8]},
        "clipped_by_its_box": {"total": len(findings["clipped"]), "examples": findings["clipped"][:8]},
        "overlaps": {"total": len(findings["overlaps"]), "examples": findings["overlaps"][:8]},
        "cut_with_dots": {"count": len(findings["cut"]), "examples": findings["cut"][:12]},
        "ocr": {"texts_read": ocr_n, "read_back_ok": ocr_ok, "failed": len(findings["ocr_failed"]),
                "failed_examples": findings["ocr_failed"][:10]},
    }
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul
        q = {
            "tour_complete": noul("Did the tour reach every screen (`stops` at least 30, `screens` includes menu, pzmenu, pzmenu-perf, "
                                  "pzmenu-perf-smooth, pzmenu-perf-smooth-1, pzmenu-vis, pzmenu-vis-light, pzmenu-tools, pzmenu-bench, "
                                  "pzmenu-update, pzmenu-compat, pzmenu-home-tools, pzmenu-perf-drive, pzmenu-problems, pzmenu-problems-1, "
                                  "pzmenu-search, pzmenu-clips, pzmenu-zoom-curve, pzmenu-colour, pzmenu-chart-few, pzmenu-chart-many, "
                                  "pzmenu-chart-none, panel, history, runs, runs-all, "
                                  "report and report-code; a tour over the pause menu has the ig- stops instead) with a screenshot "
                                  "per stop (`screenshots` equals `stops`)?"),
            "nothing_overflows": noul("Does every text stay inside the screen, its dialog and the box that clips it "
                                      "(`off_screen.total`, `outside_dialog.total` and `clipped_by_its_box.total` all 0)?"),
            "nothing_overlaps": noul("Do no two texts sit on top of each other (`overlaps.total` 0)?"),
            "cuts_are_rare": noul("Are texts shortened with '...' rare and only long free-form values such as a run label, a "
                                  "GPU or driver name (`cut_with_dots.examples`), never a setting name, a column title or a number?"),
            "legible": noul("Did OCR read back nearly every text (`ocr.read_back_ok` at least 90 % of `ocr.texts_read`), the failures "
                            "being short or symbol-heavy strings (bars of '|', single numbers, '-') rather than words?"),
            "verdict": choice("Is every text of the benchmark's screens readable at this screen size?", {
                "readable": "nothing overflows or overlaps and the texts read back; at most a few long free-form values are cut",
                "minor_issues": "nothing overflows, but some texts are cut or touch",
                "overflowing": "some text leaves its box, its dialog or the screen, or texts overlap",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    card["findings"] = findings
    json.dump(card, open(run / "benchmark-readability.json", "w"), indent=1, default=str)
    print(f"wrote {run / 'benchmark-readability.json'}")


if __name__ == "__main__":
    main()
