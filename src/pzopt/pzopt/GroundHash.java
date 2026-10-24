package pzopt;

/**
 * Ground and grass, shared part (2026-10-24): one world-space hash and noise library for the CPU rules (Java) and the shaders
 * (GLSL, {@link #GLSL}), so ground tiling, scatter, glint seeds and blade seeds agree and a place looks the same on every visit.
 *
 * <p><b>What it gives.</b> Integer hashes of world integers (squares, texels, cells) that are bit-identical in Java and GLSL:
 * {@link #pcg(int)} (PCG RXS-M-XS, one input), {@link #hash2}/{@link #hash3} (Jarzynski and Olano's pcg3d, JCGT 9(3) 2020,
 * the best quality / speed of their GPU hash survey), and {@link #u01} (the top 24 bits as a float in [0, 1): exact on both
 * sides). Value noise ({@link #noise}), fbm ({@link #fbm}) and the nearest feature point of a jittered grid ({@link #cellId},
 * {@link #cellDist}, Worley F1: clumps, scatter points) are built on them; their integer parts (lattice and cell picks) are
 * bit-identical, their float blends agree to a few ulps (a GPU may contract a*b+c into one fma), so a rule should compare a
 * noise value against a threshold with a margin or use the integer hash for a yes / no.
 *
 * <p><b>Seeds.</b> Every feature hashes with its own seed ({@link #SEED_TILING} ... {@link #SEED_TRACKS}; GLSL
 * {@code PZG_SEED_*}), so two features never pick the same squares by accident. Derive sub-seeds with {@code pcg(seed ^ k)}.
 *
 * <p><b>How a feature uses it.</b> Java: call the static methods (pure, thread-safe, no allocation). GLSL: put
 * {@link #withLibrary(String)} around a shader source (it makes sure the version has {@code uint}, at least 1.30, and puts
 * the library after the {@code #version} and every {@code #extension} line, as Mesa wants), or paste {@link #GLSL} after
 * those lines yourself. All GLSL names start with {@code pzg}. Inputs are signed world integers ({@code ivec2}); a negative
 * value hashes by its two's-complement bits on both sides. Coordinates for the float noises are in whatever unit the caller
 * scales them to (squares / 8 for a gentle field); keep them under ~1e5 (float precision).
 *
 * <p>Tested by tests/pzopt/GroundHashTest (the GLSL run on the GPU and Mesa llvmpipe through tools/ground/glslrun.c, every
 * value compared with the Java one).
 */
public final class GroundHash {
   private GroundHash() {
   }

   /** Feature seeds: one per feature so their picks are independent (any 32-bit constant; never change one, it moves the world). */
   public static final int SEED_TILING = 0x6A09E667, SEED_MACRO = 0xBB67AE85, SEED_SCATTER = 0x3C6EF372, SEED_GLINT = 0xA54FF53A,
      SEED_BLADE = 0x510E527F, SEED_TRACKS = 0x9B05688C, SEED_CLUMP = 0x1F83D9AB, SEED_COMMON = 0x5BE0CD19;

   /** PCG RXS-M-XS of one word (O'Neill): a good 32-bit integer hash. */
   public static int pcg(int v) {
      int state = v * 747796405 + -1403630843; // 2891336453u
      int word = ((state >>> ((state >>> 28) + 4)) ^ state) * 277803737;
      return (word >>> 22) ^ word;
   }

   /** pcg3d (Jarzynski and Olano 2020): three words in, three hashed words out ({@code out} gets them). */
   public static void pcg3d(int x, int y, int z, int[] out) {
      x = x * 1664525 + 1013904223;
      y = y * 1664525 + 1013904223;
      z = z * 1664525 + 1013904223;
      x += y * z;
      y += z * x;
      z += x * y;
      x ^= x >>> 16;
      y ^= y >>> 16;
      z ^= z >>> 16;
      x += y * z;
      y += z * x;
      z += x * y;
      out[0] = x;
      out[1] = y;
      out[2] = z;
   }

   /** The hash of a world integer pair with a seed (pcg3d's first word). */
   public static int hash2(int x, int y, int seed) {
      x = x * 1664525 + 1013904223;
      y = y * 1664525 + 1013904223;
      int z = seed * 1664525 + 1013904223;
      x += y * z;
      y += z * x;
      z += x * y;
      x ^= x >>> 16;
      y ^= y >>> 16;
      z ^= z >>> 16;
      x += y * z;
      return x;
   }

   /** The hash of a world integer triple (x, y, level or step) with a seed: pcg3d of (x, y, z ^ pcg(seed)), first word. */
   public static int hash3(int x, int y, int z, int seed) {
      return hash2(x ^ pcg(z), y, seed ^ z); // (z mixed into both: two triples differing in z alone never collide in x)
   }

   /** A hash as a float in [0, 1): its top 24 bits (exact in float, identical in GLSL). */
   public static float u01(int h) {
      return (h >>> 8) * (1F / 16777216F);
   }

