package pzopt;

import java.util.HashMap;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.weather.ClimateManager;

/**
 * Ground without the grid in the chunk bake: the floor-program patch ({@link GroundBake.FloorPatch}) that re-samples every
 * natural floor sprite (whole squares and the blends_natural edge pieces) from its material's art ({@link GroundTileArt})
 * at the texel's world position, with the tiling of {@link GroundTiling} ({@code groundHexTiling}), the macro drift
 * ({@code groundMacroPct}) and the height-blended edges ({@code groundHeightBlend}). Baked: no per-frame cost.
 *
 * <p>Which sprite a fragment belongs to: the stock program fetches the art at {@code texCoord}; the patch finds the table
 * row whose page rect holds it ({@link GroundTileArt#sprites()}) and confirms it with three probe texels of the bound
 * page (an overlay of another page at the same uv fails them and stays stock). The exemplar pixel is exact integer
 * arithmetic from the bake's mapping (GroundBake.mapping's terms), so chunk textures agree texel for texel at their
 * borders; the world position for the cell lattices and noise is local to the chunk origin, whose lattice split the CPU
 * does in double ({@link GroundTiling#origin}).
 */
public final class GroundTilingPatch implements GroundBake.FloorPatch {
   static final GroundTilingPatch INSTANCE = new GroundTilingPatch();
   /** Texture units: the sprite table, then the art, Gaussian and LUT arrays. */
   static final int UNIT = 60;

   static {
      GroundBake.register(INSTANCE);
      // the clock anchor harness/ground-tiling/bakecost.py needs to put pzopt-gpusections.out's nanoTime pairs into the
      // alternation's epoch-ms halves
      Log.info("ground tiling: clock nano=" + System.nanoTime() + " epoch_ms=" + System.currentTimeMillis());
   }

   /** Loads the class (registers the patch). */
   public static void init() {
   }

   private GroundTilingPatch() {
   }

   @Override
   public String id() {
      return "tiling";
   }

   // ---------------------------------------------------------------------------------------------- game thread

   static final String[] MODES = {"stock", "hex", "hpb", "bomb", "voronoi", "two", "lap", "ident", "grid"}; // ident (dev): the exemplar at offset 0; grid (dev): the stock texel with the exemplar's square edges (red) and the geometry's (blue)
   private static volatile float[] params = new float[0]; // the frame's uniform values (game thread -> render thread)
   private static String lastSig = "";
   private static float seasonDry = -1F;
   private static int seasonHour = -1;
   private static boolean kicked;

   static int modeIndex(String name) {
      for (int i = 0; i < MODES.length; i++) {
         if (MODES[i].equalsIgnoreCase(name)) {
            return i;
         }
      }
      return -1;
   }

   @Override
   public boolean active() {
      boolean hex = Ground.on(Ground.K_HEX), height = Ground.on(Ground.K_HEIGHT);
      int macro = Ground.macroPct();
      if (!hex && !height && macro <= 0) {
         return false;
      }
      if (GroundTileArt.artTex == 0) {
         if (!GroundTileArt.failed && !kicked) {
            kicked = true;
            SpriteRenderer.instance.drawGeneric(ART);
         }
         return false;
      }
      int mode = 0;
      String v = Ground.variant();
      if (v != null && hex) {
         // devGroundCycle names: <mode>[+b][+m] (the height blend / the macro at 100 % for that step, else off)
         String[] t = v.split("\\+");
         mode = Math.max(0, modeIndex(t[0]));
         height = false;
         macro = 0;
         for (int i = 1; i < t.length; i++) {
            height |= t[i].equals("b");
            macro = t[i].equals("m") ? 100 : macro;
         }
         if (mode == 0 && !height && macro == 0) {
            return false;
         }
      } else if (hex) {
         mode = modeIndex(Config.DEV_GROUND_TILING_MODE);
         if (mode <= 0) {
            mode = 2;
         }
      }
      float dry = seasonDryness();
      float[] p = {
         mode, Config.DEV_GROUND_TILING_CELL_PCT / 100F, 0.6F, 7F, 0.5F, // tiling
         height ? 0.12F : 0F, 0.18F, 1.1F, 0.35F, // blend: softness (score units), noise amplitude, noise cell (squares), height weight
         0.12F * macro / 100F, 0.3F * dry * macro / 100F, 8F, 24F, // macro
         GroundTileArt.DRY_TINT[0], GroundTileArt.DRY_TINT[1], GroundTileArt.DRY_TINT[2],
         seed()};
      String sig = java.util.Arrays.toString(p);
      if (!sig.equals(lastSig)) {
         // (the first time the patch set changes GroundBake re-bakes by itself; a parameter change within it re-bakes here)
         if (!lastSig.isEmpty()) {
            GroundBake.rebakeAll("ground tiling " + MODES[mode] + (height ? " +height" : "") + (macro > 0 ? " +macro" + macro : ""), false);
         }
         lastSig = sig;
      }
      params = p;
      return true;
   }

