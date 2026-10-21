package pzopt;

import java.util.HashMap;
import org.lwjgl.opengl.GL20;
import zombie.IndieGL;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;

/**
 * tileVertexDepth (2026-10-09, the 300 fps loop): a tile drawn with the tile-depth programs (tileWithDepth /
 * opaqueWithDepth) gets its two depth values (front, far: stock's zDepth = zDepthBlendZ and zDepthBlendToZ) in the
 * vertex stream instead of a shader start with four uniforms. Stock starts the program once per tile, so every tile was
 * its own draw call plus a program start and ~7 uniform uploads; in a native Wayland window (HDR) NVIDIA's driver runs
 * that work on our render thread (~1,100 starts a frame on the 120 km/h Rosewood drive, bakes included). Here
 * consecutive tiles of one program share a start, and tiles on the same texture pages fall into one run (one draw).
 *
 * The pair rides in the third texture's coordinate slot (vertex attribute 4), which tile draws leave empty
 * (SpriteRenderer.RingBuffer.add writes it when {@link TextureDraw#pzoptVD}); the patched programs read it while their
 * {@code pzoptVD} uniform is 1, which only a start of this class sets ({@link #onStart}); every stock start of those
 * programs puts it back to 0, so the game's other users of the programs (uniforms) are unchanged.
 * A swaying plant keeps the stock path (foliage sway gives each plant's start its own uniforms).
 */
public final class TileBatch {
   private TileBatch() {
   }

   // Not on macOS's GL 2.1 context: the patched shaders' `layout(location = 4)` input does not exist in GLSL 1.20. On the
   // 4.1 core context (macGlCore, CoreGlsl / CoreGl) it is on since 2026-10-09: picture pairs no different from run-to-run
   // noise, 0 failed programs, drives +6 % (docs/findings-frame-spikes-2026-10-09.md section 10).
   public static final boolean ON = Config.TILE_VERTEX_DEPTH && Overrides.enabled() && !CoreGl.legacyMac();

   // ------------------------------------------------------------------------------------------------ shaders

   static volatile boolean vertPatched, fragPatched;

   /** ShaderUnit hook (innermost): the tile-depth programs read the depth pair from attribute 4 when pzoptVD is 1. */
   public static String patchShader(String fileName, String code) {
      if (!ON || fileName == null || code == null) {
         return code;
      }
      String fn = fileName.replace('\\', '/');
      int s = fn.lastIndexOf('/');
      String base = s >= 0 ? fn.substring(s + 1) : fn;
      if (base.equals("tileWithDepth.vert") || base.equals("opaqueWithDepth.vert")) {
         String decl = "layout (location = 3) in vec2 vUV2;";
         String pos = "gl_Position = ModelViewProjection * vec4(vPos.x, vPos.y, zDepth, 1);";
         if (!code.contains(decl) || !code.contains(pos) || !code.contains("uniform float zDepthBlendToZ")) {
            Log.warn("tile vertex depth: " + base + " not as expected; tiles keep their uniforms");
            return code;
         }
         code = code.replace(decl, decl + "\nlayout (location = 4) in vec2 pzoptVDIn;\nuniform float pzoptVD = 0;\nout vec2 pzoptBlend;");
         code = code.replace(pos, "float pzZ = pzoptVD > 0.5 ? pzoptVDIn.x : zDepth;\n"
            + "    pzoptBlend = pzoptVD > 0.5 ? pzoptVDIn : vec2(zDepthBlendZ, zDepthBlendToZ);\n"
            + "    gl_Position = ModelViewProjection * vec4(vPos.x, vPos.y, pzZ, 1);");
         vertPatched = true;
         return code;
      }
      if (base.equals("tileWithDepth.frag") || base.equals("opaqueWithDepth.frag")) {
         int m = code.indexOf("void main");
         if (m < 0 || !code.contains("zDepthBlendToZ")) {
            Log.warn("tile vertex depth: " + base + " not as expected; tiles keep their uniforms");
            return code;
         }
         // after the uniform declarations: the blend depths come from the vertex (the pair, or the uniforms copied)
         code = code.substring(0, m) + "varying vec2 pzoptBlend;\n#define zDepthBlendZ pzoptBlend.x\n#define zDepthBlendToZ pzoptBlend.y\n" + code.substring(m);
         fragPatched = true;
         return code;
      }
      return code;
   }

   // ------------------------------------------------------------------------------------------------ game / recording threads

