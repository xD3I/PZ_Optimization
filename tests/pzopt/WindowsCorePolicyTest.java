package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** No native calls: safe on every test host, and no game/install/save access. */
public final class WindowsCorePolicyTest {
   private static final long MIB = 1024L * 1024;

   private WindowsCorePolicyTest() { }

   public static void main(String[] args) {
      cacheSizeNotCpuNumberSelectsCcd();
      equalCachesUseCcd0();
      workerLimitCountsPhysicalCores();
      highBitAndSparseMasksRemainIntact();
      invalidTopologiesFailClosedWithEvidence();
      nativeCacheRecordsDecodeByGroupMasks();
      malformedNativeRecordsFailClosed();
      nativeCoreRecordsDecode();
      criticalThreadsShareThePrimaryCcd();
      knownBackgroundNamesDoNotCaptureFrameWorkers();
      unknownAndAmbiguousDescriptionsStayWide();
      pauseWorkersStayOnBothCcds();
   }

   private static WindowsCorePolicy.Cache cache(long mib, int group, long mask) {
      return new WindowsCorePolicy.Cache(mib * MIB, List.of(new WindowsCorePolicy.GroupMask(group, mask)));
   }

   /** One physical core per {@code perCore} consecutive set bits of {@code mask} (SMT siblings are enumerated adjacently). */
   private static List<WindowsCorePolicy.GroupMask> cores(int group, long mask, int perCore) {
      List<WindowsCorePolicy.GroupMask> result = new ArrayList<>();
      while (mask != 0) {
         long core = 0;
         for (int i = 0; i < perCore; i++) {
            core |= Long.lowestOneBit(mask);
            mask &= ~Long.lowestOneBit(mask);
         }
         result.add(new WindowsCorePolicy.GroupMask(group, core));
      }
      return result;
   }

   private static WindowsCorePolicy.Topology validate(long available, int perCore, WindowsCorePolicy.Cache... caches) {
      return WindowsCorePolicy.validate(List.of(caches), cores(0, available, perCore), new WindowsCorePolicy.GroupMask(0, available), 1);
   }

   private static WindowsCorePolicy.Topology topology() {
      return validate(0xaa55, 1, cache(32, 0, 0xaa00), cache(96, 0, 0x0055));
   }

   private static void cacheSizeNotCpuNumberSelectsCcd() {
      var result = validate(0xffff, 1, cache(32, 0, 0x00ff), cache(96, 0, 0xff00));
      Check.check(result.primary().mask() == 0xff00, "larger L3 is primary even on higher logical CPU indexes");
      Check.check(result.background().mask() == 0xff, "lower CPU indexes are not automatically primary when L3 differs");
      Check.check(result.primaryBytes() == 96 * MIB && result.backgroundBytes() == 32 * MIB, "preserves OS cache sizes");
      var reversed = validate(0xffff, 1, cache(96, 0, 0xff00), cache(32, 0, 0x00ff));
      Check.check(reversed.equals(result), "cache enumeration order cannot change CCD assignment");
      Check.check(result.toString().contains("group0:0xff00") && result.toString().contains("96MiB"),
            "summary contains the actual mask and discovered cache size");
   }

   private static void equalCachesUseCcd0() {
      var plain = validate(0xffff, 1, cache(32, 0, 0xff00), cache(32, 0, 0x00ff));
      Check.check(plain.primary().mask() == 0x00ff && plain.background().mask() == 0xff00,
            "equal L3 (non-X3D dual CCD): CCD0, holding the lowest logical CPU, is primary");
      var dualX3d = validate(0xffff, 1, cache(96, 0, 0x00ff), cache(96, 0, 0xff00));
      Check.check(dualX3d.primary().mask() == 0x00ff, "two V-Cache CCDs are equal too: CCD0 is primary");
   }

   private static void workerLimitCountsPhysicalCores() {
      var eight = validate(0xffff, 1, cache(96, 0, 0x00ff), cache(32, 0, 0xff00));
      Check.check(eight.primaryCores() == 8 && eight.workerLimit() == 6, "8+8 without SMT: six workers beside game/render");
      var eightSmt = validate(0xffffffffL, 2, cache(96, 0, 0xffff), cache(32, 0, 0xffff0000L));
      Check.check(eightSmt.primaryCores() == 8 && eightSmt.workerLimit() == 6, "8+8 with SMT counts physical cores, not 16 CPUs");
      var six = validate(0xfff, 1, cache(32, 0, 0x03f), cache(32, 0, 0xfc0));
      Check.check(six.primaryCores() == 6 && six.workerLimit() == 4 && six.primary().mask() == 0x03f,
            "6+6 without SMT (12-core dual CCD): four workers");
      var sixSmt = validate(0xffffff, 2, cache(96, 0, 0xfff000), cache(32, 0, 0x000fff));
      Check.check(sixSmt.primaryCores() == 6 && sixSmt.workerLimit() == 4 && sixSmt.primary().mask() == 0xfff000,
            "6+6 with SMT (7900X3D/9900X3D): V-Cache CCD primary, four workers");
      Check.check(sixSmt.maskFor(WindowsCorePolicy.Kind.CRITICAL).equals(sixSmt.primary()),
            "game/render and workers share the whole primary CCD");
   }

