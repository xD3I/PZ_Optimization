#!/usr/bin/env python3
"""Offline rig for pzopt.GroundTiling: the real GLSL (GroundTiling.GLSL) on Mesa llvmpipe, a field of one natural
material at 1:1 (2x tiles), stock vs the tiling modes, plus the tileability metrics.

    rig.py field OUT.png --mat grass [--mode stock|hex|hpb|bomb|voronoi|two|wang] [--n 2] [--seam poisson]
                         [--cell 1.5] [--contrast 0.6] [--exp 7] [--gain 0.5] [--size 1024x512] [--origin X,Y]
    rig.py sweep OUTDIR --mat grass [...]     # every mode, one PNG each + a metrics table
    rig.py seamcheck --mat grass --mode hex   # chunk-edge continuity: two chunk origins render the same texels

Materials (blends_natural_01 groups; full variants g, g+5, g+6, g+7): sand 0, grass 16, grasslight 32, grassdry 48,
dirt 64, dirtgrass 80, dirt2 96. Run under build/venv; uses llvmpipe so the 4090 stays free for measuring runs.
"""
import argparse, math, os, re, sys, textwrap
os.environ.setdefault("__EGL_VENDOR_LIBRARY_FILENAMES", "/usr/share/glvnd/egl_vendor.d/50_mesa.json")
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import art  # noqa: E402
import metric  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SPRITES = os.path.join(ROOT, "build", "ground-tiling", "sprites")
MATS = {"sand": 0, "grass": 16, "grasslight": 32, "grassdry": 48, "dirt": 64, "dirtgrass": 80, "dirt2": 96}
MODES = {"stock": 0, "hex": 1, "hpb": 2, "bomb": 3, "voronoi": 4, "two": 5, "lap": 6}


def lib():
    src = open(os.path.join(ROOT, "src", "pzopt", "pzopt", "GroundTiling.java")).read()
    m = re.search(r'String GLSL = """\n(.*?)\n\s*""";', src, re.S)
    return textwrap.dedent(m.group(1)) + "\n"


def variant_ids(mat):
    g = MATS[mat]
    return [str(g), str(g + 5), str(g + 6), str(g + 7)]


def height_of(rgb):
    """per-texel height for the blend weights: luminance, normalised to the material's 1..99 % range."""
    l = rgb @ np.array([0.299, 0.587, 0.114], np.float32)
    lo, hi = np.percentile(l, [1, 99])
    return np.clip((l - lo) / max(hi - lo, 1e-4), 0, 1)


LUT_W = 260  # 257 inverse-CDF entries (Gaussian value 0..1 in 1/256 steps) + room for the basis row's 4 texels


def gaussianise(img):
    """Heitz-Neyret full version: PCA-decorrelate the rgb, histogram-transform each decorrelated channel to a
    Gaussian (mean 0.5, sd 1/6); returns the Gaussian image (a = height kept) and the float LUT (3+1 rows)."""
    from scipy.special import ndtri, ndtr
    H, W, _ = img.shape
    rgb = img[..., :3].reshape(-1, 3).astype(np.float64)
    mu = rgb.mean(0)
    cov = np.cov((rgb - mu).T)
    ev, E = np.linalg.eigh(cov)  # columns = basis
    d = (rgb - mu) @ E
    g = np.zeros_like(img)
    lut = np.zeros((4, LUT_W, 4), np.float32)
    n = 256
    x = np.arange(n + 1) / n
    q = np.clip(ndtr((x - 0.5) * 6.0), 0, 1)
    for c in range(3):
        v = d[:, c]
        order = np.argsort(v, kind="stable"); ranks = np.empty_like(order); ranks[order] = np.arange(v.size)
        u = (ranks + 0.5) / v.size
        g[..., c] = np.clip(0.5 + ndtri(u).reshape(H, W) / 6.0, 0, 1)
        lut[c, :n + 1, 0] = np.quantile(v, q)
    for k in range(3):
        lut[3, k, :3] = E[:, k]
    lut[3, 3, :3] = mu
    g[..., 3] = img[..., 3]
    return g, lut


