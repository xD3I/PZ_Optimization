package pzopt;

import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;

/**
 * Ground and grass, shared part (2026-10-24): which sprites and squares are natural ground, and of what material.
 *
 * <p><b>What it gives.</b> {@link #of(IsoSprite)}: a sprite's ground code (material class in the low 4 bits, a tone /
 * variant in bits 4-7, flags above), looked up once by name and cached on the sprite ({@code IsoSprite.pzoptGround}): a
 * plain field read after the first call, safe from any thread (two threads that race compute the same value). {@link
 * #square(IsoGridSquare)}: the square's code (its floor's material, plus {@link #F_OVERLAY} when grass / erosion plants lie
 * on it, {@link #F_EXCLUDED} for farmland, dug soil and anything not ground), reads only; {@link #mapByte(int)} packs a code
 * into the byte {@link GroundBake}'s class map holds for the shaders.
 *
 * <p><b>The tile sheets</b> (B42.21, from the game's own rules: IsoGridSquare.hasSand / hasDirt, FMODFootstep,
 * ParameterVehicleRoadMaterial, ISWorldObjectContextMenuLogic's shovel table, NatureGeneric, FBORenderSnow):
 * <ul>
 *   <li>{@code blends_natural_01_N}: rows of 16 by material: 0-15 sand, 16-63 grass in three tones (16-31 light, 32-47
 *       medium, 48-63 dark; IsoChunk maps floors_exterior_natural_01_0/1/2 to 16/32/48), 64-79 dirt, 80-95 dark dirt, 96-111
 *       clay. In each row 0, 5, 6, 7 are whole squares ({@link #F_BASE}), the rest the alpha edge pieces that blend a
 *       material over its neighbour ({@link #F_BLEND}). Rows past 111 are left out (not ground we know).</li>
 *   <li>{@code floors_exterior_natural_01_N} (older maps): 0 / 1 / 2 grass tones, 13 gravel, 16-19 dirt, 24 sand.</li>
 *   <li>{@code blends_street_01_48 / 53 / 54 / 55}: gravel (the rest of the street sheet is road: not ground).</li>
 *   <li>{@code blends_grassoverlays_01}, {@code e_newgrass_1} (erosion grass), {@code d_generic_1} (erosion's dry plants),
 *       {@code vegetation_groundcover_01}: plants lying on a floor ({@link #GRASS_OVERLAY} / {@link #VEGETATION}, flag
 *       {@link #F_OVERLAY}).</li>
 *   <li>{@code e_newsnow_ground_1}: the snow pass's textures ({@link #SNOW}, overlay).</li>
 *   <li>{@code blends_natural_02}: water and shore: excluded (the water pass owns it).</li>
 *   <li>{@code vegetation_farm}, {@code farming_*}, a floor carrying {@code blends_natural_01_87} (dug soil): farmland,
 *       {@link #F_EXCLUDED} for the square. Player-built floors are other sheets and never ground.</li>
 * </ul>
 *
 * <p><b>How a feature uses it.</b> CPU rules: {@code int c = GroundTiles.square(sq); if (GroundTiles.natural(c)) ...
 * GroundTiles.material(c)}. Shaders: the class map ({@link GroundBake#GLSL_CLASS}, {@code pzgClassAt}) holds
 * {@link #mapByte} of every loaded square at level 0.
 */
public final class GroundTiles {
   private GroundTiles() {
   }

   /** Materials (the low 4 bits of a code). NONE: not natural ground. */
   public static final int NONE = 0, GRASS = 1, DIRT = 2, SAND = 3, GRAVEL = 4, SNOW = 5, GRASS_OVERLAY = 6, VEGETATION = 7, CLAY = 8,
      DARK_DIRT = 9;
   public static final String[] MATERIAL_NAMES = {"none", "grass", "dirt", "sand", "gravel", "snow", "grassOverlay", "vegetation", "clay", "darkDirt"};
   /** Flags. */
   public static final int F_BASE = 1 << 8, F_BLEND = 1 << 9, F_OVERLAY = 1 << 10, F_EROSION = 1 << 11, F_EXCLUDED = 1 << 12, F_FARM = 1 << 13;
   /**
    * Paved floors (material {@link #NONE}: never natural ground): {@link #F_ROAD} asphalt road ({@code blends_street_01} past
    * its gravel pieces), {@link #F_WALK} sidewalk / pavement ({@code blends_street_01_64..79}, {@code floors_exterior_street},
    * {@code floors_exterior_tilesandstone}, sheets named {@code sidewalk}, {@code street_trafficlines}); wet-asphalt glints,
    * cover that keeps off the road. Upstreamed from ground cover's own name rules (2026-10-24).
    */
   public static final int F_ROAD = 1 << 14, F_WALK = 1 << 15;
   private static final int KNOWN = 1 << 30; // the cache's "looked up" bit

