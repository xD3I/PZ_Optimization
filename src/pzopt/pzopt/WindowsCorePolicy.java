package pzopt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure validation/classification for Windows dual-CCD placement; never infers CCDs from CPU numbers. */
final class WindowsCorePolicy {
   enum Kind { CRITICAL, PAUSE, BACKGROUND, UNKNOWN }
   record GroupMask(int group, long mask) {
      @Override public String toString() {
         return "group" + group + ":0x" + Long.toUnsignedString(mask, 16);
      }
   }
   record Cache(long bytes, List<GroupMask> groups) { }
   /**
    * primary: the game, render and frame-completion threads share this CCD (the larger L3, else the one holding the lowest
    * logical CPU); background: known background work; all: both CCDs (GC pauses, unproved threads).
    */
   record Topology(GroupMask primary, GroupMask background, GroupMask all, long primaryBytes, long backgroundBytes,
                   int primaryCores) {
      GroupMask maskFor(Kind kind) {
         return switch (kind) {
            case CRITICAL -> primary;
            case BACKGROUND -> background;
            case PAUSE, UNKNOWN -> all;
         };
      }
      /** Worker pools fit the primary CCD's physical cores, two of which the game and render threads keep. */
      int workerLimit() {
         return Math.max(1, primaryCores - 2);
      }
      @Override public String toString() {
         return "primary=" + primary + " l3=" + primaryBytes / (1024 * 1024) + "MiB cores=" + primaryCores
               + " workers<=" + workerLimit() + " background=" + background
               + " l3=" + backgroundBytes / (1024 * 1024) + "MiB all=" + all;
      }
   }

   private WindowsCorePolicy() { }

   /** Reject an incomplete/overlapping topology instead of silently intersecting it with the process mask. */
   static Topology validate(List<Cache> caches, List<GroupMask> cores, GroupMask available, int processorGroups) {
      String evidence = "L3=" + caches + ", cores=" + cores.size() + ", available=" + available + ", processorGroups=" + processorGroups;
      if (processorGroups != 1 || available == null || available.mask == 0) {
         throw new IllegalArgumentException("requires one known processor group; " + evidence);
      }
      if (caches.size() != 2) {
         throw new IllegalArgumentException("requires exactly two shared L3 caches; " + evidence);
      }
      Cache first = caches.get(0), second = caches.get(1);
      if (first.groups.size() != 1 || second.groups.size() != 1) {
         throw new IllegalArgumentException("multi-group L3 affinity is unsupported; " + evidence);
      }
      GroupMask a = first.groups.get(0), b = second.groups.get(0);
      if (a.group != available.group || b.group != available.group || a.mask == 0 || b.mask == 0
            || (a.mask & b.mask) != 0 || (a.mask | b.mask) != available.mask) {
         throw new IllegalArgumentException("L3 masks must be nonempty, disjoint and cover all available CPUs; " + evidence);
      }
      if (first.bytes <= 0 || second.bytes <= 0) {
         throw new IllegalArgumentException("unknown L3 cache size; " + evidence);
      }
      // V-Cache CCD when the sizes differ; otherwise CCD0, the lowest logical CPUs (the masks are disjoint).
      boolean firstPrimary = first.bytes != second.bytes ? first.bytes > second.bytes
            : Long.numberOfTrailingZeros(a.mask) < Long.numberOfTrailingZeros(b.mask);
      Cache primaryCache = firstPrimary ? first : second, backgroundCache = firstPrimary ? second : first;
      GroupMask primary = firstPrimary ? a : b, background = firstPrimary ? b : a;
      long seen = 0;
      int primaryCores = 0;
      for (GroupMask core : cores) {
         boolean inPrimary = (core.mask & primary.mask) != 0, inBackground = (core.mask & background.mask) != 0;
         if (core.group != available.group || core.mask == 0 || (core.mask & seen) != 0 || inPrimary == inBackground
               || (core.mask & ~available.mask) != 0) {
            throw new IllegalArgumentException("each physical core must be disjoint and inside exactly one L3; " + evidence);
         }
         seen |= core.mask;
         if (inPrimary) primaryCores++;
      }
      if (seen != available.mask) {
         throw new IllegalArgumentException("physical core masks must cover all available CPUs; " + evidence);
      }
      return new Topology(primary, background, available, primaryCache.bytes, backgroundCache.bytes, primaryCores);
   }

