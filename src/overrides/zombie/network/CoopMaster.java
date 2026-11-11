package zombie.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.Platform;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager.GlobalObject;
import zombie.core.Core;
import zombie.core.ThreadGroups;
import zombie.core.logger.LoggerManager;
import zombie.core.logger.ZipLogs;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

public class CoopMaster {
   private Process serverProcess;
   private Thread serverThread;
   private PrintStream serverCommandStream;
   private final List<String> incomingMessages = new LinkedList<>();
   private final Pattern serverMessageParser;
   private CoopMaster.TerminationReason serverTerminationReason;
   private Thread timeoutWatchThread;
   private boolean serverResponded;
   public static final CoopMaster instance = new CoopMaster();
   private String adminUsername;
   private final String adminPassword;
   private String serverName;
   private Long serverSteamId;
   private String serverIp;
   private Integer serverPort;
   private int autoCookie;
   private static final int autoCookieOffset = 1000000;
   private static final int maxAutoCookie = 1000000;
   private final List<CoopMaster.Pair<ICoopServerMessageListener, CoopMaster.ListenerOptions>> listeners = new LinkedList<>();

   private CoopMaster() {
      this.serverMessageParser = Pattern.compile("^([\\-\\w]+)(\\[(\\d+)\\])?@(.*)$");
      this.adminPassword = UUID.randomUUID().toString();
   }

   public int getServerPort() {
      return this.serverPort;
   }

   public void launchServer(String serverName, String username, int memory) throws IOException {
      this.launchServer(serverName, username, memory, false);
   }

   public void softreset(String serverName, String username, int memory) throws IOException {
      this.launchServer(serverName, username, memory, true);
   }

