#!/usr/bin/env python3
"""Zombie "phasing" at melee events (2026-10-18): per hit / kill / spawn of a `--flag melee=N` run (pzopt.MeleeRig v7), how
the zombie looks in the --record capture around the event, against the same zombie a second later.

MeleeRig logs `harness: melee hit|kill|spawn t=<epoch ms> zombie=<i> ... sx=<px> sy=<px> zoom=<z>` (the zombie's feet on the
screen) and, on builds with zombieFirstSightSprite, `harness: melee standin on|off` for every frame run in which the build
drew the zombie as the first-sight stand-in sprite (the game's generic unclothed body). For each event this script crops the
zombie's box (feet at sx, sy; --box-w x --box-h px at zoom 1, scaled by the zoom) at --fps over [t - --pre, t + --post]
and over a settled window [t + --after, t + --after + 0.4], and counts per frame the "pale" pixels (the stand-in's
beige-grey: PQ luma >= --pale-y, chroma in [--pale-c-min, --pale-c], Cb - 512 <= --pale-u-max; plain asphalt is neutral) and the "colour" pixels (chroma >= --color-c: clothes). A phasing
frame has many pale and few colour pixels; the event's score is the peak pale count over the window minus the settled
median, and the colour dip (settled median - window minimum). The clocks: recording time = epoch / 1000 - launch_epoch -
--lag (the capture starts about a second after the launch).

Jev reads, per run, the event scores and the stand-in episodes, TEST against CONTROL runs, and answers whether the test
runs show the naked-figure flashes at hits / kills / spawns and the controls not.

usage: phasing-events.py --test <run>... [--control <run>...] [--context "..."] [--json f] [--sheet dir] [--no-jev]
"""
import argparse, glob, json, os, re, subprocess, sys
import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
W, H = 5120, 2160
EV = re.compile(r"harness: melee (hit|kill|spawn) t=(\d+) zombie=(\d+).*?sx=(-?\d+) sy=(-?\d+) zoom=([\d.]+)")
SI_ON = re.compile(r"harness: melee standin on t=(\d+) zombie=(\d+).*?sx=(-?\d+) sy=(-?\d+)")
SI_OFF = re.compile(r"harness: melee standin off t=(\d+) zombie=(\d+) frames=(\d+) ms=(\d+)")


def run_dir(r):
    if os.path.isdir(r):
        return r.rstrip("/")
    m = sorted(glob.glob(f"harness/runs/{r}*"))
    if not m:
        sys.exit(f"no run {r}")
    return m[-1]


def kv(p):
    return dict(l.strip().split("=", 1) for l in open(p) if "=" in l)


def crop_frames(rec, t0, dur, box, fps):
    x, y, w, h = box
    vf = f"crop={w}:{h}:{x}:{y},fps={fps},format=yuv444p16le"
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{max(0, t0):.3f}", "-t", f"{dur:.3f}", "-i", rec, "-vf", vf,
                          "-f", "rawvideo", "-"], capture_output=True).stdout
    n = w * h * 3 * 2
    return [np.frombuffer(raw[i:i + n], np.uint16).reshape(3, h, w).astype(np.float32) / 64.0
            for i in range(0, len(raw) - n + 1, n)]


def counts(f, a):
    Y, U, V = f
    c = np.hypot(U - 512, V - 512)
    pale = (Y >= a.pale_y) & (c >= a.pale_c_min) & (c <= a.pale_c) & (U - 512 <= a.pale_u_max)  # the stand-in's warm grey; neutral asphalt has c < 4
    return int(pale.sum()), int((c >= a.color_c).sum())


