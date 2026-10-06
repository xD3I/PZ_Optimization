package zombie.fileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.GameWindow;
import zombie.core.logger.ExceptionLogger;
import zombie.fileSystem.FileSystem.TexturePackTextures;
import zombie.gameStates.GameLoadingState;

public final class FileSystemImpl extends FileSystem {
   private final ArrayList<DeviceList> devices = new ArrayList<>();
   private final ArrayList<FileSystemImpl.AsyncItem> inProgress = new ArrayList<>();
   private final ArrayList<FileSystemImpl.AsyncItem> pending = new ArrayList<>();
   private int lastId;
   private final DiskFileDevice diskDevice;
   private final MemoryFileDevice memoryFileDevice;
   private final HashMap<String, TexturePackDevice> texturepackDevices = new HashMap<>();
   private final HashMap<String, DeviceList> texturepackDevicelists = new HashMap<>();
   private final DeviceList defaultDevice;
   private final ExecutorService executor;
   private final AtomicBoolean lock = new AtomicBoolean(false);
   private final ArrayList<FileSystemImpl.AsyncItem> added = new ArrayList<>();
   public static final HashMap<String, Boolean> TexturePackCompression = new HashMap<>();
   // pzopt: tasks handed to the worker threads at once (stock 16)
   private int maxInFlight; // pzopt: not final, pzoptSetMaxInFlight

   static {
      pzopt.Overrides.onClassLoaded("zombie.fileSystem.FileSystemImpl");
   }

   public FileSystemImpl() {
      this.diskDevice = new DiskFileDevice("disk");
      this.memoryFileDevice = new MemoryFileDevice();
      this.defaultDevice = new DeviceList();
      this.defaultDevice.add(this.diskDevice);
      this.defaultDevice.add(this.memoryFileDevice);
      int numThreads = Runtime.getRuntime().availableProcessors() <= 4 ? 2 : 4;
      // pzopt: the pool decodes every texture, model, animation and depth map the load waits for; size it to the machine
      if (pzopt.Overrides.enabled()) {
         // pzopt: wider while the boot pump feeds it (the main thread is the only other busy core then); shrunk to
         // FILE_THREADS when the loading screen starts (pzopt.BootPump.onLoadStart)
         numThreads = pzopt.Config.BOOT_PUMP ? Math.max(pzopt.Config.FILE_THREADS, pzopt.Config.BOOT_FILE_THREADS) : pzopt.Config.FILE_THREADS;
         this.maxInFlight = pzopt.Config.FILE_INFLIGHT_LOAD; // pzopt: the boot and load width; FILE_INFLIGHT in play
      } else {
         this.maxInFlight = 16;
      }
      pzopt.Log.info("file system: " + numThreads + " worker threads, " + this.maxInFlight + " tasks in flight");
      // Keep I/O workers distinguishable from the render slot-init executor's generic pool threads:
      // corePlacement=dual-ccd may place only this known background pool on the background CCD.
      AtomicInteger threadId = new AtomicInteger();
      this.executor = Executors.newFixedThreadPool(numThreads, r -> new Thread(r, "pzopt-file-" + threadId.getAndIncrement()));
   }

   public boolean mount(IFileDevice device) {
      return true;
   }

   public boolean unMount(IFileDevice device) {
      return this.devices.remove(device);
   }

   public IFile open(DeviceList deviceList, String path, int mode) {
      IFile file = deviceList.createFile();
      if (file != null) {
         if (file.open(path, mode)) {
            return file;
         }

         file.release();
         return null;
      } else {
         return null;
      }
   }

   public void close(IFile file) {
      file.close();
      file.release();
   }

   public int openAsync(DeviceList deviceList, String path, int mode, IFileTask2Callback cb) {
      IFile file = deviceList.createFile();
      if (file != null) {
         FileSystemImpl.OpenTask item = new FileSystemImpl.OpenTask(this);
         item.file = file;
         item.path = path;
         item.mode = mode;
         item.cb = cb;
         return this.runAsync(item);
      } else {
         return -1;
      }
   }

   public void closeAsync(IFile file, IFileTask2Callback cb) {
      FileSystemImpl.CloseTask item = new FileSystemImpl.CloseTask(this);
      item.file = file;
      item.cb = cb;
      this.runAsync(item);
   }

