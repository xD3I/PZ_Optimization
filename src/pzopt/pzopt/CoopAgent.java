package pzopt;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;

/**
 * The hosted server (main menu > Host) runs PZ Optimization like the client again (2026-11-11, Discord report
 * "Error when I want click to start in host game": "File doesn't exist on the client: media/lua/shared/pzopt/
 * pzopt_keybinding.lua").
 *
 * <p>{@code CoopMaster.launchServer} starts the server as a second JVM ({@code jre64/bin/java}, the client's class path,
 * its own short list of options). Before Build 42.22 that class path loaded our classes from the game folder, so the
 * server ran them too. Since the jar is signed they load only through the Java agent, which the server command never
 * names: the server ran stock. Our {@code NetChecksum} leaves the pzopt Lua files out of the multiplayer Lua checksum
 * ({@link LuaChecksum}); a stock server counts them, so the server's file list and the host's own client no longer
 * agreed and the join was refused. Friends joining that server failed the same way, with or without PZ Optimization.
 *
 * <p>The fix hands the server the agent this launch was started with ({@code -javaagent:...pzopt-agent.jar} among the
 * JVM's input arguments), as an absolute path, right after the java executable. Not when the agent came from
 * {@code JAVA_TOOL_OPTIONS} (the macOS launch option): the server inherits the environment and gets it that way. The
 * server's {@code java} sits in {@code jre64/bin}, so Windows finds the agent's libraries without pzopt-launch.cmd.
 * {@code -Dpzopt.coopServerAgent=false} keeps the server stock.
 */
public final class CoopAgent {
   private CoopAgent() {
   }

   public static void addAgent(List<String> command) {
      try {
         if (!Boolean.parseBoolean(System.getProperty("pzopt.coopServerAgent", "true"))) {
            return;
         }
         String arg = agentArgument(ManagementFactory.getRuntimeMXBean().getInputArguments(), System.getenv("JAVA_TOOL_OPTIONS"),
               System.getProperty("user.dir"));
         if (arg != null && !command.isEmpty()) {
            command.add(1, arg);
            Log.info("hosted server: started with the PZ Optimization agent (" + arg + ")");
         }
      } catch (Throwable t) {
         Log.warn("hosted server: could not add the PZ Optimization agent, the server runs stock: " + t);
      }
   }

   /** The agent argument for the server, or null (no agent of ours in this launch, or the environment passes it on). */
   static String agentArgument(List<String> inputArguments, String javaToolOptions, String userDir) {
      if (javaToolOptions != null && javaToolOptions.contains("pzopt-agent.jar")) {
         return null;
      }
      for (String a : inputArguments) {
         if (a != null && a.startsWith("-javaagent:") && a.contains("pzopt-agent.jar")) {
            String spec = a.substring("-javaagent:".length());
            int eq = spec.indexOf('=');
            String jar = eq < 0 ? spec : spec.substring(0, eq);
            String opts = eq < 0 ? "" : spec.substring(eq);
            Path p = Path.of(jar);
            if (!p.isAbsolute() && userDir != null) {
               p = Path.of(userDir).resolve(p);
            }
            return "-javaagent:" + p.normalize() + opts;
         }
      }
      return null;
   }
}
