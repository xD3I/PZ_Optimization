package pzopt;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Windows-only, opt-in per-thread placement. No process affinity or processor-count changes. */
final class WindowsCorePlacement {
   private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
   private static final ValueLayout.OfLong J = ValueLayout.JAVA_LONG;
   private static final ValueLayout.OfShort S = ValueLayout.JAVA_SHORT;
   private static final AddressLayout P = ValueLayout.ADDRESS;
   private static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
   private static final VarHandle CAPTURED_ERROR = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
   private static final int THREAD_ACCESS = 0x0800 | 0x0020; // QUERY_LIMITED_INFORMATION | SET_INFORMATION
   private static final int ERROR_NO_MORE_FILES = 18, ERROR_INVALID_PARAMETER = 87, STILL_ACTIVE = 259;
   private final MethodHandle currentThread, currentTid, currentPid, snapshot, threadFirst, threadNext, openThread,
         closeHandle, threadTimes, threadOwner, threadDescription, localFree, threadAffinity, setThreadAffinity, exitCode,
         lastError;
   private final WindowsCorePolicy.Topology topology;
   private final int pid;
   private final Map<Integer, Registration> registered = new HashMap<>();
   private final Map<Integer, Applied> applied = new HashMap<>();
   private volatile boolean disabled;
   private volatile String counts = "critical=0 background=0 pause=0 unknown=0";

   private record Registration(long creation, WindowsCorePolicy.Kind kind, String name) { }
   private record Applied(long creation, WindowsCorePolicy.Kind kind, String description, WindowsCorePolicy.GroupMask mask) { }
   private record NativeThread(int tid, long creation, MemorySegment handle, String description) { }

   /** Placement for an already validated topology ({@link #detect}); no affinity API is used before it passed. */
   WindowsCorePlacement(WindowsCorePolicy.Topology topology) throws Throwable {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
      currentThread = bind(linker, kernel, "GetCurrentThread", P);
      currentTid = bind(linker, kernel, "GetCurrentThreadId", I);
      currentPid = bind(linker, kernel, "GetCurrentProcessId", I);
      snapshot = bind(linker, kernel, "CreateToolhelp32Snapshot", P, I, I);
      // The FFM transition can overwrite the native last-error slot before a separate GetLastError downcall.
      threadFirst = linker.downcallHandle(kernel.find("Thread32First").orElseThrow(),
            FunctionDescriptor.of(I, P, P), Linker.Option.captureCallState("GetLastError"));
      threadNext = linker.downcallHandle(kernel.find("Thread32Next").orElseThrow(),
            FunctionDescriptor.of(I, P, P), Linker.Option.captureCallState("GetLastError"));
      openThread = linker.downcallHandle(kernel.find("OpenThread").orElseThrow(),
            FunctionDescriptor.of(P, I, I, I), Linker.Option.captureCallState("GetLastError"));
      closeHandle = bind(linker, kernel, "CloseHandle", I, P);
      threadTimes = bind(linker, kernel, "GetThreadTimes", I, P, P, P, P, P);
      threadOwner = bind(linker, kernel, "GetProcessIdOfThread", I, P);
      threadDescription = bind(linker, kernel, "GetThreadDescription", I, P, P);
      localFree = bind(linker, kernel, "LocalFree", P, P);
      threadAffinity = bind(linker, kernel, "GetThreadGroupAffinity", I, P, P);
      setThreadAffinity = bind(linker, kernel, "SetThreadGroupAffinity", I, P, P, P);
      exitCode = bind(linker, kernel, "GetExitCodeThread", I, P, P);
      lastError = bind(linker, kernel, "GetLastError", I);
      pid = (int) currentPid.invokeExact();
      this.topology = topology;
   }

