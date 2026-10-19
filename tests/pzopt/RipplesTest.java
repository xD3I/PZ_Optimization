package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * pzopt.Ripples (2026-10-10): the iWave model's tables are sane (kernel, the impulse response's reach and fade), the
 * patched puddle unit and the three compute shaders compile (glslangValidator when it is on the PATH: NVIDIA accepted
 * a reserved word Mesa would not), and the world bins stay consistent through births, evictions and deaths.
 */
public class RipplesTest {
   public static void main(String[] args) throws Exception {
      System.setProperty("pzopt.ripplesMode", "world");
      RipplesModel.build();
      Check.check(Math.abs(RipplesModel.KERNEL[RipplesModel.P * (2 * RipplesModel.P + 1) + RipplesModel.P] - 1F) < 1e-3F, "kernel centre 1");
      Check.check(RipplesModel.reach0 > 0F && RipplesModel.reachV > 0.5F && RipplesModel.reachV < 3F, "reach " + RipplesModel.reach0 + " + " + RipplesModel.reachV + " sqrt(t)");
      double young = 0, old = 0;
      for (int i = 0; i < RipplesModel.LUT_W; i++) {
         float a = RipplesModel.LUT[4 * RipplesModel.LUT_W + i], b = RipplesModel.LUT[(RipplesModel.LUT_H - 1) * RipplesModel.LUT_W + i];
         Check.check(Float.isFinite(a) && Float.isFinite(b), "profile finite");
         young = Math.max(young, Math.abs(a));
         old = Math.max(old, Math.abs(b));
      }
      Check.check(young > 0.1 && old < young * 0.2, "the rings fade: young " + young + " last row " + old);
      for (int j = 0; j < RipplesModel.LUT_H; j++) {
         float t = (j + 0.5F) / RipplesModel.LUT_H * Ripples.LIFE;
         float reach = RipplesModel.reach0 + RipplesModel.reachV * (float)Math.sqrt(t);
         int i0 = (int)Math.ceil(reach / RipplesModel.RMAX * RipplesModel.LUT_W) + 1;
         for (int i = i0; i < RipplesModel.LUT_W; i++) {
            Check.check(RipplesModel.LUT[j * RipplesModel.LUT_W + i] == 0F, "profile zero past the cull radius, row " + j);
         }
      }

      // Rain crest shading must not give native-excluded puddle pixels nonzero alpha.
      // RainMaskGpuTest exercises the resulting HQ/MQ/LQ pixels with a real driver when opted in.
      for (boolean binding : new boolean[] {false, true}) {
         String rain = RainRipples.glsl(binding);
         Check.check(rain.contains("pzRainSlope(fc) * pzRainP.z * clamp(puddlesDirNone, 0.0, 1.0)"),
               "rain slope respects the native puddle exclusion mask before normals and crest shading");
      }
      // the shaders
      File jar = new File(zombie.core.Core.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path shaders = jar.toPath().getParent().resolve("media/shaders");
      String unit = Files.readString(shaders.resolve("puddles_common.frag.glsl"));
      String patched = Ripples.patchText(unit);
      Check.check(patched != null && patched.startsWith("#version 120"), "the puddle unit patches and stays GLSL 1.20 (Hdr / Ssr check the version line)");
      Check.check(patched.contains("rainWaves = pzRain(fragCoord)") && patched.contains("pzWakeG = pzWake(fragCoord"), "the rain and wake hooks are in");
      Check.check(patched.contains("vec3 reflection = fragColor.rgb;") && patched.contains("fragColor.a = mix(fragColor.a, 0.5+muddyPuddles*0.3, alphaPuddlesReflection);"),
            "Ssr's and Hdr's anchors are still there");
      if (onPath("glslangValidator")) {
         Path tmp = Files.createTempDirectory("pzrip");
         String vert = Wakes.patchVert(Files.readString(shaders.resolve("puddles_common.vert.glsl")));
         Check.check(vert != null, "the puddle vertex unit takes the wake gate");
         Path early = jar.toPath().getParent().resolve("media/shaders/pzopt_puddles_common.vert.glsl");
         if (Files.exists(early)) {
            Check.check(Wakes.patchVert(Files.readString(early)) != null, "puddleEarlyZ's vertex unit takes the wake gate too");
         }
         String[][] units = {{"puddle.frag", patched}, {"puddle.vert", vert + "\nvoid main(void) { puddlesMain(); }\n"}, {"field.comp", RipplesGpu.FIELD_CS}, {"step.comp", RipplesGpu.STEP_CS}, {"clear.comp", RipplesGpu.CLEAR_CS},
               {"rainfield.comp", RainRipples.FIELD_CS}, {"rainstep.comp", RainRipples.STEP_CS},
               {"wakestamp.comp", Wakes.STAMP_CS}, {"wakeclear.comp", Wakes.CLEAR_CS}, {"wakebin.comp", Wakes.BIN_CS}, {"wakegather.comp", Wakes.GATHER_CS}};
         for (String[] u : units) {
            Path f = tmp.resolve(u[0]);
            Files.writeString(f, u[1]);
            Process pr = new ProcessBuilder("glslangValidator", f.toString()).redirectErrorStream(true).start();
            String out = new String(pr.getInputStream().readAllBytes());
            Check.check(pr.waitFor() == 0, u[0] + " compiles:\n" + out);
         }
      }

      // the raindrop bakes (pzopt.RainRipples): Lagarde's drop texture and the flipbook hold rings, in reasonable time
      long tb = System.nanoTime();
      java.nio.ShortBuffer tex = RainRipples.buildTexture(1);
      long tv = System.nanoTime();
      java.nio.ByteBuffer vol = RainRipples.buildFlipbook(2);
      long te = System.nanoTime();
      int inDrop = 0;
      for (int i = 0; i < RainRipples.N * RainRipples.N; i++) {
         int ox = (tex.get(i * 4) & 65535) - 32768, oy = (tex.get(i * 4 + 1) & 65535) - 32768;
         if ((long)ox * ox + (long)oy * oy < 32767L * 32767L) {
            inDrop++;
         }
      }
      Check.check(inDrop > RainRipples.N * RainRipples.N / 5, "the drop texture covers " + inDrop + " texels");
      int moving = 0;
      for (int i = 0; i < vol.capacity(); i++) {
         if (Math.abs((vol.get(i) & 255) - 128) > 3) {
            moving++;
         }
      }
      Check.check(moving > vol.capacity() / 20, "the flipbook has slopes in " + moving + " of " + vol.capacity() + " bytes");
      System.out.printf("rain bakes: texture %.0f ms (%d drop texels), flipbook %.0f ms (%d sloped bytes) %s, sim g %.4f, reach %.3f, life %.3f%n",
            (tv - tb) / 1e6, inDrop, (te - tv) / 1e6, moving, RainRipples.status, RainRipples.SIM_G, RainRipples.REACH, RainRipples.LIFE);

      // world bins: rings born round two spots (one across the toroidal seam), all listed where they reach, gone after life
      Ripples.Frame fr = new Ripples.Frame();
      java.util.Random r = new java.util.Random(7);
      for (int k = 0; k < 600; k++) {
         Ripples.clock += 0.004;
         float x = k % 2 == 0 ? 100.3F + r.nextFloat() * 3F : 127.5F + r.nextFloat() * 1.5F; // 128 = the span: wraps
         float y = 200.7F + r.nextFloat() * 3F;
         RipplesWorld.birth(x, y, 0, 0.7F, k % 50 == 0, (float)Ripples.clock);
         if (k % 10 == 0) {
            RipplesWorld.tick();
            RipplesWorld.build(fr);
         }
      }
      Check.check(RipplesWorld.build(fr), "rings live");
      Check.check(RipplesWorld.evictions > 0, "busy tiles evicted their oldest ring (" + RipplesWorld.evictions + ")");
      Ripples.clock += Ripples.LIFE * Ripples.BIG + 1.0;
      RipplesWorld.tick();
      RipplesWorld.build(fr);
      Check.check(!fr.world.any, "every ring died");
      Check.check(RipplesWorld.listed() == 0, "no tile lists a ring (" + RipplesWorld.listed() + ")");
      Check.check(RipplesWorld.births == RipplesWorld.deaths, "births " + RipplesWorld.births + " = deaths " + RipplesWorld.deaths);
      System.out.println("RipplesTest ok: reach " + RipplesModel.reach0 + " + " + RipplesModel.reachV + " sqrt(t), " + RipplesWorld.stats());
   }

   static boolean onPath(String exe) {
      for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
         if (new File(d, exe).canExecute()) {
            return true;
         }
      }
      return false;
   }
}
