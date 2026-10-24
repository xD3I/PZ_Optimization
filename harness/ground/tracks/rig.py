#!/usr/bin/env python3
"""Offline rig of the ground tracks (pzopt.GroundTracks): stamp, relax, decay and shade on real ground tiles, headless EGL.

Needs moderngl: python -m venv build/venv && build/venv/bin/pip install moderngl numpy pillow
usage: rig.py OUT.png [--mat grass|dirt|sand|gravel|snow|all] [--pom 0|1] [--shadow N] [--ao 0|1] [--sun ELEV_DEG]
       [--hours 0,12,48] [--wet W] [--snowing H] [--timing]
The GLSL is the shipped one: gt_rules.glsl / gt_look.glsl next to this file (GroundTracksGlsl.java is generated from them).
"""
import argparse, math, os, sys, time
import numpy as np
import moderngl
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TILES = os.environ.get("GT_TILES", os.path.join(HERE, "../../../build/rig/tiles"))
S = 14  # squares a side
TPS = 16
N = S * TPS
TW, TH = 128, 64  # a 2x floor tile's diamond

MATS = {"grass": 1, "dirt": 2, "sand": 3, "gravel": 4, "snow": 5}
TILE_OF = {"grass": ["blends_natural_01_16", "blends_natural_01_21"], "dirt": ["blends_natural_01_64", "blends_natural_01_69"],
           "sand": ["blends_natural_01_0", "blends_natural_01_5"], "gravel": ["blends_natural_01_80"],
           "snow": ["e_newsnow_ground_1_40", "e_newsnow_ground_1_41"]}

def rules():
    return open(os.path.join(HERE, "gt_rules.glsl")).read()

def look():
    return open(os.path.join(HERE, "gt_look.glsl")).read()

STAMP = """
#version 430
layout(local_size_x = 8, local_size_y = 8) in;
#define GT_TPS %(TPS)d.0
layout(rgba32f, binding = 0) uniform image2D st;
layout(std430, binding = 1) buffer Prims { vec4 P[]; };
uniform usampler2D mat;
uniform int n;
%(RULES)s
GtPrim gtPrimAt(int i) { GtPrim q; q.a = P[i * 3]; q.b = P[i * 3 + 1]; q.c = P[i * 3 + 2]; return q; }
void main() {
   ivec2 t = ivec2(gl_GlobalInvocationID.xy);
   vec2 p = (vec2(t) + 0.5) / GT_TPS;
   int m = int(texelFetch(mat, ivec2(p), 0).r);
   vec4 s = imageLoad(st, t);
   imageStore(st, t, gtStampTexel(s, p, m, n));
}
"""

RELAX = """
#version 430
layout(local_size_x = 8, local_size_y = 8) in;
#define GT_TPS %(TPS)d.0
layout(rgba32f, binding = 0) uniform readonly image2D src;
layout(rgba32f, binding = 1) uniform writeonly image2D dst;
uniform usampler2D mat;
%(RULES)s
GtPrim gtPrimAt(int i) { GtPrim q; return q; }
void main() {
   ivec2 t = ivec2(gl_GlobalInvocationID.xy), sz = imageSize(src);
   vec4 s = imageLoad(src, t);
   int m = int(texelFetch(mat, ivec2((vec2(t) + 0.5) / GT_TPS), 0).r);
   vec4 hn = vec4(imageLoad(src, clamp(t + ivec2(1, 0), ivec2(0), sz - 1)).x, imageLoad(src, clamp(t - ivec2(1, 0), ivec2(0), sz - 1)).x,
                  imageLoad(src, clamp(t + ivec2(0, 1), ivec2(0), sz - 1)).x, imageLoad(src, clamp(t - ivec2(0, 1), ivec2(0), sz - 1)).x);
   if (m != 0) s.x = gtRelax(s.x, hn, GT_M0[m].z);
   imageStore(dst, t, s);
}
"""

DECAY = """
#version 430
layout(local_size_x = 8, local_size_y = 8) in;
#define GT_TPS %(TPS)d.0
layout(rgba32f, binding = 0) uniform image2D st;
uniform usampler2D mat;
uniform float dt;
uniform vec4 weather;
%(RULES)s
GtPrim gtPrimAt(int i) { GtPrim q; return q; }
void main() {
   ivec2 t = ivec2(gl_GlobalInvocationID.xy);
   int m = int(texelFetch(mat, ivec2((vec2(t) + 0.5) / GT_TPS), 0).r);
   imageStore(st, t, gtDecay(imageLoad(st, t), m, dt, weather));
}
"""

