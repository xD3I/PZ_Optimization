package pzopt;

/**
 * {@code bodyAtlasPrewarm} (f9-burst, 2026-10-15, Louisville 120 fps: the first-sight burst). A zombie drawn as a flat sprite
 * (the zombie LOD's atlas zombies, past the 3D cap) takes its picture from DeadBodyAtlas's generic zombie bodies: one of
 * 128 textures per (female / male, idle / walktoward, direction, animation frame 0-3), each made the first time any zombie
 * needs it (IsoZombie.renderTextureInsteadOfModel -> DeadBodyAtlas.getBodyTexture: a render job that poses the generic
 * zombie model, DeadBodyAtlas.render's RenderJob.renderMain on the game thread, then a draw into the atlas). On the
 * Louisville route nearly all of them were made in the frames the horde first came into view (~30 ms of game thread in
 * route s1.5-3.0, on top of every other first-sight cost), and a zombie whose texture was not ready yet was not drawn.
 *
 * <p>Here the same 128 calls are made once the world exists, a few per frame ({@code bodyAtlasPrewarmPerFrame}) from the
 * render phase right before DeadBodyAtlas.render (IsoWorld), after {@code bodyAtlasPrewarmDelayFrames} frames. Exact: the
 * generic body parameters carry no world state (BodyParams.init with a human visual: ambient 1, no lights, outside, the
 * shared CharacterTextureVisual of each sex), the trackTime is the one renderTextureInsteadOfModel computes for that frame,
 * and the result is the cached texture the later call returns. Only the moment of making them changes (and the atlas slot
 * each later entry lands in).
 */
public final class BodyAtlasPrewarm {
   private BodyAtlasPrewarm() {
   }

   private static final int COUNT = 2 * 2 * 8 * 4;
   private static int next;
   private static int frames;
   public static long made;

   /** Game thread, render phase, right before DeadBodyAtlas.instance.render() (IsoWorld). */
   public static void tick() {
      if (!Config.BODY_ATLAS_PREWARM || next >= COUNT || !Overrides.enabled()) {
         return;
      }
      if (++frames < Config.BODY_ATLAS_PREWARM_DELAY_FRAMES) {
         return;
      }
      try {
         for (int k = 0; k < Config.BODY_ATLAS_PREWARM_PER_FRAME && next < COUNT; k++, next++) {
            int i = next;
            boolean female = (i & 1) != 0;
            boolean moving = (i >> 1 & 1) != 0;
            int frame = i >> 2 & 3;
            zombie.iso.IsoDirections dir = zombie.iso.IsoDirections.fromIndex(i >> 4 & 7);
            float trackTime = (moving ? 0.67F : 1.0F) * (frame / 4.0F); // IsoZombie.renderTextureInsteadOfModel's
            zombie.core.skinnedmodel.DeadBodyAtlas.instance.getBodyTexture(female, "zombie", moving ? "walktoward" : "idle", dir, frame, trackTime);
            made++;
         }
         if (next >= COUNT) {
            Log.info("body atlas prewarm: " + made + " generic zombie bodies requested");
         }
      } catch (Throwable t) {
         next = COUNT;
         Log.warn("body atlas prewarm: " + t);
      }
   }
}
