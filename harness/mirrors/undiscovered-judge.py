#!/usr/bin/env python3
"""Jev's verdict on mirror panes in undiscovered rooms (2026-10-10, the maintainer: "the mirror reflection is visible even on
undiscovered rooms", their save Sandbox/2026-10-10_12-55-31).

The game draws a room the player has not discovered black (FBORenderCell.isBlackedOutBuildingSquare); a mirror there must
stay as dark as the stock game draws it. room-judge.py counted the `dev pane` log states (reflection shown or not) and said
fixed on 2026-10-08, but the bright pane was not the reflection: the wall mirror's per-frame capture draw (meant to be
invisible) painted the glass fully lit. So this judge reads pixels.

Three runs of one still camera (`--flag route=E:0 --flag hold=8 --prop devMirrorsLog=true --prop devMirrorsRectsEvery=24
--prop devCapture=7,1,10,50,ram`): TEST (the change), BASE (before it), REF (`--prop mirrors=false`, the stock look). The
panes come from TEST's last `mirrors: dev rects` line (screen px), their seen state from its last `dev pane` line per pane.
Per pane: mean luminance in the three runs and the share of pixels 25+ levels brighter than REF. Controls: the seen panes
(their reflection must stay: TEST departs from REF like BASE does).

usage: undiscovered-judge.py --test RUN --base RUN --ref RUN [--frame -1] [--json out] [--no-jev] [--context "..."]
       undiscovered-judge.py --walk --test WALK --ref WALK_MIRRORS_OFF   (the same Jev mirror walk: every faced event)
"""
import argparse, json, os, re, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


def frame(run, idx):
    hdr = open(os.path.join(run, "capture", "index.txt")).readline().split()
    w, h = int(hdr[0][2:]), int(hdr[1][2:])
    d = np.memmap(os.path.join(run, "capture", "frames.rgba"), dtype=np.uint8, mode="r")
    n = d.size // (w * h * 4)
    i = idx % n
    a = np.array(d[i * w * h * 4:(i + 1) * w * h * 4]).reshape(h, w, 4)[::-1, :, :3].astype(np.float32)
    return 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]


def frame_at(run, ms):
    """The captured frame nearest epoch ms (index.txt: one epoch per frame after the size line)."""
    stamps = [int(l) for l in open(os.path.join(run, "capture", "index.txt")).read().split()[2:] if l.strip().isdigit()]
    return frame(run, int(np.argmin(np.abs(np.array(stamps) - ms))))


def panes(run, until=None):
    rects, seen, vw = [], {}, 5120
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        if until is not None:
            e = re.search(r"epoch_ms[= ](\d+)", line)
            if e and int(e.group(1)) > until:
                break
        m = re.search(r"mirrors: dev rects \(viewport (\d+)x\d+\): (.*) epoch_ms", line)
        if m:
            vw = int(m.group(1))
            rects = re.findall(r"\[(mirror|window) (-?\d+),(-?\d+) (\d+)x(\d+) @(\d+,\d+,\d+)\]", m.group(2))
        m = re.search(r"mirrors: dev pane (mirror|window) (\d+,\d+,\d+): seen (true|false), light ([\d.]+), reflection shown ([\d.]+)", line)
        if m:
            seen[m.group(1) + " " + m.group(2)] = {"seen": m.group(3) == "true", "light": float(m.group(4)), "shown": float(m.group(5))}
    return rects, seen, vw


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", required=True)
    ap.add_argument("--base")
    ap.add_argument("--ref", required=True)
    ap.add_argument("--frame", type=int, default=-1)
    ap.add_argument("--walk", action="store_true", help="TEST / REF are the same Jev mirror walk (BASE unused): judge every faced event")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    if a.walk:
        return walk(a)
    T, B, R = frame(a.test, a.frame), frame(a.base, a.frame), frame(a.ref, a.frame)
    rects, seen, vw = panes(a.test)
    s = T.shape[1] / vw
    card = {"runs": {"test": a.test, "base": a.base, "ref": a.ref}, "context": a.context,
            "unseen_panes": {}, "seen_panes": {}}
    for kind, x, y, w, h, sq in rects:
        x0, y0 = max(0, int(int(x) * s)), max(0, int(int(y) * s))
        x1, y1 = min(T.shape[1], int((int(x) + int(w)) * s)), min(T.shape[0], int((int(y) + int(h)) * s))
        if x1 - x0 < 4 or y1 - y0 < 4:
            continue  # off screen
        st = seen.get(kind + " " + sq, {})
        t, b, r = T[y0:y1, x0:x1], B[y0:y1, x0:x1], R[y0:y1, x0:x1]
        row = {
            "kind": kind, "square": sq, "box_capture_px": [x0, y0, x1 - x0, y1 - y0],
            "pane_log": st,
            "mean_luma": {"test": round(float(t.mean()), 1), "base": round(float(b.mean()), 1), "ref_stock": round(float(r.mean()), 1)},
            "brighter_than_stock_25_share": {"test": round(float(((t - r) > 25).mean()), 3), "base": round(float(((b - r) > 25).mean()), 3)},
            "test_vs_base_mean_abs_diff": round(float(np.abs(t - b).mean()), 1),
        }
        card["seen_panes" if st.get("seen") else "unseen_panes"][kind + " " + sq] = row
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, noul, choice, fmt
        q = {
            "unseen_lit_base": noul(
                "`unseen_panes` are mirrors / windows in rooms the player has not discovered: the game draws those rooms "
                "black (ref_stock is the stock game's look of the same pixels). In BASE (before the change), do one or more "
                "unseen panes show clearly more light than stock: mean_luma base well above ref_stock, or a sizeable "
                "brighter_than_stock_25_share base (a few percent is edge noise)?"),
            "unseen_lit_test": noul(
                "Same question for TEST (with the change): does any unseen pane show clearly more light than stock (mean_luma "
                "test well above ref_stock, or a sizeable brighter_than_stock_25_share test)?"),
            "seen_still_reflect": noul(
                "`seen_panes` are panes the player has seen: their reflection is wanted and makes them depart from ref_stock. "
                "With the change, do they keep departing from stock about as much as in BASE (or is there no seen pane to "
                "judge, which counts as yes)? A seen pane whose test luminance fell to the stock value while base was above "
                "it lost its reflection."),
            "verdict": choice("What best describes the change for mirrors in undiscovered rooms?", {
                "fixed": "unseen panes lit in base, dark like stock in test, seen panes still reflect",
                "not_fixed": "unseen panes still show light in test",
                "regressed": "unseen panes fixed but seen panes lost their reflection",
                "no_problem_in_base": "base showed no lit unseen pane either",
                "inconclusive": "the numbers do not support any of these",
            }),
        }
        ans = ask(card, q)
        print(fmt(ans))
        card["jev"] = ans
    if a.json:
        json.dump(card, open(a.json, "w"), indent=1)


