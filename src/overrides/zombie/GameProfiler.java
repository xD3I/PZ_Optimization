package zombie;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import zombie.core.profiling.TriggerGameProfilerFile;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.iso.IsoCamera;
import zombie.ui.TextManager;
import zombie.util.IPooledObject;
import zombie.util.Pool;
import zombie.util.PooledObject;

public final class GameProfiler {
   private static final String s_currentSessionUUID = UUID.randomUUID().toString();
   private static final pzopt.Tl<GameProfiler> s_instance = new pzopt.Tl<>(GameProfiler::new); // pzopt: pzopt.Tl (zombie redesign, f8-zomb2)
   private final Stack<GameProfiler.ProfileArea> stack = new Stack<>();
   private final GameProfiler.RecordingFrame currentFrame = new GameProfiler.RecordingFrame();
   private final GameProfiler.RecordingFrame previousFrame = new GameProfiler.RecordingFrame();
   private boolean isInFrame;
   private boolean isRunning;
   private final GameProfileRecording recorder;
   private static final Object m_gameProfilerRecordingTriggerLock = "Game Profiler Recording Watcher, synchronization lock";
   private static PredicatedFileWatcher gameProfilerRecordingTriggerWatcher;
   private static final ArrayList<String> m_validThreadNames = new ArrayList<>();
   private static final int MAX_DEPTH = 20;

   private GameProfiler() {
      String currentThreadName = Thread.currentThread().getName();
      String recordingClassName = currentThreadName.replace("-", "").replace(" ", "");
      String recordingUUID = String.format("%s_GameProfiler_%s", this.getCurrentSessionUUID(), recordingClassName);
      this.recorder = new GameProfileRecording(recordingUUID);
   }

   // pzopt: profilerThreadMemo. Every performance probe in the game (IsoZombie.update and postupdate among them) calls
   // this on the way in and on the way out, and the valid-thread list is an ArrayList, so each call was a linear scan
   // comparing thread-name strings. The list is filled once in the static initialiser and a thread keeps its name, so
   // the answer is memoised on the per-thread instance the profiler already keeps. 0.7 % of the game thread on the
   // Louisville horde, paid whether or not the profiler is recording.
   private boolean pzoptValidThread;
   private boolean pzoptValidThreadKnown;

   // pzopt: profilerIdleFast. A probe asks isValidThread and isRunning on the way in and out, each a ThreadLocal lookup;
   // with ~2,200 zombies that was 2.2 % of the game thread on the Louisville horde while nothing records. The two valid
   // threads are remembered by identity once the memo has said yes for them, our frame workers are never valid, and
   // isRunning is false for every thread until some thread's isRunning first turns true (it only does from
   // gameProfilerEnabled, in startFrame / endFrame), so until then the lookups are skipped with the same answers.
   private static volatile Thread pzoptValidA; // pzopt
   private static volatile Thread pzoptValidB; // pzopt
   private static volatile boolean pzoptAnyRunning; // pzopt: some thread's isRunning has been true

   private static boolean pzoptIdleFast() { // pzopt
      return pzopt.Config.PROFILER_IDLE_FAST && pzopt.Overrides.enabled() && pzopt.GtAb.on(pzopt.GtAb.PROFILER_IDLE); // pzopt
   } // pzopt

   public static boolean isValidThread() {
      if (pzoptIdleFast()) { // pzopt: profilerIdleFast
         Thread t = Thread.currentThread(); // pzopt
         if (t == pzoptValidA || t == pzoptValidB) { // pzopt
            return true; // pzopt
         } // pzopt
         if (t instanceof pzopt.FrameBatch.Worker) { // pzopt: "pzopt-frame-N" is not in the list
            return false; // pzopt
         } // pzopt
      } // pzopt
      if (pzopt.Config.PROFILER_THREAD_MEMO && pzopt.Overrides.enabled()) {
         GameProfiler profiler = s_instance.get();
         if (!profiler.pzoptValidThreadKnown) {
            profiler.pzoptValidThread = m_validThreadNames.contains(Thread.currentThread().getName());
            profiler.pzoptValidThreadKnown = true;
            if (profiler.pzoptValidThread) { // pzopt: profilerIdleFast, remembered by identity (a thread keeps its name, as the memo assumes)
               if (pzoptValidA == null) { // pzopt
                  pzoptValidA = Thread.currentThread(); // pzopt
               } else if (pzoptValidB == null && pzoptValidA != Thread.currentThread()) { // pzopt
                  pzoptValidB = Thread.currentThread(); // pzopt
               } // pzopt
            } // pzopt
         }

         return profiler.pzoptValidThread;
      }

      return m_validThreadNames.contains(Thread.currentThread().getName());
   }

