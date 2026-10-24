package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ground and grass, shared part (2026-10-24): GroundTiles' sheet rules, GroundState's region files, and GroundBake's floor
 * variants, built from the game's own tileWithDepth / opaqueWithDepth with a test patch that touches every helper, compile,
 * link and validate with the real driver and with Mesa llvmpipe (headless, build/glslcheck from tools/hdr/glslcheck.c; the
 * GPU part is skipped without a C compiler or the game's shaders).
 */
public class GroundCommonTest {
   public static void main(String[] args) throws Exception {
      tiles();
      regions();
      chunkSlots();
      int n = shaders();
      int g = stateGpu();
      System.out.println("GroundCommonTest ok (" + n + " variant programs linked, " + g + " GroundState GPU runs)");
   }

   /** GroundChunk: slots per chunk, dropped and released on reuse, a stale asynchronous result refused. */
   static void chunkSlots() throws Exception {
      java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      uf.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) uf.get(null);
      zombie.iso.IsoChunk c = (zombie.iso.IsoChunk) unsafe.allocateInstance(zombie.iso.IsoChunk.class);
      List<Object> released = new ArrayList<>();
      int a = GroundChunk.id("test.a", released::add), b = GroundChunk.id("test.b");
      Check.check(a != b && GroundChunk.id("test.a") == a, "slot ids");
      Check.check(GroundChunk.get(c, a) == null, "empty slot");
      GroundChunk.put(c, a, "A");
      GroundChunk.put(c, b, "B");
      Check.check("A".equals(GroundChunk.get(c, a)) && "B".equals(GroundChunk.get(c, b)), "put / get");
      int g = GroundChunk.gen(c);
      Check.check(GroundChunk.putIf(c, b, g, "B2") && "B2".equals(GroundChunk.get(c, b)), "putIf same generation");
      GroundChunk.reused(c);
      Check.check(GroundChunk.get(c, a) == null && GroundChunk.get(c, b) == null, "reuse drops the slots");
      Check.check(released.size() == 1 && "A".equals(released.get(0)), "release hook gets the dropped value");
      Check.check(!GroundChunk.putIf(c, a, g, "late") && GroundChunk.get(c, a) == null, "a stale result is refused");
   }

   static void tiles() {
      int g = GroundTiles.classify("blends_natural_01_16", null);
      Check.check(GroundTiles.material(g) == GroundTiles.GRASS && (g & GroundTiles.F_BASE) != 0 && GroundTiles.tone(g) == 0, "light grass base");
      g = GroundTiles.classify("blends_natural_01_34", null);
      Check.check(GroundTiles.material(g) == GroundTiles.GRASS && GroundTiles.tone(g) == 1 && (g & GroundTiles.F_BLEND) != 0, "medium grass blend piece");
      Check.check(GroundTiles.material(GroundTiles.classify("blends_natural_01_64", null)) == GroundTiles.DIRT, "dirt");
      Check.check(GroundTiles.material(GroundTiles.classify("blends_natural_01_0", null)) == GroundTiles.SAND, "sand");
      Check.check(GroundTiles.material(GroundTiles.classify("blends_natural_01_96", null)) == GroundTiles.CLAY, "clay");
      Check.check(GroundTiles.classify("blends_natural_01_112", null) == 0, "rows past clay are not ground");
      Check.check((GroundTiles.classify("blends_natural_01_87", null) & GroundTiles.F_FARM) != 0, "dug soil is farmland");
      Check.check(GroundTiles.material(GroundTiles.classify("floors_exterior_natural_01_13", null)) == GroundTiles.GRAVEL, "old gravel");
      Check.check(GroundTiles.material(GroundTiles.classify("blends_street_01_53", null)) == GroundTiles.GRAVEL, "street gravel");
      Check.check(GroundTiles.classify("blends_street_01_0", null) == 0, "road is not ground");
      Check.check((GroundTiles.classify("blends_natural_02_3", null) & GroundTiles.F_EXCLUDED) != 0, "water and shore excluded");
      Check.check((GroundTiles.classify("vegetation_farming_01_3", null) & GroundTiles.F_EXCLUDED) != 0, "farm sheets excluded");
      Check.check(GroundTiles.classify("floors_interior_tilesandwood_01_0", null) == 0, "built floors are not ground");
      Check.check(GroundTiles.classify("blends_natural_01_x", null) == 0, "a name without an index");
      Check.check(GroundTiles.natural(GroundTiles.classify("blends_natural_01_48", null)), "dark grass is natural");
      Check.check(!GroundTiles.natural(GroundTiles.classify("e_newgrass_1_3", null)), "an overlay alone is not a natural floor");
      int b = GroundTiles.mapByte(GroundTiles.classify("blends_natural_01_33", null));
      Check.check((b & 15) == GroundTiles.GRASS && (b & 32) != 0 && b < 256, "map byte: material + blend bit");
   }

   static void regions() throws Exception {
      File d = Files.createTempDirectory("pzg-region").toFile();
      byte[] raw = new byte[GroundState.CHUNK_TEX * GroundState.CHUNK_TEX * 4];
      for (int i = 0; i < raw.length; i += 4) {
         raw[i] = GroundState.REST_R;
         raw[i + 1] = (byte)(i * 7);
      }
      GroundState.writeChunk(d, -3, 17, raw, 12.5);
      byte[] other = raw.clone();
      other[1] = 99;
      GroundState.writeChunk(d, -4, 17, other, 13.0); // same region: the first must survive
      double[] at = new double[1];
      byte[] back = GroundState.readChunk(d, -3, 17, at);
      Check.check(back != null && java.util.Arrays.equals(back, raw) && at[0] == 12.5, "region round trip with its hour");
      byte[] back2 = GroundState.readChunk(d, -4, 17, at);
      Check.check(back2 != null && back2[1] == 99 && at[0] == 13.0, "second chunk in the same region");
      Check.check(GroundState.readChunk(d, -5, 17, at) == null, "absent chunk");
      byte[] rest = new byte[raw.length];
      for (int i = 0; i < rest.length; i += 4) {
         rest[i] = GroundState.REST_R;
      }
      // several chunks in one call, two regions: the one at rest is removed from its region
      GroundState.writeChunks(d, new int[] {-3, 40}, new int[] {17, 17}, new byte[][] {rest, raw}, 20.0);
      Check.check(GroundState.readChunk(d, -3, 17, at) == null, "a chunk at rest is removed");
      Check.check(GroundState.readChunk(d, -4, 17, at) != null, "its neighbour stays");
      Check.check(GroundState.readChunk(d, 40, 17, at) != null && at[0] == 20.0, "the other region written in the same call");
      Check.check(GroundState.regionFiles(d) == 2, "two region files");
      Check.check(GroundState.dir("Sandbox/../x y").getPath().endsWith("Sandbox" + File.separator + "_" + File.separator + "x_y"), "save id sanitised: " + GroundState.dir("Sandbox/../x y"));
   }

   /** A patch that reads every helper the floor variant gives. */
   static final GroundBake.FloorPatch TEST = new GroundBake.FloorPatch() {
      public String id() {
         return "test";
      }

      public boolean active() {
         return true;
      }

      public String declarations() {
         return String.join("\n",
            "uniform float pzgTestAmount;",
            "uniform sampler2D pzgTestTex;",
            "vec4 pzgTestTexel(vec4 t) {",
            "   float n = pzgFbm(pzgW.xy * 0.125, 3, PZG_SEED_TILING);",
            "   vec3 cell = pzgCell(pzgW.xy, 0.8, PZG_SEED_MACRO);",
            "   vec4 st = pzgStateAt(pzgW.xy);",
            "   float age = st.z;",
            "   int nb = pzgClassAt(pzgSq + ivec2(1, 0));",
            "   if (pzgMaterial() == 1 && pzgNatural(nb) && !pzgAttached) t.rgb *= 0.9 + 0.1 * n + 0.01 * cell.z;",
            "   return t * (1.0 - st.r * 0.3 * pzgTestAmount) + texture2D(pzgTestTex, pzgF) * 0.0 + vec4(age * 0.0);",
            "}",
            "vec4 pzgTestColor(vec4 c) { return c * (1.0 + 0.01 * pzgU01(pzgHash2(pzgSq, PZG_SEED_COMMON))); }");
      }

      public String texel() {
         return "pzgTestTexel";
      }

      public String color() {
         return "pzgTestColor";
      }

      public String[][] samplers() {
         return new String[][] {{"pzgTestTex", "58"}};
      }
   };

   static int shaders() throws Exception {
      Path game = gameShaders();
      Path tool = tool();
      if (game == null || tool == null) {
         System.out.println("GroundCommonTest: shader part skipped (" + (game == null ? "no game shaders" : "no glslcheck") + ")");
         return 0;
      }
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      int n = 0;
      for (String base : new String[] {"tileWithDepth", "opaqueWithDepth"}) {
         String vert = Files.readString(game.resolve(base + ".vert"));
         String frag = Files.readString(game.resolve(base + ".frag"));
         for (int view = 0; view <= 3; view++) {
            for (int piece = 0; piece < 3; piece++) {
               boolean attached = piece != 0;
               List<GroundBake.FloorPatch> ps = new ArrayList<>();
               ps.add(TEST);
               String v = GroundBake.variantSource(frag, ps, view, piece);
               Check.check(v != null, base + ": a variant source");
               Check.check(v.contains("pzgTexel(texture2D(DIFFUSE") && v.contains("pzgColor(c)") && v.contains("pzgSetup();"), base + ": hooks placed:\n" + v);
               Path vp = Files.writeString(Files.createTempFile("pzg", ".vert"), vert);
               Path fp = Files.writeString(Files.createTempFile("pzg", ".frag"), v);
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
                  String tag = base + " view " + view + (attached ? " piece " + piece : "") + (useMesa ? " (Mesa)" : " (driver)");
                  Check.check(out.contains("link ok") && out.contains("validate ok"), tag + ":\n" + out + "\n" + v);
                  n++;
               }
            }
         }
      }
      // no patches, no view: the variant is still the stock program plus unused helpers (it is never built then, but must not break)
      return n;
   }

   static Path gameShaders() {
      try {
         File jar = new File(zombie.core.Core.class.getProtectionDomain().getCodeSource().getLocation().toURI());
         Path p = jar.toPath().getParent().resolve("media/shaders");
         return Files.exists(p.resolve("tileWithDepth.frag")) ? p : null;
      } catch (Throwable t) {
         return null;
      }
   }

   /** build/glslcheck from tools/hdr/glslcheck.c (compiled when missing or older). */
   static Path tool() throws Exception {
      Path root = Path.of("").toAbsolutePath();
      while (root != null && !Files.exists(root.resolve("tools/hdr/glslcheck.c"))) {
         root = root.getParent();
      }
      if (root == null) {
         return null;
      }
      Path src = root.resolve("tools/hdr/glslcheck.c");
      Path bin = root.resolve("build/glslcheck");
      if (!Files.exists(bin) || Files.getLastModifiedTime(bin).compareTo(Files.getLastModifiedTime(src)) < 0) {
         Files.createDirectories(bin.getParent());
         Process p = new ProcessBuilder("cc", "-O2", "-o", bin.toString(), src.toString(), "-lEGL", "-lGL").redirectErrorStream(true).start();
         p.getInputStream().readAllBytes();
         if (p.waitFor() != 0) {
            return null;
         }
      }
      return bin;
   }

   // ------------------------------------------------------------------------------------------------ GroundState on the GPU

   /** Rules with a relax pass (links the framework's GS_RELAX path; the height is pulled to the neighbours' mean). */
   static final GroundState.Rules RELAX_RULES = new GroundState.Rules() {
      public String id() {
         return "test-relax";
      }

      public String glsl() {
         return GroundState.GLSL_DEFAULT_RULES + "float gsRelaxHeight(float h, vec4 nb, int mat) { return mix(h, dot(nb, vec4(0.25)), 0.5); }\n";
      }

      public boolean relax() {
         return true;
      }
   };

   /**
    * The game thread's binning (GroundState.testFrame: the window, the surfaces, the counting sort) run through the real
    * compute source on the driver and on Mesa, read back the way the persistence does: a wheel line and a print wear the
    * grass they cross and nothing else, a square without a surface keeps the rest texel, a long decay brings the wear back
    * to zero, and the relax rules link and run.
    */
   static int stateGpu() throws Exception {
      Path root = Path.of("").toAbsolutePath();
      while (root != null && !Files.exists(root.resolve("tools/ground/gsrun.c"))) {
         root = root.getParent();
      }
      if (root == null) {
         System.out.println("GroundCommonTest: GroundState GPU part skipped (no tools/ground/gsrun.c)");
         return 0;
      }
      Path bin = root.resolve("build/gsrun");
      Path src = root.resolve("tools/ground/gsrun.c");
      if (!Files.exists(bin) || Files.getLastModifiedTime(bin).compareTo(Files.getLastModifiedTime(src)) < 0) {
         Files.createDirectories(bin.getParent());
         Process p = new ProcessBuilder("cc", "-O2", "-o", bin.toString(), src.toString(), "-lEGL", "-lGL").redirectErrorStream(true).start();
         String o = new String(p.getInputStream().readAllBytes());
         if (p.waitFor() != 0) {
            System.out.println("GroundCommonTest: GroundState GPU part skipped (gsrun does not build: " + o + ")");
            return 0;
         }
      }
      // grass everywhere but the column x == 1005 (no surface: e.g. a road)
      GroundState.testSurface = (x, y) -> x == 1005 ? GroundTiles.SURF_NONE : GroundTiles.SURF_GRASS;
      final float px = 1000.5F, py = 2000.5F;
      GroundState.Frame f = GroundState.testFrame(px, py, () -> {
         GroundState.wheel(996.0, 2000.5, 1008.0, 2000.5, 0.12F, 0.1F, 0.04F, 0F);
         GroundState.ellipse(1000.5, 2004.5, 1F, 0F, 0.14F, 0.055F, 0.05F, 0.012F, 1F, 1F);
      });
      Check.check(f.nCells > 0 && f.nPrims == 2, "binned: " + f.nCells + " cells, " + f.nPrims + " prims");
      for (int c = 0; c < f.nCells; c++) {
         int sx = (f.cells[c * 4 + 1] & 0xFFFF) - 32768 + f.ox;
         Check.check(sx != 1005, "no cell on a square without a surface");
      }
      Path dir = Files.createTempDirectory("pzg-gs");
      Path cells = ints(dir.resolve("cells"), f.cells, f.nCells * 4);
      Path index = ints(dir.resolve("index"), f.index, f.nIndex);
      Path prims = floats(dir.resolve("prims"), f.prims, f.nPrims * 12);
      // the read-back: squares 994..1009 x 1996..2011 (a 256 x 256 texel region, inside the map)
      int rx = (994 * GroundState.TPS) & (GroundState.TN - 1), ry = (1996 * GroundState.TPS) & (GroundState.TN - 1);
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      int runs = 0;
      for (boolean useMesa : new boolean[] {false, true}) {
         if (useMesa && !Files.exists(mesa)) {
            continue;
         }
         for (GroundState.Rules rules : new GroundState.Rules[] {new GroundState.DefaultRules(), RELAX_RULES}) {
            for (boolean decay : new boolean[] {false, true}) {
               Path srcf = Files.writeString(dir.resolve("cs-" + rules.id() + ".comp"), GroundState.computeSource(rules));
               StringBuilder job = new StringBuilder();
               job.append("src ").append(srcf).append("\ntn ").append(GroundState.TN).append("\nrelax ").append(rules.relax() ? 1 : 0).append("\n");
               job.append("cells ").append(cells).append(' ').append(f.nCells).append("\nprims ").append(prims).append(' ').append(f.nPrims).append('\n');
               job.append("index ").append(index).append(' ').append(f.nIndex).append('\n');
               if (decay) {
                  GroundState.Frame d = GroundState.testFrame(px, py, () -> {
                  });
                  int ccx = (int)Math.floor(px / 8), ccy = (int)Math.floor(py / 8);
                  for (int cy = ccy - 1; cy <= ccy + 1; cy++) {
                     for (int cx = ccx - 1; cx <= ccx + 1; cx++) {
                        GroundState.testDecay(d, cx, cy, 1e5F);
                     }
                  }
                  job.append("decay ").append(ints(dir.resolve("decay"), d.decay, d.nDecay * 4)).append(' ').append(d.nDecay).append('\n');
               }
               Path out = dir.resolve("out.rgba");
               job.append("readback ").append(rx).append(' ').append(ry).append(" 256 256 ").append(out).append('\n');
               Path jf = Files.writeString(dir.resolve("job.txt"), job.toString());
               ProcessBuilder pb = new ProcessBuilder(bin.toString(), jf.toString());
               if (useMesa) {
                  pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
                  pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
               }
               Process p = pb.redirectErrorStream(true).start();
               String o = new String(p.getInputStream().readAllBytes());
               p.waitFor();
               if (!useMesa && o.contains("no context")) {
                  continue;
               }
               String tag = rules.id() + (decay ? " + decay" : "") + (useMesa ? " (Mesa)" : " (driver)");
               Check.check(o.contains("ran ok"), tag + ":\n" + o);
               byte[] px8 = Files.readAllBytes(out);
               // texel of world (x, y) in the read-back
               java.util.function.BiFunction<Double, Double, int[]> at = (wx, wy) -> {
                  int tx = (int)Math.floor(wx * GroundState.TPS) - 994 * GroundState.TPS, ty = (int)Math.floor(wy * GroundState.TPS) - 1996 * GroundState.TPS;
                  int o4 = (ty * 256 + tx) * 4;
                  return new int[] {px8[o4] & 255, px8[o4 + 1] & 255, px8[o4 + 2] & 255, px8[o4 + 3] & 255};
               };
               int[] onLine = at.apply(1001.5, 2000.5), off = at.apply(1001.5, 2003.0), road = at.apply(1005.5, 2000.5), print = at.apply(1000.5, 2004.5);
               if (!decay) {
                  Check.check(onLine[1] > 0 && onLine[2] > 0, tag + ": the wheel wears the grass it crosses " + java.util.Arrays.toString(onLine));
                  Check.check(print[1] > 0, tag + ": the print wears its grass " + java.util.Arrays.toString(print));
               } else {
                  Check.check(onLine[1] == 0 && onLine[2] == 0, tag + ": a long decay clears the wear " + java.util.Arrays.toString(onLine));
               }
               Check.check(off[0] == 192 && off[1] == 0, tag + ": a texel away from both is at rest " + java.util.Arrays.toString(off));
               Check.check(road[0] == 192 && road[1] == 0 && road[2] == 0, tag + ": a square without a surface is untouched " + java.util.Arrays.toString(road));
               Check.check(onLine[0] == 192, tag + ": the default rules keep the height " + java.util.Arrays.toString(onLine));
               runs++;
            }
         }
      }
      GroundState.testSurface = null;
      return runs;
   }

   static Path ints(Path p, int[] a, int n) throws Exception {
      java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(Math.max(4, n * 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < n; i++) {
         b.putInt(a[i]);
      }
      return Files.write(p, b.array());
   }

   static Path floats(Path p, float[] a, int n) throws Exception {
      java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(Math.max(4, n * 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < n; i++) {
         b.putFloat(a[i]);
      }
      return Files.write(p, b.array());
   }

}
