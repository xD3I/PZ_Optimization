package pzopt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.Styles.Style;
import zombie.core.opengl.GLState;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;
import zombie.input.Mouse;
import zombie.ui.UIElement;
import zombie.ui.UIElementInterface;
import zombie.ui.UIManager;

/**
 * uiRetained (2026-09-30, the UI snappiness pass): a retained UI. Stock runs every top-level UI element's Lua
 * prerender / render (and every child's) uiRenderFPS times a second to rebuild the whole UI texture, although most
 * elements draw exactly the same commands as last time (pzopt.UiProfile's "same" count, once the stale a/b/c fields of
 * pooled quads are left out of the comparison). This records each top-level element's draw commands when its Lua runs
 * ("fresh") and, on UI frames where nothing can have changed it, copies the recorded commands into the UI render state
 * instead ("replay"): the render thread gets the same command stream, the game thread skips the Lua.
 *
 * <p>An element renders fresh when: it was never recorded or drew something that cannot be replayed (models, generic
 * drawers such as the world map, shader starts with per-frame data, FBO switches, probes); any mouse button, the wheel,
 * a key or an activated controller changed this frame (then every element is fresh and all back-offs reset); the mouse
 * moves over it (at most uiHoverHz a second) or leaves it; it moved, resized or changed its visible height; it is an
 * inventory page and the Lua flag ISInventoryPage.renderDirty is set; or its timer is due. The timer is the stock rate
 * (uiRenderFPS) while its output keeps changing and doubles with every fresh render that drew exactly the previous
 * commands, up to uiRetainedMaxMs. Elements whose fresh render costs under uiRetainedCheapUs stay at the stock rate.
 *
 * <p>UI frames: at least the stock rate (so world text, tooltips and OnPre/PostUIDraw drawing keep stock timing), plus
 * immediately on the frame of any input that makes an element fresh (uiInstant): stock waited for the next 60 Hz slot.
 *
 * <p>Every top-level element starts with GLState.startFrame() (every cached GL state dirty), so its recorded stream sets
 * all the state it relies on itself and does not depend on which elements ran fresh before it.
 *
 * <p>devUiRetainedCheck: replays are rendered fresh anyway and their commands compared with the recording; a difference is
 * a stale replay (counted, logged as {@code S <name> age_ms} in pzopt-ui.out); the schedule is unchanged by the check.
 */
public final class UiRetained {
   private static final class Rec {
      TextureDraw[] cmds = new TextureDraw[0];
      Style[] styles = new Style[0];
      int n;
      boolean recorded;
      boolean replayable;
      long hash;
      int streak;
      long lastChangeNs;
      long nextFreshNs;
      long lastFreshNs;
      long lastHoverNs;
      long costNs;
      double gx;
      double gy;
      double gw;
      double gh;
      double gmax;
      float bx0;
      float by0;
      float bx1;
      float by1;
      boolean hovered;
      long seenFrame;
      boolean inventory;
      String name;
      boolean child;
      int checkFrom = -1;
      se.krka.kahlua.j2se.KahluaTableImpl table;
      int writes;
      final UiDeps.Log deps = new UiDeps.Log(); // uiVerify: what its last fresh render read
      long dirtyFrame = -1; // uiVerify / uiAnimate: the frame decision found it must render fresh
      boolean pressedInside; // a mouse button went down over it and is still held (a drag that started here)
      boolean invalidFresh; // this fresh render was caused by a read-set mismatch (learn noise if it drew the same)
      int changeRun; // consecutive fresh renders that each drew something else (in motion while > 0)
      boolean moveInvariant; // uiMoveReplay: after a move it drew its previous commands shifted by the move (learned)
      boolean moveReplay; // this frame: replay translated to the new position instead of rendering
      boolean truthMoved; // devUiTruth: this frame's simulated replay was a moved one
      UiDeps.Log noiseFrom; // the log whose mismatch caused it
      long lastGetterCheckNs; // uiVerify: when its getters were last re-invoked (tables and the pointer: every frame)
      long getterCostNs; // what that full validation cost
      String mod; // uiRetainedMods off: the mod whose function the element (or a class it derives from) carries, else null
      boolean modded; // it or an element drawn inside its last fresh render is mod-drawn: rendered at the stock rate
      // devUiTruth: what this frame's schedule decided, and what each schedule shows against the truth
      int decision = D_FRESH;
      int writesBefore;
      long truthHash;
      long truthFrame = -1;
      long shownSched = ABSENT;
      long shownStock = ABSENT;
      final Episode epSched = new Episode();
      final Episode epStock = new Episode();
   }

   /** devUiTruth: one stretch of frames in which a schedule shows something other than the truth. */
   private static final class Episode {
      long startNs;
      long startFrame = -1;
      String cause;
   }

   private static final int D_FRESH = 0;
   private static final int D_REPLAY = 1;
   private static final int D_HOLD = 2;
   private static final long ABSENT = Long.MIN_VALUE + 1;

   private static final IdentityHashMap<UIElementInterface, Rec> recs = new IdentityHashMap<>();
   private static boolean active;
   private static long frameNo;
   private static long now;
   private static int mx;
   private static int my;
   private static boolean mouseMoved;
   private static boolean freshAll;
   private static boolean inventoryDirty;
   private static boolean padActive;
   private static long nextBaseNs;
   private static long nextCleanupNs;
   /** GameKeyboard: counts key state changes (any key pressed or released); the harness UI rig adds its emulated input. */
   public static int keyEvents;
   private static int uiEvents;
   private static int lastUiEvents;
   private static int statUiEvents;
   private static long lastInputNs;

   /** Lua events after which UI elements show something else (inventory, equipment, clothing, skills, health). */
   public static boolean isUiEvent(String name) {
      switch (name) {
         case "OnContainerUpdate":
         case "OnClothingUpdated":
         case "OnEquipPrimary":
         case "OnEquipSecondary":
         case "AddXP":
         case "LevelPerk":
         case "OnPlayerGetDamage":
         case "OnRefreshInventoryWindowContainers":
            return true;
         default:
            return false;
      }
   }

   /** zombie.Lua.Event.trigger of a UI event (any thread that raises Lua events: the game thread). */
   public static void uiEvent() {
      uiEvents++;
   }
   private static int lastKeyEvents;
   private static boolean checking;
   // devUiTruth
   private static boolean truth;
   private static boolean schedFrame;
   private static boolean stockFrame;
   private static float stockAcc;
   private static boolean frameClickKey;
   private static boolean frameEvent;
   private static final int[] truthStale = new int[4]; // element-frames stale (sched, stock), frames with any (sched, stock)
   private static int truthFrames;
   private static int truthNear; // devUiTruth: move-replayed elements within 1 px of the truth (not counted stale)
   private static int statFresh;
   private static int statReplay;
   private static int statChecked;
   private static int statStale;
   private static int statUiFrames;
   private static int statInputFrames;

   private UiRetained() {
   }

   private static long baseNs() {
      return 1_000_000_000L / Math.max(1, Core.getInstance().getOptionUIRenderFPS());
   }

