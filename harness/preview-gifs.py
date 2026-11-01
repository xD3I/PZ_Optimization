#!/usr/bin/env python3
"""Workshop preview GIF candidates (2026-09-26): four 512x512 loops under Steam's 1,000,000-byte preview limit, stock
against every optimization plus the picture enhancements (ambient occlusion, sun shadows, reflections, per-pixel
lighting; HDR output cannot show in an SDR GIF, so the runs keep hdrAuto=false). Sources: the wsgif-* queue runs.

  night  1-night-wipe.gif     the night torch shot pair (wsgif-night-stock* / wsgif-night-opt), a divider sweeping
                              between the stock and the optimized picture, each side's fps in its corner
  rsnight 1b-night-wipe-riverside.gif  the same wipe at the Riverside pier (wsgif-rsnight6-*: the Sunset deck edge 6402,5189, confirmed by stand-spot-judge.py, lights=on, turn=90 before the held shot) with a keyword row
                              (PREVIEW_KEYWORDS, default HDR, DLSS, AO, sun shadows, reflections, per-pixel light)
  horde  2-horde-counter.gif  the Louisville horde (wsgif-louwalk-opt recording, the preset's walk at zoom 1.5, into a
                              parking lot full of zombies), the fps counting up from stock's (wsgif-louwalk-stock244)
  storm  3-storm.gif          the night thunderstorm (wsgif-storm2-opt recording) round a lightning strike, one fps tag
  cuts   4-three-cuts.gif     title, stock, optimized: the 16:30 sun shot pair (wsgif-day-stock* / wsgif-day-opt)

fps: the route window's mean from analyze.py's `overlay:` line. Stock turns options.ini `uncappedFPS=true` into a
60 fps lock at load (Core.loadOptions), so the stock runs cap at its fastest setting, frameRate=244; the optimized
runs are uncapped (--prop uncappedFps=true). The optimized `--shot-at` capture is the 4096x1728 bottom-left of the
5120x2160 screen at 1:1, the stock one the whole screen: `align` finds the offset.

    harness/queue.sh submit media --label wsgif-render --out docs/workshop/preview/1-night-wipe.gif ... \\
        -- python3 harness/preview-gifs.py [night horde storm cuts]
    env: PREVIEW_NAME (default PZ_Optimization), PREVIEW_OUT (default docs/workshop/preview), WSGIF_RUNS (run dir root)
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "tools", "hdr"))
from hdrvideo import pq_to_nits  # noqa: E402
from newcard import BG, INK, OPT, STOCK, font, write_gif  # noqa: E402

RUNS = os.environ.get("WSGIF_RUNS", os.path.join(HERE, "runs"))
OUT = Path(os.environ.get("PREVIEW_OUT", os.path.join(HERE, "..", "docs", "workshop", "preview")))
NAME = os.environ.get("PREVIEW_NAME", "PZ_Optimization")
S = 512                 # the GIF's side
BAND = 58               # the name band along the bottom
LIMIT = 995_000         # Steam refuses above 1,000,000 bytes
REF_NITS = 203.0        # SDR white in the PQ capture


def run(label):
    """The newest run dir whose name is label-<timestamp> (label may carry a glob)."""
    dirs = sorted(glob.glob(os.path.join(RUNS, f"{label}-2*")), key=lambda d: d[-15:])
    if not dirs:
        sys.exit(f"no {label} run under {RUNS}")
    return Path(dirs[-1])


def fps(rd):
    out = subprocess.run([sys.executable, os.path.join(HERE, "analyze.py"), str(rd)], capture_output=True, text=True).stdout
    m = re.search(r"overlay: \d+ frames over \d+s \(route window\), ([\d.]+) fps mean", out)
    if not m:
        sys.exit(f"no overlay fps in analyze.py {rd}")
    return float(m.group(1))


def fps_text(v):
    return f"{v:.0f}"


STOCK_LABEL = "STOCK (CAPPED AT 244)"   # stock's fastest frameRate; its uncappedFPS=true loads as a 60 lock


def text(d, xy, s, size, fill=INK, anchor="la", weight="bold", stroke=3):
    d.text(xy, s, font=font(size, weight), fill=fill, anchor=anchor, stroke_width=stroke, stroke_fill="#000000")


def band(im, sub=None):
    """The solid name band along the bottom (solid: a translucent one costs bytes every frame the scene moves)."""
    d = ImageDraw.Draw(im)
    d.rectangle((0, S - BAND, S, S), fill=BG)
    if sub:
        text(d, (16, S - BAND / 2), NAME, 30, anchor="lm", stroke=0)
        text(d, (S - 16, S - BAND / 2), sub, 20, fill=OPT, anchor="rm", weight="semibold", stroke=0)
    else:
        text(d, (S / 2, S - BAND / 2), NAME, 30, anchor="mm", stroke=0)


def tag(d, xy, label, value, colour, anchor="la"):
    """A two-line corner tag: small label over a large fps value, on a solid plate (value None: the label alone, on
    the plate's bottom line)."""
    f1, f2 = font(17, "bold"), font(34, "bold")
    v = f"{value} fps" if value is not None else ""
    w = max(f1.getlength(label), f2.getlength(v)) + 20
    h = 66 if value is not None else 30
    x, y = xy
    if value is None:
        y += 36
    if anchor == "ra":
        x -= w
    d.rounded_rectangle((x, y, x + w, y + h), 8, fill=BG)
    d.text((x + 10, y + 6), label, font=f1, fill=colour)
    if value is not None:
        d.text((x + 10, y + 24), v, font=f2, fill=INK)


def align(stock_png, opt_png):
    """Both shots as the same world region: the stock one cropped to the optimized one's rectangle (found by the edge
    correlation of a 4x downscale when the sizes differ)."""
    a, b = Image.open(stock_png).convert("RGB"), Image.open(opt_png).convert("RGB")
    if a.size == b.size:
        return a, b
    A, B = (np.asarray(x.convert("L"), np.float32)[::4, ::4] for x in (a, b))
    edge = lambda x: (np.abs(np.diff(x, axis=1))[:-1, :] + np.abs(np.diff(x, axis=0))[:, :-1])
    A, B = edge(A), edge(B)
    best = (-2.0, 0, 0)
    for oy in range(A.shape[0] - B.shape[0] + 1):
        for ox in range(A.shape[1] - B.shape[1] + 1):
            c = np.corrcoef(A[oy:oy + B.shape[0], ox:ox + B.shape[1]][40:-40, 40:-40].ravel(), B[40:-40, 40:-40].ravel())[0, 1]
            if c > best[0]:
                best = (c, ox * 4, oy * 4)
    _, ox, oy = best
    print(f"align: optimized shot at ({ox},{oy}) of the stock one, correlation {best[0]:.2f}")
    return a.crop((ox, oy, ox + b.width, oy + b.height)), b


def square(im, cx, cy, side):
    return im.crop((cx - side // 2, cy - side // 2, cx + side // 2, cy + side // 2)).resize((S, S), Image.LANCZOS)


def rec_frames(rd, start, length, crop, rate, denoise="hqdn3d=2:2:10:10"):
    """Tone-mapped S x S frames of the run's recording (PQ capture of an SDR game: SDR white at 203 nits)."""
    cx, cy, side = crop
    vf = (f"fps={rate},crop={side}:{side}:{cx - side // 2}:{cy - side // 2}" + (f",{denoise}" if denoise else "")
          + f",scale={S}:{S}:in_color_matrix=bt2020:in_range=tv:out_range=pc:flags=area,format=rgb48le")
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-ss", f"{start:.3f}", "-t", f"{length:.3f}", "-i",
                          str(rd / "recording.mp4"), "-vf", vf, "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    n = len(raw) // (S * S * 6)
    f = np.frombuffer(raw[:n * S * S * 6], dtype="<u2").reshape(n, S, S, 3).astype(np.float32) / 65535.0
    m = np.array([[1.6605, -0.5876, -0.0728], [-0.1246, 1.1329, -0.0083], [-0.0182, -0.1006, 1.1187]], np.float32)
    out = []
    for i in range(n):
        lin = np.clip((pq_to_nits(f[i]) / REF_NITS) @ m.T, 0.0, 1.0)
        srgb = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * np.power(lin, 1 / 2.4) - 0.055)
        out.append(Image.fromarray((srgb * 255.0 + 0.5).astype(np.uint8)))
    return out


def route_start(rd):
    """Seconds from the recording's start to the route start: schedule.log's 'route starts at +M s', else (runs without
    one, e.g. the horde-shoot bench) pzopt-bench.out's route_start_epoch_ms against the recording's first frame (the
    file's mtime minus its duration, as harness/sun-card-gif.py)."""
    sched = rd / "schedule.log"
    if sched.exists():
        m = re.search(r"route starts at \+([\d.]+) s", sched.read_text())
        if m:
            return float(m.group(1))
    epoch = int(re.search(r"route_start_epoch_ms=(\d+)", (rd / "pzopt-bench.out").read_text()).group(1)) / 1000.0
    rec = rd / "recording.mp4"
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(rec)],
                               capture_output=True, text=True).stdout.strip())
    return epoch - (rec.stat().st_mtime - dur)


