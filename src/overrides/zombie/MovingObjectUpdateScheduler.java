package zombie;

import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.popman.ZombieCountOptimiser;
import zombie.util.list.PZArrayUtil;

public final class MovingObjectUpdateScheduler {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.MovingObjectUpdateScheduler");
   }

   public static final MovingObjectUpdateScheduler instance = new MovingObjectUpdateScheduler();
   private final MovingObjectUpdateSchedulerUpdateBucket[] simulationLevels;
   private long frameCounter;
   private final int[] pzoptLevelCount = new int[5]; // pzopt: instrument
   private boolean isEnabled = true;

   private MovingObjectUpdateScheduler() {
      this.simulationLevels = new MovingObjectUpdateSchedulerUpdateBucket[UpdateSchedulerSimulationLevel.numValues()];

      for (UpdateSchedulerSimulationLevel simulationLevel : UpdateSchedulerSimulationLevel.allValues()) {
         this.simulationLevels[simulationLevel.getUpdateOrderIndex()] = new MovingObjectUpdateSchedulerUpdateBucket(simulationLevel);
      }
   }

   public long getFrameCounter() {
      return this.frameCounter;
   }

   private boolean pzoptSeparateBatch; // pzopt: separateParallel, this frame's zombies are being collected

   public void startFrame() {
      long pzoptT = pzopt.GtAb.begin(); // pzopt: devGtAlternate section timer
      try { // pzopt
         this.pzoptStartFrame(); // pzopt
      } finally { // pzopt
         pzopt.GtAb.end(pzopt.GtAb.S_START_FRAME, pzoptT); // pzopt
      } // pzopt
   } // pzopt

   private void pzoptStartFrame() { // pzopt: the stock body of startFrame
      this.frameCounter++;
      if (pzopt.Config.INSTRUMENT && this.frameCounter % 600L == 0L) { // pzopt: instrument, zombies classified per level over the last 600 frames
         pzopt.Log.info("sim levels (zombies a frame, 1/16 1/8 1/4 1/2 full): " + this.pzoptLevelCount[0] / 600 + " " + this.pzoptLevelCount[1] / 600 + " " + this.pzoptLevelCount[2] / 600 + " " + this.pzoptLevelCount[3] / 600 + " " + this.pzoptLevelCount[4] / 600); // pzopt
         java.util.Arrays.fill(this.pzoptLevelCount, 0); // pzopt
      } // pzopt
      pzopt.SeparateBatch.clear(); // pzopt: separateParallel, anything a previous frame left uncollected
      this.pzoptSeparateBatch = pzopt.SeparateBatch.enabled();
      PZArrayUtil.forEach(this.simulationLevels, MovingObjectUpdateSchedulerUpdateBucket::clear);
      float averageFps = GameWindow.averageFPS;
      if (GameServer.server) {
         ZombieCountOptimiser.prepareZombiesForDeletion();
      }

      if (!GameServer.server && this.isEnabled && pzopt.SchedulerClassify.enabled()) { // pzopt: schedulerClassifyParallel
         pzopt.SchedulerClassify.run(this, IsoWorld.instance.getCell().getObjectList(), averageFps); // pzopt: the loop below, classified on the frame workers
         return; // pzopt
      } // pzopt

      for (IsoMovingObject isoMovingObject : IsoWorld.instance.getCell().getObjectList()) {
         if (GameServer.server && isoMovingObject instanceof IsoZombie isoZombie) {
            if (GameServer.guiCommandline) {
               isoZombie.updateForServerGui();
            }
         } else {
            if (isoMovingObject.getCurrentSquare() == null) {
               isoMovingObject.setCurrentSquareFromPosition();
            }

            UpdateSchedulerSimulationLevel sim = this.getUpdateSchedulerSimulationLevelForObject(isoMovingObject, averageFps);
            this.simulationLevels[sim.getUpdateOrderIndex()].add(isoMovingObject);
            // pzopt: separateParallel. The bucket a level holds this frame is the one whose index matches the object's
            // id, so this is exactly the set update() will walk; their separation is computed on the workers first.
            if (pzoptSeparateBatch && isoMovingObject instanceof IsoZombie zombieForSeparate) {
               int frameMod = sim.getFrameMod();
               if (isoMovingObject.getID() % frameMod == (int)(this.frameCounter % (long)frameMod)) {
                  pzopt.SeparateBatch.add(zombieForSeparate);
               }
            }
         }
      }
   }

   /**
    * pzopt: schedulerClassifyParallel, a frame worker: this object's simulation level as the loop in startFrame computes
    * it, or null when computing it here could write (a character whose animation player the body-model check would
    * replace): the game thread classifies that one itself.
    */
   public UpdateSchedulerSimulationLevel pzoptClassify(IsoMovingObject isoMovingObject, float averageFps) { // pzopt
      if (isoMovingObject instanceof zombie.characters.IsoGameCharacter chr && chr.pzoptAnimPlayerStale()) { // pzopt
         return null; // pzopt
      } // pzopt
      return this.getUpdateSchedulerSimulationLevelForObject(isoMovingObject, averageFps); // pzopt
   } // pzopt

   /** pzopt: schedulerClassifyParallel, game thread: the stock loop's classification of one object. */
   public UpdateSchedulerSimulationLevel pzoptClassifySerial(IsoMovingObject isoMovingObject, float averageFps) { // pzopt
      return this.getUpdateSchedulerSimulationLevelForObject(isoMovingObject, averageFps); // pzopt
   } // pzopt

   /** pzopt: schedulerClassifyParallel, game thread, in the loop's order: the rest of the stock loop body for one object. */
   public void pzoptAdd(IsoMovingObject isoMovingObject, UpdateSchedulerSimulationLevel sim) { // pzopt
      this.simulationLevels[sim.getUpdateOrderIndex()].add(isoMovingObject); // pzopt
      if (pzopt.Config.INSTRUMENT && isoMovingObject instanceof IsoZombie) { // pzopt: instrument, zombies per simulation level
         this.pzoptLevelCount[sim.ordinal()]++; // pzopt
      } // pzopt
      if (pzoptSeparateBatch && isoMovingObject instanceof IsoZombie zombieForSeparate) { // pzopt: separateParallel, as in startFrame
         int frameMod = sim.getFrameMod(); // pzopt
         if (isoMovingObject.getID() % frameMod == (int)(this.frameCounter % (long)frameMod)) { // pzopt
            pzopt.SeparateBatch.add(zombieForSeparate); // pzopt
         } // pzopt
      } // pzopt
   } // pzopt

   private UpdateSchedulerSimulationLevel getUpdateSchedulerSimulationLevelForObject(IsoMovingObject isoMovingObject, float averageFps) {
      if (this.isEnabled && !GameServer.server) {
         UpdateSchedulerSimulationLevel minSim = isoMovingObject.getMinimumSimulationLevel();
         if (minSim == UpdateSchedulerSimulationLevel.FULL) {
            return minSim;
         }

         if (isoMovingObject.getDoRender() && !isoMovingObject.isSceneCulled()) {
            float distance = 1.0E8F;
            int levelSeparation = Integer.MAX_VALUE;
            float alpha = 0.0F;
            float targetAlpha = 0.0F;

            for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
               IsoPlayer player = IsoPlayer.players[playerIndex];
               if (player != null) {
                  if (player == isoMovingObject) {
                     return UpdateSchedulerSimulationLevel.FULL;
                  }

                  distance = PZMath.min(isoMovingObject.DistTo(player), distance);
                  levelSeparation = PZMath.min(PZMath.abs(isoMovingObject.getZi() - player.getZi()), levelSeparation);
                  alpha = PZMath.max(isoMovingObject.getAlpha(playerIndex), alpha);
                  targetAlpha = PZMath.max(isoMovingObject.getTargetAlpha(playerIndex), targetAlpha);
               }
            }

            UpdateSchedulerSimulationLevel sim = UpdateSchedulerSimulationLevel.FULL;
            float minAlpha = 0.25F;
            if (alpha < 0.25F && targetAlpha < 0.25F) {
               sim = sim.less();
               if (distance > 10.0F) {
                  sim = sim.less();
               }

               if (levelSeparation > 1) {
                  sim = minSim;
               }
            }

            if (distance > 30.0F) {
               sim = sim.less();
            }

            if (distance > 60.0F) {
               sim = sim.less();
               if (averageFps < 20.0F) {
                  sim = sim.less();
               }

               if (averageFps < 10.0F) {
                  sim = sim.less();
               }
            }

            if (distance > 80.0F) {
               sim = sim.less();
               if (averageFps < 20.0F) {
                  sim = sim.less();
               }
            }

            if (averageFps > 25.0F) {
               sim = sim.more();
            }

            if (averageFps > 35.0F) {
               sim = sim.more();
            }

            if (averageFps > 45.0F) {
               sim = sim.more();
            }

            if (averageFps > 55.0F) {
               sim = sim.more();
            }

            // pzopt: zombieSimLodTiles. Stock already drops a visible object's simulation level a step at 30, 60 and
            // 80 tiles from the nearest player; this is the same mechanism with one more step at a closer distance,
            // for zombies only, as an A/B of "simulate fewer of the horde per frame" (default 0 = stock).
            if (pzopt.Config.ZOMBIE_SIM_LOD_TILES > 0 && isoMovingObject instanceof IsoZombie && pzopt.Overrides.enabled() && pzopt.GtAb.on(pzopt.GtAb.SIM_LOD)) {
               for (int step = 0; step < pzopt.Config.ZOMBIE_SIM_LOD_STEPS; step++) {
                  if (distance <= (float)(pzopt.Config.ZOMBIE_SIM_LOD_TILES << step)) {
                     break; // each further step doubles the distance, like stock's own 30 / 60 / 80 ladder
                  }

                  sim = sim.less();
               }
            }

            return sim.max(minSim);
         } else {
            return minSim;
         }
      } else {
         return UpdateSchedulerSimulationLevel.FULL;
      }
   }

   public void update() {
      long pzoptT = pzopt.GtAb.begin(); // pzopt: devGtAlternate section timer
      try { // pzopt
         this.pzoptUpdate(); // pzopt
      } finally { // pzopt
         pzopt.GtAb.end(pzopt.GtAb.S_SCHED_UPDATE, pzoptT); // pzopt
      } // pzopt
   } // pzopt

   private void pzoptUpdate() { // pzopt: the stock body of update
      pzopt.FrameTick.next(); // pzopt: the frame stamp of the simulation memos (separateFast, allPlayersAsleep)
      boolean pzoptCombined = pzopt.UpdateBatch.latchFrameFromConfig(); // pzopt: entityUpdatePipeline -- the frame's shape, latched ONCE (a devPipelineAlternate flip then lands on a frame boundary instead of mixing one frame's buckets)
      if (this.pzoptSeparateBatch) {
         pzopt.SeparateBatch.run(); // pzopt: separateParallel, this frame's separations computed on the workers
      }

      pzopt.ZombieStats.begin(); // pzopt: zombieStatsFold, the zombies' distance statistics accumulated through the loop
      try { // pzopt
      try { // pzopt: entityUpdatePipeline -- see the finally: nothing may leave this method with a flight airborne or with queued entities unrun
         for (MovingObjectUpdateSchedulerUpdateBucket simulation : this.simulationLevels) {
            simulation.update((int)this.frameCounter);
         }
      } finally { // pzopt: entityUpdatePipeline
         try { // pzopt: entityUpdatePipeline
            if (pzoptCombined) { // pzopt: entityUpdatePipeline (combined dispatch, spec 2026-09-27)
               pzopt.UpdateBatch.dispatchCombined(); // pzopt: entityUpdatePipeline -- one workers-only flight for the whole frame
               pzopt.UpdateBatch.runInlinePhase(); // pzopt: entityUpdatePipeline -- the player and vehicles (animals too with animalsAfterJoin off) on the game thread WHILE that flight is airborne: this is the runway the per-bucket shape never had
            } // pzopt: entityUpdatePipeline
         } finally { // pzopt: entityUpdatePipeline
            pzopt.UpdateBatch.joinPending(); // pzopt: entityUpdatePipeline -- the batch is still airborne and nothing past this line may see a half-updated entity, so land it here before postupdate and the render read anything (the inner finally: a throwing inline entity must not leave a flight up for updateZombieVocals, which reads every zombie)
         } // pzopt: entityUpdatePipeline
      } // pzopt: entityUpdatePipeline
      if (pzoptCombined) { // pzopt: animalsAfterJoin -- outside every finally on purpose: reached only when the buckets, the inline phase and the join returned normally, so an exception from any of them propagates instead of being replaced by one from the herd
         pzopt.UpdateBatch.runAnimalPhase(); // pzopt: animalsAfterJoin -- the frame's animals, now that every zombie has landed: their sight walks read the horde directly instead of through the snapshot, and no worker writes what they read; still before postupdate and the vocal walks, which read animals
      } // pzopt: animalsAfterJoin
      } finally { // pzopt
         pzopt.ZombieStats.end(); // pzopt: zombieStatsFold, written back after the batch landed, the achievement check once per statistic
      } // pzopt
   }

   public void postupdate() {
      if (GameServer.server) {
         ZombieCountOptimiser.deleteZombies();
      }

      // pzopt: two batches ride on this loop (Config.actionEvalParallel, Config.animBonesParallel). The eligible zombies stop
      // their postUpdateAnimating after the turning flags and queue in pzopt.ActionEval; after the loop their transition
      // evaluation runs on the frame workers and the rest of their postUpdateAnimating runs here in loop order, which
      // queues their bone math in pzopt.AnimBatch; that batch runs last, joined before anything reads a bone.
      boolean pzoptBatch = !GameServer.server && pzopt.Overrides.enabled();
      if (pzoptBatch) {
         pzopt.AnimBatch.begin();
         pzopt.ActionEval.begin();
      }
      long pzoptPu = pzopt.GtAb.begin(); // pzopt: Louisville 120 plan B census, the bucket loop and the flush timed apart
      if (pzoptBatch) { // pzopt: postupdateParallel
         pzopt.PostupdateBatch.prepass(this.simulationLevels, (int)this.frameCounter); // pzopt: postupdateParallel — the eligible zombies' movement on the workers, committed in the loop
         pzopt.GtAb.end(pzopt.GtAb.S_PU_MOVE, pzoptPu); // pzopt: Louisville 120 plan B census
         pzoptPu = pzopt.GtAb.begin(); // pzopt: Louisville 120 plan B census
      } // pzopt: postupdateParallel
      try {
         for (MovingObjectUpdateSchedulerUpdateBucket simulation : this.simulationLevels) {
            simulation.postupdate((int)this.frameCounter);
         }
      } finally {
         if (pzoptBatch) { // pzopt: postupdateParallel
            pzopt.PostupdateBatch.finish(); // pzopt: postupdateParallel — a computed zombie the loop never reached is put back
         } // pzopt: postupdateParallel
         pzopt.GtAb.end(pzopt.GtAb.S_PU_LOOP, pzoptPu); // pzopt: Louisville 120 plan B census
         pzoptPu = pzopt.GtAb.begin(); // pzopt: Louisville 120 plan B census
         if (pzoptBatch) {
            try {
               pzopt.ActionEval.flush();
            } finally {
               pzopt.AnimBatch.flush();
            }
         }
         pzopt.GtAb.end(pzopt.GtAb.S_PU_FLUSH, pzoptPu); // pzopt: Louisville 120 plan B census
      }

      if (pzopt.SimChecksum.ENABLED) {
         pzopt.SimChecksum.frameDone(); // pzopt: devSimChecksum, the per-frame hash of every zombie's state
      }
   }

   public boolean isEnabled() {
      return this.isEnabled;
   }

   public void setEnabled(boolean enabled) {
      this.isEnabled = enabled;
   }

   public void removeObject(IsoMovingObject object) {
      PZArrayUtil.forEach(this.simulationLevels, object, MovingObjectUpdateSchedulerUpdateBucket::removeObject);
   }
}
