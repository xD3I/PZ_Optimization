#!/usr/bin/env python3
"""Roof judge for the missing / stuck roof report (Discord, ismaello, 2026-10-09; 2026-10-18).

Three recorded bench runs of one camera spot with a zoom_cycle between 0.25 and 2.5 (zoom_span=9 zoom_jump=true, the
run starting at 0.25, so the odd steps go out to 2.5): TEST (the change on), BASE (the change off) and REF (stock,
`--prop enabled=false`). The player stands still on the spot in every zoomed-out window that starts after --after
seconds of route, so those windows are pixel-aligned across the runs. For each window, code measures in the roof box
(the house's roof on screen at 2.5) and in a control box (lawn and path beside it):
  - the settled image (the window's last frames) against REF's settled image: share of pixels 25+ levels apart;
  - the share of dark pixels (what a missing roof shows: unseen interior, black);
  - how long the roof keeps changing after the zoom-out: frames whose share of pixels 25+ levels away from the settled
    image passes 5 % (holes that fill in late), and the largest such share.
Jev reads only those numbers.

usage: roof-judge.py --test <run> --base <run> --ref <run> [--roof x,y,w,h] [--control x,y,w,h] [--after S]
                     [--context "..."] [--json out] [--no-jev]
"""
import argparse, glob, json, os, re, subprocess, sys
import numpy as np

sys.path.insert(0, os.path.dirname(__file__))

FPS = 10
DIFF = 25
DARK = 70
VIEW = (2200, 650, 900, 500)  # 5120x2160 screen crop that holds the house at zoom 2.5 from start=6293,5463
MIN_CORR = 0.4


def run_dir(run):
    return run if os.path.isdir(run) else sorted(glob.glob(f"harness/runs/{run}-*"))[-1]


def route_window(d):
    """(first, last) s of the recording to scan: the route start from schedule.log (rounded) to the route's end."""
    s = open(os.path.join(d, "schedule.log")).read()
    m = re.search(r"route starts at \+(\d+(?:\.\d+)?) s", s)
    con = open(os.path.join(d, "console.txt"), errors="replace").read()
    r = re.search(r"route done in (\d+(?:\.\d+)?)s", con)
    if not m or not r:
        sys.exit(f"{d}: no route start in schedule.log or no 'route done' in console.txt")
    return float(m.group(1)) - 1.0, float(m.group(1)) + float(r.group(1)) + 1.0


