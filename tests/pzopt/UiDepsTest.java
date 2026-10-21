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

      Check.check(!UiDeps.on, "nothing records after reset");
      System.out.println("UiDepsTest ok");
   }
}
