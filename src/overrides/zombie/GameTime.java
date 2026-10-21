/*
 * Decompiled with CFR 0.152.
 */
package zombie;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.concurrent.TimeUnit;
import se.krka.kahlua.vm.KahluaTable;
import zombie.AmbientStreamManager;
import zombie.GameWindow;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.ai.sadisticAIDirector.SleepingEvent;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SceneShaderStore;
import zombie.core.Translator;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.opengl.RenderSettings;
import zombie.core.random.Rand;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.erosion.ErosionMain;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.SaveBufferMap;
import zombie.iso.SliceY;
import zombie.iso.weather.ClimateManager;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.ServerOptions;
import zombie.network.packets.INetworkPacket;
import zombie.network.statistics.data.ConnectionQueueStatistic;
import zombie.popman.ZombiePopulationManager;
import zombie.radio.ZomboidRadio;
import zombie.ui.SpeedControls;
import zombie.ui.UIManager;
import zombie.util.ByteBufferOutputStream;
import zombie.util.ByteBufferPooledObject;
import zombie.util.PZCalendar;
import zombie.util.StringUtils;

@UsedFromLua
public final class GameTime {
    // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
    static {
        pzopt.Overrides.onClassLoadedQuiet("zombie.GameTime");
    }

    public static final int NANOSECONDS_PER_SECOND = 1000000000;
    public static final int MILLISECONDS_PER_SECOND = 1000;
    public static final float MinutesPerHour = 60.0f;
    public static final float SecondsPerHour = 3600.0f;
    public static final int SECONDS_PER_MINUTE = 60;
    public static final float MULTIPLIER = 0.8f;
    public static final float START_OF_NEW_DAY = 7.0f;
    public static final float THIRTY_FPS_SCALE = 1.6f;
    public static final long SYNC_CLOCK_MS = 10000L;
    public static GameTime instance = new GameTime();
    private static long serverTimeShift;
    private static boolean serverTimeShiftIsSet;
    private static boolean isUTest;
    private final float minutesPerDayStart = 30.0f;
    private final boolean rainingToday = true;
    private final float[] gunFireTimes = new float[5];
    public float timeOfDay = 9.0f;
    public int nightsSurvived;
    public PZCalendar calender;
    public float fpsMultiplier = 1.0f;
    public float moon;
    public float serverTimeOfDay;
    public float serverLastTimeOfDay;
    public int serverNewDays;
    public float lightSourceUpdate;
    public float multiplierBias = 1.0f;
    public float lastLastTimeOfDay;
    public float perObjectMultiplier = 1.0f;
    private int helicopterTime1Start;
    private int helicopterTime1End;
    private int helicopterDay1;
    private float ambient = 0.9f;
    private float ambientMax = 1.0f;
    private float ambientMin = 0.24f;
    private int day = 22;
    private int startDay = 22;
    private float maxZombieCountStart = 750.0f;
    private float minZombieCountStart = 750.0f;
    private float maxZombieCount = 750.0f;
    private float minZombieCount = 750.0f;
    private int month = 7;
    private int startMonth = 7;
    private float startTimeOfDay = 9.0f;
    private float viewDistMax = 42.0f;
    private float viewDistMin = 19.0f;
    private int year = 2012;
    private int startYear = 2012;
    private double hoursSurvived;
    private float minutesPerDay = 30.0f;
    private float lastTimeOfDay;
    private int targetZombies = (int)this.minZombieCountStart;
    private boolean gunFireEventToday;
    private int numGunFireEvents = 1;
    private long lastClockSync;
    private KahluaTable table;
    private int minutesMod = -1;
    private boolean thunderDay = true;
    private boolean randomAmbientToday = true;
    private float multiplier = 1.0f;
    private int dusk = 3;
    private int dawn = 12;
    private float nightMin;
    private float nightMax = 1.0f;
    private long minutesStamp;
    private long previousMinuteStamp;
    int lastSkyLight = -100;

    public GameTime() {
        serverTimeShift = 0L;
        serverTimeShiftIsSet = false;
    }

    public static GameTime getInstance() {
        return instance;
    }

    public static void setInstance(GameTime aInstance) {
        instance = aInstance;
    }

    public static void syncServerTime(long timeClientSend, long timeServer, long timeClientReceive) {
        long localPing = timeClientReceive - timeClientSend;
        long localServerTimeShift = timeServer - timeClientReceive + localPing / 2L;
        long serverTimeShiftLast = serverTimeShift;
        serverTimeShift = !serverTimeShiftIsSet ? localServerTimeShift : (serverTimeShift += (localServerTimeShift - serverTimeShift) / 100L);
        long serverTimeuQality = 10000000L;
        if (Math.abs(serverTimeShift - serverTimeShiftLast) > 10000000L) {
            INetworkPacket.send(PacketTypes.PacketType.TimeSync, new Object[0]);
        } else {
            serverTimeShiftIsSet = true;
        }
    }

    public static long getServerTime() {
        if (isUTest) {
            return System.nanoTime() + serverTimeShift;
        }
        if (GameServer.server) {
            return System.nanoTime();
        }
        if (GameClient.client) {
            if (!serverTimeShiftIsSet) {
                return 0L;
            }
            return System.nanoTime() + serverTimeShift;
        }
        return 0L;
    }

    public static long getServerTimeMills() {
        return TimeUnit.NANOSECONDS.toMillis(GameTime.getServerTime());
    }

    public static boolean getServerTimeShiftIsSet() {
        return serverTimeShiftIsSet;
    }

    public static void setServerTimeShift(long tshift) {
        isUTest = true;
        serverTimeShift = tshift;
        serverTimeShiftIsSet = true;
    }

