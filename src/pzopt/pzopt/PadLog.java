package pzopt;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.lwjglx.input.Controller;
import zombie.GameWindow;
import zombie.input.JoypadManager;

/**
 * What the game knows about controllers during the first minutes of a boot (2026-10-24, the flip report: after the
 * built-in benchmark's restart the report screen did not react to the controller at all, while the Steam logs say Steam
 * kept the restarted process as the game). Console lines {@code [pzopt] pads: ...}, each with the ms since the JVM
 * started:
 * <ul>
 * <li>at boot ({@link #start}, from the update check in GameWindow.mainThreadInit): the launch's Steam environment
 * (SteamAppId / SteamGameId / SteamEnv, the Steam overlay in LD_PRELOAD, the SDL controller variables Steam Input sets,
 * the Steam runtime container, Restart's marker, zomboid.steam); on Linux a daemon thread then reads
 * /proc/bus/input/devices every 500 ms for {@link #WINDOW_MS} and logs the joystick devices (name, vendor:product,
 * handlers, whether this process may read the event node) whenever the set changes;</li>
 * <li>from the game thread ({@link #frame}, InputLag's hook after GameInput.updateGameThread): the controllers GLFW
 * hands the game (index, GUID, names, gamepad mapping), the GUIDs the options enable, the joypads the manager made of
 * them, the activated joypad, whenever one of these changes, and the first presses of each controller's buttons.</li>
 * </ul>
 * Nothing runs after the window; a frame costs one clock read then.
 */
public final class PadLog {
   private PadLog() {
   }

   static final long WINDOW_MS = 300_000;
   private static final int MAX_PRESSES = 40;

   private static boolean started;
   private static boolean done;
   private static int frames;
   private static String lastGame = "";
   private static int presses;

   private static long ms() {
      return ManagementFactory.getRuntimeMXBean().getUptime();
   }

   private static void log(String msg) {
      Log.info("pads: +" + ms() + " ms " + msg);
   }

   /** Once per boot, early: the environment, then the device watcher (Linux). */
   public static synchronized void start() {
      if (started) {
         return;
      }
      started = true;
      try {
         Map<String, String> env = System.getenv();
         StringBuilder sb = new StringBuilder("env");
         for (String k : new String[] {"SteamAppId", "SteamGameId", "SteamEnv", "SteamDeck", "SteamOS", "STEAM_COMPAT_APP_ID",
               "SDL_GAMECONTROLLER_IGNORE_DEVICES", "SDL_GAMECONTROLLER_ALLOW_STEAM_VIRTUAL_GAMEPAD", "SDL_JOYSTICK_HIDAPI",
               "SDL_VIDEODRIVER", "container", "PRESSURE_VESSEL_RUNTIME", "PZOPT_RESTARTED_FROM"}) {
            String v = env.get(k);
            sb.append(' ').append(k).append('=').append(v == null ? "-" : v.length() > 80 ? v.substring(0, 80) + "..." : v);
         }
         String cfg = env.get("SDL_GAMECONTROLLERCONFIG");
         sb.append(" SDL_GAMECONTROLLERCONFIG=").append(cfg == null ? "-" : cfg.split("\n").length + " mappings");
         String pre = env.get("LD_PRELOAD");
         sb.append(" overlay=").append(pre != null && pre.contains("gameoverlayrenderer") ? "preloaded" : "no");
         sb.append(" pressure-vessel=").append(Files.exists(Path.of("/run/pressure-vessel")) ? "yes" : "no");
         sb.append(" zomboid.steam=").append(System.getProperty("zomboid.steam", "-"));
         log(sb.toString());
      } catch (Exception e) {
         log("env unreadable: " + e);
      }
      Path devices = Path.of("/proc/bus/input/devices");
      if (!Files.isReadable(devices)) {
         return;
      }
      Thread t = new Thread(() -> {
         String last = null;
         while (ms() < WINDOW_MS) {
            String now = joysticks(devices);
            if (!now.equals(last)) {
               log("devices " + now);
               last = now;
            }
            try {
               Thread.sleep(500);
            } catch (InterruptedException e) {
               return;
            }
         }
      }, "pzopt-padlog");
      t.setDaemon(true);
      t.start();
   }

