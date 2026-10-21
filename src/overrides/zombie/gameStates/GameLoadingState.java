package zombie.gameStates;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import zombie.AmbientStreamManager;
import zombie.ChunkMapFilenames;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.SandboxOptions;
import zombie.SoundManager;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.Lua.LuaManager.GlobalObject;
import zombie.SandboxOptions.SandboxOption;
import zombie.characters.IsoPlayer;
import zombie.characters.ecs.ECSEntity;
import zombie.chat.ChatManager;
import zombie.chat.ChatUtility;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.ThreadGroups;
import zombie.core.Translator;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.physics.Bullet;
import zombie.core.raknet.UdpConnection.ConnectionType;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.population.OutfitManager;
import zombie.core.skinnedmodel.runtime.RuntimeAnimationScript;
import zombie.core.textures.AnimatedTexture;
import zombie.core.textures.AnimatedTextures;
import zombie.core.textures.Texture;
import zombie.core.znet.ServerBrowser;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.GameStateMachine.StateAction;
import zombie.globalObjects.CGlobalObjects;
import zombie.globalObjects.SGlobalObjects;
import zombie.input.GameKeyboard;
import zombie.input.JoypadManager;
import zombie.input.Mouse;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoObjectPicker;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoWater;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.WorldConverter;
import zombie.iso.WorldStreamer;
import zombie.iso.areas.SafeHouse;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.sprite.SkyBox;
import zombie.iso.weather.ClimateManager;
import zombie.modding.ActiveMods;
import zombie.modding.ActiveModsFile;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.NetworkAIParams;
import zombie.network.ServerOptions;
import zombie.randomizedWorld.randomizedBuilding.TableStories.RBTableStoryBase;
import zombie.savefile.SavefileNaming;
import zombie.scripting.ScriptManager;
import zombie.ui.ScreenFader;
import zombie.ui.TextManager;
import zombie.ui.TutorialManager;
import zombie.ui.UIFont;
import zombie.ui.UIManager;
import zombie.util.StringUtils;
import zombie.vehicles.BaseVehicle;
import zombie.world.WorldDictionary;
import zombie.worldMap.WorldMapImages;
import zombie.worldMap.WorldMapVisited;

@UsedFromLua
public final class GameLoadingState extends GameState {
   static {
      pzopt.Overrides.onClassLoaded("zombie.gameStates.GameLoadingState");
   }

   public static final int QUICK_TIP_MAX_TIMER = 720;
   public static Thread loader;
   private static boolean newGame = true;
   private static long startTime;
   public static boolean worldVersionError;
   private static boolean unexpectedError;
   public static String gameLoadingString = "";
   public static boolean playerWrongIP;
   private static boolean showedUI;
   private static boolean showedClickToSkip;
   private static boolean playerCreated;
   private static boolean done;
   public static boolean convertingWorld;
   public static int convertingFileCount = -1;
   public static int convertingFileMax = -1;
   private volatile boolean waitForAssetLoadingToFinish1;
   private volatile boolean waitForAssetLoadingToFinish2;
   private long pzoptWaitLogMs; // pzopt: assetLock2 wait diagnostic
   /** pzopt: loader-thread steps handed to the main thread (pzoptOnMain), run at the top of update() */
   private final java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.FutureTask<Void>> pzoptMainTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();

   /**
    * pzopt: run r on the main thread and wait for it when the world was entered early (centerFirstLoad: the main thread
    * loads chunks meanwhile), else here as stock does.
    */
   private static void pzoptInitChatIcons() { // pzopt: the icon scan iterates the unsynchronised shared texture table; retry it on a concurrent write
      for (int attempt = 0; ; attempt++) {
         try {
            ChatUtility.InitAllowedChatIcons();
            if (attempt > 0) {
               pzopt.Log.info("chat icons: scan retried " + attempt + " time(s) after a concurrent texture registration");
            }
            return;
         } catch (java.util.ConcurrentModificationException e) {
            if (attempt >= 50) {
               throw e;
            }
            try {
               Thread.sleep(5L);
            } catch (InterruptedException ie) {
               Thread.currentThread().interrupt();
               throw e;
            }
         }
      }
   }

   private void pzoptOnMain(Runnable r) {
      if (!pzopt.Overrides.enabled() || !pzopt.CenterFirstLoad.enteredEarly()) {
         r.run();
         return;
      }
      java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(r, null);
      this.pzoptMainTasks.add(task);
      try {
         task.get();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      } catch (java.util.concurrent.ExecutionException e) {
         if (e.getCause() instanceof RuntimeException re) {
            throw re;
         }
         throw new RuntimeException(e.getCause());
      }
   }
   private final Object assetLock1 = "Asset Lock 1";
   private final Object assetLock2 = "Asset Lock 2";
   private float time;
   private boolean forceDone;
   private String text;
   private float width;
   private static final ScreenFader screenFader = new ScreenFader();
   private AnimatedTexture animatedTexture;
   private long progressFadeStartMs;
   private int stage;
   private final float totalTime = 33.0F;
   private float loadingDotTick;
   private String loadingDot = "";
   private float clickToSkipAlpha = 1.0F;
   private boolean clickToSkipFadeIn;
   private float quickTipsTimer = 720.0F;
   private String quickTipsText;
   private List<String> quickTipsList;
   private List<String> quickTipsListJoke;
   private static final int BOTTOM_SCREEN = 40;

