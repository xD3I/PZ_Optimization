package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugLog;

/**
 * Timestamps every console log line into Zomboid/pzopt-loadtrace.out
 * ("epochMs<TAB>line"), so the game's own phase markers ("X start" / "X end",
 * "STATE: ...", "game loading took", the [pzopt] harness lines) can be turned
 * into a load-phase table (harness/loadtime.py). console.txt is untouched.
 *
 * Hooks DebugLog's "recording" output: every line DebugLog echoes to
 * console.txt is also printed to that stream. The animation-player recorder
 * (a debug tool) sets the same stream; it is chained if present.
 *
 * Installed when the first override loads, only for harness runs or with
 * instrument=true.
 */
public final class LoadTrace {
   private static volatile boolean installed;
   private static volatile BufferedWriter writer;
   private static volatile PrintStream tee;

   private LoadTrace() {
   }

   public static void install() {
      if (installed) {
         return;
      }
      if (!Config.INSTRUMENT && HarnessFlags.get("mode") == null) {
         return;
      }
      installed = true;
      try {
         File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-loadtrace.out");
         BufferedWriter w = new BufferedWriter(new FileWriter(f, false), 1 << 14);
         writer = w;
         PrintStream previous = DebugLog.getInstance().getRecordingOut();
         Tee t = new Tee(w, previous);
         tee = t;
         DebugLog.getInstance().setRecordingOut(t);
         Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (w) {
               try {
                  w.flush();
               } catch (IOException ignored) {
               }
            }
         }, "pzopt-loadtrace-flush"));
      } catch (Throwable t) {
         Log.warn("load trace not installed: " + t);
      }
   }

   /**
    * Every frame (Stats.frameTick): puts the trace back when the game dropped it. Every character removed from the world
    * closes its animation recorder, and AnimationPlayerRecorder.closeLogRecording sets DebugLog's recording stream to null
    * whoever opened it, so the trace (and the benchmark's per-pass console window) ended with the first zombie unloaded
    * after world-ready (2026-10-21: every pass's console.out stopped 0-2 s into the route).
    */
   public static void rearm() {
      PrintStream t = tee;
      if (t != null) {
         DebugLog d = DebugLog.getInstance();
         if (d.getRecordingOut() == null) {
            d.setRecordingOut(t);
         }
      }
   }

   /** Writes the buffered lines out (a reader of the file mid-run, BenchCapture's console window: up to 16 KB and the last second sat in the buffer). */
   public static void flush() {
      BufferedWriter w = writer;
      if (w == null) {
         return;
      }
      synchronized (w) {
         try {
            w.flush();
         } catch (IOException ignored) {
         }
      }
   }

   /** A load-phase step marker, logged only when the trace is installed (harness runs, instrument=true). */
   public static void step(String what) {
      if (installed) {
         Log.info("load step: " + what);
      }
   }

   /** Stamp an event of our own into the trace (e.g. state changes seen by the auto-start thread). */
   public static void mark(String what) {
      Log.info(what); // DebugLog echoes it into the trace with the stamp
   }

   private static final class Tee extends PrintStream {
      private final BufferedWriter w;
      private final PrintStream previous;
      private long lastFlushMs;

      Tee(BufferedWriter w, PrintStream previous) {
         super(OutputStream.nullOutputStream(), false);
         this.w = w;
         this.previous = previous;
      }

      @Override
      public void println(String line) {
         if (previous != null) {
            previous.println(line);
         }
         long now = System.currentTimeMillis();
         synchronized (w) {
            try {
               w.write(Long.toString(now));
               w.write('\t');
               w.write(line);
               w.newLine();
               if (now - lastFlushMs > 1000L) { // a crash mid-load still leaves the trace on disk
                  w.flush();
                  lastFlushMs = now;
               }
            } catch (IOException ignored) {
            }
         }
      }

      @Override
      public void println(Object o) {
         println(String.valueOf(o));
      }

      @Override
      public void flush() {
         synchronized (w) {
            try {
               w.flush();
            } catch (IOException ignored) {
            }
         }
      }
   }
}
