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
      int n = shaders();
      System.out.println("GroundCommonTest ok (" + n + " variant programs linked)");
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
         raw[i] = (byte)(i * 7);
         raw[i + 3] = (byte)5;
      }
      GroundState.writeChunk(d, -3, 17, raw, 0.0);
      byte[] other = raw.clone();
      other[0] = 99;
      GroundState.writeChunk(d, -4, 17, other, 0.0); // same region: the first must survive
      byte[] back = GroundState.readChunk(d, -3, 17);
      Check.check(back != null && java.util.Arrays.equals(back, raw), "region round trip");
      byte[] back2 = GroundState.readChunk(d, -4, 17);
      Check.check(back2 != null && back2[0] == 99, "second chunk in the same region");
      Check.check(GroundState.readChunk(d, -5, 17) == null, "absent chunk");
      GroundState.writeChunk(d, -3, 17, new byte[raw.length], 0.0); // an empty chunk is dropped from the region
      Check.check(GroundState.readChunk(d, -3, 17) == null, "an empty chunk is removed");
      Check.check(GroundState.readChunk(d, -4, 17) != null, "its neighbour stays");
      Check.check(GroundState.regionFiles(d) == 1, "one region file");
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
            "   float age = pzgStateAge(st.a);",
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
         for (int view = 0; view <= 2; view++) {
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
}
