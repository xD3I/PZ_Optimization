package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import se.krka.kahlua.vm.KahluaTable;
import zombie.ZomboidFileSystem;
import zombie.core.SpriteRenderer;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;
import zombie.ui.UIElement;
import zombie.ui.UIElementInterface;
import zombie.ui.UIManager;

/**
 * uiProfile (2026-09-30, the UI snappiness pass): what the UI costs the game thread, measured exactly (nanoTime) instead
 * of sampled. UIManager.update is timed every frame (GameWindow hook), UIManager.render per UI frame and per top-level
 * element (UIManager override). For each element the UI draw commands it appended to the UI render state are counted and
 * hashed, so the log also tells how often an element's output was identical to its previous render (the head room of a
 * render-on-change UI). One block a second in Zomboid/pzopt-ui.out:
 * {@code T <epoch_ms> frames=<n> uiframes=<n> update_us=<sum> render_us=<sum> render_max_us=<max> sprites=<per ui frame>}
 * then per element with renders {@code E <name> renders=<n> us=<sum> max_us=<max> sprites=<sum> same=<renders equal to the
 * previous>}. On in instrumented runs; costs ~1 us per element render for the hash.
 */
public final class UiProfile {
   private static final class Stat {
      final String name;
      int renders;
      long ns;
      long maxNs;
      long sprites;
      int same;
      int replays;
      long replayNs;
      long updNs;
      long updTickNs;
      int updTicks;
      long lastHash;
      boolean hasHash;

      Stat(String name) {
         this.name = name;
      }
   }

   private static final IdentityHashMap<Object, Stat> stats = new IdentityHashMap<>();
   private static final ArrayList<Stat> order = new ArrayList<>();
   private static BufferedWriter out;
   private static boolean failed;
   private static long secondEnd;
   private static int frames;
   private static int uiFrames;
   private static long updateNs;
   private static long renderNs;
   private static long renderMaxNs;
   private static long updateMaxNs;
   private static long spritesTotal;
   private static long updateStart;
   private static long renderStart;
   private static long elementStart;
   private static int elementSprite;
   private static int renderSprite;

   private UiProfile() {
   }

   /** Frame / UI-render timestamps are logged until this nanoTime (set by mark). */
   private static long traceUntil;

   /**
    * Harness UI rig: an interaction (name, the Lua handler's microseconds). Logs {@code M <epoch_us> <name> <handler_us>}
    * and for the next two seconds every game frame end ({@code F <epoch_us>}) and UI render ({@code R <epoch_us> <render_us>
    * <sprites>}), so the analyser gets the handler's frame, the worst frames after it and the delay until the UI shows it.
    */
   public static void mark(String name, double handlerUs) {
      if (!enabled()) {
         return;
      }
      traceUntil = System.nanoTime() + 2_000_000_000L; // 2 s: the map shows ~1.04 s after its key
      line("M " + epochUs() + " " + name.replace(' ', '_') + " " + Math.round(handlerUs));
   }

   private static final long NANO0 = System.nanoTime();
   private static final long EPOCH0_US = System.currentTimeMillis() * 1000L;

   /** Wall-clock microseconds on the monotonic clock (epoch base taken once at class load). */
   private static long epochUs() {
      return EPOCH0_US + (System.nanoTime() - NANO0) / 1000L;
   }

   private static void line(String l) {
      try {
         open();
         out.write(l);
         out.write('\n');
      } catch (Throwable t) {
         failed = true;
         Log.warn("uiProfile: " + t);
      }
   }

   private static void open() throws java.io.IOException {
      if (out == null) {
         File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-ui.out");
         out = new BufferedWriter(new FileWriter(f, false), 1 << 16);
         out.write("# pzopt UI profile: T epoch_ms frames uiframes update_us render_us render_max_us sprites; E name renders us max_us sprites same; M epoch_us name handler_us; F epoch_us; R epoch_us render_us sprites\n");
      }
   }

   public static boolean enabled() {
      return Config.UI_PROFILE && !failed;
   }

   /** GameWindow, right before UIManager.update (once per game frame). */
   public static void updateBegin() {
      if (!enabled()) {
         return;
      }
      updateStart = System.nanoTime();
   }