   private static void onTrigger_setAnimationRecorderTriggerFile(TriggerGameProfilerFile triggerXml) {
      DebugOptions.instance.gameProfilerEnabled.setValue(triggerXml.isRecording);
   }

   private String getCurrentSessionUUID() {
      return s_currentSessionUUID;
   }

   public static GameProfiler getInstance() {
      return s_instance.get(); // pzopt: pzopt.Tl, the per-thread instance without the ThreadLocal lookup on the game thread and the frame workers
   }

   public void startFrame(String frameInvokerKey) {
      if (this.isInFrame) {
         throw new RuntimeException("Already inside a frame.");
      }

      this.isInFrame = true;
      this.isRunning = DebugOptions.instance.gameProfilerEnabled.getValue();
      if (this.isRunning) { // pzopt: profilerIdleFast
         pzoptAnyRunning = true; // pzopt
      } // pzopt
      if (!this.stack.empty()) {
         throw new RuntimeException("Recording stack should be empty at the start of a frame.");
      }

      if (this.isRunning) {
         int frameCount = IsoCamera.frameState.frameCount;
         if (this.currentFrame.frameNo != frameCount) {
            this.previousFrame.transferFrom(this.currentFrame);
            if (this.previousFrame.frameNo != -1) {
               this.recorder.writeLine();
            }

            long timeNs = getTimeNs();
            this.currentFrame.frameNo = frameCount;
            this.currentFrame.frameInvokerKey = frameInvokerKey;
            this.currentFrame.startTime = timeNs;
            this.recorder.reset();
            this.recorder.setFrameNumber(this.currentFrame.frameNo);
            this.recorder.setStartTime(this.currentFrame.startTime);
         }
      }
   }

   public void endFrame() {
      try {
         if (!this.isInFrame) {
            throw new RuntimeException("Not inside a frame.");
         }

         if (!this.isRunning) {
            return;
         }

         this.currentFrame.endTime = getTimeNs();
         this.currentFrame.totalTime = this.currentFrame.endTime - this.currentFrame.startTime;
         if (!this.stack.empty()) {
            throw new RuntimeException("Recording stack should be empty at the end of a frame.");
         }
      } finally {
         this.isInFrame = false;
         this.isRunning = DebugOptions.instance.gameProfilerEnabled.getValue();
         if (this.isRunning) { // pzopt: profilerIdleFast
            pzoptAnyRunning = true; // pzopt
         } // pzopt
      }
   }

   private boolean checkShouldMeasure() {
      if (!isRunning()) {
         return false;
      } else if (!this.isInFrame) {
         DebugType.General.warn("Not inside in a frame. Find the root caller function for this thread, and add call to invokeAndMeasureFrame.");
         return false;
      } else {
         return true;
      }
   }

   public static boolean isRunning() {
      if (!pzoptAnyRunning && pzoptIdleFast()) { // pzopt: profilerIdleFast, no thread has run the profiler yet
         return false; // pzopt
      } // pzopt
      return getInstance().isRunning;
   }

   public GameProfiler.@Nullable ProfileArea profile(String key) {
      return this.checkShouldMeasure() ? this.start(key) : null;
   }

   @Deprecated
   public GameProfiler.ProfileArea start(String areaKey) {
      if (this.stack.size() >= 20) {
         return null;
      }

      long timeNs = getTimeNs();
      GameProfiler.ProfileArea area = GameProfiler.ProfileArea.alloc();
      area.key = areaKey;
      return this.start(area, timeNs);
   }

