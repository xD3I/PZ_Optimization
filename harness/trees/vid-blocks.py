#!/usr/bin/env python3
"""Decode a gpu-screen-recorder capture (AV1 10-bit PQ, VFR) frame by frame and keep per-frame block means of the luma
(2026-10-25, the maintainer's tree-flicker video). Heavy: run it as a queue `cmd` job.

The luma is the Y plane's PQ code value (0-255 after the 10 -> 8 bit drop): perceptually even, so a fixed threshold means the
same visible step in the dark and in the torch beam. No alignment is needed for the blink metric (a block that leaves the
3x3 neighbourhood range of both the previous and the next frame), the camera moves 0-3 px a frame at 165 fps.

Writes OUT.npz: blocks (n, H/B, W/B) float16, pts (n,) seconds, block, w, h. Optional --crop W:H:X:Y (1:1, top-left origin)
and --frames-out DIR with --frames-at T0-T1 (seconds) to also dump those frames as 8-bit gray PNGs of the crop.

Usage: vid-blocks.py VIDEO OUT.npz [--block 16] [--crop W:H:X:Y] [--frames-out DIR --frames-at T0-T1]
"""
import argparse
import os
import subprocess
import sys

import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("out")
    ap.add_argument("--block", type=int, default=16)
    ap.add_argument("--crop")
    ap.add_argument("--frames-out")
    ap.add_argument("--frames-at")
    ap.add_argument("--threads", type=int, default=12)
    ap.add_argument("--max-frames", type=int, default=0)
    a = ap.parse_args()
    pr = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time",
                         "-of", "csv=p=0", a.video], capture_output=True, text=True, check=True)
    pts = np.array(sorted(float(x.split(",")[0]) for x in pr.stdout.split() if x.strip() and x.strip() != "N/A"))
    pr = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
                         "-of", "csv=p=0:s=,", a.video], capture_output=True, text=True, check=True)
    w, h = (int(v) for v in pr.stdout.strip().split(",")[:2])
    vf = "format=gray"
    if a.crop:
        cw, ch, cx, cy = (int(v) for v in a.crop.split(":"))
        vf = f"crop={cw}:{ch}:{cx}:{cy},format=gray"
        w, h = cw, ch
    cmd = ["ffmpeg", "-v", "error", "-threads", str(a.threads), "-c:v", "libdav1d", "-i", a.video, "-map", "0:v:0",
           "-fps_mode", "passthrough", "-vf", vf, "-f", "rawvideo", "-pix_fmt", "gray", "-"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=w * h * 4)
    B = a.block
    hb, wb = h // B, w // B
    dump = None
    if a.frames_out and a.frames_at:
        t0, t1 = (float(v) for v in a.frames_at.split("-"))
        dump = (t0, t1)
        os.makedirs(a.frames_out, exist_ok=True)
        from PIL import Image
    out = []
    i = 0
    fsz = w * h
    while True:
        buf = p.stdout.read(fsz)
        if len(buf) < fsz:
            break
        f = np.frombuffer(buf, np.uint8).reshape(h, w)
        out.append(f[: hb * B, : wb * B].reshape(hb, B, wb, B).mean(axis=(1, 3), dtype=np.float32).astype(np.float16))
        if dump is not None and i < len(pts) and dump[0] <= pts[i] <= dump[1]:
            Image.fromarray(f).save(os.path.join(a.frames_out, f"f{i:05d}.png"))
        i += 1
        if a.max_frames and i >= a.max_frames:
            p.kill()
            break
        if i % 500 == 0:
            print(f"{i} frames", file=sys.stderr, flush=True)
    p.wait()
    n = len(out)
    np.savez(a.out, blocks=np.stack(out), pts=pts[:n], block=B, w=w, h=h)
    print(f"frames={n} pts={len(pts)} size={w}x{h} block={B} -> {a.out}")


if __name__ == "__main__":
    main()
