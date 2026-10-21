package pzopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * Dev rig {@code devMovingProf=N} (Louisville 120 goal, horde round 2, 2026-10-14): where the render thread and the GPU
 * spend the world pass's moving objects. Measurement only, nothing drawn changes.
 *
 * <p>Render thread, inside the moving pass (pzopt.HordeDraw's pass markers): every command and every sprite batch of the
 * ring buffer is put in a category (a sprite batch by its texture, a generic drawer by its class, a model draw by its
 * object's class, any other command by its type) and the render thread's time between two category changes is charged
 * to the category that was running; nested parts (the hordeBatch flush, a model's init-future wait) are charged to their
 * own category. Every N-th frame a {@code GL_TIMESTAMP} query is written at each category change too (at most
 * {@link #MAX_Q} a frame), so the GPU time between two changes is charged the same way (it includes the GPU's waits for
 * the render thread: a category the GPU waits on shows GPU time it did not spend drawing).
 *
 * <p>{@code pzopt-movingprof.out}: one line per frame, {@code nano gpu|cpu passNs passGpuNs cat:cpuNs:count:units[:gpuNs]
 * ...} (units = indices of a sprite batch, 0 elsewhere), and {@code # cat <i> <name>} the first time a category is seen.
 * harness/movingprof.py sums it per route second.
 */
public final class MovingProf {
   public static final int N = Config.DEV_MOVING_PROF;
   public static final boolean ON = N > 0;
   public static final int MAX_Q = 6000;
   private static final int MAX_CAT = 160;

   private MovingProf() {
   }

   /** Render thread: inside the moving pass of a profiled frame. */
   public static boolean active;

   private static final String[] names = new String[MAX_CAT];
   private static int ncat;
   private static final HashMap<String, Integer> byName = new HashMap<>();
   private static final IdentityHashMap<Object, Integer> byKey = new IdentityHashMap<>();
   private static final HashMap<Integer, Integer> byTexId = new HashMap<>();
   private static final ArrayList<String> newNames = new ArrayList<>();

   public static final int GAP = cat("gap");
   public static final int FLUSH = cat("hordeBatch.flush");
   public static final int WAIT = cat("model.initWait");
   public static final int BEGIN = cat("slot.begin");
   public static final int BEGIN_TEX = cat("slot.beginTexRender");
   public static final int RECORD = cat("slot.record");
   public static final int REC_LIGHTS = cat("slot.record.lights");
   public static final int REC_PALETTE = cat("slot.record.palette");
   public static final int STOCK = cat("slot.stockDraw");
   public static final int BIND = cat("flush.texBind");
   public static final int VAO = cat("flush.vao");
   public static final int VAO_NEW = cat("flush.vao.new");
   public static final int ATLAS_FLUSH = cat("atlasBatch.flush");
   public static final int VAO_REBUILD = cat("flush.vao.rebuild");
   public static final int VAO_CLEAR = cat("flush.vao.clear");
   private static final int SPR_MORE = cat("spr.more");
   private static final int OTHER_MORE = cat("other.more");

   private static final long[] cpu = new long[MAX_CAT];
   private static final int[] count = new int[MAX_CAT];
   private static final long[] units = new long[MAX_CAT];
   private static int cur;
   private static long curT;
   private static final int[] stack = new int[16];
   private static int depth;
   private static long passT0;
   private static long frames;

   // GPU sampling
   private static boolean gpuFrame;
   private static boolean dense; // stamps at every category change (else only the pass's begin and end)
   private static final int RECS = 8;
   private static final Rec[] recs = new Rec[RECS];
   private static Rec rec;
   private static int[] qpool;
   private static int qnext;

   private static final class Rec {
      boolean busy;
      boolean dense;
      int slot;
      int mode;
      long nano;
      long passNs;
      int nq;
      final int[] q = new int[MAX_Q];
      final short[] qcat = new short[MAX_Q];
      final long[] cpu = new long[MAX_CAT];
      final int[] count = new int[MAX_CAT];
      final long[] units = new long[MAX_CAT];
      final long[] gpu = new long[MAX_CAT];
   }

   private static int cat(String name) {
      Integer i = byName.get(name);
      if (i != null) {
         return i;
      }
      if (ncat >= MAX_CAT - 1) {
         return name.startsWith("spr.") ? SPR_MORE : OTHER_MORE;
      }
      int c = ncat++;
      names[c] = name;
      byName.put(name, c);
      newNames.add(c + " " + name);
      return c;
   }

   // ---- pass markers (render thread, pzopt.HordeDraw.Setter) ----
   public static void frameBegin() {
      if (!ON || !Overrides.enabled()) {
         return;
      }
      if (active && rec != null && rec.nano == 0L) {
         rec.busy = false; // the previous pass never ended
      }
      poll();
      java.util.Arrays.fill(cpu, 0L);
      java.util.Arrays.fill(count, 0);
      java.util.Arrays.fill(units, 0L);
      depth = 0;
      cur = GAP;
      frames++;
      gpuFrame = false;
      dense = false;
      rec = null;
      for (int i = 0; i < RECS; i++) {
         if (recs[i] == null) {
            recs[i] = new Rec();
            recs[i].slot = i;
         }
         if (!recs[i].busy) {
            rec = recs[i];
            break;
         }
      }
      if (rec != null) {
         if (qpool == null) {
            qpool = new int[MAX_Q * RECS];
            GL15.glGenQueries(qpool);
         }
         rec.nq = 0;
         rec.nano = 0L;
         rec.busy = true;
         rec.mode = HordeDraw.mode;
         gpuFrame = true;
         dense = frames % N == 0;
      }
      active = true;
      passT0 = curT = System.nanoTime();
      stampAlways(GAP);
   }

   public static void frameEnd() {
      if (!active) {
         return;
      }
      long now = System.nanoTime();
      cpu[cur] += now - curT;
      active = false;
      long passNs = now - passT0;
      if (gpuFrame) {
         stampAlways(-1);
         rec.dense = dense;
         rec.nano = now;
         rec.passNs = passNs;
         System.arraycopy(cpu, 0, rec.cpu, 0, MAX_CAT);
         System.arraycopy(count, 0, rec.count, 0, MAX_CAT);
         System.arraycopy(units, 0, rec.units, 0, MAX_CAT);
         gpuFrame = false;
      } else {
         write(now, false, HordeDraw.mode, passNs, -1L, cpu, count, units, null);
      }
   }

   private static void stamp(int newCat) {
      if (dense) {
         stampAlways(newCat);
      }
   }

   private static void stampAlways(int newCat) {
      if (!gpuFrame || rec.nq >= MAX_Q) {
         return;
      }
      int slot = rec.slot * MAX_Q + rec.nq;
      int id = qpool[slot];
      GL33.glQueryCounter(id, GL33.GL_TIMESTAMP);
      rec.q[rec.nq] = id;
      rec.qcat[rec.nq] = (short) newCat;
      rec.nq++;
   }

   private static void poll() {
      for (Rec r : recs) {
         if (r == null || !r.busy || r.nano == 0L || r.nq < 2) {
            continue;
         }
         if (GL15.glGetQueryObjecti(r.q[r.nq - 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            continue;
         }
         java.util.Arrays.fill(r.gpu, 0L);
         long prev = GL33.glGetQueryObjecti64(r.q[0], GL15.GL_QUERY_RESULT);
         long first = prev;
         for (int i = 1; i < r.nq; i++) {
            long t = GL33.glGetQueryObjecti64(r.q[i], GL15.GL_QUERY_RESULT);
            int c = r.qcat[i - 1];
            if (c >= 0) {
               r.gpu[c] += Math.max(0L, t - prev);
            }
            prev = t;
         }
         write(r.nano, r.dense, r.mode, r.passNs, prev - first, r.cpu, r.count, r.units, r.dense ? r.gpu : null);
         r.busy = false;
         r.nano = 0L;
      }
   }

   // ---- category changes ----
   /** Top level: a new command or sprite batch starts (the previous one's time is charged). */
   private static void top(int c) {
      long now = System.nanoTime();
      cpu[cur] += now - curT;
      curT = now;
      count[c]++;
      if (c != cur) {
         stamp(c);
      }
      cur = c;
   }

   /** Nested part (flush, init wait) starts. */
   public static void enter(int c) {
      if (!active || depth >= stack.length) {
         return;
      }
      long now = System.nanoTime();
      cpu[cur] += now - curT;
      curT = now;
      stack[depth++] = cur;
      count[c]++;
      if (c != cur) {
         stamp(c);
      }
      cur = c;
   }

   public static void exit() {
      if (!active || depth == 0) {
         return;
      }
      long now = System.nanoTime();
      cpu[cur] += now - curT;
      curT = now;
      int c = stack[--depth];
      if (c != cur) {
         stamp(c);
      }
      cur = c;
   }

   // census of the texture composites: how many characters, how often each (a cache would serve the repeats)
   private static final java.util.WeakHashMap<Object, int[]> composites = new java.util.WeakHashMap<>();
   private static long compositeCount;
   private static long compositeLogNs;

   public static void composite(Object character) {
      if (character == null) {
         return;
      }
      compositeCount++;
      composites.computeIfAbsent(character, k -> new int[1])[0]++;
      long now = System.nanoTime();
      if (now - compositeLogNs > 10_000_000_000L) {
         compositeLogNs = now;
         int[] hist = new int[6];
         for (int[] v : composites.values()) {
            hist[Math.min(5, v[0])]++;
         }
         Log.info("moving prof: composites=" + compositeCount + " characters=" + composites.size() + " by count 1/2/3/4/5+ " + hist[1] + "/" + hist[2] + "/" + hist[3] + "/" + hist[4] + "/" + hist[5]);
      }
   }

   /** TextureDraw's DrawModel, the stock drawer of a character model (not batched) about to render. */
   public static void enterStock(Object drawer) {
      if (drawer instanceof ModelSlotRenderData) {
         enter(STOCK);
      }
   }

   public static void exitStock(Object drawer) {
      if (drawer instanceof ModelSlotRenderData) {
         exit();
      }
   }

   /** TextureDraw.run, before anything else. */
   public static void op(TextureDraw texd) {
      if (depth != 0) {
         return; // a command run from inside a nested part (e.g. a replay): stays in that part
      }
      Object key;
      String name;
      if (texd.type == TextureDraw.Type.DrawModel && texd.drawer != null) {
         Object d = texd.drawer;
         if (d instanceof ModelSlotRenderData slot) {
            Object o = slot.object != null ? slot.object : slot.character;
            key = o == null ? ModelSlotRenderData.class : o.getClass();
            Integer c = byKey.get(key);
            if (c == null) {
               c = cat("slot." + (o == null ? "null" : o.getClass().getSimpleName()));
               byKey.put(key, c);
            }
            top(c);
            return;
         }
         key = d.getClass();
         Integer c = byKey.get(key);
         if (c == null) {
            String n = d.getClass().getName();
            c = cat("gen." + n.substring(n.lastIndexOf('.') + 1));
            byKey.put(key, c);
         }
         top(c);
         return;
      }
      key = texd.type;
      Integer c = byKey.get(key);
      if (c == null) {
         c = cat("op." + texd.type.name());
         byKey.put(key, c);
      }
      top(c);
   }

   /** SpriteRenderer's StateRun, a sprite batch about to draw (texture0, index count). */
   public static void sprites(Texture tex, int length) {
      if (depth != 0) {
         return;
      }
      int id = tex == null ? 0 : tex.getID();
      Integer c = byTexId.get(id);
      if (c == null) {
         String n = tex == null ? "none" : tex.getName();
         if (n == null) {
            n = "id" + id + "_" + tex.getWidthHW() + "x" + tex.getHeightHW();
         } else {
            n = n.replace(' ', '_');
            int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
            if (slash >= 0) {
               n = n.substring(slash + 1);
            }
         }
         c = cat("spr." + n);
         byTexId.put(id, c);
      }
      top(c);
      units[c] += Math.max(0, length);
   }

   // ---- output ----
   private static java.io.BufferedWriter log;
   private static boolean logFailed;
   private static int lines;

   private static void write(long nano, boolean gpu, int mode, long passNs, long passGpu, long[] cpu, int[] count, long[] units, long[] gpuNs) {
      if (logFailed) {
         return;
      }
      try {
         if (log == null) {
            String dir = zombie.ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new java.io.BufferedWriter(new java.io.FileWriter(new java.io.File(dir, "pzopt-movingprof.out"), false), 1 << 16);
            log.write("# nano gpu|cpu passNs passGpuNs m<devHordeModes mode> cat:cpuNs:count:units[:gpuNs] ... (render thread, the moving pass; gpu = timestamps at every category change, else passGpuNs from the pass's two stamps (-1 none); pzopt.MovingProf)\n");
         }
         for (String s : newNames) {
            log.write("# cat " + s + "\n");
         }
         newNames.clear();
         StringBuilder sb = new StringBuilder(256);
         sb.append(nano).append(gpu ? " gpu " : " cpu ").append(passNs).append(' ').append(passGpu).append(" m").append(mode);
         for (int c = 0; c < ncat; c++) {
            if (count[c] == 0 && cpu[c] == 0L && (gpuNs == null || gpuNs[c] == 0L)) {
               continue;
            }
            sb.append(' ').append(c).append(':').append(cpu[c]).append(':').append(count[c]).append(':').append(units[c]);
            if (gpuNs != null) {
               sb.append(':').append(gpuNs[c]);
            }
         }
         sb.append('\n');
         log.write(sb.toString());
         if (++lines % 240 == 0) {
            log.flush();
         }
      } catch (java.io.IOException | RuntimeException e) {
         logFailed = true;
      }
   }
}
