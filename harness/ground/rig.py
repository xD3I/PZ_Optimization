#!/usr/bin/env python3
"""Offline shader rig for the ground and grass floor patches (2026-10-24, ground-common).

Draws a field of the game's own floor sprites the way a chunk bake draws them, through the ground floor variant that
pzopt.GroundBake builds in game (the exact source: tools/ground/VariantSource.java calls GroundBake.variantSource on
the game's tileWithDepth.frag), with the class map and ground state the variant reads, and writes a PNG. Iterate on a
patch here before a game run.

    python3 harness/ground/rig.py [--map MAP] [--patch FILE:TEXELFN:COLORFN ...] [--view 0|1|2] [--piece 0|1|2]
                                  [--state FILE.png] [--uniform NAME=V ...] [--sampler NAME=FILE.png ...]
                                  [--size N] [--origin X,Y] [--stock tileWithDepth|opaqueWithDepth] [--mesa]
                                  [--out out.png] [--compare]

  --map      a material grid, one row a line, world x to the right and y down: g grass (G light, h dark), d dirt,
             D dark dirt, s sand, v gravel, c clay, . road / not ground; or the lines of a run's ground_map
             (`harness: ground map y=...`), or a run directory (its console's ground map). Default: a synthetic 24 x 24
             field (grass with a dirt track and a sand patch).
  --patch    a GLSL file with the patch's declarations and the names of its texel / colour functions ("-" for none);
             several in order, as the game chains them.
  --compare  also draw the stock program (no patch) and write <out>-stock.png and a side-by-side <out>-ab.png.
  --mesa     llvmpipe instead of the driver.

The art comes from the game's Tiles2x.pack (harness/mirrors/packsprite.py), cached in build/ground-rig/. Needs only
numpy and Pillow (system python); the GPU part is tools/ground/floorrig.c (a compatibility context: moderngl's EGL
contexts are core and the game's tile programs are GLSL 1.20 / 1.30 with varyings).
"""
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build" / "ground-rig"
GAME = Path(os.environ.get("PZ_DIR", "/games/steamapps/common/ProjectZomboid/projectzomboid"))
sys.path.insert(0, str(ROOT / "harness" / "mirrors"))

# material letter -> (class map code (GroundTiles.mapByte of a base piece), sprites to pick from by hash)
MAT = {
    "g": (1 | 16, ["blends_natural_01_32", "blends_natural_01_37", "blends_natural_01_38", "blends_natural_01_39"]),
    "G": (1 | 16, ["blends_natural_01_16", "blends_natural_01_21", "blends_natural_01_22", "blends_natural_01_23"]),
    "h": (1 | 16, ["blends_natural_01_48", "blends_natural_01_53", "blends_natural_01_54", "blends_natural_01_55"]),
    "d": (2 | 16, ["blends_natural_01_64", "blends_natural_01_69", "blends_natural_01_70", "blends_natural_01_71"]),
    "D": (9 | 16, ["blends_natural_01_80", "blends_natural_01_85", "blends_natural_01_86"]),
    "s": (3 | 16, ["blends_natural_01_0", "blends_natural_01_5", "blends_natural_01_6", "blends_natural_01_7"]),
    "v": (4 | 16, ["floors_exterior_natural_01_13"]),
    "c": (8 | 16, ["blends_natural_01_96", "blends_natural_01_101", "blends_natural_01_102", "blends_natural_01_103"]),
    ".": (0, ["blends_street_01_85"]),
}


def synthetic(n=24):
    rows = []
    for y in range(n):
        r = ""
        for x in range(n):
            if abs((x - y) - 2) <= 1:
                r += "d"
            elif (x - 16) ** 2 + (y - 6) ** 2 < 14:
                r += "s"
            elif x + y > 34:
                r += "h"
            else:
                r += "g"
        rows.append(r)
    return rows


def read_map(arg):
    if not arg:
        return synthetic()
    p = Path(arg)
    text = (p / "console.txt").read_text(errors="replace") if p.is_dir() else p.read_text(errors="replace")
    gm = re.findall(r"ground map y=-?\d+ x=\S+: (\S+)", text)
    if gm:
        conv = {"#": ".", "P": "g", "W": ".", "g": "g", "G": "g", "d": "d", "D": "D", "s": "s", "S": "s", "v": "v", "V": "v", "c": "c", "C": "c"}
        return ["".join(conv.get(ch, ".") for ch in row) for row in gm]
    return [l.rstrip("\n") for l in text.splitlines() if l.strip()]