class Rig:
    def __init__(self):
        import moderngl
        self.ctx = moderngl.create_standalone_context(backend="egl")
        self.mgl = moderngl

    def textures(self, ex, gauss, lut):
        H, W, _ = ex.shape
        ctx = self.ctx
        def arr(a):
            t = ctx.texture_array((a.shape[1], a.shape[0], 1), 4, (np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8).tobytes())
            t.filter = (self.mgl.NEAREST, self.mgl.NEAREST)
            return t
        self.tArt = arr(ex); self.tGauss = arr(gauss)
        self.tArt.build_mipmaps(); self.tArt.filter = (self.mgl.LINEAR_MIPMAP_LINEAR, self.mgl.LINEAR)
        self.tLut = ctx.texture_array((lut.shape[1], lut.shape[0], 1), 4, lut.astype(np.float32).tobytes(), dtype="f4")
        self.tLut.filter = (self.mgl.NEAREST, self.mgl.NEAREST)
        self.period = (W, H)

    def render(self, mode, size, origin_sq, cell, contrast, exp, gain, seed=1, view_px=None):
        W, H = size
        ctx = self.ctx
        fs = "#version 330\n" + os.environ.get("GT_DEFINES", "").replace(";", "\n") + "\n" + lib() + textwrap.dedent("""
            uniform ivec2 uOriginPx;   // screen px of the view's top-left texel (exact)
            uniform ivec2 uChunkSq;    // the chunk origin (world squares, integer)
            uniform int uRows;
            out vec4 o;
            void main() {
               ivec2 px = uOriginPx + ivec2(int(gl_FragCoord.x), uRows - 1 - int(gl_FragCoord.y));
               // world squares of the texel centre relative to the chunk origin (whose diamond top is at px (63, 0) of
               // its own screen origin 64 (X0 - Y0), 32 (X0 + Y0))
               ivec2 cpx = ivec2(64 * (uChunkSq.x - uChunkSq.y), 32 * (uChunkSq.x + uChunkSq.y));
               vec2 c = vec2(px - cpx) + 0.5 - vec2(63.0, 0.0);
               vec2 w = vec2(c.x / 64.0 + c.y / 32.0, c.y / 32.0 - c.x / 64.0) * 0.5;
               vec4 s = gtSample(px, w, 0.0, pzGtPeriod.z);
               o = vec4(s.rgb, 1.0);
            }
        """)
        vs = "#version 330\nvoid main(){ vec2 p = vec2((gl_VertexID<<1)&2, gl_VertexID&2); gl_Position = vec4(p*2.0-1.0, 0.0, 1.0); }"
        prog = ctx.program(vertex_shader=vs, fragment_shader=fs)
        def u(name, v):
            if name in prog:
                prog[name].value = v
        self.tArt.use(0); self.tGauss.use(1); self.tLut.use(2)
        u("pzGtArt", 0); u("pzGtGauss", 1); u("pzGtLut", 2)
        u("pzGtPeriod", (self.period[0], self.period[1], MODES[mode], seed))
        u("pzGtHex", (cell, contrast, exp, gain))
        X0, Y0 = origin_sq
        sk = skew(X0, Y0, cell); cx, cy = X0 / cell, Y0 / cell
        u("pzGtOI", tuple(int(math.floor(v)) for v in (sk[0], sk[1], cx, cy)))
        u("pzGtOF", tuple(float(v - math.floor(v)) for v in (sk[0], sk[1], cx, cy)))
        u("uChunkSq", (X0, Y0))
        u("uRows", H)
        u("uOriginPx", view_px or view_origin(origin_sq, size))
        fbo = ctx.simple_framebuffer((W, H), components=4)
        fbo.use(); fbo.clear(0, 0, 0, 1)
        vao = ctx.vertex_array(prog, [])
        vao.render(vertices=3)
        img = np.frombuffer(fbo.read(components=4), np.uint8).reshape(H, W, 4)[::-1].astype(np.float32) / 255.0
        fbo.release(); vao.release(); prog.release()
        return img