   private static void highBitAndSparseMasksRemainIntact() {
      long larger = Long.MIN_VALUE | 0x05, smaller = (1L << 41) | 0x0a;
      var result = WindowsCorePolicy.validate(List.of(cache(96, 7, larger), cache(32, 7, smaller)),
            cores(7, larger | smaller, 1), new WindowsCorePolicy.GroupMask(7, larger | smaller), 1);
      Check.check(result.primary().mask() == larger && result.background().mask() == smaller, "unsigned bit 63 and sparse masks retained");
      Check.check(result.primary().group() == 7, "pure policy preserves the reported processor-group number");
      Check.check(result.all().mask() == (larger | smaller), "wide affinity is the union, not a contiguous guessed range");
   }

   private static void reject(List<WindowsCorePolicy.Cache> caches, List<WindowsCorePolicy.GroupMask> cores,
         WindowsCorePolicy.GroupMask available, int groups, String reason) {
      try {
         WindowsCorePolicy.validate(caches, cores, available, groups);
         throw new AssertionError("accepted invalid topology: " + reason);
      } catch (IllegalArgumentException expected) {
         Check.check(expected.getMessage().contains("L3=") && expected.getMessage().contains("available="),
               "fail-closed diagnostics include masks for " + reason);
      }
   }

   private static void reject(List<WindowsCorePolicy.Cache> caches, WindowsCorePolicy.GroupMask available, int groups, String reason) {
      reject(caches, cores(0, 0xffff, 1), available, groups, reason);
   }

   private static void invalidTopologiesFailClosedWithEvidence() {
      var all = new WindowsCorePolicy.GroupMask(0, 0xffff);
      reject(List.of(), all, 1, "unknown caches");
      reject(List.of(cache(96, 0, 0xffff)), all, 1, "single cache");
      reject(List.of(cache(96, 0, 0x00ff), cache(32, 0, 0xff00), cache(32, 0, 0x10000)), all, 1, "three caches");
      reject(List.of(cache(96, 0, 0x0fff), cache(32, 0, 0xff00)), all, 1, "overlap");
      reject(List.of(cache(96, 0, 0x000f), cache(32, 0, 0xff00)), all, 1, "incomplete mask coverage");
      reject(List.of(cache(96, 0, 0), cache(32, 0, 0xffff)), all, 1, "empty mask");
      reject(List.of(cache(96, 0, 0x00ff), cache(0, 0, 0xff00)), all, 1, "unknown cache size");
      reject(List.of(cache(96, 0, 0x00ff), cache(32, 1, 0xff00)), all, 1, "different processor groups");
      reject(List.of(cache(96, 0, 0x00ff), cache(32, 0, 0xff00)), all, 2, "multiple active groups");
      reject(List.of(new WindowsCorePolicy.Cache(96 * MIB, List.of(new WindowsCorePolicy.GroupMask(0, 0xff),
                  new WindowsCorePolicy.GroupMask(1, 0xff))), cache(32, 0, 0xff00)), all, 1, "multi-group shared cache");
      reject(List.of(cache(96, 0, 0x00ff), cache(32, 0, 0xff00)), null, 1, "unknown processor availability");
      reject(List.of(cache(96, 0, 0x00ff), cache(32, 0, 0xff00)), new WindowsCorePolicy.GroupMask(0, 0), 1, "zero availability");
      var ccds = List.of(cache(96, 0, 0x00ff), cache(32, 0, 0xff00));
      List<WindowsCorePolicy.GroupMask> straddling = new ArrayList<>(cores(0, 0xfe7f, 1));
      straddling.add(new WindowsCorePolicy.GroupMask(0, 0x0180));
      reject(ccds, straddling, all, 1, "a physical core across both L3 groups");
      reject(ccds, cores(0, 0x7fff, 1), all, 1, "physical cores missing a CPU");
      List<WindowsCorePolicy.GroupMask> overlapping = new ArrayList<>(cores(0, 0xffff, 1));
      overlapping.add(new WindowsCorePolicy.GroupMask(0, 0x1));
      reject(ccds, overlapping, all, 1, "overlapping physical cores");
      reject(ccds, List.of(), all, 1, "no physical core records");
   }

