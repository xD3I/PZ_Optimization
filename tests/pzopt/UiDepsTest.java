package pzopt;

import java.lang.reflect.Method;
import java.util.HashMap;
import se.krka.kahlua.j2se.KahluaTableImpl;

/**
 * uiVerify's read sets (pzopt.UiDeps): what a recorded render read decides whether a later frame may replay it.
 * Tables (own writes, writes after the render, class chain, render-local tables), getters (same / changed result, the
 * pointer, an updater re-run before the read after it), calls that make a log opaque, child logs, learned noise.
 */
public class UiDepsTest {
   /** The game objects a render reads (getters), updates (an updater) and calls with a result that is not a read. */
   public static final class Item {
      int age;
      int hidden;
      String name = "Axe";

      public String getName() {
         return name;
      }

      public int getAge() {
         return age;
      }

      public void updateAge() {
         age = hidden;
      }

      public int pop() {
         return 1;
      }

      public void setOwner(Item other) {
      }
   }

   static int mouseX;

   public static int getMouseX() {
      return mouseX;
   }

   static Method m(String name, Class<?>... types) throws Exception {
      return (name.equals("getMouseX") ? UiDepsTest.class : Item.class).getMethod(name, types);
   }

   /** One Lua -> Java call as MethodCaller.call makes it while a render records. */
   static Object call(Method method, Object self, Object... args) throws Exception {
      int d = UiDeps.enter();
      Object r = method.invoke(self, args);
      UiDeps.call(d, method, self, args, method.getReturnType() != void.class, r);
      UiDeps.exit(d);
      return r;
   }

   static KahluaTableImpl table() {
      return new KahluaTableImpl(new HashMap<>());
   }

   /** Like zombie.util.list.PZArrayList: an indexed list whose iterators throw (AbstractList.equals / hashCode use them). */
   public static final class NoIterList extends java.util.AbstractList<Object> implements java.util.RandomAccess {
      final java.util.ArrayList<Object> a = new java.util.ArrayList<>();

      @Override
      public Object get(int i) {
         return a.get(i);
      }

      @Override
      public int size() {
         return a.size();
      }

      @Override
      public void add(int i, Object o) {
         a.add(i, o);
      }

      @Override
      public Object remove(int i) {
         return a.remove(i);
      }

      @Override
      public java.util.Iterator<Object> iterator() {
         throw new UnsupportedOperationException();
      }

      @Override
      public java.util.ListIterator<Object> listIterator() {
         throw new UnsupportedOperationException();
      }

      @Override
      public java.util.ListIterator<Object> listIterator(int i) {
         throw new UnsupportedOperationException();
      }
   }

   /** A table key whose hashCode fails on demand. */
   static final class BadKey {
      static boolean fail;

      @Override
      public int hashCode() {
         if (fail) {
            throw new IllegalStateException("bad key");
         }
         return 7;
      }
   }

   static void callList(Method size, java.util.List<?> l) throws Exception {
      int d = UiDeps.enter();
      Object r = size.invoke(l);
      UiDeps.call(d, size, l, null, true, r);
      UiDeps.exit(d);
   }

   @SuppressWarnings("unchecked")
   static void noisyListReceivers(java.util.List<?> a0, java.util.List<?> b0, String what) throws Exception {
      java.util.List<Object> a = (java.util.List<Object>)a0;
      java.util.List<Object> b = (java.util.List<Object>)b0;
      Method size = java.util.List.class.getMethod("size");
      UiDeps.Log log = new UiDeps.Log();
      UiDeps.begin(log, null);
      callList(size, a);
      callList(size, b);
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(validate(log) == UiDeps.VALID, what + ": nothing changed, valid");
      a.add("window");
      Check.check(validate(log) == UiDeps.INVALID, what + ": the first list changed, invalid");
      UiDeps.learnNoise(log); // the fresh render drew the same: the first list's size is noise
      Check.check(log.noiseCount() == 1, what + ": noise learned");
      int v;
      try {
         v = validate(log);
      } catch (RuntimeException e) {
         v = -1;
      }
      Check.check(v == UiDeps.INVALID, what + ": the noisy list still differs and is checked when due, no exception");
      UiDeps.skipNoisy = true;
      try {
         v = validate(log);
      } catch (RuntimeException e) {
         v = -1;
      }
      Check.check(v == UiDeps.VALID, what + ": the learned-noisy list is skipped while not due, the other list compared safely");
      b.add("furniture");
      try {
         v = validate(log);
      } catch (RuntimeException e) {
         v = -1;
      }
      UiDeps.skipNoisy = false;
      Check.check(v == UiDeps.INVALID, what + ": a real change of the other list still counts");
   }