   public static int material(int code) {
      return code & 15;
   }

   /** The tone / variant within the material (grass: 0 light, 1 medium, 2 dark; else 0). */
   public static int tone(int code) {
      return (code >> 4) & 15;
   }

   /** 1 road, 2 sidewalk / pavement, 0 neither (see {@link #F_ROAD}). */
   public static int paved(int code) {
      return (code & F_ROAD) != 0 ? 1 : (code & F_WALK) != 0 ? 2 : 0;
   }

   /** A floor of natural ground that a feature may draw over (not excluded, not an overlay plant alone). */
   public static boolean natural(int code) {
      int m = code & 15;
      return m != NONE && m != GRASS_OVERLAY && m != VEGETATION && m != SNOW && (code & F_EXCLUDED) == 0;
   }

   public static long lookups;

   /** The sprite's ground code (cached on the sprite). */
   public static int of(IsoSprite s) {
      if (s == null) {
         return 0;
      }
      int c = s.pzoptGround;
      if (c == 0) {
         c = classify(s.name, s) | KNOWN;
         s.pzoptGround = c;
         lookups++;
      }
      return c & ~KNOWN;
   }

   /** The code a sprite name gets (the cache's work; public for tests and the offline tools). */
   public static int classify(String name, IsoSprite s) {
      if (name == null) {
         return 0;
      }
      int us = name.lastIndexOf('_');
      int idx = -1;
      if (us > 0) {
         try {
            idx = Integer.parseInt(name.substring(us + 1));
         } catch (NumberFormatException e) {
            idx = -1;
         }
      }
      String sheet = us > 0 ? name.substring(0, us) : name;
      if (s != null) {
         try {
            if (s.getProperties().has(IsoFlagType.water)) {
               return F_EXCLUDED;
            }
         } catch (Throwable t) {
            // (no properties: the name decides)
         }
      }
      switch (sheet) {
         case "blends_natural_01": {
            if (idx < 0) {
               return 0;
            }
            int row = idx / 16, k = idx % 16;
            int base = (k == 0 || k == 5 || k == 6 || k == 7) ? F_BASE : F_BLEND;
            if (idx == 87) {
               return DARK_DIRT | F_BASE | F_FARM; // the dug-soil piece (IsoGridSquare.dirtStamp attaches it)
            }
            switch (row) {
               case 0:
                  return SAND | base;
               case 1:
               case 2:
               case 3:
                  return GRASS | ((row - 1) << 4) | base;
               case 4:
                  return DIRT | base;
               case 5:
                  return DARK_DIRT | base;
               case 6:
                  return CLAY | base;
               default:
                  return 0;
            }
         }
         case "floors_exterior_natural_01":
            if (idx >= 0 && idx <= 2) {
               return GRASS | (idx << 4) | F_BASE;
            }
            if (idx == 13) {
               return GRAVEL | F_BASE;
            }
            if (idx >= 16 && idx <= 19) {
               return DIRT | F_BASE;
            }
            if (idx == 24) {
               return SAND | F_BASE;
            }
            return 0;
         case "blends_street_01":
            return idx == 48 || idx == 53 || idx == 54 || idx == 55 ? GRAVEL | F_BASE : idx >= 64 && idx < 80 ? F_WALK : F_ROAD;
         case "blends_grassoverlays_01":
            return GRASS_OVERLAY | F_OVERLAY;
         case "e_newgrass_1":
            return GRASS_OVERLAY | F_OVERLAY | F_EROSION;
         case "d_generic_1":
         case "vegetation_groundcover_01":
            return VEGETATION | F_OVERLAY | (sheet.charAt(0) == 'd' ? F_EROSION : 0);
         case "e_newsnow_ground_1":
            return SNOW | F_OVERLAY | F_EROSION;
         case "blends_natural_02":
            return F_EXCLUDED; // water, shore
         default:
            if (sheet.startsWith("vegetation_farm") || sheet.startsWith("farming_")) {
               return F_EXCLUDED | F_FARM;
            }
            if (sheet.startsWith("floors_exterior_street") || sheet.startsWith("floors_exterior_tilesandstone") || sheet.contains("sidewalk")
               || sheet.startsWith("street_trafficlines")) {
               return F_WALK;
            }
            return 0;
      }
   }

