// The ground tracks' compute pass (pzopt.GroundTracks): one work group a square (16 x 16 texels), cells listed by the
// game thread. mode 0 stamp (this frame's primitives binned per square), 1 decay (dt game hours, the weather), 2 relax
// (angle of repose, 1 pass). The state map is toroidal: texel (absolute square * GT_TPS + local) & (GT_TN - 1).
// The includer prepends #version 430, GT_TPS, GT_TN and gt_rules.glsl.
layout(local_size_x = 16, local_size_y = 16) in;
layout(rgba8, binding = 0) uniform image2D st;
layout(std430, binding = 1) readonly buffer Cells { uvec4 cells[]; };
layout(std430, binding = 2) readonly buffer Prims { vec4 prims[]; };
layout(std430, binding = 3) readonly buffer Index { uint index[]; };
uniform int mode;
uniform float dt;
uniform vec4 weather;
uniform uint tick;

int gtCellStart, gtCellCount;

GtPrim gtPrimAt(int i) {
   uint k = index[gtCellStart + i];
   GtPrim q;
   q.a = prims[k * 3u];
   q.b = prims[k * 3u + 1u];
   q.c = prims[k * 3u + 2u];
   return q;
}

// 8-bit state: h = (r * 255 - 192) / 510 squares (-0.376 .. +0.125), c, f, slip as they are
vec4 gtDecode(vec4 r) {
   return vec4((r.x * 255.0 - 192.0) / 510.0, r.yzw);
}

// stochastic rounding: slow decays (mud: days) move the 8-bit value on average instead of stalling on it
vec4 gtEncode(vec4 s, vec2 seed) {
   vec4 v = vec4(s.x * 510.0 + 192.0, s.yzw * 255.0);
   vec4 n = vec4(gtHash(seed + 0.17), gtHash(seed + 3.31), gtHash(seed + 7.73), gtHash(seed + 11.9));
   return clamp(floor(v + n), 0.0, 255.0) / 255.0;
}

shared float sh[18 * 18];

void main() {
   uvec4 cell = cells[gl_WorkGroupID.x];
   ivec2 base = ivec2(int(cell.x & 0xFFFFu), int(cell.x >> 16)); // the square's first texel in the map
   vec2 sq = vec2(float(int(cell.y & 0xFFFFu) - 32768), float(int(cell.y >> 16) - 32768)); // the square, relative to the frame's origin
   gtCellStart = int(cell.z);
   gtCellCount = int(cell.w & 0xFFFFu);
   int mat = int(cell.w >> 24);
   ivec2 l = ivec2(gl_LocalInvocationID.xy);
   ivec2 t = (base + l) & (GT_TN - 1);
   vec2 p = sq + (vec2(l) + 0.5) / GT_TPS;
   vec2 seed = vec2(t) + float(tick & 1023u) * 0.618;
   if (mode == 2) {
      // relax: the 16 x 16 square and a one-texel ring through shared memory
      for (int k = int(gl_LocalInvocationIndex); k < 18 * 18; k += 256) {
         ivec2 o = ivec2(k % 18, k / 18) - 1;
         sh[k] = gtDecode(imageLoad(st, (base + o) & (GT_TN - 1))).x;
      }
      barrier();
      vec4 s = gtDecode(imageLoad(st, t));
      if (mat == 0) {
         return;
      }
      int c = (l.y + 1) * 18 + l.x + 1;
      float h = gtRelax(sh[c], vec4(sh[c + 1], sh[c - 1], sh[c + 18], sh[c - 18]), GT_M0[mat].z);
      if (abs(h - s.x) > 1e-5) {
         s.x = h;
         imageStore(st, t, gtEncode(s, seed));
      }
      return;
   }
   vec4 s = gtDecode(imageLoad(st, t));
   vec4 n;
   if (mode == 0) {
      n = gtStampTexel(s, p, mat, gtCellCount);
   } else {
      n = gtDecay(s, mat, dt, weather);
   }
   if (n != s) {
      imageStore(st, t, mode == 0 ? clamp(floor(vec4(n.x * 510.0 + 192.0, n.yzw * 255.0) + 0.5), 0.0, 255.0) / 255.0 : gtEncode(n, seed));
   }
}
