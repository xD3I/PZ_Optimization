#!/usr/bin/env python3
"""Offline rig for the glint models of pzopt.GlintGlsl (ground cover, 2026-10-24).

Runs the GLSL the game compiles (the LIB block of src/pzopt/pzopt/GlintGlsl.java) on a floor patch seen through PZ's 2:1
dimetric camera, on the desktop GPU through headless EGL (moderngl):

  img    PNGs of every model at several zooms under a point light and the sun        -> <out>/img-*.png
  cost   GPU time of a full 5120x2160 floor per model (timer queries, median of 30)   -> printed
  stats  mean D_P over a large patch against the smooth NDF (energy, bias)           -> printed
  flakes the particle / compact passes' flake model (the game's compute shader, build/shader-dump from GroundCoverTest)
         on a 48 x 48-square frosty patch: energy of the drawn flakes against the smooth NDF's, flakes lit per square
  pcost  GPU time of the particle / compact passes alone (shader-dump programs) over SQUARES (5225) sun-lit squares,
         the data texture's upload timed apart (timer queries, median of 30)              -> printed
  fused  the "fused into the chunk composite" placement, emulated: a full-screen composite-like pass (an RGBA8 chunk
         picture + an R16 depth read, blended into RGBA8) without glints, with db23iso fused behind a per-pixel mask fetch
         (mask 0 / 10 / 100 % of the screen), and the base + a separate masked pass over the same share  -> printed
  ppan   the particle pass's temporal stability, as pan (motion-compensated |I_t - I_t-1| / mean I), 512 x 512
  pan    temporal stability: the camera pans 0.37 px a frame, the light still;       -> printed
         flicker = mean |I_t - I_t-1| / mean I over 48 frames, per model and zoom

Needs the venv: build/venv/bin/python harness/ground/cover/glint_rig.py <mode> [--out DIR]
"""
import argparse
import os
import re
import sys
import time

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
JAVA = os.path.join(ROOT, "src", "pzopt", "pzopt", "GlintGlsl.java")
MODELS = ["db23", "db23iso", "jamsi", "sparkle", "explicit"]


def glsl_lib(name="LIB"):
    s = open(JAVA, encoding="utf-8").read()
    a = s.index("// GLSL-BEGIN " + name)
    b = s.index("// GLSL-END", a)
    block = s[a:b]
    lines = []
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', block[block.index("String.join"):]):
        lit = m.group(1)
        if lit == "\\n":
            continue
        lines.append(lit.encode().decode("unicode_escape"))
    return "\n".join(lines)


FRAG = """
#version 430
%LIB%
uniform vec2 uOff;      // camera offset, grid units (unzoomed px at ts 1)
uniform vec2 uRes;
uniform float uZoom;    // grid units per screen pixel
uniform int uModel;
uniform int uLightKind; // 0 sun / moon (uLight = direction), 1 point (uLight = position, squares)
uniform vec3 uLight;
uniform vec3 uParams;   // facets per grid unit^2, NDF width a, angular cell b
uniform int uSalt;
out vec4 fragColor;
const vec3 V = vec3(0.61237244, 0.61237244, 0.5);
void main() {
   vec2 g = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) * uZoom + uOff;
   // world floor position (squares) from grid space: s = (x - y) 32, t = (x + y) 16
   float xm = g.x / 32.0, xp = g.y / 16.0;
   vec3 p = vec3(0.5 * (xp + xm), 0.5 * (xp - xm), 0.0);
   vec3 L; float E;
   if (uLightKind == 0) { L = normalize(uLight); E = 1.0; }
   else { vec3 d = uLight - p; float r = length(d); L = d / r; E = 6.0 / (1.0 + r * r); }
   float cosI = L.z, cosO = V.z;
   if (cosI <= 0.0) { fragColor = vec4(0.0); return; }
   vec3 H = normalize(L + V);
   vec2 m = H.xy / H.z;
   float rho = uParams.x, a = uParams.y, b = uParams.z;
   vec2 dgx = dFdx(g), dgy = dFdy(g);
   float f = uZoom;
   float D = 0.0;
   if (uModel == 0) D = pzgDb23(g, dgx, dgy, rho, m, a, b, uSalt);
   else if (uModel == 1) D = pzgDb23Iso(g, f, rho, m, a, b, uSalt);
   else if (uModel == 2) D = pzgJamsi(g, f, 0.0, rho, m, a, b);
   else if (uModel == 3) D = pzgSparkle(g, f, m, a, b);
   else if (uModel == 4) D = pzgExplicit(g, f, rho, m, a, b);
   else if (uModel == 5) D = pzgP22(m, a); // the smooth NDF
   else if (uModel == 6) { fragColor = vec4(0.0); return; } // the fill alone
   float c4 = H.z * H.z * H.z * H.z;
   float Dh = D / c4;
   float F = 0.02 + 0.98 * pow(1.0 - dot(L, H), 5.0);
   float spec = F * Dh / (4.0 * cosI * cosO) * cosI * E;
   fragColor = vec4(vec3(spec), 1.0);
}
"""