def events(run):
    out = []
    for line in open(os.path.join(run, "console.txt"), errors="replace"):
        m = re.search(r"harness: mirror walk: (faced mirror \d+ at station \S+).*epoch_ms=(\d+)", line)
        if m:
            out.append((m.group(1), int(m.group(2))))
    return out


def walk(a):
    """Per faced event (and the walk's first frame, every room still undiscovered): each mirror pane on screen, TEST against
    REF (mirrors off: the stock look) in the frame 0.6 s after the event, with the pane's seen state from TEST's log then."""
    et, er = events(a.test), events(a.ref)
    stamps_t = [int(l) for l in open(os.path.join(a.test, "capture", "index.txt")).read().split()[2:] if l.strip().isdigit()]
    stamps_r = [int(l) for l in open(os.path.join(a.ref, "capture", "index.txt")).read().split()[2:] if l.strip().isdigit()]
    pairs = [("walk start (nothing discovered yet)", stamps_t[2], stamps_r[2])]
    pairs += [(n, t + 600, r + 600) for (n, t), (_, r) in zip(et, er)]
    card = {"runs": {"test": a.test, "ref": a.ref}, "context": a.context, "events_test": len(et), "events_ref": len(er), "moments": []}
    for name, mt, mr in pairs:
        T, R = frame_at(a.test, mt), frame_at(a.ref, mr)
        rects, seen, vw = panes(a.test, mt)
        s = T.shape[1] / vw
        mo = {"moment": name, "mirrors": {}}
        for kind, x, y, w, h, sq in rects:
            if kind != "mirror":
                continue
            x0, y0 = max(0, int(int(x) * s)), max(0, int(int(y) * s))
            x1, y1 = min(T.shape[1], int((int(x) + int(w)) * s)), min(T.shape[0], int((int(y) + int(h)) * s))
            if x1 - x0 < 3 or y1 - y0 < 3:
                continue
            t, r = T[y0:y1, x0:x1], R[y0:y1, x0:x1]
            st = seen.get("mirror " + sq, {})
            mo["mirrors"][sq] = {"seen": st.get("seen"), "reflection_shown": st.get("shown"),
                                 "mean_luma_test": round(float(t.mean()), 1), "mean_luma_stock": round(float(r.mean()), 1),
                                 "mean_abs_diff_vs_stock": round(float(np.abs(t - r).mean()), 1),
                                 "brighter_than_stock_25_share": round(float(((t - r) > 25).mean()), 3)}
        card["moments"].append(mo)
    print(json.dumps(card, indent=1))
    if not a.no_jev:
        from typesafe_client import ask, noul, choice, fmt
        q = {
            "unseen_dark": noul(
                "Mirrors with seen=false are in rooms the player has not discovered: the game draws them black. With the "
                "change (TEST), are all of them about as dark as stock (mean_luma_test close to mean_luma_stock, "
                "brighter_than_stock_25_share near 0)? Ignore tiny differences of a few levels."),
            "seen_reflect": noul(
                "Mirrors with seen=true and reflection_shown 1 are in discovered rooms and should show a reflection, which "
                "makes them depart from stock (mirrors off). Do the seen mirrors, at the moments the player faces them, "
                "clearly depart from stock (mean_abs_diff_vs_stock of several levels or more) for most of those moments?"),
            "verdict": choice("Overall, for mirrors and undiscovered rooms during the walk:", {
                "fixed": "unseen mirrors dark like stock, seen mirrors reflect",
                "unseen_lit": "some unseen mirror still shows light",
                "seen_broken": "unseen mirrors dark but seen mirrors show no reflection",
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