   /**
    * The square's code: its floor's material and flags, {@link #F_OVERLAY} when plants lie on it, {@link #F_EXCLUDED} on
    * farmland or dug soil. 0 for a square without a natural floor. Reads only (any thread; the objects list may change under
    * a worker: a stale answer there only costs a re-bake).
    */
   public static int square(IsoGridSquare sq) {
      if (sq == null) {
         return 0;
      }
      IsoObject floor = sq.getFloor();
      if (floor == null) {
         return 0;
      }
      int code = of(floor.sprite);
      if ((code & 15) == NONE) {
         return code & (F_EXCLUDED | F_ROAD | F_WALK);
      }
      java.util.ArrayList<IsoSpriteInstance> att = floor.getAttachedAnimSprite();
      if (att != null) {
         for (int i = 0; i < att.size(); i++) {
            IsoSpriteInstance inst = att.get(i);
            int a = inst == null ? 0 : of(inst.parentSprite);
            if ((a & F_FARM) != 0) {
               code |= F_EXCLUDED | F_FARM;
            } else if ((a & F_OVERLAY) != 0) {
               code |= F_OVERLAY;
            }
         }
      }
      IsoObject[] objs = (IsoObject[])sq.getObjects().getElements();
      int n = sq.getObjects().size();
      for (int i = 0; i < n; i++) {
         IsoObject o = objs[i];
         if (o == null || o == floor) {
            continue;
         }
         int a = of(o.sprite);
         if ((a & F_FARM) != 0) {
            code |= F_EXCLUDED | F_FARM;
         } else if ((a & F_OVERLAY) != 0) {
            code |= F_OVERLAY;
         }
      }
      return code;
   }

   /** Surfaces for what presses into the ground (GroundState's cells, the tracks' rules): 0 none, then as named. */
   public static final int SURF_NONE = 0, SURF_GRASS = 1, SURF_DIRT = 2, SURF_SAND = 3, SURF_GRAVEL = 4, SURF_SNOW = 5;

   /**
    * The square's surface at level 0 for tracks: its natural floor's material (grass; dirt, dark dirt and clay as dirt; sand;
    * gravel), else its floor's FloorMaterial / FootstepMaterial property (Grass, Dirt, Clay, Sand, Gravel: grass sheets the
    * name rules do not know), {@link #SURF_SNOW} over any of them when {@code snow}; farmland, water and built floors none.
    */
   public static int surface(IsoGridSquare sq, boolean snow) {
      int code = square(sq);
      if ((code & F_EXCLUDED) != 0) {
         return SURF_NONE;
      }
      int s;
      switch (code & 15) {
         case GRASS:
            s = SURF_GRASS;
            break;
         case DIRT:
         case DARK_DIRT:
         case CLAY:
            s = SURF_DIRT;
            break;
         case SAND:
            s = SURF_SAND;
            break;
         case GRAVEL:
            s = SURF_GRAVEL;
            break;
         default:
            s = sq == null ? SURF_NONE : byProperty(sq.getFloor());
            break;
      }
      return s != SURF_NONE && snow ? SURF_SNOW : s;
   }

   private static int byProperty(IsoObject floor) {
      if (floor == null || floor.sprite == null) {
         return SURF_NONE;
      }
      try {
         zombie.core.properties.PropertyContainer p = floor.getProperties();
         String fm = p == null ? null : p.get(zombie.core.properties.IsoPropertyType.FLOOR_MATERIAL);
         if (fm == null && p != null) {
            fm = p.get(zombie.core.properties.IsoPropertyType.FOOTSTEP_MATERIAL);
         }
         if (fm == null) {
            return SURF_NONE;
         }
         switch (fm) {
            case "Grass":
               return SURF_GRASS;
            case "Dirt":
            case "Clay":
               return SURF_DIRT;
            case "Sand":
               return SURF_SAND;
            case "Gravel":
               return SURF_GRAVEL;
            default:
               return SURF_NONE;
         }
      } catch (Throwable t) {
         return SURF_NONE;
      }
   }

   /** The class map's byte of a code: material (4 bits), base (bit 4), blend (bit 5), overlay (bit 6), excluded (bit 7). */
   /** The class map's second byte: the tone (low 4 bits), 16 erosion, 32 farm, 64 road, 128 sidewalk (GLSL pzgToneAt / pzgToneByteAt). */
   public static int toneByte(int code) {
      return ((code >> 4) & 15) | ((code & F_EROSION) != 0 ? 16 : 0) | ((code & F_FARM) != 0 ? 32 : 0) | ((code & F_ROAD) != 0 ? 64 : 0)
         | ((code & F_WALK) != 0 ? 128 : 0);
   }

   public static int mapByte(int code) {
      return (code & 15) | ((code & F_BASE) != 0 ? 16 : 0) | ((code & F_BLEND) != 0 ? 32 : 0) | ((code & F_OVERLAY) != 0 ? 64 : 0)
         | ((code & F_EXCLUDED) != 0 ? 128 : 0);
   }
}