VERT = """
#version 430
in vec2 pos;
void main() { gl_Position = vec4(pos, 0.0, 1.0); }
"""


class Rig:
    def __init__(self, w, h, cost=False):
        import moderngl
        self.ctx = moderngl.create_standalone_context(backend="egl", require=430)
        src = FRAG.replace("%LIB%", glsl_lib())
        self.prog = self.ctx.program(vertex_shader=VERT, fragment_shader=src)
        vbo = self.ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], dtype="f4").tobytes())
        self.vao = self.ctx.vertex_array(self.prog, [(vbo, "2f", "pos")])
        self.resize(w, h, cost)

    def resize(self, w, h, cost=False):
        self.w, self.h = w, h
        # cost runs write one half float a pixel (the game adds into an RGBA8 / RGBA16F world buffer; an RGBA32F target
        # here measured the bandwidth, not the shader)
        self.tex = self.ctx.texture((w, h), 1 if cost else 4, dtype="f2" if cost else "f4")
        self.fbo = self.ctx.framebuffer(color_attachments=[self.tex])
        if "uRes" in self.prog:
            self.prog["uRes"].value = (float(w), float(h))

    def centre(self, x, y, zoom):
        """uOff that puts world square (x, y) at the image centre."""
        return ((x - y) * 32.0 - self.w * 0.5 * zoom, (x + y) * 16.0 - self.h * 0.5 * zoom)

    def set(self, **kw):
        for k, v in kw.items():
            if k in self.prog:
                self.prog[k].value = v

    def draw(self):
        self.fbo.use()
        self.vao.render()

    def read(self):
        a = np.frombuffer(self.fbo.read(components=4, dtype="f4"), dtype="f4").reshape(self.h, self.w, 4)
        return a[::-1, :, 0].copy()


DEFAULT = dict(rho=0.08, a=0.45, b=0.035)  # frost: ~0.08 flakes per px^2 at ts 1 (5 per 64-px floor row), wide crystals


def tonemap(x, exp=1.0):
    y = 1.0 - np.exp(-np.maximum(x, 0) * exp)
    return (np.clip(y, 0, 1) ** (1 / 2.2) * 255).astype(np.uint8)


def mode_img(out):
    from PIL import Image
    rig = Rig(640, 360)
    os.makedirs(out, exist_ok=True)
    for li, (kind, light) in enumerate([(1, (1.0, 1.0, 1.35)), (0, (-0.6, -0.6, 0.53))]):
        rows = []
        for model in MODELS + ["smooth"]:
            row = []
            for zoom in (0.5, 1.0, 2.0, 3.0):
                rig.set(uOff=rig.centre(0.0, 0.0, zoom), uZoom=zoom, uModel=MODELS.index(model) if model in MODELS else 5,
                        uLightKind=kind, uLight=light, uParams=(DEFAULT["rho"], DEFAULT["a"], DEFAULT["b"]), uSalt=7)
                rig.draw()
                row.append(tonemap(rig.read(), 0.6))
            rows.append(np.concatenate(row, axis=1))
        img = np.concatenate(rows, axis=0)
        Image.fromarray(img).save(os.path.join(out, "img-%s.png" % ("point" if kind else "sun")))
    print("wrote", out)