def emit(frames, rate, name, plans=((128, 0), (96, 0), (96, 40), (80, 60), (64, 80))):
    """Write the frames as a GIF, stepping down colours / lossy LZW until it fits Steam's limit."""
    OUT.mkdir(parents=True, exist_ok=True)
    out = str(OUT / name)
    work = Path(tempfile.mkdtemp(prefix="pzopt-wsgif-"))
    try:
        for i, im in enumerate(frames):
            im.save(work / f"{i:04d}.png")
        for colours, lossy in plans:
            write_gif(str(work), rate, out, colours=colours, lossy=lossy)
            if os.path.getsize(out) <= LIMIT:
                print(f"{name}: {os.path.getsize(out):,} bytes, {colours} colours, lossy {lossy}")
                return
        sys.exit(f"{name}: {os.path.getsize(out):,} bytes even at the last plan")
    finally:
        shutil.rmtree(work)


def ease(t):
    return t * t * (3 - 2 * t)


KEYWORDS = os.environ.get("PREVIEW_KEYWORDS", "HDR,DLSS,AO,SUN SHADOWS,REFLECTIONS,PER-PIXEL LIGHT").split(",")
CHIPS = 34              # the keyword row above the name band


def chips(d, y, words, h=24):
    """One centred row of keyword chips (solid plates, no bytes while the picture moves), the font shrunk until the row
    fits the width."""
    for size in range(15, 9, -1):
        f, pad, gap = font(size, "bold"), max(6, size - 4), 5
        ws = [f.getlength(w) + 2 * pad for w in words]
        if sum(ws) + gap * (len(ws) - 1) <= S - 12:
            break
    x = (S - sum(ws) - gap * (len(ws) - 1)) / 2
    for w, word in zip(ws, words):
        d.rounded_rectangle((x, y, x + w, y + h), 6, fill=BG, outline=OPT, width=1)
        d.text((x + w / 2, y + h / 2), word, font=f, fill=INK, anchor="mm")
        x += w + gap


