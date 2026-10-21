package pzopt;

import java.lang.reflect.Method;
import se.krka.kahlua.j2se.KahluaTableImpl;
import zombie.ui.UIElement;

/**
 * uiVerify (2026-10-14, the UI latency pass): what a UI element's last fresh render read, so a later frame can tell
 * exactly whether rendering it again would draw anything else (a read set validated instead of a timer guessed).
 *
 * <p>While a UI element renders fresh (UiRetained.freshBegin / childBegin to freshEnd, game thread only) every Lua table
 * the render reads (KahluaTableImpl rawget / iterator / len) and every Lua -> Java call it makes (MethodCaller.call: the
 * method, its receiver, its arguments, its result) is logged. A child element rendered or replayed inside the render
 * adds its own log, so a top-level element's log covers everything its recorded commands came from. Validation re-checks
 * the log: every table still has the write count it had at the end of the render (KahluaTableImpl.pzoptWrites counts
 * value-changing writes), the class tables are unchanged (pzoptMetaVersion), and every getter called (get / is / has /
 * ... with a result) returns the same value when called again with the same receiver and arguments, in order (so an
 * argument that came from an earlier call is unchanged too). The calls a render makes again on every stock UI frame are
 * the same reads made here once, without the drawing.
 *
 * <p>The mouse is a getter like any other (getMouseX / getMouseY): a log whose only differing calls read the mouse
 * position reports MOUSE, and the caller decides whether that pointer movement matters to the element (over it, held
 * button, capture, follows the pointer). A render that calls anything else with a result (a random number, a remove, a
 * constructor whose object it keeps), a void Java call that passes a game object (it may read that object's state
 * inside), or throws is OPAQUE: its element keeps uiRetained's timer rules. Tables created during the render are its
 * own locals and not tracked; a tracked table the render itself changes after reading it marks the log mutating (an
 * element animating itself: it keeps the back-off timer as a backstop).
 */
public final class UiDeps {
   public static final int VALID = 0;
   public static final int MOUSE = 1;
   public static final int INVALID = 2;
   public static final int OPAQUE = 3;

   private static final byte K_PURE = 0;
   private static final byte K_MOUSE = 1;
   private static final byte K_EFFECT = 2; // a void updater the render calls (InventoryItem.updateAge): run again before the reads after it
   private static final byte K_CLOCK = 4; // a clock read (ms since the last UI render, a timestamp): checked at the stock rate
   private static final byte K_SIG = 3;
   private static final byte K_TEXTBOX = 5; // an unfocused Java text box: its text, cursor and state, compared // a Java draw helper that reads a game object inside (DrawItemIcon): those reads, compared

   /** True while some log on this (the game) thread records; the hooks test it first. */
   public static boolean on;
   private static Thread thread;
   private static final Log[] stack = new Log[64];
   private static int depth;
   private static int callDepth;
   private static int nextId = 1;
   /** Tables born while a top-level log recorded carry its epoch (KahluaTableImpl.pzoptDepBorn). */
   public static int epoch = 1;

   // statistics for the per-second log line
   static int statValidations;
   static long statValidateNs;
   static int statCalls;
   static int statOpaque;
   static final java.util.HashMap<String, int[]> opaqueWhy = new java.util.HashMap<>();

   private UiDeps() {
   }

   public static final class Log {
      int id;
      Method[] m = new Method[16];
      Object[] self = new Object[16];
      Object[][] args = new Object[16][];
      Object[] res = new Object[16];
      byte[] kind = new byte[16];
      int n;
      KahluaTableImpl[] tables = new KahluaTableImpl[16];
      int[] writes = new int[16];
      long[] chain = new long[16]; // the write counts of its metatable chain (lookups answered by a class)
      Object[][] skeys = new Object[16][];
      Object[][] svals = new Object[16][];
      int[] scount = new int[16];
      int nt;
      Log[] subs = new Log[4];
      int ns;
      long meta;
      boolean recorded;
      boolean opaque;
      String why;
      boolean mutating;
      boolean mouse;
      boolean selfPositioned;
      UIElement owner;
      long validFrame = -1;
      boolean validFull;
      int validResult;
      /** the first entry that differed in the last validation: "Class.method" (a table: "table") */
      String mismatch;
      Log mismatchLog;
      Object mismatchA;
      Object mismatchB;
      boolean mismatchNoisy; // the entry that differed was a learned-noisy one (checked because the element was due)
      // learned noise (kept across recordings): entries whose change did not change the output last time; checked only
      // when the element is due at the stock rate (UiDeps.skipNoisy false)
      Object[] noiseA = new Object[4];
      Object[] noiseB = new Object[4];
      int nNoise;