   /**
    * GameWindow, right after UIManager.update: read this frame's input and decide whether this frame renders the UI
    * (Core.StartFrameUI reads the accumulator we set).
    */
   public static void decideFrame() {
      if (!Config.UI_RETAINED || !UIManager.useUiFbo || !Overrides.enabled()) {
         if (active) {
            active = false;
            recs.clear();
         }
         return;
      }
      active = true;
      truth = Config.DEV_UI_TRUTH;
      checking = Config.DEV_UI_RETAINED_CHECK && !truth;
      now = System.nanoTime();
      frameNo++;
      int x = Mouse.getXA();
      int y = Mouse.getYA();
      mouseMoved = x != mx || y != my;
      mx = x;
      my = y;
      boolean click = Mouse.getWheelState() != 0;
      for (int b = 0; b < 3 && !click; b++) {
         click = Mouse.isButtonPressed(b) || Mouse.isButtonReleased(b);
      }
      boolean key = keyEvents != lastKeyEvents;
      lastKeyEvents = keyEvents;
      boolean event = uiEvents != lastUiEvents;
      statUiEvents += uiEvents - lastUiEvents;
      lastUiEvents = uiEvents;
      padActive = GameWindow.activatedJoyPad != null;
      buttonHeld = Mouse.isButtonDown(0) || Mouse.isButtonDown(1);
      if (Mouse.isButtonPressed(0) || Mouse.isButtonPressed(1)) {
         for (Map.Entry<UIElementInterface, Rec> en : recs.entrySet()) {
            en.getValue().pressedInside = en.getValue().recorded && inside(en.getKey(), en.getValue());
         }
      }
      freshAll = click || key || event;
      if (click || key) {
         lastInputNs = now;
      }
      // ISInventoryPage.renderDirty is cleared by a visible inventory page's prerender: with both pages hidden it stays set and
      // made every frame a UI frame (the HUD at 240 UI frames a second, 2026-10-14); it only counts while a page shows
      inventoryDirty = luaFlag("ISInventoryPage", "renderDirty") && inventoryShown();
      boolean due = now >= nextBaseNs;
      boolean input = freshAll || inventoryDirty;
      if (!due) {
         trig[0] += freshAll ? 1 : 0;
         trig[1] += inventoryDirty ? 1 : 0;
      }
      if (!due && !input) {
         input = anyInputFresh();
         trig[2] += input ? 1 : 0;
      }
      if ((Config.UI_VERIFY || Config.UI_ANIMATE) && now >= nextOptionalNs && anyDirty()) {
         trig[3] += !due && !input ? 1 : 0;
         input = true; // uiVerify / uiAnimate: an element shows something else now: render the UI in this frame
      }
      if (due || input) {
         // uiMaxHz: the next UI frame no input asked for waits for the next refresh tick (a tick grid, not "a refresh
         // after this one": at 240 fps on a 165 Hz display that rule gave every second frame, 120 Hz)
         long period = minOptionalNs();
         nextOptionalNs = period <= 0L ? 0L : Math.max(nextOptionalNs + period, now - period);
         Core.getInstance().uiRenderAccumulator = 1.0e6F;
         if (due) {
            nextBaseNs = now + baseNs();
         }
         if (!due) {
            statInputFrames++;
         }
         statUiFrames++;
      } else {
         Core.getInstance().uiRenderAccumulator = -1.0e6F;
      }
      if (truth) {
         // the truth renders every frame; the schedule's choice is kept for the simulation, stock's is simulated
         schedFrame = due || input;
         frameClickKey = click || key;
         frameEvent = event;
         Core.getInstance().uiRenderAccumulator = 1.0e6F;
         float step = 30.0F / Math.max(1, Core.getInstance().getOptionUIRenderFPS());
         stockAcc += zombie.GameTime.getInstance().getThirtyFPSMultiplier();
         stockFrame = stockAcc >= step;
         if (stockFrame) {
            stockAcc %= step;
         }
      }
      if (now >= nextCleanupNs) {
         cleanup();
      }
   }

   /** Does the mouse make some recorded element fresh this frame (hover in / out at uiHoverHz, capture)? */
   private static boolean anyInputFresh() {
      if (!mouseMoved) {
         return false;
      }
      ArrayList<UIElementInterface> ui = UIManager.getUI();
      for (int i = 0; i < ui.size(); i++) {
         UIElementInterface e = ui.get(i);
         Rec r = recs.get(e);
         if (r == null || !r.recorded) {
            continue;
         }
         boolean inside = inside(e, r);
         if (inside != r.hovered) {
            return true;
         }
         if (Config.UI_HOVER_HZ > 0 && (inside || e.isCapture()) && now - r.lastHoverNs >= hoverNs() && !verified(r)) {
            return true; // uiHoverHz > 0: the old rate rule (0: anyDirty decides, inside the frame's room)
         }
      }
      return false;
   }

   /** uiVerify: the element's replay is decided by its read set (no timers needed for pointer or passive changes). */
   private static boolean verified(Rec r) {
      return Config.UI_VERIFY && r.deps.verifiable();
   }

   /** uiAnimate: its output changed in its last fresh renders (it is moving): fresh every frame while it does. */
   private static boolean animating(Rec r) {
      // its last fresh render drew something else than the one before (a scroll easing, a fade, a moving map) and that was
      // recently: render again next frame; one identical render ends it
      return Config.UI_ANIMATE && r.recorded && r.replayable && r.changeRun > 0 && now - r.lastChangeNs < 100_000_000L;
   }

   /**
    * Does the pointer's movement matter to this element: over it (or just left it), capture, it follows the pointer, or a
    * button pressed over it is still held (dragging an item, a window, the map).
    */
   private static boolean mouseRelevant(UIElementInterface e, Rec r) {
      boolean inside = inside(e, r);
      return inside || inside != r.hovered || e.isCapture() || r.deps.selfPositioned() || r.pressedInside && buttonHeld;
   }

   private static boolean buttonHeld;
   // uiSlack: the game thread's frame work without the UI's fresh renders, and the fixed cost of a UI frame
   private static long freshNsThisFrame;
   private static long truthNsThisFrame; // devUiTruth's extra renders this frame
   private static long baseWorkEma;
   private static long uiFrameOverheadEma;
   private static int statDenied;

   /** Pacing.limiterWait: the game thread finished this frame's work after workNs and now waits for the cap. */
   public static void frameWorkDone(long workNs) {
      long base = Math.max(0L, workNs - freshNsThisFrame - truthNsThisFrame);
      truthNsThisFrame = 0L;
      baseWorkEma = baseWorkEma == 0L ? base : baseWorkEma + (base - baseWorkEma) / 8;
      freshNsThisFrame = 0L;
   }

   private static long uiRenderStartNs;
   private static long uiRenderFreshAtStart;

   /** UIManager.render on a UI frame, start and end: its time without the fresh renders is the overhead of a UI frame. */
   public static void uiRenderBegin() {
      uiRenderStartNs = System.nanoTime();
      uiRenderFreshAtStart = freshNsThisFrame + truthNsThisFrame;
   }