   public void enter() {
      pzopt.ResumeShot.startLoad(); // pzopt: resumeShot, decode the save's last view for the loading screen
      pzopt.LuaEventProfile.dump("the load's start (boot and main menu)"); // pzopt: luaEventProfile
      if (GameWindow.fileSystem instanceof zombie.fileSystem.FileSystemImpl) {
         pzopt.BootPump.onLoadStart(((zombie.fileSystem.FileSystemImpl)GameWindow.fileSystem).pzoptExecutor()); // pzopt: file pool back to its play width
         if (pzopt.Overrides.enabled()) {
            ((zombie.fileSystem.FileSystemImpl)GameWindow.fileSystem).pzoptSetMaxInFlight(pzopt.Config.FILE_INFLIGHT_LOAD); // pzopt: a second load
         }
      }

      pzopt.BootAsync.joinAnimSets(); // pzopt: the loader thread's IsoPlayer needs "player"; normally done long ago
      pzopt.AnimalPreload.start(); // pzopt: animalPreload (tail pass), the animal types' anim sets and action groups off the game thread
      if (pzopt.LuaPrecompiler.enabled()) {
         pzopt.Log.info(pzopt.LuaPrecompiler.stats());
      }

      this.loadQuickTipList();

      try {
         WorldMapImages.Reset();
         WorldMapVisited.Reset();
         LuaManager.releaseAllVideoTextures();
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
      }

      if (GameClient.client) {
         this.text = Translator.getText("UI_DirectConnectionPortWarning", new Object[]{ServerOptions.getInstance().udpPort.getValue()});
         this.width = TextManager.instance.MeasureStringX(UIFont.NewMedium, this.text) + 8;
      }

      GameWindow.loadedAsClient = GameClient.client;
      GameWindow.okToSaveOnExit = false;
      showedUI = false;
      ChunkMapFilenames.instance.clear();
      DebugType.DetailedInfo.println("Savefile name is \"" + Core.gameSaveWorld + "\"");
      gameLoadingString = "";

      try {
         LuaManager.LoadDirBase("server");
         LuaManager.finishChecksum();
      } catch (Exception e) {
         ExceptionLogger.logException(e);
      }

      ScriptManager.instance.LoadedAfterLua();
      Core.getInstance().initFBOs();
      Core.getInstance().initShaders();
      SkyBox.getInstance();
      IsoPuddles.getInstance();
      IsoWater.getInstance();
      GameWindow.serverDisconnected = false;
      if (GameClient.client && !GameClient.instance.connected) {
         GameClient.instance.init();
         Core.getInstance().setGameMode("Multiplayer");

         for (; GameClient.instance.id == -1; GameClient.instance.update()) {
            try {
               LuaEventManager.RunQueuedEvents();
               Thread.sleep(10L);
            } catch (InterruptedException e) {
               DebugType.General.printException(e, LogSeverity.Error);
            }
         }

         int id = GameClient.instance.id & 255;
         Core.gameSaveWorld = "clienttest" + id;
         GlobalObject.deleteSave("clienttest" + id);
         GlobalObject.createWorld("clienttest" + id);
      }

      if (Core.gameSaveWorld.isEmpty()) {
         DebugLog.log("No savefile directory was specified.  It's a bug.");
         GameWindow.DoLoadingText("No savefile directory was specified.  The game will now close.  Sorry!");

         try {
            Thread.sleep(4000L);
         } catch (Exception var5) {
         }

         System.exit(-1);
      }

      File file = new File(ZomboidFileSystem.instance.getCurrentSaveDir());
      if (!file.exists() && !Core.getInstance().isNoSave()) {
         DebugLog.log("The savefile directory doesn't exist.  It's a bug.");
         GameWindow.DoLoadingText("The savefile directory doesn't exist.  The game will now close.  Sorry!");

         try {
            Thread.sleep(4000L);
         } catch (Exception var4) {
         }

         System.exit(-1);
      }

      if (!Core.getInstance().isNoSave()) {
         SavefileNaming.ensureSubdirectoriesExist(ZomboidFileSystem.instance.getCurrentSaveDir());
      }

      try {
         if (!GameClient.client && !GameServer.server && !Core.tutorial && !Core.isLastStand() && !"Multiplayer".equals(Core.gameMode)) {
            FileWriter fw = new FileWriter(new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "latestSave.ini"));
            fw.write(IsoWorld.instance.getWorld() + "\r\n");
            fw.write(Core.getInstance().getGameMode() + "\r\n");
            fw.flush();
            fw.close();
         }
      } catch (IOException ex) {
         ExceptionLogger.logException(ex);
      }

      done = false;
      this.forceDone = false;
      IsoChunkMap.CalcChunkWidth();
      Core.setInitialSize();
      LosUtil.init(IsoChunkMap.chunkGridWidth * 8, IsoChunkMap.chunkGridWidth * 8);
      this.time = 0.0F;
      this.stage = 0;
      this.clickToSkipAlpha = 1.0F;
      this.clickToSkipFadeIn = false;
      startTime = System.currentTimeMillis();
      SoundManager.instance.Purge();
      SoundManager.instance.setMusicState("Loading");
      LuaEventManager.triggerEvent("OnPreMapLoad");
      newGame = true;
      worldVersionError = false;
      unexpectedError = false;
      playerCreated = false;
      convertingWorld = false;
      convertingFileCount = 0;
      convertingFileMax = -1;
      File inFile = ZomboidFileSystem.instance.getFileInCurrentSave("map_ver.bin");
      if (inFile.exists()) {
         newGame = false;
      }

      if (GameClient.client) {
         newGame = false;
      }

      if (!newGame) {
         this.stage = -1;
         screenFader.startFadeFromBlack();
         this.progressFadeStartMs = 0L;
      }