      boolean noisy(Object a, Object b) {
         for (int i = 0; i < nNoise; i++) {
            if (noiseA[i] == a && java.util.Objects.equals(noiseB[i], b)) {
               return true;
            }
         }
         return false;
      }

      void addNoise(Object a, Object b) {
         if (noisy(a, b) || nNoise >= 16) {
            return;
         }
         if (nNoise == noiseA.length) {
            noiseA = java.util.Arrays.copyOf(noiseA, nNoise * 2);
            noiseB = java.util.Arrays.copyOf(noiseB, nNoise * 2);
         }
         noiseA[nNoise] = a;
         noiseB[nNoise] = b;
         nNoise++;
      }

      public int noiseCount() {
         return nNoise;
      }
      long validNs;

      void reset(int id, UIElement owner) {
         this.id = id;
         this.owner = owner;
         for (int i = 0; i < n; i++) {
            self[i] = null;
            args[i] = null;
            res[i] = null;
         }
         for (int i = 0; i < nt; i++) {
            tables[i] = null;
            java.util.Arrays.fill(skeys[i], 0, scount[i], null);
            java.util.Arrays.fill(svals[i], 0, scount[i], null);
            scount[i] = 0;
         }
         for (int i = 0; i < ns; i++) {
            subs[i] = null;
         }
         n = 0;
         nt = 0;
         ns = 0;
         recorded = false;
         opaque = false;
         why = null;
         mutating = false;
         mouse = false;
         selfPositioned = false;
         validFrame = -1;
      }

      /** reset() keeps the learned noise and the last mismatch (learnNoise reads it after the new render). */
      public boolean verifiable() {
         return recorded && !opaque;
      }

      public boolean readsMouse() {
         return mouse;
      }

      public boolean mutating() {
         return mutating;
      }

      public boolean selfPositioned() {
         return selfPositioned;
      }

      public String why() {
         return why;
      }

      public int size() {
         int s = n;
      for (int i = 0; i < nt; i++) {
         s += scount[i];
      }
         for (int i = 0; i < ns; i++) {
            s += subs[i].size();
         }
         return s;
      }

      void addCall(Method method, Object self, Object[] params, Object result, byte k) {
         if (n == m.length) {
            int cap = n * 2;
            m = java.util.Arrays.copyOf(m, cap);
            this.self = java.util.Arrays.copyOf(this.self, cap);
            args = java.util.Arrays.copyOf(args, cap);
            res = java.util.Arrays.copyOf(res, cap);
            kind = java.util.Arrays.copyOf(kind, cap);
         }
         m[n] = method;
         this.self[n] = self;
         args[n] = params == null || params.length == 0 ? NO_ARGS : params.clone();
         res[n] = result;
         kind[n] = k;
         n++;
      }

      int addTable(KahluaTableImpl t) {
         if (nt == tables.length) {
            tables = java.util.Arrays.copyOf(tables, nt * 2);
            writes = java.util.Arrays.copyOf(writes, nt * 2);
            chain = java.util.Arrays.copyOf(chain, nt * 2);
            skeys = java.util.Arrays.copyOf(skeys, nt * 2);
            svals = java.util.Arrays.copyOf(svals, nt * 2);
            scount = java.util.Arrays.copyOf(scount, nt * 2);
         }
         if (skeys[nt] == null) {
            skeys[nt] = new Object[8];
            svals[nt] = new Object[8];
         }
         scount[nt] = 0;
         tables[nt] = t;
         return nt++;
      }

      /** One field the render read (ITER: it walked the table, LEN: its length), deduplicated per table. */
      void addKey(int slot, Object key, Object value) {
         Object[] k = skeys[slot];
         int c = scount[slot];
         for (int i = 0; i < c; i++) {
            if (k[i] == key || key != null && key.equals(k[i])) {
               return; // the first value read is what the render saw first; later reads of a key it set itself follow from it
            }
         }
         if (c == k.length) {
            skeys[slot] = k = java.util.Arrays.copyOf(k, c * 2);
            svals[slot] = java.util.Arrays.copyOf(svals[slot], c * 2);
         }
         k[c] = key;
         svals[slot][c] = value;
         scount[slot] = c + 1;
      }

