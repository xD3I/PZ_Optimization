#!/usr/bin/env python3
"""Do tree crowns pop? Short appear / vanish events of whole patches over a frame sequence, and Jev's verdict (2026-10-25).

Built for the maintainer's report "trees flicker" (video 2026-10-25 02:15, night park, torch, walking back and forth): a
see-through tree in the cutaway round the player showed its whole crown, opaque, for 1-3 frames several times a second.

Sources: a devCapture run (<run>/capture, pzopt.FrameCapture, gray or RGBA, a 1:1 crop) or the npz of harness/trees/vid-blocks.py
(a gpu-screen-recorder video, PQ code values). Per frame: 16x16 block means of the luma. A block pops at frame i for K frames
(K = 1..3) when frames i..i+K-1 all leave the 3x3 neighbourhood range of frame i-1 AND of frame i+K by more than --thresh, in the
same direction (the camera moves a few px a frame, so a moving edge stays inside the range; a patch that appears and goes again
does not). Pops of one frame are grouped 8-connected; a cluster of >= --min-blocks blocks is a pop event (a crown is hundreds);
events within 3 frames are one. The walking character and the torch beam sweeping at a turn also make small clusters: compare
against a control run of the same walk with the suspect setting off.

Usage: tree-pop.py --test SRC [--before SRC] [--control SRC] [--reference NAME=SRC ...] [--scene "..."] [--context "..."]
                   [--thresh 8] [--min-blocks 40] [--skip 0] [--json out] [--no-jev]
SRC = run dir or .npz. Exit 0 = Jev says `test` does not pop.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

B = 16


def blocks_of(src, skip):
    if src.endswith(".npz"):
        z = np.load(src)
        return z["blocks"].astype(np.float32)[skip:], z["pts"][skip:], f"video {int(z['w'])}x{int(z['h'])}"
    d = os.path.join(src, "capture")
    lines = open(os.path.join(d, "index.txt")).read().splitlines()
    head = lines[0]
    w = int(re.search(r"w=(\d+)", head).group(1))
    h = int(re.search(r"h=(\d+)", head).group(1))
    gray = "fmt=gray" in head
    stamps = np.array([int(x.split()[0]) for x in lines[1:] if x.strip() and x.split()[0].isdigit()], dtype=np.int64)
    ch = 1 if gray else 4
    raw = np.memmap(os.path.join(d, "frames.gray" if gray else "frames.rgba"), dtype=np.uint8, mode="r")
    n = min(len(stamps), raw.size // (w * h * ch))
    fr = raw[: n * w * h * ch].reshape(n, h, w, ch)
    hb, wb = h // B, w // B
    out = np.empty((n - skip, hb, wb), np.float32)
    wts = np.array([0.2126, 0.7152, 0.0722], np.float32)
    for i in range(skip, n):
        a = fr[i, ::-1]
        l = a[:, :, 0].astype(np.float32) if gray else a[:, :, :3].astype(np.float32) @ wts
        out[i - skip] = l[: hb * B, : wb * B].reshape(hb, B, wb, B).mean(axis=(1, 3))
    return out, (stamps[skip:n] - stamps[skip]) / 1000.0, f"capture {w}x{h}"


def label(mask):
    h, w = mask.shape
    seen = np.zeros_like(mask)
    out = []
    for y, x in zip(*np.nonzero(mask)):
        if seen[y, x]:
            continue
        st = [(y, x)]
        seen[y, x] = True
        c, y0, x0, y1, x1 = 0, y, x, y, x
        while st:
            cy, cx = st.pop()
            c += 1
            y0, x0, y1, x1 = min(y0, cy), min(x0, cx), max(y1, cy), max(x1, cx)
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        st.append((ny, nx))
        out.append((c, y0, x0, y1, x1))
    return out


def span(g):
    v = sliding_window_view(g, (3, 3))
    return v.min(axis=(2, 3)), v.max(axis=(2, 3))


def analyse(src, thresh, min_blocks, skip, roi=None):
    blk, pts, what = blocks_of(src, skip)
    n, hb, wb = blk.shape
    valid = np.ones((hb, wb), bool)
    if roi:
        x0, y0, x1, y1 = roi
        m = np.zeros_like(valid)
        m[y0 // B: -(-y1 // B), x0 // B: -(-x1 // B)] = True
        valid &= m
    rng = [span(blk[i]) for i in range(n)]
    clusters = []
    popped = np.zeros(n, np.int32)
    for K in (1, 2, 3):
        for i in range(1, n - K):
            pmn, pmx = rng[i - 1]
            nmn, nmx = rng[i + K]
            up = np.ones_like(pmn, bool)
            dn = np.ones_like(pmn, bool)
            for j in range(K):
                c = blk[i + j][1:-1, 1:-1]
                up &= c > np.maximum(pmx, nmx) + thresh
                dn &= c < np.minimum(pmn, nmn) - thresh
            m = np.zeros((hb, wb), bool)
            m[1:-1, 1:-1] = up | dn
            m &= valid
            if not m.any():
                continue
            popped[i] += int(m.sum())
            for (c, y0, x0, y1, x1) in label(m):
                if c >= min_blocks:
                    mm = m[y0:y1 + 1, x0:x1 + 1]
                    d = float((blk[i][y0:y1 + 1, x0:x1 + 1] - blk[i - 1][y0:y1 + 1, x0:x1 + 1])[mm].mean())
                    clusters.append({"frame": i, "t": round(float(pts[i]), 3), "frames": K, "blocks": c,
                                     "bbox_px": [int(x0 * B), int(y0 * B), int((x1 + 1) * B), int((y1 + 1) * B)],
                                     "kind": "appears_brighter" if d > 0 else "vanishes_darker", "delta": round(d, 1)})
    clusters.sort(key=lambda c: (c["frame"], -c["blocks"]))
    events = []
    for c in clusters:
        if events and c["frame"] - events[-1]["frame"] <= 3:
            if c["blocks"] > events[-1]["blocks"]:
                events[-1].update({k: c[k] for k in ("blocks", "bbox_px", "kind", "delta", "frames")})
            continue
        events.append(dict(c))
    secs = max(1e-3, float(pts[-1] - pts[0]))
    big = [e for e in events if e["blocks"] >= 4 * min_blocks]
    iv = np.diff([e["t"] for e in events]) * 1000 if len(events) > 1 else np.array([])
    return {
        "source": os.path.basename(src.rstrip("/")), "what": what, "frames": int(n), "seconds": round(secs, 1),
        "fps": round(n / secs), "block_px": B, "thresh": thresh, "min_blocks": min_blocks,
        "pop_events": len(events), "pop_events_per_10s": round(10 * len(events) / secs, 2),
        "large_pop_events(>=4x_min)": len(big), "large_pop_events_per_10s": round(10 * len(big) / secs, 2),
        "events_appearing_brighter": sum(1 for e in events if e["kind"] == "appears_brighter"),
        "events_vanishing_darker": sum(1 for e in events if e["kind"] == "vanishes_darker"),
        "events_lasting_1_2_3_frames": [sum(1 for e in events if e["frames"] == k) for k in (1, 2, 3)],
        "median_event_blocks": int(np.median([e["blocks"] for e in events])) if events else 0,
        "largest_event_blocks": max([e["blocks"] for e in events], default=0),
        "median_ms_between_events": int(np.median(iv)) if len(iv) else None,
        "blocks_popping_per_frame_mean": round(float(popped.mean()), 2),
        "events": events[:60],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", required=True)
    ap.add_argument("--before")
    ap.add_argument("--control")
    ap.add_argument("--reference", action="append", default=[])
    ap.add_argument("--scene", default="night, the player walks back and forth on a park lawn with a lit torch; a large tree "
                                        "south-east of the player sits in the game's tree cutaway (drawn see-through round the player)")
    ap.add_argument("--context", default="")
    ap.add_argument("--thresh", type=float, default=8.0)
    ap.add_argument("--min-blocks", type=int, default=40)
    ap.add_argument("--skip", type=int, default=0)
    ap.add_argument("--roi")
    ap.add_argument("--json")
    ap.add_argument("--no-jev", action="store_true")
    a = ap.parse_args()
    roi = [int(v) for v in a.roi.split(",")] if a.roi else None
    res = {"test": analyse(a.test, a.thresh, a.min_blocks, a.skip, roi)}
    if a.before:
        res["before"] = analyse(a.before, a.thresh, a.min_blocks, a.skip, roi)
    if a.control:
        res["control"] = analyse(a.control, a.thresh, a.min_blocks, a.skip, roi)
    for r in a.reference:
        name, src = r.split("=", 1)
        res["reference_" + name] = analyse(src, a.thresh, a.min_blocks, a.skip, roi)
    for k, v in res.items():
        brief = {kk: vv for kk, vv in v.items() if kk != "events"}
        print(k, json.dumps(brief))
        for e in v["events"][:12]:
            print(f"   t={e['t']:7.3f} f={e['frame']:5d} {e['frames']}fr {e['blocks']:4d} blocks {e['kind']} d={e['delta']} bbox={e['bbox_px']}")
    out = {"numbers": res}
    if not a.no_jev:
        from typesafe_client import ask, choice, fmt, noul  # noqa: E402
        state = {
            "scene": a.scene,
            "method": "Every presented frame; 16x16-pixel block means of the luma. A block 'pops' when for 1-3 frames it leaves the "
                      "range of its 3x3 neighbourhood in both the frame before and the frame after, in the same direction, by more "
                      f"than {a.thresh} code values (a patch appearing / vanishing and coming back; camera motion and steady changes "
                      f"do not count). A pop event = >= {a.min_blocks} connected popping blocks in one frame (a tree crown popping is "
                      "hundreds of blocks); events within 3 frames are one. The walking character and the torch beam swinging round "
                      "at a turn make some small events on their own: `control` (when given) has the same walk and scene with the "
                      "suspect setting off, so its events are that background. " + a.context,
            "report": "the player sees trees flicker: a see-through crown near the player turns fully opaque for a frame or two, "
                      "several times a second, while walking",
        }
        for k, v in res.items():
            state[k] = {kk: vv for kk, vv in v.items() if kk != "events"}
            state[k]["first_events"] = [{x: e[x] for x in ("t", "frames", "blocks", "kind", "delta")} for e in v["events"][:15]]
        q = {
            "pops_present": noul("Does `test` show tree-crown pops: pop events, in particular large ones (hundreds of blocks, "
                                 "appearing brighter for 1-3 frames), clearly above the background of `control` when control is "
                                 "given (or, without a control, more than an occasional one)?"),
            "verdict": choice({"question": "What best describes `test`?"},
                              {"pops": "test has repeated large pop events (whole patches appearing for 1-3 frames) that the "
                                       "control does not have, or that are clearly too many and too large to be the walk's own "
                                       "background",
                               "clean": "test's pop events are at the background level of control (or rare and small)",
                               "inconclusive": "too few frames or contradicting numbers"}),
        }
        if "before" in res:
            q["fixed_vs_before"] = noul("`before` is the build that showed the flicker, `test` the fixed build, same walk. Are "
                                        "the large pop events of `before` gone in `test`?")
        log = []
        ans = ask(state, q, log=log)
        print("jev:")
        print("  " + fmt(ans).replace("\n", "\n  "))
        print(f"  ({log[0]['ms']} ms, {log[0]['model']})")
        out.update({"state": state, "answers": ans, "typesafe": log[0]})
        if a.json:
            Path(a.json).write_text(json.dumps(out, indent=1))
        v = ans.get("verdict")
        top = v.get("choice") if isinstance(v, dict) else v
        sys.exit(0 if top == "clean" else 1)
    if a.json:
        Path(a.json).write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
