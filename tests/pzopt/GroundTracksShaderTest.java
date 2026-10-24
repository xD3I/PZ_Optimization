package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The ground tracks' programs (pzopt.GroundTracks, 2026-10-24) link with the real driver and with Mesa (llvmpipe through
 * glvnd's Mesa EGL vendor), headless through glslcheck (tools/hdr/glslcheck.c; build/glslcheck first, it takes a .comp unit
 * alone, else /tmp/glslcheck): the chunk-texture bake program for the keys' defaults and every dev look variant, and
 * GroundState's compute program with the tracks rules. Skipped without the checker.
 */
public class GroundTracksShaderTest {
   public static void main(String[] args) throws Exception {
      Path check = Path.of("build/glslcheck");
      if (!Files.isExecutable(check)) {
         check = Path.of("/tmp/glslcheck");
      }
      if (!Files.isExecutable(check)) {
         System.out.println("GroundTracksShaderTest skipped (no glslcheck)");
         return;
      }
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      int n = 0;
      for (String v : new String[] {null, "full", "flat", "nopom", "noshadow", "noao", "colour"}) {
         String h = GroundTracks.header(v);
         Path vp = Files.writeString(Files.createTempFile("pzgt", ".vert"), GroundTracks.bakeVert(h));
         Path fp = Files.writeString(Files.createTempFile("pzgt", ".frag"), GroundTracks.bakeFrag(h));
         n += checkBoth(check, mesa, "bake program (" + v + ")", vp.toString(), fp.toString());
      }
      String h0 = GroundTracks.header(null);
      Path cvp = Files.writeString(Files.createTempFile("pzgt", ".vert"), h0 + GroundTracks.COMP_VERT);
      Path cfp = Files.writeString(Files.createTempFile("pzgt", ".frag"), GroundTracks.bakeFrag(h0));
      n += checkBoth(check, mesa, "composite program", cvp.toString(), cfp.toString());
      Path rfp = Files.writeString(Files.createTempFile("pzgt", ".frag"), GroundTracks.rtFrag(h0));
      n += checkBoth(check, mesa, "realtime program", cvp.toString(), rfp.toString());
      Path cp = Files.writeString(Files.createTempFile("pzgt", ".comp"), GroundState.computeSource(GroundTracks.RULES));
      String out = RipplesLinkTest.run(new ProcessBuilder(check.toString(), cp.toString()));
      if (out.contains("usage") || (!out.contains("link ok") && out.contains("vertex"))) {
         System.out.println("GroundTracksShaderTest: " + check + " takes no compute unit, compute skipped");
      } else {
         n += checkBoth(check, mesa, "compute program (tracks rules)", cp.toString());
      }
      // the floor tile program's variant with the patch look and the grass-object patch: floors (piece 0) and grass objects (3)
      Path game = GroundCommonTest.gameShaders();
      if (game != null) {
         String vert = Files.readString(game.resolve("tileWithDepth.vert"));
         String frag = Files.readString(game.resolve("tileWithDepth.frag"));
         Path vp = Files.writeString(Files.createTempFile("pzgt", ".vert"), vert);
         for (int piece : new int[] {0, 3}) {
            java.util.List<GroundBake.FloorPatch> ps = new java.util.ArrayList<>();
            if (piece == 0) {
               ps.add(GroundTracks.PATCH);
            }
            ps.add(GroundTracks.GRASS);
            String v = GroundBake.variantSource(frag, ps, 0, piece);
            Check.check(v != null, "floor variant source, piece " + piece);
            Path fp = Files.writeString(Files.createTempFile("pzgt", ".frag"), v);
            n += checkBoth(check, mesa, "floor variant piece " + piece + " (patch + grass)", vp.toString(), fp.toString());
         }
      } else {
         System.out.println("GroundTracksShaderTest: no game shaders, the floor variant skipped");
      }
      System.out.println("GroundTracksShaderTest ok (" + n + " programs)");
   }

   private static int checkBoth(Path check, Path mesa, String tag, String... units) throws Exception {
      String[] cmd = new String[units.length + 1];
      cmd[0] = check.toString();
      System.arraycopy(units, 0, cmd, 1, units.length);
      String out = RipplesLinkTest.run(new ProcessBuilder(cmd));
      Check.check(out.contains("link ok"), tag + " with the driver:\n" + out);
      int n = 1;
      if (Files.exists(mesa)) {
         ProcessBuilder pb = new ProcessBuilder(cmd);
         pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
         pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
         out = RipplesLinkTest.run(pb);
         Check.check(out.contains("link ok"), tag + " with Mesa (llvmpipe):\n" + out);
         n++;
      }
      return n;
   }
}