      void addSub(Log l) {
         for (int i = 0; i < ns; i++) {
            if (subs[i] == l) {
               return;
            }
         }
         if (ns == subs.length) {
            subs = java.util.Arrays.copyOf(subs, ns * 2);
         }
         subs[ns++] = l;
         absorb(l);
      }

      void absorb(Log l) {
         if (l.opaque && !opaque) {
            opaque = true;
            why = l.why;
         }
         mouse |= l.mouse;
         mutating |= l.mutating;
      }

      void markOpaque(String reason) {
         if (!opaque) {
            opaque = true;
            why = reason;
         }
      }
   }

   private static final Object[] NO_ARGS = new Object[0];

   /** Validation skips the learned-noisy entries (the caller sets it per element: false when it is due at the stock rate). */
   public static boolean skipNoisy;
   /** Validation checks tables and pointer reads only (the caller re-invokes an element's getters at a cost-scaled period). */
   public static boolean skipGetters;

   /**
    * The fresh render that a validation mismatch caused drew exactly what was recorded: that entry's change was noise for
    * this element (a game-time getter read by a clock that shows minutes); from now on it is checked at the stock rate.
    */
   public static void learnNoise(Log top) {
      Log l = top.mismatchLog;
      if (l != null && (top.validResult == INVALID || top.validResult == MOUSE) && (top.mismatchA instanceof Method
         || top.mismatchA instanceof KahluaTableImpl && top.mismatchB != ITER)) {
         l.addNoise(top.mismatchA, top.mismatchB); // getters and pointer reads only: a table holds many fields, one noisy field must not hide the others
      }
   }

   /** The render a noisy entry's change caused drew something else: that entry matters after all, check it every frame. */
   public static void unlearnNoise(Log top) {
      Log l = top.mismatchLog;
      if (l != null && top.mismatchNoisy) {
         for (int i = 0; i < l.nNoise; i++) {
            if (l.noiseA[i] == top.mismatchA && l.noiseB[i] == top.mismatchB) {
               l.nNoise--;
               l.noiseA[i] = l.noiseA[l.nNoise];
               l.noiseB[i] = l.noiseB[l.nNoise];
               l.noiseA[l.nNoise] = null;
               l.noiseB[l.nNoise] = null;
               break;
            }
         }
      }
   }

   /** A fresh render of the element owning log starts (UiRetained.freshBegin / childBegin). */
   public static void begin(Log log, UIElement owner) {
      Thread t = Thread.currentThread();
      if (depth == 0) {
         thread = t;
         epoch++;
      } else if (t != thread) {
         return;
      }
      if (depth == stack.length) {
         log.reset(nextId++, owner);
         log.markOpaque("depth");
         return;
      }
      log.reset(nextId++, owner);
      if (owner instanceof zombie.ui.UITextBox2 tb && !tb.isFocused()) {
         // a text box nobody types into draws its text, cursor and frame: those, compared (a focused one blinks: opaque)
         log.addCall(null, tb, null, textBoxSig(tb), K_TEXTBOX);
      } else if (owner != null && owner.getClass() != UIElement.class) {
         // a Java element (Clock, MoodlesUI, a focused text box, the map): its render reads Java state directly, no read set sees it
         log.markOpaque("java " + owner.getClass().getSimpleName());
      }
      stack[depth++] = log;
      on = true;
   }

   /** The render that begin started ended (UiRetained.freshEnd); its table write counts are taken now. */
   public static void end(Log log) {
      if (depth == 0 || stack[depth - 1] != log) {
         return; // begin was refused (another thread, too deep) or the stack was reset after an exception
      }
      depth--;
      stack[depth] = null;
      for (int i = 0; i < log.nt; i++) {
         log.writes[i] = log.tables[i].pzoptWrites;
         log.chain[i] = chainWrites(log.tables[i]);
      }
      log.meta = KahluaTableImpl.pzoptMetaVersion;
      log.recorded = true;
      statCalls += log.n;
      if (log.opaque) {
         statOpaque++;
         int[] c = opaqueWhy.get(log.why);
         if (c == null && opaqueWhy.size() < 200) {
            opaqueWhy.put(log.why, c = new int[1]);
         }
         if (c != null) {
            c[0]++;
         }
      }
      if (depth > 0) {
         stack[depth - 1].addSub(log);
      }
      on = depth > 0;
   }