   public static void uiRenderEnd() {
      if (!active || uiRenderStartNs == 0L) {
         return;
      }
      long o = Math.max(0L, System.nanoTime() - uiRenderStartNs - (freshNsThisFrame + truthNsThisFrame - uiRenderFreshAtStart));
      uiFrameOverheadEma = uiFrameOverheadEma == 0L ? o : uiFrameOverheadEma + (o - uiFrameOverheadEma) / 8;
      uiRenderStartNs = 0L;
   }

   /** What the frame has left before the cap for UI renders nobody clicked for (0 uncapped or unknown: stock rate). */
   private static long slackNs() {
      long cap = Pacing.capIntervalNs();
      if (baseWorkEma <= 0L) {
         return 0L;
      }
      if (cap <= 0L) {
         return baseWorkEma / 5L; // uncapped: a fifth of the frame for the UI's renders on pointer moves and motion
      }
      return cap * 85L / 100L - baseWorkEma;
   }
   private static long nextOptionalNs;

   /**
    * uiMaxHz: UI frames that no click, key or UI event caused (pointer moves, animations, read-set changes) come at most
    * this often; 0 = the display's refresh rate (a UI frame the display never shows only costs).
    */
   private static long minOptionalNs() {
      double hz = Config.UI_MAX_HZ > 0 ? Config.UI_MAX_HZ : Config.UI_MAX_HZ < 0 ? 0.0 : Vrr.refreshHz();
      return hz <= 0.0 ? 0L : (long)(1e9 / hz);
   }
   private static final java.util.HashMap<String, Integer> dirtyWhy = new java.util.HashMap<>();

   /** uiVerify: the read set says rendering it again would draw something else (memoised for the frame). */
   private static boolean depsChanged(UIElementInterface e, Rec r) {
      UiDeps.skipNoisy = now - r.lastFreshNs < baseNs(); // learned-noisy reads are checked at the stock rate
      // the getters (Java state: item names, stats) are re-invoked at a period that keeps their cost near 1 % of the time,
      // never longer than the stock interval; tables and the pointer every frame
      long period = Math.min(baseNs(), r.getterCostNs * 100L);
      boolean full = now - r.lastGetterCheckNs >= period;
      UiDeps.skipGetters = !full;
      int v = UiDeps.validate(r.deps, frameNo);
      UiDeps.skipGetters = false;
      if (full && r.deps.validFrame == frameNo) {
         r.lastGetterCheckNs = now;
         r.getterCostNs = r.deps.validNs;
      }
      return v == UiDeps.INVALID || v == UiDeps.MOUSE && mouseRelevant(e, r);
   }

   /**
    * uiVerify / uiAnimate, every frame: does some visible top-level element need a fresh render now (its read set changed,
    * the pointer moved over a pointer-reading element, or it is animating)? Marks each such element for this frame.
    */
   private static boolean anyDirty() {
      boolean any = false;
      long budget = slackNs();
      long spent = uiFrameOverheadEma;
      ArrayList<UIElementInterface> ui = UIManager.getUI();
      for (int i = 0; i < ui.size(); i++) {
         UIElementInterface e = ui.get(i);
         Rec r = recs.get(e);
         if (r == null || !r.recorded || !e.isVisible()) {
            continue;
         }
         boolean anim = animating(r); // in motion: renders anyway, no need to check its read set
         boolean dirty = !anim && (verified(r) ? depsChanged(e, r) : mouseMoved && Config.UI_HOVER_HZ <= 0 && mouseRelevant(e, r));
         if (anim || dirty) {
            // uiSlack: a fresh render no click or key asked for fits in what the frame has left before the cap, or the
            // element has waited the stock interval (never later than stock); otherwise it waits for a frame with room
            boolean floor = now - r.lastFreshNs >= baseNs();
            if (!floor && Config.UI_SLACK && spent + r.costNs > budget) {
               statDenied++;
               continue;
            }
            spent += r.costNs;
            r.dirtyFrame = frameNo;
            any = true;
            if (UiProfile.enabled()) {
               String why = anim ? "anim" : !verified(r) ? "hover" : r.deps.validResult == UiDeps.MOUSE ? "mouse" : String.valueOf(r.deps.mismatch);
               dirtyWhy.merge(r.name + ":" + why, 1, Integer::sum);
            }
         }
      }
      return any;
   }

   private static long hoverNs() {
      return Config.UI_HOVER_HZ <= 0 ? 0L : 1_000_000_000L / Config.UI_HOVER_HZ;
   }

   private static boolean inside(UIElementInterface e, Rec r) {
      if (mx >= r.bx0 && mx < r.bx1 && my >= r.by0 && my < r.by1) {
         return true;
      }
      double ex = absX(e);
      double ey = absY(e);
      return mx >= ex && my >= ey && mx < ex + e.getWidth() && my < ey + e.getHeight();
   }

   /** Screen position (a child's getX is relative to its parent; its recorded commands are absolute). */
   private static double absX(UIElementInterface e) {
      return e instanceof UIElement u ? u.getAbsoluteX() : e.getX();
   }

   private static double absY(UIElementInterface e) {
      return e instanceof UIElement u ? u.getAbsoluteY() : e.getY();
   }

   private static boolean inventoryShown() {
      for (Map.Entry<UIElementInterface, Rec> en : recs.entrySet()) {
         if (en.getValue().inventory && en.getKey().isVisible()) {
            return true;
         }
      }
      return false;
   }

   private static boolean luaFlag(String table, String key) {
      try {
         Object t = LuaManager.env == null ? null : LuaManager.env.rawget(table);
         if (t instanceof KahluaTable kt) {
            return kt.rawget(key) == Boolean.TRUE;
         }
      } catch (Throwable ignored) {
      }
      return false;
   }

   private static GenericSpriteRenderState state() {
      return SpriteRenderer.instance.states.getPopulating().stateUi;
   }

   private static Rec rec(UIElementInterface e) {
      Rec r = recs.get(e);
      if (r == null) {
         r = new Rec();
         r.name = UiProfile.name(e);
         r.inventory = "ISInventoryPage".equals(r.name);
         r.mod = modDrawing(e);
         r.modded = r.mod != null;
         recs.put(e, r);
      }
      r.seenFrame = frameNo;
      return r;
   }

   public static boolean active() {
      return active;
   }

   /**
    * UIManager.render, for each top-level element: true when its recorded commands were copied into the UI render state
    * (the caller skips element.render()); false when the caller must render it fresh between freshBegin / freshEnd.
    */
   public static boolean replay(UIElementInterface e) {
      if (!active) {
         return false;
      }
      GLState.startFrame();
      if (truth && !schedFrame) {
         if (e.isVisible()) { // the schedule renders no UI this frame: the screen keeps what it showed
            Rec r = rec(e);
            r.decision = D_HOLD;
            r.writesBefore = writesOf(e);
         }
         return false;
      }
      if (!e.isVisible()) {
         Rec hidden = recs.get(e);
         if (hidden != null) {
            hidden.recorded = false; // shown again later: render it fresh, its old commands may be outdated
         }
         return false;
      }
      Rec r = rec(e);
      boolean inside = inside(e, r);
      boolean fresh = needsFresh(e, r, inside);
      r.hovered = inside;
      if (fresh) {
         r.decision = D_FRESH;
         return false;
      }
      if (truth) {
         r.decision = D_REPLAY; // rendered fresh for the truth; the schedule shows its recording
         r.writesBefore = writesOf(e);
         if (r.moveReplay) { // what the schedule shows: the recording moved, as the real replay below does
            r.moveReplay = false;
            translate(r, (float)(absX(e) - r.gx), (float)(absY(e) - r.gy));
            r.gx = absX(e);
            r.gy = absY(e);
            r.truthMoved = true;
         }
         return false;
      }
      if (checking) {
         r.checkFrom = state().numSprites;
         return false;
      }
      if (r.moveReplay) {
         r.moveReplay = false;
         translate(r, (float)(absX(e) - r.gx), (float)(absY(e) - r.gy));
         r.gx = absX(e);
         r.gy = absY(e);
         statMoveReplay++;
      }
      copyOut(r);
      statReplay++;
      return true;
   }

