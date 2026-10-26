package pzopt;

import java.lang.reflect.Field;
import java.util.HashMap;
import zombie.Lua.MapObjects;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoWorldInventoryObject;

/**
 * {@code mapObjectsFast} (lua-stream, 2026-10-25, idea 3). Build 42's MapObjects (the OnNewWithSprite / OnLoadWithSprite
 * registry the vanilla Map/MapObjects Lua uses) is already Java: a HashMap from sprite name to the Lua callbacks, and
 * {@code MapObjects.newGridSquare / loadGridSquare} call Lua only for an object whose sprite name is registered. What is
 * left per square is the walk itself: a copy of the object list, a {@code contains} over the list per object and the map
 * lookup, for every square of every chunk that joins the world.
 *
 * <p>With the key on, the hand-off's square step asks first whether any object of the square has a registered sprite
 * name; only then does the stock method run (and it does everything as before). Exact: before the stock loop's first
 * callback nothing has changed the list, so its {@code contains} test is true for every object and the objects it
 * would call a callback for are exactly the ones found here (not a world inventory object, a sprite, a name: the
 * sprite's name or else the object's spriteName, registered). No candidate: the stock method calls nothing, writes
 * nothing but its own scratch list (cleared at its next call), so skipping it is the same. {@code devMapObjectsCheck}
 * runs the stock decision next to this one on every square and counts the differences.
 */
public final class LuaStreamMapObjects {
   private LuaStreamMapObjects() {
   }

   private static HashMap<String, ?> onNew, onLoad;
   private static boolean failed;
   public static long squares, candidates, mismatches, fastNs, stockNs; // the two decisions' times: devMapObjectsCheck only

   @SuppressWarnings("unchecked")
   private static boolean init() {
      if (onLoad != null) {
         return true;
      }
      if (failed) {
         return false;
      }
      try {
         Field fn = MapObjects.class.getDeclaredField("onNew");
         Field fl = MapObjects.class.getDeclaredField("onLoad");
         fn.setAccessible(true);
         fl.setAccessible(true);
         onNew = (HashMap<String, ?>)fn.get(null);
         onLoad = (HashMap<String, ?>)fl.get(null);
         return true;
      } catch (Throwable t) {
         failed = true;
         Log.warn("map objects fast: MapObjects' registries not found (" + t + "): the stock walk runs");
         return false;
      }
   }

   /** True when the stock MapObjects walk of this square (onNew when {@code isNew}, else onLoad) may call Lua. */
   public static boolean mayCall(IsoGridSquare square, boolean isNew) {
      if (!Config.MAP_OBJECTS_FAST || !Overrides.enabled() || !init()) {
         return true;
      }
      HashMap<String, ?> map = isNew ? onNew : onLoad;
      long t0 = Config.DEV_MAP_OBJECTS_CHECK ? System.nanoTime() : 0L;
      boolean any = false;
      if (!map.isEmpty() && square != null) {
         for (int i = 0; i < square.getObjects().size(); i++) {
            IsoObject obj = (IsoObject)square.getObjects().get(i);
            if (obj == null || obj instanceof IsoWorldInventoryObject || obj.sprite == null) {
               continue;
            }
            String name = obj.sprite.name == null ? obj.spriteName : obj.sprite.name;
            if (name != null && !name.isEmpty() && map.get(name) != null) {
               any = true;
               break;
            }
         }
      }
      squares++;
      if (any) {
         candidates++;
      }
      if (Config.DEV_MAP_OBJECTS_CHECK) {
         long t1 = System.nanoTime();
         fastNs += t1 - t0;
         boolean stock = square != null && stockDecision(square, map);
         stockNs += System.nanoTime() - t1;
         if (square != null && stock != any) {
            mismatches++;
            Log.warn("map objects fast check: square " + square.getX() + "," + square.getY() + "," + square.getZ() + " decided " + any + " (stock walk: " + !any + "), " + mismatches + " so far");
         }
      }
      return any;
   }

   private static final java.util.ArrayList<IsoObject> TEMP = new java.util.ArrayList<>();

   /** The stock walk's decisions up to its first callback, verbatim (copy, contains, the name, the lookup). */
   private static boolean stockDecision(IsoGridSquare square, HashMap<String, ?> map) {
      if (square.getObjects().isEmpty()) {
         return false;
      }
      TEMP.clear();
      for (int i = 0; i < square.getObjects().size(); i++) {
         TEMP.add((IsoObject)square.getObjects().get(i));
      }
      for (int i = 0; i < TEMP.size(); i++) {
         IsoObject obj = TEMP.get(i);
         if (!square.getObjects().contains(obj) || obj instanceof IsoWorldInventoryObject || obj == null || obj.sprite == null) {
            continue;
         }
         String spriteName = obj.sprite.name == null ? obj.spriteName : obj.sprite.name;
         if (spriteName != null && !spriteName.isEmpty() && map.get(spriteName) != null) {
            TEMP.clear();
            return true;
         }
      }
      TEMP.clear();
      return false;
   }
}