   private static final long[] updSec = new long[7];
   private static long updLast;

   /** UIManager.update: time since the previous mark goes to section k (0 list upkeep, 1 mouse buttons, 2 click / wheel,
    * 3 mouse move, 4 world pick + OnMouseMove, 5 element updates, 6 tooltip). */
   public static void updateMark(int k) {
      if (!enabled() || updateStart == 0L) {
         return;
      }
      long t = System.nanoTime();
      updSec[k] += t - (updLast > updateStart ? updLast : updateStart);
      updLast = t;
   }

   public static void updateEnd() {
      if (!enabled() || updateStart == 0L) {
         return;
      }
      long now = System.nanoTime();
      updateNs += now - updateStart;
      updateMaxNs = Math.max(updateMaxNs, now - updateStart);
      updateStart = 0L;
      frames++;
      if (traceUntil != 0L) {
         if (now < traceUntil) {
            line("F " + epochUs());
         } else {
            traceUntil = 0L;
         }
      }
      long ms = System.currentTimeMillis();
      if (ms >= secondEnd) {
         flush(ms);
      }
   }

   private static GenericSpriteRenderState uiState() {
      return SpriteRenderer.instance.states.getPopulating().stateUi;
   }

   public static void renderBegin() {
      if (!enabled()) {
         return;
      }
      renderStart = System.nanoTime();
      renderSprite = uiState().numSprites;
   }

   public static void renderEnd() {
      if (!enabled() || renderStart == 0L) {
         return;
      }
      long ns = System.nanoTime() - renderStart;
      renderStart = 0L;
      renderNs += ns;
      renderMaxNs = Math.max(renderMaxNs, ns);
      spritesTotal += Math.max(0, uiState().numSprites - renderSprite);
      uiFrames++;
      if (traceUntil != 0L && System.nanoTime() < traceUntil) {
         line("R " + epochUs() + " " + ns / 1000L + " " + Math.max(0, uiState().numSprites - renderSprite));
      }
   }

   private static final java.util.HashMap<String, long[]> calls = new java.util.HashMap<>();
   private static final IdentityHashMap<Object, String> typeNames = new IdentityHashMap<>();

   /** UIElement: before a Lua prerender / render / update call (0 when not profiling). */
   public static long callBegin() {
      return enabled() ? System.nanoTime() : 0L;
   }

   /** UIElement: after it; kind 0 prerender, 1 render, 2 update; summed per element Lua Type. */
   public static void callEnd(UIElement e, int kind, long t0) {
      if (t0 == 0L) {
         return;
      }
      long ns = System.nanoTime() - t0;
      String type = typeNames.get(e);
      if (type == null) {
         type = name(e);
         if (typeNames.size() > 20000) {
            typeNames.clear();
         }
         typeNames.put(e, type);
      }
      long[] a = calls.computeIfAbsent(type, k -> new long[6]);
      a[kind * 2] += ns;
      a[kind * 2 + 1]++;
   }

   /** UIManager.updateUIElements: one top-level element's update (Java walk every frame, its Lua update on doTick frames). */
   public static void elementUpdated(UIElementInterface element, long ns, boolean tick) {
      if (!enabled() || element == null) {
         return;
      }
      Stat s = stat(element);
      s.updNs += ns;
      if (tick) {
         s.updTickNs += ns;
         s.updTicks++;
      }
   }

   /** uiRetained replayed the element's recorded commands (ns = the copy). */
   public static void elementReplayed(UIElementInterface element, long ns) {
      if (!enabled()) {
         return;
      }
      Stat s = stat(element);
      s.replays++;
      s.replayNs += ns;
   }

   private static Stat stat(UIElementInterface element) {
      Stat s = stats.get(element);
      if (s == null) {
         s = new Stat(name(element));
         stats.put(element, s);
         order.add(s);
      }
      return s;
   }

   /** devUiRetainedCheck: a replay would have shown outdated commands. */
   static void staleReplay(String name, long ageMs) {
      if (enabled()) {
         line("S " + System.currentTimeMillis() + " " + name.replace(' ', '_') + " " + ageMs);
      }
   }