   /** The quintic fade 6t^5 - 15t^4 + 10t^3 (Perlin 2002). */
   static float fade(float t) {
      return t * t * t * (t * (t * 6F - 15F) + 10F);
   }

   static int floor(float v) {
      int i = (int)v;
      return v < i ? i - 1 : i;
   }

   /** 2D value noise in [0, 1) at {@code (px, py)}: the lattice's hashed corners blended by the quintic fade. */
   public static float noise(float px, float py, int seed) {
      int ix = floor(px), iy = floor(py);
      float fx = px - ix, fy = py - iy;
      float a = u01(hash2(ix, iy, seed)), b = u01(hash2(ix + 1, iy, seed));
      float c = u01(hash2(ix, iy + 1, seed)), d = u01(hash2(ix + 1, iy + 1, seed));
      float ux = fade(fx), uy = fade(fy);
      float ab = a + (b - a) * ux, cd = c + (d - c) * ux;
      return ab + (cd - ab) * uy;
   }

   /** fbm: {@code octaves} of value noise, each twice the frequency and half the weight, normalised to [0, 1). */
   public static float fbm(float px, float py, int octaves, int seed) {
      float sum = 0F, w = 0.5F, norm = 0F;
      for (int o = 0; o < octaves; o++) {
         sum += w * noise(px, py, seed + o * 0x632BE5AB);
         norm += w;
         px *= 2F;
         py *= 2F;
         w *= 0.5F;
      }
      return sum / norm;
   }