   public void cancelAsync(int id) {
      if (id != -1) {
         for (int i = 0; i < this.pending.size(); i++) {
            FileSystemImpl.AsyncItem item = this.pending.get(i);
            if (item.id == id) {
               item.future.cancel(false);
               return;
            }
         }

         for (int i = 0; i < this.inProgress.size(); i++) {
            FileSystemImpl.AsyncItem item = this.inProgress.get(i);
            if (item.id == id) {
               item.future.cancel(false);
               return;
            }
         }

         while (!this.lock.compareAndSet(false, true)) {
            Thread.onSpinWait();
         }

         for (int i = 0; i < this.added.size(); i++) {
            FileSystemImpl.AsyncItem item = this.added.get(i);
            if (item.id == id) {
               item.future.cancel(false);
               break;
            }
         }

         this.lock.set(false);
      }
   }

   public InputStream openStream(DeviceList deviceList, String path) throws IOException {
      return deviceList.createStream(path);
   }

   public void closeStream(InputStream stream) {
   }

   private int runAsync(FileSystemImpl.AsyncItem item) {
      Thread thread = Thread.currentThread();
      if (thread != GameWindow.gameThread && thread != GameLoadingState.loader) {
         boolean var3 = true;
      }

      while (!this.lock.compareAndSet(false, true)) {
         Thread.onSpinWait();
      }

      item.id = this.lastId++;
      if (this.lastId < 0) {
         this.lastId = 0;
      }

      this.added.add(item);
      this.lock.set(false);
      return item.id;
   }

   public int runAsync(FileTask fileTask) {
      FileSystemImpl.AsyncItem item = new FileSystemImpl.AsyncItem();
      item.task = fileTask;
      item.future = new FutureTask<>(() -> { // pzopt: per-class run time (pzopt.FileTaskStats)
         long t0 = System.nanoTime();

         try {
            return fileTask.call();
         } finally {
            pzopt.FileTaskStats.add(fileTask.getClass(), System.nanoTime() - t0);
         }
      });
      return this.runAsync(item);
   }

   // pzopt: the boot pump thread (pzopt.BootPump) drives this concurrently with the main thread's own calls during
   // GameWindow.init (font loading); one pump at a time (docs/plan-instant-load.md B10)
   private final java.util.concurrent.locks.ReentrantLock pzoptPumpLock = new java.util.concurrent.locks.ReentrantLock();

   /** pzopt: tasks handed to the pool at once (FILE_INFLIGHT_LOAD while booting / loading, FILE_INFLIGHT in play) */
   public void pzoptSetMaxInFlight(int n) {
      if (this.maxInFlight != n) {
         pzopt.Log.info("file system: " + this.maxInFlight + " -> " + n + " tasks in flight");
         this.maxInFlight = n;
      }
   }

   public java.util.concurrent.ExecutorService pzoptExecutor() {
      return this.executor;
   }

   public void updateAsyncTransactions() {
      this.pzoptPumpLock.lock();
      try {
         this.pzoptUpdateAsyncTransactions();
      } finally {
         this.pzoptPumpLock.unlock();
      }
   }

   private void pzoptUpdateAsyncTransactions() {
      pzopt.SpriteWindow.drain(); // pzopt: depth-map loads that finished during the world loader's sprite refill
      int n = Math.min(this.inProgress.size(), this.maxInFlight); // pzopt: was 16

      for (int i = 0; i < n; i++) {
         FileSystemImpl.AsyncItem item = this.inProgress.get(i);
         if (item.future.isDone()) {
            this.inProgress.remove(i--);
            n--;
            if (item.future.isCancelled()) {
               boolean itemx = true;
            } else {
               Object result = null;

               try {
                  result = item.future.get();
               } catch (Throwable t) {
                  ExceptionLogger.logException(t, item.task.getErrorMessage());
               }

               item.task.handleResult(result);
            }

            item.task.done();
            item.task = null;
            item.future = null;
         }
      }

      while (!this.lock.compareAndSet(false, true)) {
         Thread.onSpinWait();
      }

      // pzopt: decompiler fix (the stock local is an int reused for the in-flight budget below)
      for (int i = 0; i < this.added.size(); i++) {
         FileSystemImpl.AsyncItem item = this.added.get(i);
         int insertAt = this.pending.size();

         for (int j = 0; j < this.pending.size(); j++) {
            FileSystemImpl.AsyncItem item1 = this.pending.get(j);
            if (item.task.priority > item1.task.priority) {
               insertAt = j;
               break;
            }
         }

         this.pending.add(insertAt, item);
      }

      this.added.clear();
      this.lock.set(false);
      int canAdd = this.maxInFlight - this.inProgress.size(); // pzopt: was 16 - size

      while (canAdd > 0 && !this.pending.isEmpty()) {
         FileSystemImpl.AsyncItem item = this.pending.remove(0);
         if (item.future.isCancelled()) {
            item.task.done();
         } else {
            this.inProgress.add(item);
            this.executor.submit(item.future);
            canAdd--;
         }
      }
   }

