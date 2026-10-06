package zombie.ui;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import pzopt.SubframeInput;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.IndieGL;
import zombie.UsedFromLua;
import zombie.GameProfiler.ProfileArea;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.characters.component.CharacterInputComponent;
import zombie.core.BoxedStaticValues;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
import zombie.core.Styles.TransparentStyle;
import zombie.core.Styles.UIFBOStyle;
import zombie.core.math.PZMath;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.GameLoadingState;
import zombie.gizmo.Gizmos;
import zombie.input.GameKeyboard;
import zombie.input.KeybindId;
import zombie.input.Mouse;
import zombie.iso.IsoCamera;
import zombie.iso.IsoObject;
import zombie.iso.IsoObjectPicker;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.iso.IsoObjectPicker.ClickObject;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.util.list.PZArrayUtil;

@UsedFromLua
public final class UIManager {
   public static int lastMouseX;
   public static int lastMouseY;
   public static ClickObject picked;
   private static final ClickObject pickedCopy = new ClickObject();
   public static Clock clock;
   public static final ArrayList<UIElementInterface> UI = new ArrayList<>();
   public static ObjectTooltip toolTip;
   public static Texture mouseArrow;
   public static Texture mouseExamine;
   public static Texture mouseAttack;
   public static Texture mouseGrab;
   public static SpeedControls speedControls;
   public static UIDebugConsole debugConsole;
   public static final MoodlesUI[] MoodleUI = new MoodlesUI[4];
   public static boolean fadeBeforeUi;
   public static final ActionProgressBar[] ProgressBar = new ActionProgressBar[4];
   public static float fadeAlpha = 1.0F;
   public static int fadeInTimeMax = 180;
   public static int fadeInTime = 180;
   public static boolean fadingOut;
   public static Texture lastMouseTexture;
   public static IsoObject lastPicked;
   public static final ArrayList<String> DoneTutorials = new ArrayList<>();
   public static float lastOffX;
   public static float lastOffY;
   public static ModalDialog modal;
   public static boolean doTick;
   public static boolean visibleAllUi = true;
   public static TextureFBO uiFbo;
   public static boolean useUiFbo;
   public static boolean uiTextureContentsValid;
   public static Texture black;
   public static boolean suspend;
   public static float lastAlpha = 10000.0F;
   public static final Vector2 PickedTileLocal = new Vector2();
   public static final Vector2 PickedTile = new Vector2();
   public static IsoObject rightDownObject;
   public static long uiUpdateTimeMS;
   public static long uiUpdateIntervalMS;
   public static long uiRenderTimeMS;
   public static long uiRenderIntervalMS;
   private static final ArrayList<UIElementInterface> tutorialStack = new ArrayList<>();
   public static final ArrayList<UIElementInterface> toTop = new ArrayList<>();
   public static KahluaThread defaultthread;
   public static KahluaThread previousThread;
   static final ArrayList<UIElementInterface> toRemove = new ArrayList<>();
   static final ArrayList<UIElementInterface> toAdd = new ArrayList<>();
   static int wheel;
   static int lastwheel;
   static final ArrayList<UIElementInterface> debugUI = new ArrayList<>();
   static boolean showLuaDebuggerOnError;
   public static String luaDebuggerAction;
   static final UIManager.Sync sync = new UIManager.Sync();
   private static boolean showPausedMessage = true;
   private static UIElementInterface playerInventoryUI;
   private static UIElementInterface playerLootUI;
   private static UIElementInterface playerInventoryTooltip;
   private static UIElementInterface playerLootTooltip;
   private static final UIManager.FadeInfo[] playerFadeInfo = new UIManager.FadeInfo[4];
   private static final UIManager.BlinkInfo[] playerBlinkInfo = new UIManager.BlinkInfo[4];
   private static boolean rendering;
   private static boolean updating;
   private static boolean subframeEdges;
   private static boolean subframeHeld;
   private static boolean subframeEventDispatch;
   private static boolean subframeFrameMouseMove;
   private static boolean subframeLastMoveConsumed;
   public static final int DEBUGGER_FPS = 60;

   public static void AddUI(UIElementInterface el) {
      toRemove.remove(el);
      toRemove.add(el);
      toAdd.remove(el);
      toAdd.add(el);
   }

   public static void RemoveElement(UIElementInterface el) {
      toAdd.remove(el);
      toRemove.remove(el);
      toRemove.add(el);
   }

   public static void clearArrays() {
      toAdd.clear();
      toRemove.clear();
      UI.clear();
   }

   public static void closeContainers() {
   }

   public static void CloseContainers() {
   }

   public static void DrawTexture(Texture tex, double x, double y) {
      double dx = x;
      double dy = y;
      dx += tex.offsetX;
      dy += tex.offsetY;
      SpriteRenderer.instance.renderi(tex, (int)dx, (int)dy, tex.getWidth(), tex.getHeight(), 1.0F, 1.0F, 1.0F, 1.0F, null);
   }

   public static void DrawTexture(Texture tex, double x, double y, double width, double height, double alpha) {
      double dx = x;
      double dy = y;
      dx += tex.offsetX;
      dy += tex.offsetY;
      SpriteRenderer.instance.renderi(tex, (int)dx, (int)dy, (int)width, (int)height, 1.0F, 1.0F, 1.0F, (float)alpha, null);
   }

   public static void FadeIn(double seconds) {
      setFadeInTimeMax((int)(seconds * 30.0 * (PerformanceSettings.getLockFPS() / 30.0F)));
      setFadeInTime(getFadeInTimeMax());
      setFadingOut(false);
   }

   public static void FadeOut(double seconds) {
      setFadeInTimeMax((int)(seconds * 30.0 * (PerformanceSettings.getLockFPS() / 30.0F)));
      setFadeInTime(getFadeInTimeMax());
      setFadingOut(true);
   }

   public static void CreateFBO(int width, int height) {
      if (Core.safeMode) {
         useUiFbo = false;
      } else {
         if (useUiFbo && (uiFbo == null || uiFbo.getTexture().getWidth() != width || uiFbo.getTexture().getHeight() != height)) {
            if (uiFbo != null) {
               RenderThread.invokeOnRenderContext(() -> uiFbo.destroy());
            }

            try {
               uiFbo = createTexture(width, height, false);
            } catch (Exception e) {
               useUiFbo = false;
               DebugType.General.printException(e, LogSeverity.Error);
            }
         }
      }
   }

   public static TextureFBO createTexture(float x, float y, boolean test) throws Exception {
      if (test) {
         Texture tex = new Texture((int)x, (int)y, 16);
         TextureFBO newOne = new TextureFBO(tex);
         newOne.destroy();
         return null;
      } else {
         Texture tex = new Texture((int)x, (int)y, 16);
         return new TextureFBO(tex);
      }
   }

   public static void init() {
      showPausedMessage = true;
      getUI().clear();
      debugUI.clear();
      clock = null;

      for (int i = 0; i < 4; i++) {
         MoodleUI[i] = null;
      }

      setSpeedControls(new SpeedControls());
      SpeedControls.instance = getSpeedControls();
      setbFadeBeforeUI(false);
      visibleAllUi = true;

      for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
         playerFadeInfo[playerIndex].setFadeBeforeUI(false);
         playerFadeInfo[playerIndex].setFadeTime(0);
         playerFadeInfo[playerIndex].setFadingOut(false);
      }

