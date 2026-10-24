// Ground that remembers: the look. c0 is the ground's colour as the game drew it (lit), p0 the world point of the pixel on
// the rest surface (floor level, squares). The includer provides gtS(p) (the state, filtered) and defines GT_TPS.
// GT_POM (0/1): relief offset sampling of the rut (the camera's fixed view ray through the height field);
// GT_SHADOW steps of the self-shadow march toward the key light; GT_AO the cavity term.
vec4 gtS(vec2 p);
#ifndef GT_RELIGHT
#define GT_RELIGHT 1
#endif
#ifndef GT_WATER
#define GT_WATER 1
#endif

// The view ray: a point h squares above the rest surface shows where the rest surface would be 2.449 h further toward +x+y
// (one square of height moves the screen like 2.449 units of x + y; SunShadow's WZ / WX). So the pixel whose rest-surface
// point is p0 sees, at height t, the world point p0 + K t (1, 1) with K = 2.449 / 2.
const float GT_K = 1.22474;

// The first height t (from the top down) where the ray meets the field: relief offset / parallax occlusion mapping.
vec2 gtHit(vec2 p0, out float h) {
#if GT_POM
   const int N = 16;
   float top = 0.06, bot = -0.32;
   float dt = (top - bot) / float(N);
   float tPrev = top, hPrev = gtS(p0 + GT_K * top).x;
   for (int i = 1; i <= N; i++) {
      float t = top - dt * float(i);
      vec2 p = p0 + GT_K * t;
      float hs = gtS(p).x;
      if (hs >= t) {
         // secant between the last step above and this one below
         float a = (tPrev - hPrev), b = (t - hs);
         float tt = mix(tPrev, t, a / max(a - b, 1e-5));
         h = gtS(p0 + GT_K * tt).x;
         return p0 + GT_K * tt;
      }
      tPrev = t;
      hPrev = hs;
   }
   h = gtS(p0).x;
   return p0;
#else
   h = gtS(p0).x;
   return p0;
#endif
}

float gtLum(vec3 c) {
   return dot(c, vec3(0.299, 0.587, 0.114));
}

// The look is a per-channel factor on the colour the game drew (a multiply commutes with the game's light, the AO and sun
// terms baked after it, and lets the same factor be blended into a finished texture or onto the screen). A tint toward a hue
// at the same luminance is the factor that takes the material's typical colour B there.
vec3 gtTint(vec3 B, vec3 t, float amount, float value) {
   vec3 to = t / max(B, vec3(1e-3)) * (gtLum(B) / max(gtLum(t), 1e-4)) * value;
   return mix(vec3(1.0), to, clamp(amount, 0.0, 1.0));
}

// The factor for the pixel whose rest-surface world point is p0. mat: material; L: the key light (world x east, y south,
// z up, unit); sun: the direct-light share of the drawn colour (0 at night / indoors .. ~0.7 in full sun); wet: the ground's
// wetness 0..1 (rain over the last hours); snowDepth: the snow cover (squares); sky: the sky's colour (the water in ruts).
// (the mean colours of the game's own tiles: blends_natural_01_16 grass, _64 dirt, _0 sand, _80 gravel-ish, e_newsnow_ground_1_40)
const vec3 GT_BASE[6] = vec3[6](vec3(0.5), vec3(0.31, 0.36, 0.115), vec3(0.36, 0.25, 0.085), vec3(0.785, 0.70, 0.53), vec3(0.31, 0.23, 0.08),
   vec3(0.94, 0.97, 0.98));

bool gtQuiet(vec4 s) {
   return abs(s.x) < 0.002 && s.y < 0.01 && s.z < 0.01;
}

