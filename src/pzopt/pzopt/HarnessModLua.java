package pzopt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import zombie.ZomboidFileSystem;

/**
 * Harness runs' mod Lua profile (2026-10-21): {@link ModLuaProfile}, the in-game benchmark's per-mod Lua profiler, over
 * a harness/run.sh route window, so a harness run carries the same "Mods (Lua)" numbers as the in-game report and
 * Grafana's PZ run dashboard shows them (harness/grafana/ingest.py tables luaprof*).
 *
 * <p>Harness calls {@link #routeStart} on the route's first frame and {@link #routeEnd} where the route ends
 * (complete, aborted, rejected). The frames come from the benchmark's own hook (Harness.onFrame -> Benchmark.onTick ->
 * ModLuaProfile.onFrame, every RUN frame). A built-in benchmark pass (BenchPlan.active) arms the profiler itself and
 * writes its own step files; this class then stays out of it.
 *
 * <p>Files, beside the other per-run logs in Zomboid/ (run.sh copies every pzopt-*.out into the run dir):
 * {@code pzopt-luaprof.out} = ModLuaProfile.writeProperties (the {@code <base>-luaprof.properties} format, plus
 * {@code start_epoch_ms} = the profile's start on the wall clock), {@code pzopt-luaprof-frames.out} =
 * ModLuaProfile.writeCsv (one row per game frame: t_s from the profile's start, frame ms, all Lua, each origin's ms).
 *
 * <p>Key {@code modLuaProfile} (default on: the sampler is ~1-1.5 % of one core at 2 kHz and reads the Lua stack
 * without a safepoint, the hooks a few ns a Lua call; ModLuaProfile's class comment has the measurements);
 * {@code --prop modLuaProfile=false} leaves a run without it.
 */
final class HarnessModLua {
   static final boolean ON = Config.MOD_LUA_PROFILE;
   private static boolean armed;
   private static long startEpochMs;

   private HarnessModLua() {
   }

   /** Harness, route start (game thread). */
   static void routeStart() {
      try {
         if (!ON || armed || BenchPlan.active() || ModLuaProfile.active()) {
            return;
         }
         startEpochMs = System.currentTimeMillis();
         ModLuaProfile.start();
         armed = ModLuaProfile.active();
      } catch (Throwable t) {
         armed = false;
         Log.warn("mod lua profile (harness): could not start: " + t);
      }
   }

   /** Harness, route end (game thread): the profile stopped and written into Zomboid/. Never throws. */
   static void routeEnd() {
      if (!armed) {
         return;
      }
      armed = false;
      try {
         ModLuaProfile.Result r = ModLuaProfile.stop();
         if (r == null) {
            return;
         }
         File dir = new File(ZomboidFileSystem.instance.getCacheDir());
         File props = new File(dir, "pzopt-luaprof.out");
         ModLuaProfile.writeCsv(r, new File(dir, "pzopt-luaprof-frames.out"));
         ModLuaProfile.writeProperties(r, props);
         Files.writeString(props.toPath(), "source=harness\nstart_epoch_ms=" + startEpochMs + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
         Log.info(String.format(Locale.ROOT, "mod lua profile (harness): %d frames, Lua %.3f ms/frame (mods %.3f = %.2f %% of the frame), worst mod %s, %d samples, hooks %.2f us/frame",
               r.frames, r.luaMs, r.mods, r.modsPct, r.worst == null ? "none" : r.worst.id.substring(4), r.samples, r.overheadUs));
      } catch (Throwable t) {
         Log.warn("mod lua profile (harness): could not write the results: " + t);
      }
   }
}