   /** How much of the macro dryness the season and the day's heat allow (0..1), re-read once a game hour. */
   static float seasonDryness() {
      try {
         int hour = (int)zombie.GameTime.getInstance().getWorldAgeHours();
         if (hour == seasonHour && seasonDry >= 0F) {
            return seasonDry;
         }
         seasonHour = hour;
         ClimateManager cm = ClimateManager.getInstance();
         int season = cm.getSeason() != null ? cm.getSeason().getSeason() : 0;
         float base = switch (season) {
            case 1 -> 0.25F; // spring
            case 2 -> 0.6F; // summer
            case 3 -> 0.9F; // late summer
            case 4 -> 1.0F; // autumn
            case 5 -> 0.7F; // winter
            default -> 0.5F;
         };
         float t = cm.getDayMeanTemperature();
         float d = Math.max(0F, Math.min(1F, base * (1F + 0.025F * (t - 18F))));
         // quantised: a change of a step re-bakes the ground at the lowest priority
         d = Math.round(d * 10F) / 10F;
         if (seasonDry >= 0F && d != seasonDry) {
            GroundBake.rebakeAll("ground macro season", true);
         }
         seasonDry = d;
      } catch (Throwable t) {
         if (seasonDry < 0F) {
            seasonDry = 0.5F;
         }
      }
      return seasonDry;
   }

   /** One seed for the map (every save of it, and every machine, sees the same ground). */
   static float seed() {
      return GroundHash.SEED_TILING & 0xFFFF;
   }