    public static boolean isGamePaused() {
        if (GameServer.server) {
            return GameServer.Players.isEmpty() && ServerOptions.instance.pauseEmpty.getValue();
        }
        if (GameClient.client) {
            return GameClient.IsClientPaused();
        }
        SpeedControls speedControls = UIManager.getSpeedControls();
        return speedControls != null && speedControls.getCurrentGameSpeed() == 0;
    }

    public float getRealworldSecondsSinceLastUpdate() {
        return 0.016666668f * pzopt.ZombieLate.fps(this.fpsMultiplier); // pzopt: zombieLateApply (f8-zomb3), the frame's fpsMultiplier on a frame worker while the apply is held
    }

    public float getMultipliedSecondsSinceLastUpdate() {
        return 0.016666668f * this.getUnmoddedMultiplier();
    }

    public float getPhysicsSecondsSinceLastUpdate() {
        return this.getRealworldSecondsSinceLastUpdate() * GameTime.getSlomoMultiplier();
    }

    public static float getSlomoMultiplier() {
        return DebugOptions.instance.getSlowMotionMultiplier().getMultiplier();
    }

    public float getGameWorldSecondsSinceLastUpdate() {
        float dif = 1440.0f / this.getMinutesPerDay();
        return this.getTimeDelta() * dif;
    }

    public int daysInMonth(int year, int month) {
        if (this.calender == null) {
            this.updateCalendar(this.getYear(), this.getMonth(), this.getDay(), (int)this.getTimeOfDay(), this.getMinutes());
        }
        int[] daysInMonths = new int[]{31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        daysInMonths[1] += this.getCalender().isLeapYear(year) ? 1 : 0; // pzopt: decompiler fix -- the jar compiles a compound assignment (dup2 index reuse); CFR expanded it to a[1] = a[1] + x, one extra index constant
        return daysInMonths[month];
    }

    public String getDeathString(IsoPlayer playerObj) {
        return Translator.getText("IGUI_Gametime_SurvivedFor", this.getTimeSurvived(playerObj));
    }

    public int getDaysSurvived() {
        float hours = 0.0f;
        for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; ++playerIndex) {
            IsoPlayer player = IsoPlayer.players[playerIndex];
            if (player == null) continue;
            hours = Math.max(hours, (float)player.getHoursSurvived());
        }
        int days = (int)hours / 24;
        return days %= 30;
    }

    public String getTimeSurvived(IsoPlayer playerObj) {
        Object total = "";
        float hours = (float)playerObj.getHoursSurvived();
        int hoursLeft = (int)hours % 24;
        int days = (int)hours / 24;
        int months = days / 30;
        days %= 30;
        int years = months / 12;
        months %= 12;
        String dayString = Translator.getText("IGUI_Gametime_day", new Object[0]);
        String yearString = Translator.getText("IGUI_Gametime_year", new Object[0]);
        String hourString = Translator.getText("IGUI_Gametime_hour", new Object[0]);
        String monthString = Translator.getText("IGUI_Gametime_month", new Object[0]);
        if (years != 0) {
            if (years > 1) {
                yearString = Translator.getText("IGUI_Gametime_years", new Object[0]);
            }
            total = (String)total + years + " " + yearString;
        }
        if (months != 0) {
            if (months > 1) {
                monthString = Translator.getText("IGUI_Gametime_months", new Object[0]);
            }
            if (!((String)total).isEmpty()) {
                total = (String)total + ", ";
            }
            total = (String)total + months + " " + monthString;
        }
        if (days != 0) {
            if (days > 1) {
                dayString = Translator.getText("IGUI_Gametime_days", new Object[0]);
            }
            if (!((String)total).isEmpty()) {
                total = (String)total + ", ";
            }
            total = (String)total + days + " " + dayString;
        }
        if (hoursLeft != 0) {
            if (hoursLeft > 1) {
                hourString = Translator.getText("IGUI_Gametime_hours", new Object[0]);
            }
            if (!((String)total).isEmpty()) {
                total = (String)total + ", ";
            }
            total = (String)total + hoursLeft + " " + hourString;
        }
        if (((String)total).trim().isEmpty()) {
            int minutes = (int)(hours * 60.0f);
            int seconds = (int)(hours * 60.0f * 60.0f) - minutes * 60;
            total = minutes + " " + Translator.getText("IGUI_Gametime_minutes", new Object[0]) + ", " + seconds + " " + Translator.getText("IGUI_Gametime_secondes", new Object[0]);
        }
        return (String)total; // pzopt: decompiler fix, CFR typed the built time string as Object
    }

    public String getZombieKilledText(IsoPlayer playerObj) {
        int kills = playerObj.getZombieKills();
        if (kills == 0 || kills > 1) {
            return Translator.getText("IGUI_Gametime_zombiesCount", kills);
        }
        if (kills == 1) {
            return Translator.getText("IGUI_Gametime_zombieCount", kills);
        }
        return null;
    }

    public String getGameModeText() {
        Object s;
        String mode;
        String string = mode = StringUtils.isNullOrEmpty(Core.challengeId) ? Translator.getTextOrNull("UI_NewGame_" + Core.gameMode, new Object[0]) : Translator.getTextOrNull("Challenge_%s_name".formatted(Core.challengeId), new Object[0]);
        if (mode == null) {
            mode = Core.gameMode;
        }
        if ((s = Translator.getTextOrNull("IGUI_Gametime_GameMode", mode)) == null) {
            s = "Game mode: " + mode;
        }
        if (Core.debug) {
            s = (String)s + " (DEBUG)";
        }
        return (String)s; // pzopt: decompiler fix, CFR typed the mode string as Object
    }

    public void init() {
        this.setDay(this.getStartDay());
        this.setTimeOfDay(this.getStartTimeOfDay());
        this.setMonth(this.getStartMonth());
        this.setYear(this.getStartYear());
        if (SandboxOptions.instance.helicopter.getValue() != 1) {
            this.helicopterDay1 = Rand.Next(6, 10);
            this.helicopterTime1Start = Rand.Next(9, 19);
            this.helicopterTime1End = this.helicopterTime1Start + Rand.Next(4) + 1;
        }
        this.setMinutesStamp();
    }