   /** devUiTruth: a schedule (s = uiRetained, k = stock) showed something else than the truth for frames / us; logged when it ends. */
   static void truthEpisode(String which, String name, String cause, long frames, long us) {
      if (enabled()) {
         line("X " + epochUs() + " " + which + " " + name.replace(' ', '_') + " " + cause + " " + frames + " " + us);
      }
   }

   /** devUiTruth's per-second counters: element-frames stale (uiRetained, stock), frames with any stale element (same). */
   static void truthStats(int frames, int[] stale, int near) {
      if (enabled()) {
         line("Y " + System.currentTimeMillis() + " frames=" + frames + " stale_el_s=" + stale[0] + " stale_el_k=" + stale[1]
            + " stale_fr_s=" + stale[2] + " stale_fr_k=" + stale[3] + " move_near=" + near);
      }
   }

   /** uiVerify's per-second counters (pzopt.UiDeps). */
   static void depsStats(String s) {
      if (enabled()) {
         line("V " + System.currentTimeMillis() + " " + s);
      }
   }

   /** uiRetained's per-second counters. */
   static void retainedStats(int uiFrames, int inputFrames, int fresh, int replay, int checked, int stale, int recs, int uiEvents) {
      if (enabled()) {
         line("U " + System.currentTimeMillis() + " uiframes=" + uiFrames + " input=" + inputFrames + " fresh=" + fresh
            + " replay=" + replay + " checked=" + checked + " stale=" + stale + " recs=" + recs + " uievents=" + uiEvents);
      }
   }

   public static void elementBegin() {
      if (!enabled()) {
         return;
      }
      elementSprite = uiState().numSprites;
      elementStart = System.nanoTime();
   }

   public static void elementEnd(UIElementInterface element) {
      if (!enabled() || elementStart == 0L) {
         return;
      }
      long ns = System.nanoTime() - elementStart;
      elementStart = 0L;
      Stat s = stat(element);
      GenericSpriteRenderState st = uiState();
      int from = elementSprite;
      int to = st.numSprites;
      long h = UiRetained.hash(st.sprite, from, to);
      s.renders++;
      s.ns += ns;
      s.maxNs = Math.max(s.maxNs, ns);
      s.sprites += Math.max(0, to - from);
      if (s.hasHash && s.lastHash == h) {
         s.same++;
      } else if (s.hasHash && Config.DEV_UI_DIFF.contains("," + s.name + ",")) {
         diff(s, st.sprite, from, to);
      }
      if (Config.DEV_UI_DIFF.contains("," + s.name + ",")) {
         keep(s, st.sprite, from, to);
      }
      s.lastHash = h;
      s.hasHash = true;
   }

   private static final IdentityHashMap<Stat, long[][]> kept = new IdentityHashMap<>();
   private static int diffsThisSecond;

   private static void keep(Stat s, TextureDraw[] sprites, int from, int to) {
      long[][] rows = new long[Math.max(0, to - from)][];
      for (int i = from; i < to; i++) {
         rows[i - from] = sprites[i] == null ? new long[0] : UiRetained.key(sprites[i]);
      }
      kept.put(s, rows);
   }

   /** devUiDiff: the first differing commands of a watched element against its previous render, over the fields
    * uiRetained's hash covers (5 lines a second at most). */
   private static void diff(Stat s, TextureDraw[] sprites, int from, int to) {
      long[][] old = kept.get(s);
      if (old == null || diffsThisSecond >= 5) {
         return;
      }
      int n = to - from;
      StringBuilder b = new StringBuilder("D ").append(s.name).append(" cmds ").append(old.length).append("->").append(n);
      int shown = 0;
      for (int i = 0; i < Math.min(n, old.length) && shown < 3; i++) {
         TextureDraw d = sprites[from + i];
         if (d == null) {
            continue;
         }
         long[] now = UiRetained.key(d);
         long[] was = old[i];
         String[] names = d.type == TextureDraw.Type.glDraw ? UiRetained.QUAD_FIELDS : UiRetained.stateFields(d.type);
         StringBuilder f = new StringBuilder();
         if (was.length != now.length || was.length == 0 || was[0] != now[0]) {
            f.append(" type ").append(was.length == 0 ? -1 : was[0]).append("->").append(now[0]);
         } else {
            for (int k = 1; k < now.length; k++) {
               if (was[k] != now[k]) {
                  String nm = names[k - 1];
                  f.append(' ').append(nm).append('=');
                  if (nm.startsWith("x") || nm.startsWith("y") || nm.startsWith("u") && nm.length() == 2 || nm.startsWith("v") || nm.equals("f1")) {
                     f.append(Float.intBitsToFloat((int)was[k])).append("->").append(Float.intBitsToFloat((int)now[k]));
                  } else {
                     f.append(Long.toHexString(was[k])).append("->").append(Long.toHexString(now[k]));
                  }
               }
            }
         }
         if (f.length() > 0) {
            b.append(" | #").append(i).append(' ').append(d.type).append(f);
            shown++;
         }
      }
      line(b.toString());
      diffsThisSecond++;
   }