   private static int statMoveReplay;
   private static final int[] trig = new int[4]; // UI frames off the stock beat by trigger: input / inventory flag / hover / dirty

   /** uiMoveReplay: the recording moved by (dx, dy) in place (quads, its bounds, its hash). */
   private static void translate(Rec r, float dx, float dy) {
      for (int i = 0; i < r.n; i++) {
         TextureDraw d = r.cmds[i];
         if (d.type == TextureDraw.Type.glDraw) {
            d.x0 += dx;
            d.x1 += dx;
            d.x2 += dx;
            d.x3 += dx;
            d.y0 += dy;
            d.y1 += dy;
            d.y2 += dy;
            d.y3 += dy;
         }
      }
      r.bx0 += dx;
      r.bx1 += dx;
      r.by0 += dy;
      r.by1 += dy;
      r.hash = hash(r.cmds, 0, r.n);
   }

   /**
    * uiMoveReplay's learning: are the new commands the recording shifted by (dx, dy)? Positions within 1/64 px (the fresh
    * render adds the offset in double before rounding to float), everything else (textures, uvs, colours, state) exact.
    */
   private static boolean sameShifted(Rec r, TextureDraw[] now, int from, int to, float dx, float dy) {
      if (to - from != r.n) {
         moveDiff(r, "count " + r.n + "->" + (to - from));
         return false;
      }
      for (int i = 0; i < r.n; i++) {
         TextureDraw a = r.cmds[i];
         TextureDraw b = now[from + i];
         if (b == null || a.type != b.type) {
            return false;
         }
         if (a.type == TextureDraw.Type.glDraw) {
            if (!near(a.x0 + dx, b.x0) || !near(a.x1 + dx, b.x1) || !near(a.x2 + dx, b.x2) || !near(a.x3 + dx, b.x3)
               || !near(a.y0 + dy, b.y0) || !near(a.y1 + dy, b.y1) || !near(a.y2 + dy, b.y2) || !near(a.y3 + dy, b.y3)
               || a.tex != b.tex || a.tex1 != b.tex1 || a.tex2 != b.tex2 || a.u0 != b.u0 || a.v0 != b.v0 || a.u2 != b.u2 || a.v2 != b.v2
               || a.col0 != b.col0 || a.col1 != b.col1 || a.col2 != b.col2 || a.col3 != b.col3 || a.flipped != b.flipped) {
               moveDiff(r, "#" + i + " quad x0 " + (a.x0 + dx) + "/" + b.x0 + " y0 " + (a.y0 + dy) + "/" + b.y0 + " tex " + (a.tex == b.tex) + " uv " + (a.u0 == b.u0 && a.v0 == b.v0) + " col " + (a.col0 == b.col0));
               return false;
            }
         } else if (!java.util.Arrays.equals(key(a), key(b))) {
            moveDiff(r, "#" + i + " " + a.type + " " + java.util.Arrays.toString(key(a)) + "/" + java.util.Arrays.toString(key(b)));
            return false;
         }
      }
      return true;
   }

   private static int moveDiffs;

   private static void moveDiff(Rec r, String what) {
      if (UiProfile.enabled() && moveDiffs < 40) {
         moveDiffs++;
         Log.info("ui move replay: " + r.name + " not shifted: " + what);
      }
   }

   private static boolean near(float a, float b) {
      return Math.abs(a - b) <= 1.0F; // the game rounds text to whole pixels: a move can land a glyph 1 px off; the stock-rate fresh render re-anchors it
   }

   /**
    * UIElement.render of a child element (after its own visibility / clipping checks, so it would draw): true when its
    * recorded commands were copied in and the child's Lua and its subtree are skipped (uiRetainedChildren).
    */
   public static boolean replayChild(UIElement e) {
      if (!active || !Config.UI_RETAINED_CHILDREN || truth) {
         return false;
      }
      GLState.startFrame();
      Rec r = rec(e);
      r.child = true;
      boolean inside = inside(e, r);
      boolean fresh = needsFresh(e, r, inside);
      r.hovered = inside;
      if (fresh) {
         return false;
      }
      if (checking) {
         r.checkFrom = state().numSprites;
         return false;
      }
      copyOut(r);
      if (Config.UI_VERIFY) {
         UiDeps.sub(r.deps); // the parent's fresh render now shows what this child's read set produced
      }
      statReplay++;
      return true;
   }

   /** UIElement.render of a child rendered fresh: start of its commands (-1 when not retaining children). */
   public static int childBegin(UIElement e) {
      if (!active || !Config.UI_RETAINED_CHILDREN || truth) {
         return -1;
      }
      int from = freshBegin();
      if (Config.UI_VERIFY) {
         UiDeps.begin(rec(e).deps, e);
      }
      return from;
   }

   private static boolean needsFresh(UIElementInterface e, Rec r, boolean inside) {
      if (!r.recorded || !r.replayable || freshAll || padActive || r.modded) {
         return true; // r.modded: a mod draws in it; its drawing may change with state the replay cannot see (stock rate)
      }
      if (r.table != null && r.table.pzoptWrites != r.writes && !verified(r)) {
         return true; // its Lua table changed since it was recorded (a field set by its update, its parent, a handler); a read
                      // set knows which of its fields the render read (a dragged window's own x / y are not among them)
      }
      boolean verified = verified(r);
      if (!verified && (r.streak < Config.UI_RETAINED_STREAK || now - r.lastChangeNs < Config.UI_RETAINED_STATIC_MS * 1_000_000L)) {
         return true; // changed recently (an animation, a fade): fresh on every UI frame (read-set elements: uiAnimate)
      }
      if (!verified && now - lastInputNs < Config.UI_RETAINED_SETTLE_MS * 1_000_000L) {
         return true; // just after input its consequences arrive (a transfer ends, an item gets equipped): stock rate
      }
      if (r.costNs < (r.child ? Config.UI_RETAINED_CHILD_CHEAP_US : Config.UI_RETAINED_CHEAP_US) * 1000L) {
         return true; // cheap (Java HUD pieces such as the animated moodles): stock behaviour, fresh on every UI frame
      }
      if (r.inventory && inventoryDirty) {
         return true;
      }
      if (e.getWidth() != r.gw || e.getHeight() != r.gh || e.getMaxDrawHeight() != r.gmax) {
         return true;
      }
      if (absX(e) != r.gx || absY(e) != r.gy) {
         // uiMoveReplay: a window being dragged draws the same thing elsewhere; replay it shifted (a compositor moving a
         // layer) while it has rendered fresh within the stock interval, so content changes during the move come at the
         // stock rate at the latest
         if (Config.UI_MOVE_REPLAY && r.moveInvariant && !r.child && now - r.lastFreshNs < baseNs()) {
            r.moveReplay = true;
            return false;
         }
         return true;
      }
      if (now >= r.nextFreshNs) {
         return true;
      }
      if (inside != r.hovered) {
         return true;
      }
      if (verified) {
         boolean changed = r.dirtyFrame == frameNo || depsChanged(e, r); // uiVerify: exactly when what it read changed
         r.invalidFresh = changed && (r.deps.validResult == UiDeps.INVALID || r.deps.validResult == UiDeps.MOUSE) && r.deps.validFrame == frameNo;
         return changed;
      }
      if (Config.UI_HOVER_HZ <= 0) {
         return r.dirtyFrame == frameNo; // the pointer moved over it and the frame had room (anyDirty)
      }
      return (inside && mouseMoved || e.isCapture() || mouseMoved && r.pressedInside && buttonHeld) && now - r.lastHoverNs >= hoverNs();
   }