      WorldDictionary.setIsNewGame(newGame);
      GameKeyboard.noEventsWhileLoading = true;
      ServerBrowser.setSuppressLuaCallbacks(true);
      loader = new Thread(ThreadGroups.Workers, new Runnable() {
         @Override
         public void run() {
            LuaManager.thread.debugOwnerThread = Thread.currentThread();
            LuaManager.debugthread.debugOwnerThread = Thread.currentThread();

            try {
               this.runInner();
            } catch (Throwable t) {
               GameLoadingState.unexpectedError = true;
               ExceptionLogger.logException(t);
            } finally {
               LuaManager.thread.debugOwnerThread = GameWindow.gameThread;
               LuaManager.debugthread.debugOwnerThread = GameWindow.gameThread;
               UIManager.suspend = false;
            }
         }

         private void runInner() throws Exception {
            GameLoadingState.this.waitForAssetLoadingToFinish1 = true;
            synchronized (GameLoadingState.this.assetLock1) {
               while (GameLoadingState.this.waitForAssetLoadingToFinish1) {
                  try {
                     GameLoadingState.this.assetLock1.wait();
                  } catch (InterruptedException var9) {
                  }
               }
            }

            boolean success = new File(ZomboidFileSystem.instance.getGameModeCacheDir() + File.separator).mkdir();
            BaseVehicle.LoadAllVehicleTextures();
            if (GameClient.client) {
               GameClient.instance.GameLoadingRequestData();
            }

            TutorialManager.instance = new TutorialManager();
            GameTime.setInstance(new GameTime());
            ClimateManager.setInstance(new ClimateManager());
            String spawnRegion = IsoWorld.instance.getSpawnRegion();
            IsoWorld.instance = new IsoWorld();
            IsoWorld.instance.setSpawnRegion(spawnRegion);
            DebugOptions.testThreadCrash(0);
            IsoWorld.instance.init();
            pzopt.HotsaveWarmup.run(); // pzopt: hotsaveWarmup, the first hot save runs compiled serialisers
            if (GameWindow.serverDisconnected) {
               GameLoadingState.done = true;
            } else if (!GameLoadingState.playerWrongIP) {
               if (!GameLoadingState.worldVersionError) {
                  DebugType.General.println("triggerEvent OnGameTimeLoaded");
                  LuaEventManager.triggerEvent("OnGameTimeLoaded");
                  DebugType.General.println("GlobalObjects.initSystems() start");
                  SGlobalObjects.initSystems();
                  CGlobalObjects.initSystems();
                  DebugType.General.println("GlobalObjects.initSystems() end");
                  IsoObjectPicker.Instance.Init();
                  TutorialManager.instance.init();
                  TutorialManager.instance.CreateQuests();
                  File inFilex = ZomboidFileSystem.instance.getFileInCurrentSave("map_t.bin");
                  if (inFilex.exists()) {
                  }

                  if (!GameServer.server) {
                     inFilex = ZomboidFileSystem.instance.getFileInCurrentSave("map_ver.bin");
                     boolean newGame = !inFilex.exists();
                     if (newGame || IsoWorld.savedWorldVersion != 249) {
                        if (!newGame) {
                           GameLoadingState.gameLoadingString = "Saving converted world.";
                        }

                        try {
                           DebugType.General.println("GameWindow.save() start");
                           GameWindow.save(true);
                           DebugType.General.println("GameWindow.save() end");
                        } catch (Throwable t) {
                           ExceptionLogger.logException(t);
                        }
                     }
                  }

                  // pzopt: with centerFirstLoad the main thread already loads chunks, and registers their textures in the
                  // shared texture table (an unsynchronised HashMap), while this thread finishes; the steps that read or
                  // write that table run on the main thread (pzoptOnMain; a ConcurrentModificationException stopped 2 of 3
                  // Dell loads on the error screen)
                  GameLoadingState.this.pzoptOnMain(GameLoadingState::pzoptInitChatIcons); // pzopt: on the main thread and retried: other threads still register textures (2026-09-23, a Dell load stopped on the error with the hand-off alone)
                  ChatManager.getInstance().init(true, IsoPlayer.getInstance());
                  Bullet.startLoadingPhysicsMeshes();
                  GameLoadingState.this.pzoptOnMain(() -> { // pzopt: the shared texture table, see above
                     Texture.getSharedTexture("media/textures/NewShadow.png");
                     Texture.getSharedTexture("media/wallcutaways.png", 3);
                     Texture.getSharedTexture("media/windowframe_cutaways.png", 3);
                     Texture.getSharedTexture("media/windowframe_cutaways_2.png", 3);
                     Texture.getSharedTexture("media/windowframe_cutaways_3.png", 3);
                  }); // pzopt
                  DebugType.General.println("bWaitForAssetLoadingToFinish2 start");
                  GameLoadingState.this.waitForAssetLoadingToFinish2 = true;
                  java.util.concurrent.ExecutorService pzoptPool = GameWindow.fileSystem instanceof zombie.fileSystem.FileSystemImpl pzoptFs
                     ? pzoptFs.pzoptExecutor() : null; // pzopt
                  pzopt.BootPump.onAssetWait(pzoptPool, true); // pzopt: every core decodes while nothing else runs
                  synchronized (GameLoadingState.this.assetLock2) {
                     while (GameLoadingState.this.waitForAssetLoadingToFinish2) {
                        try {
                           GameLoadingState.this.assetLock2.wait();
                        } catch (InterruptedException var7) {
                        }
                     }
                  }
                  pzopt.BootPump.onAssetWait(pzoptPool, false); // pzopt
                  pzopt.Log.info(pzopt.FileTaskStats.summary()); // pzopt: the whole boot + load, per task class

                  DebugType.General.println("bWaitForAssetLoadingToFinish2 end");
                  if (PerformanceSettings.fboRenderChunk) {
                     DebugType.General.println("FBORenderChunkManager.gameLoaded() start");
                     FBORenderChunkManager.instance.gameLoaded();
                     DebugType.General.println("FBORenderChunkManager.gameLoaded() end");
                  }

                  DebugType.General.println("Bullet.initPhysicsMeshes() start");
                  Bullet.initPhysicsMeshes();
                  DebugType.General.println("Bullet.initPhysicsMeshes() end");
                  RBTableStoryBase.initStories();
                  IsoPlayer.visitAllPlayers(ECSEntity::onGameLoadingStateEnter);
                  GameLoadingState.playerCreated = true;
                  GameLoadingState.gameLoadingString = "";
                  GameLoadingState.SendDone();
               }
            }
         }
      });
      UIManager.suspend = true;
      loader.setName("GameLoadingThread");
      loader.setUncaughtExceptionHandler(GameWindow::uncaughtException);
      loader.start();
   }

   public static void SendDone() {
      DebugLog.log("game loading took " + (System.currentTimeMillis() - startTime + 999L) / 1000L + " seconds");
      if (!GameClient.client) {
         done = true;
         GameKeyboard.noEventsWhileLoading = false;
      } else {
         GameClient.instance.sendLoginQueueDone(System.currentTimeMillis() - startTime);
      }
   }

   public static void Done() {
      done = true;
      GameKeyboard.noEventsWhileLoading = false;
   }

   public GameState redirectState() {
      return new IngameState();
   }

   public void exit() {
      pzopt.JitGovernor.onWorldStart(); // pzopt: C2 off for play on few-core machines (jitMode)
      zombie.iso.fboRenderChunk.FBORenderCell.pzoptPrewarmRenderChunks(); // pzopt: render-chunk textures made on the loading screen (renderChunkPrewarm)
      if (pzopt.Overrides.enabled() && GameWindow.fileSystem instanceof zombie.fileSystem.FileSystemImpl pzoptFs) {
         pzoptFs.pzoptSetMaxInFlight(pzopt.Config.FILE_INFLIGHT); // pzopt: play order follows the file system's priorities again
      }
      boolean useUIFBO = UIManager.useUiFbo;
      UIManager.useUiFbo = false;
      if (!(pzopt.Config.NO_LOAD_FADE && pzopt.Overrides.enabled())) { // pzopt: skip the 350 ms fade to black before the world (docs/plan-instant-load.md L8)
         screenFader.startFadeToBlack();
      }

      // pzopt: noLoadingScreen, the loading screen's own fade from black (enter) is advanced by the stock render only; with
      // the black frame it never ran, and this loop would play all of it here (0.36 s of renders and 33 ms sleeps)
      while (screenFader.isFading() && !(pzopt.NoLoadingScreen.active() && pzopt.ResumeShot.hasShot())) {
         screenFader.preRender();
         screenFader.postRender();
         if (screenFader.isFading()) {
            try {
               Thread.sleep(33L);
            } catch (Exception var5) {
            }
         }
      }

      UIManager.useUiFbo = useUIFBO;
      ServerBrowser.setSuppressLuaCallbacks(false);
      if (GameClient.client) {
         NetworkAIParams.Init();
      }

      UIManager.init();
      LuaEventManager.triggerEvent("OnCreatePlayer", 0, IsoPlayer.players[0]);
      loader = null;
      done = false;
      this.stage = 0;
      IsoCamera.SetCharacterToFollow(IsoPlayer.getInstance());
      if (GameClient.client && !ServerOptions.instance.safehouseAllowTrepass.getValue()) {
         SafeHouse safe = SafeHouse.isSafeHouse(IsoPlayer.getInstance().getCurrentSquare(), GameClient.username, true);
         if (safe != null) {
            IsoPlayer.getInstance().setX(safe.getX() - 1.0F);
            IsoPlayer.getInstance().setY(safe.getY() - 1.0F);
         }
      }

      SoundManager.instance.stopMusic("");
      AmbientStreamManager.instance.init();
      if (IsoPlayer.getInstance() != null && IsoPlayer.getInstance().isAsleep()) {
         UIManager.setFadeBeforeUI(IsoPlayer.getInstance().getIndex(), true);
         UIManager.FadeOut(IsoPlayer.getInstance().getIndex(), 2.0);
         UIManager.setFadeTime(IsoPlayer.getInstance().getIndex(), 0.0);
         UIManager.getSpeedControls().SetCurrentGameSpeed(3);
      }

      if (!GameClient.client) {
         ActiveMods activeMods = ActiveMods.getById("currentGame");
         activeMods.checkMissingMods();
         activeMods.checkMissingMaps();
         ActiveMods.setLoadedMods(activeMods);
         String path = ZomboidFileSystem.instance.getFileNameInCurrentSave("mods.txt");
         ActiveModsFile activeModsFile = new ActiveModsFile();
         activeModsFile.write(path, activeMods);
      }

      DebugLog.log("Game Mode: " + Core.gameMode);
      DebugLog.log("Sandbox Options:");
      SandboxOptions options = GlobalObject.getSandboxOptions();

      for (int i = 0; i < options.getNumOptions(); i++) {
         SandboxOption option = options.getOptionByIndex(i);
         DebugLog.log(option.getShortName() + " " + option.asConfigOption().getValueAsString());
      }

      GameWindow.okToSaveOnExit = true;
   }

   public void render() {
      if (pzopt.NoLoadingScreen.active() && pzopt.ResumeShot.hasShot() && !unexpectedError && !GameWindow.serverDisconnected
         && !playerWrongIP && !worldVersionError && !convertingWorld) { // pzopt: 42.21 dropped mapDownloadFailed
         // pzopt: noLoadingScreen + resumeShot, the save's cached ground view with its tile effect on black instead of the
         // loading screen (text, tips, progress); a save without a cached view keeps the stock loading screen
         Core.getInstance().StartFrame();
         Core.getInstance().EndFrame();
         boolean useUIFBO = UIManager.useUiFbo;
         UIManager.useUiFbo = false;
         Core.getInstance().StartFrameUI();
         SpriteRenderer.instance.renderi(null, 0, 0, Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight(), 0.0F, 0.0F, 0.0F, 1.0F, null);
         pzopt.ResumeShot.draw(); // pzopt: resumeShot, the square of cached ground with random tiles fading to black and back
         Core.getInstance().EndFrameUI();
         UIManager.useUiFbo = useUIFBO;
         return;
      }
      float fontHeightSmall = TextManager.instance.getFontHeight(UIFont.NewSmall);
      float fontHeightMedium = TextManager.instance.getFontHeight(UIFont.NewMedium);
      this.loadingDotTick = this.loadingDotTick + GameTime.getInstance().getMultiplierInMenu();
      if (this.loadingDotTick > 20.0F) {
         this.loadingDot = ".";
      }

      if (this.loadingDotTick > 40.0F) {
         this.loadingDot = "..";
      }

      if (this.loadingDotTick > 60.0F) {
         this.loadingDot = "...";
      }

      if (this.loadingDotTick > 80.0F) {
         this.loadingDot = "";
         this.loadingDotTick = 0.0F;
      }

      this.time = this.time + GameTime.instance.getTimeDeltaInMenu();
      float alpha1 = 0.0F;
      float alpha2 = 0.0F;
      float alpha3 = 0.0F;
      if (this.stage == 0) {
         float pos = this.time;
         float textstart = 0.0F;
         float textfull = 1.0F;
         float textfullend = 5.0F;
         float textend = 7.0F;
         float del = 0.0F;
         if (pos > 0.0F && pos < 1.0F) {
            del = (pos - 0.0F) / 1.0F;
         }

         if (pos >= 1.0F && pos <= 5.0F) {
            del = 1.0F;
         }

         if (pos > 5.0F && pos < 7.0F) {
            del = 1.0F - (pos - 5.0F) / 2.0F;
         }

         if (pos >= 7.0F) {
            this.stage++;
         }

         alpha1 = del;
      }

      if (this.stage == 1) {
         float pos = this.time;
         float textstart = 7.0F;
         float textfull = 8.0F;
         float textfullend = 13.0F;
         float textend = 15.0F;
         float del = 0.0F;
         if (pos > 7.0F && pos < 8.0F) {
            del = (pos - 7.0F) / 1.0F;
         }

         if (pos >= 8.0F && pos <= 13.0F) {
            del = 1.0F;
         }

         if (pos > 13.0F && pos < 15.0F) {
            del = 1.0F - (pos - 13.0F) / 2.0F;
         }

         if (pos >= 15.0F) {
            this.stage++;
         }

         alpha2 = del;
      }

      if (this.stage == 2) {
         float pos = this.time;
         float textstart = 15.0F;
         float textfull = 16.0F;
         float textfullend = 31.0F;
         float textend = 33.0F;
         float del = 0.0F;
         if (pos > 15.0F && pos < 16.0F) {
            del = (pos - 15.0F) / 1.0F;
         }

         if (pos >= 16.0F && pos <= 31.0F) {
            del = 1.0F;
         }

         if (pos > 31.0F && pos < 33.0F) {
            del = 1.0F - (pos - 31.0F) / 2.0F;
         }

         if (pos >= 33.0F) {
            this.stage++;
         }

         alpha3 = del;
      }

      Core.getInstance().StartFrame();
      Core.getInstance().EndFrame();
      boolean useUIFBO = UIManager.useUiFbo;
      UIManager.useUiFbo = false;
      Core.getInstance().StartFrameUI();
      SpriteRenderer.instance.renderi(null, 0, 0, Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight(), 0.0F, 0.0F, 0.0F, 1.0F, null);
      if (this.stage == -1) {
         this.renderProgressIndicator();
         screenFader.update();
         screenFader.render();
      }

      if (unexpectedError) {
         int mediumHgt = TextManager.instance.getFontFromEnum(UIFont.Medium).getLineHeight();
         int smallHgt = TextManager.instance.getFontFromEnum(UIFont.Small).getLineHeight();
         int pad1 = 8;
         int pad2 = 2;
         int dy = mediumHgt + 8 + smallHgt + 2 + smallHgt;
         int cx = Core.getInstance().getScreenWidth() / 2;
         int cy = Core.getInstance().getScreenHeight() / 2;
         int top = cy - dy / 2;
         TextManager.instance.DrawStringCentre(UIFont.Medium, cx, top, Translator.getText("UI_GameLoad_UnexpectedError1", new Object[0]), 0.8, 0.1, 0.1, 1.0);
         TextManager.instance
            .DrawStringCentre(UIFont.Small, cx, top + mediumHgt + 8, Translator.getText("UI_GameLoad_UnexpectedError2", new Object[0]), 1.0, 1.0, 1.0, 1.0);
         String consoleDotTxt = ZomboidFileSystem.instance.getCacheDir() + File.separator + "console.txt";
         TextManager.instance.DrawStringCentre(UIFont.Small, cx, top + mediumHgt + 8 + smallHgt + 2, consoleDotTxt, 1.0, 1.0, 1.0, 1.0);
         UIManager.render();
         Core.getInstance().EndFrameUI();
      } else if (GameWindow.serverDisconnected) {
         int cx = Core.getInstance().getScreenWidth() / 2;
         int cy = Core.getInstance().getScreenHeight() / 2;
         int mediumHgt = TextManager.instance.getFontFromEnum(UIFont.Medium).getLineHeight();
         int pad = 2;
         int top = cy - (mediumHgt + 2 + mediumHgt) / 2;
         String reason = GameWindow.kickReason;
         if (reason == null) {
            reason = Translator.getText("UI_OnConnectFailed_ConnectionLost", new Object[0]);
         }

         TextManager.instance.DrawStringCentre(UIFont.Medium, cx, top, reason, 0.8, 0.1, 0.1, 1.0);
         UIManager.render();
         Core.getInstance().EndFrameUI();
      } else {
         if (worldVersionError) {
            if (WorldConverter.convertingVersion == 0) {
               TextManager.instance
                  .DrawStringCentre(
                     UIFont.Small,
                     Core.getInstance().getScreenWidth() / 2,
                     Core.getInstance().getScreenHeight() - 100,
                     Translator.getText("UI_CorruptedWorldVersion", new Object[0]),
                     0.8,
                     0.1,
                     0.1,
                     1.0
                  );
            } else if (WorldConverter.convertingVersion < 1) {
               TextManager.instance
                  .DrawStringCentre(
                     UIFont.Small,
                     Core.getInstance().getScreenWidth() / 2,
                     Core.getInstance().getScreenHeight() - 100,
                     Translator.getText("UI_ConvertWorldFailure", new Object[0]),
                     0.8,
                     0.1,
                     0.1,
                     1.0
                  );
            }
         } else if (convertingWorld) {
            TextManager.instance
               .DrawStringCentre(
                  UIFont.Small,
                  Core.getInstance().getScreenWidth() / 2,
                  Core.getInstance().getScreenHeight() - 100,
                  Translator.getText("UI_ConvertWorld", new Object[0]),
                  0.5,
                  0.5,
                  0.5,
                  1.0
               );
            if (convertingFileMax != -1) {
               TextManager.instance
                  .DrawStringCentre(
                     UIFont.Small,
                     Core.getInstance().getScreenWidth() / 2,
                     Core.getInstance().getScreenHeight() - 100 + TextManager.instance.getFontFromEnum(UIFont.Small).getLineHeight() + 8,
                     convertingFileCount + " / " + convertingFileMax,
                     0.5,
                     0.5,
                     0.5,
                     1.0
                  );
            }
         }

         if (playerWrongIP) {
            int cx = Core.getInstance().getScreenWidth() / 2;
            int cy = Core.getInstance().getScreenHeight() / 2;
            int mediumHgt = TextManager.instance.getFontFromEnum(UIFont.Medium).getLineHeight();
            int pad = 2;
            int top = cy - (mediumHgt + 2 + mediumHgt) / 2;
            String str = gameLoadingString;
            if (gameLoadingString == null) {
               str = "";
            }

            TextManager.instance.DrawStringCentre(UIFont.Medium, cx, top, str, 0.8, 0.1, 0.1, 1.0);
            UIManager.render();
            Core.getInstance().EndFrameUI();
         } else {
            if (GameClient.client) {
               String str = gameLoadingString;
               if (gameLoadingString == null) {
                  str = "";
               }

               TextManager.instance
                  .DrawStringCentre(
                     UIFont.Small,
                     Core.getInstance().getScreenWidth() / 2,
                     Core.getInstance().getScreenHeight() - 40 - fontHeightSmall - 5.0F,
                     str,
                     0.5,
                     0.5,
                     0.5,
                     1.0
                  );
               if (GameClient.connection.getConnectionType() == ConnectionType.Steam) {
                  SpriteRenderer.instance
                     .render(
                        null,
                        (Core.getInstance().getScreenWidth() - this.width) / 2.0F,
                        Core.getInstance().getScreenHeight() - 40 - fontHeightSmall * 2.0F - 5.0F,
                        this.width,
                        18.0F,
                        1.0F,
                        0.4F,
                        0.35F,
                        0.8F,
                        null
                     );
                  TextManager.instance
                     .DrawStringCentre(
                        UIFont.Medium,
                        Core.getInstance().getScreenWidth() / 2,
                        Core.getInstance().getScreenHeight() - 40 - fontHeightSmall * 2.0F - 5.0F,
                        this.text,
                        0.1,
                        0.1,
                        0.1,
                        1.0
                     );
               }
            } else if (!playerCreated && newGame && !Core.isLastStand()) {
               TextManager.instance
                  .DrawStringCentre(
                     UIFont.NewSmall,
                     Core.getInstance().getScreenWidth() / 2,
                     Core.getInstance().getScreenHeight() - 40 - fontHeightSmall - 5.0F,
                     Translator.getText("UI_Loading", new Object[0]).replace(".", ""),
                     0.5,
                     0.5,
                     0.5,
                     1.0
                  );
               TextManager.instance
                  .DrawString(
                     UIFont.NewSmall,
                     Core.getInstance().getScreenWidth() / 2
                        + TextManager.instance.MeasureStringX(UIFont.Small, Translator.getText("UI_Loading", new Object[0]).replace(".", "")) / 2
                        + 1,
                     Core.getInstance().getScreenHeight() - 40 - fontHeightSmall - 5.0F,
                     this.loadingDot,
                     0.5,
                     0.5,
                     0.5,
                     1.0
                  );
            }

            this.doQuickTips();
            if (this.stage == 0) {
               int x = Core.getInstance().getScreenWidth() / 2;
               int y = Core.getInstance().getScreenHeight() / 2 - TextManager.instance.getFontFromEnum(UIFont.Intro).getLineHeight() / 2;
               TextManager.instance.DrawStringCentre(UIFont.Intro, x, y, Translator.getText("UI_Intro1", new Object[0]), 1.0, 1.0, 1.0, alpha1);
            }

            if (this.stage == 1) {
               int x = Core.getInstance().getScreenWidth() / 2;
               int y = Core.getInstance().getScreenHeight() / 2 - TextManager.instance.getFontFromEnum(UIFont.Intro).getLineHeight() / 2;
               TextManager.instance.DrawStringCentre(UIFont.Intro, x, y, Translator.getText("UI_Intro2", new Object[0]), 1.0, 1.0, 1.0, alpha2);
            }

            if (this.stage == 2) {
               int x = Core.getInstance().getScreenWidth() / 2;
               int y = Core.getInstance().getScreenHeight() / 2 - TextManager.instance.getFontFromEnum(UIFont.Intro).getLineHeight() / 2;
               TextManager.instance.DrawStringCentre(UIFont.Intro, x, y, Translator.getText("UI_Intro3", new Object[0]), 1.0, 1.0, 1.0, alpha3);
            }

            if (Core.getInstance().getDebug()) {
               showedClickToSkip = true;
            }

            if (done && playerCreated && (!newGame || this.time >= 33.0F || Core.isLastStand() || "Tutorial".equals(Core.gameMode) || (pzopt.Config.NO_INTRO_WAIT && pzopt.Overrides.enabled()))) { // pzopt: new game: click-to-start as soon as the world is loaded, not after the 33 s intro
               if (this.clickToSkipFadeIn) {
                  this.clickToSkipAlpha = this.clickToSkipAlpha + GameTime.getInstance().getThirtyFPSMultiplierInMenu() / 30.0F;
                  if (this.clickToSkipAlpha > 1.0F) {
                     this.clickToSkipAlpha = 1.0F;
                     this.clickToSkipFadeIn = false;
                  }
               } else {
                  showedClickToSkip = true;
                  this.clickToSkipAlpha = this.clickToSkipAlpha - GameTime.getInstance().getThirtyFPSMultiplierInMenu() / 30.0F;
                  if (this.clickToSkipAlpha < 0.25F) {
                     this.clickToSkipFadeIn = true;
                  }
               }

               int baseline = Core.getInstance().getScreenHeight();
               if (GameWindow.activatedJoyPad != null && !JoypadManager.instance.joypadList.isEmpty()) {
                  String textureType;
                  if (Core.getInstance().getOptionControllerButtonStyle() == 1) {
                     textureType = "XBOX";
                  } else {
                     textureType = "PS4";
                  }

                  Texture tex = Texture.getSharedTexture("media/ui/controller/" + textureType + "_A.png");
                  if (tex != null) {
                     int fontHgt = TextManager.instance.getFontFromEnum(UIFont.Small).getLineHeight();
                     SpriteRenderer.instance
                        .renderi(
                           tex,
                           Core.getInstance().getScreenWidth() / 2
                              - TextManager.instance.MeasureStringX(UIFont.Small, Translator.getText("UI_PressAToStart", new Object[0])) / 2
                              - 8
                              - tex.getWidth(),
                           baseline - 60 + fontHgt / 2 - tex.getHeight() / 2,
                           tex.getWidth(),
                           tex.getHeight(),
                           1.0F,
                           1.0F,
                           1.0F,
                           this.clickToSkipAlpha,
                           null
                        );
                  }

                  TextManager.instance
                     .DrawStringCentre(
                        UIFont.Small,
                        Core.getInstance().getScreenWidth() / 2,
                        baseline - 40 - fontHeightSmall - 5.0F,
                        Translator.getText("UI_PressAToStart", new Object[0]),
                        1.0,
                        1.0,
                        1.0,
                        this.clickToSkipAlpha
                     );
               } else {
                  TextManager.instance
                     .DrawStringCentre(
                        UIFont.NewLarge,
                        Core.getInstance().getScreenWidth() / 2,
                        baseline - 40 - fontHeightSmall - 5.0F,
                        Translator.getText("UI_ClickToSkip", new Object[0]),
                        1.0,
                        1.0,
                        1.0,
                        this.clickToSkipAlpha
                     );
               }
            }

            ActiveMods.renderUI();
            Core.getInstance().EndFrameUI();
            UIManager.useUiFbo = useUIFBO;
         }
      }
   }

   private void doQuickTips() {
      if (!newGame) {
         if (this.quickTipsTimer > 720.0F) {
            this.quickTipsText = this.getNewQuickTip();
            this.quickTipsTimer = 0.0F;
         }

         this.quickTipsTimer = this.quickTipsTimer + GameTime.getInstance().getMultiplierInMenu();
         if (!StringUtils.isNullOrEmpty(this.quickTipsText)) {
            TextManager.instance
               .DrawStringCentre(
                  UIFont.NewMedium,
                  Core.getInstance().getScreenWidth() / 2.0,
                  Core.getInstance().getScreenHeight() - 40,
                  this.quickTipsText,
                  0.5,
                  0.5,
                  0.5,
                  1.0
               );
         }
      }
   }

   private String getNewQuickTip() {
      if (!this.quickTipsList.isEmpty() && !this.quickTipsListJoke.isEmpty()) {
         String quickTip = this.quickTipsList.get(Rand.Next(this.quickTipsList.size()));
         if (Rand.NextBool(13)) {
            quickTip = this.quickTipsListJoke.get(Rand.Next(this.quickTipsListJoke.size()));
         }

         return Translator.getText(quickTip, new Object[0]);
      } else {
         return null;
      }
   }

   private void loadQuickTipList() {
      this.quickTipsList = new ArrayList<>();
      this.quickTipsListJoke = new ArrayList<>();

      for (String key : Translator.getUI().keySet()) {
         if (key.startsWith("UI_quick_tip_joke")) {
            this.quickTipsListJoke.add(key);
         } else if (key.startsWith("UI_quick_tip")) {
            this.quickTipsList.add(key);
         }
      }
   }

   private void renderProgressIndicator() {
      if (!unexpectedError) {
         if (convertingWorld) {
            this.animatedTexture = AnimatedTextures.getTexture("media/ui/Progress/MaleDoor.png");
         } else if (SandboxOptions.instance.lore.speed.getValue() == 1) {
            this.animatedTexture = AnimatedTextures.getTexture("media/ui/Progress/MaleSprint06.png");
         } else {
            this.animatedTexture = AnimatedTextures.getTexture("media/ui/Progress/MaleWalk2.png");
         }

         if (this.animatedTexture.isReady()) {
            int width = 196;
            float scale = 196.0F / this.animatedTexture.getWidth();
            int height = (int)(this.animatedTexture.getHeight() * scale);
            float alpha = 0.66F;
            if (done && showedClickToSkip) {
               if (this.progressFadeStartMs == 0L) {
                  this.progressFadeStartMs = System.currentTimeMillis();
               }

               long fadeTime = 200L;
               long dt = PZMath.clamp(System.currentTimeMillis() - this.progressFadeStartMs, 0L, 200L);
               alpha *= 1.0F - (float)dt / 200.0F;
               if (alpha == 0.0F) {
                  return;
               }
            }

            int textY = Core.getInstance().getScreenHeight() - (convertingWorld ? 100 : 0);
            this.animatedTexture
               .render(
                  Core.getInstance().getScreenWidth() / 2 - 98,
                  PZMath.min(Core.getInstance().getScreenHeight(), textY) - height - 30 + (convertingWorld ? 32 : 0),
                  196,
                  height,
                  1.0F,
                  1.0F,
                  1.0F,
                  alpha
               );
         }
      }
   }

   public StateAction update() {
      for (java.util.concurrent.FutureTask<Void> pzoptTask; (pzoptTask = this.pzoptMainTasks.poll()) != null; ) {
         pzoptTask.run(); // pzopt: loader steps that touch main-thread state (pzoptOnMain)
      }
      if (this.waitForAssetLoadingToFinish1 && !OutfitManager.instance.isLoadingClothingItems()) {
         if (Core.debug) {
            OutfitManager.instance.debugOutfits();
         }

         synchronized (this.assetLock1) {
            this.waitForAssetLoadingToFinish1 = false;
            this.assetLock1.notifyAll();
         }
      }

      if (this.waitForAssetLoadingToFinish2 && pzopt.Config.INSTRUMENT && System.currentTimeMillis() - this.pzoptWaitLogMs >= 250L) {
         // pzopt: load-trace diagnostic, what the assetLock2 wait is waiting on (instrumented runs only)
         this.pzoptWaitLogMs = System.currentTimeMillis();
         String files = GameWindow.fileSystem instanceof zombie.fileSystem.FileSystemImpl impl ? impl.pzoptWorkSummary() : " ?";
         pzopt.Log.info("assetLock2 wait: animations=" + ModelManager.instance.isLoadingAnimations() + " files (queued+running):" + files);
      }

      if (this.waitForAssetLoadingToFinish2 && !ModelManager.instance.isLoadingAnimations() && !GameWindow.fileSystem.hasWork()) {
         synchronized (this.assetLock2) {
            this.waitForAssetLoadingToFinish2 = false;
            this.assetLock2.notifyAll();

            for (RuntimeAnimationScript runtimeAnimationScript : ScriptManager.instance.getAllRuntimeAnimationScripts()) {
               runtimeAnimationScript.exec();
            }
         }
      }

      if (!unexpectedError && !GameWindow.serverDisconnected && !playerWrongIP) {
         if (!done) {
            return StateAction.Remain;
         }

         if (WorldStreamer.instance.isBusy() && !pzopt.CenterFirstLoad.enteredEarly()) { // pzopt: centerFirstLoad, the rest streams in during play
            return StateAction.Remain;
         }

         if (ModelManager.instance.isLoadingAnimations()) {
            return StateAction.Remain;
         }

         if (pzopt.Config.NO_CLICK_TO_START && pzopt.Overrides.enabled() && playerCreated
            && (!newGame || this.time >= 33.0F || Core.isLastStand() || "Tutorial".equals(Core.gameMode) || pzopt.Config.NO_INTRO_WAIT)) {
            showedClickToSkip = true; // pzopt: noClickToStart, enter the world the moment it is loaded (same point a click would)
            this.forceDone = false;
            return StateAction.Continue;
         }

         if (!showedClickToSkip) {
            return StateAction.Remain;
         }

         if (Mouse.isButtonDown(0)) {
            this.forceDone = true;
         }

         if (GameWindow.activatedJoyPad != null && GameWindow.activatedJoyPad.isAPressed()) {
            this.forceDone = true;
         }

         if (this.forceDone) {
            SoundManager.instance.playUISound("UIClickToStart");
            this.forceDone = false;
            return StateAction.Continue;
         } else {
            return StateAction.Remain;
         }
      } else {
         if (!showedUI) {
            showedUI = true;
            IsoPlayer.setInstance(null);
            IsoPlayer.players[0] = null;
            UIManager.UI.clear();
            LuaManager.thread.debugOwnerThread = GameWindow.gameThread;
            LuaManager.debugthread.debugOwnerThread = GameWindow.gameThread;
            LuaEventManager.Reset();
            LuaManager.call("ISGameLoadingUI_OnGameLoadingUI", "");
            UIManager.suspend = false;
         }

         if (GameKeyboard.isKeyDownRaw(1)) {
            GameClient.instance.Shutdown();
            SteamUtils.shutdown();
            System.exit(1);
         }

         return StateAction.Remain;
      }
   }
}
