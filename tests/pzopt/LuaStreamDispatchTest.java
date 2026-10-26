package pzopt;

import java.lang.reflect.Method;
import java.util.HashMap;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import se.krka.kahlua.vm.UpValue;

/**
 * lua-stream's LoadGridsquare dispatcher (pzopt.LuaStreamDispatch, pzopt.LuaEffects): what a recorded call counts as an
 * effect or as a read beyond the square signature, and when what a handler learned stays valid (tables key by key, upvalues).
 */
public class LuaStreamDispatchTest {
   static KahluaTableImpl table() {
      return new KahluaTableImpl(new HashMap<>());
   }

   static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError(what);
      }
   }

   public static final class Thing {
      public String getName() {
         return "x";
      }

      public void poke() {
      }

      public Object getOrCreateThing() {
         return this;
      }
   }

   static void call(Method m, Object self) throws Exception {
      int d = UiDeps.enter();
      Object r = m.invoke(self);
      UiDeps.call(d, m, self, new Object[0], m.getReturnType() != void.class, r);
      UiDeps.exit(d);
   }

   static LuaClosure closure(int[] code, int upvalues) {
      Prototype p = new Prototype();
      p.code = code;
      p.numUpvalues = upvalues;
      p.lines = new int[]{1};
      p.filename = "test.lua";
      LuaClosure c = new LuaClosure(p, table());
      for (int i = 0; i < upvalues; i++) {
         c.upvalues[i] = new UpValue(null, 0);
      }
      return c;
   }

   public static void main(String[] args) throws Exception {
      KahluaTableImpl shared = table();
      shared.rawset("enabled", Boolean.TRUE);
      shared.rawset("other", 1.0);

      // 1. a table the call made is its own: writing it is no effect; writing a table from before is
      check(LuaEffects.begin(), "begin");
      KahluaTableImpl local = table();
      local.rawset("a", 1.0);
      shared.rawget("enabled");
      LuaEffects.end();
      check(!LuaEffects.effect, "local write is no effect");
      check(!LuaEffects.uncovered, "plain reads are covered");
      check(LuaEffects.rn == 1 && LuaEffects.rt[0] == shared && LuaEffects.rkn[0] == 1, "one table read, one key");

      check(LuaEffects.begin(), "begin 2");
      shared.rawset("other", 2.0);
      LuaEffects.end();
      check(LuaEffects.effect && "table write".equals(LuaEffects.why), "write to an older table is an effect");
      shared.rawset("other", 1.0);

      // 2. a Java object as a key or as a value read from a table: beyond the signature
      KahluaTableImpl byObject = table();
      Thing thing = new Thing();
      byObject.rawset(thing, Boolean.TRUE);
      check(LuaEffects.begin(), "begin 3");
      byObject.rawget(thing);
      LuaEffects.end();
      check(!LuaEffects.effect && LuaEffects.uncovered, "a Java key is uncovered");
      KahluaTableImpl holder = table();
      holder.rawset("obj", thing);
      check(LuaEffects.begin(), "begin 4");
      holder.rawget("obj");
      LuaEffects.end();
      check(LuaEffects.uncovered, "a Java value out of a table is uncovered");

      // 3. Java calls: a getter of an unknown class is uncovered, a void call or a creator is an effect
      check(LuaEffects.begin(), "begin 5");
      call(Thing.class.getMethod("getName"), thing);
      LuaEffects.end();
      check(!LuaEffects.effect && LuaEffects.uncovered, "getter of another class: uncovered");
      check(LuaEffects.begin(), "begin 6");
      call(Thing.class.getMethod("poke"), thing);
      LuaEffects.end();
      check(LuaEffects.effect, "void call: effect");
      check(LuaEffects.begin(), "begin 7");
      call(Thing.class.getMethod("getOrCreateThing"), thing);
      LuaEffects.end();
      check(LuaEffects.effect, "getOrCreate: effect");
      check(LuaEffects.begin(), "begin 8");
      call(java.util.ArrayList.class.getMethod("size"), new java.util.ArrayList<>());
      LuaEffects.end();
      check(!LuaEffects.effect && !LuaEffects.uncovered, "list size is covered");

      // 4. SETUPVAL anywhere in the prototype tree
      check(!LuaEffects.setsUpvalue(closure(new int[]{30}, 0).prototype), "RETURN only");
      Prototype parent = closure(new int[]{30}, 0).prototype;
      parent.prototypes = new Prototype[]{closure(new int[]{8, 30}, 0).prototype};
      check(LuaEffects.setsUpvalue(parent), "SETUPVAL in a nested function");

      // 5. what a handler learned stays valid while its reads are unchanged: other keys of a table may change
      LuaClosure handler = closure(new int[]{30}, 1);
      handler.upvalues[0].setValue("on");
      LuaStreamDispatch.H h = new LuaStreamDispatch.H(handler);
      check(LuaEffects.begin(), "begin 9");
      shared.rawget("enabled");
      LuaEffects.end();
      LuaStreamDispatch.merge(h);
      h.learned.put(42L, 1);
      check(LuaStreamDispatch.valid(h), "valid right after");
      shared.rawset("unrelated", 5.0);
      check(LuaStreamDispatch.valid(h), "another key changed: still valid");
      check(h.learned.get(42L) == 1, "kept");
      shared.rawset("enabled", Boolean.FALSE);
      check(!LuaStreamDispatch.valid(h), "the key read changed: invalid");
      check(h.learned.get(42L) == 0 && h.dn == 0, "forgotten");

      // 6. an upvalue another closure assigns
      shared.rawset("enabled", Boolean.TRUE);
      check(LuaEffects.begin(), "begin 10");
      shared.rawget("enabled");
      LuaEffects.end();
      LuaStreamDispatch.merge(h);
      h.learned.put(7L, 1);
      check(LuaStreamDispatch.valid(h), "valid again");
      handler.upvalues[0].setValue("o" + "n"); // an equal string: same value
      check(LuaStreamDispatch.valid(h), "equal upvalue: valid");
      handler.upvalues[0].setValue("off");
      check(!LuaStreamDispatch.valid(h), "upvalue changed: invalid");

      // 7. the long -> int map
      LuaStreamDispatch.LongIntMap m = new LuaStreamDispatch.LongIntMap();
      for (long k = 0; k < 5000; k++) {
         m.put(k * 0x9E3779B97F4A7C15L, (int)k + 1);
      }
      boolean all = m.size() == 5000;
      for (long k = 0; k < 5000; k++) {
         all &= m.get(k * 0x9E3779B97F4A7C15L) == (int)k + 1;
      }
      check(all && m.get(123456789L) == 0, "map");
      m.put(0L, -1);
      check(m.get(0L) == -1, "map overwrite");
      // 8. mod data a covered getModData returned: its plain contents are in the signature; writing it is an effect
      KahluaTableImpl md = table();
      md.rawset("lit", Boolean.TRUE);
      KahluaTableImpl nested = table();
      md.rawset("sub", nested);
      Method getModData = zombie.iso.IsoObject.class.getMethod("getModData");
      check(LuaEffects.begin(), "begin 11");
      int d = UiDeps.enter();
      UiDeps.call(d, getModData, null, new Object[0], true, md);
      UiDeps.exit(d);
      md.rawget("lit");
      md.rawget("missing");
      LuaEffects.end();
      check(!LuaEffects.effect && !LuaEffects.uncovered && LuaEffects.rn == 0, "mod data reads: covered, not kept as table reads");
      check(LuaEffects.begin(), "begin 12");
      d = UiDeps.enter();
      UiDeps.call(d, getModData, null, new Object[0], true, md);
      UiDeps.exit(d);
      md.rawget("sub");
      LuaEffects.end();
      check(LuaEffects.uncovered, "a table inside mod data: uncovered");
      check(LuaEffects.begin(), "begin 13");
      KahluaTableImpl fresh = table(); // as getModData makes one for an object without any
      d = UiDeps.enter();
      UiDeps.call(d, getModData, null, new Object[0], true, fresh);
      UiDeps.exit(d);
      fresh.rawset("seen", Boolean.TRUE);
      LuaEffects.end();
      check(LuaEffects.effect, "writing new mod data: effect");
      int h1 = LuaStreamDispatch.modData(md);
      md.rawset("lit", Boolean.FALSE);
      int h2 = LuaStreamDispatch.modData(md);
      md.rawset("lit", Boolean.TRUE);
      check(h1 != h2 && h1 == LuaStreamDispatch.modData(md), "mod data hash follows the contents");

      // 10. signature parts: what each covered getter's answer follows from, collected over a call
      check(LuaEffects.parts(zombie.iso.IsoGridSquare.class.getMethod("getZ")) == LuaStreamDispatch.C_Z, "getZ: level");
      check(LuaEffects.parts(zombie.iso.IsoGridSquare.class.getMethod("getObjects")) == LuaStreamDispatch.C_OBJS, "getObjects: objects");
      int spriteName = LuaEffects.parts(zombie.iso.IsoObject.class.getMethod("getSpriteName"));
      check((spriteName & LuaStreamDispatch.C_OSPRITE) != 0 && (spriteName & LuaStreamDispatch.C_OBJS) != 0 && (spriteName & LuaStreamDispatch.C_Z) == 0,
         "getSpriteName: the objects' sprites");
      check(LuaEffects.parts(zombie.iso.IsoObject.class.getMethod("getContainerCount")) == (LuaStreamDispatch.C_OCONT | LuaStreamDispatch.C_OBJS), "containers");
      check(LuaEffects.parts(java.util.ArrayList.class.getMethod("size")) == 0, "a list's size: its owner's part");
      Method getZ = zombie.iso.IsoGridSquare.class.getMethod("getZ");
      check(LuaEffects.begin(), "begin 14");
      d = UiDeps.enter();
      UiDeps.call(d, getZ, null, new Object[0], true, 0);
      UiDeps.exit(d);
      d = UiDeps.enter();
      UiDeps.call(d, getModData, null, new Object[0], true, md);
      UiDeps.exit(d);
      LuaEffects.end();
      check(LuaEffects.touched == (LuaStreamDispatch.C_Z | LuaStreamDispatch.C_OBJS | LuaStreamDispatch.C_OMOD), "touched parts of a call");
      check("z+objs+objmod".equals(LuaStreamDispatch.maskName(LuaEffects.touched)) && "all".equals(LuaStreamDispatch.maskName(LuaStreamDispatch.C_ALL)),
         "mask names");

      // 9. upvalue values compared by value for numbers, strings, booleans
      check(LuaStreamDispatch.sameValue(1.0, 1.0) && LuaStreamDispatch.sameValue("a", "a") && LuaStreamDispatch.sameValue(true, Boolean.TRUE)
         && !LuaStreamDispatch.sameValue(1.0, 2.0) && !LuaStreamDispatch.sameValue(table(), table()) && LuaStreamDispatch.sameValue(null, null), "sameValue");
      // 11. the order check (devLuaLoadOrderCheck): footprints, the batch's reversed pairs, the chunks' reversed pairs
      LuaEffects.fp = true;
      LuaOrderCheck.reset();
      KahluaTableImpl state = table();
      state.rawset("k", 1.0);
      state.rawset("other", 1.0);
      Object chunkA = new Object(), chunkB = new Object();
      check(LuaEffects.begin(), "begin 15");
      state.rawset("k", 2.0); // handler 1 on square 0,0 (loop index 0) writes k
      LuaEffects.end();
      check(LuaEffects.fpWn == 1 && LuaEffects.fpWk[0] == "k".hashCode(), "a write's key kept");
      LuaOrderCheck.add(chunkA, 0, 0, 0, 0, 200L, true, 0, 0, 0, 1, "writer", false);
      check(LuaEffects.begin(), "begin 16");
      state.rawget("k"); // handler 0 on square 0,1 (loop index 1) reads k: the batch runs it before the write
      LuaEffects.end();
      LuaOrderCheck.add(chunkA, 0, 0, 0, 0, 200L, true, 0, 1, 0, 0, "reader", false);
      check(LuaEffects.begin(), "begin 17");
      state.rawget("other"); // handler 0 on square 0,2 reads another key: no conflict
      LuaEffects.end();
      LuaOrderCheck.add(chunkA, 0, 0, 0, 0, 200L, true, 0, 2, 0, 0, "reader", false);
      check(LuaEffects.begin(), "begin 18");
      d = UiDeps.enter();
      UiDeps.call(d, zombie.iso.IsoCell.class.getMethod("getGridSquare", int.class, int.class, int.class), new Object(), new Object[]{0, 5, 0}, true, null);
      UiDeps.exit(d);
      LuaEffects.end();
      check(LuaEffects.fpRsn == 1 && LuaEffects.fpRs[0] == LuaEffects.sqKey(0, 5, 0), "a square asked for is read, there or not");
      LuaOrderCheck.add(chunkA, 0, 0, 0, 0, 200L, true, 0, 3, 0, 0, "reader", false); // reads square 0,5: loaded after 0,3 in stock
      check(LuaEffects.begin(), "begin 19");
      state.rawget("k"); // the next chunk, queued before the first (handed off after it: reversed), reads k
      LuaEffects.end();
      LuaOrderCheck.add(chunkB, 1, 0, 0, 0, 100L, true, 8, 0, 0, 0, "reader", false);
      LuaOrderCheck.flush();
      check(LuaOrderCheck.batchPairs == 1 && LuaOrderCheck.batchTable == 1, "one reversed conflicting pair: " + LuaOrderCheck.batchPairs);
      check(LuaOrderCheck.laterSquare == 1, "one call reads a later square of its chunk: " + LuaOrderCheck.laterSquare);
      check(LuaOrderCheck.chunkPairsReversed == 1 && LuaOrderCheck.chunkPairsConflict == 1 && LuaOrderCheck.cpTable == 1, "the chunks' order: one reversed, conflicting pair");
      // a counter both chunks only count on (t.n = t.n + 1): order-free, its own class
      LuaOrderCheck.reset();
      state.rawset("n", 0.0);
      Object chunkC = new Object(), chunkD = new Object();
      check(LuaEffects.begin(), "begin 20");
      state.rawset("n", (Double)state.rawget("n") + 1.0);
      LuaEffects.end();
      check(LuaEffects.fpWn == 1 && LuaEffects.fpWinc[0] && LuaEffects.fpRinc[0], "a counter is seen as one");
      LuaOrderCheck.add(chunkC, 2, 0, 0, 0, 200L, true, 16, 0, 0, 0, "counter", false);
      check(LuaEffects.begin(), "begin 21");
      state.rawset("n", (Double)state.rawget("n") + 1.0);
      LuaEffects.end();
      LuaOrderCheck.add(chunkD, 3, 0, 0, 0, 100L, true, 24, 0, 0, 0, "counter", false);
      LuaOrderCheck.flush();
      check(LuaOrderCheck.chunkPairsConflict == 1 && LuaOrderCheck.cpCounter == 1 && LuaOrderCheck.cpTable == 0, "counters only: " + LuaOrderCheck.cpTable + "/" + LuaOrderCheck.cpCounter);
      LuaOrderCheck.reset();
      LuaEffects.fp = false;
      System.out.println("LuaStreamDispatchTest: ok");
   }
}