   public static int freshBegin() {
      if (!active) {
         return -1;
      }
      GLState.startFrame();
      spanMods.add(Boolean.FALSE);
      return state().numSprites;
   }

   /** UIManager.render: a top-level element renders fresh (its read set is recorded from here to freshEnd). */
   public static int freshBegin(UIElementInterface e) {
      int from = freshBegin();
      if (from >= 0 && Config.UI_VERIFY && e instanceof UIElement u && e.isVisible()) {
         Rec r = rec(e);
         if (!truth || r.decision == D_FRESH) {
            UiDeps.begin(r.deps, u);
         }
      }
      return from;
   }

   public static void freshEnd(UIElementInterface e, int from, long costNs) {
      if (!active || from < 0) {
         return;
      }
      if (Config.UI_VERIFY) {
         Rec lr = recs.get(e);
         if (lr != null) {
            UiDeps.end(lr.deps);
         }
      }
      boolean innerMods = !spanMods.isEmpty() && spanMods.remove(spanMods.size() - 1);
      GenericSpriteRenderState st = state();
      int to = st.numSprites;
      if (!e.isVisible()) {
         return;
      }
      Rec r = rec(e);
      if (truth && r.decision != D_FRESH) {
         // devUiTruth: a render the schedule would not have run; it gives the truth, the schedule learns nothing from it
         long th = hash(st.sprite, from, to);
         r.truthHash = th;
         r.truthFrame = frameNo;
         if (r.decision == D_REPLAY) {
            r.shownSched = r.hash;
            if (r.truthMoved) {
               r.truthMoved = false;
               if (th != r.hash && sameShifted(r, st.sprite, from, to, 0.0F, 0.0F)) {
                  r.shownSched = th; // uiMoveReplay drew it within 1 px of the truth (a rounded glyph): counted apart
                  truthNear++;
               }
            }
         }
         if (r.table != null) {
            r.writes += r.table.pzoptWrites - r.writesBefore; // what this extra render wrote into its own table
         }
         if (stockFrame) {
            r.shownStock = th;
         }
         r.decision = D_FRESH;
         truthNsThisFrame += costNs; // not the schedule's work: kept out of uiSlack's accounting
         return;
      }
      r.modded = r.mod != null || innerMods;
      if (r.modded && !spanMods.isEmpty()) {
         spanMods.set(spanMods.size() - 1, Boolean.TRUE); // the element whose fresh render drew this one is mod-drawn too
      }
      long h = hash(st.sprite, from, to);
      if (truth) {
         r.truthHash = h;
         r.truthFrame = frameNo;
         r.shownSched = h;
         if (stockFrame) {
            r.shownStock = h;
         }
      }
      if (checking && r.checkFrom == from) {
         // devUiRetainedCheck: this render stood in for a replay; compare, keep the schedule as it was
         r.checkFrom = -1;
         statChecked++;
         if (h != r.hash) {
            statStale++;
            UiProfile.staleReplay(r.name, (now - r.lastFreshNs) / 1_000_000L);
            record(r, st, from, to, h);
            r.streak = 0;
            r.nextFreshNs = Math.min(r.nextFreshNs, now + baseNs());
         }
         return;
      }
      r.checkFrom = -1;
      statFresh++;
      boolean same = r.recorded && h == r.hash;
      if (Config.UI_MOVE_REPLAY && r.recorded && !r.child && r.replayable && (absX(e) != r.gx || absY(e) != r.gy)
         && e.getWidth() == r.gw && e.getHeight() == r.gh) {
         // it moved since its recording: did it draw the recording shifted by the move? (learn / unlearn move invariance)
         r.moveInvariant = sameShifted(r, st.sprite, from, to, (float)(absX(e) - r.gx), (float)(absY(e) - r.gy));
         r.invalidFresh = false; // its own x / y changed; no noise to learn from this render
      }
      if (r.invalidFresh && Config.UI_VERIFY) {
         if (same) {
            UiDeps.learnNoise(r.deps); // the read that changed did not change what it draws: check it at the stock rate
         } else {
            UiDeps.unlearnNoise(r.deps); // a learned-noisy read changed what it draws after all: every frame again
         }
      }
      if (!r.child) {
         freshNsThisFrame += costNs;
      }
      r.invalidFresh = false;
      record(r, st, from, to, h);
      r.streak = same ? r.streak + 1 : 0;
      r.changeRun = same ? 0 : r.changeRun + 1;
      if (!same) {
         r.lastChangeNs = now;
      }
      r.costNs = costNs;
      r.lastFreshNs = now;
      if (r.hovered || e.isCapture()) {
         r.lastHoverNs = now;
      }
      if (freshAll && !verified(r)) {
         r.streak = 0; // after input, elements without a read set keep the stock rate for a while (uiVerify's know exactly)
      }
      long base = baseNs();
      long interval = base;
      if (r.streak > 0 && costNs >= Config.UI_RETAINED_CHEAP_US * 1000L && !(Config.UI_VERIFY && !r.deps.verifiable())) {
         // back off while it draws the same; with uiVerify only elements whose read set is checked do (an opaque one, a Java
         // element such as the clock, renders at the stock rate: never later than stock)
         interval = Math.min(Config.UI_RETAINED_MAX_MS * 1_000_000L, base << Math.min(r.streak, 6));
      }
      r.nextFreshNs = now + Math.max(base, interval);
      r.gx = absX(e);
      r.gy = absY(e);
      if (e instanceof UIElement u && u.getTable() instanceof se.krka.kahlua.j2se.KahluaTableImpl t) {
         r.table = t;
         r.writes = t.pzoptWrites; // after its own render: what it wrote while rendering does not count
      }
      r.gw = e.getWidth();
      r.gh = e.getHeight();
      r.gmax = e.getMaxDrawHeight();
   }

