package pzopt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Grass with blades (pzopt.GrassBlades): the dev alternation's knob parser, the mesh shader's limits, the blades' screen
 * rectangle, and (with /tmp/glslcheck, tools/hdr/glslcheck.c) the draw programs linking and validating with the driver and
 * with Mesa llvmpipe: the compute path's vertex program, the vertex-only (OpenGL 4.1, the Mac) path's root pass and draw,
 * the layer composite. The compute programs are compiled by the offline rig (tools/GrassRig.java) on the real driver.
 */
public class GrassBladesTest {
   public static void main(String[] args) throws Exception {
      // knobs
      GrassBlades.Opts o = new GrassBlades.Opts();
      o.apply("segments:2;density=50,waves:0;tufts:false;drawMode:3;layerDiv:2;layerEvery:4;feedback:true;on:false");
      Check.check(o.segments == 2 && o.densityPct == 50 && o.wavesPct == 0 && !o.tufts && o.drawMode == 3, "knobs parsed");
      Check.check(o.layerDiv == 2 && o.layerEvery == 4 && o.feedback && o.off, "layer / feedback / off knobs parsed");
      GrassBlades.Opts c = o.copy();
      Check.check(c.segments == 2 && c.layerEvery == 4 && c.feedback && !c.tufts && c.wavesPct == 0, "copy keeps the knobs");
      o.apply("segments:x;bogus:1"); // a bad value or an unknown key only warns
      Check.check(o.segments == 2, "a bad knob value leaves the knob");
      Check.check(GrassBlades.Gpu.perSquare(o) == 24, "density 50 % = 24 candidates a square");

      // the mesh shader stays inside GL_NV_mesh_shader's limits (256 vertices, 512 primitives)
      for (int seg = 1; seg <= 8; seg++) {
         String ms = GrassBlades.meshShader(seg);
         int v = GrassBlades.MESH_BLADES * (2 * seg + 1), t = GrassBlades.MESH_BLADES * (2 * seg - 1);
         Check.check(ms.contains("max_vertices = " + v + ", max_primitives = " + t), "mesh limits for " + seg + " segments");
         Check.check(seg > 7 || v <= 256 && t <= 512, "mesh shader within the NV limits at " + seg + " segments");
      }

      // the blades' rectangle covers every square's root and a blade above it
      GrassBlades.Params p = new GrassBlades.Params();
      p.o = new GrassBlades.Opts();
      p.vp[0] = 0;
      p.vp[1] = 0;
      p.vp[2] = 5120;
      p.vp[3] = 2160;
      float[] map = {0.015625F, -40F, -0.03125F, 32F, -692.8306F, 172F};
      System.arraycopy(map, 0, p.map, 0, 6);
      p.squares = new int[] {128 | 128 << 8 | 1 << 16, 130 | 125 << 8 | 2 << 16};
      p.n = 2;
      int[] r = new int[4];
      GrassBlades.Gpu.bladeRect(p, r);
      for (int i = 0; i < p.n; i++) {
         int s = p.squares[i];
         float u = (s & 255) - 128 - ((s >> 8 & 255) - 128), v = (s & 255) - 128 + ((s >> 8 & 255) - 128);
         float x = (u - map[1]) / map[0], y = (v - map[3]) / map[2];
         Check.check(x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3], "root " + i + " inside the rectangle " + java.util.Arrays.toString(r));
      }

      // GLSL
      Path check = Path.of("/tmp/glslcheck");
      if (!Files.isExecutable(check)) {
         System.out.println("GrassBladesTest ok (GLSL skipped: no /tmp/glslcheck)");
         return;
      }
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      String frag410 = GrassBlades.FRAG.replace("#version 430", "#version 410");
      String[][] programs = {
         {"draw (compute path)", GrassBlades.VERT, GrassBlades.FRAG},
         {"vertex-only draw", GrassBlades.INLINE_VERT, frag410},
         {"root pass", GrassBlades.ROOT_VERT, GrassBlades.ROOT_FRAG},
         {"layer composite", GrassBlades.RESOLVE_VERT.replace("#version 450", "#version 430"), GrassBlades.LAYER_COMPOSITE},
      };
      int n = 0;
      for (String[] prog : programs) {
         Path vp = Files.writeString(Files.createTempFile("pzgb", ".vert"), prog[1]);
         Path fp = Files.writeString(Files.createTempFile("pzgb", ".frag"), prog[2]);
         String out = RipplesLinkTest.run(new ProcessBuilder(check.toString(), vp.toString(), fp.toString()));
         Check.check(out.contains("link ok"), prog[0] + " with the driver:\n" + out);
         n++;
         if (Files.exists(mesa)) {
            ProcessBuilder pb = new ProcessBuilder(check.toString(), vp.toString(), fp.toString());
            pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
            pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
            String m = RipplesLinkTest.run(pb);
            Check.check(m.contains("link ok"), prog[0] + " with Mesa (llvmpipe):\n" + m);
            n++;
         }
      }
      System.out.println("GrassBladesTest ok (" + n + " program links)");
   }
}