   /** Worley F1 of a jittered grid (one point a cell, jitter {@code j} in 0..1): the nearest point's cell, packed {@code (cx, cy)}. */
   public static long cellId(float px, float py, float j, int seed) {
      int ix = floor(px), iy = floor(py);
      float best = Float.MAX_VALUE;
      long id = 0L;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            int cx = ix + dx, cy = iy + dy;
            int h = hash2(cx, cy, seed);
            float ox = cx + 0.5F + (u01(h) - 0.5F) * j, oy = cy + 0.5F + (u01(pcg(h)) - 0.5F) * j;
            float d = (ox - px) * (ox - px) + (oy - py) * (oy - py);
            if (d < best) {
               best = d;
               id = ((long)cx << 32) | (cy & 0xFFFFFFFFL);
            }
         }
      }
      return id;
   }

   /** Worley F1: the distance to the nearest point of the jittered grid (cells of 1). */
   public static float cellDist(float px, float py, float j, int seed) {
      int ix = floor(px), iy = floor(py);
      float best = Float.MAX_VALUE;
      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            int cx = ix + dx, cy = iy + dy;
            int h = hash2(cx, cy, seed);
            float ox = cx + 0.5F + (u01(h) - 0.5F) * j, oy = cy + 0.5F + (u01(pcg(h)) - 0.5F) * j;
            float d = (ox - px) * (ox - px) + (oy - py) * (oy - py);
            best = Math.min(best, d);
         }
      }
      return (float)Math.sqrt(best);
   }

   /** The GLSL twin of this class (GLSL 1.30 or newer: {@code uint}). Paste after #version and every #extension line. */
   public static final String GLSL = String.join("\n",
      "#ifndef PZG_HASH",
      "#define PZG_HASH 1",
      "#define PZG_SEED_TILING " + (SEED_TILING & 0xFFFFFFFFL) + "u",
      "#define PZG_SEED_MACRO " + (SEED_MACRO & 0xFFFFFFFFL) + "u",
      "#define PZG_SEED_SCATTER " + (SEED_SCATTER & 0xFFFFFFFFL) + "u",
      "#define PZG_SEED_GLINT " + (SEED_GLINT & 0xFFFFFFFFL) + "u",
      "#define PZG_SEED_BLADE " + (SEED_BLADE & 0xFFFFFFFFL) + "u",
      "#define PZG_SEED_TRACKS " + (SEED_TRACKS & 0xFFFFFFFFL) + "u",
      "#define PZG_SEED_CLUMP " + (SEED_CLUMP & 0xFFFFFFFFL) + "u",
      "#define PZG_SEED_COMMON " + (SEED_COMMON & 0xFFFFFFFFL) + "u",
      "uint pzgPcg(uint v) {",
      "   uint state = v * 747796405u + 2891336453u;",
      "   uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;",
      "   return (word >> 22u) ^ word;",
      "}",
      "uvec3 pzgPcg3d(uvec3 v) {",
      "   v = v * 1664525u + 1013904223u;",
      "   v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;",
      "   v ^= v >> 16u;",
      "   v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;",
      "   return v;",
      "}",
      // hash2: pcg3d's first word only (the shader compiler drops the rest, as the Java does)
      "uint pzgHash2(ivec2 p, uint seed) {",
      "   uvec3 v = uvec3(uint(p.x), uint(p.y), seed) * 1664525u + 1013904223u;",
      "   v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;",
      "   v ^= v >> 16u;",
      "   return v.x + v.y * v.z;",
      "}",
      "uint pzgHash3(ivec3 p, uint seed) {",
      "   return pzgHash2(ivec2(int(uint(p.x) ^ pzgPcg(uint(p.z))), p.y), seed ^ uint(p.z));",
      "}",
      "float pzgU01(uint h) { return float(h >> 8u) * (1.0 / 16777216.0); }",
      "float pzgFade(float t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }",
      "float pzgNoise(vec2 p, uint seed) {",
      "   vec2 fl = floor(p);",
      "   ivec2 i = ivec2(fl);",
      "   vec2 f = p - fl;",
      "   float a = pzgU01(pzgHash2(i, seed)), b = pzgU01(pzgHash2(i + ivec2(1, 0), seed));",
      "   float c = pzgU01(pzgHash2(i + ivec2(0, 1), seed)), d = pzgU01(pzgHash2(i + ivec2(1, 1), seed));",
      "   float ux = pzgFade(f.x), uy = pzgFade(f.y);",
      "   float ab = a + (b - a) * ux, cd = c + (d - c) * ux;",
      "   return ab + (cd - ab) * uy;",
      "}",
      "float pzgFbm(vec2 p, int octaves, uint seed) {",
      "   float sum = 0.0, w = 0.5, norm = 0.0;",
      "   for (int o = 0; o < octaves; o++) {",
      "      sum += w * pzgNoise(p, seed + uint(o) * 1663821227u);",
      "      norm += w;",
      "      p *= 2.0;",
      "      w *= 0.5;",
      "   }",
      "   return sum / norm;",
      "}",
      // Worley F1 over a jittered grid: xy = the nearest point's cell, z = its squared distance (sqrt it if you need it)
      "vec3 pzgCell(vec2 p, float j, uint seed) {",
      "   vec2 fl = floor(p);",
      "   ivec2 i = ivec2(fl);",
      "   float best = 3.4e38;",
      "   ivec2 id = i;",
      "   for (int dy = -1; dy <= 1; dy++) {",
      "      for (int dx = -1; dx <= 1; dx++) {",
      "         ivec2 c = i + ivec2(dx, dy);",
      "         uint h = pzgHash2(c, seed);",
      "         vec2 o = vec2(c) + 0.5 + (vec2(pzgU01(h), pzgU01(pzgPcg(h))) - 0.5) * j;",
      "         vec2 d = o - p;",
      "         float dd = d.x * d.x + d.y * d.y;",
      "         if (dd < best) { best = dd; id = c; }",
      "      }",
      "   }",
      "   return vec3(vec2(id), best);",
      "}",
      "#endif",
      "");

   /** {@code code} with {@link #GLSL} after its {@code #version} and {@code #extension} lines (a version below 1.30 raised to 1.30; 1.10 / 1.20 shaders compile unchanged under 1.30's compatibility rules); null if there is no main. */
   public static String withLibrary(String code) {
      return insertAfterHeader(code, GLSL, 130);
   }

   /**
    * Inserts {@code block} after {@code code}'s {@code #version} line and every {@code #extension} line that follows the
    * version (Mesa refuses a declaration ahead of an #extension), raising a version below {@code minVersion} to it. A
    * version 1.20 / 1.10 shader becomes {@code #version 130}; with {@code minVersion} 150 or more it becomes
    * {@code #version <n> compatibility} (keeps gl_FragColor, varying, texture2D). Returns null if there is no main.
    */
   public static String insertAfterHeader(String code, String block, int minVersion) {
      if (code == null || code.indexOf("void main") < 0) {
         return null;
      }
      java.util.regex.Matcher vm = java.util.regex.Pattern.compile("#version\\s+(\\d+)[^\\n]*").matcher(code);
      String head = "";
      String body = code;
      if (vm.find()) {
         int v = Integer.parseInt(vm.group(1));
         String line = vm.group(0);
         if (v < minVersion) {
            line = minVersion >= 150 ? "#version " + minVersion + " compatibility" : "#version " + minVersion;
         }
         head = code.substring(0, vm.start()) + line + "\n";
         body = code.substring(Math.min(code.length(), vm.end() + 1));
      } else {
         head = (minVersion >= 150 ? "#version " + minVersion + " compatibility" : "#version " + minVersion) + "\n";
      }
      // every #extension line ahead of the first declaration stays ahead of the block
      StringBuilder ext = new StringBuilder();
      StringBuilder rest = new StringBuilder();
      String[] lines = body.split("\n", -1);
      int i = 0;
      for (; i < lines.length; i++) {
         String t = lines[i].trim();
         if (t.startsWith("#extension") || t.isEmpty() || t.startsWith("//")) {
            ext.append(lines[i]).append('\n');
            continue;
         }
         break;
      }
      // a later #extension (after a declaration) is moved up too: none of the game's shaders has one, a patcher may
      for (; i < lines.length; i++) {
         String t = lines[i].trim();
         if (t.startsWith("#extension")) {
            ext.append(lines[i]).append('\n');
         } else {
            rest.append(lines[i]);
            if (i < lines.length - 1) {
               rest.append('\n');
            }
         }
      }
      return head + ext + block + "\n" + rest;
   }
}