   /** A child element replayed its recorded commands inside the current fresh render: its log becomes part of it. */
   public static void sub(Log log) {
      if (depth > 0 && Thread.currentThread() == thread) {
         if (!log.recorded) {
            stack[depth - 1].markOpaque("unrecorded child");
         } else {
            stack[depth - 1].addSub(log);
         }
      }
   }

   /** After the UI element loop, or after an exception left logs open: nothing records any more. */
   public static void reset() {
      while (depth > 0) {
         Log l = stack[--depth];
         stack[depth] = null;
         l.markOpaque("unfinished");
      }
      on = false;
      callDepth = 0;
   }

   // ── hooks (KahluaTableImpl, MethodCaller) ─────────────────────────────────────────────────────────────────────

   /** The pseudo-keys of a walk over a table (pairs) and of its length (#). */
   public static final Object ITER = new Object();
   public static final Object LEN = new Object();

   /**
    * KahluaTableImpl: the recording render read key and got value (rawget's whole answer, its class chain included; ITER for
    * a walk, LEN for the length). A validation re-reads exactly these fields when the table or its classes changed.
    */
   public static void read(KahluaTableImpl t, Object key, Object value) {
      if (Thread.currentThread() != thread || t.pzoptDepBorn == epoch || depth == 0) {
         return;
      }
      Log c = stack[depth - 1];
      int slot;
      if (t.pzoptDepMark != c.id) {
         t.pzoptDepMark = c.id;
         slot = c.addTable(t);
         t.pzoptDepSlot = slot;
      } else {
         slot = t.pzoptDepSlot;
      }
      c.addKey(slot, key, value);
   }

   /** The write counts of a table's metatable chain, summed (a class change shows here). */
   static long chainWrites(KahluaTableImpl t) {
      if (t.pzoptChainVer == KahluaTableImpl.pzoptMetaVersion) {
         return t.pzoptChainSum; // no class table changed since it was computed
      }
      boolean wasOn = on;
      on = false; // reading __index is not a read of the render
      try {
         long sum = chainWrites0(t);
         t.pzoptChainSum = sum;
         t.pzoptChainVer = KahluaTableImpl.pzoptMetaVersion;
         return sum;
      } finally {
         on = wasOn;
      }
   }

   private static long chainWrites0(KahluaTableImpl t) {
      long sum = 0L;
      se.krka.kahlua.vm.KahluaTable mt = t.getMetatable();
      for (int i = 0; i < 16 && mt instanceof KahluaTableImpl m; i++) {
         sum += m.pzoptWrites + ((long)i << 40);
         Object idx = m.rawget("__index");
         mt = idx instanceof KahluaTableImpl ix && ix != m ? ix : m.getMetatable();
      }
      return sum;
   }

   /** KahluaTableImpl: a value-changing write while recording. */
   public static void write(KahluaTableImpl t) {
      if (Thread.currentThread() != thread || t.pzoptDepBorn == epoch) {
         return;
      }
      for (int i = 0; i < depth; i++) {
         if (t.pzoptDepMark == stack[i].id) {
            stack[i].mutating = true;
         }
      }
   }

   /** KahluaTableImpl constructor while recording: a table local to the render. */
   public static void created(KahluaTableImpl t) {
      if (Thread.currentThread() == thread) {
         t.pzoptDepBorn = epoch;
      }
   }

   /** MethodCaller.call: before invoking; returns the depth to hand back to call / exit, -1 when not recording here. */
   public static int enter() {
      if (Thread.currentThread() != thread) {
         return -1;
      }
      return callDepth++;
   }

   public static void exit(int d) {
      if (d >= 0) {
         callDepth = d;
      }
   }

