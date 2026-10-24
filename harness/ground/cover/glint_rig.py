#!/usr/bin/env python3
"""Offline rig for the glint models of pzopt.GlintGlsl (ground cover, 2026-10-24).

Runs the GLSL the game compiles (the LIB block of src/pzopt/pzopt/GlintGlsl.java) on a floor patch seen through PZ's 2:1
dimetric camera, on the desktop GPU through headless EGL (moderngl):

  img    PNGs of every model at several zooms under a point light and the sun        -> <out>/img-*.png
  cost   GPU time of a full 5120x2160 floor per model (timer queries, median of 30)   -> printed
  stats  mean D_P over a large patch against the smooth NDF (energy, bias)           -> printed
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
                # changes only by the resampling
                diffs, means = [], []
                for t in range(1, 48):
                    a = frames[t - 1]
                    b = frames[t]
                    sh = 0.37
                    a_s = (1 - sh) * a[:, 1:-1] + sh * a[:, 2:]
                    diffs.append(np.abs(b[:, 1:-1] - a_s).mean())
                    means.append(b.mean())
                out.append("z%.1f %.3f" % (zoom, np.mean(diffs) / max(np.mean(means), 1e-9)))
            print("pan %-10s %-9s flicker: %s" % (name, model, "  ".join(out)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["img", "cost", "stats", "pan", "src"])
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "glint-rig"))
    a = ap.parse_args()
    if a.mode == "src":
        print(glsl_lib())
    elif a.mode == "img":
        mode_img(a.out)
    elif a.mode == "cost":
        mode_cost()
    elif a.mode == "stats":
        mode_stats()
    else:
        mode_pan()


if __name__ == "__main__":
    main()