def mode_cost():
    rig = Rig(5120, 2160, cost=True)
    q = rig.ctx.query(time=True)
    for kind, light, name in [(1, (0.0, 0.0, 1.35), "point"), (0, (-0.35, -0.55, 0.76), "sun")]:
        for model in MODELS + ["smooth", "fill"]:
            mi = MODELS.index(model) if model in MODELS else (5 if model == "smooth" else 6)
            for zoom in (1.0, 2.0):
                rig.set(uOff=rig.centre(0.0, 0.0, zoom), uZoom=zoom, uModel=mi, uLightKind=kind, uLight=light,
                        uParams=(DEFAULT["rho"], DEFAULT["a"], DEFAULT["b"]), uSalt=7)
                ts = []
                for i in range(36):
                    with q:
                        rig.draw()
                    if i >= 6:
                        ts.append(q.elapsed / 1000.0)
                print("cost %-6s %-9s zoom %.1f: %8.1f us full 5120x2160" % (name, model, zoom, np.median(ts)))


def mode_stats():
    rig = Rig(1024, 1024)
    for kind, light, name in [(1, (0.0, 0.0, 1.35), "point"), (0, (-0.2, -0.3, 0.93), "sun-high"), (0, (-0.6, -0.6, 0.53), "sun-mirror")]:
        rig.set(uOff=rig.centre(0.0, 0.0, 1.0), uZoom=1.0, uModel=5, uLightKind=kind, uLight=light, uParams=(DEFAULT["rho"], DEFAULT["a"], DEFAULT["b"]), uSalt=7)
        rig.draw()
        ref = rig.read().mean()
        for model in MODELS:
            vals = []
            for zoom in (0.5, 1.0, 2.0, 3.0):
                rig.set(uZoom=zoom, uOff=rig.centre(0.0, 0.0, zoom), uModel=MODELS.index(model))
                rig.draw()
                img = rig.read()
                rig.set(uModel=5, uOff=rig.centre(0.0, 0.0, zoom))
                rig.draw()
                r = rig.read().mean()
                vals.append("z%.1f %.3f (lit %.4f)" % (zoom, img.mean() / max(r, 1e-12), (img > 0.05).mean()))
            print("stats %-10s %-9s E[model]/smooth: %s" % (name, model, "  ".join(vals)))


def blur(img, passes=2):
    """[1 4 6 4 1] / 16 separable, twice (sigma ~1.4 px): the pan metric's resampling floor goes, popping stays."""
    k = np.array([1, 4, 6, 4, 1], "f4") / 16.0
    for _ in range(passes):
        img = np.apply_along_axis(lambda r: np.convolve(r, k, mode="same"), 1, img)
        img = np.apply_along_axis(lambda r: np.convolve(r, k, mode="same"), 0, img)
    return img


def flicker(frames, sh=0.37):
    """(raw, blurred) motion-compensated mean |I_t - I_t-1 shifted| / mean I."""
    out = []
    for fr in (frames, [blur(f) for f in frames]):
        diffs, means = [], []
        for t in range(1, len(fr)):
            a, b = fr[t - 1], fr[t]
            a_s = (1 - sh) * a[:, 1:-1] + sh * a[:, 2:]
            diffs.append(np.abs(b[:, 1:-1] - a_s).mean())
            means.append(b.mean())
        out.append(np.mean(diffs) / max(np.mean(means), 1e-12))
    return out


def mode_pan():
    rig = Rig(512, 512)
    for kind, light, name in [(1, (1.5, 2.0, 1.35), "point"), (0, (-0.6, -0.6, 0.53), "sun-mirror")]:
        for model in MODELS:
            out = []
            for zoom in (0.5, 1.0, 2.0, 3.0):
                frames = []
                for t in range(48):
                    # the camera pans by 0.37 screen px a frame (sub-pixel steps); the image is shifted back by the integer part
                    # only, as the screen would show it
                    c0 = rig.centre(0.0, 0.0, zoom)
                    off = (c0[0] + t * 0.37 * zoom, c0[1])
                    rig.set(uOff=off, uZoom=zoom, uModel=MODELS.index(model), uLightKind=kind, uLight=light,
                            uParams=(DEFAULT["rho"], DEFAULT["a"], DEFAULT["b"]), uSalt=7)
                    rig.draw()
                    frames.append(rig.read())
                # compare each frame with the previous one moved by the pan (bilinear shift of 0.37 px): a stable model
                # changes only by the resampling (raw), and by nothing once both are blurred (blurred: popping, twinkle)
                fr = [f.sum(-1) if f.ndim == 3 else f for f in frames[:24]]
                raw, bl = flicker(fr)
                out.append("z%.1f %.3f/%.3f" % (zoom, raw, bl))
            print("pan %-10s %-9s flicker: %s" % (name, model, "  ".join(out)))