   /** MethodCaller.call: the call returned (the outermost one of a render's Java calls; nested ones are inside its result). */
   public static void call(int d, Method method, Object self, Object[] params, boolean hasResult, Object result) {
      if (d != 0 || depth == 0) {
         return;
      }
      Log c = stack[depth - 1];
      String name = method.getName();
      if (hasResult) {
         if (isMouse(name)) {
            c.addCall(method, self, params, result, K_MOUSE);
            c.mouse = true;
         } else if (isClock(name)) {
            c.addCall(method, self, params, result, K_CLOCK);
         } else if (isPure(name)) {
            c.addCall(method, self, params, result, K_PURE);
         } else {
            c.markOpaque(method.getDeclaringClass().getSimpleName() + "." + name);
         }
         return;
      }
      // a void call: drawing or setting UI state; its effect is in the recorded commands or follows from its arguments
      if (isEffect(name)) {
         c.addCall(method, self, params, null, K_EFFECT);
         return;
      }
      if (self instanceof UIElement u) {
         if (u == c.owner && (name.equals("setX") || name.equals("setY"))) {
            c.selfPositioned = true;
         }
         if (name.equals("DrawItemIcon") && params != null && params.length > 0 && params[0] instanceof zombie.inventory.InventoryItem item) {
            c.addCall(method, item, null, itemIconSig(item), K_SIG);
            return;
         }
         if (name.equals("DrawScriptItemIcon")) {
            return; // a script item: static data
         }
         if (!plainArgs(params)) {
            c.markOpaque(method.getDeclaringClass().getSimpleName() + "." + name + "(object)");
         }
         return;
      }
      if (self instanceof zombie.ui.TextManager || self instanceof zombie.core.SpriteRenderer || plainArgs(params) && isDrawOrSet(name)) {
         return;
      }
      if (valueHolder(self) && plainArgs(params)) {
         // a fade or a colour the render keeps and steers: its state follows from the logged arguments (a running fade's
         // time steps show as output changes, which keep the element rendering every frame: uiAnimate)
         return;
      }
      c.markOpaque(method.getDeclaringClass().getSimpleName() + "." + name + "(void)");
   }

   /** MethodCaller.call threw. */
   public static void threw(int d, Method method) {
      if (d == 0 && depth > 0) {
         stack[depth - 1].markOpaque("throws " + method.getName());
      }
   }

   /** Void calls a render makes to bring a game object's derived state up to date before reading it (time-driven, idempotent). */
   static boolean isEffect(String name) {
      return name.equals("updateAge") || name.equals("updateWetness");
   }

   /** What a UITextBox2's render shows besides its geometry (checked by the element's own geometry rules). */
   static Object[] textBoxSig(zombie.ui.UITextBox2 tb) {
      return new Object[]{tb.getText(), tb.isFocused(), tb.isEditable(), tb.getCursorPos(), tb.getCursorLine()};
   }

   /** What UIElement.DrawItemIcon reads of the item (its texture, tint, masks, fluid colour and level). */
   static Object[] itemIconSig(zombie.inventory.InventoryItem item) {
      zombie.entity.components.fluids.FluidContainer fc = item.getFluidContainer();
      if (fc == null && item.getWorldItem() != null) {
         fc = item.getWorldItem().getFluidContainer();
      }
      zombie.core.Color col = fc != null ? fc.getColor() : null;
      return new Object[]{item.getTex(), item.getR(), item.getG(), item.getB(), item.getTextureColorMask(), item.getTextureFluidMask(),
         fc, col == null ? null : col.r, col == null ? null : col.g, col == null ? null : col.b,
         fc == null ? null : fc.getAmount(), fc == null ? null : fc.getCapacity()};
   }

   /** Clock reads: a render that reads one animates with time; like stock it is checked at the stock rate, its data at once. */
   static boolean isClock(String name) {
      return name.equals("getMillisSinceLastRender") || name.equals("getTimestampMs") || name.equals("getTimestamp")
         || name.equals("getTimeInMillis") || name.equals("getCurrentTimeMillis") || name.equals("getMillis");
   }

   static boolean isMouse(String name) {
      return name.equals("getMouseX") || name.equals("getMouseY") || name.equals("getMouseXScaled") || name.equals("getMouseYScaled");
   }

   static boolean isPure(String name) {
      return name.startsWith("get") || name.startsWith("is") || name.startsWith("has") || name.startsWith("can")
         || name.startsWith("contains") || name.equals("size") || name.startsWith("Measure") || name.startsWith("measure")
         || name.equals("instanceof") || name.equals("instof") || name.equals("tostring") || name.equals("equals") || name.startsWith("Get")
         || name.startsWith("Is") || name.startsWith("have") || name.startsWith("are") || name.equals("fraction") || name.startsWith("clamp")
         || name.startsWith("mayDegrade") || name.equals("needToBeLearn") || name.equals("WrapText");
   }