   private static final class Pending {
      GenericSpriteRenderState state; // where the merged start was recorded (null = none open)
      int program;
      int next; // the slot the next draw under it would take
      int swayKey;
      float front, far;
      int lastDraw = -1; // slot of the last draw stamped
      boolean bound; // nothing that may change the program has been recorded since the merged start (skipping / folding allowed)
      int maskAtDraw = -1, funcAtDraw = -1; // the depth mask / depth function in effect for it (game thread; -1 unknown)
   }

   private static final ThreadLocal<Pending> PENDING = ThreadLocal.withInitial(Pending::new);
   private static final int[] programs = new int[4]; // the programs found patched (pzoptVD uniform present)
   private static final boolean[] usable = new boolean[4];
   private static int nPrograms;
   public static long starts, merged, stamped, stockStarts;
   private static int samples;

   private static synchronized boolean usable(int program) {
      for (int i = 0; i < nPrograms; i++) {
         if (programs[i] == program) {
            return usable[i];
         }
      }
      Shader sh = Shader.ShaderMap.get(program);
      ShaderProgram p = sh == null ? null : sh.getProgram();
      boolean ok = p != null && p.getUniform("pzoptVD", GL20.GL_FLOAT, false) != null;
      if (nPrograms < programs.length) {
         programs[nPrograms] = program;
         usable[nPrograms++] = ok;
      }
      return ok;
   }

   /**
    * IsoSprite.startTileDepthShader / startTileDepthShader2: true = taken (the start, if one is needed, is recorded and
    * the next draws carry the pair); false = the caller records the stock start with its uniforms.
    */
   public static boolean start(int program, float front, float far) {
      if (!ON || !vertPatched || !fragPatched) {
         return false;
      }
      int key = Sway.startKey();
      if (key == 2 || !usable(program)) {
         stockStarts++;
         return false;
      }
      GenericSpriteRenderState st = SpriteRenderer.instance.states.getPopulatingActiveState();
      Pending p = PENDING.get();
      int n = st.numSprites;
      // bakeRecordParallel: a bake unit's recorder records every tile start; its splice merges and folds them as this
      // method would have against the frame's stream (spliceStart)
      boolean raw = DrawRecorder.recording && (BakeRecord.onBakeRecorder() || DrawRecorder.onBatchSimRecorder()); // (tileRecordBatchSim: a translucent unit too)
      boolean reuse = !raw && p.state == st && p.bound && p.program == program && p.swayKey == key && p.lastDraw >= 0 && p.lastDraw < n
         && st.sprite[p.lastDraw].type == TextureDraw.Type.glDraw && st.sprite[p.lastDraw].pzoptVD && onlyState(st, p.lastDraw + 1, n);
      if (!reuse && Config.TILE_STATE_FOLD && p.state == st && p.bound && p.program == program && p.swayKey == key && p.lastDraw >= 0
         && p.lastDraw < n && !DrawRecorder.onRecordingThread() && fold(st, p, n)) {
         reuse = true;
         folded++;
         n = st.numSprites;
      }
      if (reuse) {
         merged++;
      } else {
         if (Config.INSTRUMENT) {
            TextureDraw last = n > 0 ? st.sprite[n - 1] : null;
            why(p.state != st ? "state" : !p.bound ? "unbound" : p.program != program ? "program" : p.swayKey != key ? "sway" : last == null ? "empty"
               : last.type != TextureDraw.Type.glDraw ? OP_KEYS[last.type.ordinal()] : !last.pzoptVD ? "draw-unstamped" : "gap");
         }
         IndieGL.StartShader(program);
         TextureDraw op = st.sprite[st.numSprites - 1];
         op.pzoptVDStart = true;
         p.state = st;
         p.program = program;
         p.swayKey = key;
         p.lastDraw = -1;
         starts++;
      }
      p.bound = true;
      p.front = front;
      p.far = far;
      p.next = st.numSprites;
      return true;
   }

   /**
    * TextureDraw.Create: every draw recorded after a merged start in this frame carries the pair. Stamping a draw that
    * another program draws is harmless (every start of a tile program sets its pzoptVD, other programs never read
    * attribute 4); an unstamped draw under a merged start would draw at depth 0, so the stamp never asks more.
    */
   public static void stamp(TextureDraw texd) {
      if (!ON) {
         return;
      }
      texd.pzoptVD = false;
      Pending p = PENDING.get();
      GenericSpriteRenderState st = p.state;
      if (st == null) {
         return;
      }
      int idx = st.numSprites;
      if (idx >= st.sprite.length || st.sprite[idx] != texd) {
         return; // not this state's next slot (another path)
      }
      if (idx < p.next) {
         p.state = null; // the state was reset (next frame)
         return;
      }
      if (p.bound && !onlyState(st, p.next, idx)) {
         p.bound = false; // something in between may have bound another program: stamp on, but never skip a start on it
      }
      texd.pzoptVD = true;
      texd.pzoptVDFront = p.front;
      texd.pzoptVDFar = p.far;
      p.next = idx + 1;
      if (p.bound) {
         p.lastDraw = idx;
         if (Config.TILE_STATE_FOLD && !DrawRecorder.onRecordingThread()) {
            p.maskAtDraw = maskNow();
            p.funcAtDraw = funcNow();
         }
      }
      stamped++;
   }