   private synchronized GameProfiler.ProfileArea start(GameProfiler.ProfileArea area, long timeNs) {
      area.startTime = timeNs;
      area.depth = this.stack.size();
      if (!this.stack.isEmpty()) {
         GameProfiler.ProfileArea parentArea = this.stack.peek();
         parentArea.children.add(area);
      }

      this.stack.push(area);
      return area;
   }

   @Deprecated
   public synchronized void end(GameProfiler.ProfileArea area) {
      if (area != null) {
         area.endTime = getTimeNs();
         area.total = area.endTime - area.startTime;
         if (this.stack.peek() != area) {
            throw new RuntimeException("Incorrect exit. ProfileArea " + area + " is not at the top of the stack: " + this.stack.peek());
         }

         this.stack.pop();
         if (this.stack.isEmpty()) {
            this.recorder.logTimeSpan(area);
            area.release();
         }
      }
   }

   private void renderPercent(String label, long time, int x, int y, float r, float g, float b) {
      float tFloat = (float)time / (float)this.previousFrame.totalTime;
      tFloat *= 100.0F;
      tFloat = (int)(tFloat * 10.0F) / 10.0F;
      TextManager.instance.DrawString(x, y, label, r, g, b, 1.0);
      TextManager.instance.DrawString(x + 300, y, tFloat + "%", r, g, b, 1.0);
   }

   public void render(int x, int y) {
      this.renderPercent(this.previousFrame.frameInvokerKey, this.previousFrame.totalTime, x, y, 1.0F, 1.0F, 1.0F);
   }

   public static long getTimeNs() {
      return System.nanoTime();
   }

   public static void init() {
      initTriggerWatcher();
   }

   private static void initTriggerWatcher() {
      if (gameProfilerRecordingTriggerWatcher == null) {
         synchronized (m_gameProfilerRecordingTriggerLock) {
            if (gameProfilerRecordingTriggerWatcher == null) {
               gameProfilerRecordingTriggerWatcher = new PredicatedFileWatcher(
                  ZomboidFileSystem.instance.getMessagingDirSub("Trigger_PerformanceProfiler.xml"),
                  TriggerGameProfilerFile.class,
                  GameProfiler::onTrigger_setAnimationRecorderTriggerFile
               );
               DebugFileWatcher.instance.add(gameProfilerRecordingTriggerWatcher);
            }
         }
      }
   }

   static {
      m_validThreadNames.add("main");
      m_validThreadNames.add("MainThread");
   }

   public static class ProfileArea extends PooledObject implements AutoCloseable {
      public String key;
      public long startTime;
      public long endTime;
      public long total;
      public int depth;
      public float r = 1.0F;
      public float g = 1.0F;
      public float b = 1.0F;
      public final List<GameProfiler.ProfileArea> children = new ArrayList<>();
      private static final Pool<GameProfiler.ProfileArea> s_pool = new Pool(GameProfiler.ProfileArea::new);

      public void onReleased() {
         super.onReleased();
         this.clear();
      }

      public void clear() {
         this.startTime = 0L;
         this.endTime = 0L;
         this.total = 0L;
         this.depth = 0;
         IPooledObject.release(this.children);
      }

      public static GameProfiler.ProfileArea alloc() {
         return (GameProfiler.ProfileArea)s_pool.alloc();
      }

      @Override
      public void close() {
         GameProfiler.getInstance().end(this);
      }
   }

   public static class RecordingFrame {
      private String frameInvokerKey = "";
      private int frameNo = -1;
      private long startTime;
      private long endTime;
      private long totalTime;

      public void transferFrom(GameProfiler.RecordingFrame srcFrame) {
         this.clear();
         this.frameNo = srcFrame.frameNo;
         this.frameInvokerKey = srcFrame.frameInvokerKey;
         this.startTime = srcFrame.startTime;
         this.endTime = srcFrame.endTime;
         this.totalTime = srcFrame.totalTime;
         srcFrame.clear();
      }

      public void clear() {
         this.frameNo = -1;
         this.frameInvokerKey = "";
         this.startTime = 0L;
         this.endTime = 0L;
         this.totalTime = 0L;
      }
   }
}