def wipe(stock_label, opt_label, crop, out, keywords=None, show_fps=True):
    """A divider sweeping between the aligned stock and optimized `--shot-at` captures, each side's fps tagged above
    the name band (and a keyword row between them when `keywords`)."""
    st, op = run(stock_label), run(opt_label)
    a, b = align(st / "shot-game.png", op / "shot-game.png")
    cx, cy, side = crop
    A, B = square(a, cx, cy, side), square(b, cx, cy, side)
    fs, fo = fps(st), fps(op)
    rate, sweep, hold = 20, 1.6, 0.9
    xs = ([S] * round(hold * rate) + [S - ease(i / (sweep * rate)) * S for i in range(round(sweep * rate))]
          + [0] * round(hold * rate) + [ease(i / (sweep * rate)) * S for i in range(round(sweep * rate))])
    bottom = S - BAND - (CHIPS if keywords else 0)
    frames = []
    for x in xs:  # stock left of the divider, optimized right of it
        x = int(round(x))
        im = B.copy()
        im.paste(A.crop((0, 0, x, S)), (0, 0))
        d = ImageDraw.Draw(im)
        if 0 < x < S:
            d.rectangle((x - 1, 0, x + 1, bottom), fill=INK)
        tag(d, (10, bottom - 76), STOCK_LABEL if show_fps else "STOCK", fps_text(fs) if show_fps else None, STOCK)
        tag(d, (S - 10, bottom - 76), "OPTIMIZED + NEW LIGHTING", fps_text(fo) if show_fps else None, OPT, anchor="ra")
        if keywords:
            d.rectangle((0, bottom, S, S - BAND), fill=BG)
            chips(d, bottom + 4, keywords)
        band(im, "FPS + lighting")
        frames.append(im)
    emit(frames, rate, out)