   static String name(UIElementInterface element) {
      if (element instanceof UIElement e) {
         KahluaTable t = e.getTable();
         if (t != null) {
            Object type = UIManager.tableget(t, "Type");
            if (type instanceof String s) {
               return s;
            }
         }
      }
      return element.getClass().getSimpleName();
   }

   private static void flush(long ms) {
      try {
         open();
         if (secondEnd != 0L) {
            StringBuilder b = new StringBuilder(512);
            b.append("T ").append(ms).append(" frames=").append(frames).append(" uiframes=").append(uiFrames)
               .append(" update_us=").append(updateNs / 1000L).append(" render_us=").append(renderNs / 1000L)
               .append(" render_max_us=").append(renderMaxNs / 1000L).append(" update_max_us=").append(updateMaxNs / 1000L)
               .append(" sprites=").append(uiFrames == 0 ? 0L : spritesTotal / uiFrames)
               .append(" upd_sections_us=");
            for (int k = 0; k < updSec.length; k++) {
               b.append(k == 0 ? "" : "/").append(updSec[k] / 1000L);
               updSec[k] = 0L;
            }
            b.append('\n');
            for (java.util.Map.Entry<String, long[]> c : calls.entrySet()) {
               long[] v = c.getValue();
               if (v[1] + v[3] + v[5] == 0L) {
                  continue;
               }
               b.append("C ").append(c.getKey().replace(' ', '_')).append(" pre_us=").append(v[0] / 1000L).append(" pre_n=").append(v[1])
                  .append(" ren_us=").append(v[2] / 1000L).append(" ren_n=").append(v[3]).append(" upd_us=").append(v[4] / 1000L)
                  .append(" upd_n=").append(v[5]).append('\n');
               java.util.Arrays.fill(v, 0L);
            }
            for (Stat s : order) {
               if (s.renders == 0 && s.replays == 0 && s.updNs == 0L) {
                  continue;
               }
               b.append("E ").append(s.name.replace(' ', '_')).append(" renders=").append(s.renders)
                  .append(" us=").append(s.ns / 1000L).append(" max_us=").append(s.maxNs / 1000L)
                  .append(" sprites=").append(s.sprites).append(" same=").append(s.same)
                  .append(" replays=").append(s.replays).append(" replay_us=").append(s.replayNs / 1000L)
                  .append(" upd_us=").append(s.updNs / 1000L).append(" updtick_us=").append(s.updTickNs / 1000L).append(" ticks=").append(s.updTicks).append('\n');
               s.replays = 0;
               s.replayNs = 0L;
               s.updNs = 0L;
               s.updTickNs = 0L;
               s.updTicks = 0;
               s.renders = 0;
               s.ns = 0L;
               s.maxNs = 0L;
               s.sprites = 0L;
               s.same = 0;
            }
            out.write(b.toString());
            out.flush();
         }
      } catch (Throwable t) {
         failed = true;
         Log.warn("uiProfile: " + t);
      }
      secondEnd = ms + 1000L;
      diffsThisSecond = 0;
      frames = 0;
      uiFrames = 0;
      updateNs = 0L;
      renderNs = 0L;
      renderMaxNs = 0L;
      updateMaxNs = 0L;
      spritesTotal = 0L;
   }
}