def sprites(names):
    """name -> RGBA array (the full frame, Tiles2x), extracted once into build/ground-rig/sprites/."""
    d = BUILD / "sprites"
    d.mkdir(parents=True, exist_ok=True)
    for pack in ("Tiles2x.floor.pack", "Tiles2x.pack"):  # (B42 keeps the floors in their own pack)
        missing = [n for n in names if not (d / (n + ".png")).exists()]
        if missing:
            subprocess.run([sys.executable, str(ROOT / "harness" / "mirrors" / "packsprite.py"), str(d), "--pack", pack] + missing,
                           check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    out = {}
    for n in names:
        f = d / (n + ".png")
        if f.exists():
            out[n] = np.array(Image.open(f).convert("RGBA"))
    return out


def tool(name, src, libs):
    b = ROOT / "build" / name
    s = ROOT / src
    if not b.exists() or b.stat().st_mtime < s.stat().st_mtime:
        subprocess.run(["cc", "-O2", "-o", str(b), str(s)] + libs, check=True)
    return b


def variant(stock, view, piece, patches):
    jar = GAME / "projectzomboid.jar"
    out = ROOT / "build" / "ground-tools"
    src = ROOT / "tools" / "ground" / "VariantSource.java"
    cls = out / "pzopt" / "VariantSource.class"
    cp = f"{ROOT / 'build' / 'classes'}:{jar}"
    if not cls.exists() or cls.stat().st_mtime < src.stat().st_mtime:
        out.mkdir(parents=True, exist_ok=True)
        subprocess.run(["javac", "-nowarn", "-cp", cp, "-d", str(out), str(src)], check=True)
    r = subprocess.run(["java", "-cp", f"{out}:{cp}", "pzopt.VariantSource", str(GAME / "media" / "shaders" / (stock + ".frag")), str(view), str(piece)] + patches,
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("variant source: " + r.stderr)
    return r.stdout


def hash01(x, y, k):
    h = (x * 73856093) ^ (y * 19349663) ^ (k * 83492791)
    return (h & 0xFFFF) / 65536.0


def render(args, rows, frag_src, tag):
    BUILD.mkdir(parents=True, exist_ok=True)
    H = len(rows)
    W = max(len(r) for r in rows)
    names = sorted({n for r in rows for ch in r for n in MAT.get(ch, MAT["."])[1]})
    art = sprites(names)
    if not art:
        sys.exit("no sprites (is the game at " + str(GAME) + "?)")
    # the atlas: the sprites in one row
    fw = max(a.shape[1] for a in art.values())
    fh = max(a.shape[0] for a in art.values())
    keys = list(art)
    atlas = np.zeros((fh, fw * len(keys), 4), np.uint8)
    for i, k in enumerate(keys):
        a = art[k]
        atlas[:a.shape[0], i * fw:i * fw + a.shape[1]] = a
    (BUILD / "atlas.rgba").write_bytes(atlas.tobytes())
    ox, oy = (int(v) for v in args.origin.split(","))
    # class map: 512 x 512, row = world y & 511
    cm = np.zeros((512, 512), np.uint8)
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            cm[(oy + y) & 511, (ox + x) & 511] = MAT.get(ch, MAT["."])[0]
    (BUILD / "class.r8").write_bytes(cm.tobytes())
    # layout: logical px of world (x, y): u = x - y, v = x + y; 64 px a unit of u, 32 of v at Tiles2x (ts 2)
    TS = 2
    px_u, px_v = 32 * TS, 16 * TS
    U0 = (ox - (oy + H)) - 1  # leftmost u
    V0 = (ox + oy) - 6        # topmost v (room for the sprite frame above the diamond)
    size_w = (W + H + 2) * px_u
    size_h = (W + H + 8) * px_v
    lines = [f"size {size_w} {size_h}", f"frag {BUILD / ('variant-' + tag + '.frag')}", f"atlas {BUILD / 'atlas.rgba'} {atlas.shape[1]} {atlas.shape[0]}",
             f"class {BUILD / 'class.r8'}", f"out {BUILD / ('out-' + tag + '.rgba')}",
             f"map {1.0 / px_u} {U0} {1.0 / px_v} {V0}", f"bake 0 {args.view}", "stateinfo 8 1024 0 0"]
    (BUILD / ("variant-" + tag + ".frag")).write_text(frag_src)
    if args.state:
        st = np.array(Image.open(args.state).convert("RGBA").resize((1024, 1024)))
        (BUILD / "state.rgba").write_bytes(st.tobytes())
        lines += [f"state {BUILD / 'state.rgba'}", "stateinfo 8 1024 0 1"]
    for u in args.uniform:
        k, v = u.split("=")
        lines.append(f"uniform1f {k} {v}")
    for i, sm in enumerate(args.sampler):
        k, f = sm.split("=")
        im = np.array(Image.open(f).convert("RGBA"))
        p = BUILD / f"sampler{i}.rgba"
        p.write_bytes(im.tobytes())
        lines.append(f"sampler {k} {58 + i} {p} {im.shape[1]} {im.shape[0]}")
    # painter's order: by x + y
    cells = sorted(((x, y, ch) for y, r in enumerate(rows) for x, ch in enumerate(r)), key=lambda c: (c[0] + c[1], c[0]))
    for x, y, ch in cells:
        wx, wy = ox + x, oy + y
        opts = [n for n in MAT.get(ch, MAT["."])[1] if n in art]
        if not opts:
            continue
        n = opts[int(hash01(wx, wy, 1) * len(opts))]
        a = art[n]
        i = keys.index(n)
        cx = ((wx - wy) - U0) * px_u        # the square's north corner, logical px
        cy = ((wx + wy) - V0) * px_v
        fwi, fhi = a.shape[1], a.shape[0]
        x0 = cx - fwi / 2
        y0 = cy - (fhi - 2 * px_v)          # the diamond is the frame's bottom 2 * px_v rows
        u0 = i * fw / atlas.shape[1]
        u1 = (i * fw + fwi) / atlas.shape[1]
        v1 = fhi / atlas.shape[0]
        lines.append(f"quad {x0} {y0} {x0 + fwi} {y0 + fhi} {u0} 0 {u1} {v1} 1 1 1 1")
    job = BUILD / ("job-" + tag + ".txt")
    job.write_text("\n".join(lines) + "\n")
    env = dict(os.environ)
    if args.mesa:
        env["__EGL_VENDOR_LIBRARY_FILENAMES"] = "/usr/share/glvnd/egl_vendor.d/50_mesa.json"
        env["LIBGL_ALWAYS_SOFTWARE"] = "1"
    rig = tool("floorrig", "tools/ground/floorrig.c", ["-lEGL", "-lGL"])
    r = subprocess.run([str(rig), str(job)], env=env, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("floorrig: " + r.stderr[-4000:])
    raw = np.frombuffer((BUILD / ("out-" + tag + ".rgba")).read_bytes(), np.uint8).reshape(size_h, size_w, 4)
    img = raw.copy()  # rows: logical y 0 first = the picture's top (the bake's flipped projection)
    # on a dark backdrop, alpha composited
    bg = np.full_like(img[..., :3], 20)
    a = img[..., 3:4] / 255.0
    return (img[..., :3] * a + bg * (1 - a)).astype(np.uint8)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--map", default="")
    ap.add_argument("--patch", action="append", default=[])
    ap.add_argument("--view", type=int, default=0)
    ap.add_argument("--piece", type=int, default=0)
    ap.add_argument("--state", default="")
    ap.add_argument("--uniform", action="append", default=[])
    ap.add_argument("--sampler", action="append", default=[])
    ap.add_argument("--origin", default="8000,11200")
    ap.add_argument("--stock", default="tileWithDepth")
    ap.add_argument("--mesa", action="store_true")
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("--out", default=str(BUILD / "rig.png"))
    args = ap.parse_args()
    rows = read_map(args.map)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    img = render(args, rows, variant(args.stock, args.view, args.piece, args.patch), "variant")
    Image.fromarray(img).save(out)
    print(f"{out}: {img.shape[1]} x {img.shape[0]}, {len(rows)} rows of map")
    if args.compare:
        stock = (GAME / "media" / "shaders" / (args.stock + ".frag")).read_text()
        simg = render(args, rows, stock, "stock")
        sp = out.with_name(out.stem + "-stock.png")
        Image.fromarray(simg).save(sp)
        ab = np.concatenate([simg, img], axis=1)
        Image.fromarray(ab).save(out.with_name(out.stem + "-ab.png"))
        d = np.abs(simg.astype(int) - img.astype(int)).mean()
        print(f"{sp}; mean |variant - stock| {d:.2f} / 255")


if __name__ == "__main__":
    main()
