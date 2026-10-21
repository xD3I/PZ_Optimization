#!/usr/bin/env python3
"""Zombie "phasing" judge (2026-10-18, Workshop reports since the 10-16 release: zombies flash as naked grey-white
mannequins for a split second on first sight of a horde, on a melee hit, on a kill, at a corpse drag, when a hat falls).

Reads the --record capture (AV1 10-bit PQ) of each run, at --fps frames a second and 1/--scale resolution, and counts per
frame the "mannequin" pixels: the stand-in sprite's flat beige-grey (PQ codes, 10-bit: luma in [--y-lo, --y-hi], chroma
within --chroma-tol of (--u, --v) off neutral) that also MOVED, i.e. differ by more than --move from a background (the
per-pixel median of the frames --bg-lag s back), so the static ground, wood and grass of the same tint drop out. Frames
where most of the picture moved (camera pan, the run-to-pier) are skipped. A clothed zombie's grey skin gives a low
baseline; a phasing event is a burst well above the run's own median (+ --k MADs, at least --min-px), and events are
counted with their duration (a hit flash is a few frames, the first-sight horde ~1.5 s).

Jev reads the per-run event counts / peak / pixel-seconds of TEST runs against CONTROL runs (stock, or
zombieFirstSightSprite=false) and answers whether the tests show the flashes and the controls not.

usage: phasing-judge.py --test <run>... --control <run>... [--from S] [--to S] [--crop x,y,w,h] [--context "..."]
                        [--json out] [--frames-dir dir] [--no-jev]
<run> = a run dir or a harness/runs name prefix (newest match). --from/--to are seconds of recording (default: the whole
recording minus its last 15 s, the quit). Calibrated on zr-horde-def / -nofs / -stock (10-16, the horde-shoot bench).
"""
import argparse, glob, json, os, subprocess, sys
import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
W, H = 5120, 2160


def run_dir(r):
    if os.path.isdir(r):
        return r.rstrip("/")
    m = sorted(glob.glob(f"harness/runs/{r}*"))
    if not m:
        sys.exit(f"no run {r}")
    return m[-1]


def duration(p):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", p],
                         capture_output=True, text=True).stdout
    return float(out.strip() or 0)


def frames(path, t0, t1, fps, scale, crop):
    x, y, w, h = crop
    ow, oh = w // scale, h // scale
    vf = f"crop={w}:{h}:{x}:{y},fps={fps},scale={ow}:{oh}:flags=area,format=yuv444p16le"
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-ss", f"{t0:.3f}", "-t", f"{t1 - t0:.3f}", "-i", path, "-vf", vf,
                          "-f", "rawvideo", "-"], stdout=subprocess.PIPE)
    n = ow * oh * 3 * 2
    while True:
        b = p.stdout.read(n)
        if len(b) < n:
            break
        yield np.frombuffer(b, np.uint16).reshape(3, oh, ow).astype(np.float32) / 64.0
    p.wait()