   /** The joystick devices of /proc/bus/input/devices (the ones with a js handler), sorted. */
   private static String joysticks(Path devices) {
      TreeSet<String> out = new TreeSet<>();
      try {
         String name = "?", id = "?", handlers = "";
         for (String line : Files.readAllLines(devices)) {
            if (line.startsWith("I: ")) {
               id = field(line, "Vendor") + ":" + field(line, "Product");
            } else if (line.startsWith("N: Name=")) {
               name = line.substring(8).replace("\"", "");
            } else if (line.startsWith("H: Handlers=")) {
               handlers = line.substring(12).trim();
            } else if (line.isBlank()) {
               if (handlers.matches(".*\\bjs\\d+.*")) {
                  String ev = "";
                  for (String h : handlers.split("\\s+")) {
                     if (h.startsWith("event")) {
                        ev = h + (Files.isReadable(Path.of("/dev/input", h)) ? " readable" : " NOT readable");
                     }
                  }
                  out.add("[" + name + " " + id + " " + ev + "]");
               }
               name = "?";
               id = "?";
               handlers = "";
            }
         }
      } catch (Exception e) {
         return "unreadable: " + e;
      }
      return out.isEmpty() ? "none" : out.size() + " " + String.join(" ", out);
   }

   private static String field(String line, String key) {
      int i = line.indexOf(key + "=");
      if (i < 0) {
         return "?";
      }
      int e = line.indexOf(' ', i);
      return line.substring(i + key.length() + 1, e < 0 ? line.length() : e);
   }

   /** Game thread, every frame (InputLag.afterGameInput) until the window ends. */
   public static void frame() {
      if (done) {
         return;
      }
      if (ms() > WINDOW_MS) {
         done = true;
         log("window over (" + presses + " presses logged)");
         return;
      }
      try {
         if (++frames % 15 == 0) {
            String now = gameState();
            if (!now.equals(lastGame)) {
               log("game " + now);
               lastGame = now;
            }
         }
         if (presses < MAX_PRESSES) {
            int n = GameWindow.GameInput.getControllerCount();
            for (int i = 0; i < n; i++) {
               Controller c = GameWindow.GameInput.getController(i);
               if (c == null) {
                  continue;
               }
               for (int b = 0; b < c.getButtonCount(); b++) {
                  if (GameWindow.GameInput.isButtonStartPress(i, b)) {
                     JoypadManager.Joypad j = JoypadManager.instance.getFromControllerID(i);
                     log("press controller " + i + " button " + b + " (joypad " + (j == null ? "none" : j.getID() + (JoypadManager.instance.joypadList.contains(j) ? " listed" : " not listed")) + ", activated "
                           + (GameWindow.activatedJoyPad == null ? "none" : GameWindow.activatedJoyPad.getID()) + ")");
                     if (++presses >= MAX_PRESSES) {
                        return;
                     }
                  }
               }
            }
         }
      } catch (Exception e) {
         done = true;
         log("game-side logging off after " + e);
      }
   }

   private static String gameState() {
      List<String> pads = new ArrayList<>();
      int n = GameWindow.GameInput.getControllerCount();
      for (int i = 0; i < n; i++) {
         Controller c = GameWindow.GameInput.getController(i);
         if (c != null) {
            pads.add(i + ":" + c.getGUID() + " \"" + c.getJoystickName() + "\"/\"" + c.getGamepadName() + "\" gamepad=" + c.isGamepad()
                  + " active=" + JoypadManager.instance.activeControllerGuids.contains(c.getGUID()));
         }
      }
      List<String> joypads = new ArrayList<>();
      for (JoypadManager.Joypad j : JoypadManager.instance.joypadList) {
         joypads.add(String.valueOf(j.getID()));
      }
      return "controllers " + (pads.isEmpty() ? "none" : String.join(", ", pads)) + "; enabled guids "
            + new TreeSet<>(JoypadManager.instance.activeControllerGuids) + "; joypads " + joypads + "; activated "
            + (GameWindow.activatedJoyPad == null ? "none" : GameWindow.activatedJoyPad.getID());
   }
}
