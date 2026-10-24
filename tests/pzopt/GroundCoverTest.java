package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ground cover and glints (2026-10-24): the programs link and validate with the real driver and with Mesa (llvmpipe), headless
 * through /tmp/glslcheck (tools/hdr/glslcheck.c; skipped without it); the blue-noise placement tile is well spread on its torus
 * and every square of the tile gets its share; the leaf-fall / flower curves follow the months.
 */
public class GroundCoverTest {
   public static void main(String[] args) throws Exception {
      // placement: the progressive blue-noise tile
      GroundCover.BlueNoise.init();
      int n = GroundCover.BlueNoise.N;
      float T = GroundCover.BlueNoise.TILE;
      float minPrefix = Float.MAX_VALUE;
      int prefix = n / 8; // any prefix is well spread: the first eighth (the sparsest densities) has no near pair
      for (int i = 0; i < prefix; i++) {
         for (int j = 0; j < i; j++) {
            float dx = Math.abs(GroundCover.BlueNoise.px[i] - GroundCover.BlueNoise.px[j]);
            float dy = Math.abs(GroundCover.BlueNoise.py[i] - GroundCover.BlueNoise.py[j]);
            dx = Math.min(dx, T - dx);
            dy = Math.min(dy, T - dy);
            minPrefix = Math.min(minPrefix, (float)Math.sqrt(dx * dx + dy * dy));
         }
      }
      // a jittered grid of the same count has pairs at ~0.1 of its spacing; best candidate keeps them over ~0.6
      float spacing = T / (float)Math.sqrt(prefix);
      Check.check(minPrefix > 0.5F * spacing, "blue noise prefix min distance " + minPrefix + " vs spacing " + spacing);
      int total = 0;
      for (int[] sq : GroundCover.BlueNoise.bySquare) {
         Check.check(sq.length > 20 && sq.length < 52, "blue noise points a square: " + sq.length);
         total += sq.length;
      }
      Check.check(total == n, "blue noise squares hold every point");
      // hashes: world-anchored, the same across chunk borders (no per-chunk seed)
      Check.check(GroundCover.hf(7, -3, 11) == GroundCover.hf(7, -3, 11), "hash is deterministic");
      float sum = 0F;
      for (int i = 0; i < 4096; i++) {
         sum += GroundCover.hf(i, i * 3 - 7, 5);
      }
      Check.check(Math.abs(sum / 4096F - 0.5F) < 0.03F, "hash mean " + sum / 4096F);
      // the season's curves
      GroundCover.Season s = new GroundCover.Season();
      GroundCover.curves(s, 7 * 30.44F + 10F); // August 11
      float aug = s.leafFall, augFl = s.flowers;
      GroundCover.curves(s, 9 * 30.44F + 25F); // October 26
      float oct = s.leafFall;
      GroundCover.curves(s, 3 * 30.44F + 25F); // April 26
      float apr = s.flowers;
      Check.check(aug < 0.1F && oct > 0.8F, "leaf fall: August " + aug + " October " + oct);
      Check.check(augFl < 0.05F && apr > 0.6F, "flowers: August " + augFl + " April " + apr);
      // the sources, for the offline rig (harness/ground/cover/glint_rig.py flakes)
      Path dump = Path.of(System.getProperty("user.dir"), "build", "shader-dump");
      Files.createDirectories(dump);
      for (java.util.Map.Entry<String, String[]> e : Glints.sources().entrySet()) {
         if (e.getValue()[0] != null) {
            Files.writeString(dump.resolve("glints-" + e.getKey() + ".vert"), e.getValue()[0]);
         }
         Files.writeString(dump.resolve("glints-" + e.getKey() + (e.getValue()[0] == null ? ".comp" : ".frag")), e.getValue()[1]);
      }
      // the programs
      Path check = Path.of("/tmp/glslcheck");
      if (!Files.isExecutable(check)) {
         System.out.println("GroundCoverTest ok (programs skipped: no /tmp/glslcheck)");
         return;
      }
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      int programs = 0;
      for (boolean useMesa : new boolean[] {false, true}) {
         if (useMesa && !Files.exists(mesa)) {
            continue;
         }
         programs += link(check, useMesa, mesa, "ground cover", GroundCover.VERT, GroundCover.FRAG);
         programs += link(check, useMesa, mesa, "wind leaves", WindLeaves.VERT, WindLeaves.FRAG);
         for (java.util.Map.Entry<String, String[]> e : Glints.sources().entrySet()) {
            if (e.getValue()[0] != null) {
               programs += link(check, useMesa, mesa, "glints " + e.getKey(), e.getValue()[0], e.getValue()[1]);
            }
         }
      }
      System.out.println("GroundCoverTest ok (" + programs + " programs linked)");
   }

   static int link(Path check, boolean useMesa, Path mesa, String tag, String vert, String frag) throws Exception {
      Path vp = Files.writeString(Files.createTempFile("pzgc", ".vert"), vert);
      Path fp = Files.writeString(Files.createTempFile("pzgc", ".frag"), frag);
      ProcessBuilder pb = new ProcessBuilder(check.toString(), vp.toString(), fp.toString());
      if (useMesa) {
         pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
         pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
      }
      Process p = pb.redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes());
      p.waitFor();
      Files.deleteIfExists(vp);
      Files.deleteIfExists(fp);
      Check.check(out.contains("link ok") && out.contains("validate ok"), tag + (useMesa ? " with Mesa" : " with the driver") + ":\n" + out);
      return 1;
   }
}
