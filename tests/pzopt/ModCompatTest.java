package pzopt;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** pzopt.ModCompat: ZombieBuddy @Patch and transformer string constants are found, plain classes are not, the policy switches the right keys. */
public class ModCompatTest {
   @me.zed_0xff.zombie_buddy.Patch(className = "zombie.iso.WorldStreamer", methodName = "stop")
   static class FakePatch {
   }

   @me.zed_0xff.zombie_buddy.Patch(className = "zombie.iso.IsoGridSquare", methodName = "render")
   static class NotOurs {
   }

   static class FakeTransformer implements java.lang.instrument.ClassFileTransformer {
      final String target = "zombie/iso/IsoChunkMap";
      final String method = "updateInternal";
   }

   static class Plain {
      final String target = "zombie.iso.IsoChunkMap";
      final String method = "updateInternal";
   }

   static byte[] bytes(Class<?> c) throws Exception {
      try (InputStream in = c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class")) {
         return in.readAllBytes();
      }
   }

   public static void main(String[] args) throws Exception {
      Map<String, Map<String, String[]>> edited = new HashMap<>();
      ModCompat.parseEdited("# header\nzombie/iso/WorldStreamer#stop=wake\nzombie/iso/WorldStreamer#threadLoop=wake,centerFirstLoad\n"
            + "zombie/iso/IsoChunkMap#updateInternal=chunkHandoffSlack\nzombie/iso/IsoChunk#recalcPooled=\n", edited);
      Check.check(edited.get("zombie/iso/WorldStreamer").get("threadLoop").length == 2, "two keys parsed");
      Check.check(edited.get("zombie/iso/IsoChunk").get("recalcPooled").length == 0, "an edit without a switch parses as no keys");
      Set<String> shadowed = Set.of("zombie/iso/WorldStreamer", "zombie/iso/IsoChunkMap", "zombie/iso/IsoChunk");

      List<ModCompat.Hit> hits = new ArrayList<>();
      ModCompat.scanClass("modA", bytes(FakePatch.class), shadowed, edited, hits);
      Check.check(hits.size() == 1 && hits.get(0).cls.equals("zombie/iso/WorldStreamer") && "stop".equals(hits.get(0).method)
            && hits.get(0).how.contains("@Patch"), "the @Patch target is read: " + hits.size());
      Check.check("pzopt/ModCompatTest$FakePatch".equals(hits.get(0).patchClass), "the @Patch class is named: " + hits.get(0).patchClass);
      hits.clear();
      ModCompat.scanClass("modA", bytes(NotOurs.class), shadowed, edited, hits);
      Check.check(hits.isEmpty(), "a patch of a class we do not ship is no hit");
      ModCompat.scanClass("agentB", bytes(FakeTransformer.class), shadowed, edited, hits);
      Check.check(hits.size() == 1 && hits.get(0).cls.equals("zombie/iso/IsoChunkMap") && "updateInternal".equals(hits.get(0).method),
            "a transformer's target class + method strings are a hit: " + hits.size());
      hits.clear();
      ModCompat.scanClass("modC", bytes(Plain.class), shadowed, edited, hits);
      Check.check(hits.isEmpty(), "a plain class naming ours in strings is not a patch");
      ModCompat.scanClass("modA", "modA.jar", bytes(FakePatch.class), shadowed, edited, hits);
      Check.check(hits.size() == 1 && "modA.jar".equals(hits.get(0).jar), "a hit carries its jar file for the menu's check");
      hits.clear();

      // policy: unknown mod in auto switches the method's keys, a known-ok mod switches nothing, report only logs
      List<ModCompat.Hit> all = new ArrayList<>();
      all.add(new ModCompat.Hit("modA", "zombie/iso/WorldStreamer", "threadLoop", "ZombieBuddy @Patch"));
      all.add(new ModCompat.Hit("modA", "zombie/iso/WorldStreamer", "addJob", "ZombieBuddy @Patch")); // not edited: stock bytecode
      all.add(new ModCompat.Hit("ZBBetterFPS", "zombie/iso/IsoChunkMap", "updateInternal", "string constants"));
      Map<String, String> policy = new HashMap<>();
      policy.put("ZBBetterFPS", "ok");
      Properties out = new Properties();
      ModCompat.applyForTest(all, edited, policy, out, "auto");
      Check.check("false".equals(out.getProperty("wake")) && "false".equals(out.getProperty("centerFirstLoad")), "modA's edited method keys off: " + out);
      Check.check(out.getProperty("chunkHandoffSlack") == null, "a known-compatible mod switches nothing");
      Check.check(ModCompat.reason("wake") != null && ModCompat.reason("wake").contains("modA"), "the reason names the mod");
      String details = ModCompat.details();
      Check.check(details.startsWith("scan\tauto\t"), "details start with the scan line: " + details);
      Properties report = new Properties();
      ModCompat.applyForTest(all, edited, new HashMap<>(), report, "report");
      Check.check(report.isEmpty(), "modCompat=report switches nothing: " + report);
      Properties rule = new Properties();
      policy.put("modA", "off:luaSkipEmpty");
      ModCompat.applyForTest(all, edited, policy, rule, "auto");
      Check.check(rule.size() == 1 && "false".equals(rule.getProperty("luaSkipEmpty")), "an off: rule replaces the method's keys: " + rule);
      System.out.println("ModCompatTest ok");
   }
}