VERT = """
#version 430
in vec2 pos; out vec2 uv;
void main() { uv = pos * 0.5 + 0.5; gl_Position = vec4(pos, 0.0, 1.0); }
"""

FRAG = """
#version 430
#define GT_TPS %(TPS)d.0
#define GT_POM %(POM)d
#define GT_SHADOW %(SHADOW)d
#define GT_AO %(AO)d
in vec2 uv; out vec4 o;
uniform sampler2D alb, st;
uniform usampler2D mat;
uniform vec2 scr; uniform float offX;
uniform vec3 L; uniform float sun, wet, snowDepth; uniform vec3 sky; uniform int view;
vec4 gtS(vec2 p) { return texture(st, p * GT_TPS / %(N)d.0); }
%(LOOK)s
void main() {
   vec2 px = vec2(uv.x, 1.0 - uv.y) * scr;
   vec4 a = texture(alb, vec2(uv.x, uv.y));
   float u = (px.x - offX) / 64.0, v = px.y / 32.0;
   vec2 p0 = vec2((v + u) * 0.5, (v - u) * 0.5);
   int m = (p0.x > 0.0 && p0.y > 0.0 && p0.x < %(S)d.0 && p0.y < %(S)d.0) ? int(texelFetch(mat, ivec2(p0), 0).r) : 0;
   vec3 lightFlat = vec3(1.0) * (0.45 + 0.75 * max(L.z, 0.0) * (sun > 0.0 ? 1.0 : 0.0));
   vec3 c0 = a.rgb * lightFlat;
   vec3 c = m == 0 ? c0 : gtLook(c0, p0, m, L, sun, wet, snowDepth, sky);
   if (view == 1) { float h = gtS(p0).x; c = h < 0.0 ? vec3(-h * 4.0) : vec3(h * 12.0, 0.0, 0.0); }
   o = vec4(c, a.a);
}
"""

def src(t, **kw):
    d = dict(TPS=TPS, N=N, S=S, RULES=rules(), LOOK=look(), POM=1, SHADOW=8, AO=1)
    d.update(kw)
    return t % d

def build_albedo(mats):
    import random
    rnd = random.Random(7)
    W, H = S * 2 * 64, S * 2 * 32 + 64
    offX = S * 64
    img = Image.new("RGBA", (W, H), (20, 22, 20, 255))
    cache = {}
    for x in range(S):
        for y in range(S):
            name = mats[y][x]
            t = rnd.choice(TILE_OF[name])
            if t not in cache:
                im = Image.open(os.path.join(TILES, t + ".png")).convert("RGBA")
                cache[t] = im.crop((0, 192, 128, 256))
            tile = cache[t]
            sx, sy = (x - y) * 64 + offX - 64, (x + y) * 32
            img.alpha_composite(tile, (sx, sy))
    return img, offX