   private static void putCache(ByteBuffer b, int offset, int level, int mib, int groupCount, int group, long mask) {
      b.putInt(offset, 2); // RelationCache
      b.putInt(offset + 4, 56);
      b.put(offset + 8, (byte) level);
      b.putInt(offset + 12, mib * (int) MIB);
      b.putInt(offset + 16, 0); // CacheUnified
      b.putShort(offset + 38, (short) groupCount);
      b.putLong(offset + 40, mask);
      b.putShort(offset + 48, (short) group);
   }

   private static void putCore(ByteBuffer b, int offset, int groupCount, int group, long mask) {
      b.putInt(offset, 0); // RelationProcessorCore
      b.putInt(offset + 4, 48);
      b.putShort(offset + 30, (short) groupCount);
      b.putLong(offset + 32, mask);
      b.putShort(offset + 40, (short) group);
   }

   private static ByteBuffer buffer(int size) {
      return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
   }

   private static void nativeCacheRecordsDecodeByGroupMasks() {
      ByteBuffer bytes = buffer(3 * 56);
      putCache(bytes, 0, 2, 1, 1, 0, 0x03); // L2 is not a CCD
      putCache(bytes, 56, 3, 32, 1, 0, 0xaa00);
      putCache(bytes, 112, 3, 96, 1, 0, 0x0055);
      var parsed = WindowsCorePolicy.readCaches(bytes);
      Check.check(parsed.size() == 2, "only shared L3 records become cache groups");
      Check.check(WindowsCorePolicy.validate(parsed, cores(0, 0xaa55, 1), new WindowsCorePolicy.GroupMask(0, 0xaa55), 1).equals(topology()),
            "native buffer uses size and group affinity fields, not WMI's aggregate L3");
      bytes.putShort(56 + 38, (short) 0);
      Check.check(WindowsCorePolicy.readCaches(bytes).equals(parsed), "legacy reserved GroupCount zero means one GroupMask");
      ByteBuffer multi = buffer(72);
      putCache(multi, 0, 3, 96, 2, 0, 0xff);
      multi.putInt(4, 72);
      multi.putLong(56, 0xff00);
      multi.putShort(64, (short) 1);
      Check.check(WindowsCorePolicy.readCaches(multi).get(0).groups().size() == 2, "multi-group cache is decoded, not silently truncated");
   }

   private static void badRecord(ByteBuffer bytes, String reason) {
      try {
         WindowsCorePolicy.readCaches(bytes);
         throw new AssertionError("accepted malformed native buffer: " + reason);
      } catch (IllegalArgumentException expected) {
         Check.check(!expected.getMessage().isBlank(), "native decoding reports " + reason);
      }
   }

   private static void malformedNativeRecordsFailClosed() {
      badRecord(buffer(7), "short header");
      ByteBuffer bytes = buffer(56);
      putCache(bytes, 0, 3, 96, 1, 0, 0xff);
      bytes.putInt(4, 0);
      badRecord(bytes, "zero record length");
      bytes.putInt(4, 57);
      badRecord(bytes, "record exceeds buffer");
      bytes.putInt(4, 56);
      bytes.putShort(38, (short) 2);
      badRecord(bytes, "declared masks exceed record");
      bytes.putShort(38, (short) 1);
      bytes.putInt(16, 1);
      badRecord(bytes, "non-unified L3");
      bytes.putInt(16, 0);
      bytes.putInt(0, 0);
      badRecord(bytes, "wrong relationship type");
   }

   private static void badCores(ByteBuffer bytes, String reason) {
      try {
         WindowsCorePolicy.readCores(bytes);
         throw new AssertionError("accepted malformed core buffer: " + reason);
      } catch (IllegalArgumentException expected) {
         Check.check(!expected.getMessage().isBlank(), "core decoding reports " + reason);
      }
   }

