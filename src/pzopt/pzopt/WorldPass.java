package pzopt;

import zombie.core.PerformanceSettings;

/**
 * Did our world pass (the FBORenderCell override) draw this frame? A mod that replaces {@code IsoCell.render} with a renderer
 * of its own (Project Viewpoint's first / third person) leaves no world of ours to post-process, so the jobs
 * {@code MultiTextureFBO2.render} queues for it (the upscaler's resolve, HDR's world statistics, god rays' late draw, ...)
 * are skipped in those frames. They did nothing useful there, and Viewpoint 0.1.7 draws none of its in-between frames while a
 * frame holds a render job it does not know: with one of them in every frame the game showed its 30 fixed ticks a second
 * (Workshop reports, 2026-11-20). Game thread only.
 */
public final class WorldPass {
   private static boolean drawn;

   private WorldPass() {
   }

   /** FBORenderCell.renderInternal: the world pass of this frame runs. */
   public static void drawn() {
      drawn = true;
   }

   /** MultiTextureFBO2.render, once per frame: whether the world pass ran since the last call (always true off the FBO chunk renderer). */
   public static boolean take() {
      boolean d = drawn || !PerformanceSettings.fboRenderChunk || !Overrides.enabled();
      drawn = false;
      return d;
   }
}
