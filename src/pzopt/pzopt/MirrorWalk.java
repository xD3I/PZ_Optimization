package pzopt;

import java.util.ArrayList;
import java.util.Locale;
import zombie.characters.IsoPlayer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;

/**
 * Harness scene {@code explore=mirror director=jev} (2026-10-04, the maintainer: "use Jev to walk around all the mirrors in
 * the house" of their latest save, to find every artifact in the reflections): every mirror tile of the building the save
 * starts in (all levels; wall mirrors, the map's mirror overlays on walls, cabinets, dressers) is listed, and Jev walks the
 * player on foot (the movement keys, never teleported; a grid path over the loaded squares with the staircases as edges,
 * doors opened on the way) from one mirror to the next. In front of each, a grid of stations: columns along the wall
 * ({@code mirror_cols}, half a square either side of the glass centre), rows out from it ({@code mirror_rows}, 1.0 / 1.8). At a
 * station Jev faces the mirror, turns his back to it, and once per mirror walks a small lap; then the next station, then
 * the next mirror. Done after the last one or at {@code mirror_secs} (10 + 25 s a mirror); {@code mirror_only=N} walks only
 * the N-th mirror (short fix iterations).
 *
 * <p>The state goes to {@code Zomboid/pzopt-explore-state.json} every 0.3 s ({@code "scene": "mirror"}),
 * harness/explore-director.py answers {@code <seq> <action>} in {@code pzopt-explore-cmd.txt}: left, right, closer, back,
 * face_mirror, turn_around, circle, next_mirror, hold, done. Every station / mirror change and director action is logged
 * with its epoch ms ({@code harness: mirror walk:}) so a devCapture can be cut per mirror and station
 * (harness/mirrors/walkframes.py).
 */
final class MirrorWalk {
   private MirrorWalk() {
   }

   static final String[] ACTIONS = {"left", "right", "closer", "back", "face_mirror", "turn_around", "circle", "next_mirror", "hold", "done"};

   /** A mirror of the house and its stations. */
   private static final class M {
      int x, y, z;
      boolean north;
      float off;
      String name;
      boolean[][] usable, visited, blockedAt, faced, turned;
      boolean circled, unreachable;
      float colShift, rowShift; // the station grid moved off furniture in front of the glass (stations())
      boolean visit; // mirror_corners=pair: not a mirror, the spot in the room south (vx, vy), facing north at the mirrors' room
      float vx, vy;
   }

   private static boolean on, finished;
   private static final ArrayList<M> mirrors = new ArrayList<>();
   private static float[] cols = {-1F, 0F, 1F}, rows = {0.8F, 1.5F, 2.2F};
   private static int mi, col, row, commands, commandSeq = -1, doorsOpened;
   private static String command = "hold";
   private static long startNs, lastStateNs, lastCmdCheckNs, lastLogNs, actionNs;
   private static float limitSecs = 300F;
   private static boolean arrived, travelling, selfTest;
   private static float[][] lap; // the circle's points (pathfinder-checked), walked one by one
   private static int lapIdx;
   private static java.io.File stateFile, cmdFile;
   private static String building = "none";

   static boolean active() {
      return on;
   }

   static boolean finished() {
      return finished;
   }

   static void worldReady(IsoPlayer p) {
      on = true;
      cols = floats(HarnessFlags.get("mirror_cols", "-0.5,0.5"));
      rows = floats(HarnessFlags.get("mirror_rows", "1.0,1.8"));
      java.io.File zd = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(zd, "pzopt-explore-state.json");
      cmdFile = new java.io.File(zd, "pzopt-explore-cmd.txt");
      cmdFile.delete();
      stateFile.delete();
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
   }

   private static float[] floats(String s) {
      String[] a = s.split(",");
      float[] r = new float[a.length];
      for (int i = 0; i < a.length; i++) r[i] = Float.parseFloat(a[i].trim());
      return r;
   }

   // ---- the house's mirrors ----

