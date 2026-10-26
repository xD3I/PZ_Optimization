package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The torch shadow map's programs (pzopt.TorchShadowMap, pplShadowMode=map, 2026-10-25): the caster cell program, the splat program (map-ism), the moment programs (evsm / msm), the screen-space march and sss passes, for each parametrization (oct, dp, cube, planar), and the
 * chunk composite with the map's read in place of the march's (full program and the dev variant), link and validate with
 * the real driver and with Mesa (llvmpipe through glvnd's Mesa EGL vendor), headless through /tmp/glslcheck
 * (tools/hdr/glslcheck.c). Skipped without the checker.
 */
public class TorchShadowMapShaderTest {
   public static void main(String[] args) throws Exception {
      Path check = Path.of(System.getProperty("glslcheck", "/tmp/glslcheck"));
      boolean gs = Boolean.getBoolean("glslcheck.gs"); // the checker takes a .geom unit (tools/hdr/glslcheck.c since 2026-10-26)
      if (!Files.isExecutable(check)) {
         System.out.println("TorchShadowMapShaderTest skipped (no /tmp/glslcheck)");
         return;
      }
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      Path dir = Files.createTempDirectory("tsmcheck");
      int bad = 0, n = 0;
      Path cf = Files.writeString(dir.resolve("cell.frag"), TorchShadowMap.CELL_FRAG);
      Path mf = Files.writeString(dir.resolve("moments.frag"), TorchShadowMap.MOMENT_FRAG);
      Path bv = Files.writeString(dir.resolve("blur.vert"), TorchShadowMap.BLUR_VERT);
      Path bf = Files.writeString(dir.resolve("blur.frag"), TorchShadowMap.BLUR_FRAG);
      String tint = (String)field("TINT"), body = (String)field("CHUNK_FRAG_BODY"), vert = (String)field("CHUNK_BASE_VERT");
      String hook = "float pplMaskN(vec3 P, vec3 n) { return pplMask(P); }";
      String mapNow = "#define PPL_SM_MAP\n" + TorchShadowMap.SAMPLE_GLSL; // pplShadowMode=map (the default since 2026-10-26): back to the hook
      if (!body.contains(hook) && body.contains(mapNow)) {
         body = body.replace(mapNow, hook);
      }
      Check.check(body.contains(hook), "the composite holds the march's mask hook");
      Path v = Files.writeString(dir.resolve("chunk.vert"), vert);
      Path qv = Files.writeString(dir.resolve("quad.vert"), AmbientOcclusion.QUAD_VERT);
      Path mfr = Files.writeString(dir.resolve("march.frag"), (String)field("SHADOW_FRAG"));
      Path sfr = Files.writeString(dir.resolve("sss.frag"), (String)field("SSS_FRAG"));
      Path hfr = Files.writeString(dir.resolve("hiz.frag"), (String)field("HIZ_FRAG"));
      Path hrf = Files.writeString(dir.resolve("hizreduce.frag"), (String)field("HIZ_REDUCE_FRAG"));
      Path tfr = Files.writeString(dir.resolve("tblur.frag"), (String)field("MASK_TBLUR_FRAG"));
      String[][] frags = {{"full", ""}, {"dev", "#define PPL_DEV\n"}, {"base", "#define PPL_BASE\n"}};
      String[] params = {"oct", "dp", "cube", "planar"};
      for (boolean useMesa : new boolean[] {false, true}) {
         if (useMesa && !Files.exists(mesa)) {
            continue;
         }
         bad += run(check, useMesa, mesa, "moment blur", bv, bf);
         bad += run(check, useMesa, mesa, "march", qv, mfr);
         bad += run(check, useMesa, mesa, "sss", qv, sfr);
         bad += run(check, useMesa, mesa, "hiz march", qv, hfr);
         bad += run(check, useMesa, mesa, "hiz reduce", qv, hrf);
         n += 2;
         bad += run(check, useMesa, mesa, "temporal blur", qv, tfr);
         n += 4;
         for (int param = 0; param < params.length; param++) {
            String pn = params[param];
            Path cv = Files.writeString(dir.resolve("cell-" + pn + ".vert"), TorchShadowMap.cellVert(param));
            Path iv = Files.writeString(dir.resolve("ism-" + pn + ".vert"), TorchShadowMap.ismVert(param));
            bad += run(check, useMesa, mesa, pn + " cell", cv, cf);
            bad += run(check, useMesa, mesa, pn + " splat", iv, cf);
            bad += run(check, useMesa, mesa, pn + " moments", cv, mf);
            n += 3;
            Path cc = Files.writeString(dir.resolve("cells-" + pn + ".comp"), TorchShadowMap.cellComp(param));
            bad += run(check, useMesa, mesa, pn + " compute cells", cc, null);
            Path dv = Files.writeString(dir.resolve("cs-" + pn + ".vert"), TorchShadowMap.csVert(param));
            bad += run(check, useMesa, mesa, pn + " compute cells draw", dv, cf);
            bad += run(check, useMesa, mesa, pn + " compute cells draw moments", dv, mf);
            n += 3;
            if (gs) {
               Path gv = Files.writeString(dir.resolve("gs.vert"), TorchShadowMap.GS_VERT);
               Path gg = Files.writeString(dir.resolve("cell-" + pn + ".geom"), TorchShadowMap.cellGeom(param));
               bad += run(check, useMesa, mesa, pn + " gs cell", gv, cf, gg);
               bad += run(check, useMesa, mesa, pn + " gs moments", gv, mf, gg);
               n += 2;
            }
            String mapBody = body.replace(hook, "#define PPL_SM_MAP\n" + TorchShadowMap.sampleGlsl(param));
            for (String[] f : frags) {
               Path p = Files.writeString(dir.resolve("chunk-" + pn + "-" + f[0] + ".frag"), "#version 420\n" + f[1] + tint + mapBody);
               bad += run(check, useMesa, mesa, pn + " composite " + f[0], v, p);
               n++;
            }
            // the compiled-in filters (PPL_SM_FILTER: 0 hard, 2 pcss, 3 evsm, 4 msm, 5 soft; 1 pcf is the default above)
            for (int flt : new int[] {0, 2, 3, 4, 5}) {
               Path p = Files.writeString(dir.resolve("chunk-" + pn + "-f" + flt + ".frag"), "#version 420\n#define PPL_SM_FILTER " + flt + "\n" + tint + mapBody);
               bad += run(check, useMesa, mesa, pn + " composite filter " + flt, v, p);
               n++;
            }
         }
      }
      System.out.println(bad == 0 ? "TorchShadowMapShaderTest ok (" + n + " programs, " + dir + ")" : "TorchShadowMapShaderTest: " + bad + " of " + n + " programs failed (" + dir + ")");
      if (bad != 0) {
         System.exit(1);
      }
   }

   private static Object field(String name) throws Exception {
      java.lang.reflect.Field f = PixelLight.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(null);
   }

   private static int run(Path check, boolean useMesa, Path mesa, String tag, Path vert, Path frag, Path... more) throws Exception {
      java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(check.toString(), vert.toString()));
      if (frag != null) {
         cmd.add(frag.toString());
      }
      for (Path m : more) {
         cmd.add(m.toString());
      }
      ProcessBuilder pb = new ProcessBuilder(cmd);
      if (useMesa) {
         pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
         pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
      }
      Process p = pb.redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes());
      p.waitFor();
      boolean ok = out.contains("link ok") && out.contains("validate ok");
      System.out.println((useMesa ? "mesa " : "driver ") + tag + ": " + (ok ? "ok" : "FAILED\n" + out));
      return ok ? 0 : 1;
   }
}