   private static boolean onlyState(GenericSpriteRenderState st, int from, int to) {
      for (int i = from; i < to; i++) {
         if (!keepsProgram(st.sprite[i].type)) {
            return false;
         }
      }
      return true;
   }

   /** State entries that only set fixed-function state: the program and its uniforms stay as the start left them. */
   private static boolean keepsProgram(TextureDraw.Type t) {
      switch (t) {
         case glStencilFunc:
         case glAlphaFunc:
         case glStencilOp:
         case glEnable:
         case glDisable:
         case glColorMask:
         case glStencilMask:
         case glBlendFunc:
         case glBlendEquation:
         case glBlendFuncSeparate:
         case glDepthMask:
         case glDepthFunc:
            return true;
         default:
            return false;
      }
   }

   // ------------------------------------------------------------------------------------------------ state folding (game thread)

   public static long folded, foldFailed;
   private static final zombie.core.opengl.IOpenGLState.Value MASK_TMP = zombie.core.opengl.GLState.DepthMask.pzoptNewValue();
   private static final zombie.core.opengl.IOpenGLState.Value FUNC_TMP = zombie.core.opengl.GLState.DepthFunc.pzoptNewValue();
   private static final int[] FUNCS = {512, 513, 514, 515, 516, 517, 518, 519};
   private static final zombie.core.opengl.GLState.CIntValue[] FUNC_VALUES = new zombie.core.opengl.GLState.CIntValue[FUNCS.length];

   static {
      for (int i = 0; i < FUNCS.length; i++) {
         FUNC_VALUES[i] = new zombie.core.opengl.GLState.CIntValue().set(FUNCS[i]);
      }
   }

   /** The game-thread cache's depth mask: ops are recorded on change, so it is what the next draw sees (-1 = dirty). */
   private static int maskNow() {
      if (zombie.core.opengl.GLState.DepthMask.pzoptSnapshot(MASK_TMP)) {
         return -1;
      }
      return MASK_TMP.equals(zombie.core.opengl.GLState.CBooleanValue.TRUE) ? 1 : 0;
   }

   private static int funcNow() {
      if (zombie.core.opengl.GLState.DepthFunc.pzoptSnapshot(FUNC_TMP)) {
         return -1;
      }
      for (int i = 0; i < FUNCS.length; i++) {
         if (FUNC_TMP.equals(FUNC_VALUES[i])) {
            return FUNCS[i];
         }
      }
      return -1;
   }

   private static final int FOLD_MAX = 64;
   private static final TextureDraw[] KEPT_D = new TextureDraw[FOLD_MAX], DROP_D = new TextureDraw[FOLD_MAX];
   private static final Object[] KEPT_S = new Object[FOLD_MAX], DROP_S = new Object[FOLD_MAX];