   /** Java objects a render owns and steers through void calls (a button's fade, a colour it interpolates into). */
   private static boolean valueHolder(Object self) {
      return self instanceof zombie.ui.UITransition || self instanceof zombie.core.textures.ColorInfo || self instanceof zombie.core.Color;
   }

   private static boolean isDrawOrSet(String name) {
      return name.startsWith("Draw") || name.startsWith("draw") || name.startsWith("render") || name.startsWith("Render");
   }

   private static boolean plainArgs(Object[] params) {
      if (params == null) {
         return true;
      }
      for (Object p : params) {
         if (p != null && !plain(p)) {
            return false;
         }
      }
      return true;
   }

   /** Values a void drawing / UI call can take without hiding a read: numbers, text, flags, textures, fonts, colours, tables. */
   private static boolean plain(Object p) {
      return p instanceof Number || p instanceof String || p instanceof Boolean || p instanceof Enum<?>
         || p instanceof zombie.core.textures.Texture || p instanceof KahluaTableImpl || p instanceof UIElement
         || p instanceof zombie.core.Color || p instanceof zombie.ui.UIFont || p instanceof se.krka.kahlua.vm.LuaClosure
         || p instanceof zombie.core.textures.ColorInfo || p instanceof zombie.ui.UITransition;
   }

   // ── validation ───────────────────────────────────────────────────────────────────────────────────────────────

   /** VALID, MOUSE (only pointer reads differ), INVALID or OPAQUE; memoised per frame. */
   public static int validate(Log log, long frame) {
      if (!log.recorded || log.opaque) {
         return OPAQUE;
      }
      if (log.validFrame == frame && log.validFull == !skipGetters) {
         return log.validResult;
      }
      long t0 = System.nanoTime();
      boolean wasOn = on;
      on = false; // a validation inside a recording render (a child's) reads tables and calls getters: not the render's reads
      int r;
      try {
         r = check(log, frame);
      } finally {
         on = wasOn;
      }
      log.validNs = System.nanoTime() - t0;
      statValidations++;
      statValidateNs += log.validNs;
      log.validFrame = frame;
      log.validResult = r;
      log.validFull = !skipGetters;
      return r;
   }

