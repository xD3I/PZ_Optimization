package pzopt;

/** pzopt.LuaZombie: the per-zombie schedule (equal share per frame, the rate) and the collide-pair table. */
public class LuaZombieTest {
   public static void main(String[] args) {
      int n = 1000;
      LuaZombie.State[] zs = new LuaZombie.State[n];
      for (int i = 0; i < n; i++) {
         zs[i] = LuaZombie.State.forTest((float)((i * 0.6180339887498949) % 1.0));
      }
      float period = 60.0F / 15.0F; // 15 Hz in 60 Hz ticks
      float dt = 60.0F / 144.0F; // a 144 fps frame
      int min = Integer.MAX_VALUE, max = 0;
      long total = 0;
      int frames = 1440; // 10 s
      int[] calls = new int[n];
      for (int f = 0; f < frames; f++) {
         int due = 0;
         for (int i = 0; i < n; i++) {
            if (LuaZombie.advance(zs[i], dt, period)) {
               due++;
               calls[i]++;
            }
         }
         if (f > 0) {
            min = Math.min(min, due);
            max = Math.max(max, due);
         }
         total += due;
      }
      double perFrame = (double)n * dt / period;
      Check.check(Math.abs(total / (double)frames - perFrame) < 1.0, "the due zombies a frame average N dt / period: " + total / (double)frames + " vs " + perFrame);
      Check.check(max - min <= Math.max(3, (int)(perFrame * 0.15)), "every frame carries an equal share: min " + min + " max " + max + " of " + perFrame);
      for (int i = 0; i < n; i++) {
         Check.check(Math.abs(calls[i] - 150) <= 2, "each zombie runs at the rate: " + calls[i] + " calls in 10 s at 15 Hz");
      }
      // a zombie updated every third frame (tiered updates): the rate holds in real time
      LuaZombie.State t = LuaZombie.State.forTest(0.3F);
      int c = 0;
      for (int f = 0; f < frames; f += 3) {
         if (LuaZombie.advance(t, dt * 3, period)) {
            c++;
         }
      }
      Check.check(Math.abs(c - 150) <= 2, "tiered updates keep the rate: " + c);
      // a period below one frame: every update
      LuaZombie.State e = LuaZombie.State.forTest(0.5F);
      int ec = 0;
      for (int f = 0; f < 100; f++) {
         if (LuaZombie.advance(e, 1.0F, 0.5F)) {
            ec++;
         }
      }
      Check.check(ec == 100, "a rate above the frame rate runs every update: " + ec);
      // a new zombie off screen starts on its phase instead of at once: 1000 of them spread over the period
      int first = 0, firstMax = 0;
      LuaZombie.State[] off = new LuaZombie.State[1000];
      for (int i = 0; i < off.length; i++) {
         off[i] = LuaZombie.State.forTest((float)((i * 0.6180339887498949) % 1.0));
      }
      for (int f = 0; f < 20; f++) {
         int d = 0;
         for (LuaZombie.State z : off) {
            if (LuaZombie.advance(z, 1.0F, 10.0F, false)) {
               d++;
            }
         }
         if (f == 0) {
            first = d;
         }
         firstMax = Math.max(firstMax, d);
      }
      Check.check(first <= 150 && firstMax <= 150, "off-screen arrivals spread: " + first + " in the first frame, max " + firstMax);
      LuaZombie.State on = LuaZombie.State.forTest(0.9F);
      Check.check(LuaZombie.advance(on, 1.0F, 10.0F, true), "a new zombie on screen runs at once");
      // the budget's periods: scale 1 = the configured rates, a scale stretches the frame step (rate 0) or the period, the
      // near tier of the LOD never, and luaZombieMaxGapMs (1000 ms = 60 ticks) caps it
      LuaZombie.vHz = 0;
      LuaZombie.vLod = false;
      float[] p1 = LuaZombie.periods(1f, 2f, false);
      Check.check(p1[0] == 0f && p1[1] == 0f && p1[2] == 0f, "scale 1 without a rate: every update");
      float[] p4 = LuaZombie.periods(4f, 2f, false);
      Check.check(p4[0] == 8f && p4[2] == 8f, "scale 4 of a 2-tick frame: 8 ticks, " + p4[0]);
      float[] pBig = LuaZombie.periods(100f, 2f, false);
      Check.check(Math.abs(pBig[0] - Config.LUA_ZOMBIE_MAX_GAP_MS * 0.06F) < 1e-3, "capped at the max gap: " + pBig[0]);
      LuaZombie.vLod = true;
      float[] pl = LuaZombie.periods(4f, 2f, true);
      Check.check(pl[0] == 0f, "the near tier is never stretched: " + pl[0]);
      Check.check(Math.abs(pl[1] - 60f / Config.LUA_ZOMBIE_LOD_HZ1 * 4f) < 1e-3 || pl[1] == Config.LUA_ZOMBIE_MAX_GAP_MS * 0.06F, "on-screen tier stretched: " + pl[1]);
      float[] pl1 = LuaZombie.periods(1f, 2f, true);
      Check.check(Math.abs(pl1[2] - 60f / Config.LUA_ZOMBIE_LOD_HZ2) < 1e-3, "scale 1 = the tier's rate: " + pl1[2]);
      LuaZombie.vLod = false;
      // luaZombieCostShare: the interval multiple is the power of two nearest sqrt(cost ratio), 1..8, with hysteresis
      Check.check(LuaZombie.shareMult(1.0, 1) == 1, "the cheapest handler: x1");
      Check.check(LuaZombie.shareMult(16.0, 1) == 4, "16x the cost: x4, " + LuaZombie.shareMult(16.0, 1));
      Check.check(LuaZombie.shareMult(1000.0, 1) == 8, "capped at x8");
      Check.check(LuaZombie.shareMult(0.5, 2) == 1, "below the cheapest: x1, " + LuaZombie.shareMult(0.5, 2));
      Check.check(LuaZombie.shareMult(7.0, 4) == 4 && LuaZombie.shareMult(7.0, 2) == 2, "hysteresis: sqrt(7) = 2.6 keeps x2 or x4");
      System.out.println("LuaZombieTest ok");
   }
}