    public float Lerp(float start, float end, float delta) {
        if (delta < 0.0f) {
            delta = 0.0f;
        }
        if (delta >= 1.0f) {
            delta = 1.0f;
        }
        float amount = end - start;
        float result = amount * delta;
        return start + result;
    }

    public void RemoveZombiesIndiscriminate(int i) {
        if (i == 0) {
            return;
        }
        for (int n = 0; n < IsoWorld.instance.currentCell.getZombieList().size(); ++n) {
            IsoZombie zombie = IsoWorld.instance.currentCell.getZombieList().get(0);
            ZombiePopulationManager.releaseZombie(zombie);
            zombie.persistentId = 0;
            IsoWorld.instance.currentCell.getZombieList().remove(n);
            IsoWorld.instance.currentCell.getRemoveList().add(zombie);
            zombie.getCurrentSquare().getMovingObjects().remove(zombie);
            --n;
            if (--i != 0 && !IsoWorld.instance.currentCell.getZombieList().isEmpty()) continue;
            return;
        }
    }

    public float TimeLerp(float startVal, float endVal, float startTime, float endTime) {
        float timeOfDay = GameTime.getInstance().getTimeOfDay();
        if (endTime < startTime) {
            endTime += 24.0f;
        }
        boolean bReverse = false;
        if (timeOfDay > endTime && timeOfDay > startTime || timeOfDay < endTime && timeOfDay < startTime) {
            bReverse = true;
            float temp = startTime += 24.0f;
            startTime = endTime;
            endTime = temp;
            if (timeOfDay < startTime) {
                timeOfDay += 24.0f;
            }
        }
        float dist = endTime - startTime;
        float current = timeOfDay - startTime;
        float delta = 0.0f;
        if (current > dist) {
            delta = 1.0f;
        }
        if (current < dist && current > 0.0f) {
            delta = current / dist;
        }
        if (bReverse) {
            delta = 1.0f - delta;
        }
        float signval = (double)(delta = (delta - 0.5f) * 2.0f) < 0.0 ? -1.0f : 1.0f;
        delta = Math.abs(delta);
        delta = 1.0f - delta;
        delta = (float)Math.pow(delta, 8.0);
        delta = 1.0f - delta;
        delta *= signval;
        delta = delta * 0.5f + 0.5f;
        return this.Lerp(startVal, endVal, delta);
    }

    public float getDeltaMinutesPerDay() {
        return 30.0f / this.minutesPerDay;
    }

    public float getNightMin() {
        return 1.0f - this.nightMin;
    }

    public void setNightMin(float min) {
        this.nightMin = 1.0f - min;
    }

    public float getNightMax() {
        return 1.0f - this.nightMax;
    }

    public void setNightMax(float max) {
        this.nightMax = 1.0f - max;
    }

    public int getMinutes() {
        return (int)((this.getTimeOfDay() - (float)((int)this.getTimeOfDay())) * 60.0f);
    }

    public void setMoon(float moon) {
        this.moon = moon;
    }