   private static int check(Log log, long frame) {
      for (int i = 0; i < log.nt; i++) {
         KahluaTableImpl t = log.tables[i];
         long ch = chainWrites(t);
         if (t.pzoptWrites == log.writes[i] && ch == log.chain[i]) {
            continue;
         }
         // the table or a class changed: are the fields the render read still what it read?
         boolean skipped = false;
         Object[] keys = log.skeys[i];
         Object[] vals = log.svals[i];
         for (int k = 0; k < log.scount[i]; k++) {
            Object key = keys[k];
            boolean same = key == ITER ? false : key == LEN ? same(t.len(), vals[k]) : same2(t.rawget(key), vals[k]);
            if (same) {
               continue;
            }
            boolean noisy = key != ITER && log.nNoise > 0 && log.noisy(t, key);
            if (skipNoisy && noisy) {
               skipped = true;
               continue;
            }
            Object type = t.rawget("Type");
            log.mismatch = "table:" + (type instanceof String ts ? ts : "?") + "." + (key == ITER ? "pairs" : key == LEN ? "#" : String.valueOf(key));
            log.mismatchLog = log;
            log.mismatchA = t;
            log.mismatchB = key;
            log.mismatchNoisy = noisy;
            return INVALID;
         }
         if (!skipped) {
            log.writes[i] = t.pzoptWrites; // the fields it read are unchanged: the fast path holds again
            log.chain[i] = ch;
         }
      }
      int result = VALID;
      Log mouseLog = null;
      Object mouseA = null;
      Object mouseB = null;
      boolean mouseNoisy = false;
      for (int i = 0; i < log.n; i++) {
         if (skipGetters && log.kind[i] != K_MOUSE && log.kind[i] != K_TEXTBOX) {
            continue;
         }
         boolean noisy = log.kind[i] == K_CLOCK || log.nNoise > 0 && log.noisy(log.m[i], log.self[i]);
         if (skipNoisy && noisy) {
            continue;
         }
         Object now;
         if (log.kind[i] == K_TEXTBOX) {
            Object[] was = (Object[])log.res[i];
            Object[] sig = textBoxSig((zombie.ui.UITextBox2)log.self[i]);
            for (int k = 0; k < sig.length; k++) {
               if (!same(sig[k], was[k]) && sig[k] != was[k]) {
                  log.mismatch = "text box";
                  log.mismatchLog = log;
                  log.mismatchA = log.self[i];
                  log.mismatchB = "textbox";
                  log.mismatchNoisy = false;
                  return INVALID;
               }
            }
            continue;
         }
         if (log.kind[i] == K_SIG) {
            Object[] was = (Object[])log.res[i];
            Object[] sig = itemIconSig((zombie.inventory.InventoryItem)log.self[i]);
            for (int k = 0; k < sig.length; k++) {
               if (sig[k] != was[k] && !same(sig[k], was[k])) {
                  log.mismatch = "DrawItemIcon";
                  log.mismatchLog = log;
                  log.mismatchA = log.m[i];
                  log.mismatchB = log.self[i];
                  log.mismatchNoisy = noisy;
                  return INVALID;
               }
            }
            continue;
         }
         try {
            now = log.m[i].invoke(log.self[i], log.args[i]);
         } catch (Throwable t) {
            return INVALID;
         }
         if (log.kind[i] == K_EFFECT) {
            continue;
         }
         if (!same(now, log.res[i])) {
            if (log.kind[i] != K_MOUSE) {
               log.mismatch = log.m[i].getDeclaringClass().getSimpleName() + "." + log.m[i].getName();
               log.mismatchLog = log;
               log.mismatchA = log.m[i];
               log.mismatchB = log.self[i];
               log.mismatchNoisy = noisy;
               return INVALID;
            }
            if (mouseLog == null) {
               mouseLog = log;
               mouseA = log.m[i];
               mouseB = log.self[i];
               mouseNoisy = noisy;
            }
            result = MOUSE;
         }
      }
      for (int i = 0; i < log.ns; i++) {
         int s = validate(log.subs[i], frame);
         if (s == INVALID || s == OPAQUE) {
            log.mismatch = log.subs[i].mismatch;
            log.mismatchLog = s == INVALID ? log.subs[i].mismatchLog : null;
            log.mismatchA = log.subs[i].mismatchA;
            log.mismatchB = log.subs[i].mismatchB;
            log.mismatchNoisy = log.subs[i].mismatchNoisy;
            return INVALID;
         }
         if (s == MOUSE) {
            if (mouseLog == null) {
               mouseLog = log.subs[i].mismatchLog;
               mouseA = log.subs[i].mismatchA;
               mouseB = log.subs[i].mismatchB;
               mouseNoisy = log.subs[i].mismatchNoisy;
            }
            result = MOUSE;
         }
      }
      if (result == MOUSE) {
         log.mismatch = "mouse";
         log.mismatchLog = mouseLog;
         log.mismatchA = mouseA;
         log.mismatchB = mouseB;
         log.mismatchNoisy = mouseNoisy;
      }
      return result;
   }

   /** A table field: numbers, text and flags by value, tables, functions and objects by identity. */
   static boolean same2(Object a, Object b) {
      return a == b || a != null && b != null && (a instanceof Double || a instanceof String || a instanceof Boolean) && a.equals(b);
   }

   static boolean same(Object a, Object b) {
      if (a == b) {
         return true;
      }
      if (a == null || b == null) {
         return false;
      }
      if (a instanceof Number || a instanceof String || a instanceof Boolean || a instanceof Character) {
         return a.equals(b);
      }
      return false;
   }

   /** The per-second line: validations, their total time, calls logged, opaque logs and their most common reasons. */
   static String stats() {
      StringBuilder b = new StringBuilder();
      b.append("validations=").append(statValidations).append(" validate_us=").append(statValidateNs / 1000L)
         .append(" calls=").append(statCalls).append(" opaque=").append(statOpaque);
      if (!opaqueWhy.isEmpty()) {
         java.util.List<java.util.Map.Entry<String, int[]>> l = new java.util.ArrayList<>(opaqueWhy.entrySet());
         l.sort((x, y) -> y.getValue()[0] - x.getValue()[0]);
         b.append(" why=");
         for (int i = 0; i < Math.min(5, l.size()); i++) {
            b.append(i == 0 ? "" : ",").append(l.get(i).getKey()).append(':').append(l.get(i).getValue()[0]);
         }
      }
      statValidations = 0;
      statValidateNs = 0L;
      statCalls = 0;
      statOpaque = 0;
      opaqueWhy.clear();
      return b.toString();
   }
}