   private static MethodHandle bind(Linker linker, SymbolLookup symbols, String name, MemoryLayout result, MemoryLayout... args) {
      return linker.downcallHandle(symbols.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
   }

   /** Two L3 groups plus their physical cores, validated against one unrestricted processor group. Read-only queries. */
   static WindowsCorePolicy.Topology detect() throws Throwable {
      if (P.byteSize() != 8) throw new IllegalArgumentException("Windows CCD placement requires a 64-bit JVM");
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
      MethodHandle topologyApi = linker.downcallHandle(kernel.find("GetLogicalProcessorInformationEx").orElseThrow(),
            FunctionDescriptor.of(I, I, P, P), Linker.Option.captureCallState("GetLastError"));
      MethodHandle processAffinity = linker.downcallHandle(kernel.find("GetProcessAffinityMask").orElseThrow(),
            FunctionDescriptor.of(I, P, P, P), Linker.Option.captureCallState("GetLastError"));
      MethodHandle groupCount = bind(linker, kernel, "GetActiveProcessorGroupCount", S);
      MethodHandle activeCount = bind(linker, kernel, "GetActiveProcessorCount", I, S);
      MethodHandle currentProcess = bind(linker, kernel, "GetCurrentProcess", P);
      int groups = Short.toUnsignedInt((short) groupCount.invokeExact());
      try (Arena arena = Arena.ofConfined()) {
         MemorySegment callState = arena.allocate(CALL_STATE);
         List<WindowsCorePolicy.Cache> caches = WindowsCorePolicy.readCaches(relation(topologyApi, arena, callState, 2, "cache"));
         List<WindowsCorePolicy.GroupMask> cores = WindowsCorePolicy.readCores(relation(topologyApi, arena, callState, 0, "core"));
         // A single GROUP_AFFINITY cannot express wide STW/unknown placement across processor groups: reject it.
         if (groups != 1) throw new IllegalArgumentException("multiple processor groups unsupported: groups=" + groups + " L3=" + caches);
         MemorySegment processMask = arena.allocate(J), systemMask = arena.allocate(J);
         MemorySegment process = (MemorySegment) currentProcess.invokeExact();
         if ((int) processAffinity.invokeExact(callState, process, processMask, systemMask) == 0) {
            throw new IllegalStateException("GetProcessAffinityMask failed, Win32 error=" + CAPTURED_ERROR.get(callState, 0L));
         }
         long available = systemMask.get(J, 0);
         int processors = (int) activeCount.invokeExact((short) 0xffff);
         if (Long.bitCount(available) != processors || processMask.get(J, 0) != available) {
            throw new IllegalArgumentException("restricted/unknown process CPU availability: process=0x"
                  + Long.toUnsignedString(processMask.get(J, 0), 16) + " system=0x" + Long.toUnsignedString(available, 16)
                  + " activeCPUs=" + processors + " L3=" + caches);
         }
         // With exactly one active processor group its OS group number is zero, not a guessed CCD index.
         return WindowsCorePolicy.validate(caches, cores, new WindowsCorePolicy.GroupMask(0, available), groups);
      }
   }

   /** One GetLogicalProcessorInformationEx relationship, copied out of the sizing/fill protocol. */
   private static java.nio.ByteBuffer relation(MethodHandle api, Arena arena, MemorySegment callState, int relationship,
         String what) throws Throwable {
      MemorySegment length = arena.allocate(I);
      int ok = (int) api.invokeExact(callState, relationship, MemorySegment.NULL, length);
      int error = (int) CAPTURED_ERROR.get(callState, 0L);
      if (ok != 0 || error != 122) throw new IllegalStateException(what + " topology sizing failed, Win32 error=" + error);
      long bytes = Integer.toUnsignedLong(length.get(I, 0));
      if (bytes == 0 || bytes > 16 * 1024 * 1024) throw new IllegalArgumentException("invalid " + what + " topology length=" + bytes);
      MemorySegment buffer = arena.allocate(bytes, 8);
      if ((int) api.invokeExact(callState, relationship, buffer, length) == 0) {
         throw new IllegalStateException(what + " topology failed, Win32 error=" + CAPTURED_ERROR.get(callState, 0L));
      }
      long returned = Integer.toUnsignedLong(length.get(I, 0));
      if (returned > bytes) throw new IllegalArgumentException(what + " topology changed during detection");
      return buffer.asSlice(0, returned).asByteBuffer();
   }

   /** The caller itself provides an unambiguous Java-role -> OS-thread-ID mapping, even on JVMs without descriptions. */
   synchronized void registerCurrent(boolean critical) throws Throwable {
      if (disabled) return;
      String name = Thread.currentThread().getName();
      WindowsCorePolicy.Kind kind = critical ? WindowsCorePolicy.Kind.CRITICAL : WindowsCorePolicy.classify(name);
      MemorySegment handle = (MemorySegment) currentThread.invokeExact();
      int tid = (int) currentTid.invokeExact();
      try (Arena arena = Arena.ofConfined()) {
         MemorySegment times = arena.allocate(32, 8), mask = arena.allocate(16, 8);
         long creation = creation(handle, times);
         registered.put(tid, new Registration(creation, kind, name));
         apply(new NativeThread(tid, creation, handle, name), kind, mask);
      }
   }

   /** Re-read OS descriptions and current affinity every pass, including existing native threads and reused IDs. */
   synchronized void scan() throws Throwable {
      if (disabled) return;
      try (Arena arena = Arena.ofConfined()) {
         MemorySegment entry = arena.allocate(28, 4), times = arena.allocate(32, 8);
         MemorySegment scratch = arena.allocate(16, 8), descriptionPointer = arena.allocate(P);
         List<NativeThread> threads = threads(entry, times, descriptionPointer, true);
         try {
            Map<String, Integer> names = new HashMap<>();
            for (NativeThread thread : threads) names.merge(thread.description, 1, Integer::sum);
            Set<Integer> seen = new HashSet<>();
            int critical = 0, background = 0, pause = 0, unknown = 0;
            for (NativeThread thread : threads) {
               WindowsCorePolicy.Kind kind = WindowsCorePolicy.classify(thread.description, names.get(thread.description));
               Registration own = registered.get(thread.tid);
               if (own != null && own.creation != thread.creation) {
                  registered.remove(thread.tid);
                  own = null;
               }
               if (own != null && (own.kind == WindowsCorePolicy.Kind.CRITICAL
                     || kind == WindowsCorePolicy.Kind.UNKNOWN
                     && (thread.description.isEmpty() || thread.description.equals(own.name)))) {
                  kind = own.kind; // a changed, unproved description cannot retain a stale registered mapping
               }
               if (!apply(thread, kind, scratch)) continue; // exited during enumeration
               seen.add(thread.tid);
               switch (kind) {
                  case CRITICAL -> critical++;
                  case BACKGROUND -> background++;
                  case PAUSE -> pause++;
                  case UNKNOWN -> unknown++;
               }
            }
            registered.keySet().retainAll(seen);
            applied.keySet().retainAll(seen);
            counts = "critical=" + critical + " background=" + background + " pause=" + pause + " unknown=" + unknown;
         } finally {
            for (NativeThread thread : threads) close(thread.handle);
         }
      }
   }

   private List<NativeThread> threads(MemorySegment entry, MemorySegment times, MemorySegment descriptionPointer, boolean strict) throws Throwable {
      List<NativeThread> result = new ArrayList<>();
      MemorySegment snap = (MemorySegment) snapshot.invokeExact(4, 0); // TH32CS_SNAPTHREAD, all processes
      if (snap.address() == -1L) throw failure("thread snapshot");
      try (Arena errors = Arena.ofConfined()) {
         MemorySegment callState = errors.allocate(CALL_STATE);
         entry.set(I, 0, 28);
         int ok = (int) threadFirst.invokeExact(callState, snap, entry);
         if (ok == 0 && (int) CAPTURED_ERROR.get(callState, 0L) != ERROR_NO_MORE_FILES) {
            throw new IllegalStateException("Thread32First failed, Win32 error=" + CAPTURED_ERROR.get(callState, 0L));
         }
         while (ok != 0) {
            if (entry.get(I, 0) < 16) throw new IllegalArgumentException("truncated THREADENTRY32");
            if (entry.get(I, 12) == pid) {
               int tid = entry.get(I, 8);
               MemorySegment handle = (MemorySegment) openThread.invokeExact(callState, THREAD_ACCESS, 0, tid);
               if (handle.address() == 0) {
                  int error = (int) CAPTURED_ERROR.get(callState, 0L);
                  if (error != ERROR_INVALID_PARAMETER) {
                     if (strict) throw new IllegalStateException("OpenThread tid=" + tid + " failed, Win32 error=" + error);
                     Log.warn("corePlacement=dual-ccd: cannot restore inaccessible tid=" + tid + " Win32 error=" + error);
                  }
               } else {
                  boolean retained = false;
                  try {
                     // A snapshot's tid may already have been reused by another process.
                     int owner = (int) threadOwner.invokeExact(handle);
                     if (owner == 0 && strict) throw failure("GetProcessIdOfThread tid=" + tid);
                     if (owner == pid && live(handle, times)) {
                        long creation = creation(handle, times);
                        result.add(new NativeThread(tid, creation, handle, strict ? description(handle, descriptionPointer) : ""));
                        retained = true;
                     }
                  } catch (Throwable t) {
                     if (strict) throw t;
                     Log.warn("corePlacement=dual-ccd: cannot inspect tid=" + tid + " for wide-mask restoration (" + t + ")");
                  } finally {
                     if (!retained) close(handle);
                  }
               }
            }
            entry.set(I, 0, 28);
            ok = (int) threadNext.invokeExact(callState, snap, entry);
         }
         if ((int) CAPTURED_ERROR.get(callState, 0L) != ERROR_NO_MORE_FILES) {
            throw new IllegalStateException("Thread32Next failed, Win32 error=" + CAPTURED_ERROR.get(callState, 0L));
         }
         return result;
      } catch (Throwable t) {
         for (NativeThread thread : result) close(thread.handle);
         throw t;
      } finally {
         close(snap);
      }
   }

   private String description(MemorySegment handle, MemorySegment pointer) throws Throwable {
      pointer.set(P, 0, MemorySegment.NULL);
      int hr = (int) threadDescription.invokeExact(handle, pointer);
      MemorySegment text = pointer.get(P, 0);
      if (text.address() == 0) return "";
      try {
         if (hr < 0) return "";
         MemorySegment utf16 = text.reinterpret(8192);
         StringBuilder name = new StringBuilder();
         for (int i = 0; i < 4096; i++) {
            char c = (char) Short.toUnsignedInt(utf16.get(S, i * 2L));
            if (c == 0) return name.toString();
            name.append(c);
         }
         return ""; // Do not classify a truncated name.
      } finally {
         MemorySegment freed = (MemorySegment) localFree.invokeExact(text);
      }
   }

   private long creation(MemorySegment handle, MemorySegment times) throws Throwable {
      if ((int) threadTimes.invokeExact(handle, times, times.asSlice(8, 8), times.asSlice(16, 8), times.asSlice(24, 8)) == 0) {
         throw failure("GetThreadTimes");
      }
      return times.get(J, 0);
   }

   private boolean live(MemorySegment handle, MemorySegment scratch) throws Throwable {
      if ((int) exitCode.invokeExact(handle, scratch) == 0) throw failure("GetExitCodeThread");
      return scratch.get(I, 0) == STILL_ACTIVE;
   }

   private boolean apply(NativeThread thread, WindowsCorePolicy.Kind kind, MemorySegment scratch) throws Throwable {
      WindowsCorePolicy.GroupMask wanted = topology.maskFor(kind);
      if ((int) threadAffinity.invokeExact(thread.handle, scratch) == 0) {
         if (!live(thread.handle, scratch)) return false;
         throw failure("GetThreadGroupAffinity tid=" + thread.tid);
      }
      long currentMask = scratch.get(J, 0);
      int currentGroup = Short.toUnsignedInt(scratch.get(S, 8));
      Applied previous = applied.get(thread.tid);
      if (currentGroup != wanted.group() || currentMask != wanted.mask()) {
         scratch.fill((byte) 0);
         scratch.set(J, 0, wanted.mask());
         scratch.set(S, 8, (short) wanted.group());
         if ((int) setThreadAffinity.invokeExact(thread.handle, scratch, MemorySegment.NULL) == 0) {
            if (!live(thread.handle, scratch)) return false;
            throw failure("SetThreadGroupAffinity tid=" + thread.tid + " mask=" + wanted);
         }
      }
      if (previous == null || previous.creation != thread.creation || previous.kind != kind
            || !previous.description.equals(thread.description) || !previous.mask.equals(wanted)) {
         String reason = kind == WindowsCorePolicy.Kind.UNKNOWN ? " (unproved/ambiguous OS description; fail-closed wide)" : "";
         Registration mapping = registered.get(thread.tid);
         if (mapping != null && mapping.creation == thread.creation) reason += " (registered Java role=\"" + mapping.name + "\")";
         Log.info("corePlacement=dual-ccd: tid=" + Integer.toUnsignedString(thread.tid) + " created="
               + Long.toUnsignedString(thread.creation) + " description=\"" + thread.description.replace('\n', ' ').replace('\r', ' ')
               + "\" class=" + kind.name().toLowerCase(java.util.Locale.ROOT) + " mask=" + wanted + reason);
         applied.put(thread.tid, new Applied(thread.creation, kind, thread.description, wanted));
      }
      return true;
   }

   /** A failed placement must not leave background-inherited masks trapping a later critical thread on the background CCD. */
   synchronized void disable() {
      if (disabled) return;
      disabled = true;
      try (Arena arena = Arena.ofConfined()) {
         MemorySegment entry = arena.allocate(28, 4), times = arena.allocate(32, 8), pointer = arena.allocate(P);
         MemorySegment mask = arena.allocate(16, 8);
         mask.set(J, 0, topology.all().mask());
         mask.set(S, 8, (short) topology.all().group());
         List<NativeThread> threads = threads(entry, times, pointer, false);
         try {
            for (NativeThread thread : threads) {
               if ((int) setThreadAffinity.invokeExact(thread.handle, mask, MemorySegment.NULL) == 0 && live(thread.handle, times)) {
                  Log.warn("corePlacement=dual-ccd: could not restore wide mask for tid=" + thread.tid);
               }
            }
         } finally {
            for (NativeThread thread : threads) close(thread.handle);
         }
      } catch (Throwable t) {
         Log.warn("corePlacement=dual-ccd: wide-mask restoration failed (" + t + ")");
      }
      registered.clear();
      applied.clear();
   }

   String describe() {
      return topology + " " + counts + " gc_pause=both unknown=both";
   }

   private IllegalStateException failure(String operation) throws Throwable {
      return new IllegalStateException(operation + " failed, Win32 error=" + (int) lastError.invokeExact());
   }

   private void close(MemorySegment handle) throws Throwable {
      int closed = (int) closeHandle.invokeExact(handle);
   }
}
