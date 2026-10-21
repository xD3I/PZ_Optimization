package pzopt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Technique 23 (and the project's objective, technique 7): what the measured limits mean for this player. Every rule
 * reads the passes' own numbers (bottleneck shares, utilization, collector pauses, spread, clock, background load);
 * {severity, text} with severity good / info / tip / warn.
 */
final class BenchAdvice {
   private BenchAdvice() {
   }

   static List<String[]> advise(Map<String, BenchResults.Agg> agg, Map<String, String> sys) {
      List<String[]> out = new ArrayList<>();
      boolean any = false;
      for (BenchResults.Agg a : agg.values()) {
         any |= "A".equals(a.config) && a.fps.n > 0; // the sweep's passes count as valid measurements too
         if (!"A".equals(a.config) || a.fps.n == 0 || !BenchSuite.scored(a.scenario)) {
            continue;
         }
         String t = BenchSuite.byId(a.scenario).title;
         double gpu = v(a, "bound_gpu_pct"), game = v(a, "bound_game_pct"), render = v(a, "bound_render_pct"), cap = v(a, "bound_cap_pct");
         double gpuBusy = v(a, "gpu_busy_pct"), gameLoad = v(a, "game_thread_pct");
         double capFps = v(a, "cap_fps");
         if (cap >= 60) {
            String what = capFps > 0 ? String.format(Locale.ROOT, "the frame cap (%d fps)", (int)capFps)
                  : "true".equals(sys.get("vsync")) ? "v-sync (" + sys.getOrDefault("refresh_hz", "?") + " Hz)" : "the present (the driver's swap)";
            out.add(new String[] {"info", String.format(Locale.ROOT, "%s: %s held %.0f %% of the frames; the PC has more to give. Switch v-sync off and pick Uncapped (Display options) to measure the headroom.", t, what, cap)});
            continue;
         }
         if (gpu >= 50) {
            out.add(new String[] {"tip", String.format(Locale.ROOT, "%s: the graphics card limited %.0f %% of the frames. Upscaling (Options > PZ Optimization > Visuals > Image quality: FSR 1.0, or DLSS on NVIDIA), a closer zoom or fewer Visuals features give it room.", t, gpu)});
         } else if (game >= 50) {
            out.add(new String[] {"tip", String.format(Locale.ROOT, "%s: the game thread limited %.0f %% of the frames (simulation, zombies, Lua). A lower zombie population, fewer script-heavy mods or a CPU with faster single cores help here; the Performance settings of PZ Optimization are what moves this work to other cores.", t, game)});
         } else if (render >= 50) {
            out.add(new String[] {"tip", String.format(Locale.ROOT, "%s: the render thread or the driver's present limited %.0f %% of the frames. V-sync off, the newest graphics driver, and \"Persistently mapped sprite buffers\" (Options > PZ Optimization > Performance > World drawing) help.", t, render)});
         }
         if (capFps > 0 && a.fps.mean < 0.95 * capFps || capFps == 0) {
            if (!Double.isNaN(gpuBusy) && !Double.isNaN(gameLoad) && gpuBusy < 80 && gameLoad < 85 && gpu < 50 && game < 50) {
               out.add(new String[] {"info", String.format(Locale.ROOT, "%s: below the cap with hardware left over (GPU busy %.0f %%, game thread %.0f %%): the frames wait on each other, not on a full core or GPU.", t, gpuBusy, gameLoad)});
            }
         }
         double gcMax = v(a, "gc_max_pause_ms");
         if (gcMax >= 30) {
            out.add(new String[] {"tip", String.format(Locale.ROOT, "%s: a garbage-collector pause of %.0f ms. More Java memory or another collector (Options > PZ Optimization > Performance: \"Java memory for the game\", \"Garbage collector\") can shorten them.", t, gcMax)});
         }
         if (a.fps.n >= 2 && a.fps.cvPct > 5) {
            out.add(new String[] {"warn", String.format(Locale.ROOT, "%s: the passes differ by %.1f %% (coefficient of variation). Close other programs, keep the game window in front, or add passes for a firmer number.", t, a.fps.cvPct)});
         }
         if (v(a, "below30_pct") > 10) {
            out.add(new String[] {"warn", String.format(Locale.ROOT, "%s: %.0f %% of the time ran below 30 fps.", t, v(a, "below30_pct"))});
         }
      }
      for (BenchResults.Agg a : agg.values()) {
         for (Map<String, String> p : a.passes) {
            String w = p.getOrDefault("warnings", "");
            if (!w.isEmpty()) {
               out.add(new String[] {"warn", BenchSuite.byId(a.scenario).title + " pass " + p.get("pass") + ": " + w + "."});
            }
         }
      }
      BenchResults.Agg any0 = null;
      for (BenchResults.Agg a : agg.values()) {
         if ("A".equals(a.config) && a.fps.n > 0) {
            any0 = a;
            break;
         }
      }
      if (any0 != null && !"uncapped".equals(sys.get("frame_cap")) && "true".equals(sys.get("vsync"))) {
         out.add(new String[] {"info", "V-sync was on: the frame rate cannot pass the screen's refresh rate."});
      }
      if (!any) {
         out.add(new String[] {"warn", "No pass of this run is a valid measurement; see the reasons beside each scenario."});
      }
      return out;
   }

   private static double v(BenchResults.Agg a, String k) {
      Double d = a.mean.get(k);
      return d == null ? Double.NaN : d;
   }
}