   /** After the element loop: the tooltip / paused text / fade overlay after it start from a clean GL state cache. */
   public static void loopEnd() {
      if (active) {
         GLState.startFrame();
         UiDeps.reset();
         if (truth) {
            truthCompare();
         }
      }
      spanMods.clear(); // a render that threw between freshBegin and freshEnd leaves its entry behind
   }

   private static int writesOf(UIElementInterface e) {
      return e instanceof UIElement u && u.getTable() instanceof se.krka.kahlua.j2se.KahluaTableImpl t ? t.pzoptWrites : 0;
   }

   /**
    * devUiTruth, after the element loop: per top-level element, the truth (this frame's fresh output, or absent) against
    * what the uiRetained schedule and stock's schedule show; a stretch of differing frames is an episode, logged when it
    * ends with the cause seen on its first frame (input = click / key, event = UI Lua event, hover = the mouse moved over
    * the element, mouse = the mouse moved elsewhere, passive = nothing the player did).
    */
   private static void truthCompare() {
      truthFrames++;
      boolean anySched = false;
      boolean anyStock = false;
      for (Map.Entry<UIElementInterface, Rec> en : recs.entrySet()) {
         Rec r = en.getValue();
         if (r.child) {
            continue;
         }
         long t = r.truthFrame == frameNo ? r.truthHash : ABSENT;
         if (t == ABSENT) {
            if (schedFrame) {
               r.shownSched = ABSENT;
            }
            if (stockFrame) {
               r.shownStock = ABSENT;
            }
         }
         if (t == ABSENT && r.shownSched == ABSENT && r.shownStock == ABSENT && r.epSched.startFrame < 0 && r.epStock.startFrame < 0) {
            continue;
         }
         String cause = null;
         boolean s = r.shownSched != t;
         boolean k = r.shownStock != t;
         if (s || k || r.epSched.startFrame >= 0 || r.epStock.startFrame >= 0) {
            cause = cause(en.getKey(), r);
         }
         anySched |= episode(r, r.epSched, s, true, cause);
         anyStock |= episode(r, r.epStock, k, false, cause);
      }
      if (anySched) {
         truthStale[2]++;
      }
      if (anyStock) {
         truthStale[3]++;
      }
   }

   private static boolean episode(Rec r, Episode ep, boolean stale, boolean sched, String cause) {
      if (stale) {
         truthStale[sched ? 0 : 1]++;
         if (ep.startFrame < 0) {
            ep.startFrame = frameNo;
            ep.startNs = now;
            ep.cause = cause;
         }
         return true;
      }
      if (ep.startFrame >= 0) {
         UiProfile.truthEpisode(sched ? "s" : "k", r.name, ep.cause, frameNo - ep.startFrame, (now - ep.startNs) / 1000L);
         ep.startFrame = -1;
      }
      return false;
   }

   private static String cause(UIElementInterface e, Rec r) {
      if (frameClickKey) {
         return "input";
      }
      if (frameEvent) {
         return "event";
      }
      if (mouseMoved) {
         return inside(e, r) || e.isCapture() ? "hover" : "mouse";
      }
      return "passive";
   }

   // ── elements a mod draws (uiRetainedMods off, mod compatibility 2026-10-01) ─────────────────────────────────────
   //
   // A mod's drawing may follow state the replay schedule cannot see (its own Lua locals, Java getters), so an element
   // carrying a mod's drawing function (render* / prerender* / postrender*, in its own table or a class up its __index chain:
   // a mod replacing ISInventoryPane.renderdetails makes every inventory pane mod-drawn) renders fresh at the stock rate.
   // So does a vanilla window whose last fresh render drew a mod-drawn child (spanMods: one flag per open freshBegin).
   // Other mod functions do not count: Inventory Tetris adds helpers to ISUIElement, the base of every element, and a
   // button's onclick from a mod draws nothing (the first matrix run, 2026-10-01, had the whole UI at the stock rate).

   private static final ArrayList<Boolean> spanMods = new ArrayList<>();
   private static final IdentityHashMap<KahluaTable, Object[]> tableMods = new IdentityHashMap<>(); // {writes, mod id or ""}
   private static final java.util.HashSet<String> loggedMods = new java.util.HashSet<>();

   /** The mod whose function this element carries, or null (also null with uiRetainedMods on). */
   private static String modDrawing(UIElementInterface e) {
      if (Config.UI_RETAINED_MODS || !(e instanceof UIElement u) || !(u.getTable() instanceof KahluaTable t)) {
         return null;
      }
      KahluaTable cur = t;
      for (int depth = 0; cur != null && depth < 16; depth++) {
         String mod = tableMod(cur, depth == 0);
         if (mod != null) {
            if (loggedMods.size() < 64 && loggedMods.add(UiProfile.name(e) + mod)) {
               Log.info("ui retained: " + UiProfile.name(e) + " carries a function of " + mod + "; rendered at the stock rate (uiRetainedMods=false)");
            }
            return mod;
         }
         KahluaTable meta = cur.getMetatable();
         Object index = meta != null ? meta.rawget("__index") : null;
         cur = index instanceof KahluaTable k && k != cur ? k : null;
      }
      return null;
   }

   /**
    * A function of an element's render path: render / prerender / postrender and their helpers (renderdetails ...). Not
    * the draw primitives (drawText, drawTexture...): they draw what they are given, so their output follows the element's
    * own inputs (Inventory Tetris adds drawTextureCenteredAndSquare to ISUIElement, which no vanilla element calls).
    */
   static boolean drawing(String name) {
      String n = name.toLowerCase(java.util.Locale.ROOT);
      return n.startsWith("render") || n.startsWith("prerender") || n.startsWith("postrender");
   }

   /** The first mod-defined drawing function of a table; class tables are cached until a write (a function replaced). */
   private static String tableMod(KahluaTable t, boolean instance) {
      int writes = t instanceof se.krka.kahlua.j2se.KahluaTableImpl ti ? ti.pzoptWrites : -1;
      Object[] cached = instance ? null : tableMods.get(t);
      if (cached != null && (Integer) cached[0] == writes) {
         return ((String) cached[1]).isEmpty() ? null : (String) cached[1];
      }
      String found = "";
      se.krka.kahlua.vm.KahluaTableIterator it = t.iterator();
      while (it.advance()) {
         Object v = it.getValue();
         if (v instanceof se.krka.kahlua.vm.LuaClosure && it.getKey() instanceof String k && drawing(k) && LuaOrigin.fromMod(v)) {
            String o = LuaOrigin.ofFunction(v);
            found = o.startsWith("mod:") ? o.substring(4) : o;
            break;
         }
      }
      if (!instance) {
         tableMods.put(t, new Object[] {writes, found});
      }
      return found.isEmpty() ? null : found;
   }