   private static void nativeCoreRecordsDecode() {
      ByteBuffer bytes = buffer(2 * 48);
      putCore(bytes, 0, 1, 0, 0x3); // SMT siblings: one physical core
      putCore(bytes, 48, 1, 0, Long.MIN_VALUE);
      var parsed = WindowsCorePolicy.readCores(bytes);
      Check.check(parsed.equals(List.of(new WindowsCorePolicy.GroupMask(0, 0x3), new WindowsCorePolicy.GroupMask(0, Long.MIN_VALUE))),
            "one GROUP_AFFINITY per physical core, SMT siblings and bit 63 kept");
      bytes.putShort(30, (short) 2);
      badCores(bytes, "core spanning processor groups");
      bytes.putShort(30, (short) 1);
      bytes.putInt(4, 40);
      badCores(bytes, "short core record");
      bytes.putInt(4, 48);
      bytes.putInt(0, 2);
      badCores(bytes, "wrong relationship type");
      badCores(buffer(7), "short header");
   }

   private static void criticalThreadsShareThePrimaryCcd() {
      for (String name : new String[]{"GameThread", "Render Thread", "pzopt-render", "Lighting Thread", "pzopt-input",
            "pzopt-vispoly", "pzopt-frame-0", "pzopt-frame-14", "pzopt-chardraw-13", "pzopt-slotinit-7",
            "NVIDIA OpenGL Driver", "nvoglv64 worker", "java:gl0", "java:gdrv2", "java:cs1"}) {
         Check.check(WindowsCorePolicy.classify(name) == WindowsCorePolicy.Kind.CRITICAL, "game/render/frame thread is critical: " + name);
         Check.check(topology().maskFor(WindowsCorePolicy.classify(name)).equals(topology().primary()),
               "critical thread uses the whole primary CCD: " + name);
      }
   }

   private static void knownBackgroundNamesDoNotCaptureFrameWorkers() {
      for (String name : new String[]{"pzopt-file-7", "pzopt-recalc-3", "C1 CompilerThread0", "C2 CompilerThread9",
            "pzopt-update-check", "pzopt-update-install", "pzopt-update-5", "pzopt-overlay-stacks", "pzopt-overlay-util",
            "pzopt-cores", "pzopt-input-log", "World Streamer", "G1 Conc#1", "G1 Refine#2", "FMOD mixer",
            "FileSystemWatchService", "WorldReuser", "ZUncommitter#0", "ZDirector", "ZStat"}) {
         Check.check(WindowsCorePolicy.classify(name) == WindowsCorePolicy.Kind.BACKGROUND, "proved background role: " + name);
         Check.check(topology().maskFor(WindowsCorePolicy.classify(name)).equals(topology().background()), "background uses the other CCD: " + name);
      }
   }

   private static void unknownAndAmbiguousDescriptionsStayWide() {
      for (String name : new String[]{"", " ", "pool-1-thread-1", "Thread-9", "main", "unknown GL worker",
            "pzopt-frame-", "pzopt-frame-0-extra", "pzopt-chardraw", "pzopt-file-", "pzopt-recalc-0-frame",
            "new-mod-frame-worker", "pzopt-future-background", "GC Thread#x"}) {
         Check.check(WindowsCorePolicy.classify(name) == WindowsCorePolicy.Kind.UNKNOWN, "unproved/truncated name is not background: " + name);
         Check.check(topology().maskFor(WindowsCorePolicy.classify(name)).equals(topology().all()), "unknown never goes to the background CCD: " + name);
      }
      Check.check(WindowsCorePolicy.classify(null) == WindowsCorePolicy.Kind.UNKNOWN, "missing OS description fails closed");
      Check.check(WindowsCorePolicy.classify("Render Thread", 2) == WindowsCorePolicy.Kind.UNKNOWN, "duplicate singleton descriptions are ambiguous");
      Check.check(WindowsCorePolicy.classify("pzopt-file-0", 2) == WindowsCorePolicy.Kind.UNKNOWN, "even duplicate background descriptions fail closed");
      Check.check(WindowsCorePolicy.classify("pzopt-frame-0", 1) == WindowsCorePolicy.Kind.CRITICAL, "unique OS description can be classified");
      Check.check(WindowsCorePolicy.classify("pzopt-file-0", 0) == WindowsCorePolicy.Kind.UNKNOWN, "missing mapping cannot narrow affinity");
   }

   private static void pauseWorkersStayOnBothCcds() {
      for (String name : new String[]{"GC Thread#0", "GC Thread 3", "G1 Full GC Worker#2", "VM Thread",
            "ZWorkerOld#0", "ZWorkerYoung#3", "ZDriverMinor", "ZDriverMajor"}) {
         Check.check(WindowsCorePolicy.classify(name) == WindowsCorePolicy.Kind.PAUSE, "STW role explicit: " + name);
         Check.check(topology().maskFor(WindowsCorePolicy.classify(name)).equals(topology().all()), "GC pause uses both CCDs: " + name);
      }
   }
}
