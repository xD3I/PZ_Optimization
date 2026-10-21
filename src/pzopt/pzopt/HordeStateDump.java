package pzopt;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.characters.IsoZombie;
import zombie.core.DefaultShader;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.IOpenGLState;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * Dev rig {@code devHordeStateDump=MS} (2026-10-14, the hordeBatch=exact flicker question): what a run of batched zombie
 * draws leaves behind compared with what the stock per-character draw leaves behind. At most once every {@code MS}
 * milliseconds per path, the render thread reads ~200 pieces of GL state (enables, blend / depth / stencil / colour
 * masks, polygon offset / mode, program, vertex array, buffer bindings, textures and samplers of units 0-3, the bound
 * vertex array's 16 attributes, viewport, scissor, depth range, pixel store, current colour) plus the Java caches the
 * next draws trust (the thirteen GLStateRenderThread values and dirty flags, ShaderHelper's bound program,
 * DefaultShader.isActive, Texture.lastTextureID, the sprite ring buffer's style / texture / restore flags) right before
 * and right after one batch flush (pzopt.HordeBatch, by its mode) or one stock zombie draw in the moving pass
 * (TextureDraw's DrawModel, the drawer's render). The console line "horde state:" lists, per path, every key the
 * draw changed (in how many samples, and up to three before -> after examples). A key that a batch flush changes and
 * the stock draw never does (or the other way round), or that ends on another value, is a state the batch leaves
 * different for whatever draws next. glGet waits for the driver thread: a dev rig, never for timing.
 */
public final class HordeStateDump {
   public static final boolean ON = Config.DEV_HORDE_STATE_DUMP > 0;
   private static final long INTERVAL_NS = Config.DEV_HORDE_STATE_DUMP * 1_000_000L;

   private HordeStateDump() {
   }

   private static final String[] PATHS = {"stock", "exact", "defer"};
   private static final long[] samples = new long[3];
   private static final long[] lastNs = new long[3];
   @SuppressWarnings("unchecked")
   private static final LinkedHashMap<String, long[]>[] changed = new LinkedHashMap[]{new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()};
   @SuppressWarnings("unchecked")
   private static final LinkedHashMap<String, LinkedHashSet<String>>[] examples = new LinkedHashMap[]{new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>()};
   private static String[] keys;
   private static String[] before;
   private static int beforePath = -1;
   private static long lastLogNs;

   // ---- hooks ----

   /** Render thread, HordeBatch.flush before anything is issued; mode 1 exact, 2 defer. */
   public static void batchBefore(int mode) {
      begin(mode == HordeBatch.DEFER ? 2 : 1);
   }

   /** Render thread, HordeBatch.flush after its tail. */
   public static void batchAfter() {
      end();
   }

   /** Render thread, TextureDraw's stock DrawModel, before the drawer renders. */
   public static void stockBefore(TextureDraw.GenericDrawer drawer) {
      if (drawer instanceof ModelSlotRenderData slot && slot.character instanceof IsoZombie && HordeBatch.inPassNow()) {
         begin(0);
      }
   }

   /** Render thread, TextureDraw's stock DrawModel, after the drawer rendered. */
   public static void stockAfter() {
      end();
   }

   private static void begin(int path) {
      beforePath = -1;
      long now = System.nanoTime();
      if (now - lastNs[path] < INTERVAL_NS) {
         return;
      }
      lastNs[path] = now;
      try {
         before = snap();
         beforePath = path;
      } catch (Throwable t) {
         Log.warn("horde state: snapshot failed (" + t + ")");
      }
   }

   private static void end() {
      int path = beforePath;
      if (path < 0) {
         return;
      }
      beforePath = -1;
      String[] after;
      try {
         after = snap();
      } catch (Throwable t) {
         Log.warn("horde state: snapshot failed (" + t + ")");
         return;
      }
      samples[path]++;
      for (int i = 0; i < keys.length; i++) {
         if (!before[i].equals(after[i])) {
            changed[path].computeIfAbsent(keys[i], k -> new long[1])[0]++;
            LinkedHashSet<String> ex = examples[path].computeIfAbsent(keys[i], k -> new LinkedHashSet<>());
            if (ex.size() < 3) {
               ex.add(before[i] + "->" + after[i]);
            }
         }
      }
      long now = System.nanoTime();
      if (now - lastLogNs > 5_000_000_000L) {
         lastLogNs = now;
         report();
      }
   }

   private static void report() {
      StringBuilder sb = new StringBuilder("horde state:");
      for (int p = 0; p < 3; p++) {
         sb.append(" [").append(PATHS[p]).append(" samples=").append(samples[p]).append(':');
         for (Map.Entry<String, long[]> e : changed[p].entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]).append(examples[p].get(e.getKey()));
         }
         sb.append(']');
      }
      // keys one path changes and another never does
      LinkedHashSet<String> all = new LinkedHashSet<>();
      for (int p = 0; p < 3; p++) {
         all.addAll(changed[p].keySet());
      }
      sb.append(" | differ:");
      for (String k : all) {
         boolean s = changed[0].containsKey(k), e = changed[1].containsKey(k), d = changed[2].containsKey(k);
         if (samples[0] > 0 && (samples[1] > 0 && s != e || samples[2] > 0 && s != d)) {
            sb.append(' ').append(k).append("(stock=").append(s).append(" exact=").append(e).append(" defer=").append(d).append(')');
         }
      }
      Log.info(sb.toString());
   }

   // ---- the snapshot ----
   private static final IntBuffer IB = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
   private static final FloatBuffer FB = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
   private static final ByteBuffer BB = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

   private static final int[][] ENABLES = {
      {0x0BE2, 0}, {0x0B71, 0}, {0x0BC0, 0}, {0x0B44, 0}, {0x0C11, 0}, {0x0B90, 0}, {0x8037, 0}, {0x2A02, 0}, {0x2A01, 0}, {0x864F, 0},
      {0x8DB9, 0}, {0x809D, 0}, {0x809E, 0}, {0x809F, 0}, {0x0BF2, 0}, {0x8C89, 0}, {0x8F9D, 0}, {0x8D69, 0}, {0x8642, 0}, {0x3000, 0},
      {0x3001, 0}, {0x0B50, 0}, {0x0B60, 0}, {0x0B57, 0}, {0x0BA1, 0}, {0x0B20, 0}, {0x0B41, 0}, {0x0BD0, 0}, {0x0B10, 0}, {0x884F, 0},
      {0x8513, 0}, {0x806F, 0}, {0x0DE0, 0}, {0x0B42, 0}, {0x0BF1, 0}
   };
   private static final String[] ENABLE_NAMES = {
      "BLEND", "DEPTH_TEST", "ALPHA_TEST", "CULL_FACE", "SCISSOR_TEST", "STENCIL_TEST", "POLYGON_OFFSET_FILL", "POLYGON_OFFSET_LINE", "POLYGON_OFFSET_POINT", "DEPTH_CLAMP",
      "FRAMEBUFFER_SRGB", "MULTISAMPLE", "SAMPLE_ALPHA_TO_COVERAGE", "SAMPLE_ALPHA_TO_ONE", "COLOR_LOGIC_OP", "RASTERIZER_DISCARD", "PRIMITIVE_RESTART", "PRIMITIVE_RESTART_FIXED", "PROGRAM_POINT_SIZE", "CLIP_DISTANCE0",
      "CLIP_DISTANCE1", "LIGHTING", "FOG", "COLOR_MATERIAL", "NORMALIZE", "LINE_SMOOTH", "POLYGON_SMOOTH", "DITHER", "POINT_SMOOTH", "TEXTURE_CUBE_MAP_SEAMLESS",
      "TEXTURE_CUBE_MAP", "TEXTURE_3D", "TEXTURE_1D", "POLYGON_STIPPLE", "INDEX_LOGIC_OP"
   };
   private static final int[] INTS = {
      0x8B8D, 0x85B5, 0x8894, 0x8895, 0x84E0, 0x90D3, 0x8A28, 0x8F43, 0x8CA6, 0x8CAA, 0x8CA7, 0x88EF, 0x88ED, 0x0B74, 0x0B72, 0x0BC1,
      0x0B45, 0x0B46, 0x0B92, 0x0B97, 0x0B93, 0x0B98, 0x0B94, 0x0B95, 0x0B96, 0x8800, 0x8CA3, 0x8CA4, 0x8CA5, 0x8801, 0x8802, 0x8803,
      0x80C9, 0x80C8, 0x80CB, 0x80CA, 0x8009, 0x883D, 0x0CF5, 0x0D05, 0x0CF2, 0x0D02, 0x0BA0, 0x8825, 0x0C01, 0x0C02, 0x0B54, 0x0BF0,
      0x8E72, 0x8F36, 0x8F37, 0x8C8F, 0x9193, 0x84E1, 0x8C1D
   };
   private static final String[] INT_NAMES = {
      "CURRENT_PROGRAM", "VERTEX_ARRAY_BINDING", "ARRAY_BUFFER_BINDING", "ELEMENT_ARRAY_BUFFER_BINDING", "ACTIVE_TEXTURE", "SHADER_STORAGE_BUFFER_BINDING", "UNIFORM_BUFFER_BINDING", "DRAW_INDIRECT_BUFFER_BINDING", "DRAW_FRAMEBUFFER_BINDING", "READ_FRAMEBUFFER_BINDING", "RENDERBUFFER_BINDING", "PIXEL_UNPACK_BUFFER_BINDING", "PIXEL_PACK_BUFFER_BINDING", "DEPTH_FUNC", "DEPTH_WRITEMASK", "ALPHA_TEST_FUNC",
      "CULL_FACE_MODE", "FRONT_FACE", "STENCIL_FUNC", "STENCIL_REF", "STENCIL_VALUE_MASK", "STENCIL_WRITEMASK", "STENCIL_FAIL", "STENCIL_PASS_DEPTH_FAIL", "STENCIL_PASS_DEPTH_PASS", "STENCIL_BACK_FUNC", "STENCIL_BACK_REF", "STENCIL_BACK_VALUE_MASK", "STENCIL_BACK_WRITEMASK", "STENCIL_BACK_FAIL", "STENCIL_BACK_PASS_DEPTH_FAIL", "STENCIL_BACK_PASS_DEPTH_PASS",
      "BLEND_SRC_RGB", "BLEND_DST_RGB", "BLEND_SRC_ALPHA", "BLEND_DST_ALPHA", "BLEND_EQUATION_RGB", "BLEND_EQUATION_ALPHA", "UNPACK_ALIGNMENT", "PACK_ALIGNMENT", "UNPACK_ROW_LENGTH", "PACK_ROW_LENGTH", "MATRIX_MODE", "DRAW_BUFFER0", "DRAW_BUFFER", "READ_BUFFER", "SHADE_MODEL", "LOGIC_OP_MODE",
      "PATCH_VERTICES", "COPY_READ_BUFFER_BINDING", "COPY_WRITE_BUFFER_BINDING", "TRANSFORM_FEEDBACK_BUFFER_BINDING", "QUERY_BUFFER_BINDING", "CLIENT_ACTIVE_TEXTURE", "TEXTURE_BINDING_2D_ARRAY"
   };
   private static final int[][] VECS = {{0x0BA2, 4, 0}, {0x0C10, 4, 0}, {0x0B70, 2, 1}, {0x0C23, 4, 2}, {0x8005, 4, 1}, {0x0B40, 2, 0}, {0x0B00, 4, 1}, {0x8038, 1, 1}, {0x2A00, 1, 1}, {0x0BC2, 1, 1}, {0x0B21, 1, 1}, {0x0B11, 1, 1}, {0x0C22, 4, 1}, {0x0B73, 1, 1}};
   private static final String[] VEC_NAMES = {"VIEWPORT", "SCISSOR_BOX", "DEPTH_RANGE", "COLOR_WRITEMASK", "BLEND_COLOR", "POLYGON_MODE", "CURRENT_COLOR", "POLYGON_OFFSET_FACTOR", "POLYGON_OFFSET_UNITS", "ALPHA_TEST_REF", "LINE_WIDTH", "POINT_SIZE", "COLOR_CLEAR_VALUE", "DEPTH_CLEAR_VALUE"};
   private static final int[] ATTR = {0x8622, 0x889F, 0x88FE, 0x8623, 0x8624, 0x8625, 0x886A, 0x88FD};
   private static final String[] ATTR_NAMES = {"enabled", "buffer", "divisor", "size", "stride", "type", "normalized", "integer"};

   private static Field fDirty;
   private static Field fShaderBound;
   private static Field[] fRing;
   private static final IOpenGLState<?>[] CACHED = {
      GLStateRenderThread.AlphaFunc, GLStateRenderThread.AlphaTest, GLStateRenderThread.Blend, GLStateRenderThread.BlendFuncSeparate,
      GLStateRenderThread.ColorMask, GLStateRenderThread.DepthFunc, GLStateRenderThread.DepthMask, GLStateRenderThread.DepthTest,
      GLStateRenderThread.ScissorTest, GLStateRenderThread.StencilFunc, GLStateRenderThread.StencilMask, GLStateRenderThread.StencilOp,
      GLStateRenderThread.StencilTest
   };
   private static final String[] CACHED_NAMES = {"AlphaFunc", "AlphaTest", "Blend", "BlendFuncSeparate", "ColorMask", "DepthFunc", "DepthMask", "DepthTest", "ScissorTest", "StencilFunc", "StencilMask", "StencilOp", "StencilTest"};
   private static final String[] RING_FIELDS = {"lastRenderedStyle", "lastRenderedTexture0", "lastRenderedTexture1", "lastRenderedTexture2", "restoreVbos", "restoreBoundTextures", "sequence"};

   private static String fields(Object v) {
      if (v == null) {
         return "null";
      }
      StringBuilder sb = new StringBuilder();
      for (Class<?> c = v.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
         for (Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
               continue;
            }
            try {
               f.setAccessible(true);
               sb.append(f.get(v)).append(',');
            } catch (Throwable t) {
               sb.append("?,");
            }
         }
      }
      return sb.length() == 0 ? v.toString() : sb.toString();
   }

   private static String vec(int pname, int n, int kind) {
      StringBuilder sb = new StringBuilder();
      if (kind == 0) {
         IB.clear();
         GL11.glGetIntegerv(pname, IB);
         for (int i = 0; i < n; i++) {
            sb.append(IB.get(i)).append(i + 1 < n ? "," : "");
         }
      } else if (kind == 1) {
         FB.clear();
         GL11.glGetFloatv(pname, FB);
         for (int i = 0; i < n; i++) {
            sb.append(FB.get(i)).append(i + 1 < n ? "," : "");
         }
      } else {
         BB.clear();
         GL11.glGetBooleanv(pname, BB);
         for (int i = 0; i < n; i++) {
            sb.append(BB.get(i)).append(i + 1 < n ? "," : "");
         }
      }
      return sb.toString();
   }

   private static String[] snap() throws Exception {
      ArrayList<String> k = keys == null ? new ArrayList<>() : null;
      ArrayList<String> v = new ArrayList<>(320);
      for (int i = 0; i < ENABLES.length; i++) {
         if (k != null) {
            k.add("en." + ENABLE_NAMES[i]);
         }
         v.add(String.valueOf(GL11.glIsEnabled(ENABLES[i][0])));
      }
      for (int i = 0; i < INTS.length; i++) {
         if (k != null) {
            k.add(INT_NAMES[i]);
         }
         v.add(String.valueOf(GL11.glGetInteger(INTS[i])));
      }
      for (int i = 0; i < VECS.length; i++) {
         if (k != null) {
            k.add(VEC_NAMES[i]);
         }
         v.add(vec(VECS[i][0], VECS[i][1], VECS[i][2]));
      }
      for (int idx : new int[]{12, 13}) {
         if (k != null) {
            k.add("SSBO[" + idx + "]");
         }
         v.add(String.valueOf(GL30.glGetIntegeri(0x90D3, idx)));
      }
      int active = GL11.glGetInteger(0x84E0);
      for (int u = 0; u < 4; u++) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
         if (k != null) {
            k.add("unit" + u + ".TEXTURE_BINDING_2D");
            k.add("unit" + u + ".SAMPLER_BINDING");
            k.add("unit" + u + ".en.TEXTURE_2D");
            k.add("unit" + u + ".TEXTURE_BINDING_3D");
         }
         v.add(String.valueOf(GL11.glGetInteger(0x8069)));
         v.add(String.valueOf(GL11.glGetInteger(0x8919)));
         v.add(String.valueOf(GL11.glIsEnabled(0x0DE1)));
         v.add(String.valueOf(GL11.glGetInteger(0x806A)));
      }
      GL13.glActiveTexture(active);
      for (int a = 0; a < 16; a++) {
         for (int j = 0; j < ATTR.length; j++) {
            if (k != null) {
               k.add("attr" + a + "." + ATTR_NAMES[j]);
            }
            IB.clear();
            GL20.glGetVertexAttribiv(a, ATTR[j], IB);
            v.add(String.valueOf(IB.get(0)));
         }
      }
      // the Java caches the next draws trust
      if (fDirty == null) {
         fDirty = IOpenGLState.class.getDeclaredField("dirty");
         fDirty.setAccessible(true);
         fShaderBound = zombie.core.ShaderHelper.class.getDeclaredField("currentlyBound");
         fShaderBound.setAccessible(true);
         fRing = new Field[RING_FIELDS.length];
         for (int i = 0; i < RING_FIELDS.length; i++) {
            try {
               fRing[i] = SpriteRenderer.RingBuffer.class.getDeclaredField(RING_FIELDS[i]);
               fRing[i].setAccessible(true);
            } catch (NoSuchFieldException e) {
               fRing[i] = null;
            }
         }
      }
      for (int i = 0; i < CACHED.length; i++) {
         if (k != null) {
            k.add("cache." + CACHED_NAMES[i]);
            k.add("cache." + CACHED_NAMES[i] + ".dirty");
         }
         v.add(fields(CACHED[i].pzoptCurrent()));
         v.add(String.valueOf(fDirty.get(CACHED[i])));
      }
      if (k != null) {
         k.add("java.ShaderHelper.currentlyBound");
         k.add("java.DefaultShader.isActive");
         k.add("java.Texture.lastTextureID");
      }
      v.add(String.valueOf(fShaderBound.get(null)));
      v.add(String.valueOf(DefaultShader.isActive));
      v.add(String.valueOf(Texture.lastTextureID));
      for (int i = 0; i < RING_FIELDS.length; i++) {
         if (k != null) {
            k.add("ring." + RING_FIELDS[i]);
         }
         Object o = fRing[i] == null ? "n/a" : fRing[i].get(SpriteRenderer.ringBuffer);
         v.add(o instanceof Texture t ? "tex" + t.getID() : String.valueOf(o));
      }
      if (k != null) {
         keys = k.toArray(new String[0]);
      }
      return v.toArray(new String[0]);
   }
}