class Rig:
    def __init__(self, mats, args):
        self.ctx = moderngl.create_context(standalone=True, backend="egl", require=430)
        self.args = args
        self.mats = mats
        m = np.array([[MATS[mats[y][x]] for x in range(S)] for y in range(S)], dtype=np.uint8)
        self.mat = self.ctx.texture((S, S), 1, m.tobytes(), dtype="u1")
        self.mat.filter = (moderngl.NEAREST, moderngl.NEAREST)
        z = np.zeros((N, N, 4), dtype=np.float32)
        self.st = [self.ctx.texture((N, N), 4, z.tobytes(), dtype="f4") for _ in range(2)]
        for t in self.st:
            t.filter = (moderngl.LINEAR, moderngl.LINEAR)
            t.repeat_x = t.repeat_y = False
        self.cur = 0
        kw = dict(POM=args.pom, SHADOW=args.shadow, AO=args.ao)
        self.stamp = self.ctx.compute_shader(src(STAMP, **kw))
        self.relax = self.ctx.compute_shader(src(RELAX, **kw))
        self.decay_cs = self.ctx.compute_shader(src(DECAY, **kw))
        self.prog = self.ctx.program(vertex_shader=VERT, fragment_shader=src(FRAG, **kw))
        self.vbo = self.ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], dtype="f4").tobytes())
        self.vao = self.ctx.vertex_array(self.prog, [(self.vbo, "2f", "pos")])
        alb, self.offX = build_albedo(mats)
        self.alb_img = alb
        self.alb = self.ctx.texture(alb.size, 4, alb.transpose(Image.FLIP_TOP_BOTTOM).tobytes())
        self.prim = self.ctx.buffer(reserve=16 * 3 * 4096)
        self.g = (N + 7) // 8
        self.t_stamp = 0.0

    def apply(self, prims, relax=2):
        if not prims:
            return
        arr = np.array(prims, dtype=np.float32).reshape(-1)
        self.prim.orphan(max(arr.nbytes, 64))
        self.prim.write(arr.tobytes())
        self.prim.bind_to_storage_buffer(1)
        self.st[self.cur].bind_to_image(0, read=True, write=True)
        self.mat.use(2)
        self.stamp["mat"] = 2
        self.stamp["n"] = len(prims) // 12
        self.stamp.run(self.g, self.g)
        self.ctx.memory_barrier()
        for _ in range(relax):
            self.st[self.cur].bind_to_image(0, read=True, write=False)
            self.st[1 - self.cur].bind_to_image(1, read=False, write=True)
            self.relax["mat"] = 2
            self.relax.run(self.g, self.g)
            self.ctx.memory_barrier()
            self.cur = 1 - self.cur

    def decay(self, hours, weather, step=0.5):
        t = 0.0
        while t < hours - 1e-6:
            dt = min(step, hours - t)
            self.st[self.cur].bind_to_image(0, read=True, write=True)
            self.mat.use(2)
            self.decay_cs["mat"] = 2
            self.decay_cs["dt"] = dt
            self.decay_cs["weather"] = weather
            self.decay_cs.run(self.g, self.g)
            self.ctx.memory_barrier()
            t += dt
            if weather[0] > 0 or weather[3] > 0:
                self.apply_relax_only(1)

    def apply_relax_only(self, k):
        for _ in range(k):
            self.st[self.cur].bind_to_image(0, read=True, write=False)
            self.st[1 - self.cur].bind_to_image(1, read=False, write=True)
            self.relax["mat"] = 2
            self.relax.run(self.g, self.g)
            self.ctx.memory_barrier()
            self.cur = 1 - self.cur

    def render(self, sun_elev, sun_az, wet, snow_depth, view=0):
        W, H = self.alb.size
        fbo = self.ctx.simple_framebuffer((W, H), components=4)
        fbo.use()
        fbo.clear(0, 0, 0, 1)
        e, a = math.radians(sun_elev), math.radians(sun_az)
        L = (math.cos(e) * math.cos(a), math.cos(e) * math.sin(a), math.sin(e))
        self.alb.use(0)
        self.st[self.cur].use(1)
        self.mat.use(2)
        p = self.prog
        p["alb"], p["st"], p["mat"] = 0, 1, 2
        p["scr"] = (W, H)
        p["offX"] = self.offX
        p["L"] = L
        p["sun"] = 0.7 if sun_elev > 0 else 0.0
        p["wet"] = wet
        p["snowDepth"] = snow_depth
        p["sky"] = (0.55, 0.62, 0.72)
        p["view"] = view
        self.vao.render(moderngl.TRIANGLE_STRIP)
        return Image.frombytes("RGBA", (W, H), fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM)

# ---------------------------------------------------------------------------------------------------- the scenario

def car_path(a):
    return (S / 2 + 4.6 * math.sin(a), S / 2 + 3.4 * math.sin(2 * a + 0.4))

WHEELS = [(1.25, 0.78), (1.25, -0.78), (-1.3, 0.78), (-1.3, -0.78)]  # (along, across) squares: a 2.6 x 1.6 square car

class Car:
    def __init__(self, depth=0.10, half=0.10):
        self.a = 0.0
        self.prev = None
        self.depth, self.half = depth, half

    def step(self, dt, speed=4.0, slip_k=0.0):
        x0, y0 = car_path(self.a)
        x1, y1 = car_path(self.a + 0.01)
        sp = math.hypot(x1 - x0, y1 - y0) / 0.01
        self.a += dt * speed / sp
        x, y = car_path(self.a)
        xn, yn = car_path(self.a + 0.01)
        h = math.atan2(yn - y, xn - x)
        f, r = (math.cos(h), math.sin(h)), (-math.sin(h), math.cos(h))
        wheels = [(x + f[0] * a + r[0] * b, y + f[1] * a + r[1] * b) for a, b in WHEELS]
        out = []
        # curvature -> lateral slip on the outer wheels (the rig's stand-in for wheelInfo.skidInfo)
        xp, yp = car_path(self.a - 0.01)
        hp = math.atan2(y - yp, x - xp)
        curv = abs((h - hp + math.pi) % (2 * math.pi) - math.pi) / max(1e-4, sp * 0.01)
        slip = min(1.0, slip_k * curv * speed / 4.0)
        if self.prev:
            for (wx, wy), (px, py) in zip(wheels, self.prev):
                out += [px, py, wx, wy, self.half, self.depth, 0.035, slip, 1.0, 0.1, 1.0, 0.0]
        self.prev = wheels
        return out

