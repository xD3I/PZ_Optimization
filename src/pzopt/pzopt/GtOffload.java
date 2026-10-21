package pzopt;

/** The game-thread offload pass's counters in one line (2026-09-27): the harness summary's {@code gt_offload=}. */
public final class GtOffload {
   private GtOffload() {
   }

   public static String describe() {
      return RenderPrep.describe() + " | " + PixelLight.packDescribe() + " | " + SchedulerClassify.describe() + " | " + PlayerLosBatch.describe() + " | " + ZombieStats.describe() + " | " + LosPrefetch.describe() + " | " + VisPolyAsync.describe() + " | aoMasks batches=" + ChunkAo.maskBatches + " jobs=" + ChunkAo.maskJobs + " | " + TranslucentOrder.describe();
   }
}