   /**
    * tileStateFold: the entries recorded since the last merged tile's draw are only shader ends and fixed-function state:
    * the ends go (the program stays bound: nothing draws in between), each depth mask / depth function keeps only its
    * last value and goes too when that is the value the last draw had. Nothing left = the next tile's quads join that
    * draw's run. False (nothing changed) when anything else is in between.
    */
   private static boolean fold(GenericSpriteRenderState st, Pending p, int n) {
      int from = p.lastDraw + 1;
      TextureDraw drawn = st.sprite[p.lastDraw];
      if (drawn.type != TextureDraw.Type.glDraw || !drawn.pzoptVD) {
         return false;
      }
      if (n - from > FOLD_MAX) {
         return false;
      }
      int maskIdx = -1, funcIdx = -1;
      for (int i = from; i < n; i++) {
         TextureDraw e = st.sprite[i];
         TextureDraw.Type t = e.type;
         if (t == TextureDraw.Type.StartShader) {
            if (e.a != 0 || e.pzoptVDStart) {
               foldFailed++;
               return false;
            }
         } else if (t == TextureDraw.Type.glDepthMask) {
            maskIdx = i;
         } else if (t == TextureDraw.Type.glDepthFunc) {
            funcIdx = i;
         } else if (!keepsProgram(t)) {
            foldFailed++;
            return false;
         }
      }
      boolean keepMask = maskIdx >= 0 && (p.maskAtDraw < 0 || st.sprite[maskIdx].a != p.maskAtDraw);
      boolean keepFunc = funcIdx >= 0 && (p.funcAtDraw < 0 || st.sprite[funcIdx].a != p.funcAtDraw);
      // compact [from, n): the kept entries in order, then the dropped objects (reused by later entries)
      int w = from;
      Object[] styles = st.style;
      TextureDraw[] keptD = KEPT_D, dropD = DROP_D;
      Object[] keptS = KEPT_S, dropS = DROP_S;
      int k = 0, d = 0;
      for (int i = from; i < n; i++) {
         TextureDraw e = st.sprite[i];
         TextureDraw.Type t = e.type;
         boolean keep = t == TextureDraw.Type.glDepthMask ? keepMask && i == maskIdx
            : t == TextureDraw.Type.glDepthFunc ? keepFunc && i == funcIdx
            : t != TextureDraw.Type.StartShader;
         if (keep) {
            keptD[k] = e;
            keptS[k++] = styles[i];
         } else {
            dropD[d] = e;
            dropS[d++] = styles[i];
         }
      }
      for (int i = 0; i < k; i++) {
         st.sprite[w] = keptD[i];
         styles[w++] = keptS[i];
      }
      int end = w;
      for (int i = 0; i < d; i++) {
         st.sprite[w] = dropD[i];
         styles[w++] = dropS[i];
      }
      st.numSprites = end;
      p.next = end;
      return true;
   }

   // ------------------------------------------------------------------------------------------------ bake unit splice (game thread)

   private static int spliceScan, spliceMask, spliceFunc; // the depth mask / function in effect at entry spliceScan of the splice

   /** bakeRecordParallel, game thread: a bake unit's entries start being copied into {@code real} here. */
   static void spliceBegin(GenericSpriteRenderState real) {
      spliceScan = real.numSprites;
      spliceMask = maskNow();
      spliceFunc = funcNow();
   }

   /**
    * bakeRecordParallel, game thread: a recorded tile start is next. True when start() at this point of the frame's stream
    * would have merged it into the open start (or folded the state since its last draw): the caller drops the entry.
    */
   static boolean spliceStart(GenericSpriteRenderState real, int program) {
      Pending p = PENDING.get();
      int key = Sway.startKey();
      int n = real.numSprites;
      boolean reuse = p.state == real && p.bound && p.program == program && p.swayKey == key && p.lastDraw >= 0 && p.lastDraw < n
         && real.sprite[p.lastDraw].type == TextureDraw.Type.glDraw && real.sprite[p.lastDraw].pzoptVD && onlyState(real, p.lastDraw + 1, n);
      if (!reuse && Config.TILE_STATE_FOLD && p.state == real && p.bound && p.program == program && p.swayKey == key && p.lastDraw >= 0
         && p.lastDraw < n && fold(real, p, n)) {
         reuse = true;
         folded++;
      }
      if (!reuse) {
         return false;
      }
      merged++;
      p.bound = true;
      p.next = real.numSprites;
      return true;
   }

   /** bakeRecordParallel: the depth mask / function entries of the game thread's own entries in [spliceScan, to) (its splice-time emits). */
   private static void spliceScanTo(GenericSpriteRenderState real, int to) {
      for (int i = spliceScan; i < to; i++) {
         TextureDraw e = real.sprite[i];
         if (e.type == TextureDraw.Type.glDepthMask) {
            spliceMask = e.a;
         } else if (e.type == TextureDraw.Type.glDepthFunc) {
            spliceFunc = e.a;
         }
      }
   }

   /** bakeRecordParallel: a recorded entry moved to slot n (its kind from DrawRecorder.classify: no read of the entry itself). */
   static void spliceMoved(GenericSpriteRenderState real, int n, byte kind, int arg) {
      spliceScanTo(real, n);
      if (kind == DrawRecorder.K_MASK) {
         spliceMask = arg;
      } else if (kind == DrawRecorder.K_FUNC) {
         spliceFunc = arg;
      }
      spliceScan = n + 1;
      if (kind == DrawRecorder.K_VDDRAW) {
         spliceDraw(real, n);
      }
   }