def night():
    # the lit room, the player at its door and the lawn below (the optimized capture's loot window sits above y 300);
    # the tags sit on the dark lawn above the band: the room fills the top of the crop
    crop = tuple(int(v) for v in os.environ.get("NIGHT_CROP", "2200,800,1000").split(","))
    wipe("wsgif-night-stock244", "wsgif-night-opt", crop, "1-night-wipe.gif")


def rsnight():
    # the reflections-work spot below the Sunset bar on the Riverside pier (the deck edge at 6402,5189, water screen-below the feet: harness/stand-spot-judge.py) at 23:00 with
    # a torch and lights=on (grid power + every switch within 50 tiles),
    # with the keyword row (HDR and DLSS cannot show in the GIF: the fps are measured with the upscaler off)
    crop = tuple(int(v) for v in os.environ.get("RSNIGHT_CROP", "2600,760,1400").split(","))
    # no fps by default (RSNIGHT_FPS=1 puts them back): the river view is GPU-bound with every enhancement on, 226 fps
    # uncapped against stock's 232 at its 244 cap (runs wsgif-rsnight6-*, GPU 93 %)
    wipe("wsgif-rsnight6-stock244", "wsgif-rsnight6-opt", crop, "1b-night-wipe-riverside.gif", KEYWORDS,
         show_fps=os.environ.get("RSNIGHT_FPS", "0") == "1")


def horde():
    # the Louisville preset's own walk at zoom 1.5 (wsgif-louwalk-*): the end of the walk crosses a parking lot full of
    # zombies. (The horde-shoot pier gave only 162 -> 181 fps with every enhancement on, GPU-bound at 92 %; its
    # 500-zombie stock run never started its route.)
    st, op = run("wsgif-louwalk-stock244"), run("wsgif-louwalk-opt")
    fs, fo = fps(st), fps(op)
    loaded = int(re.search(r"zombies_loaded=(\d+)", (op / "pzopt-bench.out").read_text()).group(1))
    crop = tuple(int(v) for v in os.environ.get("HORDE_CROP", "2560,1300,1400").split(","))
    rate, length = 6, 3.0   # the camera walks 6 tiles/s: every pixel changes, so few frames
    t0 = route_start(op) + float(os.environ.get("HORDE_T", "21.8"))
    frames = rec_frames(op, t0, length, crop, rate, denoise="hqdn3d=3:3:12:12")
    stock_rgb, opt_rgb = (Image.new("RGB", (1, 1), c).getpixel((0, 0)) for c in (STOCK, OPT))
    who = f"Louisville, {loaded // 100 * 100:,}+ zombies loaded"
    out = []
    for i, im in enumerate(frames):
        k = ease(min(1.0, max(0.0, (i - 1) / 7)))
        colour = tuple(int(round(a + (b - a) * k)) for a, b in zip(stock_rgb, opt_rgb))
        d = ImageDraw.Draw(im)
        d.rounded_rectangle((10, 10, 20 + font(20, "bold").getlength(who), 44), 8, fill=BG)
        d.text((20, 14), who, font=font(20, "bold"), fill=INK)
        if k > 0:  # the reference line once the count has left it
            text(d, (S / 2, S - BAND - 118), f"stock {fps_text(fs)} fps", 26, fill=STOCK, anchor="mm")
        text(d, (S / 2, S - BAND - 58), f"{fs + (fo - fs) * k:.0f} fps", 76, fill=colour, anchor="mm", stroke=5)
        band(im)
        out.append(im)
    emit(out, rate, "2-horde-counter.gif", plans=((96, 40), (80, 80), (64, 120), (64, 160), (48, 200)))


