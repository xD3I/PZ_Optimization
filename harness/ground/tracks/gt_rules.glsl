// Ground that remembers: the rules (stamp, relax, decay). Shared by the offline rig and pzopt.GroundTracks.
// State per texel: x = height h in squares (< 0 rut, > 0 berm), y = compaction / wear c (0..1),
// z = freshness f (1 just cut, fades in hours), w = spare (slip smear).
// Materials: 0 none (no tracks), 1 grass, 2 dirt (mud when wet), 3 sand, 4 gravel, 5 snow.
// GT_TPS (texels a square) must be defined by the includer.

// per material: x sink (depth multiplier), y compressed share of the cut volume (the rest makes the berm),
// z repose: tan of the steepest stable wall (Sumner's angle of repose), w berm width (squares)
const vec4 GT_M0[6] = vec4[6](
   vec4(0.0, 1.0, 9.0, 0.0),
   vec4(0.30, 0.97, 4.0, 0.05),   // grass: blades flatten, roots hold the wall
   vec4(0.62, 0.45, 2.2, 0.13),   // dirt / mud: plastic, cohesive, big berms
   vec4(0.50, 0.25, 0.67, 0.11),  // sand: 34 degrees, berms that slump
   vec4(0.22, 0.40, 0.85, 0.08),  // gravel: stones pushed aside
   vec4(1.00, 0.80, 1.40, 0.12)); // snow: compresses (densifies), modest berms

// per material: x tau of the height (game hours), y tau of the wear, z tau of the freshness, w wear a pass adds
const vec4 GT_M1[6] = vec4[6](
   vec4(1.0, 1.0, 1.0, 0.0),
   vec4(20.0, 24.0, 3.0, 0.22),   // grass: springs back over ~2 days (3 tau = 48 h), yellowing with it
   vec4(240.0, 168.0, 8.0, 0.18),
   vec4(14.0, 48.0, 2.0, 0.08),
   vec4(150.0, 120.0, 5.0, 0.12),
   vec4(900.0, 400.0, 6.0, 0.30));

float gtHash(vec2 p) {
   p = fract(p * vec2(443.897, 441.423));
   p += dot(p, p.yx + 19.19);
   return fract((p.x + p.y) * p.x);
}

float gtNoise(vec2 p) {
   vec2 i = floor(p), f = fract(p);
   vec2 u = f * f * (3.0 - 2.0 * f);
   return mix(mix(gtHash(i), gtHash(i + vec2(1, 0)), u.x), mix(gtHash(i + vec2(0, 1)), gtHash(i + vec2(1, 1)), u.x), u.y);
}

// A primitive: a = (p0, p1) for a wheel line, (centre, unit heading) for an ellipse; b, c parameters; c.w = kind.
//   kind 0 wheel line: b = (half width, depth, berm height, slip 0..1), c = (wear, berm width, tread, 0)
//   kind 1 foot / hoof / body ellipse: b = (half length, half width, depth, berm), c = (wear, berm width, heel-toe 0..1, 1)
//   kind 2 drag line: as kind 0, a shallow smear (b.y small), its wear high
struct GtPrim {
   vec4 a;
   vec4 b;
   vec4 c;
};

GtPrim gtPrimAt(int i); // the includer's primitive list (an SSBO, a uniform array)

// One primitive's effect at world point p: cut depth target (<= 0, or 1 when outside), berm height (0 none), its profile
// weight (wear / freshness), and the slip.
void gtPrim(GtPrim q, vec2 p, int mat, out float cut, out float berm, out float w, out float slip) {
   cut = 1.0;
   berm = 0.0;
   w = 0.0;
   slip = 0.0;
   vec4 m0 = GT_M0[mat];
   int kind = int(q.c.w + 0.5);
   float bermW = 1.5 * q.c.y * (m0.w / 0.1);
   if (kind != 1) {
      vec2 p0 = q.a.xy, p1 = q.a.zw, d = p1 - p0;
      float L2 = max(dot(d, d), 1e-8);
      float t = clamp(dot(p - p0, d) / L2, 0.0, 1.0);
      float r = length(p - (p0 + d * t));
      float hw = q.b.x, s = q.b.w;
      if (r < hw) {
         float x = r / hw;
         float prof = 1.0 - x * x * x * x; // flat-bottomed, rounded shoulders
         // tread: shallow cross grooves across a tyre, the slip smears them out
         float along = dot(p - p0, d) * inversesqrt(L2);
         float tread = q.c.z * (1.0 - s) * (0.5 + 0.5 * sin(along * 6.2832 * 7.0));
         // slip: a lumpy, deeper, churned bottom
         float lump = 1.0 - 0.35 * s * gtNoise(p * 9.0);
         cut = -q.b.y * prof * (1.0 - 0.18 * tread) * lump;
         w = prof;
         slip = s;
      } else if (r < hw + bermW * (1.0 + 0.8 * s)) {
         float x = (r - hw) / (bermW * (1.0 + 0.8 * s));
         berm = q.b.z * (0.5 - 0.5 * cos(6.28318 * x)) * (1.0 - 0.3 * x);
         slip = s;
      }
   } else {
      vec2 f = q.a.zw, rel = p - q.a.xy;
      float a = dot(rel, f), b = dot(rel, vec2(-f.y, f.x));
      float len = q.b.x, wid = q.b.y;
      float e = (a / len) * (a / len) + (b / wid) * (b / wid);
      if (e < 1.0) {
         float prof = 1.0 - e * e;
         float ht = mix(1.0, 0.72 + 0.28 * abs(a / len) + 0.2 * smoothstep(0.2, 0.9, -a / len), q.c.z); // heel and toe press deeper
         cut = -q.b.z * prof * ht;
         w = prof;
      } else {
         float rr = sqrt(e);
         float bw = 1.0 + bermW / max(wid, 1e-3);
         if (rr < bw) {
            float x = (rr - 1.0) / (bw - 1.0);
            berm = q.b.w * sin(3.14159 * x);
         }
      }
   }
}