   private static final TextureDraw.GenericDrawer ART = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         GroundTileArt.ensure();
         if (GroundTileArt.artTex == 0 && !GroundTileArt.failed) {
            kicked = false; // (not yet: asked again next frame)
         }
      }
   };

   // ---------------------------------------------------------------------------------------------- GLSL

   @Override
   public String declarations() {
      StringBuilder ml = new StringBuilder("const int GT_MAT_LAYER[16] = int[16](");
      for (int i = 0; i < 16; i++) {
         ml.append(i == 0 ? "" : ", ").append(GroundTileArt.MATERIAL_LAYER[i]);
      }
      ml.append(");\nconst int GT_LAYER_MAT[").append(GroundTileArt.LAYERS.length).append("] = int[").append(GroundTileArt.LAYERS.length).append("](");
      for (int i = 0; i < GroundTileArt.LAYERS.length; i++) {
         ml.append(i == 0 ? "" : ", ").append(GroundTileArt.LAYERS[i].material);
      }
      ml.append(");\n");
      return "#define GT_EDGE_SOFT 0.12\n" + GroundTiling.GLSL + ml + GLSL;
   }

   static final String GLSL = """
      #define GT_SPR_N %d
      uniform vec4 pzGtSpr[GT_SPR_N];       // the natural sprites' page rects (uv x0, y0, x1, y1)
      uniform ivec4 pzGtSprCode[GT_SPR_N / 4]; // layer | kind << 4, -1 unused (four a vector)
      uniform sampler2D pzGtSprTex;         // per row: probe colours (texels 0..2), probe page texels (3: x1 y1 x2 y2, 4: x3 y3)
      uniform ivec4 pzGtBakeI;              // x, y: the exemplar pixel of the bake's logical pixel 0 (mod the period); z texels a
                                            //   logical pixel (1, 2); w the grass layers (bits)
      uniform vec4 pzGtBakeF;               // the art-aligned local world position: u = x - y = fragX * x + y, v = x + y = fragY * z + w

      int gtSpriteRow() {
         for (int i = 0; i < GT_SPR_N; i++) {
            vec4 r = pzGtSpr[i];
            if (texCoord.x >= r.x && texCoord.x < r.z && texCoord.y >= r.y && texCoord.y < r.w) {
               if (pzGtSprCode[i >> 2][i & 3] < 0) return -1;
               vec4 q = texelFetch(pzGtSprTex, ivec2(3, i), 0), q2 = texelFetch(pzGtSprTex, ivec2(4, i), 0);
               vec4 d0 = abs(texelFetch(DIFFUSE, ivec2(q.xy), 0) - texelFetch(pzGtSprTex, ivec2(0, i), 0));
               vec4 d1 = abs(texelFetch(DIFFUSE, ivec2(q.zw), 0) - texelFetch(pzGtSprTex, ivec2(1, i), 0));
               vec4 d2 = abs(texelFetch(DIFFUSE, ivec2(q2.xy), 0) - texelFetch(pzGtSprTex, ivec2(2, i), 0));
               vec4 m = max(d0, max(d1, d2));
               return max(max(m.x, m.y), max(m.z, m.w)) < 0.035 ? i : -1;
            }
         }
         return -1;
      }

      // Transitions between natural materials from the class map instead of the map's edge pieces (groundHeightBlend): the
      // material indicator of the four squares round the pixel interpolated bilinearly between their centres (a staircase
      // of squares becomes a smooth line through its corners), a rank bias (grass grows over dirt, dirt over sand), world
      // noise per material and the art's height (the grass blades win over the dirt at the line); the top two scores blend
      // over pzGtBlend.x. The floor and the map's edge pieces evaluate the same field, so the transition is one line
      // whichever piece draws it; an edge piece paints only its own material's share (with its tone), between two tones of
      // one material the stock fade stays.
      int gtRank(int cls) {
         if ((cls & 128) != 0) return -1; // excluded (farmland, player-built)
         int m = cls & 15;
         return m == 1 ? 5 : (m == 2 || m == 9) ? 3 : m == 8 ? 2 : m == 4 ? 1 : m == 3 ? 0 : -1;
      }
      // out: the winner material m1 and its weight w1, the runner-up m2 (weight 1 - w1); false if the pixel has one material
      bool gtField(vec2 wLocal, ivec2 px, int ownMat, int ownLayer, out int m1, out float w1, out int m2) {
         vec2 g = wLocal - 0.5;
         vec2 gf = floor(g);
         vec2 t = g - gf;
         ivec2 i0 = ivec2(gf) + pzGtWorld;
         int mats[4]; float wts[4]; int n = 0; float wsum = 0.0;
         for (int k = 0; k < 4; k++) {
            ivec2 d = ivec2(k & 1, k >> 1);
            int cls = pzgClassAt(i0 + d);
            if (gtRank(cls) < 0) continue;
            float w = (d.x == 1 ? t.x : 1.0 - t.x) * (d.y == 1 ? t.y : 1.0 - t.y);
            int m = cls & 15;
            int j = 0;
            for (; j < n; j++) if (mats[j] == m) break;
            if (j == n) { mats[n] = m; wts[n] = 0.0; n++; }
            wts[j] += w;
            wsum += w;
         }
         if (n < 2 || wsum <= 1e-4) return false;
         ivec2 bi; vec2 bf; gtSplit(pzGtBlend.z, bi, bf);
         float s1 = -1e9, s2 = -1e9; m1 = ownMat; m2 = ownMat;
         for (int j = 0; j < n; j++) {
            int m = mats[j];
            int la = GT_MAT_LAYER[m]; // (the default tone's height: the floor and the edges must score alike)
            float h = la < 0 ? 0.5 : gtFetch(px, ivec2(0), float(la)).a;
            float nz = gtFbm2(wLocal / pzGtBlend.z, bi, bf, 31u + uint(m)) - 0.5;
            float r = float(gtRank(m));
            float sc = wts[j] / wsum + 0.06 * r + 2.0 * pzGtBlend.y * nz + pzGtBlend.w * (h - 0.5);
            if (sc > s1) { s2 = s1; m2 = m1; s1 = sc; m1 = m; } else if (sc > s2) { s2 = sc; m2 = m; }
         }
         w1 = clamp(0.5 + (s1 - s2) / pzGtBlend.x, 0.5, 1.0);
         return true;
      }

      vec4 pzGtTexel(vec4 t) {
      #if defined(PZG_PIECE) && PZG_PIECE == 2
         return t; // a grass / erosion overlay or dug soil on the natural floor: stock
      #endif
         int row = gtSpriteRow();
         if (row < 0) return t;
         int code = pzGtSprCode[row >> 2][row & 3];
         int layer = code & 15, kind = code >> 4;
         ivec2 px = gtWrap(ivec2(gl_FragCoord.xy) / pzGtBakeI.z + pzGtBakeI.xy);
         vec2 uv = vec2(gl_FragCoord.x * pzGtBakeF.x + pzGtBakeF.y, gl_FragCoord.y * pzGtBakeF.z + pzGtBakeF.w);
         vec2 wLocal = vec2(uv.y + uv.x, uv.y - uv.x) * 0.5;
         int mode = pzGtPeriod.z;
         if (mode == 8) {
            // dev alignment check: the exemplar's slot diamonds (red, from the exact pixel) and the world squares (blue, from
            // the art-aligned position) over the stock texel; both must trace the sprite's own diamond edges
            vec2 q = vec2(px) + 0.5 - vec2(float(pzGtPeriod.x / 4) - 1.0, 0.0); // slot (0, 0)'s top vertex at x = 32ts - 1
            float k = float(pzGtPeriod.x / 4), kh = float(pzGtPeriod.y / 4);
            vec2 e = vec2(q.y / kh + q.x / k, q.y / kh - q.x / k) * 0.5;
            vec2 fe = abs(fract(e) - 0.5), fw = abs(fract(wLocal) - 0.5);
            float red = max(fe.x, fe.y) > 0.49 ? 1.0 : 0.0, blue = max(fw.x, fw.y) > 0.49 ? 1.0 : 0.0;
            return vec4(mix(t.rgb, vec3(red, 0.0, blue), max(red, blue) * 0.8), t.a);
         }
         // the game's pages hold premultiplied colour and the bake blends (ONE, ONE_MINUS_SRC_ALPHA): the stock texel is
         // un-premultiplied here, the exemplar holds straight colour, and the result goes back premultiplied by its alpha
         vec4 c = mode > 0 ? gtSample(px, wLocal, float(layer), mode) : (kind == 0 || pzGtBlend.x <= 0.0 ? vec4(t.rgb / max(t.a, 1e-4), t.a) : gtFetch(px, ivec2(0), float(layer)));
         float grassy = ((pzGtBakeI.w >> layer) & 1) != 0 ? 1.0 : 0.0;
         int own = pzgClass & 15;
         if (kind == 0) {
            int m1, m2; float w1;
            if (pzGtBlend.x > 0.0 && GT_LAYER_MAT[layer] == own && gtRank(pzgClass) >= 0 && gtField(wLocal, px, own, layer, m1, w1, m2) && !(m1 == own && w1 >= 1.0)) {
               // the other material's art (its default tone; an edge piece of it, if the map has one here, repaints its tone)
               int mo = m1 == own ? m2 : m1;
               int lo = GT_MAT_LAYER[mo];
               if (lo >= 0) {
                  vec4 co = mode > 0 ? gtSample(px, wLocal, float(lo), mode) : gtFetch(px, ivec2(0), float(lo));
                  c.rgb = mix(co.rgb, c.rgb, m1 == own ? w1 : 1.0 - w1);
                  grassy = max(grassy, ((pzGtBakeI.w >> lo) & 1) != 0 ? (m1 == own ? 1.0 - w1 : w1) : 0.0);
               }
            }
            vec3 rgb = gtMacro(c.rgb, wLocal, grassy);
            return vec4(rgb * t.a, t.a);
         }
         vec3 rgb = gtMacro(c.rgb, wLocal, grassy);
         // an edge piece of material B over the square's base A
         float a = t.a;
         int mb = GT_LAYER_MAT[layer];
         if (pzGtBlend.x > 0.0 && mb != own && gtRank(pzgClass) >= 0) {
            int m1, m2; float w1;
            a = 0.0;
            if (gtField(wLocal, px, own, GT_MAT_LAYER[own], m1, w1, m2)) {
               a = m1 == mb ? w1 : m2 == mb ? 1.0 - w1 : 0.0;
            }
         }
         return vec4(rgb * a, a);
      }
      """.formatted(GroundTileArt.SPR_MAX);

   /** The overlays on a natural floor get their own program, which keeps them stock without the table walk. */
   @Override
   public boolean wantsAttachedFlag() {
      return true;
   }

   @Override
   public String texel() {
      return "pzGtTexel";
   }

   @Override
   public String[][] samplers() {
      return new String[][] {{"pzGtSprTex", "" + UNIT}, {"pzGtArt", "" + (UNIT + 1)}, {"pzGtGauss", "" + (UNIT + 2)}, {"pzGtLut", "" + (UNIT + 3)}};
   }

   // ---------------------------------------------------------------------------------------------- render thread

   private static final class Locs {
      int period, hex, blend, macro, dry, world, oi, of, bakeI, bakeF, spr, sprCode;
      int tableVersion = -1;
   }

   private static final HashMap<Integer, Locs> LOCS = new HashMap<>();
   private static int tableVersion;
   private static final int[] OI = new int[4];
   private static final float[] OF = new float[4];
   private static java.nio.FloatBuffer rectBuf;
   private static java.nio.IntBuffer codeBuf;

   private static Locs locs(int program) {
      Locs l = LOCS.get(program);
      if (l == null) {
         l = new Locs();
         l.period = GL20.glGetUniformLocation(program, "pzGtPeriod");
         l.hex = GL20.glGetUniformLocation(program, "pzGtHex");
         l.blend = GL20.glGetUniformLocation(program, "pzGtBlend");
         l.macro = GL20.glGetUniformLocation(program, "pzGtMacro");
         l.dry = GL20.glGetUniformLocation(program, "pzGtDryTint");
         l.world = GL20.glGetUniformLocation(program, "pzGtWorld");
         l.oi = GL20.glGetUniformLocation(program, "pzGtOI");
         l.of = GL20.glGetUniformLocation(program, "pzGtOF");
         l.bakeI = GL20.glGetUniformLocation(program, "pzGtBakeI");
         l.bakeF = GL20.glGetUniformLocation(program, "pzGtBakeF");
         l.spr = GL20.glGetUniformLocation(program, "pzGtSpr");
         l.sprCode = GL20.glGetUniformLocation(program, "pzGtSprCode");
         LOCS.put(program, l);
      }
      return l;
   }

   @Override
   public void bakeStart(int program, FBORenderChunk rc) {
      float[] p = params;
      if (p.length == 0 || GroundTileArt.artTex == 0 || rc.chunk == null) {
         return;
      }
      Locs l = locs(program);
      if (l.tableVersion != tableVersion || l.tableVersion < 0) {
         if (rectBuf == null) {
            rectBuf = org.lwjgl.BufferUtils.createFloatBuffer(GroundTileArt.SPR_MAX * 4);
            codeBuf = org.lwjgl.BufferUtils.createIntBuffer(GroundTileArt.SPR_MAX);
         }
         rectBuf.clear();
         rectBuf.put(GroundTileArt.sprRects).flip();
         codeBuf.clear();
         codeBuf.put(GroundTileArt.sprCodes).flip();
         if (l.spr >= 0) {
            GL20.glUniform4fv(l.spr, rectBuf);
         }
         if (l.sprCode >= 0) {
            GL20.glUniform4iv(l.sprCode, codeBuf);
         }
         l.tableVersion = tableVersion;
      }
      int mode = (int)p[0];
      float cell = p[1];
      GL20.glUniform4i(l.period, GroundTileArt.periodW, GroundTileArt.periodH, mode, (int)p[16]);
      GL20.glUniform4f(l.hex, cell, p[2], p[3], p[4]);
      GL20.glUniform4f(l.blend, p[5], p[6], p[7], p[8]);
      GL20.glUniform4f(l.macro, p[9], p[10], p[11], p[12]);
      GL20.glUniform3f(l.dry, p[13], p[14], p[15]);
      int ox = rc.chunk.wx * 8, oy = rc.chunk.wy * 8;
      GL30.glUniform2i(l.world, ox, oy);
      GroundTiling.origin(ox, oy, cell, OI, OF);
      GL20.glUniform4i(l.oi, OI[0], OI[1], OI[2], OI[3]);
      GL20.glUniform4f(l.of, OF[0], OF[1], OF[2], OF[3]);
      // GroundBake.mapping's terms in integers: logical x = fragX / s + (ox - goX), y = fragY / s - yoff
      int ts = Core.tileScale;
      int s = rc.highRes ? 2 : 1;
      int dx = (rc.highRes ? rc.w / 4 : 0) - rc.w / 2;
      int yoff = (rc.getTopLevel() - rc.getMinLevel() + 1) * FBORenderChunk.PIXELS_PER_LEVEL + rc.getMinLevel() * FBORenderChunk.PIXELS_PER_LEVEL
         + zombie.iso.fboRenderChunk.FBORenderLevels.extraHeightForJumboTrees(rc.getMinLevel(), rc.getTopLevel());
      int pw = GroundTileArt.periodW, ph = GroundTileArt.periodH;
      // the art of square (i, j) starts at logical x = 32ts (i - j) - 32ts - 1 relative to its top vertex: the sprite's diamond
      // centre sits one pixel left of the vertex the projection gives (the frame is 128 wide, the diamond 126)
      int offX = Math.floorMod(dx + 256 * ts * (rc.chunk.wx - rc.chunk.wy) + 32 * ts, pw);
      int offY = Math.floorMod(-yoff + 128 * ts * (rc.chunk.wx + rc.chunk.wy), ph);
      GL20.glUniform4i(l.bakeI, offX, offY, s, GroundTileArt.GRASSY);
      float k = 32F * ts;
      GL20.glUniform4f(l.bakeF, 1F / (s * k), (dx + 1F) / k, 1F / (s * 16F * ts), -yoff / (16F * ts));
      GroundTileArt.bind(UNIT);
   }

   /** dev: the uniforms as the next bake will set them. */
   static String describe() {
      float[] p = params;
      return p.length == 0 ? "ground tiling: off" : "ground tiling: mode " + MODES[(int)p[0]] + " cell " + p[1] + " blend " + p[5] + " macro " + p[9] + "/" + p[10]
         + " season dry " + seasonDry + "; " + GroundTileArt.stats();
   }
}