      setPicked(null);
      setLastPicked(null);
      rightDownObject = null;
      if (IsoPlayer.getInstance() != null) {
         if (!Core.gameMode.equals("LastStand") && !GameClient.client) {
            getUI().add(getSpeedControls());
         }

         if (!GameServer.server) {
            setToolTip(new ObjectTooltip());
            if (Core.getInstance().getOptionClockSize() == 2) {
               setClock(new Clock(Core.getInstance().getOffscreenWidth(0) - 166, 10));
            } else {
               setClock(new Clock(Core.getInstance().getOffscreenWidth(0) - 91, 10));
            }

            if (!Core.gameMode.equals("LastStand")) {
               getUI().add(getClock());
            }

            getUI().add(getToolTip());
            setDebugConsole(new UIDebugConsole(20, Core.getInstance().getScreenHeight() - 265));
            debugConsole.setY(Core.getInstance().getScreenHeight() - debugConsole.getHeight() - 20.0);
            if (Core.debug && DebugOptions.instance.uiDebugConsoleStartVisible.getValue()) {
               debugConsole.setVisible(true);
            } else {
               debugConsole.setVisible(false);
            }

            for (int i = 0; i < 4; i++) {
               MoodlesUI ui = new MoodlesUI();
               setMoodleUI(i, ui);
               ui.setVisible(true);
               getUI().add(ui);
            }

            getUI().add(getDebugConsole());
            setLastMouseTexture(getMouseArrow());
            resize();

            for (int i = 0; i < 4; i++) {
               ActionProgressBar bar = new ActionProgressBar(0, 0);
               bar.setRenderThisPlayerOnly(i);
               setProgressBar(i, bar);
               getUI().add(bar);
               bar.setValue(1.0F);
               bar.setVisible(false);
            }

            playerInventoryUI = null;
            playerLootUI = null;
            LuaEventManager.triggerEvent("OnCreateUI");
         }
      }
   }

   public static void render() {
      if (!useUiFbo || Core.getInstance().uiRenderThisFrame) {
         if (!suspend) {
            long currentTimeMS = System.currentTimeMillis();
            uiRenderIntervalMS = Math.min(currentTimeMS - uiRenderTimeMS, 1000L);
            uiRenderTimeMS = currentTimeMS;
            UIElement.stencilLevel = 0;
            pzopt.UiProfile.renderBegin(); // pzopt: uiProfile, UI frame timing
            IndieGL.enableBlend();
            if (useUiFbo) {
               SpriteRenderer.instance.setDefaultStyle(UIFBOStyle.instance);
               IndieGL.glBlendFuncSeparate(770, 771, 1, 771);
            } else {
               IndieGL.glBlendFunc(770, 771);
            }

            IndieGL.disableDepthTest();
            GameProfiler profiler = GameProfiler.getInstance();
            ProfileArea playerIndex = profiler.profile("UITransition.UpdateAll");

            try {
               UITransition.UpdateAll();
            } catch (Throwable var10) {
               if (playerIndex != null) {
                  try {
                     playerIndex.close();
                  } catch (Throwable var8) {
                     var10.addSuppressed(var8);
                  }
               }

               throw var10;
            }

            if (playerIndex != null) {
               playerIndex.close();
            }

            if (getBlack() == null) {
               setBlack(Texture.getSharedTexture("black.png"));
            }

            if (LuaManager.thread == defaultthread) {
               LuaEventManager.triggerEvent("OnPreUIDraw");
            }

            if (isbFadeBeforeUI()) {
               renderFadeOverlay();
            }

            setLastAlpha(getFadeAlpha().floatValue());

            for (int playerIndexx = 0; playerIndexx < IsoPlayer.numPlayers; playerIndexx++) {
               if (IsoPlayer.players[playerIndexx] != null && playerFadeInfo[playerIndexx].isFadeBeforeUI()) {
                  playerFadeInfo[playerIndexx].render();
               }
            }

            rendering = true;

            for (int i = 0; i < getUI().size(); i++) {
               UIElementInterface element = getUI().get(i);
               if ((element.isIgnoreLossControl() || !TutorialManager.instance.stealControl) && !element.isFollowGameWorld()) {
                  try {
                     if (element.isDefaultDraw()) {
                        if (GameProfiler.isRunning()) {
                           ProfileArea fontHeight = profiler.profile("Render " + element);

                           try {
                              element.render();
                           } catch (Throwable var11) {
                              if (fontHeight != null) {
                                 try {
                                    fontHeight.close();
                                 } catch (Throwable var9) {
                                    var11.addSuppressed(var9);
                                 }
                              }

                              throw var11;
                           }

                           if (fontHeight != null) {
                              fontHeight.close();
                           }
                        } else {
                           long pzoptT0 = System.nanoTime(); // pzopt: uiRetained, replay the recorded commands or render fresh
                           if (pzopt.UiRetained.replay(element)) { // pzopt: uiRetained
                              pzopt.UiProfile.elementReplayed(element, System.nanoTime() - pzoptT0); // pzopt: uiProfile
                           } else { // pzopt: uiRetained
                              int pzoptFrom = pzopt.UiRetained.freshBegin(); // pzopt: uiRetained
                              pzopt.UiProfile.elementBegin(); // pzopt: uiProfile, per top-level element
                              long pzoptT1 = System.nanoTime(); // pzopt: uiRetained
                              element.render();
                              long pzoptNs = System.nanoTime() - pzoptT1; // pzopt: uiRetained
                              pzopt.UiProfile.elementEnd(element); // pzopt: uiProfile
                              pzopt.UiRetained.freshEnd(element, pzoptFrom, pzoptNs); // pzopt: uiRetained
                           } // pzopt: uiRetained
                        }
                     }
                  } catch (Exception ex) {
                     Logger.getLogger(GameWindow.class.getName()).log(Level.SEVERE, null, ex);
                  }
               }
            }

            rendering = false;
            pzopt.UiRetained.loopEnd(); // pzopt: uiRetained, clean GL state cache after the element loop
            if (getToolTip() != null) {
               getToolTip().render();
            }

            if (isShowPausedMessage() && GameTime.isGamePaused() && (getModal() == null || !modal.isVisible()) && visibleAllUi) {
               String text = Translator.getText("IGUI_GamePaused", new Object[0]);
               int boxWidth = TextManager.instance.MeasureStringX(UIFont.Small, text) + 32;
               int fontHeight = TextManager.instance.font.getLineHeight();
               int boxHeight = (int)Math.ceil(fontHeight * 1.5);
               SpriteRenderer.instance
                  .renderi(
                     null,
                     Core.getInstance().getScreenWidth() / 2 - boxWidth / 2,
                     Core.getInstance().getScreenHeight() / 6 - boxHeight / 2,
                     boxWidth,
                     boxHeight,
                     0.0F,
                     0.0F,
                     0.0F,
                     0.75F,
                     null
                  );
               TextManager.instance
                  .DrawStringCentre(
                     Core.getInstance().getScreenWidth() / 2, Core.getInstance().getScreenHeight() / 6 - fontHeight / 2, text, 1.0, 1.0, 1.0, 1.0
                  );
            }

            if (!isbFadeBeforeUI()) {
               renderFadeOverlay();
            }

            for (int playerIndexx = 0; playerIndexx < IsoPlayer.numPlayers; playerIndexx++) {
               if (IsoPlayer.players[playerIndexx] != null && !playerFadeInfo[playerIndexx].isFadeBeforeUI()) {
                  playerFadeInfo[playerIndexx].render();
               }
            }

            if (LuaManager.thread == defaultthread) {
               LuaEventManager.triggerEvent("OnPostUIDraw");
            }

            if (useUiFbo) {
               SpriteRenderer.instance.setDefaultStyle(TransparentStyle.instance);
               IndieGL.glBlendFunc(770, 771);
            }

            pzopt.UiProfile.renderEnd(); // pzopt: uiProfile
         }
      }
   }

   public static void renderFadeOverlay() {
      setFadeAlpha((float)fadeInTime / fadeInTimeMax);
      if (isFadingOut()) {
         setFadeAlpha(1.0 - getFadeAlpha());
      }

      if (IsoCamera.getCameraCharacter() != null && getFadeAlpha() > 0.0) {
         DrawTexture(getBlack(), 0.0, 0.0, Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight(), getFadeAlpha());
      }
   }

   public static void resize() {
      if (useUiFbo && uiFbo != null) {
         CreateFBO(Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight());
      }

      if (getClock() != null) {
         setLastOffX(Core.getInstance().getScreenWidth());
         setLastOffY(Core.getInstance().getScreenHeight());

         for (int i = 0; i < 4; i++) {
            int sw = Core.getInstance().getScreenWidth();
            int sh = Core.getInstance().getScreenHeight();
            int y;
            if (!Clock.instance.isVisible()) {
               y = 24;
            } else {
               y = 64;
            }

            if (i == 0 && IsoPlayer.numPlayers > 1 || i == 2) {
               sw /= 2;
            }

            MoodleUI[i].setX(sw - (10.0F + MoodleUI[i].width));
            if ((i == 0 || i == 1) && IsoPlayer.numPlayers > 1) {
               MoodleUI[i].setY(y);
            }

            if (i == 2 || i == 3) {
               MoodleUI[i].setY(sh / 2 + y);
            }

            MoodleUI[i].setVisible(visibleAllUi && IsoPlayer.players[i] != null);
         }

         clock.resize();
         if (IsoPlayer.numPlayers == 1) {
            if (Core.getInstance().getOptionClockSize() == 2) {
               clock.setX(Core.getInstance().getScreenWidth() - 166);
            } else {
               clock.setX(Core.getInstance().getScreenWidth() - 91);
            }
         } else {
            if (Core.getInstance().getOptionClockSize() == 2) {
               clock.setX(Core.getInstance().getScreenWidth() / 2.0F - 83.0F);
            } else {
               clock.setX(Core.getInstance().getScreenWidth() / 2.0F - 45.5F);
            }

            clock.setY(Core.getInstance().getScreenHeight() - 70);
         }

         if (IsoPlayer.numPlayers == 1) {
            speedControls.setX(Core.getInstance().getScreenWidth() - speedControls.width - 10.0F);
         } else {
            speedControls.setX(Core.getInstance().getScreenWidth() / 2 - speedControls.width - 10.0F);
         }

         if (IsoPlayer.numPlayers == 1 && !clock.isVisible()) {
            speedControls.setY(clock.getY());
         } else {
            speedControls.setY(clock.getY() + clock.getHeight() + 10.0);
         }

         speedControls.setVisible(visibleAllUi && !IsoPlayer.allPlayersDead());
      }
   }

   public static Vector2 getTileFromMouse(double mx, double my, double z) {
      int playerNum = 0;
      PickedTile.x = IsoUtils.XToIso(0, (float)(mx - 0.0), (float)(my - 0.0), (float)z);
      PickedTile.y = IsoUtils.YToIso(0, (float)(mx - 0.0), (float)(my - 0.0), (float)z);
      PickedTileLocal.x = getPickedTile().x - PZMath.fastfloor(getPickedTile().x);
      PickedTileLocal.y = getPickedTile().y - PZMath.fastfloor(getPickedTile().y);
      PickedTile.x = PZMath.fastfloor(getPickedTile().x);
      PickedTile.y = PZMath.fastfloor(getPickedTile().y);
      return getPickedTile();
   }

   private static int isOverElement(UIElementInterface ui, int mx, int my) {
      if (!ui.isIgnoreLossControl() && TutorialManager.instance.stealControl) {
         return -1;
      }

      if (!ui.isVisible()) {
         return -1;
      }

      if (modal != null && modal != ui && modal.isVisible()) {
         return -1;
      }

      if (ui.isCapture()) {
         return 1;
      }

      if (ui.getMaxDrawHeight() != -1.0) {
         if (mx >= ui.getX() && my >= ui.getY() && mx < ui.getX() + ui.getWidth() && my < ui.getY() + Math.min(ui.getHeight(), ui.getMaxDrawHeight())) {
            return 1;
         }
      } else if (ui.isOverElement(mx, my)) {
         return 1;
      }

      return 0;
   }

   public static void update() {
      if (SubframeInput.active() && SubframeInput.inEvent()) return; // pzopt: a Lua input callback cannot recursively drain/replay its frame
      if (!suspend) {
         if (!toRemove.isEmpty()) {
            UI.removeAll(toRemove);
         }

         toRemove.clear();
         if (!toAdd.isEmpty()) {
            PZArrayUtil.addAll(UI, toAdd);
         }

         toAdd.clear();
         setFadeInTime(getFadeInTime() - 1.0);

         for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
            playerFadeInfo[playerIndex].update();
            playerBlinkInfo[playerIndex].update();
         }

         long currentTimeMS = System.currentTimeMillis();
         if (currentTimeMS - uiUpdateTimeMS >= 100L) {
            doTick = true;
            uiUpdateIntervalMS = Math.min(currentTimeMS - uiUpdateTimeMS, 1000L);
            uiUpdateTimeMS = currentTimeMS;
         } else {
            doTick = false;
         }

         int mx = Mouse.getXA();
         int my = Mouse.getYA();
         int mxw = Mouse.getX();
         int myw = Mouse.getY();
         tutorialStack.clear();

         for (int i = UI.size() - 1; i >= 0; i--) {
            UIElementInterface ui = UI.get(i);
            if (ui.getParent() != null) {
               UI.remove(i);
               throw new IllegalStateException();
            }

            if (ui.isFollowGameWorld()) {
               tutorialStack.add(ui);
            }

            if (ui instanceof ObjectTooltip) {
               UIElementInterface rem = UI.remove(i);
               UI.add(rem);
            }
         }

         for (int i = 0; i < UI.size(); i++) {
            UIElementInterface ui = UI.get(i);
            if (ui.isAlwaysOnTop() || toTop.contains(ui)) {
               UIElementInterface rem = UI.remove(i);
               i--;
               toAdd.add(rem);
            }
         }

         if (!toAdd.isEmpty()) {
            PZArrayUtil.addAll(UI, toAdd);
            toAdd.clear();
         }

         toTop.clear();

         for (int i = 0; i < UI.size(); i++) {
            UIElementInterface ui = UI.get(i);
            if (ui.isBackMost()) {
               UIElementInterface rem = UI.remove(i);
               UI.add(0, rem);
            }
         }

         for (int i = 0; i < tutorialStack.size(); i++) {
            UI.remove(tutorialStack.get(i));
            UI.add(0, tutorialStack.get(i));
         }

         updating = true;
         pzopt.UiProfile.updateMark(0); // pzopt: uiProfile, update sections (list upkeep)
         GameProfiler profiler = GameProfiler.getInstance();
         ProfileArea var34 = profiler.profile("updateMouseButtons");

         int consumed;
         try {
            if (SubframeInput.active()) {
               consumed = updateSubframeMouse(mx, my);
            } else {
               consumed = updateMouseButtons(mx, my);
            }
         } catch (Throwable var23) {
            if (var34 != null) {
               try {
                  var34.close();
               } catch (Throwable var19) {
                  var23.addSuppressed(var19);
               }
            }

            throw var23;
         }

         if (var34 != null) {
            var34.close();
         }

         pzopt.UiProfile.updateMark(1); // pzopt: uiProfile (mouse buttons)
         boolean consumedClick = (consumed & 1) == 1;
         boolean consumedRClick = (consumed & 2) == 2;
         boolean consumedMove = false;
         int attackclick = GameKeyboard.whichKeyPressed("Attack/Click");
         if (!SubframeInput.active() && attackclick > 0 && checkPicked()) {
            if ((attackclick != 10000 || !consumedClick) && (attackclick != 10001 || !consumedRClick)) {
               LuaEventManager.triggerEvent("OnObjectLeftMouseButtonDown", picked.tile, BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
            }

            GameKeyboard.whichKeyPressed("Attack/Click");
            if (IsoWorld.instance.currentCell != null
               && !IsoWorld.instance.currentCell.DoBuilding(0, false)
               && getPicked() != null
               && !GameTime.isGamePaused()
               && IsoPlayer.getInstance() != null
               && !IsoPlayer.getInstance().isAiming()
               && !IsoPlayer.getInstance().isAsleep()) {
               getPicked().tile.onMouseLeftClick(getPicked().lx, getPicked().ly);
            }
         }
         if (SubframeInput.active() && attackclick > 0 && attackclick < 10000 && checkPicked()) {
            LuaEventManager.triggerEvent("OnObjectLeftMouseButtonDown", picked.tile, BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
            GameKeyboard.whichKeyPressed("Attack/Click");
            if (IsoWorld.instance.currentCell != null
               && !IsoWorld.instance.currentCell.DoBuilding(0, false)
               && getPicked() != null
               && !GameTime.isGamePaused()
               && IsoPlayer.getInstance() != null
               && !IsoPlayer.getInstance().isAiming()
               && !IsoPlayer.getInstance().isAsleep()) {
               getPicked().tile.onMouseLeftClick(getPicked().lx, getPicked().ly);
            }
         }

         attackclick = GameKeyboard.whichKeyWasDown("Attack/Click");
         if (!SubframeInput.active() && attackclick > 0 && checkPicked() && (attackclick != 10000 || !consumedClick) && (attackclick != 10001 || !consumedRClick)) {
            LuaEventManager.triggerEvent("OnObjectLeftMouseButtonUp", picked.tile, BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
         }
         if (SubframeInput.active() && attackclick > 0 && attackclick < 10000 && checkPicked()) {
            LuaEventManager.triggerEvent("OnObjectLeftMouseButtonUp", picked.tile, BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
         }

         if (!SubframeInput.active()) {
            lastwheel = 0;
            wheel = Mouse.getWheelState();
            boolean bWheelConsumed = false;
            if (wheel != lastwheel) {
               int del = wheel - lastwheel < 0 ? 1 : -1;

               for (int i = UI.size() - 1; i >= 0; i--) {
                  UIElementInterface ui = UI.get(i);
                  if ((ui.isPointOver(mx, my) || ui.isCapture()) && ui.onConsumeMouseWheel(del, mx - ui.getX(), my - ui.getY())) {
                     bWheelConsumed = true;
                     break;
                  }
               }

               LuaEventManager.triggerEvent("OnMouseWheel", BoxedStaticValues.toDouble(wheel));
               if (!bWheelConsumed) {
                  Core.getInstance().doZoomScroll(0, del);
               }
            }
         }

         pzopt.UiProfile.updateMark(2); // pzopt: uiProfile (attack click, wheel)
         ProfileArea var38 = profiler.profile("updateMouseMove");

         try {
            if (SubframeInput.active()) {
               consumedMove = false;
            } else {
               consumedMove = updateMouseMove(mx, my, consumedMove);
            }
         } catch (Throwable var22) {
            if (var38 != null) {
               try {
                  var38.close();
               } catch (Throwable var18) {
                  var22.addSuppressed(var18);
               }
            }

            throw var22;
         }

         if (var38 != null) {
            var38.close();
         }

         pzopt.UiProfile.updateMark(3); // pzopt: uiProfile (mouse move)
         if (!SubframeInput.active() && !consumedMove && IsoPlayer.players[0] != null) {
            setPicked(IsoObjectPicker.Instance.ContextPick(mx, my));
            if (IsoCamera.getCameraCharacter() != null) {
               setPickedTile(getTileFromMouse(mxw, myw, PZMath.fastfloor(IsoPlayer.players[0].getZ())));
            }

            LuaEventManager.triggerEvent(
               "OnMouseMove", BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my), BoxedStaticValues.toDouble(mxw), BoxedStaticValues.toDouble(myw)
            );
         }

         setLastMouseX(mx);
         setLastMouseY(my);
         pzopt.UiProfile.updateMark(4); // pzopt: uiProfile (world pick, OnMouseMove)
         var38 = profiler.profile("updateUIElements");

         try {
            updateUIElements();
         } catch (Throwable var21) {
            if (var38 != null) {
               try {
                  var38.close();
               } catch (Throwable var17) {
                  var21.addSuppressed(var17);
               }
            }

            throw var21;
         }

         if (var38 != null) {
            var38.close();
         }

         pzopt.UiProfile.updateMark(5); // pzopt: uiProfile (element updates)
         var38 = profiler.profile("updateTooltip");

         try {
            updateTooltip(mx, my);
         } catch (Throwable var20) {
            if (var38 != null) {
               try {
                  var38.close();
               } catch (Throwable var16) {
                  var20.addSuppressed(var16);
               }
            }

            throw var20;
         }

         if (var38 != null) {
            var38.close();
         }

         updating = false;
         pzopt.UiProfile.updateMark(6); // pzopt: uiProfile (tooltip)
         handleZoomKeys();
         IsoCamera.cameras[0].lastOffX = (int)IsoCamera.cameras[0].offX;
         IsoCamera.cameras[0].lastOffY = (int)IsoCamera.cameras[0].offY;
      }
   }

   private static int updateSubframeMouse(int mx, int my) {
      int result = 0;
      subframeFrameMouseMove = false;
      subframeLastMoveConsumed = false;
      if (SubframeInput.reset || SubframeInput.hasPendingReset()) {
         cancelSubframeInteraction();
      } else {
         int pendingMove = -1;
         for (int i = 0; i < SubframeInput.count; i++) {
            if (SubframeInput.hasPendingReset()) {
               cancelSubframeInteraction();
               break;
            }
            boolean action = SubframeInput.pressed[i] != 0
               || SubframeInput.released[i] != 0
               || SubframeInput.wheelSteps[i] != 0;
            if (!action) {
               pendingMove = i;
               continue;
            }

            if (pendingMove >= 0) {
               SubframeInput.beginEvent(pendingMove);
               try {
                  updateSubframePosition(Mouse.getXA(), Mouse.getYA());
               } finally {
                  SubframeInput.endEvent();
               }
               pendingMove = -1;
            }

            SubframeInput.beginEvent(i);
            try {
               int x = Mouse.getXA();
               int y = Mouse.getYA();
               updateSubframePosition(x, y);
               subframeEventDispatch = true;
               subframeEdges = true;
               subframeHeld = false;
               int consumed = updateMouseButtons(x, y);
               result |= consumed;
               if ((consumed & SubframeInput.eventPressed()) != 0 || (consumed & SubframeInput.eventReleased()) != 0) {
                  SubframeInput.consume(i);
               }
               if (SubframeInput.pressed[i] != 0 || SubframeInput.released[i] != 0) {
                  dispatchSubframeAttackClick(x, y, consumed);
               }
               // Whole notches only: a high-resolution wheel's partial records accumulate like the stock per-frame sum.
               int wheelSteps = SubframeInput.eventWheelSteps();
               if (wheelSteps != 0) {
                  int delta = wheelSteps < 0 ? 1 : -1;
                  boolean wheelConsumed = false;
                  for (int uiIndex = UI.size() - 1; uiIndex >= 0; uiIndex--) {
                     UIElementInterface ui = UI.get(uiIndex);
                     if ((ui.isPointOver(x, y) || ui.isCapture()) && ui.onConsumeMouseWheel(delta, x - ui.getX(), y - ui.getY())) {
                        wheelConsumed = true;
                        break;
                     }
                  }
                  LuaEventManager.triggerEvent("OnMouseWheel", BoxedStaticValues.toDouble(wheelSteps));
                  if (!wheelConsumed) {
                     Core.getInstance().doZoomScroll(0, delta);
                  } else {
                     SubframeInput.consume(i);
                  }
               }
            } finally {
               subframeEdges = false;
               subframeHeld = false;
               subframeEventDispatch = false;
               for (int button = 0; button < Mouse.getButtonCount(); button++) {
                  if ((SubframeInput.released[i] & (1 << button)) != 0) Mouse.uiCaptured[button] = false;
               }
               SubframeInput.capture(i);
               SubframeInput.endEvent();
            }
         }
         if (SubframeInput.hasPendingReset()) {
            cancelSubframeInteraction();
         } else {
            if (pendingMove >= 0) {
               SubframeInput.beginEvent(pendingMove);
               try {
                  updateSubframePosition(Mouse.getXA(), Mouse.getYA());
               } finally {
                  SubframeInput.endEvent();
               }
            }
            updateSubframePosition(mx, my, true);
            subframeEventDispatch = true;
            subframeEdges = false;
            subframeHeld = true;
            try {
               result |= updateMouseButtons(mx, my);
            } finally {
               subframeHeld = false;
               subframeEventDispatch = false;
            }
         }
      }
      return result;
   }

   private static void updateSubframePosition(int mx, int my) {
      updateSubframePosition(mx, my, false);
   }

   private static void updateSubframePosition(int mx, int my, boolean frameHover) {
      int oldX = lastMouseX;
      int oldY = lastMouseY;
      boolean moved = oldX != mx || oldY != my;
      boolean consumedMove = updateMouseMove(mx, my, false);
      if (moved) {
         subframeLastMoveConsumed = consumedMove;
      }
      if (!consumedMove && IsoPlayer.players[0] != null) {
         setPicked(IsoObjectPicker.Instance.ContextPick(mx, my));
         int mouseX = Mouse.getX();
         int mouseY = Mouse.getY();
         if (IsoCamera.getCameraCharacter() != null) {
            setPickedTile(getTileFromMouse(mouseX, mouseY, PZMath.fastfloor(IsoPlayer.players[0].getZ())));
         }
         if (moved || frameHover && !subframeFrameMouseMove && !subframeLastMoveConsumed) {
            LuaEventManager.triggerEvent(
               "OnMouseMove",
               BoxedStaticValues.toDouble(mx),
               BoxedStaticValues.toDouble(my),
               BoxedStaticValues.toDouble(mouseX),
               BoxedStaticValues.toDouble(mouseY)
            );
            subframeFrameMouseMove = true;
         }
      }
      setLastMouseX(mx);
      setLastMouseY(my);
   }

   private static void dispatchSubframeAttackClick(int mx, int my, int consumed) {
      int attackclick = GameKeyboard.whichKeyPressed("Attack/Click");
      if (attackclick >= 10000 && checkPicked() && (consumed & (1 << (attackclick - 10000))) == 0) {
         LuaEventManager.triggerEvent("OnObjectLeftMouseButtonDown", picked.tile, BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
         if (IsoWorld.instance.currentCell != null
            && !IsoWorld.instance.currentCell.DoBuilding(0, false)
            && getPicked() != null
            && !GameTime.isGamePaused()
            && IsoPlayer.getInstance() != null
            && !IsoPlayer.getInstance().isAiming()
            && !IsoPlayer.getInstance().isAimControlActive()
            && !IsoPlayer.getInstance().isAsleep()) {
            getPicked().tile.onMouseLeftClick(getPicked().lx, getPicked().ly);
         }
      }
      attackclick = GameKeyboard.whichKeyWasDown("Attack/Click");
      if (attackclick >= 10000 && checkPicked()
         && (SubframeInput.eventReleased() & (1 << (attackclick - 10000))) != 0
         && (consumed & (1 << (attackclick - 10000))) == 0) {
         LuaEventManager.triggerEvent("OnObjectLeftMouseButtonUp", picked.tile, BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
      }
   }

   private static void cancelSubframeInteraction() {
      Mouse.pzoptButtonState(0, 0);
      Mouse.wheelDelta = 0;
      if (Mouse.uiCaptured != null) {
         for (int i = 0; i < Mouse.uiCaptured.length; i++) {
            Mouse.uiCaptured[i] = false;
         }
      }
      for (int i = 0; i < UI.size(); i++) {
         if (UI.get(i) instanceof UIElement element) {
            element.cancelMouseInteraction();
         }
      }
      if (LuaManager.env != null && LuaManager.env.rawget("ISMouseDrag") instanceof KahluaTable drag) {
         drag.rawset("dragView", null);
         drag.rawset("tabPanel", null);
      }
      setRightDownObject(null);
      IsoPlayer player = IsoPlayer.getInstance();
      if (player != null) {
         player.setDragObject(null);
      }
   }

   private static int updateMouseButtons(int mx, int my) {
      boolean consumedClick = false;
      boolean consumedRClick = false;
      int consumedButtons = 0; // pzopt: keep consumption for remapped extra mouse buttons too

      for (int btn = 0; btn < Mouse.getButtonCount(); btn++) {
         boolean consumed = btn == 0 && Gizmos.getInstance().hitTest(mx, my);
         boolean capturedRelease = SubframeInput.active() && Mouse.uiCaptured[btn] && Mouse.isButtonReleased(btn);
         if ((!subframeEventDispatch || subframeEdges) && Mouse.isButtonPressed(btn)) {
            if (btn == 0) {
               Core.UnfocusActiveTextEntryBox();
            }

            for (int i = UI.size() - 1; i >= 0 && !consumed; i--) {
               UIElementInterface ui = UI.get(i);
               switch (isOverElement(ui, mx, my)) {
                  case 0:
                     ui.onMouseButtonDownOutside(btn, mx - ui.getX(), my - ui.getY());
                     break;
                  case 1:
                     if (ui.onConsumeMouseButtonDown(btn, mx - ui.getX(), my - ui.getY())) {
                        consumed = true;
                     }
               }
            }
         } else if ((!subframeEventDispatch || subframeEdges) && Mouse.isButtonReleased(btn)) {
            for (int i = UI.size() - 1; i >= 0 && !consumed; i--) {
               UIElementInterface ui = UI.get(i);
               switch (isOverElement(ui, mx, my)) {
                  case 0:
                     ui.onMouseButtonUpOutside(btn, mx - ui.getX(), my - ui.getY());
                     break;
                  case 1:
                     if (ui.onConsumeMouseButtonUp(btn, mx - ui.getX(), my - ui.getY())) {
                        consumed = true;
                     }
               }
            }
         }
         consumed |= capturedRelease;
         if (consumed) {
            consumedButtons |= 1 << btn;
            if (SubframeInput.active() && Mouse.isButtonPressed(btn)) Mouse.UIBlockButtonDown(btn);
         }

         if (btn == 0) {
            consumedClick = consumed;
         } else if (btn == 1) {
            consumedRClick = consumed;
         }
      }

      for (int btn = 2; btn < Mouse.getButtonCount(); btn++) {
         if (SubframeInput.active() && ((consumedButtons & (1 << btn)) != 0 || Mouse.uiCaptured[btn])) continue;
         if ((!subframeEventDispatch || subframeEdges) && Mouse.isButtonPressed(btn)) {
            LuaEventManager.triggerEvent("OnKeyStartPressed", 10000 + btn);
            LuaEventManager.triggerEvent("OnKeyPressed", 10000 + btn);
         } else if ((!subframeEventDispatch || subframeHeld) && !subframePressedThisFrame(btn) && !Mouse.isButtonReleased(btn) && Mouse.isButtonDown(btn)) {
            LuaEventManager.triggerEvent("OnKeyKeepPressed", 10000 + btn);
         }
      }

      if ((!subframeEventDispatch || subframeEdges) && Mouse.isLeftPressed()) {
         if (!consumedClick) {
            LuaEventManager.triggerEvent("OnMouseDown", BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
            LuaEventManager.triggerEvent("OnKeyStartPressed", 10000);
            LuaEventManager.triggerEvent("OnKeyPressed", 10000);
            CloseContainers();
         } else {
            Mouse.UIBlockButtonDown(0);
         }
      } else if ((!subframeEventDispatch || subframeEdges) && Mouse.isLeftReleased()) {
         if (!consumedClick) {
            LuaEventManager.triggerEvent("OnMouseUp", BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
         }
      } else if ((!subframeEventDispatch || subframeHeld) && !subframePressedThisFrame(0) && Mouse.isLeftDown() && !consumedClick
         && (!SubframeInput.active() || !Mouse.uiCaptured[0])) {
         LuaEventManager.triggerEvent("OnKeyKeepPressed", 10000);
      }

      if ((!subframeEventDispatch || subframeEdges) && Mouse.isRightPressed()) {
         if (!consumedRClick) {
            LuaEventManager.triggerEvent("OnRightMouseDown", BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
            if (checkPicked()) {
               LuaEventManager.triggerEvent("OnObjectRightMouseButtonDown", picked.tile, BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
            }
         } else {
            Mouse.UIBlockButtonDown(1);
         }

         if ((!SubframeInput.active() || !consumedRClick) && IsoWorld.instance.currentCell != null
            && getPicked() != null
            && getSpeedControls() != null
            && !IsoPlayer.getInstance().isAiming()
            && !IsoPlayer.getInstance().isAsleep()
            && !GameTime.isGamePaused()) {
            getSpeedControls().SetCurrentGameSpeed(1);
            getPicked().tile.onMouseRightClick(getPicked().lx, getPicked().ly);
            setRightDownObject(getPicked().tile);
         }
      } else if ((!subframeEventDispatch || subframeEdges) && Mouse.isRightReleased()) {
         if (!consumedRClick) {
            LuaEventManager.triggerEvent("OnRightMouseUp", BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
            if (checkPicked()) {
               LuaEventManager.triggerEvent("OnObjectRightMouseButtonUp", picked.tile, BoxedStaticValues.toDouble(mx), BoxedStaticValues.toDouble(my));
            }
         }

         if (IsoPlayer.getInstance() != null) {
            IsoPlayer.getInstance().setDragObject(null);
         }

         if ((!SubframeInput.active() || !consumedRClick) && IsoWorld.instance.currentCell != null
            && getRightDownObject() != null
            && IsoPlayer.getInstance() != null
            && !IsoPlayer.getInstance().isAiming()
            && !IsoPlayer.getInstance().isAsleep()) {
            getRightDownObject().onMouseRightReleased();
            setRightDownObject(null);
         }
         if (SubframeInput.active()) setRightDownObject(null);
      } else if ((!subframeEventDispatch || subframeHeld) && Mouse.isRightDown()) {
         for (int i = UI.size() - 1; i >= 0; i--) {
            UIElementInterface ui = UI.get(i);
            if (isOverElement(ui, mx, my) == 1) {
               consumedRClick = true;
               break;
            }
         }

         if (!consumedRClick && !subframePressedThisFrame(1) && (!SubframeInput.active() || !Mouse.uiCaptured[1])) {
            LuaEventManager.triggerEvent("OnKeyKeepPressed", 10001);
         }
      }

      if ((!subframeEventDispatch || subframeHeld) && !consumedRClick && Mouse.isRightDelay()) {
         LuaEventManager.triggerEvent("OnKeyStartPressed", 10001);
         LuaEventManager.triggerEvent("OnKeyPressed", 10001);
      }

      return consumedButtons | (consumedClick ? 1 : 0) | (consumedRClick ? 2 : 0);
   }
   private static boolean subframePressedThisFrame(int button) {
      return subframeEventDispatch && subframeHeld && (SubframeInput.framePressed() & 1 << button) != 0;
   }

   private static boolean updateMouseMove(int mx, int my, boolean consumedMove) {
      if (getLastMouseX() != mx || getLastMouseY() != my) {
         for (int i = UI.size() - 1; i >= 0; i--) {
            UIElementInterface ui = UI.get(i);
            if ((ui.isIgnoreLossControl() || !TutorialManager.instance.stealControl) && ui.isVisible()) {
               if (!ui.isOverElement(mx, my) && !ui.isCapture()) {
                  ui.onExtendMouseMoveOutside(mx - getLastMouseX(), my - getLastMouseY(), mx - ui.getX(), my - ui.getY());
               } else if (!consumedMove && ui.onConsumeMouseMove(mx - getLastMouseX(), my - getLastMouseY(), mx - ui.getX(), my - ui.getY())) {
                  consumedMove = true;
               }
            }
         }
      }

      return consumedMove;
   }

   private static void updateUIElements() {
      boolean pzoptStagger = pzopt.UiTicks.active(); // pzopt: uiTickStagger, every top-level element on its own 100 ms tick
      boolean pzoptTick = doTick; // pzopt: uiTickStagger
      long pzoptInterval = uiUpdateIntervalMS; // pzopt: uiTickStagger
      long pzoptNow = System.currentTimeMillis(); // pzopt: uiTickStagger
      if (pzoptStagger) { // pzopt: uiTickStagger
         pzopt.UiTicks.beginFrame(pzoptNow); // pzopt: uiTickStagger
      } // pzopt: uiTickStagger
      for (int i = 0; i < UI.size(); i++) {
         if (pzoptStagger) { // pzopt: uiTickStagger
            doTick = pzopt.UiTicks.due(UI.get(i), i, UI.size(), pzoptNow); // pzopt: uiTickStagger
            uiUpdateIntervalMS = doTick ? pzopt.UiTicks.interval() : pzoptInterval; // pzopt: uiTickStagger
         } // pzopt: uiTickStagger
         long pzoptT0 = System.nanoTime(); // pzopt: uiProfile, per top-level element update
         UI.get(i).update();
         pzopt.UiProfile.elementUpdated(UI.get(i), System.nanoTime() - pzoptT0, doTick); // pzopt: uiProfile
      }
      if (pzoptStagger) { // pzopt: uiTickStagger
         doTick = pzoptTick; // pzopt: uiTickStagger
         uiUpdateIntervalMS = pzoptInterval; // pzopt: uiTickStagger
      } // pzopt: uiTickStagger
   }

   private static boolean checkPicked() {
      return picked != null && picked.tile != null && picked.tile.getObjectIndex() != -1;
   }

   private static void handleZoomKeys() {
      boolean allowZoom = true;
      if (Core.currentTextEntryBox != null && Core.currentTextEntryBox.isEditable() && Core.currentTextEntryBox.isDoingTextEntry()) {
         allowZoom = false;
      }

      if (GameTime.isGamePaused()) {
         allowZoom = false;
      }

      if (allowZoom) {
         IsoPlayer.visitAllPlayersWithComponent(CharacterInputComponent.class, (player, playerInput) -> {
            int playerIdx = player.getIndex();
            if (playerInput.isZoomInButtonPressed()) {
               Core.getInstance().doZoomScroll(playerIdx, -1);
            }

            if (playerInput.isZoomOutButtonPressed()) {
               Core.getInstance().doZoomScroll(playerIdx, 1);
            }
         });
      }
   }

   public static Double getLastMouseX() {
      return BoxedStaticValues.toDouble(lastMouseX);
   }

   public static void setLastMouseX(double aLastMouseX) {
      lastMouseX = (int)aLastMouseX;
   }

   public static Double getLastMouseY() {
      return BoxedStaticValues.toDouble(lastMouseY);
   }

   public static void setLastMouseY(double aLastMouseY) {
      lastMouseY = (int)aLastMouseY;
   }

   public static ClickObject getPicked() {
      return picked;
   }

   public static void setPicked(ClickObject aPicked) {
      picked = aPicked == null ? null : pickedCopy.set(aPicked);
   }

   public static Clock getClock() {
      return clock;
   }

   public static void setClock(Clock aClock) {
      clock = aClock;
   }

   public static ArrayList<UIElementInterface> getUI() {
      return UI;
   }

   public static void setUI(ArrayList<UIElementInterface> aUI) {
      PZArrayUtil.copy(UI, aUI);
   }

   public static ObjectTooltip getToolTip() {
      return toolTip;
   }

   public static void setToolTip(ObjectTooltip aToolTip) {
      toolTip = aToolTip;
   }

   public static Texture getMouseArrow() {
      return mouseArrow;
   }

   public static void setMouseArrow(Texture aMouseArrow) {
      mouseArrow = aMouseArrow;
   }

   public static Texture getMouseExamine() {
      return mouseExamine;
   }

   public static void setMouseExamine(Texture aMouseExamine) {
      mouseExamine = aMouseExamine;
   }

   public static Texture getMouseAttack() {
      return mouseAttack;
   }

   public static void setMouseAttack(Texture aMouseAttack) {
      mouseAttack = aMouseAttack;
   }

   public static Texture getMouseGrab() {
      return mouseGrab;
   }

   public static void setMouseGrab(Texture aMouseGrab) {
      mouseGrab = aMouseGrab;
   }

   public static SpeedControls getSpeedControls() {
      return speedControls;
   }

   public static void setSpeedControls(SpeedControls aSpeedControls) {
      speedControls = aSpeedControls;
   }

   public static UIDebugConsole getDebugConsole() {
      return debugConsole;
   }

   public static void setDebugConsole(UIDebugConsole aDebugConsole) {
      debugConsole = aDebugConsole;
   }

   public static MoodlesUI getMoodleUI(double index) {
      return MoodleUI[(int)index];
   }

   public static void setMoodleUI(double index, MoodlesUI aMoodleUI) {
      MoodleUI[(int)index] = aMoodleUI;
   }

   public static boolean isbFadeBeforeUI() {
      return fadeBeforeUi;
   }

   public static void setbFadeBeforeUI(boolean abFadeBeforeUI) {
      fadeBeforeUi = abFadeBeforeUI;
   }

   public static ActionProgressBar getProgressBar(int index) {
      return ProgressBar[index];
   }

   public static boolean trySetProgressBarValue(int index, float value) {
      ActionProgressBar progressBar = getProgressBar(index);
      if (progressBar == null) {
         return false;
      }

      progressBar.setValue(value);
      return true;
   }

   public static void setProgressBar(double index, ActionProgressBar aProgressBar) {
      ProgressBar[(int)index] = aProgressBar;
   }

   public static Double getFadeAlpha() {
      return BoxedStaticValues.toDouble(fadeAlpha);
   }

   public static void setFadeAlpha(double aFadeAlpha) {
      fadeAlpha = PZMath.clamp((float)aFadeAlpha, 0.0F, 1.0F);
   }

   public static Double getFadeInTimeMax() {
      return BoxedStaticValues.toDouble(fadeInTimeMax);
   }

   public static void setFadeInTimeMax(double aFadeInTimeMax) {
      fadeInTimeMax = (int)aFadeInTimeMax;
   }

   public static Double getFadeInTime() {
      return BoxedStaticValues.toDouble(fadeInTime);
   }

   public static void setFadeInTime(double aFadeInTime) {
      fadeInTime = Math.max((int)aFadeInTime, 0);
   }

   public static Boolean isFadingOut() {
      return fadingOut ? Boolean.TRUE : Boolean.FALSE;
   }

   public static void setFadingOut(boolean aFadingOut) {
      fadingOut = aFadingOut;
   }

   public static Texture getLastMouseTexture() {
      return lastMouseTexture;
   }

   public static void setLastMouseTexture(Texture aLastMouseTexture) {
      lastMouseTexture = aLastMouseTexture;
   }

   public static IsoObject getLastPicked() {
      return lastPicked;
   }

   public static void setLastPicked(IsoObject aLastPicked) {
      lastPicked = aLastPicked;
   }

   public static ArrayList<String> getDoneTutorials() {
      return DoneTutorials;
   }

   public static void setDoneTutorials(ArrayList<String> aDoneTutorials) {
      PZArrayUtil.copy(DoneTutorials, aDoneTutorials);
   }

   public static float getLastOffX() {
      return lastOffX;
   }

   public static void setLastOffX(float aLastOffX) {
      lastOffX = aLastOffX;
   }

   public static float getLastOffY() {
      return lastOffY;
   }

   public static void setLastOffY(float aLastOffY) {
      lastOffY = aLastOffY;
   }

   public static ModalDialog getModal() {
      return modal;
   }

   public static void setModal(ModalDialog aModal) {
      modal = aModal;
   }

   public static Texture getBlack() {
      return black;
   }

   public static void setBlack(Texture aBlack) {
      black = aBlack;
   }

   public static float getLastAlpha() {
      return lastAlpha;
   }

   public static void setLastAlpha(float aLastAlpha) {
      lastAlpha = aLastAlpha;
   }

   public static Vector2 getPickedTileLocal() {
      return PickedTileLocal;
   }

   public static void setPickedTileLocal(Vector2 aPickedTileLocal) {
      PickedTileLocal.set(aPickedTileLocal);
   }

   public static Vector2 getPickedTile() {
      return PickedTile;
   }

   public static void setPickedTile(Vector2 aPickedTile) {
      PickedTile.set(aPickedTile);
   }

   public static IsoObject getRightDownObject() {
      return rightDownObject;
   }

   public static void setRightDownObject(IsoObject aRightDownObject) {
      rightDownObject = aRightDownObject;
   }

   static void pushToTop(UIElementInterface aThis) {
      toTop.add(aThis);
   }

   public static boolean isShowPausedMessage() {
      return showPausedMessage;
   }

   public static void setShowPausedMessage(boolean showPausedMessage) {
      UIManager.showPausedMessage = showPausedMessage;
   }

   public static void setShowLuaDebuggerOnError(boolean show) {
      showLuaDebuggerOnError = show;
   }

   public static boolean isShowLuaDebuggerOnError() {
      return showLuaDebuggerOnError;
   }

   public static void debugBreakpoint(String filename, long pc) {
      if (showLuaDebuggerOnError) {
         if (Core.currentTextEntryBox != null) {
            Core.currentTextEntryBox.setDoingTextEntry(false);
            Core.currentTextEntryBox = null;
         }

         if (!GameServer.server) {
            if (!(GameWindow.states.current instanceof GameLoadingState)) {
               previousThread = defaultthread;
               defaultthread = LuaManager.debugthread;
               int frameStage = Core.getInstance().frameStage;
               if (frameStage != 0) {
                  if (frameStage <= 1) {
                     Core.getInstance().EndFrame(0);
                  }

                  if (frameStage <= 2) {
                     Core.getInstance().StartFrameUI();
                  }

                  if (frameStage <= 3) {
                     Core.getInstance().EndFrameUI();
                  }
               }

               LuaManager.thread.step = false;
               LuaManager.thread.stepInto = false;
               if (!toRemove.isEmpty()) {
                  UI.removeAll(toRemove);
               }

               toRemove.clear();
               if (!toAdd.isEmpty()) {
                  UI.addAll(toAdd);
               }

               toAdd.clear();
               boolean bOldSuspend = suspend;
               ArrayList<UIElementInterface> oldUI = new ArrayList<>(UI);
               UI.clear();
               suspend = false;
               setShowPausedMessage(false);
               boolean bFinished = false;
               boolean[] bDebounce = new boolean[11];
               boolean bEscKeyDown = false;

               for (int n = 0; n < 11; n++) {
                  bDebounce[n] = true;
               }

               if (debugUI.isEmpty()) {
                  LuaManager.debugcaller.pcall(LuaManager.debugthread, LuaManager.env.rawget("DoLuaDebugger"), filename, pc);
               } else {
                  UI.addAll(debugUI);
                  LuaManager.debugcaller.pcall(LuaManager.debugthread, LuaManager.env.rawget("DoLuaDebuggerOnBreak"), filename, pc);
               }

               Mouse.setCursorVisible(true);
               sync.begin();

               while (true) {
                  if (RenderThread.isCloseRequested()) {
                     System.exit(0);
                  }

                  if (GameKeyboard.isKeyDown(1)) {
                     bEscKeyDown = true;
                  }

                  if (!GameWindow.luaDebuggerKeyDown && (GameKeyboard.isKeyDown(KeybindId.TOGGLE_LUA_DEBUGGER) || bEscKeyDown && !GameKeyboard.isKeyDown(1))) {
                     GameWindow.luaDebuggerKeyDown = true;
                     executeGame(oldUI, bOldSuspend, frameStage);
                     return;
                  }

                  String action = luaDebuggerAction;
                  luaDebuggerAction = null;
                  if ("StepInto".equalsIgnoreCase(action)) {
                     LuaManager.thread.step = true;
                     LuaManager.thread.stepInto = true;
                     executeGame(oldUI, bOldSuspend, frameStage);
                     return;
                  }

                  if ("StepOver".equalsIgnoreCase(action)) {
                     LuaManager.thread.step = true;
                     LuaManager.thread.stepInto = false;
                     LuaManager.thread.lastCallFrameIdx = LuaManager.thread.getCurrentCoroutine().getCallframeTop();
                     executeGame(oldUI, bOldSuspend, frameStage);
                     return;
                  }

                  if ("Resume".equalsIgnoreCase(action)) {
                     executeGame(oldUI, bOldSuspend, frameStage);
                     return;
                  }

                  sync.startFrame();

                  for (int n = 0; n < 11; n++) {
                     boolean bPressed = GameKeyboard.isKeyDown(59 + n);
                     if (bPressed) {
                        if (!bDebounce[n]) {
                           if (n + 1 == 5) {
                              LuaManager.thread.step = true;
                              LuaManager.thread.stepInto = true;
                              executeGame(oldUI, bOldSuspend, frameStage);
                              return;
                           }

                           if (n + 1 == 6) {
                              LuaManager.thread.step = true;
                              LuaManager.thread.stepInto = false;
                              LuaManager.thread.lastCallFrameIdx = LuaManager.thread.getCurrentCoroutine().getCallframeTop();
                              executeGame(oldUI, bOldSuspend, frameStage);
                              return;
                           }
                        }

                        bDebounce[n] = true;
                     } else {
                        bDebounce[n] = false;
                     }
                  }

                  Mouse.update();
                  GameKeyboard.update();
                  Core.getInstance().DoFrameReady();
                  update();
                  Core.getInstance().StartFrame(0, true);
                  Core.getInstance().EndFrame(0);
                  Core.getInstance().RenderOffScreenBuffer();
                  if (Core.getInstance().StartFrameUI()) {
                     render();
                  }

                  Core.getInstance().EndFrameUI();
                  resize();
                  if (!GameKeyboard.isKeyDown(KeybindId.TOGGLE_LUA_DEBUGGER)) {
                     GameWindow.luaDebuggerKeyDown = false;
                  }

                  sync.endFrame();
                  if (!Core.isUseGameViewport()) {
                     Core.getInstance().setScreenSize(RenderThread.getDisplayWidth(), RenderThread.getDisplayHeight());
                  }
               }
            }
         }
      }
   }

   private static void executeGame(ArrayList<UIElementInterface> oldUI, boolean bOldSuspend, int frameStage) {
      debugUI.clear();
      debugUI.addAll(UI);
      UI.clear();
      UI.addAll(oldUI);
      suspend = bOldSuspend;
      setShowPausedMessage(true);
      if (!LuaManager.thread.step && frameStage != 0) {
         if (frameStage == 1) {
            Core.getInstance().StartFrame(0, true);
         }

         if (frameStage == 2) {
            Core.getInstance().StartFrame(0, true);
            Core.getInstance().EndFrame(0);
         }

         if (frameStage == 3) {
            Core.getInstance().StartFrame(0, true);
            Core.getInstance().EndFrame(0);
            Core.getInstance().StartFrameUI();
         }
      }

      defaultthread = previousThread;
   }

   public static KahluaThread getDefaultThread() {
      if (defaultthread == null) {
         defaultthread = LuaManager.thread;
      }

      return defaultthread;
   }

   public static Double getDoubleClickInterval() {
      return BoxedStaticValues.toDouble(500.0);
   }

   public static Double getDoubleClickDist() {
      return BoxedStaticValues.toDouble(5.0);
   }

   public static Boolean isDoubleClick(double x1, double y1, double x2, double y2, double clickTime) {
      if (Math.abs(x2 - x1) > getDoubleClickDist()) {
         return false;
      } else if (Math.abs(y2 - y1) > getDoubleClickDist()) {
         return false;
      } else {
         long now = SubframeInput.inEvent() ? SubframeInput.eventMillis() : System.currentTimeMillis();
         return now - clickTime > getDoubleClickInterval() ? Boolean.FALSE : Boolean.TRUE;
      }
   }

   protected static void updateTooltip(double mx, double my) {
      UIElementInterface mouseOverUI = null;

      for (int i = getUI().size() - 1; i >= 0; i--) {
         UIElementInterface ui = getUI().get(i);
         if (ui != toolTip && ui.isVisible() && ui.isOverElement(mx, my) && (ui.getMaxDrawHeight() == -1.0 || my < ui.getY() + ui.getMaxDrawHeight())) {
            mouseOverUI = ui;
            break;
         }
      }

      IsoObject mouseOverObject = null;
      if (mouseOverUI == null && getPicked() != null) {
         mouseOverObject = getPicked().tile;
         if (mouseOverObject != getLastPicked() && toolTip != null) {
            toolTip.targetAlpha = 0.0F;
            if (mouseOverObject.haveSpecialTooltip()) {
               if (getToolTip().object != mouseOverObject) {
                  getToolTip().show(mouseOverObject, (int)mx + 24, (int)my + 24);
                  if (toolTip.isVisible()) {
                     toolTip.showDelay = 0;
                  }
               } else {
                  toolTip.targetAlpha = 1.0F;
               }
            }
         }
      }

      setLastPicked(mouseOverObject);
      if (toolTip != null && (mouseOverObject == null || toolTip.alpha <= 0.0F && toolTip.targetAlpha <= 0.0F)) {
         toolTip.hide();
      }

      if (toolTip != null && toolTip.isVisible()) {
         toolTip.setX(Mouse.getXA() + 24);
         toolTip.setY(Mouse.getYA() + 24);
      }
   }

   public static void setPlayerInventory(int playerIndex, UIElementInterface inventory, UIElementInterface loot) {
      if (playerIndex == 0) {
         playerInventoryUI = inventory;
         playerLootUI = loot;
      }
   }

   public static void setPlayerInventoryTooltip(int playerIndex, UIElementInterface inventory, UIElementInterface loot) {
      if (playerIndex == 0) {
         playerInventoryTooltip = inventory;
         playerLootTooltip = loot;
      }
   }

   public static boolean isMouseOverInventory() {
      if (playerInventoryTooltip != null && playerInventoryTooltip.isMouseOver()) {
         return true;
      } else if (playerLootTooltip != null && playerLootTooltip.isMouseOver()) {
         return true;
      } else if (playerInventoryUI != null && playerLootUI != null) {
         return playerInventoryUI.getMaxDrawHeight() == -1.0 && playerInventoryUI.isMouseOver()
            ? true
            : playerLootUI.getMaxDrawHeight() == -1.0 && playerLootUI.isMouseOver();
      } else {
         return false;
      }
   }

   public static void updateBeforeFadeOut() {
      if (!toRemove.isEmpty()) {
         UI.removeAll(toRemove);
         toRemove.clear();
      }

      if (!toAdd.isEmpty()) {
         UI.addAll(toAdd);
         toAdd.clear();
      }
   }

   public static void setVisibleAllUI(boolean visible) {
      visibleAllUi = visible;
   }

   public static void setFadeBeforeUI(int playerIndex, boolean bFadeBeforeUI) {
      playerFadeInfo[playerIndex].setFadeBeforeUI(bFadeBeforeUI);
   }

   public static float getFadeAlpha(double playerIndex) {
      return playerFadeInfo[(int)playerIndex].getFadeAlpha();
   }

   public static void setFadeTime(double playerIndex, double fadeTime) {
      playerFadeInfo[(int)playerIndex].setFadeTime((int)fadeTime);
   }

   public static void FadeIn(double playerIndex, double seconds) {
      playerFadeInfo[(int)playerIndex].FadeIn((int)seconds);
   }

   public static void FadeOut(double playerIndex, double seconds) {
      playerFadeInfo[(int)playerIndex].FadeOut((int)seconds);
   }

   public static boolean isFBOActive() {
      return useUiFbo;
   }

   public static double getMillisSinceLastUpdate() {
      return uiUpdateIntervalMS;
   }

   public static double getSecondsSinceLastUpdate() {
      return uiUpdateIntervalMS / 1000.0;
   }

   public static double getMillisSinceLastRender() {
      return uiRenderIntervalMS;
   }

   public static double getSecondsSinceLastRender() {
      return uiRenderIntervalMS / 1000.0;
   }

   public static boolean onKeyPress(int key) {
      for (int i = UI.size() - 1; i >= 0; i--) {
         UIElementInterface ui = UI.get(i);
         if (ui.isVisible() && ui.isWantKeyEvents() && ui.onConsumeKeyPress(key)) {
            return true;
         }
      }

      return false;
   }

   public static boolean onKeyRepeat(int key) {
      for (int i = UI.size() - 1; i >= 0; i--) {
         UIElementInterface ui = UI.get(i);
         if (ui.isVisible() && ui.isWantKeyEvents() && ui.onConsumeKeyRepeat(key)) {
            return true;
         }
      }

      return false;
   }

   public static boolean onKeyRelease(int key) {
      for (int i = UI.size() - 1; i >= 0; i--) {
         UIElementInterface ui = UI.get(i);
         if (ui.isVisible() && ui.isWantKeyEvents() && ui.onConsumeKeyRelease(key)) {
            return true;
         }
      }

      return false;
   }

   public static boolean isForceCursorVisible() {
      for (int i = UI.size() - 1; i >= 0; i--) {
         UIElementInterface ui = UI.get(i);
         if (ui.isVisible() && (ui.isForceCursorVisible() || ui.isMouseOver())) {
            return true;
         }
      }

      return false;
   }

   public static Object tableget(KahluaTable table, Object key) {
      return table != null ? getDefaultThread().tableget(table, key) : null;
   }

   public static float getBlinkAlpha(int playerIndex) {
      return playerIndex >= 0 && playerIndex < playerBlinkInfo.length ? playerBlinkInfo[playerIndex].alpha : 1.0F;
   }

   public static int getSyncedIconIndex(int playerIndex, int maxIndex) {
      return playerBlinkInfo[0].syncedIconIndex % maxIndex;
   }

   public static int resetSyncedIconIndex(int playerIndex) {
      return playerBlinkInfo[0].syncedIconIndex = 0;
   }

   public static boolean isRendering() {
      return rendering;
   }

   public static boolean isUpdating() {
      return updating;
   }

   public static boolean isModalVisible() {
      for (int i = 0; i < getUI().size(); i++) {
         UIElementInterface ui = getUI().get(i);
         if (ui.isModalVisible()) {
            return true;
         }
      }

      return false;
   }

   static {
      for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
         playerFadeInfo[playerIndex] = new UIManager.FadeInfo(playerIndex);
         playerBlinkInfo[playerIndex] = new UIManager.BlinkInfo();
      }
   }

   private static class BlinkInfo {
      private float alpha = 1.0F;
      private final float delta = 0.015F;
      private float direction = -1.0F;
      private int syncedIconIndex;
      private float syncedIconIndexTimer;

      private void update() {
         if (this.alpha >= 1.0F) {
            this.alpha = 1.0F;
            this.direction = -1.0F;
         } else if (this.alpha <= 0.0F) {
            this.alpha = 0.0F;
            this.direction = 1.0F;
         }

         this.alpha = this.alpha + 0.015F * (PerformanceSettings.getLockFPS() / 30.0F) * this.direction;
         this.syncedIconIndexTimer = this.syncedIconIndexTimer + GameTime.instance.getRealworldSecondsSinceLastUpdate();
         if (this.syncedIconIndexTimer > 1.5F) {
            this.syncedIconIndexTimer = 0.0F;
            this.syncedIconIndex++;
            if (this.syncedIconIndex > 32767) {
               this.syncedIconIndex = 0;
            }
         }
      }

      private void reset() {
         this.alpha = 1.0F;
         this.direction = -1.0F;
      }
   }

   private static class FadeInfo {
      public int playerIndex;
      public boolean fadeBeforeUi;
      public float fadeAlpha;
      public int fadeTime = 2;
      public int fadeTimeMax = 2;
      public boolean fadingOut;

      public FadeInfo(int playerIndex) {
         this.playerIndex = playerIndex;
      }

      public boolean isFadeBeforeUI() {
         return this.fadeBeforeUi;
      }

      public void setFadeBeforeUI(boolean bFadeBeforeUI) {
         this.fadeBeforeUi = bFadeBeforeUI;
      }

      public float getFadeAlpha() {
         return this.fadeAlpha;
      }

      public void setFadeAlpha(float fadeAlpha) {
         this.fadeAlpha = fadeAlpha;
      }

      public int getFadeTime() {
         return this.fadeTime;
      }

      public void setFadeTime(int fadeTime) {
         this.fadeTime = fadeTime;
      }

      public int getFadeTimeMax() {
         return this.fadeTimeMax;
      }

      public void setFadeTimeMax(int fadeTimeMax) {
         this.fadeTimeMax = fadeTimeMax;
      }

      public boolean isFadingOut() {
         return this.fadingOut;
      }

      public void setFadingOut(boolean fadingOut) {
         this.fadingOut = fadingOut;
      }

      public void FadeIn(int seconds) {
         this.setFadeTimeMax((int)(seconds * 30 * (PerformanceSettings.getLockFPS() / 30.0F)));
         this.setFadeTime(this.getFadeTimeMax());
         this.setFadingOut(false);
      }

      public void FadeOut(int seconds) {
         this.setFadeTimeMax((int)(seconds * 30 * (PerformanceSettings.getLockFPS() / 30.0F)));
         this.setFadeTime(this.getFadeTimeMax());
         this.setFadingOut(true);
      }

      public void update() {
         this.setFadeTime(this.getFadeTime() - 1);
      }

      public void render() {
         this.setFadeAlpha((float)this.getFadeTime() / this.getFadeTimeMax());
         if (this.getFadeAlpha() > 1.0F) {
            this.setFadeAlpha(1.0F);
         }

         if (this.getFadeAlpha() < 0.0F) {
            this.setFadeAlpha(0.0F);
         }

         if (this.isFadingOut()) {
            this.setFadeAlpha(1.0F - this.getFadeAlpha());
         }

         if (!(this.getFadeAlpha() <= 0.0F)) {
            int x = IsoCamera.getScreenLeft(this.playerIndex);
            int y = IsoCamera.getScreenTop(this.playerIndex);
            int w = IsoCamera.getScreenWidth(this.playerIndex);
            int h = IsoCamera.getScreenHeight(this.playerIndex);
            UIManager.DrawTexture(UIManager.getBlack(), x, y, w, h, this.getFadeAlpha());
         }
      }
   }

   static class Sync {
      private final int fps = 60;
      private final long period = 16666666L;
      private long excess;
      private long beforeTime = System.nanoTime();
      private long overSleepTime;

      void begin() {
         this.beforeTime = System.nanoTime();
         this.overSleepTime = 0L;
      }

      void startFrame() {
         this.excess = 0L;
      }

      void endFrame() {
         long afterTime = System.nanoTime();
         long timeDiff = afterTime - this.beforeTime;
         long sleepTime = 16666666L - timeDiff - this.overSleepTime;
         if (sleepTime > 0L) {
            try {
               Thread.sleep(sleepTime / 1000000L);
            } catch (InterruptedException var8) {
            }

            this.overSleepTime = System.nanoTime() - afterTime - sleepTime;
         } else {
            this.excess -= sleepTime;
            this.overSleepTime = 0L;
         }

         this.beforeTime = System.nanoTime();
      }
   }
}