def measure(d, a):
    rec = d + "/recording.mp4"
    dur = duration(rec)
    t0 = a.t_from if a.t_from is not None else 3.0
    t1 = a.t_to if a.t_to is not None else max(t0 + 1, dur - 15)
    lag = int(a.bg_lag * a.fps)
    hist, counts, skipped = [], [], 0
    bg, bg_every = None, max(1, int(a.fps / 2))  # the background median is the slow part: refresh it twice a second
    for n, f in enumerate(frames(rec, t0, t1, a.fps, a.scale, a.crop)):
        Y, U, V = f
        hist.append(Y)
        if len(hist) > 2 * lag:
            hist.pop(0)
        if len(hist) < lag:
            counts.append(0)
            continue
        if bg is None or n % bg_every == 0:
            bg = np.median(np.stack(hist[: max(1, len(hist) - lag)][::3]), axis=0)
        moved = np.abs(Y - bg) > a.move
        if moved.mean() > 0.5:  # camera moved: the whole picture differs
            skipped += 1
            counts.append(0)
            continue
        tint = (Y >= a.y_lo) & (Y <= a.y_hi) & (np.hypot(U - 512 - a.u, V - 512 - a.v) <= a.chroma_tol)
        counts.append(int((tint & moved).sum()))
    c = np.array(counts, np.float64)
    px_scale = a.scale * a.scale  # report full-resolution pixels
    live = c[c > 0]
    med = float(np.median(live)) if live.size else 0.0
    mad = float(np.median(np.abs(live - med))) if live.size else 0.0
    thr = max(med + a.k * max(mad, 1.0), a.min_px / px_scale)
    events, i = [], 0
    while i < len(c):
        if c[i] > thr:
            j = i
            while j + 1 < len(c) and c[j + 1] > thr:
                j += 1
            events.append({"t": round(t0 + i / a.fps, 2), "frames": j - i + 1, "seconds": round((j - i + 1) / a.fps, 2),
                           "peak_px": int(c[i:j + 1].max() * px_scale)})
            i = j + 1
        else:
            i += 1
    if a.frames_dir and events:
        os.makedirs(a.frames_dir, exist_ok=True)
        for e in sorted(events, key=lambda e: -e["peak_px"])[:6]:
            x, y, w, h = a.crop
            out = f"{a.frames_dir}/{os.path.basename(d)}-{e['t']:.2f}.png"
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{e['t'] + e['seconds'] / 2:.3f}", "-i", rec,
                            "-frames:v", "1", "-vf", f"crop={w}:{h}:{x}:{y}", out])
    return {"run": os.path.basename(d), "window_s": [round(t0, 1), round(t1, 1)], "frames": len(c),
            "camera_moved_frames": skipped, "baseline_px": int(med * px_scale), "threshold_px": int(thr * px_scale),
            "events": len(events), "event_seconds": round(sum(e["seconds"] for e in events), 2),
            "peak_px": int(c.max() * px_scale) if c.size else 0,
            "peak_over_baseline": round(float(c.max() / max(med, 1.0)), 1) if c.size else 0.0,
            "short_events_le_0.5s": sum(1 for e in events if e["seconds"] <= 0.5),
            "long_events_gt_0.5s": sum(1 for e in events if e["seconds"] > 0.5),
            "event_list": events[:40], "series": [int(v * px_scale) for v in c] if a.series else None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", nargs="+", required=True)
    ap.add_argument("--control", nargs="+", default=[])
    ap.add_argument("--from", dest="t_from", type=float)
    ap.add_argument("--to", dest="t_to", type=float)
    ap.add_argument("--crop", default="1280,480,2560,1600")
    ap.add_argument("--fps", type=float, default=30)
    ap.add_argument("--scale", type=int, default=2)
    ap.add_argument("--bg-lag", type=float, default=1.0)
    ap.add_argument("--move", type=float, default=18)
    ap.add_argument("--y-lo", type=float, default=340)
    ap.add_argument("--y-hi", type=float, default=460)
    ap.add_argument("--u", type=float, default=-12)
    ap.add_argument("--v", type=float, default=4)
    ap.add_argument("--chroma-tol", type=float, default=4.5)
    ap.add_argument("--k", type=float, default=8)
    ap.add_argument("--min-px", type=float, default=1500)
    ap.add_argument("--context", default="")
    ap.add_argument("--json")
    ap.add_argument("--frames-dir")
    ap.add_argument("--series", action="store_true")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    a.crop = tuple(int(v) for v in a.crop.split(","))
    card = {"context": a.context, "metric": "full-resolution pixels a frame of the stand-in sprite's beige-grey that moved; "
            "an event = consecutive frames above the run's baseline + k MADs (and min_px)",
            "test": [measure(run_dir(r), a) for r in a.test], "control": [measure(run_dir(r), a) for r in a.control]}
    for k in ("test", "control"):
        for r in card[k]:
            print(f"{k:7s} {r['run']:45s} events {r['events']:3d} ({r['short_events_le_0.5s']} short, "
                  f"{r['long_events_gt_0.5s']} long) {r['event_seconds']:5.2f} s  peak {r['peak_px']:6d} px "
                  f"({r['peak_over_baseline']}x baseline {r['baseline_px']})  camera-moved {r['camera_moved_frames']}")
            for e in r["event_list"][:12]:
                print(f"          t={e['t']:7.2f}s  {e['seconds']:.2f}s  peak {e['peak_px']} px")
    if not a.no_jev and card["control"]:
        from typesafe_client import ask, noul, choice, fmt
        slim = json.loads(json.dumps(card))
        for k in ("test", "control"):
            for r in slim[k]:
                r.pop("series", None)
        q = {
            "test_shows_phasing": noul(
                "Each run is a recording of zombies near the player. Do the TEST runs show bursts of the flat beige-grey "
                "stand-in figure (events well above their own baseline, peak_over_baseline high) clearly more often or "
                "larger than the CONTROL runs do?"),
            "control_clean": noul("Are the CONTROL runs essentially free of such bursts (few or no events, small peaks)?"),
            "verdict": choice("What best describes the TEST runs against the CONTROL runs?", {
                "phasing": "the test runs show the naked-figure flashes, the controls do not",
                "clean": "the test runs are as clean as the controls",
                "both": "both show similar bursts (the metric does not separate them)",
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
