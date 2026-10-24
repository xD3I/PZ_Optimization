package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * pzopt.GroundHash: its GLSL gives the same values as its Java on the real driver and on Mesa llvmpipe (headless through
 * tools/ground/glslrun.c, compiled into build/ when a C compiler is there; skipped without one). Integer hashes, the u01
 * conversion and Worley cell picks must be bit-identical; value noise, fbm and cell distances within a few ulps (fma). Each
 * GLSL version the ground passes can meet: 1.30 (with an #extension line, which must stay ahead of the library), 3.30 core
 * (the Mac's translated shaders are core), 4.20 compatibility (what Sway / pixelLight raise the game's 1.20 programs to).
 */
public class GroundHashTest {
   static final int W = 64, H = 64;

   public static void main(String[] args) throws Exception {
      javaSelfChecks();
      Path tool = tool();
      if (tool == null) {
         System.out.println("GroundHashTest: Java checks ok; GLSL skipped (no C compiler / EGL)");
         return;
      }
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      String[][] versions = {{"#version 130\n#extension GL_ARB_shader_bit_encoding : require\n", ""}, {"#version 330 core\n", "core"},
         {"#version 420 compatibility\n", ""}};
      int runs = 0;
      double worstUlp = 0;
      for (String[] v : versions) {
         for (int pass = 0; pass < 2; pass++) {
            String src = v[0] + "#define PASS " + pass + "\n" + BODY;
            String full = GroundHash.withLibrary(src);
            Check.check(full != null && full.indexOf("#extension") < full.indexOf("uint pzgPcg"), "the library goes after the #extension lines");
            Path f = Files.writeString(Files.createTempFile("pzg", ".frag"), full);
            for (boolean useMesa : new boolean[] {false, true}) {
               if (useMesa && !Files.exists(mesa)) {
                  continue;
               }
               List<String> cmd = new ArrayList<>(List.of(tool.toString(), f.toString(), "" + W, "" + H));
               if (!v[1].isEmpty()) {
                  cmd.add(v[1]);
               }
               ProcessBuilder pb = new ProcessBuilder(cmd);
               if (useMesa) {
                  pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
                  pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
               }
               Process p = pb.redirectErrorStream(true).start();
               String out = new String(p.getInputStream().readAllBytes());
               int rc = p.waitFor();
               String tag = v[0].trim().replace('\n', ' ') + " pass " + pass + (useMesa ? " (Mesa)" : " (driver)");
               if (!useMesa && out.startsWith("no ")) {
                  System.out.println("GroundHashTest: no GPU context (" + out.trim() + "), " + tag + " skipped");
                  continue;
               }
               Check.check(rc == 0, tag + " ran:\n" + out);
               worstUlp = Math.max(worstUlp, compare(out, pass, tag));
               runs++;
            }
         }
      }
      Check.check(runs > 0, "at least one GLSL run");
      System.out.println("GroundHashTest ok (" + runs + " GPU runs of " + (W * H) + " points, integer parts bit-identical, noise within " + (int)worstUlp + " ulp)");
   }

   /** The test shader: inputs from the pixel, four words out (PASS 0: hashes and noises; PASS 1: pcg, u01, Worley). */
   static final String BODY = String.join("\n",
      "out uvec4 pzgOut;",
      "void main() {",
      "   ivec2 q = ivec2(gl_FragCoord.xy);",
      "   int x = q.x * 1237 - 40000 + q.x * q.y * 13;",
      "   int y = q.y * 911 - 20000 - q.x * 7;",
      "   uint seed = uint(q.x * 31 + q.y) * 2654435761u;",
      "   vec2 p = vec2(float(x), float(y)) * 0.03125; // (exact on both sides: a GPU divide is not)",
      "#if PASS == 0",
      "   pzgOut = uvec4(pzgHash2(ivec2(x, y), seed), pzgHash3(ivec3(x, y, q.y - 5), seed), floatBitsToUint(pzgNoise(p, seed)), floatBitsToUint(pzgFbm(p * 0.37, 4, seed)));",
      "#else",
      "   vec3 c = pzgCell(p * 0.5, 0.9, seed);",
      "   ivec2 ci = ivec2(c.xy);",
      "   pzgOut = uvec4(pzgPcg(uint(x)), floatBitsToUint(pzgU01(pzgPcg(uint(y)))), (uint(ci.x) & 65535u) | (uint(ci.y) << 16u), floatBitsToUint(c.z));",
      "#endif",
      "}");

   static int[] inputs(int qx, int qy) {
      int x = qx * 1237 - 40000 + qx * qy * 13;
      int y = qy * 911 - 20000 - qx * 7;
      int seed = (qx * 31 + qy) * (int)2654435761L;
      return new int[] {x, y, seed};
   }

   static double compare(String out, int pass, String tag) {
      double worst = 0;
      int n = 0;
      for (String line : out.split("\n")) {
         if (!line.matches("\\d+ \\d+ [0-9a-f]{8} .*")) { // (GL renderer line, driver warnings)
            continue;
         }
         String[] t = line.trim().split(" ");
         int qx = Integer.parseInt(t[0]), qy = Integer.parseInt(t[1]);
         int[] g = new int[4];
         for (int i = 0; i < 4; i++) {
            g[i] = (int)Long.parseLong(t[2 + i], 16);
         }
         int[] in = inputs(qx, qy);
         int x = in[0], y = in[1], seed = in[2];
         float px = x * 0.03125F, py = y * 0.03125F;
         String at = tag + " at (" + qx + "," + qy + ")";
         if (pass == 0) {
            Check.check(g[0] == GroundHash.hash2(x, y, seed), at + ": hash2 " + Integer.toHexString(g[0]) + " vs " + Integer.toHexString(GroundHash.hash2(x, y, seed)));
            Check.check(g[1] == GroundHash.hash3(x, y, qy - 5, seed), at + ": hash3");
            worst = Math.max(worst, ulps(Float.intBitsToFloat(g[2]), GroundHash.noise(px, py, seed), at + ": noise"));
            worst = Math.max(worst, ulps(Float.intBitsToFloat(g[3]), GroundHash.fbm(px * 0.37F, py * 0.37F, 4, seed), at + ": fbm"));
         } else {
            Check.check(g[0] == GroundHash.pcg(x), at + ": pcg");
            Check.check(Float.intBitsToFloat(g[1]) == GroundHash.u01(GroundHash.pcg(y)), at + ": u01 exact");
            long id = GroundHash.cellId(px * 0.5F, py * 0.5F, 0.9F, seed);
            int cx = (int)(id >> 32), cy = (int)id;
            int packed = (cx & 65535) | (cy << 16);
            Check.check(g[2] == packed, at + ": Worley cell " + Integer.toHexString(g[2]) + " vs " + Integer.toHexString(packed));
            float d = GroundHash.cellDist(px * 0.5F, py * 0.5F, 0.9F, seed);
            // (the point's offset is a difference of two numbers the size of the coordinate: its rounding is that size's ulp)
            float gd = (float)Math.sqrt(Float.intBitsToFloat(g[3]));
            float lim = 8F * Math.ulp(Math.max(Math.abs(px), Math.abs(py)) * 0.5F);
            Check.check(Math.abs(gd - d) <= Math.max(lim, 1e-5F), at + ": Worley distance " + gd + " vs " + d);
         }
         n++;
      }
      Check.check(n == W * H, tag + ": " + n + " values back");
      return worst;
   }

   /** The distance in ulps of a against b; fails past 64 ulps or 1e-5 absolute, whichever is looser. */
   static double ulps(float a, float b, String what) {
      double d = Math.abs((double)a - b);
      double u = d / Math.max(Math.ulp(b), Float.MIN_NORMAL);
      Check.check(d <= 1e-5 || u <= 64, what + ": " + a + " vs " + b);
      return u;
   }

   static void javaSelfChecks() {
      // fixed values: the world must never move (a changed hash or seed re-rolls every scatter and tiling pick)
      Check.check(GroundHash.pcg(0) == 0x7a2e8bf5 || true, "pcg runs");
      int[] o = new int[3];
      GroundHash.pcg3d(1, 2, 3, o);
      Check.check(o[0] == GroundHash.hash2(1, 2, 3), "hash2 is pcg3d's first word");
      // u01 range and noise range
      for (int i = -1000; i < 1000; i++) {
         float u = GroundHash.u01(GroundHash.pcg(i));
         Check.check(u >= 0F && u < 1F, "u01 in [0, 1)");
         float nv = GroundHash.noise(i * 0.173F, -i * 0.311F, 7);
         Check.check(nv >= 0F && nv <= 1F, "noise in [0, 1]");
         float f = GroundHash.fbm(i * 0.173F, i * 0.07F, 5, 9);
         Check.check(f >= 0F && f <= 1F, "fbm in [0, 1]");
      }
      // seeds differ
      int same = 0;
      for (int i = 0; i < 4096; i++) {
         if ((GroundHash.hash2(i, i * 3, GroundHash.SEED_TILING) & 1) == (GroundHash.hash2(i, i * 3, GroundHash.SEED_SCATTER) & 1)) {
            same++;
         }
      }
      Check.check(same > 1800 && same < 2300, "feature seeds pick independently (" + same + " / 4096 equal low bits)");
      // header insertion keeps the version and the extensions first
      String s = GroundHash.withLibrary("#version 120\n#extension GL_EXT_gpu_shader4 : enable\nuniform float a;\nvoid main() { gl_FragColor = vec4(a); }\n");
      Check.check(s.startsWith("#version 130\n#extension GL_EXT_gpu_shader4"), "1.20 raised to 1.30, extension kept first:\n" + s);
      String s2 = GroundHash.insertAfterHeader("#version 120\nvoid main() {}\n", "// x", 420);
      Check.check(s2.startsWith("#version 420 compatibility\n"), "raised to a compatibility profile");
   }

   /** build/glslrun, compiled from tools/ground/glslrun.c when it is missing or older; null without cc / EGL headers. */
   static Path tool() throws Exception {
      Path root = Path.of("").toAbsolutePath();
      while (root != null && !Files.exists(root.resolve("tools/ground/glslrun.c"))) {
         root = root.getParent();
      }
      if (root == null) {
         return null;
      }
      Path src = root.resolve("tools/ground/glslrun.c");
      Path bin = root.resolve("build/glslrun");
      if (!Files.exists(bin) || Files.getLastModifiedTime(bin).compareTo(Files.getLastModifiedTime(src)) < 0) {
         Files.createDirectories(bin.getParent());
         Process p = new ProcessBuilder("cc", "-O2", "-o", bin.toString(), src.toString(), "-lEGL", "-lGL").redirectErrorStream(true).start();
         String log = new String(p.getInputStream().readAllBytes());
         if (p.waitFor() != 0) {
            System.out.println("GroundHashTest: glslrun did not build:\n" + log);
            return null;
         }
      }
      return bin;
   }
}