   static long frame = 1;

   static int validate(UiDeps.Log log) {
      return UiDeps.validate(log, frame++);
   }

   public static void main(String[] args) throws Exception {
      Item item = new Item();
      KahluaTableImpl self = table();
      self.rawset("x", 1.0);

      // tables and getters
      KahluaTableImpl scratch = table();
      UiDeps.Log log = new UiDeps.Log();
      UiDeps.begin(log, null);
      self.rawget("x");
      call(m("getName"), item);
      scratch.rawset("cache", 5.0); // a table written, never read by the render: not mutating
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(log.verifiable() && !log.mutating(), "a render of table reads and getters is verifiable");
      Check.check(validate(log) == UiDeps.VALID, "nothing changed: valid");
      self.rawset("x", 1.0);
      Check.check(validate(log) == UiDeps.VALID, "a write of the same value is no change");
      self.rawset("x", 2.0);
      Check.check(validate(log) == UiDeps.INVALID, "a table it read changed: invalid");

      UiDeps.begin(log, null);
      self.rawget("x");
      call(m("getName"), item);
      UiDeps.end(log);
      UiDeps.reset();
      item.name = "Hammer";
      Check.check(validate(log) == UiDeps.INVALID, "a getter it called returns something else: invalid");

      // class chain: a lookup answered by the class table is a read of the class table
      KahluaTableImpl cls = table();
      cls.rawset("__index", cls);
      KahluaTableImpl inst = table();
      inst.setMetatable(cls);
      cls.rawset("label", "Ok");
      UiDeps.begin(log, null);
      inst.rawget("label");
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(validate(log) == UiDeps.VALID, "class lookup: valid");
      cls.rawset("label", "Cancel");
      Check.check(validate(log) == UiDeps.INVALID, "the class table changed: invalid");

      // render-local tables, write after read
      UiDeps.begin(log, null);
      KahluaTableImpl local = table();
      local.rawset("a", 1.0);
      local.rawget("a");
      local.rawset("a", 2.0);
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(!log.mutating() && validate(log) == UiDeps.VALID, "a table created by the render is its own local");
      UiDeps.begin(log, null);
      self.rawget("x");
      self.rawset("x", 3.0);
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(log.mutating(), "a tracked table the render changes after reading it: mutating");

      // the pointer
      UiDeps.begin(log, null);
      call(m("getMouseX"), null);
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(log.readsMouse() && validate(log) == UiDeps.VALID, "pointer read, pointer still: valid");
      mouseX = 40;
      Check.check(validate(log) == UiDeps.MOUSE, "only the pointer moved: MOUSE");

      // an updater re-run before the getter after it
      item.hidden = 3;
      UiDeps.begin(log, null);
      call(m("updateAge"), item);
      call(m("getAge"), item);
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(log.verifiable() && validate(log) == UiDeps.VALID, "updater + getter: valid");
      item.hidden = 4;
      Check.check(validate(log) == UiDeps.INVALID, "the updater brings a new age: invalid");

      // opaque calls
      UiDeps.begin(log, null);
      call(m("pop"), item);
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(!log.verifiable() && validate(log) == UiDeps.OPAQUE, "a call with a result that is not a read: opaque");
      UiDeps.begin(log, null);
      call(m("setOwner", Item.class), item, item);
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(!log.verifiable(), "a void call passing a game object: opaque");

      // child logs
      UiDeps.Log parent = new UiDeps.Log();
      UiDeps.Log child = new UiDeps.Log();
      item.name = "Axe";
      UiDeps.begin(parent, null);
      self.rawget("x");
      UiDeps.begin(child, null);
      call(m("getName"), item);
      UiDeps.end(child);
      UiDeps.end(parent);
      UiDeps.reset();
      Check.check(validate(parent) == UiDeps.VALID, "parent with a child: valid");
      item.name = "Saw";
      Check.check(validate(parent) == UiDeps.INVALID, "the child's read changed: the parent is invalid");
      UiDeps.Log parent2 = new UiDeps.Log();
      UiDeps.begin(parent2, null);
      UiDeps.sub(child); // the child replayed inside the parent's fresh render
      UiDeps.end(parent2);
      UiDeps.reset();
      Check.check(validate(parent2) == UiDeps.INVALID, "a replayed child's read set is part of the parent's");

      // learned noise: a getter whose change drew the same is checked only when the element is due
      item.name = "Axe";
      UiDeps.begin(log, null);
      call(m("getName"), item);
      UiDeps.end(log);
      UiDeps.reset();
      item.name = "Axe2";
      Check.check(validate(log) == UiDeps.INVALID, "noise: first change is invalid");
      UiDeps.learnNoise(log);
      UiDeps.skipNoisy = true;
      Check.check(validate(log) == UiDeps.VALID, "a learned-noisy getter is skipped while not due");
      UiDeps.skipNoisy = false;
      Check.check(validate(log) == UiDeps.INVALID, "and checked when due");
      UiDeps.unlearnNoise(log);
      UiDeps.skipNoisy = true;
      Check.check(validate(log) == UiDeps.INVALID, "unlearned: checked every frame again");
      UiDeps.skipNoisy = false;

      // fields, not tables: a change to a field the render never read is no change; a noisy field hides only itself
      self.rawset("x", 1.0);
      self.rawset("y", 1.0);
      UiDeps.begin(log, null);
      self.rawget("x");
      self.rawget("y");
      UiDeps.end(log);
      UiDeps.reset();
      self.rawset("unread", 7.0);
      Check.check(validate(log) == UiDeps.VALID, "a field the render did not read changed: valid");
      self.rawset("x", 9.0);
      Check.check(validate(log) == UiDeps.INVALID, "a field it read changed: invalid");
      UiDeps.learnNoise(log);
      UiDeps.skipNoisy = true;
      Check.check(validate(log) == UiDeps.VALID, "that field learned as noise: skipped while not due");
      self.rawset("y", 2.0);
      Check.check(validate(log) == UiDeps.INVALID, "another field of the same table still counts");
      UiDeps.skipNoisy = false;
      // a walk over a table and its length
      KahluaTableImpl list = table();
      list.rawset(1, "a");
      UiDeps.begin(log, null);
      list.len();
      UiDeps.end(log);
      UiDeps.reset();
      Check.check(validate(log) == UiDeps.VALID, "length read: valid");
      list.rawset(2, "b");
      Check.check(validate(log) == UiDeps.INVALID, "the length changed: invalid");
      UiDeps.begin(log, null);
      list.iterator();
      UiDeps.end(log);
      UiDeps.reset();
      list.rawset("k", 1.0);
      Check.check(validate(log) == UiDeps.INVALID, "a walked table changed: invalid");

      // a Java element's render reads Java state no read set sees
      UiDeps.Log javaLog = new UiDeps.Log();
      UiDeps.begin(javaLog, new zombie.ui.UIElement() { });
      UiDeps.end(javaLog);
      UiDeps.reset();
      Check.check(!javaLog.verifiable(), "a Java UI element (a subclass) is opaque");

      // a top-level element that is also some panel's child (a mod adds it to UIManager and to a parent): UIManager's
      // fresh render and its own UIElement.render both begin its log (Workshop report 2026-10-19: StackOverflowError in
      // UiDeps.validate, the log had become its own sub)
      UiDeps.Log both = new UiDeps.Log();
      self.rawset("x", 1.0);
      UiDeps.begin(both, null);
      UiDeps.begin(both, null);
      self.rawget("x");
      UiDeps.end(both);
      UiDeps.end(both);
      UiDeps.reset();
      Check.check(both.verifiable(), "a log begun twice is recorded once");
      Check.check(validate(both) == UiDeps.VALID, "and validates without recursing into itself");
      self.rawset("x", 2.0);
      Check.check(validate(both) == UiDeps.INVALID, "its read still counts");
      Check.check(both.size() >= 1, "size of a log begun twice");
      // a cycle between two logs (A replayed B, then B replayed A): validate and size end, the cycle reads as opaque
      UiDeps.Log ca = new UiDeps.Log();
      UiDeps.Log cb = new UiDeps.Log();
      UiDeps.begin(cb, null);
      UiDeps.end(cb);
      UiDeps.begin(ca, null);
      UiDeps.sub(cb);
      UiDeps.end(ca);
      UiDeps.begin(cb, null);
      UiDeps.sub(ca);
      UiDeps.end(cb);
      UiDeps.reset();
      Check.check(validate(ca) == UiDeps.INVALID, "a cycle of logs: no verdict, rendered fresh");
      Check.check(ca.size() >= 0 && cb.size() >= 0, "size of a cycle ends");

      // learned noise on a getter whose receiver is a game list (Workshop report 2026-10-19: a zombie broke a window /
      // furniture placed: zombie.util.list.PZArrayList.listIterator threw out of AbstractList.equals inside Log.noisy,
      // every frame, ~3 fps). Two receivers of the same method; the noise learned on one must not compare the other with
      // equals, and a real change of the other must still count.
      noisyListReceivers(new NoIterList(), new NoIterList(), "a list whose iterators throw");
      noisyListReceivers(new zombie.util.list.PZArrayList<>(Object.class, 4), new zombie.util.list.PZArrayList<>(Object.class, 4),
         "the game's PZArrayList");
      // same content, other list: still the same receiver for learned noise (AbstractList.equals' semantics, by index)
      UiDeps.Log same = new UiDeps.Log();
      NoIterList la = new NoIterList();
      NoIterList lb = new NoIterList();
      la.add("w");
      lb.add("w");
      Method size = java.util.List.class.getMethod("size");
      UiDeps.begin(same, null);
      callList(size, la);
      UiDeps.end(same);
      UiDeps.reset();
      la.add("x");
      Check.check(validate(same) == UiDeps.INVALID, "list size changed: invalid");
      UiDeps.learnNoise(same);
      la.remove(1);
      UiDeps.begin(same, null);
      callList(size, lb);
      UiDeps.end(same);
      UiDeps.reset();
      lb.add("y");
      la.add("y"); // the learned list holds the same elements as the one read now
      UiDeps.skipNoisy = true;
      Check.check(validate(same) == UiDeps.VALID, "an equal list (same elements) is the learned-noisy receiver");
      UiDeps.skipNoisy = false;

      // a validation that throws anyway (a table key whose hashCode fails once the table changed): the element renders
      // fresh this frame, nothing escapes into the frame, and its later recordings keep the timer rules
      UiDeps.Log boom = new UiDeps.Log();
      KahluaTableImpl bt = table();
      BadKey bk = new BadKey();
      bt.rawset(bk, 1.0);
      UiDeps.begin(boom, null);
      bt.rawget(bk);
      UiDeps.end(boom);
      UiDeps.reset();
      Check.check(validate(boom) == UiDeps.VALID, "bad key, nothing changed: valid");
      bt.rawset("other", 2.0);
      BadKey.fail = true;
      int bv;
      try {
         bv = validate(boom);
      } catch (RuntimeException e) {
         bv = -1;
      }
      BadKey.fail = false;
      Check.check(bv == UiDeps.INVALID, "a validation that throws: invalid (rendered fresh), not an exception");
      UiDeps.begin(boom, null);
      bt.rawget(bk);
      UiDeps.end(boom);
      UiDeps.reset();
      Check.check(!boom.verifiable() && validate(boom) == UiDeps.OPAQUE, "its next recording keeps the timer rules");

      Check.check(!UiDeps.on, "nothing records after reset");
      System.out.println("UiDepsTest ok");
   }
}