   /** pzopt: the queued and running tasks by class, for load-trace diagnostics (main thread, like hasWork) */
   public String pzoptWorkSummary() {
      java.util.TreeMap<String, int[]> n = new java.util.TreeMap<>();
      for (FileSystemImpl.AsyncItem item : this.pending) {
         n.computeIfAbsent(item.task.getClass().getSimpleName(), k -> new int[2])[0]++;
      }
      for (FileSystemImpl.AsyncItem item : this.inProgress) {
         n.computeIfAbsent(item.task.getClass().getSimpleName(), k -> new int[2])[1]++;
      }
      StringBuilder sb = new StringBuilder();
      n.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v[0]).append('+').append(v[1]));
      return sb.length() == 0 ? " none" : sb.toString();
   }

   public boolean hasWork() {
      if (this.pending.isEmpty() && this.inProgress.isEmpty()) {
         while (!this.lock.compareAndSet(false, true)) {
            Thread.onSpinWait();
         }

         boolean hasWork = !this.added.isEmpty();
         this.lock.set(false);
         return hasWork;
      } else {
         return true;
      }
   }

   public DeviceList getDefaultDevice() {
      return this.defaultDevice;
   }

   public void mountTexturePack(String name, TexturePackTextures subTextures, int flags) {
      TexturePackDevice texturePackDevice = new TexturePackDevice(name, flags);
      if (subTextures != null) {
         try {
            texturePackDevice.getSubTextureInfo(subTextures);
         } catch (IOException ex) {
            ExceptionLogger.logException(ex);
         }
      }

      this.texturepackDevices.put(name, texturePackDevice);
      DeviceList deviceList = new DeviceList();
      deviceList.add(texturePackDevice);
      this.texturepackDevicelists.put(texturePackDevice.name(), deviceList);
   }

   public DeviceList getTexturePackDevice(String name) {
      return this.texturepackDevicelists.get(name);
   }

   public int getTexturePackFlags(String name) {
      return this.texturepackDevices.get(name).getTextureFlags();
   }

   public boolean getTexturePackAlpha(String name, String page) {
      return this.texturepackDevices.get(name).isAlpha(page);
   }

   private static final class AsyncItem {
      int id;
      FileTask task;
      FutureTask<Object> future;
   }

   private static final class CloseTask extends FileTask {
      IFile file;
      IFileTask2Callback cb;

      CloseTask(FileSystem fileSystem) {
         super(fileSystem);
      }

      public Object call() throws Exception {
         this.file.close();
         this.file.release();
         return null;
      }

      public void handleResult(Object result) {
         if (this.cb != null) {
            this.cb.onFileTaskFinished(this.file, result);
         }
      }

      public void done() {
         this.file = null;
         this.cb = null;
      }
   }

   private static final class OpenTask extends FileTask {
      IFile file;
      String path;
      int mode;
      IFileTask2Callback cb;

      OpenTask(FileSystem fileSystem) {
         super(fileSystem);
      }

      public Object call() throws Exception {
         return this.file.open(this.path, this.mode);
      }

      public void handleResult(Object result) {
         if (this.cb != null) {
            this.cb.onFileTaskFinished(this.file, result);
         }
      }

      public void done() {
         if ((this.mode & 5) == 5) {
            this.fileSystem.closeAsync(this.file, null);
         }

         this.file = null;
         this.path = null;
         this.cb = null;
      }
   }
}