def storm():
    op = run("wsgif-storm2-opt")
    fo = fps(op)
    crop = tuple(int(v) for v in os.environ.get("STORM_CROP", "2560,1000,1500").split(","))
    rate, length = 5, 3.0   # rain streaks are noise to LZW (~50 KB a frame), so few frames; a strong temporal
    # denoise (hqdn3d 18) fits more frames but wipes the rain out of the picture
    t_route = route_start(op)
    # the brightest frame of the route (a lightning strike) a third of the way into the loop
    probe = rec_frames(op, t_route, 18.0, crop, 5, denoise="")
    luma = [float(np.asarray(f.convert("L")).mean()) for f in probe]
    peak = t_route + int(np.argmax(luma)) / 5.0
    print(f"storm: strike at +{peak - t_route:.1f} s of the route (luma {max(luma):.0f} vs median {np.median(luma):.0f})")
    frames = rec_frames(op, max(t_route, peak - length / 3), length, crop, rate,
                        denoise=os.environ.get("STORM_DENOISE", "hqdn3d=2:2:5:5"))
    for im in frames:
        d = ImageDraw.Draw(im)
        tag(d, (S - 10, S - BAND - 76), "THUNDERSTORM AT NIGHT", fps_text(fo), OPT, anchor="ra")
        band(im, "FPS + lighting")
    emit(frames, rate, "3-storm.gif", plans=((96, 40), (80, 80), (64, 120), (64, 160), (48, 200)))


def cuts():
    st, op = run("wsgif-day-stock244"), run("wsgif-day-opt")
    a, b = align(st / "shot-game.png", op / "shot-game.png")
    cx, cy, side = (int(v) for v in os.environ.get("CUTS_CROP", "2900,1050,1300").split(","))
    A, B = square(a, cx, cy, side), square(b, cx, cy, side)
    fs, fo = fps(st), fps(op)
    rate = 10
    title = B.filter(ImageFilter.GaussianBlur(6)).point(lambda v: int(v * 0.45))
    d = ImageDraw.Draw(title)
    text(d, (S / 2, S / 2 - 30), NAME, 52, anchor="mm", stroke=0)
    text(d, (S / 2, S / 2 + 28), "stock vs optimized", 26, fill=OPT, anchor="mm", weight="semibold", stroke=0)
    frames = [title] * round(1.0 * rate)
    for img, label, value, colour, secs in ((A, STOCK_LABEL, fps_text(fs), STOCK, 1.6),
                                            (B, "OPTIMIZED + NEW LIGHTING", fps_text(fo), OPT, 2.0)):
        im = img.copy()
        d = ImageDraw.Draw(im)
        d.rounded_rectangle((10, 10, 22 + font(22, 'bold').getlength(label), 46), 8, fill=BG)
        d.text((21, 14), label, font=font(22, "bold"), fill=colour)
        text(d, (S / 2, S - BAND - 52), f"{value} fps", 72, fill=colour, anchor="mm", stroke=5)
        band(im)
        frames += [im] * round(secs * rate)
    emit(frames, rate, "4-three-cuts.gif")


if __name__ == "__main__":
    todo = sys.argv[1:] or ["night", "horde", "storm", "cuts"]
    for name in todo:
        {"night": night, "rsnight": rsnight, "horde": horde, "storm": storm, "cuts": cuts}[name]()