    public void update(boolean bSleeping) {
        int now;
        boolean serverSyncClock;
        long ms = System.currentTimeMillis();
        int metaSandbox = 9000;
        if (SandboxOptions.instance.metaEvent.getValue() == 1) {
            metaSandbox = -1;
        }
        if (SandboxOptions.instance.metaEvent.getValue() == 3) {
            metaSandbox = 6000;
        }
        if (!GameClient.client && this.randomAmbientToday && metaSandbox != -1 && Rand.Next(Rand.AdjustForFramerate(metaSandbox)) == 0 && !GameTime.isGamePaused()) {
            AmbientStreamManager.instance.addRandomAmbient();
            boolean bl = this.randomAmbientToday = SandboxOptions.instance.metaEvent.getValue() == 3 && Rand.Next(3) == 0;
        }
        if (GameServer.server && UIManager.getSpeedControls() != null) {
            UIManager.getSpeedControls().SetCurrentGameSpeed(1);
        }
        if (GameServer.server || !GameClient.client) {
            if (this.gunFireEventToday) {
                for (int n = 0; n < this.numGunFireEvents; ++n) {
                    if (!(this.timeOfDay > this.gunFireTimes[n]) || !(this.lastLastTimeOfDay < this.gunFireTimes[n])) continue;
                    AmbientStreamManager.instance.doGunEvent();
                }
            }
            if (this.nightsSurvived == this.helicopterDay1 && this.timeOfDay > (float)this.helicopterTime1Start && this.timeOfDay < (float)this.helicopterTime1End && !IsoWorld.instance.helicopter.isActive() && Rand.Next((int)(800.0f * this.getInvMultiplier())) == 0) {
                this.helicopterTime1Start = (int)((float)this.helicopterTime1Start + 0.5f);
                IsoWorld.instance.helicopter.pickRandomTarget();
            }
            if (this.nightsSurvived > this.helicopterDay1 && (SandboxOptions.instance.helicopter.getValue() == 3 || SandboxOptions.instance.helicopter.getValue() == 4)) {
                if (SandboxOptions.instance.helicopter.getValue() == 3) {
                    this.helicopterDay1 = this.nightsSurvived + Rand.Next(10, 16);
                }
                if (SandboxOptions.instance.helicopter.getValue() == 4) {
                    this.helicopterDay1 = this.nightsSurvived + Rand.Next(6, 10);
                }
                this.helicopterTime1Start = Rand.Next(9, 19);
                this.helicopterTime1End = this.helicopterTime1Start + Rand.Next(4) + 1;
            }
        }
        int previousHour = this.getHour();
        this.updateCalendar(this.getYear(), this.getMonth(), this.getDay(), (int)this.getTimeOfDay(), (int)((this.getTimeOfDay() - (float)((int)this.getTimeOfDay())) * 60.0f));
        float lastTimeOfDay = this.getTimeOfDay();
        if (!GameTime.isGamePaused()) {
            IsoPlayer player;
            float time = 1.0f / this.getMinutesPerDay() / 60.0f * this.getMultiplier() / 2.0f;
            if (Core.lastStand) {
                time = 1.0f / this.getMinutesPerDay() / 60.0f * this.getUnmoddedMultiplier() / 2.0f;
            }
            if (DebugOptions.instance.freezeTimeOfDay.getValue()) {
                time = 0.0f;
            }
            this.setTimeOfDay(this.getTimeOfDay() + time);
            if (!GameServer.server) {
                for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; ++playerIndex) {
                    IsoPlayer player2 = IsoPlayer.players[playerIndex];
                    if (player2 == null || !player2.isAlive()) continue;
                    player2.setHoursSurvived(player2.getHoursSurvived() + (double)time);
                }
            }
            if (GameServer.server) {
                ArrayList<IsoPlayer> players = GameServer.getPlayers();
                for (int i1 = 0; i1 < players.size(); ++i1) {
                    player = players.get(i1);
                    player.setHoursSurvived(player.getHoursSurvived() + (double)time);
                }
            }
            if (GameClient.client) {
                ArrayList<IsoPlayer> players = GameClient.instance.getPlayers();
                for (int i1 = 0; i1 < players.size(); ++i1) {
                    player = players.get(i1);
                    if (player == null || player.isDead() || player.isLocalPlayer()) continue;
                    player.setHoursSurvived(player.getHoursSurvived() + (double)time);
                }
            }
            for (int pn = 0; pn < IsoPlayer.numPlayers; ++pn) {
                IsoPlayer player3 = IsoPlayer.players[pn];
                if (player3 == null) continue;
                if (player3.isAsleep()) {
                    player3.setAsleepTime(player3.getAsleepTime() + time);
                    SleepingEvent.instance.update(player3);
                    continue;
                }
                player3.setAsleepTime(0.0f);
            }
        }
        boolean bl = serverSyncClock = GameServer.server && (ms - this.lastClockSync >= 10000L || GameServer.fastForward);
        if (lastTimeOfDay <= 7.0f && this.getTimeOfDay() > 7.0f) {
            this.setNightsSurvived(this.getNightsSurvived() + 1);
            if (GameServer.server) {
                serverSyncClock = true;
            }
            if (!GameClient.client) {
                this.doMetaEvents();
            }
        }
        if (GameClient.client) {
            if (this.getTimeOfDay() >= 24.0f) {
                this.setTimeOfDay(this.getTimeOfDay() - 24.0f);
            }
            while (this.serverNewDays > 0) {
                --this.serverNewDays;
                this.advanceOneDay();
            }
        } else if (this.getTimeOfDay() >= 24.0f) {
            this.setTimeOfDay(this.getTimeOfDay() - 24.0f);
            this.advanceOneDay();
            if (GameServer.server) {
                serverSyncClock = true;
            }
        }
        if (!ClimateManager.getInstance().getThunderStorm().isModifyingNight()) {
            this.setAmbient(this.TimeLerp(this.getAmbientMin(), this.getAmbientMax(), this.getDusk(), this.getDawn()));
        }
        if (SceneShaderStore.weatherShader != null && Core.getInstance().getOffscreenBuffer() != null) {
            this.setNightTint(0.0f);
        }
        this.setMinutesStamp();
        if (this.getHour() != previousHour) {
            LuaEventManager.triggerEvent("EveryHours");
        }
        if (GameServer.server && this.getHour() < previousHour) {
            ConnectionQueueStatistic.getInstance().zombiesKilledByFireToday.clear();
            ConnectionQueueStatistic.getInstance().zombiesKilledToday.clear();
            ConnectionQueueStatistic.getInstance().zombifiedPlayersToday.clear();
            ConnectionQueueStatistic.getInstance().playersKilledByFireToday.clear();
            ConnectionQueueStatistic.getInstance().playersKilledByZombieToday.clear();
            ConnectionQueueStatistic.getInstance().playersKilledByPlayerToday.clear();
            ConnectionQueueStatistic.getInstance().burnedCorpsesToday.clear();
        }
        if ((now = (int)((this.getTimeOfDay() - (float)((int)this.getTimeOfDay())) * 60.0f)) / 10 != this.minutesMod) {
            IsoPlayer[] players = IsoPlayer.players;
            for (int i = 0; i < players.length; ++i) {
                IsoPlayer player = players[i];
                if (player == null) continue;
                player.dirtyRecalcGridStackTime = 1.0f;
            }
            ErosionMain.EveryTenMinutes();
            ClimateManager.getInstance().updateEveryTenMins();
            LuaEventManager.triggerEvent("EveryTenMinutes");
            this.minutesMod = now / 10;
            ZomboidRadio.getInstance().UpdateScripts(this.getHour(), now);
        }
        if (this.previousMinuteStamp != this.minutesStamp) {
            LuaEventManager.triggerEvent("EveryOneMinute");
            this.previousMinuteStamp = this.minutesStamp;
        }
        if (serverSyncClock) {
            GameServer.syncClock();
            this.lastClockSync = ms;
        }
    }

    private void advanceOneDay() {
        this.setDay(this.getDay() + 1);
        if (this.getDay() >= this.daysInMonth(this.getYear(), this.getMonth())) {
            this.setDay(0);
            this.setMonth(this.getMonth() + 1);
            if (this.getMonth() >= 12) {
                this.setMonth(0);
                this.setYear(this.getYear() + 1);
            }
        }
        this.updateCalendar(this.getYear(), this.getMonth(), this.getDay(), (int)this.getTimeOfDay(), this.getMinutes());
        LuaEventManager.triggerEvent("EveryDays");
    }

    private void setMinutesStamp() {
        this.minutesStamp = (long)this.getWorldAgeHours() * 60L + (long)this.getMinutes();
    }

    public long getMinutesStamp() {
        return this.minutesStamp;
    }

    public boolean getThunderStorm() {
        return ClimateManager.getInstance().getIsThunderStorming();
    }

    private void doMetaEvents() {
        int metaSandbox = 3;
        if (SandboxOptions.instance.metaEvent.getValue() == 1) {
            metaSandbox = -1;
        }
        if (SandboxOptions.instance.metaEvent.getValue() == 3) {
            metaSandbox = 2;
        }
        boolean bl = this.gunFireEventToday = metaSandbox != -1 && Rand.Next(metaSandbox) == 0;
        if (this.gunFireEventToday) {
            this.numGunFireEvents = 1;
            for (int n = 0; n < this.numGunFireEvents; ++n) {
                this.gunFireTimes[n] = (float)Rand.Next(18000) / 1000.0f + 7.0f;
            }
        }
        this.randomAmbientToday = true;
    }

    @Deprecated
    public float getAmbient() {
        return ClimateManager.getInstance().getAmbient();
    }

    public int getSkyLightLevel() {
        RenderSettings.PlayerRenderSettings aa = RenderSettings.getInstance().getPlayerSettings(IsoPlayer.getPlayerIndex());
        float b = aa.getBmod();
        float g = aa.getGmod();
        float r = aa.getRmod();
        b *= 2.0f;
        g *= 2.0f;
        r *= 2.0f;
        r = PZMath.clamp(r * aa.getAmbient(), 0.0f, 1.0f);
        g = PZMath.clamp(g * aa.getAmbient(), 0.0f, 1.0f);
        b = PZMath.clamp(b * aa.getAmbient(), 0.0f, 1.0f);
        int a = (int)(Math.min(1.0f, b) * 255.0f) | (int)(Math.min(1.0f, g) * 255.0f) << 8 | (int)(Math.min(1.0f, r) * 255.0f) << 16;
        if (DebugOptions.instance.fboRenderChunk.forceSkyLightLevel.getValue()) {
            a = 15000000;
        }
        if (a != this.lastSkyLight) {
            LightingJNI.doInvalidateGlobalLights(IsoPlayer.getPlayerIndex());
            this.lastSkyLight = a;
        }
        return a;
    }

    public void setAmbient(float ambient) {
        this.ambient = ambient;
    }

    public float getAmbientMax() {
        return this.ambientMax;
    }

    public void setAmbientMax(float ambientMax) {
        ambientMax = Math.min(1.0f, ambientMax);
        this.ambientMax = ambientMax = Math.max(0.0f, ambientMax);
    }

    public float getAmbientMin() {
        return this.ambientMin;
    }

    public void setAmbientMin(float ambientMin) {
        ambientMin = Math.min(1.0f, ambientMin);
        this.ambientMin = ambientMin = Math.max(0.0f, ambientMin);
    }

    public int getDay() {
        return this.day;
    }

    public void setDay(int day) {
        this.day = day;
    }

    public int getDayPlusOne() {
        return this.day + 1;
    }

    public int getStartDay() {
        return this.startDay;
    }

    public void setStartDay(int startDay) {
        this.startDay = startDay;
    }

    public float getMaxZombieCountStart() {
        return 0.0f;
    }

    public void setMaxZombieCountStart(float maxZombieCountStart) {
        this.maxZombieCountStart = maxZombieCountStart;
    }

    public float getMinZombieCountStart() {
        return 0.0f;
    }

    public void setMinZombieCountStart(float minZombieCountStart) {
        this.minZombieCountStart = minZombieCountStart;
    }

    public float getMaxZombieCount() {
        return this.maxZombieCount;
    }

    public void setMaxZombieCount(float maxZombieCount) {
        this.maxZombieCount = maxZombieCount;
    }

    public float getMinZombieCount() {
        return this.minZombieCount;
    }

    public void setMinZombieCount(float minZombieCount) {
        this.minZombieCount = minZombieCount;
    }

    public int getMonth() {
        return this.month;
    }

    public void setMonth(int month) {
        this.month = month;
    }

    public int getStartMonth() {
        return this.startMonth;
    }

    public void setStartMonth(int startMonth) {
        this.startMonth = startMonth;
    }

    public float getNightTint() {
        if (PerformanceSettings.fboRenderChunk) {
            return 0.0f;
        }
        return ClimateManager.getInstance().getNightStrength();
    }

    private void setNightTint(float nightTint) {
    }

    public float getNight() {
        return ClimateManager.getInstance().getNightStrength();
    }

    private void setNight(float nightTint) {
    }

    public float getTimeOfDay() {
        return this.timeOfDay;
    }

    public void setTimeOfDay(float timeOfDay) {
        this.timeOfDay = timeOfDay;
    }

    public float getStartTimeOfDay() {
        return this.startTimeOfDay;
    }

    public void setStartTimeOfDay(float startTimeOfDay) {
        this.startTimeOfDay = startTimeOfDay;
    }

    public float getViewDist() {
        return ClimateManager.getInstance().getViewDistance();
    }

    public float getViewDistMax() {
        return this.viewDistMax;
    }

    public void setViewDistMax(float viewDistMax) {
        this.viewDistMax = viewDistMax;
    }

    public float getViewDistMin() {
        return this.viewDistMin;
    }

    public void setViewDistMin(float viewDistMin) {
        this.viewDistMin = viewDistMin;
    }

    public int getYear() {
        return this.year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public int getStartYear() {
        return this.startYear;
    }

    public void setStartYear(int startYear) {
        this.startYear = startYear;
    }

    public int getNightsSurvived() {
        return this.nightsSurvived;
    }

    public void setNightsSurvived(int nightsSurvived) {
        this.nightsSurvived = nightsSurvived;
    }

    public double getWorldAgeDaysSinceBegin() {
        return this.getWorldAgeHours() / 24.0 + (double)((SandboxOptions.instance.getTimeSinceApo() - 1) * 30);
    }

    public double getWorldAgeHours() {
        float elapsedHours = (float)this.getNightsSurvived() * 24.0f;
        elapsedHours = this.getTimeOfDay() >= 7.0f ? (elapsedHours += this.getTimeOfDay() - 7.0f) : (elapsedHours += this.getTimeOfDay() + 17.0f);
        return elapsedHours;
    }

    public double getHoursSurvived() {
        DebugLog.log("GameTime.getHoursSurvived() has no meaning, use IsoPlayer.getHourSurvived() instead");
        return this.hoursSurvived;
    }

    public void setHoursSurvived(double hoursSurvived) {
        DebugLog.log("GameTime.getHoursSurvived() has no meaning, use IsoPlayer.getHourSurvived() instead");
        this.hoursSurvived = hoursSurvived;
    }

    public int getHour() {
        double sec = Math.floor(this.getTimeOfDay() * 3600.0f);
        return (int)Math.floor(sec / 3600.0);
    }

    public PZCalendar getCalender() {
        this.updateCalendar(this.getYear(), this.getMonth(), this.getDay(), (int)this.getTimeOfDay(), (int)((this.getTimeOfDay() - (float)((int)this.getTimeOfDay())) * 60.0f));
        return this.calender;
    }

    public void setCalender(PZCalendar calendar) {
        this.calender = calendar;
    }

    public void updateCalendar(int year, int month, int dayOfMonth, int hourOfDay, int minute) {
        if (this.calender == null) {
            this.calender = new PZCalendar(new GregorianCalendar());
        }
        this.calender.set(year, month, dayOfMonth, hourOfDay, minute);
    }

    public float getMinutesPerDay() {
        return this.minutesPerDay;
    }

    public void setMinutesPerDay(float minutesPerDay) {
        this.minutesPerDay = minutesPerDay;
    }

    public float getLastTimeOfDay() {
        return this.lastTimeOfDay;
    }

    public void setLastTimeOfDay(float lastTimeOfDay) {
        this.lastTimeOfDay = lastTimeOfDay;
    }

    public void setTargetZombies(int targetZombies) {
        this.targetZombies = targetZombies;
    }

    public boolean isRainingToday() {
        return true;
    }

    public float getMultiplier() {
        if (!GameServer.server && !GameClient.client && IsoPlayer.getInstance() != null && IsoPlayer.allPlayersAsleep()) {
            return 200.0f * (30.0f / (float)PerformanceSettings.getLockFPS());
        }
        float multiplier = 1.0f;
        if (GameServer.server && GameServer.fastForward) {
            multiplier = (float)ServerOptions.instance.fastForwardMultiplier.getValue() / this.getDeltaMinutesPerDay();
        } else if (GameClient.client && GameClient.fastForward && GameWindow.isIngameState()) {
            multiplier = (float)ServerOptions.instance.fastForwardMultiplier.getValue() / this.getDeltaMinutesPerDay();
        }
        multiplier *= this.multiplier;
        multiplier *= pzopt.ZombieLate.fps(this.fpsMultiplier); // pzopt: zombieLateApply (f8-zomb3), a frame worker reads the frame's value while the apply is held
        multiplier *= this.multiplierBias;
        multiplier *= pzopt.UpdateBatch.pom(this); // pzopt: entityUpdateParallel -- a batch task reads its bucket's dispatch-time multiplier through pom() (the game thread may have moved on to the next bucket's global write); the live field everywhere else
        multiplier *= GameTime.getSlomoMultiplier();
        return multiplier *= 0.8f;
    }

    public void setMultiplier(float multiplier) {
        this.multiplier = multiplier;
    }

    public float getTimeDelta() {
        return this.getTimeDeltaFromMultiplier(this.getMultiplier());
    }

    public float getTimeDeltaFromMultiplier(float multiplier) {
        return multiplier / 0.8f / this.multiplierBias / 60.0f;
    }

    public float getMultiplierFromTimeDelta(float timeDelta) {
        return timeDelta * 0.8f * this.multiplierBias * 60.0f;
    }

    public float getServerMultiplier() {
        float fpsMultiplier = 10.0f / GameWindow.averageFPS / (float)(PerformanceSettings.manualFrameSkips + 1);
        float multiplier = this.multiplier * fpsMultiplier;
        multiplier *= 0.5f;
        if (!GameServer.server && !GameClient.client && IsoPlayer.getInstance() != null && IsoPlayer.allPlayersAsleep()) {
            return 200.0f * (30.0f / (float)PerformanceSettings.getLockFPS());
        }
        multiplier *= 1.6f;
        return multiplier *= this.multiplierBias;
    }

    public float getUnmoddedMultiplier() {
        if (!GameServer.server && !GameClient.client && IsoPlayer.getInstance() != null && IsoPlayer.allPlayersAsleep()) {
            return 200.0f * (30.0f / (float)PerformanceSettings.getLockFPS());
        }
        return this.multiplier * pzopt.ZombieLate.fps(this.fpsMultiplier) * pzopt.UpdateBatch.pom(this); // pzopt: zombieLateApply (f8-zomb3), the frame's fpsMultiplier on a frame worker while the apply is held // pzopt: entityUpdateParallel -- a batch task reads its bucket's dispatch-time multiplier through pom() (the game thread may have moved on to the next bucket's global write); the live field everywhere else
    }

    public float getInvMultiplier() {
        return 1.0f / this.getMultiplier();
    }

    public float getTrueMultiplier() {
        return this.multiplier * pzopt.UpdateBatch.pom(this); // pzopt: entityUpdateParallel -- a batch task reads its bucket's dispatch-time multiplier through pom() (the game thread may have moved on to the next bucket's global write); the live field everywhere else
    }

    public float getThirtyFPSMultiplier() {
        return this.getMultiplier() / 1.6f;
    }

    public float getMultiplierInMenu() {
        return this.fpsMultiplier * 0.8f;
    }

    public float getThirtyFPSMultiplierInMenu() {
        return this.getMultiplierInMenu() / 1.6f;
    }

    public float getTimeDeltaInMenu() {
        return this.getTimeDeltaFromMultiplier(this.getMultiplierInMenu());
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void saveToBufferMap(SaveBufferMap bufferMap) {
        Object object = SliceY.SliceBufferLock;
        synchronized (object) {
            SliceY.SliceBuffer.clear();
            DataOutputStream output = new DataOutputStream(new ByteBufferOutputStream(SliceY.SliceBuffer, false));
            try {
                instance.save(output);
                ByteBufferPooledObject buffer = bufferMap.allocate(SliceY.SliceBuffer.position());
                buffer.put(SliceY.SliceBuffer.array(), 0, SliceY.SliceBuffer.position());
                output.close();
                String outFile = ZomboidFileSystem.instance.getFileNameInCurrentSave("map_t.bin");
                bufferMap.put(outFile, buffer);
            }
            catch (IOException e) {
                DebugType.General.printException(e, LogSeverity.Error);
            }
        }
    }

    public void save() {
        FileOutputStream outStream;
        File outFile = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("map_t.bin"));
        try {
            outStream = new FileOutputStream(outFile);
        }
        catch (FileNotFoundException e) {
            DebugType.General.printException(e, LogSeverity.Error);
            return;
        }
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(outStream));
        try {
            instance.save(output);
        }
        catch (IOException e) {
            DebugType.General.printException(e, LogSeverity.Error);
        }
        try {
            output.flush();
            output.close();
        }
        catch (IOException e) {
            DebugType.General.printException(e, LogSeverity.Error);
        }
    }

    public void save(DataOutputStream output) throws IOException {
        output.writeByte(71);
        output.writeByte(77);
        output.writeByte(84);
        output.writeByte(77);
        output.writeInt(249);
        output.writeFloat(this.multiplier);
        output.writeInt(this.nightsSurvived);
        output.writeInt(this.targetZombies);
        output.writeFloat(this.lastTimeOfDay);
        output.writeFloat(this.timeOfDay);
        output.writeInt(this.day);
        output.writeInt(this.month);
        output.writeInt(this.year);
        output.writeFloat(0.0f);
        output.writeFloat(0.0f);
        output.writeInt(0);
        if (this.table != null) {
            output.writeByte(1);
            this.table.save(output);
        } else {
            output.writeByte(0);
        }
        GameWindow.WriteString(output, Core.getInstance().getPoisonousBerry());
        GameWindow.WriteString(output, Core.getInstance().getPoisonousMushroom());
        output.writeInt(this.helicopterDay1);
        output.writeInt(this.helicopterTime1Start);
        output.writeInt(this.helicopterTime1End);
        ClimateManager.getInstance().save(output);
    }

    public void save(ByteBuffer output) throws IOException {
        output.putFloat(this.multiplier);
        output.putInt(this.nightsSurvived);
        output.putInt(this.targetZombies);
        output.putFloat(this.lastTimeOfDay);
        output.putFloat(this.timeOfDay);
        output.putInt(this.day);
        output.putInt(this.month);
        output.putInt(this.year);
        output.putFloat(0.0f);
        output.putFloat(0.0f);
        output.putInt(0);
        if (this.table != null) {
            output.put((byte)1);
            this.table.save(output);
        } else {
            output.put((byte)0);
        }
    }

    public void load(DataInputStream input) throws IOException {
        int worldVersion = IsoWorld.savedWorldVersion;
        if (worldVersion == -1) {
            worldVersion = 249;
        }
        input.mark(0);
        byte b1 = input.readByte();
        byte b2 = input.readByte();
        byte b3 = input.readByte();
        byte b4 = input.readByte();
        if (b1 == 71 && b2 == 77 && b3 == 84 && b4 == 77) {
            worldVersion = input.readInt();
        } else {
            input.reset();
        }
        this.multiplier = input.readFloat();
        this.nightsSurvived = input.readInt();
        this.targetZombies = input.readInt();
        this.lastTimeOfDay = input.readFloat();
        this.timeOfDay = input.readFloat();
        this.day = input.readInt();
        this.month = input.readInt();
        this.year = input.readInt();
        input.readFloat();
        input.readFloat();
        input.readInt();
        if (input.readByte() == 1) {
            if (this.table == null) {
                this.table = LuaManager.platform.newTable();
            }
            this.table.load(input, worldVersion);
        }
        if (!GameClient.client) {
            Core.getInstance().setPoisonousBerry(GameWindow.ReadString(input));
            Core.getInstance().setPoisonousMushroom(GameWindow.ReadString(input));
        }
        this.helicopterDay1 = input.readInt();
        this.helicopterTime1Start = input.readInt();
        this.helicopterTime1End = input.readInt();
        ClimateManager.getInstance().load(input, worldVersion);
        this.setMinutesStamp();
    }

    public void load(ByteBufferReader input) throws IOException {
        int worldVersion = 249;
        this.multiplier = input.getFloat();
        this.nightsSurvived = input.getInt();
        this.targetZombies = input.getInt();
        this.lastTimeOfDay = input.getFloat();
        this.timeOfDay = input.getFloat();
        this.day = input.getInt();
        this.month = input.getInt();
        this.year = input.getInt();
        input.getFloat();
        input.getFloat();
        input.getInt();
        if (input.getBoolean()) {
            if (this.table == null) {
                this.table = LuaManager.platform.newTable();
            }
            this.table.load(input.bb, 249);
        }
        this.setMinutesStamp();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void load() {
        File inFile = ZomboidFileSystem.instance.getFileInCurrentSave("map_t.bin");
        try (FileInputStream fis2 = new FileInputStream(inFile);
             BufferedInputStream bis = new BufferedInputStream(fis2);){
            Object object = SliceY.SliceBufferLock;
            synchronized (object) {
                SliceY.SliceBuffer.clear();
                int numBytes = bis.read(SliceY.SliceBuffer.array());
                SliceY.SliceBuffer.limit(numBytes);
                DataInputStream input = new DataInputStream(new ByteArrayInputStream(SliceY.SliceBuffer.array(), 0, numBytes));
                this.load(input);
            }
        }
        catch (FileNotFoundException fis2) {
        }
        catch (Exception ex) {
            ExceptionLogger.logException(ex);
        }
    }

    public int getDawn() {
        return this.dawn;
    }

    public void setDawn(int dawn) {
        this.dawn = dawn;
    }

    public int getDusk() {
        return this.dusk;
    }

    public void setDusk(int dusk) {
        this.dusk = dusk;
    }

    public KahluaTable getModData() {
        if (this.table == null) {
            this.table = LuaManager.platform.newTable();
        }
        return this.table;
    }

    public boolean isThunderDay() {
        return this.thunderDay;
    }

    public void setThunderDay(boolean thunderDay) {
        this.thunderDay = thunderDay;
    }

    public void saveToPacket(ByteBufferWriter bb) throws IOException {
        KahluaTable modData = GameTime.getInstance().getModData();
        Object camping = modData.rawget("camping");
        Object farming = modData.rawget("farming");
        Object trapping = modData.rawget("trapping");
        modData.rawset("camping", null);
        modData.rawset("farming", null);
        modData.rawset("trapping", null);
        this.save(bb.bb);
        modData.rawset("camping", camping);
        modData.rawset("farming", farming);
        modData.rawset("trapping", trapping);
    }

    public int getHelicopterDay1() {
        return this.helicopterDay1;
    }

    public int getHelicopterDay() {
        return this.helicopterDay1;
    }

    public void setHelicopterDay(int day) {
        this.helicopterDay1 = PZMath.max(day, 0);
    }

    public int getHelicopterStartHour() {
        return this.helicopterTime1Start;
    }

    public void setHelicopterStartHour(int hour) {
        this.helicopterTime1Start = PZMath.clamp(hour, 0, 24);
    }

    public int getHelicopterEndHour() {
        return this.helicopterTime1End;
    }

    public void setHelicopterEndHour(int hour) {
        this.helicopterTime1End = PZMath.clamp(hour, 0, 24);
    }

    public boolean isEndlessDay() {
        return SandboxOptions.getInstance().dayNightCycle.getValue() == 2;
    }

    public boolean isEndlessNight() {
        return SandboxOptions.getInstance().dayNightCycle.getValue() == 3;
    }

    public boolean isDay() {
        return this.isEndlessDay() || this.timeOfDay >= ClimateManager.getInstance().getSeason().getDawn() && this.timeOfDay <= ClimateManager.getInstance().getSeason().getDusk();
    }

    public boolean isNight() {
        return this.isEndlessNight() || !this.isDay();
    }

    public boolean isZombieActivityPhase() {
        return SandboxOptions.instance.lore.activeOnly.getValue() == 1 || SandboxOptions.instance.lore.activeOnly.getValue() == 2 && this.isNight() || SandboxOptions.instance.lore.activeOnly.getValue() == 3 && this.isDay();
    }

    public boolean isZombieInactivityPhase() {
        return !this.isZombieActivityPhase();
    }

    public double minHours(double hours) {
        return GameTime.minHours(hours, this.getWorldAgeHours());
    }

    public static double minHours(double hours, double worldAgeHours) {
        return Math.min(hours, worldAgeHours);
    }

    public float minHours(float hours) {
        return GameTime.minHours(hours, (float)this.getWorldAgeHours());
    }

    public static float minHours(float hours, float worldAgeHours) {
        return PZMath.min(hours, worldAgeHours);
    }

    public double clampHours(double hours) {
        return GameTime.clampHours(hours, this.getWorldAgeHours());
    }

    public float clampHours(float hours) {
        return GameTime.clampHours(hours, (float)this.getWorldAgeHours());
    }

    public static double clampHours(double hours, double worldAgeHours) {
        return Math.clamp(hours, 0.0, worldAgeHours);
    }

    public static float clampHours(float hours, float worldAgeHours) {
        return PZMath.clamp(hours, 0.0f, worldAgeHours);
    }

    public static double checkHours(double hours, double worldAgeHours) {
        return hours < 0.0 ? worldAgeHours : GameTime.minHours(hours, worldAgeHours);
    }

    public static float checkHours(float hours, float worldAgeHours) {
        return hours < 0.0f ? worldAgeHours : GameTime.minHours(hours, worldAgeHours);
    }

    public static class AnimTimer {
        public float elapsed;
        public float duration;
        public boolean finished = true;
        public int ticks;

        public void init(int ticks) {
            this.ticks = ticks;
            this.elapsed = 0.0f;
            this.duration = (float)ticks / 30.0f;
            this.finished = false;
        }

        public void update() {
            this.elapsed += instance.getMultipliedSecondsSinceLastUpdate() * 60.0f / 30.0f;
            if (this.elapsed >= this.duration) {
                this.elapsed = this.duration;
                this.finished = true;
            }
        }

        public float ratio() {
            return this.elapsed / this.duration;
        }

        public boolean finished() {
            return this.finished;
        }
    }
}

