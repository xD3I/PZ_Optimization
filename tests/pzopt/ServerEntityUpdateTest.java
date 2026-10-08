package pzopt;

/** entityUpdateServer: the authoritative server may batch safe zombies; an MP client never may. */
public final class ServerEntityUpdateTest {
   public static void main(String[] args) {
      System.setProperty("pzopt.entityUpdateParallel", "true");
      System.setProperty("pzopt.entityUpdatePipeline", "true");
      System.setProperty("pzopt.entityUpdateServer", "true");
      System.setProperty("pzopt.workers", "2");
      zombie.core.random.RandStandard.INSTANCE.init();

      Check.check(!UpdateBatch.multiplayerAllowed(false, true, false),
            "a server stays serial unless entityUpdateServer is on");
      Check.check(UpdateBatch.multiplayerAllowed(false, true, true),
            "entityUpdateServer allows the authoritative server");
      Check.check(!UpdateBatch.multiplayerAllowed(true, false, true),
            "a multiplayer client stays serial even when entityUpdateServer is on");
      Check.check(!UpdateBatch.multiplayerAllowed(true, true, true),
            "a listen-server client process stays serial");
      Check.check(!UpdateBatch.safeState(zombie.ai.states.WalkTowardNetworkState.instance(), true, false),
            "the server network walk state stays excluded without the opt-in");
      Check.check(UpdateBatch.safeState(zombie.ai.states.WalkTowardNetworkState.instance(), true, true),
            "the server opt-in accepts the network walk state");
      Check.check(!UpdateBatch.safeState(zombie.ai.states.WalkTowardNetworkState.instance(), false, true),
            "the network walk state is not added to single-player eligibility");

      zombie.network.GameClient.client = false;
      zombie.network.GameServer.server = true;
      Check.check(Config.ENTITY_UPDATE_SERVER, "the server key was read");
      Check.check(UpdateBatch.enabled(), "the authoritative server enables the safe-state entity batch");
      zombie.network.GameClient.client = true;
      Check.check(!UpdateBatch.enabled(), "the client gate still wins");

      System.out.println("ServerEntityUpdateTest ok");
   }
}