// Apply this frame's primitives to one texel. s: the state; returns the new state. Cuts first (min over every primitive),
// berms only where nothing cut this frame and the texel is not inside an older rut (Sumner: the displaced material moves
// to the nearest uncompressed column).
vec4 gtStampTexel(vec4 s, vec2 p, int mat, int n) {
   if (mat == 0) {
      return s;
   }
   vec4 m0 = GT_M0[mat];
   vec4 m1 = GT_M1[mat];
   float cutMin = 1.0, bermMax = 0.0, wMax = 0.0, slipMax = 0.0, wear = 0.0;
   for (int i = 0; i < n; i++) {
      float cut, berm, w, slip;
      GtPrim q = gtPrimAt(i);
      gtPrim(q, p, mat, cut, berm, w, slip);
      if (cut < 1.0) {
         // sinkage: the material's response, multipass (a compacted texel sinks less: Bekker-Wong stiffness grows with density)
         cut *= m0.x * (1.0 - 0.55 * s.y);
         cutMin = min(cutMin, cut);
         wMax = max(wMax, w);
         slipMax = max(slipMax, slip);
         wear += w * m1.w * q.c.x;
      }
      bermMax = max(bermMax, berm * m0.x * (1.0 - m0.y) * 2.0);
   }
   if (cutMin < 1.0) {
      s.x = min(s.x, cutMin);
      s.y = clamp(s.y + wear * (1.0 - s.y), 0.0, 1.0);
      s.z = max(s.z, wMax);
      s.w = max(s.w, slipMax);
   } else if (bermMax > 0.0 && s.x > -0.004) {
      s.x = max(s.x, bermMax);
      s.z = max(s.z, 0.5 * bermMax / max(bermMax, 1e-4));
   }
   return s;
}

// Angle of repose (Sumner's erosion): material above the stable slope with a neighbour flows half the excess to it,
// symmetric, so the volume is kept. hc: centre, hn: the 4 neighbours, their materials' repose share the min.
float gtRelax(float hc, vec4 hn, float reposeTan) {
   float T = reposeTan / GT_TPS; // height a texel may step
   vec4 d = vec4(hc) - hn;
   vec4 out_ = max(d - T, 0.0), in_ = max(-d - T, 0.0);
   return hc - 0.2 * (out_.x + out_.y + out_.z + out_.w) + 0.2 * (in_.x + in_.y + in_.z + in_.w);
}

// Decay over dt game hours. weather: x rain 0..1, y snowfall 0..1, z temperature (C), w wind 0..1.
vec4 gtDecay(vec4 s, int mat, float dt, vec4 weather) {
   if (mat == 0) {
      return vec4(0.0);
   }
   vec4 m1 = GT_M1[mat];
   float th = m1.x, tc = m1.y, tf = m1.z;
   if (mat == 2) {
      th /= 1.0 + 9.0 * weather.x; // rain softens mud tracks: days -> about a day of steady rain
      tf *= 1.0 + 3.0 * weather.x; // stays wet-looking while it rains
   } else if (mat == 3) {
      th /= 1.0 + 4.0 * weather.w + 3.0 * weather.x; // wind and rain smooth sand
   } else if (mat == 1) {
      th /= 1.0 + weather.x; // wet grass stands up sooner
   } else if (mat == 5) {
      if (weather.z > 1.0) {
         th = min(th, 60.0 / (weather.z)); // thaw slumps the walls
         tf = 1.0;
      }
   }
   tc *= 1.0 + 4.0 * s.y * s.y; // a worn path lasts: desire paths hold for days
   s.x *= exp(-dt / th);
   s.y *= exp(-dt / tc);
   s.z *= exp(-dt / tf);
   s.w *= exp(-dt / tf);
   if (mat == 5 && weather.y > 0.0) {
      // snowfall refills: ~0.05 squares an hour at full fall (5 cm/h), berms buried as fast
      float fill = 0.025 * weather.y * dt;
      s.x = s.x < 0.0 ? min(0.0, s.x + fill) : max(0.0, s.x - fill);
      s.z *= exp(-dt * 2.0 * weather.y);
      s.y *= exp(-dt * 0.3 * weather.y);
   }
   if (abs(s.x) < 0.0015) {
      s.x = 0.0;
   }
   if (s.y < 0.004) {
      s.y = 0.0;
   }
   if (s.z < 0.004) {
      s.z = 0.0;
   }
   return s;
}
