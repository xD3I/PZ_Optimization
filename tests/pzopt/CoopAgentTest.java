package pzopt;

import java.util.List;

/** The hosted server gets this launch's agent as an absolute path, unless the environment passes it on (macOS). */
public final class CoopAgentTest {
   public static void main(String[] args) {
      String dir = System.getProperty("os.name", "").startsWith("Win") ? "C:\\Games\\ProjectZomboid" : "/games/ProjectZomboid";
      String rel = CoopAgent.agentArgument(List.of("-Xmx4g", "-javaagent:pzopt/agent/pzopt-agent.jar", "-Dzomboid.steam=1"), null, dir);
      Check.check(rel != null && rel.startsWith("-javaagent:") && rel.endsWith("pzopt-agent.jar")
            && java.nio.file.Path.of(rel.substring(11)).isAbsolute(), "relative agent made absolute: " + rel);
      String abs = CoopAgent.agentArgument(List.of("-javaagent:/opt/pz/pzopt/agent/pzopt-agent.jar=x"), null, dir);
      Check.check(abs != null && abs.endsWith("pzopt-agent.jar=x"), "agent options kept: " + abs);
      Check.check(CoopAgent.agentArgument(List.of("-javaagent:ZombieBuddy.jar"), null, dir) == null, "another agent is not passed on");
      Check.check(CoopAgent.agentArgument(List.of("-Xmx4g"), null, dir) == null, "no agent, stock server");
      Check.check(CoopAgent.agentArgument(List.of("-javaagent:/a/pzopt/agent/pzopt-agent.jar"),
            "-javaagent:'/a/pzopt/agent/pzopt-agent.jar'", dir) == null, "JAVA_TOOL_OPTIONS already reaches the server");
      System.out.println("CoopAgentTest ok");
   }
}