   /** Decode the 64-bit SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX RelationCache buffer with bounds checks. */
   static List<Cache> readCaches(ByteBuffer source) {
      ByteBuffer b = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      List<Cache> caches = new ArrayList<>();
      int offset = b.position(), end = b.limit();
      while (offset < end) {
         if (end - offset < 8) throw new IllegalArgumentException("truncated cache topology header");
         int relationship = b.getInt(offset), size = b.getInt(offset + 4);
         if (relationship != 2 || size < 56 || size > end - offset) {
            throw new IllegalArgumentException("invalid RelationCache record at " + offset + " size=" + size);
         }
         if (Byte.toUnsignedInt(b.get(offset + 8)) == 3) {
            if (b.getInt(offset + 16) != 0) throw new IllegalArgumentException("L3 cache is not unified");
            // GroupCount was reserved on older Windows; its zero means the original single GroupMask layout.
            int count = Short.toUnsignedInt(b.getShort(offset + 38));
            if (count == 0) count = 1;
            if (count > (size - 40) / 16) throw new IllegalArgumentException("truncated L3 group masks");
            List<GroupMask> groups = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
               int p = offset + 40 + i * 16;
               groups.add(new GroupMask(Short.toUnsignedInt(b.getShort(p + 8)), b.getLong(p)));
            }
            caches.add(new Cache(Integer.toUnsignedLong(b.getInt(offset + 12)), List.copyOf(groups)));
         }
         offset += size;
      }
      return List.copyOf(caches);
   }

   /** Decode the 64-bit SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX RelationProcessorCore buffer: one mask per physical core. */
   static List<GroupMask> readCores(ByteBuffer source) {
      ByteBuffer b = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      List<GroupMask> cores = new ArrayList<>();
      int offset = b.position(), end = b.limit();
      while (offset < end) {
         if (end - offset < 8) throw new IllegalArgumentException("truncated core topology header");
         int relationship = b.getInt(offset), size = b.getInt(offset + 4);
         if (relationship != 0 || size < 48 || size > end - offset) {
            throw new IllegalArgumentException("invalid RelationProcessorCore record at " + offset + " size=" + size);
         }
         // A physical core never spans processor groups: GroupCount is 1 and one GROUP_AFFINITY follows at 32.
         if (Short.toUnsignedInt(b.getShort(offset + 30)) != 1) throw new IllegalArgumentException("core spans processor groups");
         cores.add(new GroupMask(Short.toUnsignedInt(b.getShort(offset + 40)), b.getLong(offset + 32)));
         offset += size;
      }
      return List.copyOf(cores);
   }

   private static final Set<String> BACKGROUND_NAMES = Set.of(
         "pzopt-cores", "pzopt-aot", "pzopt-animcache-writer", "pzopt-autostart", "pzopt-animset-preload",
         "pzopt-fmod-init", "pzopt-boot-pump", "pzopt-carglass-maps", "pzopt-occupant-colours", "pzopt-capture",
         "pzopt-capture-drain", "pzopt-cloudfield", "pzopt-overlay-stacks", "pzopt-overlay-util", "pzopt-gif-decode",
         "pzopt-gpu-boost", "pzopt-grade", "pzopt-hdr-light", "pzopt-hdr-dump", "pzopt-inputlag", "pzopt-input-log",
         "pzopt-loadtrace-flush", "pzopt-lua-precompile", "pzopt-quit", "pzopt-restart", "pzopt-resume-shot",
         "pzopt-resume-shot-load", "pzopt-resume-load-time", "pzopt-sim-close", "pzopt-texcompress-log",
         "pzopt-thread-nice", "pzopt-tiledef-preload", "pzopt-upscaler-deps", "pzopt-upscaler-deps-install",
         "pzopt-virtual-pad", "pzopt-vrr", "World Streamer", "PolyPathThread",
         "MapCollisionDataJNI", "GameLoadingThread", "Reference Handler", "Finalizer", "Signal Dispatcher",
         "Service Thread", "Monitor Deflation Thread", "Notification Thread", "Common-Cleaner", "Attach Listener",
         "Sweeper thread", "JFR Recorder Thread", "JFR Periodic Tasks", "VM Periodic Task Thread",
         "G1 Service", "G1 Main Marker", "G1 Young RemSet Sampling", "JNA Cleaner", "FileSystemWatchService",
         "WorldReuser", "ZDirector", "ZStat");

   /** Names are OS descriptions, not Java IDs. Unproved/default executor names must never go to the background CCD. */
   static Kind classify(String description) {
      if (description == null || description.isBlank()) return Kind.UNKNOWN;
      if (description.equals("GameThread") || description.equals("Render Thread") || description.equals("pzopt-render")
            || description.equals("Lighting Thread") || description.equals("pzopt-input") || description.equals("pzopt-vispoly")
            || numbered(description, "pzopt-frame-") || numbered(description, "pzopt-chardraw-")
            || numbered(description, "pzopt-slotinit-")) {
         return Kind.CRITICAL;
      }
      String lower = description.toLowerCase(Locale.ROOT);
      int colon = lower.lastIndexOf(':');
      String tail = colon < 0 ? "" : lower.substring(colon + 1);
      // A vendor's unnamed GL worker is UNKNOWN/wide; do not guess from generic "pool-N-thread-M" names.
      if (lower.contains("opengl") || lower.startsWith("nvoglv") || numbered(tail, "gl")
            || numbered(tail, "gdrv") || numbered(tail, "cs")) return Kind.CRITICAL;
      if (numbered(description, "GC Thread#") || numbered(description, "GC Thread ") || description.equals("VM Thread")
            || numbered(description, "G1 Full GC Worker#") || numbered(description, "ZWorkerOld#")
            || numbered(description, "ZWorkerYoung#") || description.equals("ZDriverMinor")
            || description.equals("ZDriverMajor")) return Kind.PAUSE;
      if (BACKGROUND_NAMES.contains(description) || numbered(description, "pzopt-file-")
            || numbered(description, "pzopt-recalc-") || numbered(description, "pzopt-update-")
            || description.equals("pzopt-update-check") || description.equals("pzopt-update-install")
            || description.equals("pzopt-update-job") || description.startsWith("pzopt-cache-sweep-")
            || numbered(description, "MetaGridLoaderThread") || compiler(description)
            || numbered(description, "G1 Refine#") || numbered(description, "G1 Conc#")
            || numbered(description, "G1 Marker") || lower.startsWith("fmod")
            || numbered(description, "ZUncommitter#")
            || description.startsWith("JFR ")) return Kind.BACKGROUND;
      return Kind.UNKNOWN;
   }

   /** An ambiguous OS description is not an identity; direct current-thread registrations can resolve it separately. */
   static Kind classify(String description, int occurrences) {
      return occurrences == 1 ? classify(description) : Kind.UNKNOWN;
   }

   private static boolean compiler(String name) {
      if (!name.startsWith("C1 CompilerThread") && !name.startsWith("C2 CompilerThread")) return false;
      return numbered(name, "C1 CompilerThread") || numbered(name, "C2 CompilerThread");
   }

   private static boolean numbered(String name, String prefix) {
      if (!name.startsWith(prefix) || name.length() == prefix.length()) return false;
      for (int i = prefix.length(); i < name.length(); i++) {
         char c = name.charAt(i);
         if (c < '0' || c > '9') return false;
      }
      return true;
   }
}