def mix32(i, j, seed):
    """a decent integer hash of a square (the map's variant choice is random, not periodic)."""
    with np.errstate(over="ignore"):
        x = (np.asarray(i).astype(np.int64) & 0xffffffff).astype(np.uint32)
        y = (np.asarray(j).astype(np.int64) & 0xffffffff).astype(np.uint32)
        h = x * np.uint32(0x9E3779B1) ^ (y * np.uint32(0x85EBCA77)) ^ np.uint32(seed * 0xC2B2AE3D & 0xffffffff)
        h ^= h >> np.uint32(15); h *= np.uint32(0x2C1B3C6D); h ^= h >> np.uint32(12); h *= np.uint32(0x297A2D39); h ^= h >> np.uint32(15)
    return h


def view_origin(origin_sq, size):
    X0, Y0 = origin_sq
    return (64 * (X0 - Y0) - size[0] // 2, 32 * (X0 + Y0))


def skew(wx, wy, cell):
    k = 3.4641016151377544 / cell; sx, sy = wx * k, wy * k
    return sx - 0.5773502691896258 * sy, 1.1547005383792515 * sy


def stock_field(mat, size, origin_sq, seed=1, layout=None):
    """the game's look: one painted variant per square (the map's choice, here a hash)."""
    W, H = size
    tiles = art.load_tiles(SPRITES, variant_ids(mat))
    X0, Y0 = origin_sq
    ox = 64 * (X0 - Y0) - W // 2; oy = 32 * (X0 + Y0)
    X, Y = np.meshgrid(np.arange(W) + ox, np.arange(H) + oy)
    cxp = 64 * (X0 - Y0); cyp = 32 * (X0 + Y0)
    cx = X - cxp + 0.5 - 63.0; cy = Y - cyp + 0.5
    u = (cx / 64.0 + cy / 32.0) * 0.5; v = (cy / 32.0 - cx / 64.0) * 0.5
    i = np.floor(u).astype(int); j = np.floor(v).astype(int)
    lx = X - cxp - 64 * (i - j); ly = Y - cyp - 32 * (i + j)
    gi = i + X0; gj = j + Y0
    if layout is None:
        k = (mix32(gi, gj, seed) >> np.uint32(8)) % np.uint32(len(tiles))
    else:
        k = layout(gi, gj)
    out = np.zeros((H, W, 4), np.float32)
    stack = np.stack(tiles)
    out[...] = stack[k, ly.clip(0, 63), lx.clip(0, 127)]
    out[..., 3] = 1.0
    return out


def wang_layout(mat, seed=1):
    """edge-matched variant choice: each square takes the variant whose NW / NE edges continue the already chosen
    neighbours best (deterministic scan from a hashed start; seam cost from the variants' own edge rows)."""
    tiles = art.load_tiles(SPRITES, variant_ids(mat))
    n = len(tiles); L = [t[..., :3] for t in tiles]
    m = art.diamond_mask()
    # edge strips: NE edge of a square (upper-right side) meets the SW edge of the square at (i, j-1)
    def edge(t, side):
        rows = []
        for y in range(32):
            if side == "NE": rows.append(t[y, 63 + 2 * y]);
            elif side == "SW": rows.append(t[32 + y, 2 * y + 1] if 2 * y + 1 < 126 else t[32 + y, 125])
            elif side == "NW": rows.append(t[y, 62 - 2 * y])
            elif side == "SE": rows.append(t[32 + y, 124 - 2 * y] if 124 - 2 * y >= 0 else t[32 + y, 0])
        return np.array(rows)
    cNE = np.array([[np.abs(edge(L[a], "NE") - edge(L[b], "SW")[::-1]).mean() for b in range(n)] for a in range(n)])
    cNW = np.array([[np.abs(edge(L[a], "NW") - edge(L[b], "SE")[::-1]).mean() for b in range(n)] for a in range(n)])
    cache = {}
    def pick(i, j):
        if (i, j) in cache:
            return cache[(i, j)]
        # neighbours already decided in scan order: (i, j-1) NE side, (i-1, j) NW side; bounded recursion via a
        # 4x4 block scan from its corner (deterministic per block, continuity across blocks via the block's edges)
        bi, bj = i - (i % 4), j - (j % 4)
        for jj in range(bj, bj + 4):
            for ii in range(bi, bi + 4):
                if (ii, jj) in cache:
                    continue
                cost = np.zeros(n)
                if jj > bj: cost += cNE[:, cache[(ii, jj - 1)]]
                if ii > bi: cost += cNW[:, cache[(ii - 1, jj)]]
                h = int(mix32(np.array(ii), np.array(jj), seed))
                cost += 1e-3 * ((h >> (3 * np.arange(n))) & 7)  # tie break
                cache[(ii, jj)] = int(np.argmin(cost))
        return cache[(i, j)]
    return np.vectorize(pick), cNE, cNW


def make_art(mat, n, seam, seed=1):
    ex, lab, lay, raw, fixed = art.build(SPRITES, variant_ids(mat), n, seam, seed)
    ex[..., 3] = height_of(ex[..., :3])
    gauss, lut = gaussianise(ex)
    return ex, gauss, lut, raw, fixed


def save(img, path):
    Image.fromarray((np.clip(img[..., :3], 0, 1) * 255 + 0.5).astype(np.uint8), "RGB").save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["field", "sweep", "seamcheck"])
    ap.add_argument("out", nargs="?")
    ap.add_argument("--mat", default="grass"); ap.add_argument("--mode", default="hex")
    ap.add_argument("--n", type=int, default=2); ap.add_argument("--seam", default="self")
    ap.add_argument("--cell", type=float, default=1.5); ap.add_argument("--contrast", type=float, default=0.6)
    ap.add_argument("--exp", type=float, default=7.0); ap.add_argument("--gain", type=float, default=0.5)
    ap.add_argument("--size", default="1024x512"); ap.add_argument("--origin", default="10000,7000")
    ap.add_argument("--modes", default="stock,wang,hex,hpb,lap,bomb,voronoi,two")
    a = ap.parse_args()
    W, H = map(int, a.size.split("x")); org = tuple(map(int, a.origin.split(",")))
    ex, gauss, lut, raw, fixed = make_art(a.mat, a.n, a.seam)
    rig = Rig(); rig.textures(ex, gauss, lut)
    ref = ex[..., :3]

    def one(mode):
        if mode == "stock":
            return stock_field(a.mat, (W, H), org)
        if mode == "wang":
            lay, _, _ = wang_layout(a.mat)
            return stock_field(a.mat, (W, H), org, layout=lay)
        return rig.render(mode, (W, H), org, a.cell, a.contrast, a.exp, a.gain)

    if a.cmd == "field":
        img = one(a.mode); save(img, a.out)
        print(a.mode, metric.summary(img, ref, *view_origin(org, (W, H))))
    elif a.cmd == "sweep":
        os.makedirs(a.out, exist_ok=True)
        print(f"art {a.mat} n={a.n} seam={a.seam}: seam energy {raw:.3f} -> {fixed:.3f}; cell={a.cell} contrast={a.contrast} exp={a.exp}")
        for mode in a.modes.split(","):
            img = one(mode); save(img, os.path.join(a.out, f"{a.mat}-{mode}.png"))
            print(f"{mode:8s}", metric.summary(img, ref, *view_origin(org, (W, H))))
    elif a.cmd == "seamcheck":
        # the same world texels rendered from two chunk origins 8 squares apart must be identical
        X0, Y0 = org
        A = rig.render(a.mode, (W, H), (X0, Y0), a.cell, a.contrast, a.exp, a.gain)
        # chunk origin moved by (+8, 0): screen origin moves by (512, 256); render the same view
        v = view_origin((X0, Y0), (W, H))
        for dx, dy in ((8, 0), (0, 8), (-8, 16), (1000, -3000)):
            B = rig.render(a.mode, (W, H), (X0 + dx, Y0 + dy), a.cell, a.contrast, a.exp, a.gain, view_px=v)
            d = np.abs(A - B)
            print(f"chunk origin +({dx},{dy}): max diff {d.max() * 255:.1f}/255, texels differing {(d.max(-1) > 0.5 / 255).mean() * 100:.3f} %")


if __name__ == "__main__":
    main()