vec3 gtFactor(vec2 p0, int mat, vec3 L, float sun, float wet, float snowDepth, vec3 sky) {
#if GT_POM
   // untouched ground (most of a live chunk): three reads along the view ray's reach instead of the march
   if (gtQuiet(gtS(p0)) && gtQuiet(gtS(p0 - GT_K * 0.13)) && gtQuiet(gtS(p0 - GT_K * 0.32))) {
      return vec3(1.0);
   }
#endif
   float h;
   vec2 p = gtHit(p0, h);
   vec4 s = gtS(p);
   if (gtQuiet(s)) {
      return vec3(1.0);
   }
   float e = 1.0 / GT_TPS;
   float hx = gtS(p + vec2(e, 0.0)).x - gtS(p - vec2(e, 0.0)).x;
   float hy = gtS(p + vec2(0.0, e)).x - gtS(p - vec2(0.0, e)).x;
   vec3 n = normalize(vec3(-hx / (2.0 * e), -hy / (2.0 * e), 1.0));
   // relight: the key light on the new normal against the flat ground it was drawn for
   float flat_ = max(L.z, 0.05);
   float lit = max(dot(n, L), 0.0) / flat_;
#if GT_SHADOW > 0
   if (L.z > 0.02 && sun > 0.0) {
      // march toward the light over the field: the rut's own wall shadows its floor (long at a low sun)
      vec2 dir = L.xy / L.z;
      float occ = 0.0;
      for (int i = 1; i <= GT_SHADOW; i++) {
         float t = 0.03 * float(i) * float(i) / float(GT_SHADOW); // heights above the hit, finer near it
         float hs = gtS(p + dir * t).x;
         occ = max(occ, smoothstep(0.0, 0.02, hs - (h + t)));
      }
      lit *= 1.0 - 0.85 * occ;
   }
#endif
   // snow scatters light under its surface: softer relief, no clipped crests
   lit = mix(1.0, lit, mat == 1 ? 0.5 : 1.0); // grass: blades over the dip, half the relief
   float relight = mat == 5 ? mix(1.0, clamp(mix(1.0, lit, 0.75), 0.35, 1.25), sun) : mix(1.0, clamp(lit, 0.25, 1.5), sun);
#if GT_AO
   // cavity: the rut floor sees less sky than the ground around it
   float r = 3.0 * e;
   float ring = 0.25 * (gtS(p + vec2(r, r)).x + gtS(p + vec2(-r, r)).x + gtS(p + vec2(r, -r)).x + gtS(p + vec2(-r, -r)).x);
   float ao = 1.0 - 0.9 * clamp((ring - h) / 0.12, 0.0, 0.45);
#else
   float ao = 1.0;
#endif
   float depth = max(-h, 0.0), berm = max(h, 0.0), wear = s.y, fresh = s.z, slip = s.w;
   vec3 B = GT_BASE[mat];
   vec3 f = vec3(1.0);
   if (mat == 1) {
      // grass: freshly pressed blades are crushed (darker, the shaded undersides up), then lie flat and pale (their lighter
      // sides, yellowing) for a day while they stand up again; worn paths go straw, then bare earth
      float flat1 = clamp(depth * 25.0 + wear * 1.2, 0.0, 1.0);
      f *= gtTint(B, vec3(0.47, 0.43, 0.17), 0.75 * flat1 * (1.0 - 0.6 * fresh), 1.0 + 0.22 * flat1 * (1.0 - fresh)); // the dry-grass tile's straw
      float bare = smoothstep(0.55, 0.95, wear);
      f *= gtTint(B, vec3(0.38, 0.31, 0.17), 0.8 * bare, 0.82); // trodden earth
      f *= 1.0 - 0.24 * fresh * flat1 * (1.0 - bare); // crushed fresh blades
   } else if (mat == 2) {
      // dirt / mud: cut soil is darker and wetter; a fresh berm's crumbs are paler
      float cut = clamp(depth * 14.0, 0.0, 1.0);
      float dark = 0.16 + 0.22 * wet;
      f *= 1.0 - dark * cut - 0.12 * fresh * (0.4 + wet);
      f *= gtTint(B, vec3(0.40, 0.29, 0.20), 0.3 * wear, 1.0);
      f *= 1.0 + 0.10 * clamp(berm * 25.0, 0.0, 1.0) * (1.0 - wet);
      f *= 1.0 - 0.15 * slip * fresh;
   } else if (mat == 3) {
      float cut = clamp(depth * 12.0, 0.0, 1.0);
      f *= 1.0 - (0.08 + 0.14 * wet) * cut * (0.4 + 0.6 * fresh); // damp sand under the dry top
      f *= 1.0 + 0.06 * clamp(berm * 30.0, 0.0, 1.0);
   } else if (mat == 4) {
      float cut = clamp(depth * 18.0, 0.0, 1.0);
      f *= 1.0 - 0.18 * cut;
      f *= gtTint(B, vec3(0.45, 0.40, 0.34), 0.3 * wear, 1.0);
   } else if (mat == 5) {
      // snow: packed snow is denser (bluer, greyer); where the cut reaches the ground the dirt / slush shows
      float pk = clamp(depth * 6.0 + wear * 0.6, 0.0, 1.0);
      f *= gtTint(B, vec3(0.80, 0.86, 0.97), 0.5 * pk, 1.0 - 0.1 * pk);
      float through = smoothstep(snowDepth * 0.8, snowDepth * 1.05, depth);
      f *= gtTint(B, vec3(0.45, 0.41, 0.38), 0.8 * through, 0.5);
   }
#if GT_RELIGHT == 0
   relight = 1.0; // (the colour response alone: the dev variant 'colour')
#endif
   f *= relight * ao;
   // water standing in the ruts (mud, sand, slush): a flat surface at the pooled level; brown, opaque, the sky at grazing
   if (GT_WATER == 1 && (mat == 2 || mat == 3 || mat == 5) && wet > 0.0) {
      float level = mix(0.035, 0.012, wet); // deeper water the wetter it has been
      float wd = depth - level;
      if (wd > 0.0) {
         float a = smoothstep(0.0, 0.01, wd) * clamp(wet * 1.5, 0.0, 1.0);
         // murky water: the bottom darkened, the overcast sky at the iso view's ~60 degree incidence (Fresnel ~0.3 with the
         // ripples' roughness), the key light's glint when the camera sits in its mirror direction
         vec3 water = vec3(0.55, 0.52, 0.48) + 0.4 * (sky / max(gtLum(sky), 1e-3)) * (gtLum(B) / max(B, vec3(1e-3)));
         vec3 v = normalize(vec3(0.5, 0.5, 0.707)); // toward the camera
         float spec = pow(max(dot(reflect(-L, vec3(0.0, 0.0, 1.0)), v), 0.0), 60.0) * sun;
         f = mix(f, water + spec * 0.6, a);
      }
   }
   return f;
}