   static void routeStart(IsoPlayer p) {
      startNs = System.nanoTime();
      IsoCell cell = IsoWorld.instance.getCell();
      // mirror_corners=x,y,z (Harness): only the mirrors of that room are walked
      zombie.iso.RoomDef only4 = null;
      String mc = Harness.mirrorCornersRoom == null ? "" : Harness.mirrorCornersRoom;
      if (!mc.isEmpty()) {
         String[] xyz = mc.split(",");
         only4 = IsoWorld.instance.getMetaGrid().getRoomAt(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), xyz.length > 2 ? Integer.parseInt(xyz[2].trim()) : 0);
      }
      BuildingDef b = only4 == null ? null : only4.getBuilding(); // the corner room's building, wherever the player stands
      IsoGridSquare sq0 = p.getCurrentSquare();
      if (b == null && sq0 != null && sq0.getBuilding() != null) b = sq0.getBuilding().getDef();
      if (b == null) { // outdoors: the nearest building
         float best = Float.MAX_VALUE;
         for (BuildingDef d : IsoWorld.instance.getMetaGrid().getBuildings()) {
            float dx = Math.max(Math.max(d.getX() - p.getX(), p.getX() - d.getX2()), 0F), dy = Math.max(Math.max(d.getY() - p.getY(), p.getY() - d.getY2()), 0F);
            float dd = (float)Math.hypot(dx, dy);
            if (dd < best) {
               best = dd;
               b = d;
            }
         }
      }
      if (b == null) {
         Log.warn("harness: mirror walk: no building near the player; nothing to walk");
         finished = true;
         return;
      }
      building = b.getX() + "," + b.getY() + "-" + b.getX2() + "," + b.getY2();
      StringBuilder backs = new StringBuilder();
      for (int z = -1; z <= 7; z++) {
         for (int y = b.getY() - 1; y <= b.getY2() + 1; y++) {
            for (int x = b.getX() - 1; x <= b.getX2() + 1; x++) {
               IsoGridSquare sq = cell.getGridSquare(x, y, z);
               if (sq == null) continue;
               for (int i = 0; i < sq.getObjects().size(); i++) {
                  // the walk opens the doors on its way (the pathfinder's door check); a locked one would fail the leg
                  if (sq.getObjects().get(i) instanceof IsoDoor d && (d.isLocked() || d.isLockedByKey()) && !d.isBarricaded()) {
                     d.setLocked(false);
                     d.setLockedByKey(false);
                     doorsOpened++;
                  }
               }
               if (only4 != null && IsoWorld.instance.getMetaGrid().getRoomAt(x, y, z) != only4) continue;
               for (int i = 0; i < sq.getObjects().size(); i++) {
                  IsoObject o = sq.getObjects().get(i);
                  zombie.iso.sprite.IsoSprite spr = o.getSprite();
                  float[] info = Mirrors.mirrorTileInfo(spr);
                  if (info == null) {
                     zombie.iso.sprite.IsoSpriteInstance att = Mirrors.attachedMirrorTile(o);
                     if (att != null) {
                        spr = att.getParentSprite();
                        info = Mirrors.mirrorTileInfo(spr);
                     }
                  }
                  if (info == null && o.getSprite() != null) {
                     // a prop mirror (the gym's recreational_sports_01_76..79, 2026-10-16): no IsMirror, reflects through pzopt.Props
                     float[] pi = Props.info(o.getSprite());
                     if (pi != null && (int)pi[0] == Props.MIRROR && pi[3] >= 0F) {
                        spr = o.getSprite();
                        info = new float[] {pi[3], pi[4], pi[1]};
                     }
                  }
                  if (info == null) {
                     // a mirror the camera sees the back of (Facing N / W) or without a glass mask: listed, not walked to
                     if (spr != null && spr.getProperties() != null && spr.getProperties().has("IsMirror")) {
                        backs.append(' ').append(spr.getName()).append('@').append(x).append(',').append(y).append(',').append(z)
                              .append(" facing ").append(spr.getProperties().get("Facing"));
                     }
                     if (o.getAttachedAnimSprite() != null) {
                        for (zombie.iso.sprite.IsoSpriteInstance a : o.getAttachedAnimSprite()) {
                           zombie.iso.sprite.IsoSprite ps = a == null ? null : a.getParentSprite();
                           if (ps != null && ps.getProperties() != null && ps.getProperties().has("IsMirror")) {
                              backs.append(' ').append(ps.getName()).append("(overlay)@").append(x).append(',').append(y).append(',').append(z)
                                    .append(" facing ").append(ps.getProperties().get("Facing"));
                           }
                        }
                     }
                     continue;
                  }
                  M m = new M();
                  m.x = x;
                  m.y = y;
                  m.z = z;
                  m.north = info[0] == 0F;
                  m.off = info[1];
                  m.name = spr.getName();
                  stations(cell, m);
                  mirrors.add(m);
               }
            }
         }
      }
      // mirror_only=N (1-based, in the walking order below): one mirror per run, for short fix iterations
      int only = Integer.parseInt(HarnessFlags.get("mirror_only", "0").trim());
      // walking order: nearest first from the player, then nearest from each mirror
      ArrayList<M> left = new ArrayList<>(mirrors);
      mirrors.clear();
      float ax = p.getX(), ay = p.getY(), az = p.getZ();
      while (!left.isEmpty()) {
         int bi = 0;
         for (int i = 1; i < left.size(); i++) {
            if (dist(left.get(i), ax, ay, az) < dist(left.get(bi), ax, ay, az)) bi = i;
         }
         M m = left.remove(bi);
         mirrors.add(m);
         ax = m.x;
         ay = m.y;
         az = m.z;
      }
      if (only > 0 && only <= mirrors.size()) {
         M keep = mirrors.get(only - 1);
         mirrors.clear();
         mirrors.add(keep);
      }
      // mirror_pick=a,b,... (2026-10-16, the gym mirror on leaving its room): these mirrors of the walking order (1-based,
      // repeats allowed) in this order, each visit with fresh stations; 1,4,1 leaves a room for the next one and comes back
      String pick = HarnessFlags.get("mirror_pick", "").trim();
      if (!pick.isEmpty() && only <= 0) {
         ArrayList<M> order = new ArrayList<>(mirrors), seq = new ArrayList<>();
         for (String s : pick.split(",")) {
            int k = Integer.parseInt(s.trim());
            if (k < 1 || k > order.size()) continue;
            M o = order.get(k - 1), m = new M();
            m.x = o.x;
            m.y = o.y;
            m.z = o.z;
            m.north = o.north;
            m.off = o.off;
            m.name = o.name;
            stations(cell, m);
            seq.add(m);
         }
         mirrors.clear();
         mirrors.addAll(seq);
      }
      // mirror_laps=N (2026-10-08, the four-corner room): the mirrors walked N times, every other pass in the reverse order
      // (back and forth across the room), each pass with fresh stations and checks
      int laps = Integer.parseInt(HarnessFlags.get("mirror_laps", "1").trim());
      int perPass = mirrors.size();
      boolean visits = Harness.mirrorVisit != null && !mirrors.isEmpty();
      if (laps > 1 && mirrors.size() > 1) {
         ArrayList<M> pass = new ArrayList<>(mirrors);
         for (int l = 1; l < laps; l++) {
            java.util.Collections.reverse(pass);
            for (int i = visits ? 0 : 1; i < pass.size(); i++) {
               M o = pass.get(i), m = new M();
               m.x = o.x;
               m.y = o.y;
               m.z = o.z;
               m.north = o.north;
               m.off = o.off;
               m.name = o.name;
               stations(cell, m);
               mirrors.add(m);
            }
         }
      }
      if (visits) {
         // mirror_corners=pair: from the room south (the mirrors' room not seen yet) into the room and back, every pass
         ArrayList<M> seq = new ArrayList<>();
         seq.add(visitEntry(cell));
         for (int i = 0; i < mirrors.size(); i++) {
            seq.add(mirrors.get(i));
            if ((i + 1) % perPass == 0) seq.add(visitEntry(cell));
         }
         mirrors.clear();
         mirrors.addAll(seq);
      }
      String lim = HarnessFlags.get("mirror_secs", "").trim();
      limitSecs = lim.isEmpty() ? 10F + 25F * mirrors.size() : Float.parseFloat(lim);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < mirrors.size(); i++) {
         M m = mirrors.get(i);
         sb.append(String.format(Locale.ROOT, " #%d %s@%d,%d,%d %s (%d stations)", i + 1, m.name, m.x, m.y, m.z, m.north ? "north" : "west", count(m.usable)));
      }
      Log.info(String.format(Locale.ROOT, "harness: mirror walk: building %s, %d mirrors with glass the camera sees:%s; time limit %.0f s", building, mirrors.size(), sb, limitSecs));
      if (backs.length() > 0) Log.info("harness: mirror walk: mirrors seen from the back / without a glass mask (not reflecting):" + backs);
      if (mirrors.isEmpty()) {
         finished = true;
         return;
      }
      mi = 0;
      selfTest = "true".equals(HarnessFlags.get("nav_selftest", "false"));
      if (!selfTest) startTravel();
   }

   private static M visitEntry(IsoCell cell) {
      M v = new M();
      v.visit = true;
      v.vx = Harness.mirrorVisit[0];
      v.vy = Harness.mirrorVisit[1];
      v.x = (int)Math.floor(v.vx);
      v.y = (int)Math.floor(v.vy);
      v.z = (int)Harness.mirrorVisit[2];
      v.north = true;
      v.name = "visit";
      stations(cell, v);
      return v;
   }

   private static float dist(M m, float x, float y, float z) {
      return (float)Math.hypot(m.x - x, m.y - y) + Math.abs(m.z - z) * 8F;
   }

   /**
    * The station grid in front of m: where the player's body fits (the pathfinder's own test) in the mirror's room. A grid
    * with no such station (a table or a toilet in front of the glass) is moved out from the wall and sideways until one fits.
    */
   private static void stations(IsoCell cell, M m) {
      if (m.visit) {
         stationsAt(cell, m);
         for (boolean[] col : m.usable) java.util.Arrays.fill(col, false);
         m.usable[cols.length / 2][0] = true; // one spot
         return;
      }
      float[][] shifts = {{0F, 0F}, {0F, 0.6F}, {-0.5F, 0F}, {0.5F, 0F}, {-0.5F, 0.6F}, {0.5F, 0.6F}, {0F, 1.2F}, {-1F, 0.6F}, {1F, 0.6F}, {0F, 1.8F}};
      for (float[] sh : shifts) {
         m.colShift = sh[0];
         m.rowShift = sh[1];
         stationsAt(cell, m);
         if (count(m.usable) > 0) {
            if (sh[0] != 0F || sh[1] != 0F) Log.info(String.format(Locale.ROOT, "harness: mirror walk: %s at %d,%d,%d: stations moved %.1f along, %.1f out (furniture in front)", m.name, m.x, m.y, m.z, sh[0], sh[1]));
            return;
         }
      }
      m.colShift = 0F;
      m.rowShift = 0F;
      stationsAt(cell, m);
   }

   private static void stationsAt(IsoCell cell, M m) {
      int nc = cols.length, nr = rows.length;
      m.usable = new boolean[nc][nr];
      m.visited = new boolean[nc][nr];
      m.blockedAt = new boolean[nc][nr];
      m.faced = new boolean[nc][nr];
      m.turned = new boolean[nc][nr];
      IsoGridSquare front = cell.getGridSquare(m.x, m.y, m.z);
      for (int c = 0; c < nc; c++) {
         for (int r = 0; r < nr; r++) {
            float[] w = world(m, c, r);
            IsoGridSquare sq = cell.getGridSquare((int)Math.floor(w[0]), (int)Math.floor(w[1]), m.z);
            // the pathfinder's own test: the player's body fits there (furniture, walls, floor)
            m.usable[c][r] = sq != null && !sq.HasStairs() && Nav.canStandClear(w[0], w[1], m.z) && (front == null || front.getRoom() == null || sq.getRoom() == front.getRoom());
         }
      }
   }

   /** Station (c, r) of mirror m: c squares along the wall from the glass centre, rows[r] out from the glass. */
   private static float[] world(M m, int c, int r) {
      if (m.visit) return new float[] {m.vx, m.vy};
      float a = cols[c] + m.colShift, o = rows[r] + m.rowShift;
      return m.north ? new float[] {m.x + 0.5F + a, m.y + m.off + o} : new float[] {m.x + m.off + o, m.y + 0.5F + a};
   }

   private static float mirrorAngle(M m) {
      return m.north ? 270F : 180F;
   }

   /** Walking to the current mirror: its usable station nearest the glass centre (the middle column, nearest row first). */
   private static void startTravel() {
      M m = mirrors.get(mi);
      travelling = true;
      if (!pickStation(m, true)) {
         noStation(m);
         return;
      }
      goStation("to mirror " + (mi + 1) + " " + m.name + " at " + m.x + "," + m.y + "," + m.z + ", station");
   }

   /**
    * The next station of m: nearest the glass centre (first visit), else nearest the player, among the usable ones not
    * blocked and not visited (then visited ones: a move may lead back). False when none is left.
    */
   private static boolean pickStation(M m, boolean centre) {
      IsoPlayer p = IsoPlayer.getInstance();
      int bc = -1, br = -1;
      float best = Float.MAX_VALUE;
      for (int pass = 0; pass < 2 && bc < 0; pass++) {
         for (int c = 0; c < cols.length; c++) {
            for (int r = 0; r < rows.length; r++) {
               if (!m.usable[c][r] || m.blockedAt[c][r] || pass == 0 && m.visited[c][r]) continue;
               float[] w = world(m, c, r);
               float sc = centre ? Math.abs(cols[c]) * 2F + rows[r] : (float)Math.hypot(w[0] - p.getX(), w[1] - p.getY());
               if (sc < best) {
                  best = sc;
                  bc = c;
                  br = r;
               }
            }
         }
      }
      if (bc < 0) return false;
      col = bc;
      row = br;
      return true;
   }

   /** No station of the mirror can be stood on or reached: the walk moves on (Jev reads all_done_here). */
   private static void noStation(M m) {
      m.unreachable = true;
      arrived = true;
      travelling = false;
      Log.info(String.format(Locale.ROOT, "harness: mirror walk: mirror %d %s at %d,%d,%d: no station that can be reached, skipped epoch_ms=%d", mi + 1, m.name, m.x, m.y, m.z, System.currentTimeMillis()));
   }

   private static void goStation(String what) {
      M m = mirrors.get(mi);
      float[] w = world(m, col, row);
      arrived = false;
      Nav.go(IsoPlayer.getInstance(), w[0], w[1], m.z, "mirror " + (mi + 1) + " station " + col + "," + row);
      stationEvent(what);
   }

   // ---- per frame ----

   static void tick(IsoPlayer p, long nowNs) {
      if (finished || mirrors.isEmpty()) return;
      if (nowNs - lastCmdCheckNs >= 100_000_000L) {
         lastCmdCheckNs = nowNs;
         readCommand(nowNs);
      }
      if (selfTest && Nav.selfTest(p, nowNs)) return; // nav_selftest=true: the collision counter's control first
      if (selfTest && !travelling && !arrived && Nav.status() == Nav.IDLE) startTravel(); // the walk waited for the push
      Showcase.releaseKeys(); // no movement key may be down: it would cancel the game's walk
      Nav.frame(p, nowNs);
      M m = mirrors.get(mi);
      float[] w = world(m, col, row);
      float dist = (float)Math.hypot(w[0] - p.getX(), w[1] - p.getY());
      if (!command.equals("done") && (nowNs - startNs) / 1e9 > limitSecs + 5F) {
         // the director is told the time is up; without one (not started, crashed) the walk ends itself 5 s later instead of
         // holding the run until the harness gives up on it (2026-10-04: 8 s routes ran 200 s)
         Log.info(String.format(Locale.ROOT, "harness: mirror walk: time limit passed by 5 s without a done from the director, ending (%d commands)", commands));
         command = "done";
         Nav.cancel();
      }
      if (command.equals("done")) {
         if (!finished) {
            finished = true;
            int v = 0, u = 0;
            for (M k : mirrors) {
               v += count(k.visited);
               u += count(k.usable);
            }
            Log.info(String.format(Locale.ROOT, "harness: mirror walk: done at +%.1f s, mirror %d of %d, %d of %d stations visited, %d commands, %d doors unlocked epoch_ms=%d",
                  (nowNs - startNs) / 1e9, mi + 1, mirrors.size(), v, u, commands, doorsOpened, System.currentTimeMillis()));
            Log.info("harness: nav summary: " + Nav.summary());
         }
         return;
      }
      if (!arrived) {
         int st = Nav.status();
         if (st == Nav.ARRIVED) {
            arrived = true;
            travelling = false;
            m.visited[col][row] = true;
            stationEvent("arrived at mirror " + (mi + 1) + " station");
         } else if (st == Nav.FAILED) {
            m.blockedAt[col][row] = true;
            Log.info(String.format(Locale.ROOT, "harness: mirror walk: mirror %d station %d,%d not reached (at %.2f,%.2f,%.1f), another one", mi + 1, col, row, p.getX(), p.getY(), p.getZ()));
            if (pickStation(m, false)) goStation("to mirror " + (mi + 1) + " station");
            else noStation(m);
         }
      } else if (command.equals("circle") && lap != null) {
         int st = Nav.status();
         if (st == Nav.ARRIVED && lapIdx < lap.length - 1) {
            lapIdx++;
            Nav.go(p, lap[lapIdx][0], lap[lapIdx][1], m.z, "mirror " + (mi + 1) + " lap point " + lapIdx);
         } else if (st == Nav.ARRIVED || st == Nav.FAILED) {
            lap = null;
            m.circled = true;
            stationEvent((st == Nav.ARRIVED ? "circled" : "circle cut short") + " at mirror " + (mi + 1) + " station");
         }
      } else {
         switch (command) {
            case "face_mirror" -> {
               Nav.face(p, mirrorAngle(m));
               if (nowNs - actionNs > 800_000_000L && !m.faced[col][row]) {
                  m.faced[col][row] = true;
                  stationEvent("faced mirror " + (mi + 1) + " at station");
               }
            }
            case "turn_around" -> {
               Nav.face(p, (mirrorAngle(m) + 180F) % 360F);
               if (nowNs - actionNs > 800_000_000L && !m.turned[col][row]) {
                  m.turned[col][row] = true;
                  stationEvent("back to mirror " + (mi + 1) + " at station");
               }
            }
            default -> {
            }
         }
      }
      if (nowNs - lastLogNs >= 2_000_000_000L) {
         lastLogNs = nowNs;
         Log.info(String.format(Locale.ROOT, "harness: mirror walk: t=%.0fs %s mirror %d station %d,%d pos=%.2f,%.2f,%.1f dir=%.0f dist=%.2f",
               (nowNs - startNs) / 1e9, command, mi + 1, col, row, p.getX(), p.getY(), p.getZ(), p.getDirectionAngle(), dist));
      }
      if (nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, nowNs, dist);
      }
   }

   private static int count(boolean[][] a) {
      int n = 0;
      for (boolean[] r : a) for (boolean b : r) if (b) n++;
      return n;
   }

   private static int left(M m) {
      int n = 0;
      for (int c = 0; c < cols.length; c++) for (int r = 0; r < rows.length; r++) if (m.usable[c][r] && !m.blockedAt[c][r] && !m.visited[c][r]) n++;
      return n;
   }

   private static void stationEvent(String what) {
      M m = mirrors.get(mi);
      float[] w = world(m, col, row);
      Log.info(String.format(Locale.ROOT, "harness: mirror walk: %s %d,%d (along %.1f, out %.1f) at %.2f,%.2f,%d epoch_ms=%d (+%.1f s)", what, col, row,
            cols[col], rows[row], w[0], w[1], m.z, System.currentTimeMillis(), (System.nanoTime() - startNs) / 1e9));
   }

   /**
    * The station a move goes to: the nearest unvisited one that way (any row for left / right, any column for closer /
    * back: the state's unvisited_* flags count those), else the next usable one straight that way; null when none.
    */
   private static int[] step(M m, String dir) {
      int dc = dir.equals("left") ? -1 : dir.equals("right") ? 1 : 0;
      int dr = dir.equals("closer") ? -1 : dir.equals("back") ? 1 : 0;
      int[] best = null;
      int bestD = Integer.MAX_VALUE;
      for (int c = 0; c < cols.length; c++) {
         for (int r = 0; r < rows.length; r++) {
            if (!m.usable[c][r] || m.blockedAt[c][r] || m.visited[c][r]) continue;
            if (dc != 0 && Integer.signum(c - col) != dc || dr != 0 && Integer.signum(r - row) != dr) continue;
            int d = Math.abs(c - col) + Math.abs(r - row);
            if (d < bestD) {
               bestD = d;
               best = new int[] {c, r};
            }
         }
      }
      if (best != null) return best;
      int c = col + dc, r = row + dr;
      while (c >= 0 && r >= 0 && c < cols.length && r < rows.length) {
         if (m.usable[c][r] && !m.blockedAt[c][r]) return new int[] {c, r};
         c += dc;
         r += dr;
      }
      return null;
   }

   private static boolean unvisitedThatWay(M m, String dir) {
      int dc = dir.equals("left") ? -1 : dir.equals("right") ? 1 : 0;
      int dr = dir.equals("closer") ? -1 : dir.equals("back") ? 1 : 0;
      for (int c = 0; c < cols.length; c++) {
         for (int r = 0; r < rows.length; r++) {
            if (!m.usable[c][r] || m.visited[c][r] || m.blockedAt[c][r]) continue;
            if (dc != 0 && Integer.signum(c - col) == dc || dr != 0 && Integer.signum(r - row) == dr) return true;
         }
      }
      return false;
   }

   /**
    * A lap round the player's spot: 8 points on a circle (radius 0.6, else 0.4 squares) the player's body fits on, joined by
    * straight walks the pathfinder calls clear; false when neither radius fits (furniture or a wall too near).
    */
   private static boolean startLap(M m) {
      IsoPlayer p = IsoPlayer.getInstance();
      float cx = p.getX(), cy = p.getY();
      for (float r : new float[] {0.6F, 0.4F}) {
         float[][] pts = new float[10][];
         pts[9] = new float[] {cx, cy}; // back to the spot
         boolean ok = Nav.lineClear(cx + r, cy, cx, cy, m.z);
         for (int i = 0; i <= 8 && ok; i++) {
            double a = Math.PI * 2 * i / 8;
            pts[i] = new float[] {cx + r * (float)Math.cos(a), cy + r * (float)Math.sin(a)};
            ok = Nav.canStandClear(pts[i][0], pts[i][1], m.z) && (i == 0 ? Nav.lineClear(cx, cy, pts[0][0], pts[0][1], m.z) : Nav.lineClear(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1], m.z));
         }
         if (ok) {
            lap = pts;
            lapIdx = 0;
            Nav.go(p, pts[0][0], pts[0][1], m.z, "mirror " + (mi + 1) + " lap point 0 (radius " + r + ")");
            return true;
         }
      }
      return false;
   }

   // ---- the director (Jev) ----

   private static void readCommand(long nowNs) {
      try {
         if (cmdFile == null || !cmdFile.isFile()) return;
         String[] parts = java.nio.file.Files.readString(cmdFile.toPath()).trim().split("\\s+");
         if (parts.length < 2) return;
         int seq = Integer.parseInt(parts[0]);
         if (seq == commandSeq) return;
         if (parts[1].equals("done") && !command.equals("done")) Nav.cancel();
         commandSeq = seq;
         String c = parts[1];
         if (!java.util.Arrays.asList(ACTIONS).contains(c) || c.equals("hold")) return; // hold: keep doing the current action
         boolean move = c.equals("left") || c.equals("right") || c.equals("closer") || c.equals("back");
         if (!move && !c.equals("next_mirror") && c.equals(command)) return;
         if ((move || c.equals("next_mirror")) && !arrived) return; // still walking
         M m = mirrors.get(mi);
         // a started check runs to its end before another command replaces it (Jev alternated face_mirror / turn_around
         // every 0.3 s, neither reached its 1.5 s)
         if (!c.equals("done") && arrived && (command.equals("face_mirror") && !m.faced[col][row] || command.equals("turn_around") && !m.turned[col][row]
               || command.equals("circle") && !m.circled)) return;
         if (move) {
            int[] s = step(m, c);
            if (s == null) return;
            col = s[0];
            row = s[1];
            goStation("to mirror " + (mi + 1) + " station");
         } else if (c.equals("circle")) {
            if (!startLap(m)) {
               m.circled = true;
               stationEvent("circle skipped (no room for a lap) at mirror " + (mi + 1) + " station");
            }
         } else if (c.equals("next_mirror")) {
            if (mi + 1 >= mirrors.size()) return;
            mi++;
            startTravel();
         }
         Log.info("harness: mirror walk director: " + command + " -> " + c + " (#" + seq + ") epoch_ms=" + System.currentTimeMillis());
         commands++;
         command = c;
         actionNs = nowNs;
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   private static void writeState(IsoPlayer p, long nowNs, float dist) {
      M m = mirrors.get(mi);
      float secs = (nowNs - startNs) / 1e9F;
      StringBuilder grid = new StringBuilder("[");
      for (int r = 0; r < rows.length; r++) {
         grid.append(r == 0 ? "\"" : ",\"");
         for (int c = 0; c < cols.length; c++) {
            grid.append(!m.usable[c][r] || m.blockedAt[c][r] ? '#' : c == col && r == row ? '@' : m.visited[c][r] ? 'v' : '.');
         }
         grid.append('"');
      }
      grid.append(']');
      boolean checks = m.faced[col][row] && m.turned[col][row];
      boolean mirrorDone = m.unreachable || arrived && left(m) == 0 && checks && m.circled; // unreachable: skipped
      String json = String.format(Locale.ROOT,
            "{\"t\":%d,\"scene\":\"mirror\",\"seconds_since_start\":%.1f,\"time_limit_seconds\":%.0f,\"time_is_up\":%b,"
                  + "\"current_action\":\"%s\",\"player\":{\"arrived_at_station\":%b,\"walking_to_this_mirror\":%b,\"distance_to_station_tiles\":%.2f,\"moving\":%b},"
                  + "\"mirror\":{\"number\":%d,\"mirrors_total\":%d,\"mirrors_left_after_this\":%d,\"is_last_mirror\":%b,\"level\":%d,"
                  + "\"circled_at_this_mirror\":%b,\"stations_left_to_visit\":%d,\"all_done_here\":%b,"
                  + "\"map_rows_near_to_far_columns_left_to_right\":%s,\"map_legend\":\"@ here, v visited, . not visited yet, # blocked or no floor\"},"
                  + "\"station\":{\"faced_mirror_here\":%b,\"turned_back_here\":%b,\"checks_done_here\":%b,"
                  + "\"can_go_left\":%b,\"can_go_right\":%b,\"can_go_closer\":%b,\"can_go_back\":%b,"
                  + "\"unvisited_to_the_left\":%b,\"unvisited_to_the_right\":%b,\"unvisited_closer\":%b,\"unvisited_further_back\":%b}}",
            System.currentTimeMillis(), secs, limitSecs, secs >= limitSecs, command, arrived, travelling, dist, p.isPlayerMoving(),
            mi + 1, mirrors.size(), mirrors.size() - mi - 1, mi + 1 >= mirrors.size(), m.z,
            m.circled, left(m), mirrorDone, grid,
            m.faced[col][row], m.turned[col][row], checks,
            step(m, "left") != null, step(m, "right") != null, step(m, "closer") != null, step(m, "back") != null,
            unvisitedThatWay(m, "left"), unvisitedThatWay(m, "right"), unvisitedThatWay(m, "closer"), unvisitedThatWay(m, "back"));
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: mirror walk: state write failed: " + e);
      }
   }
}