   private static void record(Rec r, GenericSpriteRenderState st, int from, int to, long h) {
      int n = Math.max(0, to - from);
      if (r.cmds.length < n) {
         int cap = Math.max(n, r.cmds.length * 3 / 2 + 8);
         TextureDraw[] c = new TextureDraw[cap];
         System.arraycopy(r.cmds, 0, c, 0, r.cmds.length);
         r.cmds = c;
         Style[] s = new Style[cap];
         System.arraycopy(r.styles, 0, s, 0, r.styles.length);
         r.styles = s;
      }
      boolean replayable = true;
      float bx0 = Float.MAX_VALUE;
      float by0 = Float.MAX_VALUE;
      float bx1 = -Float.MAX_VALUE;
      float by1 = -Float.MAX_VALUE;
      for (int i = 0; i < n; i++) {
         TextureDraw src = st.sprite[from + i];
         if (src == null || !replayable(src)) {
            replayable = false;
            continue;
         }
         TextureDraw dst = r.cmds[i];
         if (dst == null) {
            dst = new TextureDraw();
            r.cmds[i] = dst;
         }
         copy(src, dst);
         r.styles[i] = st.style[from + i];
         if (src.type == TextureDraw.Type.glDraw) {
            bx0 = Math.min(bx0, Math.min(Math.min(src.x0, src.x1), Math.min(src.x2, src.x3)));
            by0 = Math.min(by0, Math.min(Math.min(src.y0, src.y1), Math.min(src.y2, src.y3)));
            bx1 = Math.max(bx1, Math.max(Math.max(src.x0, src.x1), Math.max(src.x2, src.x3)));
            by1 = Math.max(by1, Math.max(Math.max(src.y0, src.y1), Math.max(src.y2, src.y3)));
         }
      }
      r.n = n;
      r.replayable = replayable;
      r.recorded = true;
      r.hash = h;
      r.bx0 = bx0;
      r.by0 = by0;
      r.bx1 = bx1;
      r.by1 = by1;
   }

   private static void copyOut(Rec r) {
      GenericSpriteRenderState st = state();
      for (int i = 0; i < r.n; i++) {
         st.CheckSpriteSlots();
         copy(r.cmds[i], st.sprite[st.numSprites]);
         st.style[st.numSprites] = r.styles[i];
         st.numSprites++;
      }
   }

   static boolean replayable(TextureDraw d) {
      if (d.drawer != null || d.future != null || d.imDrawData != null || d.probe != null) {
         return false;
      }
      switch (d.type) {
         case glDraw:
         case glStencilFunc:
         case glAlphaFunc:
         case glStencilOp:
         case glEnable:
         case glDisable:
         case glColorMask:
         case glStencilMask:
         case glClear:
         case glBlendFunc:
         case glBlendFuncSeparate:
         case glBlendEquation:
         case glIgnoreStyles:
         case glClearColor:
         case glDepthMask:
         case glDepthFunc:
         case glClearDepth:
         case glTexParameteri:
         case glBind:
         case glViewport:
         case glLoadIdentity:
            return true;
         case StartShader:
            return d.a == 0;
         default:
            return false;
      }
   }

   /**
    * The draw commands' identity for "did this element draw the same as last time": a quad by its geometry, texture
    * coordinates, textures and colours (its a/b/c/d/f1 are leftovers from the pooled object's last use as a state
    * command), a state command by its arguments.
    */
   static final String[] QUAD_FIELDS = {"tex", "tex1", "tex2", "x0", "y0", "x1", "y1", "x2", "y2", "x3", "y3", "u0", "v0", "u2", "v2",
      "col0", "col1", "col2", "col3", "flipped", "useAttribArray"};

   /** The argument names a state command of this type carries (TextureDraw's builders; the rest are leftovers). */
   static String[] stateFields(TextureDraw.Type t) {
      switch (t) {
         case glStencilFunc:
         case glStencilOp:
         case glTexParameteri:
            return new String[]{"a", "b", "c"};
         case glEnable:
         case glDisable:
         case glClear:
         case glDepthFunc:
         case glStencilMask:
         case glBlendEquation:
         case glIgnoreStyles:
         case StartShader:
            return new String[]{"a"};
         case glBlendFunc:
            return new String[]{"a", "b"};
         case glBlendFuncSeparate:
            return new String[]{"a", "b", "c", "d"};
         case glAlphaFunc:
            return new String[]{"a", "f1"};
         case glColorMask:
            return new String[]{"a", "b", "c", "x0"};
         case glClearColor:
            return new String[]{"col0", "col1", "col2", "col3"};
         case glClearDepth:
            return new String[]{"u0"};
         default:
            return new String[]{"a", "b", "c", "d", "f1"};
      }
   }

   private static long field(TextureDraw d, String f) {
      switch (f) {
         case "a": return d.a;
         case "b": return d.b;
         case "c": return d.c;
         case "d": return d.d;
         case "f1": return Float.floatToRawIntBits(d.f1);
         case "x0": return Float.floatToRawIntBits(d.x0);
         case "u0": return Float.floatToRawIntBits(d.u0);
         case "col0": return d.col0;
         case "col1": return d.col1;
         case "col2": return d.col2;
         case "col3": return d.col3;
         default: return 0L;
      }
   }

   /** The values the hash covers for one command, after its type (QUAD_FIELDS for a quad, stateFields otherwise). */
   static long[] key(TextureDraw d) {
      if (d.type == TextureDraw.Type.glDraw) {
         return new long[]{d.type.ordinal(), System.identityHashCode(d.tex), System.identityHashCode(d.tex1), System.identityHashCode(d.tex2),
            Float.floatToRawIntBits(d.x0), Float.floatToRawIntBits(d.y0), Float.floatToRawIntBits(d.x1), Float.floatToRawIntBits(d.y1),
            Float.floatToRawIntBits(d.x2), Float.floatToRawIntBits(d.y2), Float.floatToRawIntBits(d.x3), Float.floatToRawIntBits(d.y3),
            Float.floatToRawIntBits(d.u0), Float.floatToRawIntBits(d.v0), Float.floatToRawIntBits(d.u2), Float.floatToRawIntBits(d.v2),
            d.col0, d.col1, d.col2, d.col3, d.flipped ? 1 : 0, d.useAttribArray};
      }
      String[] f = stateFields(d.type);
      long[] k = new long[f.length + 1];
      k[0] = d.type.ordinal();
      for (int i = 0; i < f.length; i++) {
         k[i + 1] = field(d, f[i]);
      }
      return k;
   }

