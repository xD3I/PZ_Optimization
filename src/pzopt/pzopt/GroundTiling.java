package pzopt;

/**
 * Ground without the grid (Visuals > Ground and grass; keys {@code groundHexTiling}, {@code groundMacroPct},
 * {@code groundHeightBlend}): natural floors (lawns, fields, sand, dirt) baked into the chunk textures without a
 * visible tile repeat, with large-scale colour drift and dry patches, and grass that grows over the dirt edge instead
 * of an even alpha fade. All of it runs while a chunk texture bakes, so it costs nothing a frame.
 *
 * <p>The material art: a natural floor sprite's diamond (2x tiles: 126 px wide, 64 rows, 4096 px, exactly one cell of
 * the screen lattice a = (64, 32), b = (64, -32)) tiles the screen without gaps, so a 128N x 64N rectangle is an exact
 * period of any pattern laid on the square lattice (world vectors (N, -N), (N, N)). Each material's variants are
 * dealt into such a rectangle (iso layout: no resampling, an integer-pixel fetch copies a source texel) and the seams
 * between them repaired in the gradient domain (periodic FFT Poisson). See {@link #GLSL} for the sampling.
 */
public final class GroundTiling {
   private GroundTiling() {
   }

   /**
    * The bake-time sampling library (GLSL 3.30; harness/ground-tiling/rig.py compiles this exact text). World
    * coordinates arrive split into an integer lattice origin (exact, from the CPU in double) and a small float part,
    * so a texel far from the map origin hashes and wraps exactly like its neighbour chunk's.
    *
    * <ul>
    *   <li>{@code gtScreenPx(origin, local)}: the texel's screen pixel (2x tiles) in the exemplar's iso layout.</li>
    *   <li>Modes (pzGtMode): 0 stock (the sprite as drawn), 1 hex (Mikkelsen 2022: three offset fetches on a triangle
    *       grid, w^exp x height weights, no rotation), 2 hpb (Heitz and Neyret 2018: Gaussianised exemplar, variance-
    *       preserving blend, inverse-CDF LUT), 3 bomb (square cells, 4 blended fetches), 4 voronoi (two nearest
    *       jittered cells), 5 two (Quilez-style two-sample: a noise index picks two of eight offsets), 6 lap (Wronski 2025:
    *       the hex tiling blended per Laplacian band).</li>
    * </ul>
    */
   public static final String GLSL = """
      // ---- pzopt ground tiling (pzopt.GroundTiling.GLSL) ----
      uniform sampler2DArray pzGtArt;   // rgb colour, a height; iso-layout periodic exemplar per layer
      uniform sampler2DArray pzGtGauss; // hpb: Gaussianised rgb (mean 0.5, sd 1/6), a height
      uniform sampler2DArray pzGtLut;   // hpb (float): rows 0..2 inverse CDF of the decorrelated channels (x = Gaussian value
                                        //   0..1); row 3: texels 0..2 the PCA basis columns, texel 3 the mean colour
      uniform ivec4 pzGtPeriod;         // x, y exemplar period px (128N, 64N); z mode; w seed
      uniform vec4 pzGtHex;             // x cell size (squares), y height contrast, z weight exponent, w gain r
      uniform ivec4 pzGtOI;             // chunk origin split exactly by the CPU: xy floor(skew(origin)), zw floor(origin / cell)
      uniform vec4 pzGtOF;              //   and the fractions: xy fract(skew(origin)), zw fract(origin / cell)

      uint gtPcg(uint v) { uint s = v * 747796405u + 2891336453u; uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u; return (w >> 22u) ^ w; }
      uint gtHash3(ivec2 c, uint salt) { return gtPcg(uint(c.x) ^ gtPcg(uint(c.y) ^ gtPcg(salt ^ uint(pzGtPeriod.w)))); }
      vec2 gtRand2(ivec2 c, uint salt) { uint h = gtHash3(c, salt); return vec2(float(h & 0xFFFFu), float(h >> 16u)) * (1.0 / 65536.0); }
      float gtRand1(ivec2 c, uint salt) { return float(gtHash3(c, salt) >> 8u) * (1.0 / 16777216.0); }

      ivec2 gtWrap(ivec2 p) { ivec2 m = pzGtPeriod.xy; return ((p % m) + m) % m; }
      vec4 gtFetch(ivec2 px, ivec2 off, float layer) { return texelFetch(pzGtArt, ivec3(gtWrap(px + off), int(layer)), 0); }
      vec4 gtFetchG(ivec2 px, ivec2 off, float layer) { return texelFetch(pzGtGauss, ivec3(gtWrap(px + off), int(layer)), 0); }
      ivec2 gtOffset(ivec2 v, uint salt) { return ivec2(gtRand2(v, salt) * vec2(pzGtPeriod.xy)); }

      // world squares (local to the chunk origin) -> skewed triangle-grid coordinates (Mikkelsen's TriangleGrid input)
      vec2 gtSkew(vec2 wLocal) { vec2 st = wLocal * (3.4641016 / pzGtHex.x); return vec2(st.x - 0.57735027 * st.y, 1.15470054 * st.y); }

      void gtTri(vec2 skLocal, out vec3 w, out ivec2 v1, out ivec2 v2, out ivec2 v3) {
         vec2 sk = skLocal + pzGtOF.xy;
         vec2 fl = floor(sk);
         ivec2 b = ivec2(fl) + pzGtOI.xy;
         vec3 t = vec3(sk - fl, 0.0); t.z = 1.0 - t.x - t.y;
         float s = step(0.0, -t.z), s2 = 2.0 * s - 1.0;
         w = vec3(-t.z * s2, s - t.y * s2, s - t.x * s2);
         int si = int(s);
         v1 = b + ivec2(si, si); v2 = b + ivec2(si, 1 - si); v3 = b + ivec2(1 - si, si);
      }

      vec3 gtGain3(vec3 x, float r) {
         float k = log(1.0 - r) / log(0.5);
         vec3 s = 2.0 * step(0.5, x); vec3 m = 2.0 * (1.0 - s);
         vec3 res = 0.5 * s + 0.25 * m * pow(max(vec3(0.0), s + x * m), vec3(k));
         return res / (res.x + res.y + res.z);
      }

      // Mikkelsen 2022 (JCGT 11(3)): three fetches, weights w^exp scaled by the texel's height, no rotation
      vec4 gtHex(ivec2 px, vec2 wLocal, float layer) {
         vec3 w; ivec2 v1, v2, v3; gtTri(gtSkew(wLocal), w, v1, v2, v3);
         vec4 c1 = gtFetch(px, gtOffset(v1, 1u), layer), c2 = gtFetch(px, gtOffset(v2, 1u), layer), c3 = gtFetch(px, gtOffset(v3, 1u), layer);
         vec3 dw = mix(vec3(1.0), vec3(c1.a, c2.a, c3.a), pzGtHex.y);
         vec3 W = dw * pow(w, vec3(pzGtHex.z));
         W /= (W.x + W.y + W.z);
         if (pzGtHex.w != 0.5) W = gtGain3(W, pzGtHex.w);
         return c1 * W.x + c2 * W.y + c3 * W.z;
      }

      // Heitz and Neyret 2018, full version: the exemplar decorrelated (PCA) and Gaussianised per channel offline;
      // three fetches blended with variance-preserving weights, then the inverse CDF and the basis back to rgb
      vec4 gtHpb(ivec2 px, vec2 wLocal, float layer) {
         vec3 w; ivec2 v1, v2, v3; gtTri(gtSkew(wLocal), w, v1, v2, v3);
         vec4 g1 = gtFetchG(px, gtOffset(v1, 1u), layer), g2 = gtFetchG(px, gtOffset(v2, 1u), layer), g3 = gtFetchG(px, gtOffset(v3, 1u), layer);
         vec3 W = pow(w, vec3(pzGtHex.z));
         W /= (W.x + W.y + W.z);
         vec4 g = (g1 * W.x + g2 * W.y + g3 * W.z) - 0.5;
         g = clamp(g / sqrt(dot(W, W)) + 0.5, 0.0, 1.0);
         int L = int(layer);
         float n1 = float(textureSize(pzGtLut, 0).x - 4);
         vec3 d;
         d.x = texelFetch(pzGtLut, ivec3(int(g.r * n1 + 0.5), 0, L), 0).r;
         d.y = texelFetch(pzGtLut, ivec3(int(g.g * n1 + 0.5), 1, L), 0).r;
         d.z = texelFetch(pzGtLut, ivec3(int(g.b * n1 + 0.5), 2, L), 0).r;
         mat3 B = mat3(texelFetch(pzGtLut, ivec3(0, 3, L), 0).xyz, texelFetch(pzGtLut, ivec3(1, 3, L), 0).xyz, texelFetch(pzGtLut, ivec3(2, 3, L), 0).xyz);
         vec3 c = B * d + texelFetch(pzGtLut, ivec3(3, 3, L), 0).xyz;
         return vec4(clamp(c, 0.0, 1.0), g.a);
      }

      // Wronski 2025 (JCGT 14(1)), Laplacian blending on the hex tiling: each band (mip k - mip k+1 of the exemplar)
      // blended with weights that are sharp at the fine bands and soft at the coarse ones; 3 x (GT_LAP_LEVELS + 1) fetches
      #define GT_LAP_LEVELS 4
      #ifndef GT_LAP_DECAY
      #define GT_LAP_DECAY 0.5
      #endif
      vec4 gtLapFetch(ivec2 px, ivec2 off, float layer, float lod) {
         vec2 uv = (vec2(gtWrap(px + off)) + 0.5) / vec2(pzGtPeriod.xy);
         return lod == 0.0 ? texelFetch(pzGtArt, ivec3(gtWrap(px + off), int(layer)), 0) : textureLod(pzGtArt, vec3(uv, layer), lod);
      }
      vec4 gtLap(ivec2 px, vec2 wLocal, float layer) {
         vec3 w; ivec2 v1, v2, v3; gtTri(gtSkew(wLocal), w, v1, v2, v3);
         ivec2 o1 = gtOffset(v1, 1u), o2 = gtOffset(v2, 1u), o3 = gtOffset(v3, 1u);
         vec4 p1 = gtLapFetch(px, o1, layer, 0.0), p2 = gtLapFetch(px, o2, layer, 0.0), p3 = gtLapFetch(px, o3, layer, 0.0);
         vec3 h = mix(vec3(1.0), vec3(p1.a, p2.a, p3.a), pzGtHex.y);
         vec4 sum = vec4(0.0);
         for (int k = 0; k <= GT_LAP_LEVELS; k++) {
            vec4 q1 = p1, q2 = p2, q3 = p3;
            if (k < GT_LAP_LEVELS) {
               float lod = float(k + 1);
               q1 = gtLapFetch(px, o1, layer, lod); q2 = gtLapFetch(px, o2, layer, lod); q3 = gtLapFetch(px, o3, layer, lod);
            }
            vec3 W = h * pow(w, vec3(max(1.0, pzGtHex.z * pow(GT_LAP_DECAY, float(k)))));
            W /= (W.x + W.y + W.z);
            vec4 b1 = k < GT_LAP_LEVELS ? p1 - q1 : p1, b2 = k < GT_LAP_LEVELS ? p2 - q2 : p2, b3 = k < GT_LAP_LEVELS ? p3 - q3 : p3;
            sum += b1 * W.x + b2 * W.y + b3 * W.z;
            p1 = q1; p2 = q2; p3 = q3;
         }
         return clamp(sum, 0.0, 1.0);
      }

      // texture bombing on square world cells: four fetches blended by smooth cell weights
      vec4 gtBomb(ivec2 px, vec2 wLocal, float layer) {
         vec2 q = wLocal / pzGtHex.x + pzGtOF.zw - 0.5; // cell centres at half-integers
         vec2 f = floor(q); vec2 t = q - f;
         ivec2 b = ivec2(f) + pzGtOI.zw;
         vec2 s = t * t * (3.0 - 2.0 * t);
         vec4 c00 = gtFetch(px, gtOffset(b, 3u), layer), c10 = gtFetch(px, gtOffset(b + ivec2(1, 0), 3u), layer);
         vec4 c01 = gtFetch(px, gtOffset(b + ivec2(0, 1), 3u), layer), c11 = gtFetch(px, gtOffset(b + ivec2(1, 1), 3u), layer);
         vec4 W = vec4((1.0 - s.x) * (1.0 - s.y), s.x * (1.0 - s.y), (1.0 - s.x) * s.y, s.x * s.y);
         W *= mix(vec4(1.0), vec4(c00.a, c10.a, c01.a, c11.a), pzGtHex.y);
         W = pow(W, vec4(pzGtHex.z * 0.5));
         W /= dot(W, vec4(1.0));
         return c00 * W.x + c10 * W.y + c01 * W.z + c11 * W.w;
      }

      // Voronoi tiling: the two nearest jittered cell points, blended across the bisector
      vec4 gtVoronoi(ivec2 px, vec2 wLocal, float layer) {
         vec2 q = wLocal / pzGtHex.x + pzGtOF.zw;
         vec2 f = floor(q);
         float d1 = 1e9, d2 = 1e9; ivec2 n1 = ivec2(0), n2 = ivec2(0);
         for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
            ivec2 cl = ivec2(f) + ivec2(i, j), c = cl + pzGtOI.zw;
            vec2 p = vec2(cl) + 0.15 + 0.7 * gtRand2(c, 5u);
            float d = dot(q - p, q - p);
            if (d < d1) { d2 = d1; n2 = n1; d1 = d; n1 = c; } else if (d < d2) { d2 = d; n2 = c; }
         }
         vec4 a = gtFetch(px, gtOffset(n1, 4u), layer), b = gtFetch(px, gtOffset(n2, 4u), layer);
         float e = sqrt(d2) - sqrt(d1); // 0 on the bisector
         float k = 0.5 + 0.5 * clamp(e / 0.35 + (a.a - b.a) * pzGtHex.y * 0.5, 0.0, 1.0); // weight of the nearest
         k = smoothstep(0.5, 1.0, k) * 0.5 + 0.5;
         return mix(b, a, k);
      }

      // value noise on an integer lattice; q = local lattice coordinate (small), o = the exact integer origin
      float gtValueNoise(vec2 q, ivec2 o, uint salt) {
         vec2 f = floor(q); vec2 t = q - f; ivec2 c = ivec2(f) + o;
         vec2 u = t * t * (3.0 - 2.0 * t);
         float a = gtRand1(c, salt), b = gtRand1(c + ivec2(1, 0), salt), d = gtRand1(c + ivec2(0, 1), salt), e = gtRand1(c + ivec2(1, 1), salt);
         return mix(mix(a, b, u.x), mix(d, e, u.x), u.y);
      }

      // two-sample stochastic (Quilez, "texture repetition" #3): a noise index picks two of eight offsets
      vec4 gtTwo(ivec2 px, vec2 wLocal, float layer) {
         float k = gtValueNoise(wLocal / pzGtHex.x + pzGtOF.zw, pzGtOI.zw, 6u) * 8.0;
         float i = floor(k); float f = fract(k);
         vec4 a = gtFetch(px, gtOffset(ivec2(int(i), 0), 7u), layer), b = gtFetch(px, gtOffset(ivec2(int(i) + 1, 0), 7u), layer);
         float t = smoothstep(0.2, 0.8, f - 0.1 * ((a.a + a.r) - (b.a + b.r)) * pzGtHex.y);
         return mix(a, b, t);
      }

      vec4 gtSample(ivec2 px, vec2 wLocal, float layer, int mode) {
         if (mode == 1) return gtHex(px, wLocal, layer);
         if (mode == 2) return gtHpb(px, wLocal, layer);
         if (mode == 3) return gtBomb(px, wLocal, layer);
         if (mode == 4) return gtVoronoi(px, wLocal, layer);
         if (mode == 5) return gtTwo(px, wLocal, layer);
         if (mode == 6) return gtLap(px, wLocal, layer);
         return gtFetch(px, ivec2(0), layer);
      }
      // ---- end pzopt ground tiling ----
      """;

   /** Skewed triangle-grid coordinates of world point (wx, wy) for hex cells of {@code cell} squares (gtSkew in double). */
   static double[] skew(double wx, double wy, double cell) {
      double k = 3.4641016151377544 / cell, sx = wx * k, sy = wy * k;
      return new double[] {sx - 0.5773502691896258 * sy, 1.1547005383792515 * sy};
   }

   /**
    * The exact split of a chunk-level origin (world squares) for pzGtOI / pzGtOF: {floor(skew), floor(origin / cell)}
    * into {@code oi}, the fractions into {@code of}.
    */
   public static void origin(double wx, double wy, double cell, int[] oi, float[] of) {
      double[] sk = skew(wx, wy, cell);
      double cx = wx / cell, cy = wy / cell;
      double[] v = {sk[0], sk[1], cx, cy};
      for (int i = 0; i < 4; i++) {
         double f = Math.floor(v[i]);
         oi[i] = (int) f;
         of[i] = (float) (v[i] - f);
      }
   }
}
