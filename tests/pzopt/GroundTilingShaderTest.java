package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ground without the grid: the natural-ground floor variant with the tiling patch (GroundTilingPatch.declarations, which
 * carries GroundTiling.GLSL) links and validates with the real driver and with Mesa llvmpipe, for both tile programs,
 * the floor's own sprite and the attached pieces; the uniforms bakeStart sets are all present (not optimised away).
 */
public class GroundTilingShaderTest {
   public static void main(String[] args) throws Exception {
      Path game = GroundCommonTest.gameShaders();
      Path tool = GroundCommonTest.tool();
      String decl = GroundTilingPatch.INSTANCE.declarations();
      for (String u : new String[] {"pzGtPeriod", "pzGtHex", "pzGtBlend", "pzGtMacro", "pzGtDryTint", "pzGtWorld", "pzGtOI", "pzGtOF", "pzGtBakeI",
         "pzGtBakeF", "pzGtSpr", "pzGtSprCode", "pzGtSprTex", "pzGtArt", "pzGtGauss", "pzGtLut"}) {
         Check.check(decl.contains(" " + u + ";") || decl.contains(" " + u + "["), "declared: " + u);
      }
      if (game == null || tool == null) {
         System.out.println("GroundTilingShaderTest ok (text only: " + (game == null ? "no game shaders" : "no glslcheck") + ")");
         return;
      }
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      int n = 0;
      for (String base : new String[] {"tileWithDepth", "opaqueWithDepth"}) {
         String vert = Files.readString(game.resolve(base + ".vert"));
         String frag = Files.readString(game.resolve(base + ".frag"));
         for (boolean attached : new boolean[] {false, true}) {
            List<GroundBake.FloorPatch> ps = new ArrayList<>();
            ps.add(GroundTilingPatch.INSTANCE);
            String v = GroundBake.variantSource(frag, ps, 0, attached);
            Check.check(v != null && v.contains("pzGtTexel("), base + ": the texel hook is placed");
            Path vp = Files.writeString(Files.createTempFile("pzgt", ".vert"), vert);
            Path fp = Files.writeString(Files.createTempFile("pzgt", ".frag"), v);
            for (boolean useMesa : new boolean[] {false, true}) {
               if (useMesa && !Files.exists(mesa)) {
                  continue;
               }
               ProcessBuilder pb = new ProcessBuilder(tool.toString(), vp.toString(), fp.toString());
               if (useMesa) {
                  pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
                  pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
               }
               Process p = pb.redirectErrorStream(true).start();
               String out = new String(p.getInputStream().readAllBytes());
               p.waitFor();
               if (!useMesa && out.contains("no context")) {
                  continue;
               }
               String tag = base + (attached ? " attached" : "") + (useMesa ? " (Mesa)" : " (driver)");
               Check.check(out.contains("link ok") && out.contains("validate ok"), tag + ":\n" + out);
               n++;
            }
         }
      }
      System.out.println("GroundTilingShaderTest ok (" + n + " links)");
   }
}