def measure(d, a, sheet):
    con = open(d + "/console.txt", errors="ignore").read()
    launch = int(kv(d + "/run.opts")["launch_epoch"])
    rec = d + "/recording.mp4"
    to_rec = lambda ms: ms / 1000.0 - launch - a.lag
    events, rows = [], []
    for m in EV.finditer(con):
        kind, t, z, sx, sy, zoom = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5)), float(m.group(6))
        events.append((kind, to_rec(t), z, sx, sy, zoom))
    standins = []
    for m in SI_ON.finditer(con):
        standins.append({"t": round(to_rec(int(m.group(1))), 2), "zombie": int(m.group(2))})
    offs = [(int(m.group(2)), int(m.group(3)), int(m.group(4))) for m in SI_OFF.finditer(con)]
    for s, (_, fr, ms) in zip(standins, offs):
        s["frames"], s["ms"] = fr, ms
    for kind, t, z, sx, sy, zoom in events:
        bw, bh = int(a.box_w * zoom), int(a.box_h * zoom)
        x, y = max(0, min(W - bw, sx - bw // 2)), max(0, min(H - bh, sy - int(bh * 0.92)))
        box = (x, y, bw, bh)
        win = crop_frames(rec, t - a.pre, a.pre + a.post, box, a.fps)
        aft = crop_frames(rec, t + a.after, 0.4, box, a.fps)
        if not win or not aft:
            continue
        wc = [counts(f, a) for f in win]
        ac = [counts(f, a) for f in aft]
        pale_after = float(np.median([p for p, _ in ac]))
        color_after = float(np.median([c for _, c in ac]))
        i_peak = int(np.argmax([p for p, _ in wc]))
        row = {"kind": kind, "t": round(t, 2), "zombie": z, "pale_peak": wc[i_peak][0], "pale_after": int(pale_after),
               "pale_excess": int(wc[i_peak][0] - pale_after), "pale_excess_share": round((wc[i_peak][0] - pale_after) / (bw * bh), 4),
               "color_min": min(c for _, c in wc), "color_after": int(color_after),
               "color_dip_share": round((color_after - min(c for _, c in wc)) / max(color_after, 1.0), 3),
               "peak_at": round(t - a.pre + i_peak / a.fps, 2),
               "standin": any(s["zombie"] == z and t - 1.0 <= s["t"] <= t + 1.0 for s in standins)}
        rows.append(row)
        if sheet and row["pale_excess_share"] >= a.sheet_min:
            os.makedirs(sheet, exist_ok=True)
            out = f"{sheet}/{os.path.basename(d)}-{kind}-{t:.2f}.png"
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{row['peak_at']:.3f}", "-i", rec, "-frames:v", "1", "-vf",
                            f"crop={bw * 3}:{bh * 2}:{max(0, x - bw)}:{max(0, y - bh // 2)},scale=iw*2:-1:flags=neighbor", out])
    flagged = [r for r in rows if r["pale_excess_share"] >= a.flag_share]
    summ = {"run": os.path.basename(d), "events": len(rows),
            "by_kind": {k: sum(1 for r in rows if r["kind"] == k) for k in ("hit", "kill", "spawn")},
            "flagged_events": len(flagged),
            "flagged_by_kind": {k: sum(1 for r in flagged if r["kind"] == k) for k in ("hit", "kill", "spawn")},
            "pale_excess_share_p90": round(float(np.percentile([r["pale_excess_share"] for r in rows], 90)), 4) if rows else 0,
            "pale_excess_share_max": max((r["pale_excess_share"] for r in rows), default=0),
            "standin_episodes_logged_by_the_build": len(standins),
            "standin_ms_total": sum(s.get("ms", 0) for s in standins),
            "events_detail": rows, "standins": standins[:40]}
    return summ


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", nargs="+", required=True)
    ap.add_argument("--control", nargs="+", default=[])
    ap.add_argument("--fps", type=float, default=60)
    ap.add_argument("--pre", type=float, default=0.5)
    ap.add_argument("--post", type=float, default=0.6)
    ap.add_argument("--after", type=float, default=1.0)
    ap.add_argument("--lag", type=float, default=1.0)
    ap.add_argument("--box-w", type=int, default=90)
    ap.add_argument("--box-h", type=int, default=150)
    ap.add_argument("--pale-y", type=float, default=360)
    ap.add_argument("--pale-c", type=float, default=16)
    ap.add_argument("--pale-c-min", type=float, default=7)
    ap.add_argument("--pale-u-max", type=float, default=-6)
    ap.add_argument("--color-c", type=float, default=22)
    ap.add_argument("--flag-share", type=float, default=0.06)
    ap.add_argument("--sheet-min", type=float, default=0.06)
    ap.add_argument("--sheet")
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    card = {"context": a.context,
            "metric": "per melee event (hit / kill / spawn) the zombie's box in the recording: pale_excess_share = the peak share "
                      "of pale beige-grey (unclothed stand-in) pixels within -0.5..+0.6 s of the event minus that share a "
                      f"second later (clothed); an event is flagged at >= {a.flag_share}. standin_episodes_logged_by_the_build = "
                      "frames runs in which the build itself drew the zombie as the stand-in sprite (0 by construction when the "
                      "feature is off).",
            "test": [measure(run_dir(r), a, a.sheet) for r in a.test],
            "control": [measure(run_dir(r), a, a.sheet) for r in a.control]}
    for k in ("test", "control"):
        for r in card[k]:
            print(f"{k:7s} {r['run']:38s} events {r['events']:3d} {r['by_kind']}  flagged {r['flagged_events']:2d} "
                  f"{r['flagged_by_kind']}  pale p90 {r['pale_excess_share_p90']:.3f} max {r['pale_excess_share_max']:.3f}  "
                  f"build stand-ins {r['standin_episodes_logged_by_the_build']} ({r['standin_ms_total']} ms)")
            for e in sorted(r["events_detail"], key=lambda e: -e["pale_excess_share"])[:5]:
                print(f"          {e['kind']:5s} t={e['t']:6.2f} peak@{e['peak_at']:6.2f} pale+{e['pale_excess_share']:.3f} "
                      f"colour dip {e['color_dip_share']:.2f} standin={e['standin']}")
    if not a.no_jev and card["control"]:
        from typesafe_client import ask, noul, choice, fmt
        slim = json.loads(json.dumps(card))
        for k in ("test", "control"):
            for r in slim[k]:
                r["events_detail"] = sorted(r["events_detail"], key=lambda e: -e["pale_excess_share"])[:8]
        q = {
            "test_shows_phasing": noul(
                "Do the TEST runs show the zombies turning into a pale unclothed figure at melee hits, kills or spawns, i.e. "
                "clearly more flagged events and higher pale_excess_share than the CONTROL runs, or stand-in episodes logged "
                "by the build itself?"),
            "control_clean": noul("Are the CONTROL runs essentially free of such events (few flagged, low pale_excess_share, no "
                                  "stand-in episodes)?"),
            "verdict": choice("What best describes the TEST runs against the CONTROL runs?", {
                "phasing": "the test runs show the naked-figure flashes at melee events, the controls do not",
                "clean": "the test runs are as clean as the controls",
                "both": "both show similar flashes (the metric does not separate them)",
                "inconclusive": "the numbers do not support any of these",
            }),
        }
        ans = ask(slim, q)
        print(fmt(ans))
        card["jev"] = ans
    if a.json:
        json.dump(card, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