def squares(W, n, ts, bits=0, lum=1.0, kind=0):
    """Glints' two-texel square layout (pzopt.Glints.TEXELS = 2): kind frost, full strength and sun, level 0."""
    data = np.zeros((n * 2, 4), dtype="f4")
    for i in range(n):
        x, y = i % W, i // W
        data[i * 2 + 0] = ((x - y) * 32.0 * ts, (x + y) * 16.0 * ts, 0.5, float(kind | (0 << 2) | (bits << 10)))
        data[i * 2 + 1] = (x, y, 1.0, float(255 + (int(round(lum * 255)) << 8)))
    rows = (n * 2 + 4095) // 4096
    pad = np.zeros((rows * 4096, 4), dtype="f4")
    pad[: n * 2] = data
    return data, pad, rows


def mode_flakes():
    import moderngl
    ctx = moderngl.create_standalone_context(backend="egl", require=430)
    src = open(os.path.join(ROOT, "build", "shader-dump", "glints-compact.comp")).read()
    W = 48
    n = W * W
    data, pad, rows = squares(W, n, 2.0, bits=1)  # light 0 reaches every square
    tex = ctx.texture((4096, rows), 4, pad.tobytes(), dtype="f4")
    tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
    rho, a, b = float(os.environ.get("FLAKES", "1536")) / 1024.0, 0.45, 0.035
    for smooth in (False, True):
        code = src.replace("#version 430", "#version 430\n#define PZG_SMOOTH", 1) if smooth else src
        cs = ctx.compute_shader(code)
        flakes = ctx.buffer(reserve=262144 * 32)
        cmd = ctx.buffer(np.array([0, 1, 0, 0], dtype="u4").tobytes())
        tex.use(1)

        def put(k, v, raw=False):
            if k in cs:
                if raw:
                    cs[k].write(v)
                else:
                    cs[k] = v
        put("uData", 1)
        put("uTotal", n * 64)
        put("uMVP", np.eye(4, dtype="f4").tobytes(), True)
        put("uJ", (0.0, 0.0, 0.0, 0.0))
        put("uTs", 2.0)
        lp = np.zeros((8, 4), "f4"); lp[0] = (W / 2, W / 2, 2.5, 30.0)
        ld = np.zeros((8, 4), "f4"); ld[0] = (0, 0, -2, 1)
        lc = np.zeros((8, 4), "f4"); lc[0] = (1, 1, 1, 0)
        put("uLp", lp.tobytes(), True); put("uLd", ld.tobytes(), True); put("uLc", lc.tobytes(), True)
        put("uSun", (0.0, 0.0, 1.0, 0.0))
        kinds = np.array([[rho, float(os.environ.get("ROUGH", "1.6")), 0.04, 0.018], [rho * 1.6, 0.55, 0.03, 0.018], [rho * 0.6, 0.22, 0.025, 0.02]], "f4")
        put("uKind", kinds.tobytes(), True)
        put("uView", (0.5, 1.0, 0.0, 4096.0))
        flakes.bind_to_storage_buffer(0)
        cmd.bind_to_storage_buffer(1)
        cs.run((n * 64 + 63) // 64)
        ctx.finish()
        cnt = int(np.frombuffer(cmd.read(), "u4")[0])
        f = np.frombuffer(flakes.read(), "f4").reshape(-1, 8)[:cnt]
        e = f[:, 4:7].sum()
        if smooth:
            print("flakes smooth reference: energy %.1f (%d slots)" % (e, cnt))
            ref = e
        else:
            print("flakes: %d lit (%.3f a square), energy %.1f" % (cnt, cnt / n, e))
            got = e
    print("flakes energy / smooth: %.3f" % (got / ref))


def mode_pcost():
    import moderngl
    ctx = moderngl.create_standalone_context(backend="egl", require=430)
    dump = os.path.join(ROOT, "build", "shader-dump")
    rd = lambda f: open(os.path.join(dump, f)).read()
    n = int(os.environ.get("SQUARES", "5225"))
    W = int(np.ceil(np.sqrt(n)))
    data, pad, rows = squares(W, n, 2.0, bits=int(os.environ.get("BITS", "0")))
    tex = ctx.texture((4096, 16), 4, dtype="f4")
    tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
    tex.write(pad.tobytes(), viewport=(0, 0, 4096, rows))
    fbo = ctx.framebuffer(color_attachments=[ctx.texture((5120, 2160), 4, dtype="f2")])
    fbo.use()
    ctx.enable(moderngl.BLEND)
    ctx.blend_func = moderngl.ONE, moderngl.ONE
    rho = float(os.environ.get("FLAKES", "3072")) / 1024.0
    kinds = np.array([[rho, 1.6, 0.04, 0.018], [rho * 1.6, 1.3, 0.035, 0.018], [rho * 0.6, 0.22, 0.025, 0.02]], "f4")
    mvp = np.eye(4, dtype="f4")
    mvp[0, 0] = 2.0 / 5120.0; mvp[1, 1] = -2.0 / 2160.0; mvp[3, 0] = -0.5; mvp[3, 1] = 0.6
    sun = (-0.35, -0.55, 0.76)

    def setup(prog):
        def put(k, v, raw=False):
            if k in prog:
                if raw:
                    prog[k].write(v)
                else:
                    prog[k] = v
        put("uData", 1); put("uTotal", n * 64)
        put("uMVP", mvp.tobytes(), True); put("uJ", (0.0, 0.0, 0.0, 0.0)); put("uTs", 1.0)
        lp = np.zeros((8, 4), "f4"); lp[0] = (W / 2, W / 2, 2.5, 30.0)
        ld = np.zeros((8, 4), "f4"); ld[0] = (0, 0, -2, 1)
        lc = np.zeros((8, 4), "f4"); lc[0] = (1, 1, 1, 0)
        put("uLp", lp.tobytes(), True); put("uLd", ld.tobytes(), True); put("uLc", lc.tobytes(), True)
        put("uSun", (sun[0], sun[1], sun[2], 1.0)); put("uSunC", (1.0, 0.95, 0.9, 1.0))
        put("uKind", kinds.tobytes(), True)
        put("uView", (1.0, 1.0, 0.0, 2048.0))
    q = ctx.query(time=True)

    def timed(fn, reps=36):
        ts = []
        for i in range(reps):
            with q:
                fn()
            if i >= 6:
                ts.append(q.elapsed / 1000.0)
        return np.median(ts)
    tex.use(1)
    up = pad.tobytes()
    print("pcost: %d squares, %d slots, flakes %.0f" % (n, n * 64, rho * 1024))
    print("  upload %d KB:            %7.1f us" % (len(up) // 1024, timed(lambda: tex.write(up, viewport=(0, 0, 4096, rows)))))
    pp = ctx.program(vertex_shader=rd("glints-particles.vert"), fragment_shader=rd("glints-particles.frag"))
    setup(pp)
    va = ctx.vertex_array(pp, [])
    ctx.enable(moderngl.PROGRAM_POINT_SIZE)
    print("  particles draw:           %7.1f us" % timed(lambda: va.render(moderngl.POINTS, vertices=n * 64)))
    cs = ctx.compute_shader(rd("glints-compact.comp"))
    setup(cs)
    flakes = ctx.buffer(reserve=262144 * 32)
    cmd = ctx.buffer(np.array([0, 1, 0, 0], dtype="u4").tobytes())
    flakes.bind_to_storage_buffer(0)
    cmd.bind_to_storage_buffer(1)
    zero = np.array([0, 1, 0, 0], dtype="u4").tobytes()

    def comp():
        cmd.write(zero)
        cs.run((n * 64 + 63) // 64)
    print("  compact compute:          %7.1f us" % timed(comp))
    cnt = int(np.frombuffer(cmd.read(), "u4")[0])
    dp = ctx.program(vertex_shader=rd("glints-compact-draw.vert"), fragment_shader=rd("glints-compact-draw.frag"))
    dva = ctx.vertex_array(dp, [])
    print("  compact draw (%6d lit):  %7.1f us" % (cnt, timed(lambda: dva.render(moderngl.POINTS, vertices=cnt))))


def mode_ppan():
    import moderngl
    ctx = moderngl.create_standalone_context(backend="egl", require=430)
    dump = os.path.join(ROOT, "build", "shader-dump")
    rd = lambda f: open(os.path.join(dump, f)).read()
    W = 40
    n = W * W
    data, pad, rows = squares(W, n, 1.0)
    tex = ctx.texture((4096, rows), 4, pad.tobytes(), dtype="f4")
    tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
    tex.use(1)
    S = 512
    fbo = ctx.framebuffer(color_attachments=[ctx.texture((S, S), 4, dtype="f4")])
    fbo.use()
    ctx.enable(moderngl.BLEND)
    ctx.blend_func = moderngl.ONE, moderngl.ONE
    ctx.enable(moderngl.PROGRAM_POINT_SIZE)
    pp = ctx.program(vertex_shader=rd("glints-particles.vert"), fragment_shader=rd("glints-particles.frag"))
    va = ctx.vertex_array(pp, [])
    rho = float(os.environ.get("FLAKES", "3072")) / 1024.0
    kinds = np.array([[rho, 1.6, 0.04, 0.018], [rho * 1.6, 1.3, 0.035, 0.018], [rho * 0.6, 0.22, 0.025, 0.02]], "f4")

    def put(k, v, raw=False):
        if k in pp:
            if raw:
                pp[k].write(v)
            else:
                pp[k] = v
    put("uData", 1); put("uJ", (0.0, 0.0, 0.0, 0.0)); put("uTs", 1.0)
    lp = np.zeros((8, 4), "f4"); lp[0] = (W / 2, W / 2, 1.35, 12.0)
    ld = np.zeros((8, 4), "f4"); ld[0] = (0, 0, -2, 1)
    lc = np.zeros((8, 4), "f4"); lc[0] = (1, 1, 1, 0)
    put("uLp", lp.tobytes(), True); put("uLd", ld.tobytes(), True); put("uLc", lc.tobytes(), True)
    put("uKind", kinds.tobytes(), True)
    put("uView", (1.0, 1.0, 0.0, 1024.0))
    for name, sun, bits in [("point", (0.0, 0.0, 1.0, 0.0), 1.0), ("sun-mirror", (-0.6, -0.6, 0.53, 1.0), 0.0)]:
        data, pad, rows = squares(W, n, 1.0, bits=int(bits))
        tex.write(pad.tobytes())
        put("uSun", sun); put("uSunC", (1.0, 1.0, 1.0, 1.0))
        frames = []
        cx, cy = 0.0, W * 16.0
        for t in range(48):
            ox = cx - S / 2 + t * 0.37
            m = np.zeros((4, 4), "f4")
            m[0, 0] = 2.0 / S; m[1, 1] = -2.0 / S; m[2, 2] = 1.0; m[3, 3] = 1.0
            m[3, 0] = -1.0 - ox * 2.0 / S; m[3, 1] = 1.0 + (cy - S / 2) * 2.0 / S
            put("uMVP", m.tobytes(), True)
            fbo.clear(0.0, 0.0, 0.0, 0.0)
            va.render(moderngl.POINTS, vertices=n * 64)
            img = np.frombuffer(fbo.read(components=4, dtype="f4"), "f4").reshape(S, S, 4)[..., :3].sum(-1)
            frames.append(img)
        raw, bl = flicker(frames[:24])
        lit = (frames[0] > 0).sum()
        print("ppan %-10s particles flicker raw/blurred: %.3f/%.3f  (lit px %d, energy %.3g)" % (name, raw, bl, lit, frames[0].sum()))


def mode_kinds():
    """Each kind (frost, snow, wet) under a lamp (bulb 0.9 level up) and the sun-mirror direction, particles pass:
    lit pixels, energy, and where the energy sits relative to the light (squares, + = towards the camera)."""
    import moderngl
    from PIL import Image
    ctx = moderngl.create_standalone_context(backend="egl", require=430)
    dump = os.path.join(ROOT, "build", "shader-dump")
    rd = lambda f: open(os.path.join(dump, f)).read()
    W = 40
    n = W * W
    SW, SH = 2600, 1400
    fbo = ctx.framebuffer(color_attachments=[ctx.texture((SW, SH), 4, dtype="f4")])
    fbo.use()
    ctx.enable(moderngl.BLEND)
    ctx.blend_func = moderngl.ONE, moderngl.ONE
    ctx.enable(moderngl.PROGRAM_POINT_SIZE)
    pp = ctx.program(vertex_shader=rd("glints-particles.vert"), fragment_shader=rd("glints-particles.frag"))
    va = ctx.vertex_array(pp, [])
    rho = float(os.environ.get("FLAKES", "3072")) / 1024.0
    kinds = np.array([[rho, 1.6, 0.04, 0.018], [rho * 1.6, 1.3, 0.035, 0.018], [rho * 0.6, 0.22, 0.025, 0.02]], "f4")
    tex = None

    def put(k, v, raw=False):
        if k in pp:
            if raw:
                pp[k].write(v)
            else:
                pp[k] = v
    put("uData", 1); put("uJ", (0.0, 0.0, 0.0, 0.0)); put("uTs", 1.0)
    put("uKind", kinds.tobytes(), True)
    put("uView", (1.0, 1.0, 0.0, 1024.0))
    put("uSunC", (1.0, 1.0, 1.0, 1.0))
    lamp = float(os.environ.get("LAMP_H", "2.2"))
    # screen x' = (x - y) 32 in [-1280, 1280], y' = (x + y) 16 in [0, 1280]
    m = np.zeros((4, 4), "f4")
    m[0, 0] = 2.0 / SW; m[1, 1] = -2.0 / SH; m[2, 2] = 1.0; m[3, 3] = 1.0
    m[3, 0] = 0.0; m[3, 1] = 1.0
    put("uMVP", m.tobytes(), True)
    out = os.environ.get("OUT", "/tmp/claude-1000/gcv/rig")
    os.makedirs(out, exist_ok=True)
    for ki, kname in enumerate(["frost", "snow", "wet"]):
        for name, sun, bits in [("lamp", (0.0, 0.0, 1.0, 0.0), 1), ("sun-mirror", (-0.6, -0.6, 0.53, 1.0), 0)]:
            data, pad, rows = squares(W, n, 1.0, bits=bits, kind=ki)
            if tex is None:
                tex = ctx.texture((4096, rows), 4, pad.tobytes(), dtype="f4")
                tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
            tex.write(pad.tobytes())
            tex.use(1)
            lp = np.zeros((8, 4), "f4"); lp[0] = (W / 2, W / 2, lamp, 20.0)
            ld = np.zeros((8, 4), "f4"); ld[0] = (0, 0, -2, 1)
            lc = np.zeros((8, 4), "f4"); lc[0] = (1, 1, 1, 0)
            put("uLp", lp.tobytes(), True); put("uLd", ld.tobytes(), True); put("uLc", lc.tobytes(), True)
            put("uSun", sun)
            fbo.clear(0.0, 0.0, 0.0, 0.0)
            va.render(moderngl.POINTS, vertices=n * 64)
            img = np.frombuffer(fbo.read(components=4, dtype="f4"), "f4").reshape(SH, SW, 4)[..., :3].sum(-1)
            img = img[::-1]  # GL rows bottom-up
            ys, xs = np.nonzero(img > 0)
            e = img.sum()
            if e > 0:
                cxp = (img.sum(0) * np.arange(SW)).sum() / e - SW / 2
                cyp = (img.sum(1) * np.arange(SH)).sum() / e
                # back to squares: x - y = x' / 32, x + y = y' / 16; relative to the lamp (W/2, W/2)
                cxp, cyp = (cxp / 32 + cyp / 16) / 2 - W / 2, (cyp / 16 - cxp / 32) / 2 - W / 2
            else:
                cxp = cyp = float("nan")
            print("kinds %-6s %-10s lit px %6d  energy %8.3g  max %.3g  centroid (squares from the lamp) x %+.1f y %+.1f" % (kname, name, len(xs), e, img.max(), cxp, cyp))
            v = np.clip(img / max(img.max() * 0.25, 1e-9), 0, 1)
            Image.fromarray((v * 255).astype("u1")).save(os.path.join(out, "kinds-%s-%s.png" % (kname, name)))


COMP = """
#version 430
%LIB%
uniform sampler2D uPic;
uniform sampler2D uDepth;
uniform sampler2D uMask;
uniform vec2 uRes;
uniform float uZoom;
uniform vec2 uOff;
uniform vec3 uLight;
uniform vec3 uParams;
uniform int uFused;
out vec4 fragColor;
const vec3 V = vec3(0.61237244, 0.61237244, 0.5);
void main() {
   vec2 uv = gl_FragCoord.xy / uRes;
   vec4 c = texture(uPic, uv);
   float dz = texture(uDepth, uv).r;
   c.rgb *= 0.5 + 0.5 * dz; // stands in for the composite's per-texture work
   if (uFused == 1) {
      float mk = texture(uMask, uv).r;
      if (mk > 0.0) {
         vec2 g = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) * uZoom + uOff;
         vec3 L = normalize(uLight), H = normalize(L + V);
         vec2 m = H.xy / H.z;
         float D = pzgDb23Iso(g, uZoom, uParams.x, m, uParams.y, uParams.z, 7);
         float c4 = H.z * H.z * H.z * H.z;
         float F = 0.02 + 0.98 * pow(1.0 - dot(L, H), 5.0);
         c.rgb += vec3(F * D / c4 / (4.0 * V.z)) * mk;
      }
   }
   fragColor = c;
}
"""


def mode_fused():
    import moderngl
    ctx = moderngl.create_standalone_context(backend="egl", require=430)
    W, H = 5120, 2160
    rng = np.random.default_rng(1)
    pic = ctx.texture((W, H), 4, rng.integers(0, 255, (H, W, 4), dtype=np.uint8).tobytes())
    dep = ctx.texture((W, H), 1, (rng.random((H, W)).astype("f2")).tobytes(), dtype="f2")
    out = ctx.texture((W, H), 4)
    fbo = ctx.framebuffer(color_attachments=[out])
    comp = ctx.program(vertex_shader=VERT, fragment_shader=COMP.replace("%LIB%", glsl_lib()))
    glint = ctx.program(vertex_shader=VERT, fragment_shader=FRAG.replace("%LIB%", glsl_lib()))
    vbo = ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], dtype="f4").tobytes())
    vc = ctx.vertex_array(comp, [(vbo, "2f", "pos")])
    vg = ctx.vertex_array(glint, [(vbo, "2f", "pos")])
    sun = (-0.35, -0.55, 0.76)
    for k, v in dict(uPic=0, uDepth=1, uMask=2, uRes=(float(W), float(H)), uZoom=1.0, uOff=(0.0, 0.0), uLight=sun,
                     uParams=(DEFAULT["rho"], DEFAULT["a"], DEFAULT["b"])).items():
        if k in comp:
            comp[k].value = v
    for k, v in dict(uRes=(float(W), float(H)), uZoom=1.0, uOff=(0.0, 0.0), uModel=1, uLightKind=0, uLight=sun,
                     uParams=(DEFAULT["rho"], DEFAULT["a"], DEFAULT["b"]), uSalt=7).items():
        if k in glint:
            glint[k].value = v
    pic.use(0); dep.use(1)
    q = ctx.query(time=True)
    fbo.use()

    def timed(fn, reps=36):
        ts = []
        for i in range(reps):
            with q:
                fn()
            if i >= 6:
                ts.append(q.elapsed / 1000.0)
        return np.median(ts)
    ctx.enable(moderngl.BLEND)
    ctx.blend_func = moderngl.ONE, moderngl.ONE
    comp["uFused"].value = 0
    base = timed(lambda: vc.render())
    print("fused: composite stand-in alone          %7.1f us" % base)
    for share in (0.0, 0.1, 1.0):
        m = np.zeros((H, W), np.uint8)
        m[: int(H * share), :] = 255
        mask = ctx.texture((W, H), 1, m.tobytes())
        mask.use(2)
        comp["uFused"].value = 1
        fu = timed(lambda: vc.render())
        comp["uFused"].value = 0

        def sep():
            vc.render()
            if share > 0.0:
                ctx.scissor = (0, H - int(H * share), W, int(H * share))
                vg.render()
                ctx.scissor = None
        sp = timed(sep)
        print("fused: mask %3d %%: fused %7.1f us (+%6.1f)   separate pass %7.1f us (+%6.1f)" % (share * 100, fu, fu - base, sp, sp - base))
        mask.release()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["img", "cost", "stats", "pan", "src", "flakes", "pcost", "ppan", "fused", "kinds"])
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "glint-rig"))
    a = ap.parse_args()
    if a.mode == "src":
        print(glsl_lib())
    elif a.mode == "img":
        mode_img(a.out)
    elif a.mode == "cost":
        mode_cost()
    elif a.mode == "flakes":
        mode_flakes()
    elif a.mode == "pcost":
        mode_pcost()
    elif a.mode == "ppan":
        mode_ppan()
    elif a.mode == "fused":
        mode_fused()
    elif a.mode == "kinds":
        mode_kinds()
    elif a.mode == "stats":
        mode_stats()
    else:
        mode_pan()


if __name__ == "__main__":
    main()