def frames(path, t0, secs):
    x, y, w, h = VIEW
    cmd = ["ffmpeg", "-v", "error", "-ss", f"{t0:.2f}", "-t", f"{secs:.2f}", "-i", path,
           "-vf", f"fps={FPS},crop={w}:{h}:{x}:{y},eq=gamma=1.8,format=gray", "-f", "rawvideo", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    n = len(raw) // (w * h)
    return np.frombuffer(raw[:n * w * h], dtype=np.uint8).reshape(n, h, w).astype(np.float32)


def corr(a, b):
    a = (a - a.mean()) / (a.std() + 1e-6)
    b = (b - b.mean()) / (b.std() + 1e-6)
    return float((a * b).mean())


def windows(stack, template):
    """Runs of consecutive frames showing the zoomed-out view (correlation with the template), >= 1 s long."""
    c = [corr(f, template) for f in stack]
    out, i = [], 0
    while i < len(c):
        if c[i] >= MIN_CORR:
            j = i
            while j < len(c) and c[j] >= MIN_CORR:
                j += 1
            if j - i >= FPS:
                out.append((i, j))
            i = j
        else:
            i += 1
    return out, c


def box_stats(seg, box, ref_settled=None):
    x, y, w, h = box
    s = seg[:, y:y + h, x:x + w]
    settled = s[-3:].mean(axis=0)
    away = (np.abs(s - settled) > DIFF).mean(axis=(1, 2))
    out = {
        "dark_share_settled": round(float((settled < DARK).mean()), 3),
        "dark_share_first_frame": round(float((s[0] < DARK).mean()), 3),
        "late_change_frames": int((away > 0.05).sum()),
        "late_change_peak_share": round(float(away.max()), 3),
    }
    if ref_settled is not None:
        out["settled_vs_stock_diff_share"] = round(float((np.abs(settled - ref_settled) > DIFF).mean()), 3)
    return out, settled


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", required=True)
    ap.add_argument("--base", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--template", required=True, help="run:seconds, a recording frame of the settled zoomed-out view")
    ap.add_argument("--roof", action="append", default=[], help="name:x,y,w,h in screen px (5120x2160)")
    ap.add_argument("--control", action="append", default=[])
    ap.add_argument("--after", type=float, default=0.0, help="ignore windows starting earlier than this many s after the scan start")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    roofs = a.roof or ["roof_front:2650,925,150,40", "roof_back:2670,710,90,50", "roof_left:2510,840,70,60"]
    ctrls = a.control or ["lawn:2250,960,100,40"]

    def parse(spec):
        name, box = spec.split(":", 1)
        x, y, w, h = (int(v) for v in box.split(","))
        return name, (x - VIEW[0], y - VIEW[1], w, h)
    boxes = dict(parse(sp) for sp in roofs + ctrls)
    kinds = {parse(sp)[0]: "roof" for sp in roofs} | {parse(sp)[0]: "control" for sp in ctrls}
    trun, tsec = a.template.rsplit(":", 1)
    template = frames(os.path.join(run_dir(trun), "recording.mp4"), float(tsec), 0.15)[0]

    runs = {k: run_dir(v) for k, v in (("ref", a.ref), ("base", a.base), ("test", a.test))}
    segs = {}
    card = {"context": a.context, "runs": runs, "boxes": {n: [kinds[n], b] for n, b in boxes.items()}, "thresholds":
            {"diff_levels": DIFF, "dark_below": DARK, "late_change_frame_share": 0.05, "fps": FPS, "min_corr": MIN_CORR}}
    for key, d in runs.items():
        t0, t1 = route_window(d)
        st = frames(os.path.join(d, "recording.mp4"), t0, t1 - t0)
        ws, c = windows(st, template)
        ws = [w for w in ws if w[0] / FPS >= a.after]
        segs[key] = [st[i:j] for i, j in ws]
        card.setdefault("window_count", {})[key] = len(ws)
        card.setdefault("window_seconds", {})[key] = [[round(i / FPS, 1), round(j / FPS, 1)] for i, j in ws]
    n = min(len(v) for v in segs.values())
    if n == 0:
        sys.exit("no zoomed-out window found in some run: " + json.dumps(card.get("window_count")))
    card["windows"] = []
    for k in range(n):
        w = {"index": k, "ref": {}, "base": {}, "test": {}}
        ref_settled = {}
        for key in ("ref", "base", "test"):
            for name, box in boxes.items():
                stats, settled = box_stats(segs[key][k], box, ref_settled.get(name))
                if key == "ref":
                    ref_settled[name] = settled
                w[key][name] = stats
        card["windows"].append(w)
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, noul, choice, fmt
        ctx = ("Three runs of the same camera spot beside a house in Project Zomboid: ref = the stock game, base = the mod "
               "without the change, test = the mod with the change. Each window is a few seconds right after the camera "
               "zoomed out. The roof box covers the house's roof, the control box a lawn beside it. A missing roof shows "
               "dark pixels where the stock roof has tiles (dark_share_settled above stock's, settled_vs_stock_diff_share "
               "well above the control's). late_change_frames counts 0.1 s frames in which the box was still changing "
               "(a roof that appears late); the control shows the noise level.")
        q = {
            "base_roof_differs_from_stock": noul(
                ctx + " In any window, does base's roof box settle clearly different from stock (diff share and dark share "
                "well above what the control box shows), i.e. roof missing or extra in the mod without the change?"),
            "test_roof_matches_stock": noul(
                ctx + " In every window, does test's roof box settle as close to stock as the control box does (diff share "
                "within a few percent of the control's, dark share like stock's)?"),
            "test_late_roof_worse_than_stock": noul(
                ctx + " Does test's roof box keep changing after the zoom-out for clearly longer than stock's "
                "(late_change_frames well above stock's in the roof box, beyond the control's noise)?"),
            "verdict": choice(ctx + " What best describes the roof?", {
                "fixed_by_change": "base shows a missing / wrong roof that test does not",
                "pre_existing_in_stock": "stock shows the same missing / wrong roof",
                "introduced_by_change": "only test shows a missing / wrong roof",
                "same_in_all": "no variant shows a missing roof beyond the control's noise",
                "inconclusive": "the numbers do not support any of these",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    if a.json:
        json.dump(card, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
