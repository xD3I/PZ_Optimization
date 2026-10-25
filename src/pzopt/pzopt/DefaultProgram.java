package pzopt;

import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * The game's default sprite program back after a raw-GL pass (2026-10-25, the maintainer's tree flicker).
 *
 * <p>The sprite renderer's batches bind no program: they draw with whatever is bound and expect the game's default
 * ({@code SceneShaderStore.defaultShaderId}, which {@code ShaderHelper.glUseProgramObjectARB(0)} binds). A pass that ended
 * with {@code glUseProgram(0)} + {@code forgetCurrentlyBound()} left the fixed-function pipeline bound until the next
 * {@code glUseProgramObjectARB} call. GroundState's compute (frame start, on frames the player stamps the ground) did that
 * right before {@code IsoCell.drawStencilMask}; the cutaway mask's quad then drew without the default program and wrote
 * no stencil on about half of those frames, so the see-through tree round the player was drawn whole for that frame
 * (runs tf-i-stencil*: 56 of 56 crown pops on frames whose mask was lost).
 */
public final class DefaultProgram extends TextureDraw.GenericDrawer {
   private static final DefaultProgram BEFORE_MASK = new DefaultProgram();

   private DefaultProgram() {
   }

   /** Render thread: end a raw-GL pass with the game's default program bound and ShaderHelper in step with it. */
   public static void restore() {
      ShaderHelper.forgetCurrentlyBound();
      ShaderHelper.glUseProgramObjectARB(0);
   }

   /** Game thread: right before IsoCell.drawStencilMask, the default program bound whatever ran before it in the stream. */
   public static void beforeStencilMask() {
      if (Config.STENCIL_MASK_PROGRAM && Overrides.enabled()) {
         SpriteRenderer.instance.drawGeneric(BEFORE_MASK);
      }
   }

   @Override
   public void render() {
      restore();
   }
}
