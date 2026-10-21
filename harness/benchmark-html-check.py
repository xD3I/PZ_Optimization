#!/usr/bin/env python3
"""Does any text of the built-in benchmark's HTML report overflow? (2026-10-18)

Loads each report.html in headless Chromium at several window widths (default 1920, 1280, 768 and 500: headless Chromium's narrowest window, a phone) with a
script that measures, after layout, every text node against
  - the window: the page scrolls sideways (document wider than the window) or a text ends past the right edge outside a
    box that scrolls on purpose (the tables sit in .tw boxes with overflow-x: auto),
  - its own element: a word or a nowrap text wider than the box that holds it (it spills over its neighbours),
  - a box that hides overflow (overflow hidden / clip, the SVG charts): the text is cut off,
and counts the boxes that scroll sideways on purpose (fine, listed). Asks Jev (numbers only) whether the report is
readable. Writes <out>/benchmark-html-check.json.

    harness/benchmark-html-check.py <report.html | dir with *.html>... [--widths 1920,1280,768,500] [--out dir] [--no-jev]
"""
import argparse
import html
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

PROBE = r"""
<script>
(function () {
  const vw = document.documentElement.clientWidth;
  const out = { vw: vw, doc_w: document.documentElement.scrollWidth, page_scrolls: document.documentElement.scrollWidth > vw + 1,
                past_window: [], spills: [], cut: [], scrollers: [] };
  const scrollerOf = (el) => {
    for (let e = el; e && e !== document.body; e = e.parentElement) {
      const cs = getComputedStyle(e);
      if (cs.overflowX === "auto" || cs.overflowX === "scroll") return e;
    }
    return null;
  };
  const clipperOf = (el) => {
    for (let e = el; e && e !== document.body; e = e.parentElement) {
      if (e instanceof SVGSVGElement) return e;
      const cs = getComputedStyle(e);
      if (cs.overflowX === "hidden" || cs.overflowX === "clip") return e;
    }
    return null;
  };
  const label = (el) => (el.tagName || "").toLowerCase() + (el.className && typeof el.className === "string" ? "." + el.className.split(" ")[0] : "");
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const range = document.createRange();
  while (walker.nextNode()) {
    const t = walker.currentNode;
    const text = t.nodeValue.trim();
    if (!text) continue;
    const el = t.parentElement;
    if (!el || el.closest("script,style,#pzopt-probe")) continue;
    range.selectNodeContents(t);
    const rs = Array.from(range.getClientRects()).filter(r => r.width > 0 && r.height > 0);
    if (!rs.length) continue;
    const right = Math.max(...rs.map(r => r.right)), left = Math.min(...rs.map(r => r.left));
    const sc = scrollerOf(el);
    if (right > vw + 1 && !sc) out.past_window.push({ text: text.slice(0, 60), el: label(el), right: Math.round(right) });
    const inSvg = el instanceof SVGElement;
    if (!inSvg) {
      const b = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      // an inline element's box is its text; a block, a cell or an inline-block holds the text
      if (cs.display !== "inline" && (right > b.right + 1 || left < b.left - 1))
        out.spills.push({ text: text.slice(0, 60), el: label(el), past_px: Math.round(Math.max(right - b.right, b.left - left)) });
    }
    const top = Math.min(...rs.map(r => r.top)), bottom = Math.max(...rs.map(r => r.bottom));
    const cl = clipperOf(el);
    if (cl) {
      const c = cl.getBoundingClientRect();
      const d = { right: right - c.right, left: c.left - left, bottom: bottom - c.bottom, top: c.top - top };
      if (d.right > 1 || d.left > 1 || d.bottom > 1 || d.top > 1)
        out.cut.push({ text: text.slice(0, 60), el: label(el), box: label(cl),
                       past_px: Object.fromEntries(Object.entries(d).filter(([k, v]) => v > 1).map(([k, v]) => [k, Math.round(v)])) });
    }
    // past the card (or the page column) that holds it, outside any box that scrolls on purpose
    const card = el.closest(".card, main");
    if (card && !sc) {
      const c = card.getBoundingClientRect();
      if (right > c.right + 1 || left < c.left - 1)
        out.spills.push({ text: text.slice(0, 60), el: label(el), past: label(card), past_px: Math.round(Math.max(right - c.right, c.left - left)) });
    }
  }
  for (const e of document.querySelectorAll("*")) {
    const cs = getComputedStyle(e);
    if ((cs.overflowX === "auto" || cs.overflowX === "scroll") && e.scrollWidth > e.clientWidth + 1)
      out.scrollers.push({ el: label(e), content_w: e.scrollWidth, box_w: e.clientWidth, title: (e.previousElementSibling && e.previousElementSibling.tagName === "H2") ? e.previousElementSibling.textContent.slice(0, 50) : "" });
  }
  const pre = document.createElement("pre");
  pre.id = "pzopt-probe";
  pre.style.display = "none";
  pre.textContent = JSON.stringify(out);
  document.body.appendChild(pre);
})();
</script>
"""