   /**
    * The draw commands' identity for "did this element draw the same as last time": a quad by its geometry, texture
    * coordinates, textures and colours, a state command by the arguments its type carries (pooled TextureDraws keep
    * the fields of their previous use, e.g. a quad's a/b/c or a stencil mask's b/c).
    */
   static long hash(TextureDraw[] sprites, int from, int to) {
      long h = 1125899906842597L;
      for (int i = from; i < to; i++) {
         TextureDraw d = sprites[i];
         if (d == null) {
            continue;
         }
         h = 31L * h + d.type.ordinal();
         if (d.type == TextureDraw.Type.glDraw) {
            h = 31L * h + System.identityHashCode(d.tex);
            h = 31L * h + System.identityHashCode(d.tex1);
            h = 31L * h + System.identityHashCode(d.tex2);
            h = 31L * h + Float.floatToRawIntBits(d.x0);
            h = 31L * h + Float.floatToRawIntBits(d.y0);
            h = 31L * h + Float.floatToRawIntBits(d.x1);
            h = 31L * h + Float.floatToRawIntBits(d.y1);
            h = 31L * h + Float.floatToRawIntBits(d.x2);
            h = 31L * h + Float.floatToRawIntBits(d.y2);
            h = 31L * h + Float.floatToRawIntBits(d.x3);
            h = 31L * h + Float.floatToRawIntBits(d.y3);
            h = 31L * h + Float.floatToRawIntBits(d.u0);
            h = 31L * h + Float.floatToRawIntBits(d.v0);
            h = 31L * h + Float.floatToRawIntBits(d.u2);
            h = 31L * h + Float.floatToRawIntBits(d.v2);
            h = 31L * h + d.col0;
            h = 31L * h + d.col1;
            h = 31L * h + d.col2;
            h = 31L * h + d.col3;
            h = 31L * h + (d.flipped ? 1 : 0);
            h = 31L * h + d.useAttribArray;
         } else {
            switch (d.type) {
               case glStencilFunc:
               case glStencilOp:
               case glTexParameteri:
                  h = 31L * (31L * (31L * h + d.a) + d.b) + d.c;
                  break;
               case glEnable:
               case glDisable:
               case glClear:
               case glDepthFunc:
               case glStencilMask:
               case glBlendEquation:
               case glIgnoreStyles:
               case StartShader:
                  h = 31L * h + d.a;
                  break;
               case glBlendFunc:
                  h = 31L * (31L * h + d.a) + d.b;
                  break;
               case glBlendFuncSeparate:
                  h = 31L * (31L * (31L * (31L * h + d.a) + d.b) + d.c) + d.d;
                  break;
               case glAlphaFunc:
                  h = 31L * (31L * h + d.a) + Float.floatToRawIntBits(d.f1);
                  break;
               case glColorMask:
                  h = 31L * (31L * (31L * (31L * h + d.a) + d.b) + d.c) + Float.floatToRawIntBits(d.x0);
                  break;
               case glClearColor:
                  h = 31L * (31L * (31L * (31L * h + d.col0) + d.col1) + d.col2) + d.col3;
                  break;
               case glClearDepth:
                  h = 31L * h + Float.floatToRawIntBits(d.u0);
                  break;
               default:
                  h = 31L * (31L * (31L * (31L * (31L * h + d.a) + d.b) + d.c) + d.d) + Float.floatToRawIntBits(d.f1);
            }
         }
      }
      return h;
   }

   static void copy(TextureDraw s, TextureDraw d) {
      d.type = s.type;
      d.flipped = s.flipped;
      d.a = s.a;
      d.b = s.b;
      d.f1 = s.f1;
      d.vars = s.vars;
      d.c = s.c;
      d.d = s.d;
      d.col0 = s.col0;
      d.col1 = s.col1;
      d.col2 = s.col2;
      d.col3 = s.col3;
      d.x0 = s.x0;
      d.x1 = s.x1;
      d.x2 = s.x2;
      d.x3 = s.x3;
      d.y0 = s.y0;
      d.y1 = s.y1;
      d.y2 = s.y2;
      d.y3 = s.y3;
      d.u0 = s.u0;
      d.u1 = s.u1;
      d.u2 = s.u2;
      d.u3 = s.u3;
      d.v0 = s.v0;
      d.v1 = s.v1;
      d.v2 = s.v2;
      d.v3 = s.v3;
      d.z = s.z;
      d.chunkDepth = s.chunkDepth;
      d.tex = s.tex;
      d.tex1 = s.tex1;
      d.tex2 = s.tex2;
      d.useAttribArray = s.useAttribArray;
      d.tex1U0 = s.tex1U0;
      d.tex1U1 = s.tex1U1;
      d.tex1U2 = s.tex1U2;
      d.tex1U3 = s.tex1U3;
      d.tex1V0 = s.tex1V0;
      d.tex1V1 = s.tex1V1;
      d.tex1V2 = s.tex1V2;
      d.tex1V3 = s.tex1V3;
      d.tex1Col0 = s.tex1Col0;
      d.tex1Col1 = s.tex1Col1;
      d.tex1Col2 = s.tex1Col2;
      d.tex1Col3 = s.tex1Col3;
      d.tex2U0 = s.tex2U0;
      d.tex2U1 = s.tex2U1;
      d.tex2U2 = s.tex2U2;
      d.tex2U3 = s.tex2U3;
      d.tex2V0 = s.tex2V0;
      d.tex2V1 = s.tex2V1;
      d.tex2V2 = s.tex2V2;
      d.tex2V3 = s.tex2V3;
      d.singleCol = s.singleCol;
      d.imDrawData = null;
      d.probe = null;
      d.drawer = null;
      d.future = null;
      d.pzoptShadowTile = s.pzoptShadowTile;
      d.pzoptShadowX = s.pzoptShadowX;
      d.pzoptShadowY = s.pzoptShadowY;
      d.pzoptShadowZ = s.pzoptShadowZ;
      d.pzoptShadowHalf = s.pzoptShadowHalf;
      d.pzoptLampN = s.pzoptLampN;
   }

   /** Forget elements not rendered for 2 s (closed windows, dropped tooltips) and write the per-second counters. */
   private static void cleanup() {
      nextCleanupNs = now + 1_000_000_000L;
      Iterator<Map.Entry<UIElementInterface, Rec>> it = recs.entrySet().iterator();
      while (it.hasNext()) {
         Rec r = it.next().getValue();
         if (frameNo - r.seenFrame > 600) {
            it.remove();
         }
      }
      UiProfile.retainedStats(statUiFrames, statInputFrames, statFresh, statReplay, statChecked, statStale, recs.size(), statUiEvents);
      if (Config.UI_VERIFY) {
         int verifiable = 0;
         int top = 0;
         StringBuilder opaque = new StringBuilder();
         for (Rec r : recs.values()) {
            if (!r.child && r.recorded) {
               top++;
               if (r.deps.verifiable()) {
                  verifiable++;
               } else if (opaque.length() < 400) {
                  opaque.append(opaque.length() == 0 ? " opaque_top=" : ",").append(r.name).append(':').append(r.deps.why());
               }
            }
         }
         StringBuilder dw = new StringBuilder();
         dirtyWhy.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
            .forEach(en -> dw.append(dw.length() == 0 ? " dirty=" : ",").append(en.getKey().replace(' ', '_')).append('=').append(en.getValue()));
         dirtyWhy.clear();
         UiProfile.depsStats("top=" + top + " verifiable=" + verifiable + " trig=" + trig[0] + "/" + trig[1] + "/" + trig[2] + "/" + trig[3] + " denied=" + statDenied + " move_replays=" + statMoveReplay + " slack_us=" + slackNs() / 1000L
            + " overhead_us=" + uiFrameOverheadEma / 1000L + " " + UiDeps.stats() + dw + opaque);
         statDenied = 0;
         statMoveReplay = 0;
         java.util.Arrays.fill(trig, 0);
      }
      if (truth) {
         UiProfile.truthStats(truthFrames, truthStale, truthNear);
         truthFrames = 0;
         truthNear = 0;
         java.util.Arrays.fill(truthStale, 0);
      }
      statUiEvents = 0;
      statUiFrames = 0;
      statInputFrames = 0;
      statFresh = 0;
      statReplay = 0;
      statChecked = 0;
      statStale = 0;
   }
}