   private void launchServer(String serverName, String username, int memory, boolean softreset) throws IOException {
      String javaPath = Paths.get(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
      if (SteamUtils.isSteamModeEnabled()) {
         username = "admin";
      }

      ArrayList<String> command = new ArrayList<>();
      command.add(javaPath);
      command.add("-Xms" + memory + "m");
      command.add("-Xmx" + memory + "m");
      command.add("-Djava.library.path=" + System.getProperty("java.library.path"));
      command.add("-Djava.class.path=" + System.getProperty("java.class.path"));
      command.add("-Duser.dir=" + System.getProperty("user.dir"));
      command.add("-Duser.home=" + System.getProperty("user.home"));
      command.add("-Dzomboid.steam=" + (SteamUtils.isSteamModeEnabled() ? "1" : "0"));
      command.add("-Djava.awt.headless=true");
      command.add("-XX:-OmitStackTraceInFastThrow");
      String gc = this.getGarbageCollector();
      if (gc != null) {
         command.add(gc);
      }

      if (softreset) {
         command.add("-Dsoftreset");
      }

      if (Core.debug) {
         command.add("-Ddebug");
      }

      command.add("zombie.network.GameServer");
      command.add("-coop");
      if (Core.antiCheats) {
         command.add("-anti-cheats");
      }

      command.add("-servername");
      command.add(this.serverName = serverName);
      command.add("-adminusername");
      command.add(this.adminUsername = username);
      command.add("-adminpassword");
      command.add(this.adminPassword);
      command.add("-cachedir=" + ZomboidFileSystem.instance.getCacheDir());
      command.add("-useTimeStampedLogFileNames=" + (LoggerManager.getUseTimeStampedLogFileNames() ? 1 : 0));
      command.add("-debugLogFile=" + LoggerManager.getDebugLogFilePrefix());
      pzopt.CoopAgent.addAgent(command); // pzopt: the hosted server gets this launch's PZ Optimization agent (42.22 signed jar)
      ProcessBuilder pb = new ProcessBuilder(command);
      ZipLogs.addZipFile(false);
      this.serverTerminationReason = CoopMaster.TerminationReason.NormalTermination;
      this.serverResponded = false;
      this.serverProcess = pb.start();
      this.serverCommandStream = new PrintStream(this.serverProcess.getOutputStream());
      this.serverThread = new Thread(ThreadGroups.Workers, this::readServer);
      this.serverThread.setUncaughtExceptionHandler(GameWindow::uncaughtException);
      this.serverThread.start();
      this.timeoutWatchThread = new Thread(ThreadGroups.Workers, this::watchServer);
      this.timeoutWatchThread.setUncaughtExceptionHandler(GameWindow::uncaughtException);
      this.timeoutWatchThread.start();
   }

   private String getGarbageCollector() {
      try {
         RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
         List<String> arguments = runtimeMxBean.getInputArguments();
         boolean useZGC = false;
         boolean useG1GC = false;

         for (String argument : arguments) {
            if ("-XX:+UseZGC".equals(argument)) {
               useZGC = true;
            }

            if ("-XX:-UseZGC".equals(argument)) {
               useZGC = false;
            }

            if ("-XX:+UseG1GC".equals(argument)) {
               useG1GC = true;
            }

            if ("-XX:-UseG1GC".equals(argument)) {
               useG1GC = false;
            }
         }

         if (useZGC) {
            return "-XX:+UseZGC";
         }

         if (useG1GC) {
            return "-XX:+UseG1GC";
         }
      } catch (Throwable var7) {
      }

      return null;
   }

   private void readServer() {
      BufferedReader serverOutput = new BufferedReader(new InputStreamReader(this.serverProcess.getInputStream()));

      while (true) {
         try {
            int e = this.serverProcess.exitValue();
            break;
         } catch (IllegalThreadStateException var5) {
            String line = null;

            try {
               line = serverOutput.readLine();
            } catch (IOException e) {
               DebugType.General.printException(e, LogSeverity.Error);
            }

            if (line != null) {
               this.storeMessage(line);
               this.serverResponded = true;
            }
         }
      }

      this.storeMessage("process-status@terminated");
      ZipLogs.addZipFile(true);
   }

   public void abortServer() {
      this.serverProcess.destroy();
   }

   private void watchServer() {
      int timeout = 20;

      try {
         Thread.sleep(20000L);
         if (!this.serverResponded) {
            this.serverTerminationReason = CoopMaster.TerminationReason.Timeout;
            this.abortServer();
         }
      } catch (InterruptedException e) {
         DebugType.General.printException(e, LogSeverity.Error);
      }
   }

   public boolean isRunning() {
      return this.serverThread != null && this.serverThread.isAlive();
   }

   public CoopMaster.TerminationReason terminationReason() {
      return this.serverTerminationReason;
   }

   private void storeMessage(String message) {
      synchronized (this.incomingMessages) {
         this.incomingMessages.add(message);
      }
   }

   public synchronized void sendMessage(String tag, String cookie, String payload) {
      StringBuilder stringBuilder = new StringBuilder();
      stringBuilder.append(tag);
      if (cookie == null) {
         stringBuilder.append("@");
      } else {
         stringBuilder.append("[");
         stringBuilder.append(cookie);
         stringBuilder.append("]@");
      }

      stringBuilder.append(payload);
      String message = stringBuilder.toString();
      if (this.serverCommandStream != null) {
         this.serverCommandStream.println(message);
         this.serverCommandStream.flush();
      }
   }

   public void sendMessage(String tag, String payload) {
      this.sendMessage(tag, null, payload);
   }

   public synchronized void invokeServer(String tag, String payload, ICoopServerMessageListener responseHandler) {
      this.autoCookie = (this.autoCookie + 1) % 1000000;
      String cookie = Integer.toString(1000000 + this.autoCookie);
      this.addListener(responseHandler, new CoopMaster.ListenerOptions(tag, cookie, true));
      this.sendMessage(tag, cookie, payload);
   }

   public String getMessage() {
      String result = null;
      synchronized (this.incomingMessages) {
         if (!this.incomingMessages.isEmpty()) {
            result = this.incomingMessages.get(0);
            this.incomingMessages.remove(0);
            if (!"ping@ping".equals(result)) {
               System.out.println("SERVER: " + result);
            }
         }

         return result;
      }
   }

   public void update() {
      String message;
      while ((message = this.getMessage()) != null) {
         Matcher matcher = this.serverMessageParser.matcher(message);
         if (matcher.find()) {
            String tag = matcher.group(1);
            String cookie = matcher.group(3);
            String payload = matcher.group(4);
            if (!tag.equals("ping")) {
               LuaEventManager.triggerEvent("OnCoopServerMessage", tag, cookie, payload);
            }

            this.handleMessage(tag, cookie, payload);
         } else {
            DebugLog.log(DebugType.Network, "[CoopMaster] Unknown message incoming from the slave server: " + message);
         }
      }
   }

   private void handleMessage(String tag, String cookie, String payload) {
      if (Objects.equals(tag, "ping")) {
         this.sendMessage("ping", cookie, "pong");
      } else if (Objects.equals(tag, "steam-id")) {
         if (Objects.equals(payload, "null")) {
            this.serverSteamId = null;
         } else {
            this.serverSteamId = SteamUtils.convertStringToSteamID(payload);
         }
      } else if (Objects.equals(tag, "server-address")) {
         DebugLog.log("Got server-address: " + payload);
         String pattern = "^(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)$";
         Pattern compiledPattern = Pattern.compile("^(\\d+\\.\\d+\\.\\d+\\.\\d+):(\\d+)$");
         Matcher matcher = compiledPattern.matcher(payload);
         if (matcher.find()) {
            String ipString = matcher.group(1);
            String portString = matcher.group(2);
            this.serverIp = ipString;
            this.serverPort = Integer.valueOf(portString);
            DebugLog.log("Successfully parsed: address = " + this.serverIp + ", port = " + this.serverPort);
         } else {
            DebugLog.log("Failed to parse server address");
         }
      }

      this.invokeListeners(tag, cookie, payload);
   }

   public void register(Platform platform, KahluaTable environment) {
      KahluaTable table = platform.newTable();
      table.rawset("launch", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            boolean result = false;
            if (nArguments == 4) {
               if (!(callFrame.get(1) instanceof String arg1) || !(callFrame.get(2) instanceof String arg2) || !(callFrame.get(3) instanceof Double arg3)) {
                  return 0;
               }

               try {
                  CoopMaster.this.launchServer(arg1, arg2, arg3.intValue());
                  result = true;
               } catch (IOException e) {
                  DebugType.General.printException(e, LogSeverity.Error);
               }
            } else {
               DebugLog.log(DebugType.Network, "[CoopMaster] wrong number of arguments: " + nArguments);
            }

            callFrame.push(result);
            return 1;
         }
      });
      table.rawset("softreset", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            boolean result = false;
            if (nArguments == 4) {
               if (!(callFrame.get(1) instanceof String arg1) || !(callFrame.get(2) instanceof String arg2) || !(callFrame.get(3) instanceof Double arg3)) {
                  return 0;
               }

               try {
                  CoopMaster.this.softreset(arg1, arg2, arg3.intValue());
                  result = true;
               } catch (IOException e) {
                  DebugType.General.printException(e, LogSeverity.Error);
               }
            } else {
               DebugLog.log(DebugType.Network, "[CoopMaster] wrong number of arguments: " + nArguments);
            }

            callFrame.push(result);
            return 1;
         }
      });
      table.rawset("isRunning", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            callFrame.push(CoopMaster.this.isRunning());
            return 1;
         }
      });
      table.rawset("sendMessage", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            if (nArguments == 4) {
               if (callFrame.get(1) instanceof String arg1 && callFrame.get(2) instanceof String arg2 && callFrame.get(3) instanceof String arg3) {
                  CoopMaster.this.sendMessage(arg1, arg2, arg3);
               }
            } else if (nArguments == 3 && callFrame.get(1) instanceof String arg1 && callFrame.get(2) instanceof String arg2) {
               CoopMaster.this.sendMessage(arg1, arg2);
            }

            return 0;
         }
      });
      table.rawset("getAdminPassword", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            callFrame.push(CoopMaster.this.adminPassword);
            return 1;
         }
      });
      table.rawset("getTerminationReason", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            callFrame.push(CoopMaster.this.serverTerminationReason.toString());
            return 1;
         }
      });
      table.rawset("getSteamID", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            if (CoopMaster.this.serverSteamId != null) {
               callFrame.push(SteamUtils.convertSteamIDToString(CoopMaster.this.serverSteamId));
               return 1;
            } else {
               return 0;
            }
         }
      });
      table.rawset("getAddress", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            callFrame.push(CoopMaster.this.serverIp);
            return 1;
         }
      });
      table.rawset("getPort", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            callFrame.push(CoopMaster.this.serverPort);
            return 1;
         }
      });
      table.rawset("abort", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            CoopMaster.this.abortServer();
            return 0;
         }
      });
      table.rawset("getServerSaveFolder", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            Object arg1 = callFrame.get(1);
            callFrame.push(CoopMaster.this.getServerSaveFolder((String)arg1));
            return 1;
         }
      });
      table.rawset("getPlayerSaveFolder", new JavaFunction() {
         public int call(LuaCallFrame callFrame, int nArguments) {
            Object arg1 = callFrame.get(1);
            callFrame.push(CoopMaster.this.getPlayerSaveFolder((String)arg1));
            return 1;
         }
      });
      environment.rawset("CoopServer", table);
   }

   public void addListener(ICoopServerMessageListener listener, CoopMaster.ListenerOptions options) {
      synchronized (this.listeners) {
         this.listeners.add(new CoopMaster.Pair<>(listener, options));
      }
   }

   public void addListener(ICoopServerMessageListener listener) {
      this.addListener(listener, null);
   }

   public void removeListener(ICoopServerMessageListener listener) {
      synchronized (this.listeners) {
         int index = 0;

         while (index < this.listeners.size() && this.listeners.get(index).first != listener) {
            index++;
         }

         if (index < this.listeners.size()) {
            this.listeners.remove(index);
         }
      }
   }

   private void invokeListeners(String tag, String cookie, String payload) {
      synchronized (this.listeners) {
         Iterator<CoopMaster.Pair<ICoopServerMessageListener, CoopMaster.ListenerOptions>> iterator = this.listeners.iterator();

         while (iterator.hasNext()) {
            CoopMaster.Pair<ICoopServerMessageListener, CoopMaster.ListenerOptions> item = iterator.next();
            ICoopServerMessageListener listener = item.first;
            CoopMaster.ListenerOptions options = item.second;
            if (listener != null) {
               if (options == null) {
                  listener.OnCoopServerMessage(tag, cookie, payload);
               } else if ((options.tag == null || options.tag.equals(tag)) && (options.cookie == null || options.cookie.equals(cookie))) {
                  if (options.autoRemove) {
                     iterator.remove();
                  }

                  listener.OnCoopServerMessage(tag, cookie, payload);
               }
            }
         }
      }
   }

   public String getServerName() {
      return this.serverName;
   }

   public String getServerSaveFolder(String serverName) {
      return GlobalObject.sanitizeWorldName(serverName);
   }

   public String getPlayerSaveFolder(String serverName) {
      return GlobalObject.sanitizeWorldName(serverName + "_player");
   }

   public class ListenerOptions {
      public String tag;
      public String cookie;
      public boolean autoRemove;

      public ListenerOptions(final String tag, final String cookie, final boolean autoRemove) {
         this.tag = tag;
         this.cookie = cookie;
         this.autoRemove = autoRemove;
      }

      public ListenerOptions(final String tag, final String cookie) {
         this(tag, cookie, false);
      }

      public ListenerOptions(final String tag) {
         this(tag, null, false);
      }
   }

   private class Pair<K, V> {
      private final K first;
      private final V second;

      public Pair(final K first, final V second) {
         this.first = first;
         this.second = second;
      }

      public K getFirst() {
         return this.first;
      }

      public V getSecond() {
         return this.second;
      }
   }

   public enum TerminationReason {
      NormalTermination,
      Timeout;
   }
}
