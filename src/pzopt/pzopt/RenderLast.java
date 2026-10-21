package pzopt;

/**
 * renderLastFast (f8-render1, 2026-10-13): IsoGameCharacter.renderlast walks the character's whole inventory every frame
 * to call {@code renderlast()} on its {@code IUpdater} items. Every IUpdater of the game (HandWeapon, WeaponPart, Radio,
 * DrainableComboItem, all final) has an empty renderlast (GameEntity's, or its own empty one), so the walk draws and
 * changes nothing; on the Louisville horde it was ~0.2 ms of the game thread's render phase (2,500 zombie inventories, a
 * cache miss per item). A Java mod could add an IUpdater with a real renderlast: with any Java mod in the launch (or the
 * mod scan off / failed) the walk stays.
 */
public final class RenderLast {
   private RenderLast() {
   }

   private static final boolean SAFE = vanillaEmpty() && !ModCompat.javaModsPossible();

   static {
      Log.info("renderLastFast: " + (!Config.RENDER_LAST_FAST ? "off (key)" : SAFE ? "on" : "off (Java mods possible or an IUpdater draws in renderlast)"));
   }

   private static boolean vanillaEmpty() {
      try {
         Class<?>[] cs = {zombie.inventory.types.HandWeapon.class, zombie.inventory.types.WeaponPart.class, zombie.inventory.types.Radio.class,
            zombie.inventory.types.DrainableComboItem.class};
         for (Class<?> c : cs) {
            Class<?> d = c.getMethod("renderlast").getDeclaringClass();
            if (d != zombie.entity.GameEntity.class && d != zombie.inventory.types.Radio.class && d != zombie.inventory.types.DrainableComboItem.class) {
               return false;
            }
         }
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   /** IsoGameCharacter.renderlast: skip the inventory's IUpdater walk this frame. */
   public static boolean skipUpdaters() {
      return Config.RENDER_LAST_FAST && SAFE && Overrides.enabled() && GtAb.on(GtAb.RENDER_LAST);
   }
}