   /** bakeRecordParallel: the recorded tile start moved to slot n (it opened a start of its own). */
   static void spliceStarted(GenericSpriteRenderState real, int program, int n) {
      spliceScanTo(real, n);
      spliceScan = n + 1;
      spliceStarted(real, program);
   }

   /** bakeRecordParallel: the recorded tile start was copied (it opened a start of its own). */
   static void spliceStarted(GenericSpriteRenderState real, int program) {
      Pending p = PENDING.get();
      p.state = real;
      p.program = program;
      p.swayKey = Sway.startKey();
      p.lastDraw = -1;
      starts++;
      p.bound = true;
      p.next = real.numSprites;
   }

   /** bakeRecordParallel: a recorded tile draw was copied to slot {@code idx} (stamp()'s bookkeeping for it). */
   static void spliceDraw(GenericSpriteRenderState real, int idx) {
      Pending p = PENDING.get();
      if (p.state != real) {
         return;
      }
      if (idx < p.next) {
         p.state = null;
         return;
      }
      if (p.bound && !onlyState(real, p.next, idx)) {
         p.bound = false;
      }
      p.next = idx + 1;
      if (p.bound) {
         p.lastDraw = idx;
         if (Config.TILE_STATE_FOLD) {
            // the game cache's depth mask / function at this draw: the last such entry of the stream since the splice began, else
            // the cache as the splice found it (a recorder's first set of a segment is issued or adopted against the cache).
            // Scanned forward from where the last draw left off (a fold may have moved the scan point past the end: the
            // entries it kept were scanned already and are still the latest)
            if (spliceScan < idx) {
               spliceScanTo(real, idx);
               spliceScan = idx;
            }
            p.maskAtDraw = spliceMask;
            p.funcAtDraw = spliceFunc;
         }
      }
   }

   /** devBakeRecordCheck: the game thread's open start, kept across a stock draw that is rolled back. */
   static Object snapshot() {
      Pending p = PENDING.get();
      return new Object[]{p.state, new int[]{p.program, p.next, p.swayKey, p.lastDraw, p.bound ? 1 : 0, p.maskAtDraw, p.funcAtDraw}, new float[]{p.front, p.far}};
   }

   static void restore(Object o) {
      Object[] a = (Object[])o;
      Pending p = PENDING.get();
      p.state = (GenericSpriteRenderState)a[0];
      int[] v = (int[])a[1];
      p.program = v[0];
      p.next = v[1];
      p.swayKey = v[2];
      p.lastDraw = v[3];
      p.bound = v[4] != 0;
      p.maskAtDraw = v[5];
      p.funcAtDraw = v[6];
      float[] f = (float[])a[2];
      p.front = f[0];
      p.far = f[1];
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static final HashMap<Integer, int[]> LOC = new HashMap<>(); // program -> {location, value set (-1 unknown)}

   /** Render thread, TextureDraw's StartShader after the program's own start: pzoptVD = 1 for a merged start, else 0. */
   public static void onStart(int program, boolean vdStart) {
      if (program == 0) {
         return;
      }
      int[] l = LOC.get(program);
      if (l == null) {
         l = new int[] {GL20.glGetUniformLocation(program, "pzoptVD"), -1};
         LOC.put(program, l);
      }
      if (l[0] < 0) {
         return;
      }
      int want = vdStart ? 1 : 0;
      if (l[1] != want) {
         GL20.glUniform1f(l[0], want);
         l[1] = want;
      }
   }

   private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> WHY = new java.util.concurrent.ConcurrentHashMap<>();

   // the "op:<type>" keys made once (GC pass 2026-10-14: the per-start concatenation was ~4 MB/s of garbage in harness runs)
   private static final String[] OP_KEYS = opKeys();

   private static String[] opKeys() {
      TextureDraw.Type[] t = TextureDraw.Type.values();
      String[] k = new String[t.length];
      for (int i = 0; i < t.length; i++) {
         k[i] = "op:" + t[i];
      }
      return k;
   }

   private static void why(String k) {
      WHY.computeIfAbsent(k, x -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
   }

   public static String stats() {
      StringBuilder b = new StringBuilder("tile vertex depth: starts " + starts + ", merged starts skipped " + merged + " (" + folded + " after folding, " + foldFailed + " not foldable), draws stamped " + stamped + ", stock starts (plants) " + stockStarts + "; new start because");
      WHY.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().get(), x.getValue().get())).limit(8).forEach(e -> b.append(' ').append(e.getKey()).append('=').append(e.getValue().get()));
      return b.toString();
   }
}