class Walker:
    def __init__(self, x, y, amt=0.045):
        self.lx = self.ly = None
        self.side = 1
        self.amt = amt

    def to(self, x, y, out, stride=0.42):
        if self.lx is None:
            self.lx, self.ly = x, y
            return
        d = math.hypot(x - self.lx, y - self.ly)
        if d > stride:
            fx, fy = (x - self.lx) / d, (y - self.ly) / d
            self.side = -self.side
            cx, cy = x - fy * 0.09 * self.side, y + fx * 0.09 * self.side
            out += [cx, cy, fx, fy, 0.14, 0.055, self.amt, 0.012, 1.0, 0.06, 1.0, 1.0]
            self.lx, self.ly = x, y

def scenario(rig, horde=60, car_laps=1.0, slip_k=0.6, corpse=True):
    import random
    rnd = random.Random(3)
    car = Car()
    dt = 1 / 30
    steps = int(car_laps * 2 * math.pi / (dt * 4.0 / 9.0)) if car_laps else 0
    for i in range(steps):
        rig.apply(car.step(dt, 4.0, slip_k), relax=1)
    # the horde: walkers crossing the lawn diagonally on a shared desire line
    zs = []
    for i in range(horde):
        sy = 2 + rnd.random() * 2.5
        zs.append(dict(x=-rnd.random() * 4, y=sy, ey=S - 2 - rnd.random() * 3, sp=0.9 + rnd.random() * 0.5, ph=rnd.random() * 6, w=Walker(0, 0)))
    for k in range(int(S / 0.9 / dt) + 200):
        out = []
        for z in zs:
            z["x"] += z["sp"] * dt
            f = min(max(z["x"] / S, 0), 1)
            y = z["y"] + (z["ey"] - z["y"]) * f + math.sin(z["x"] * 0.9 + z["ph"]) * 0.3
            if 0.2 < z["x"] < S - 0.2 and 0.2 < y < S - 0.2:
                z["w"].to(z["x"], y, out)
        rig.apply(out, relax=0)
    rig.apply_relax_only(2)
    if corpse:
        # a body (and its drag mark from where it fell)
        out = [3.0, S - 3.0, 0.7071, -0.7071, 0.85, 0.28, 0.035, 0.01, 1.0, 0.05, 0.0, 1.0]
        out += [1.0, S - 1.2, 3.0, S - 3.0, 0.22, 0.012, 0.004, 0.0, 2.5, 0.08, 0.0, 2.0]
        rig.apply(out, relax=1)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--mat", default="all")
    ap.add_argument("--pom", type=int, default=1)
    ap.add_argument("--shadow", type=int, default=8)
    ap.add_argument("--ao", type=int, default=1)
    ap.add_argument("--sun", type=float, default=35)
    ap.add_argument("--az", type=float, default=-150, help="sun azimuth (deg, world x axis = 0)")
    ap.add_argument("--hours", default="0,12,48")
    ap.add_argument("--wet", type=float, default=0.0)
    ap.add_argument("--rain", type=float, default=0.0)
    ap.add_argument("--snowing", type=float, default=0.0, help="hours of full snowfall before the last column")
    ap.add_argument("--view", type=int, default=0)
    ap.add_argument("--scale", type=float, default=0.5)
    a = ap.parse_args()
    mats = list(MATS) if a.mat == "all" else a.mat.split(",")
    rows = []
    for mname in mats:
        grid = [[mname] * S for _ in range(S)]
        rig = Rig(grid, a)
        scenario(rig)
        cols = []
        last = 0.0
        for hstr in a.hours.split(","):
            h = float(hstr)
            rig.decay(h - last, (a.rain, 0.0, 5.0 if mname != "snow" else -4.0, 0.0))
            last = h
            cols.append(rig.render(a.sun, a.az, a.wet if mname != "grass" else 0.0, 0.25, a.view))
        if mname == "snow" and a.snowing > 0:
            rig.decay(a.snowing, (0.0, 1.0, -4.0, 0.0))
            cols.append(rig.render(a.sun, a.az, 0.0, 0.25, a.view))
        rows.append(cols)
    w, h = rows[0][0].size
    sw, sh = int(w * a.scale), int(h * a.scale)
    ncol = max(len(r) for r in rows)
    out = Image.new("RGB", (sw * ncol, sh * len(rows)), (0, 0, 0))
    for j, r in enumerate(rows):
        for i, im in enumerate(r):
            out.paste(im.convert("RGB").resize((sw, sh), Image.LANCZOS), (i * sw, j * sh))
    out.save(a.out)
    print("wrote", a.out, out.size)

if __name__ == "__main__":
    main()