def chromium():
    for c in ("chromium", "google-chrome-stable", "chromium-browser"):
        p = shutil.which(c)
        if p:
            return p
    sys.exit("no chromium")


def measure(page, width, tmp):
    src = page.read_text(encoding="utf-8")
    probe = src.replace("</body>", PROBE + "</body>") if "</body>" in src else src + PROBE
    f = Path(tmp) / f"{page.stem}-{width}.html"
    f.write_text(probe, encoding="utf-8")
    cmd = [chromium(), "--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars", f"--window-size={width},900",
           "--virtual-time-budget=3000", "--dump-dom", f.as_uri()]
    dom = subprocess.run(cmd, capture_output=True, text=True, timeout=90).stdout
    m = re.search(r'<pre id="pzopt-probe"[^>]*>(.*?)</pre>', dom, re.S)
    if not m:
        return {"error": "no probe output"}
    return json.loads(html.unescape(m.group(1)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pages", nargs="+")
    ap.add_argument("--widths", default="1920,1280,768,500")   # headless Chromium makes no window narrower than 500 px
    ap.add_argument("--out", default=None)
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    pages = []
    for p in a.pages:
        p = Path(p)
        pages += sorted(p.glob("*.html")) if p.is_dir() else [p]
    widths = [int(w) for w in a.widths.split(",")]
    results = {}
    with tempfile.TemporaryDirectory(prefix="pzopt-html-") as tmp:
        for page in pages:
            for w in widths:
                results[f"{page.stem}@{w}"] = measure(page, w, tmp)
    def total(k):
        return sum(len(r.get(k, [])) for r in results.values())
    def examples(k, n=8):
        ex = []
        for name, r in results.items():
            for e in r.get(k, []):
                ex.append(dict(e, page=name))
        return ex[:n]
    card = {
        "reports": len(pages), "widths": widths, "loads": len(results),
        "loads_failed": sum(1 for r in results.values() if "error" in r),
        "page_scrolls_sideways": [n for n, r in results.items() if r.get("page_scrolls")],
        "text_past_window": {"total": total("past_window"), "examples": examples("past_window")},
        "text_spills_its_box": {"total": total("spills"), "examples": examples("spills")},
        "text_cut_by_a_box": {"total": total("cut"), "examples": examples("cut")},
        "boxes_scrolling_sideways_on_purpose": {"total": total("scrollers"), "examples": examples("scrollers", 6)},
    }
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul
        q = {
            "all_loaded": noul("Did every report load at every width (`loads_failed` 0)?"),
            "no_sideways_page": noul("Does no page scroll sideways at any width (`page_scrolls_sideways` empty) and no text end "
                                     "past the window (`text_past_window.total` 0)?"),
            "no_spill": noul("Does no text spill out of the box that holds it (`text_spills_its_box.total` 0)?"),
            "no_cut": noul("Is no text cut off by a box that hides overflow, such as a chart (`text_cut_by_a_box.total` 0)?"),
            "scrolling_ok": noul("Are the boxes that scroll sideways only wide tables on narrow windows (768 px or less), where "
                                 "a table box that scrolls is the intended way to read it, not text overflow?"),
            "verdict": choice("Is every text of the HTML report readable at every width?", {
                "readable": "nothing overflows, spills or is cut; at most wide tables scroll inside their box on narrow windows",
                "minor_issues": "a few texts touch or spill by a few pixels",
                "overflowing": "text leaves its box, the page scrolls sideways or text is cut off",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    card["results"] = results
    out = Path(a.out) if a.out else (Path(a.pages[0]) if Path(a.pages[0]).is_dir() else Path(a.pages[0]).parent)
    json.dump(card, open(out / "benchmark-html-check.json", "w"), indent=1)
    print(f"wrote {out / 'benchmark-html-check.json'}")


if __name__ == "__main__":
    main()
