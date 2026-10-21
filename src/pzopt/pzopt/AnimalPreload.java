package pzopt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.ZomboidFileSystem;
import zombie.characters.action.ActionGroup;

/**
 * {@code animalPreload} (tail pass 2026-10-13): an animal type's first appearance in a session parsed its animation set
 * and its action group (a folder of XML state files) on the game thread, in the chunk hand-off that spawned it
 * ({@code IsoChunk.addRatsAfterLoading -> IsoAnimal.initType -> ActionGroup.getActionGroup -> ActionGroup.load}): 15-37 ms
 * frames on the Louisville route, part of it file I/O (run t1-b4-intended, +5.5 s). Both are caches the game fills on first
 * use, so they are filled ahead here instead, from the loading screen on, on a background thread:
 * <ul>
 * <li>the animation sets through {@code AnimationSet.GetAnimationSet}, which our override makes synchronized (the boot
 * preload of "player" / "zombie" does the same);</li>
 * <li>the action groups are built privately (ActionGroup's own constructor and load, the condition factories registered
 * first by ActionContext's class initialization) and handed to ActionGroup's map on the game thread at a frame start
 * ({@link #drain}), only for a name the game has not loaded itself in the meantime. A group's load reads only its own
 * files and the read-only condition factories, so the object is the one the game's own lazy load would have built.</li>
 * </ul>
 * The animal types are the folders of media/actiongroups other than the player, zombie, mannequin and editor ones.
 */
public final class AnimalPreload {
   private AnimalPreload() {
   }

   public static final boolean ON = Config.ANIMAL_PRELOAD && Overrides.enabled();
   private static final ConcurrentLinkedQueue<Object[]> READY = new ConcurrentLinkedQueue<>();
   private static volatile boolean started;
   public static volatile int animSets, groups;
   public static int inserted, skipped;
   private static Map<String, Object> groupMap;
   private static boolean mapFailed;

   /** Loading screen start (GameLoadingState): parse every animal type's animation set and action group off the game thread. */
   public static void start() {
      if (!ON || started) {
         return;
      }
      started = true;
      Thread t = new Thread(AnimalPreload::run, "pzopt-animal-preload");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
   }

   private static void run() {
      long t0 = System.nanoTime();
      try {
         Class.forName("zombie.characters.action.ActionContext", true, AnimalPreload.class.getClassLoader()); // the condition factories
         File dir = ZomboidFileSystem.instance.getMediaFile("actiongroups");
         File[] kids = dir.listFiles(File::isDirectory);
         if (kids == null) {
            return;
         }
         ArrayList<String> names = new ArrayList<>();
         for (File k : kids) {
            String n = k.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.startsWith("player") || n.startsWith("zombie") || n.contains("editor") || n.startsWith("mannequin")) {
               continue;
            }
            names.add(n);
         }
         java.util.Collections.sort(names);
         Constructor<ActionGroup> ctor = ActionGroup.class.getDeclaredConstructor();
         ctor.setAccessible(true);
         Field nameField = ActionGroup.class.getDeclaredField("name");
         nameField.setAccessible(true);
         Method load = ActionGroup.class.getDeclaredMethod("load");
         load.setAccessible(true);
         for (String n : names) {
            try {
               if (ZomboidFileSystem.instance.getMediaFile("AnimSets/" + n).isDirectory()) {
                  zombie.core.skinnedmodel.advancedanimation.AnimationSet.GetAnimationSet(n, false);
                  animSets++;
               }
            } catch (Throwable t) {
               Log.warn("animalPreload: animation set " + n + ": " + t);
            }
            try {
               ActionGroup g = ctor.newInstance();
               nameField.set(g, n);
               load.invoke(g);
               READY.add(new Object[] {n, g});
               groups++;
            } catch (Throwable t) {
               Log.warn("animalPreload: action group " + n + ": " + t);
            }
         }
         Log.info(String.format("animalPreload: %d animation sets, %d action groups parsed in %.2f s (%s)", animSets, groups,
            (System.nanoTime() - t0) / 1e9, String.join(",", names)));
      } catch (Throwable t) {
         Log.warn("animalPreload: " + t);
      }
   }

   /** Game thread, every frame start: hands finished action groups to ActionGroup's map unless the game loaded the name already. */
   @SuppressWarnings("unchecked")
   public static void drain() {
      if (!ON || READY.isEmpty() || mapFailed) {
         return;
      }
      Thread loader = zombie.gameStates.GameLoadingState.loader;
      if (loader != null && loader.isAlive() || zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null) {
         return; // the world loader thread may be creating animals (and reading ActionGroup's map) right now
      }
      if (groupMap == null) {
         try {
            Field f = ActionGroup.class.getDeclaredField("s_actionGroupMap");
            f.setAccessible(true);
            groupMap = (Map<String, Object>) f.get(null);
         } catch (Throwable t) {
            mapFailed = true;
            Log.warn("animalPreload: ActionGroup map not reachable (" + t + "); the game loads its groups itself");
            READY.clear();
            return;
         }
      }
      Object[] e;
      while ((e = READY.poll()) != null) {
         String k = (String) e[0];
         if (groupMap.containsKey(k)) {
            skipped++;
         } else {
            groupMap.put(k, e[1]);
            inserted++;
         }
      }
      if (READY.isEmpty() && groups > 0 && inserted + skipped >= groups) {
         Log.info("animalPreload: " + inserted + " action groups handed to the game, " + skipped + " it had loaded first");
      }
   }
}
